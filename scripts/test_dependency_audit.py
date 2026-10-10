import importlib.util
import pathlib
import unittest


class AuditTest(unittest.TestCase):
    def test_exact_versions_findings_withdrawals_and_failure_are_not_silent(self):
        spec = importlib.util.spec_from_file_location('audit', pathlib.Path(__file__).with_name('dependency-audit.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        package = {'group': 'example', 'name': 'library', 'version': '1.2.3', 'scopes': ['releaseRuntimeClasspath']}
        findings = module.audit([package], lambda query: {'vulns': [{'id': 'OSV-123'}]})
        self.assertEqual(['OSV-123'], findings[0]['vulnerabilities'])
        self.assertEqual([], module.audit([package], lambda query: {'vulns': [{'id': 'OSV-old', 'withdrawn': '2020-01-01'}]})[0]['vulnerabilities'])
        for response in [{'vulns': None}, {'error': 'unavailable'}, {'vulns': [{}]}]:
            with self.assertRaises(ValueError):
                module.audit([package], lambda query: response)
        with self.assertRaises(ValueError):
            module.audit([], lambda query: {})
        with self.assertRaises(OSError):
            module.audit([package], lambda query: (_ for _ in ()).throw(OSError('offline')))
        calls = []
        def pages(query):
            calls.append(query)
            return {'vulns': [{'id': 'OSV-later'}]} if 'page_token' in query else {'next_page_token': 'next'}
        self.assertEqual(['OSV-later'], module.audit([package], pages)[0]['vulnerabilities'])
        self.assertEqual('1.2.3', calls[0]['version'])
        self.assertEqual('example:library', calls[0]['package']['name'])


if __name__ == '__main__':
    unittest.main()
