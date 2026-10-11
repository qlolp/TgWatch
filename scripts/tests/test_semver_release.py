"""Deterministic source versions and fail-closed publication preflight."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('release_policy', SCRIPTS / 'release-policy.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class SemVerTests(unittest.TestCase):
    def test_source_version_maps_to_android_code(self):
        self.assertEqual(1013000, policy.version_code('1.13.0'))
        self.assertEqual(2146999999, policy.version_code('2146.999.999'))
        self.assertEqual(2147483647, policy.version_code('2147.483.647'))

    def test_invalid_or_ambiguous_versions_fail(self):
        for value in ('', '1.13', 'v1.13.0', '01.13.0', '1.013.0', '1.13.00',
                      '1.13.0-beta', '1.13.0+build', '1.1000.0', '1.0.1000',
                      '0.0.0', '2147.483.648', '999999999999999999.0.0', ' 1.13.0'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                policy.version_code(value)

    def test_version_properties_rejects_unknown_duplicate_or_missing_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'version.properties'
            for value in ('version=1.13.0\nversionCode=5\n', 'version=1.13.0\nversion=1.13.0\n', ''):
                path.write_text(value)
                with self.subTest(value=value), self.assertRaises(ValueError):
                    policy.read_version(path)
            path.write_text('# shared source\nversion=1.13.0\n')
            self.assertEqual('1.13.0', policy.read_version(path))


class PreflightTests(unittest.TestCase):
    def check(self, ref='refs/tags/v1.13.0', releases=None, latest=None, status='ahead', tag_sha='a' * 40):
        def api(endpoint, *args):
            if '/releases?' in endpoint: return releases or []
            if '/compare/' in endpoint: return {'status': status}
            raise AssertionError(endpoint)
        with mock.patch.object(policy, 'api', side_effect=api), \
             mock.patch.object(policy, 'optional_api', return_value=latest), \
             mock.patch.object(policy, 'commit_for_tag', return_value=tag_sha), \
             mock.patch.object(policy, 'gh') as gh:
            result = policy.preflight('owner/repo', '1.13.0', 'a' * 40, ref)
            gh.assert_not_called()
            return result

    def test_exact_tag_and_manual_main_are_allowed(self):
        self.assertEqual('v1.13.0', self.check())
        self.assertEqual('v1.13.0', self.check(status='identical'))
        self.assertEqual('v1.13.0', self.check(ref='refs/heads/main', tag_sha=None))

    def test_mismatched_tag_or_branch_fails(self):
        for ref in ('refs/tags/v1.13.1', 'refs/tags/1.13.0', 'refs/heads/feature', 'v1.13.0'):
            with self.subTest(ref=ref), self.assertRaises(ValueError): self.check(ref=ref)

    def test_off_main_commit_fails(self):
        for status in ('behind', 'diverged', '', None):
            with self.subTest(status=status), self.assertRaises(ValueError): self.check(status=status)

    def test_existing_release_is_never_mutated(self):
        for draft in (True, False):
            with self.subTest(draft=draft), self.assertRaisesRegex(ValueError, 'Existing release'):
                self.check(releases=[{'tag_name': 'v1.13.0', 'draft': draft}])

    def test_non_monotonic_and_unknown_releases_fail(self):
        for tag in ('v1.14.0', 'v1.13.1', 'unknown'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.check(releases=[{'tag_name': tag}])
        with self.assertRaises(ValueError): self.check(latest={'tag_name': 'v1.14.0'})

    def test_published_legacy_code_is_respected(self):
        self.assertEqual(147, policy.tag_code('v1.10.47'))
        self.assertEqual('v1.13.0', self.check(releases=[{'tag_name': 'v1.10.47'}]))
        with self.assertRaises(ValueError): self.check(releases=[{'tag_name': 'v1.10.2000000'}])

    def test_tag_must_resolve_to_exact_commit(self):
        with self.assertRaises(ValueError): self.check(tag_sha='b' * 40)
        with self.assertRaises(ValueError): self.check(tag_sha=None)

class WorkflowPreflightTests(unittest.TestCase):
    def test_tag_trigger_and_preflight_before_credentials(self):
        import yaml
        root = SCRIPTS.parent
        workflow = yaml.safe_load((root / '.github/workflows/build-apk.yml').read_text())
        triggers = workflow.get('on', workflow.get(True))
        self.assertEqual(['v*'], triggers['push']['tags'])
        release = workflow['jobs']['release']
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", release['if'])
        steps = release['steps']
        preflight = next(i for i, step in enumerate(steps) if '--preflight' in step.get('run', ''))
        signing = next(i for i, step in enumerate(steps) if 'TGWATCH_KEYSTORE_BASE64' in step.get('env', {}))
        audits = next(i for i, step in enumerate(steps) if 'verify-audit-evidence.py' in step.get('run', ''))
        self.assertLess(preflight, signing)
        self.assertLess(audits, signing)
        self.assertIn('signed-upgrade-smoke.sh', str(steps))
        self.assertNotIn('GITHUB_RUN_NUMBER', str(steps))
        self.assertIn('detekt', str(workflow['jobs']['build']['steps']))

    def test_fdroid_candidate_stays_disabled_and_matches_source(self):
        import yaml
        root = SCRIPTS.parent
        metadata = yaml.safe_load((root / 'metadata/ru.tgwatch.yml').read_text())
        build = metadata['Builds'][0]
        source = policy.read_version(root / 'version.properties')
        self.assertEqual(source, build['versionName'])
        self.assertEqual(policy.version_code(source), build['versionCode'])
        self.assertEqual('v' + source, build['commit'])
        self.assertTrue(build['disable'])
        self.assertEqual(['yes'], build['gradle'])
        self.assertEqual('None', metadata['AutoUpdateMode'])
        self.assertNotIn('Binaries', metadata)

    def test_fastlane_store_text_is_bounded_and_has_changelog_for_source_code(self):
        import yaml
        root = SCRIPTS.parent
        store = root / 'fastlane/metadata/android'
        code = policy.version_code(policy.read_version(root / 'version.properties'))
        metadata = yaml.safe_load((root / 'metadata/ru.tgwatch.yml').read_text())
        limits = {'title.txt': 50, 'short_description.txt': 80, 'full_description.txt': 4000,
                  f'changelogs/{code}.txt': 500}
        for locale in ('en-US', 'ru-RU'):
            for name, limit in limits.items():
                with self.subTest(locale=locale, name=name):
                    text = (store / locale / name).read_text(encoding='utf-8').strip()
                    self.assertTrue(0 < len(text) <= limit)
        short = (store / 'en-US/short_description.txt').read_text(encoding='utf-8').strip()
        self.assertEqual(metadata['Summary'], short)

class PaginationTests(unittest.TestCase):
    def test_full_page_is_followed_until_short_page(self):
        first = [{'tag_name': f'v1.13.{n}'} for n in range(100)]
        last = [{'tag_name': 'v1.14.0'}]
        with mock.patch.object(policy, 'api', side_effect=[first, last]) as api:
            self.assertEqual(first + last, policy.published_releases('owner/repo'))
            self.assertEqual([
                mock.call('repos/owner/repo/releases?per_page=100&page=1'),
                mock.call('repos/owner/repo/releases?per_page=100&page=2')], api.call_args_list)

    def test_invalid_page_or_release_shape_fails_closed(self):
        for page in ({'message': 'API error'}, None, [None], [{'tag_name': 1}], [{}]):
            with self.subTest(page=page), mock.patch.object(policy, 'api', return_value=page), \
                 self.assertRaises(ValueError): policy.published_releases('owner/repo')

    def test_cap_refuses_incomplete_release_inventory(self):
        page = [{'tag_name': 'v1.10.47'}] * 100
        with mock.patch.object(policy, 'MAX_RELEASE_PAGES', 2), \
             mock.patch.object(policy, 'api', return_value=page) as api, \
             self.assertRaisesRegex(ValueError, 'pagination limit'):
            policy.published_releases('owner/repo')
        self.assertEqual(2, api.call_count)


class LegacyBuildTests(unittest.TestCase):
    def release(self):
        return {'id': 408759859, 'tag_name': 'build', 'draft': False, 'assets': [{
            'id': 627276037, 'name': 'TgWatch.apk', 'size': 905179,
            'digest': 'sha256:0a5f4a61d30bb18f2d9f00117630657d5a0e790c3e593a18560ffcd927d53368'}]}

    def test_verified_historical_build_uses_actual_apk_code(self):
        with mock.patch.object(policy, 'commit_for_tag', return_value='fef8aef77da5a8b5d2363ef2167deab3a3d4d4d2'):
            self.assertEqual((7, (1, 6, 0)), policy.legacy_build_order('qlolp/TgWatch', self.release()))

    def test_changed_identity_is_refused(self):
        for field, value in (('id', 1), ('draft', True), ('assets', [])):
            release = self.release()
            release[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                policy.legacy_build_order('qlolp/TgWatch', release)
        for field, value in (('id', 1), ('name', 'changed.apk'), ('size', 1), ('digest', 'sha256:' + '0' * 64)):
            release = self.release()
            release['assets'][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                policy.legacy_build_order('qlolp/TgWatch', release)
        with self.assertRaises(ValueError): policy.legacy_build_order('fork/TgWatch', self.release())
        with mock.patch.object(policy, 'commit_for_tag', return_value='a' * 40), self.assertRaises(ValueError):
            policy.legacy_build_order('qlolp/TgWatch', self.release())


if __name__ == '__main__': unittest.main()
