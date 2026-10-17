import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))


class CoreInventoryTests(unittest.TestCase):
    def module(self, name):
        spec = importlib.util.spec_from_file_location(name.replace('-', '_'), SCRIPTS / (name + '.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def fixture(self, directory):
        artifact = Path(directory) / 'core.jar'
        artifact.write_bytes(b'actual compiled local core fixture')
        maven = Path(directory) / 'stdlib.jar'
        maven.write_bytes(b'resolved Maven fixture')
        version = '1.18.0'
        core = dict(group='ru.tgwatch', name='probe-core', version=version, kind='project',
                    projectPath=':probe-core', buildPath=':', scopes=['releaseRuntimeClasspath'])
        dependency = dict(group='org.jetbrains.kotlin', name='kotlin-stdlib', version='2.4.21',
                          scopes=['releaseRuntimeClasspath'])
        inventory = dict(applicationVersion=version, applicationId='ru.tgwatch', artifacts=[
            {**core, 'type': 'jar', 'file': str(artifact)},
            {**dependency, 'type': 'jar', 'file': str(maven)}], runtimeGraph=[
                {'ref': 'ru.tgwatch:releaseRuntimeClasspath', 'dependsOn': ['project::probe-core:' + version]},
                {'ref': 'project::probe-core:' + version, 'dependsOn': ['org.jetbrains.kotlin:kotlin-stdlib:2.4.21']},
                {'ref': 'org.jetbrains.kotlin:kotlin-stdlib:2.4.21', 'dependsOn': []}])
        return inventory, [core, dependency]

    def test_local_core_is_hashed_and_preserves_transitive_edges(self):
        with tempfile.TemporaryDirectory() as directory:
            inventory, _ = self.fixture(directory)
            bom = self.module('generate-sbom').generate(inventory)
            core = next(row for row in bom['components'] if row['name'] == 'probe-core')
            self.assertTrue(core['purl'].startswith('pkg:generic/'))
            self.assertEqual(64, len(core['hashes'][0]['content']))
            properties = {row['name']: row['value'] for row in core['properties']}
            self.assertEqual(':probe-core', properties['tgwatch:projectPath'])
            edges = {row['ref']: row['dependsOn'] for row in bom['dependencies']}
            self.assertIn(core['bom-ref'], edges['ru.tgwatch:releaseRuntimeClasspath'])
            self.assertEqual(1, len(edges[core['bom-ref']]))

    def test_unrelated_build_project_and_version_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source, _ = self.fixture(directory)
            module = self.module('generate-sbom')
            for field, value in [('projectPath', ':unreviewed'), ('buildPath', ':included'), ('version', '9.9.9')]:
                forged = json.loads(json.dumps(source))
                forged['artifacts'][0][field] = value
                with self.assertRaises(ValueError): module.generate(forged)

    def test_osv_explicitly_accounts_for_first_party_core_without_maven_query(self):
        with tempfile.TemporaryDirectory() as directory:
            _, packages = self.fixture(directory)
            calls = []
            def query(request):
                calls.append(request)
                return {}
            result = self.module('dependency-audit').audit(packages, query, application_version='1.18.0')
            self.assertEqual(1, len(calls))
            core = next(row for row in result if row['name'] == 'probe-core')
            self.assertEqual('first_party_source', core['audit_method'])
            from audit_report import verify_osv
            report = dict(source='https://api.osv.dev/v1/query', checked_at='2026-10-17T00:00:00+00:00', packages=result)
            inventory = dict(applicationVersion='1.18.0', packages=packages)
            verify_osv(report, inventory)
            forged = json.loads(json.dumps(report))
            forged['packages'][0]['projectPath'] = ':unreviewed'
            with self.assertRaises(ValueError): verify_osv(forged, inventory)

    def test_trivy_maven_coverage_stays_complete_and_no_generic_dependency_is_exempted(self):
        with tempfile.TemporaryDirectory() as directory:
            inventory, _ = self.fixture(directory)
            bom = self.module('generate-sbom').generate(inventory)
            maven = next(row for row in bom['components'] if row['purl'].startswith('pkg:maven/'))
            scan = dict(SchemaVersion=2, ArtifactType='cyclonedx', ArtifactName='TgWatch.sbom.cdx.json',
                        CreatedAt='2026-10-17T00:00:00Z', Results=[dict(Packages=[
                            dict(Identifier=dict(PURL=maven['purl']), Version=maven['version'])])])
            from scan_report import verify_report
            self.assertEqual([], verify_report(scan, 'TgWatch.sbom.cdx.json', bom))
            empty = json.loads(json.dumps(scan))
            empty['Results'][0]['Packages'] = []
            with self.assertRaises(ValueError): verify_report(empty, 'TgWatch.sbom.cdx.json', bom)
            forged = json.loads(json.dumps(scan))
            forged['Results'][0]['Packages'].append(dict(Identifier=dict(PURL='pkg:generic/unreviewed/lib@9'), Version='9'))
            with self.assertRaises(ValueError): verify_report(forged, 'TgWatch.sbom.cdx.json', bom)
