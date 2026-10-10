import importlib.util
import pathlib
import unittest
from unittest import mock
import json
import tempfile


class AuditTest(unittest.TestCase):
    def test_exact_versions_findings_withdrawals_and_failure_are_not_silent(self):
        spec = importlib.util.spec_from_file_location('audit', pathlib.Path(__file__).with_name('dependency-audit.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        package = {'group': 'example', 'name': 'library', 'version': '1.2.3', 'scopes': ['releaseRuntimeClasspath']}
        findings = module.audit([package], lambda query: {'vulns': [{'id': 'OSV-123'}]})
        self.assertEqual(['OSV-123'], findings[0]['vulnerabilities'])
        self.assertEqual([], module.audit([package], lambda query: {'vulns': [{'id': 'OSV-old', 'withdrawn': '2020-01-01T00:00:00Z'}]})[0]['vulnerabilities'])
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

    def test_malformed_optional_fields_fail_the_audit_and_cli(self):
        spec = importlib.util.spec_from_file_location('audit', pathlib.Path(__file__).with_name('dependency-audit.py'))
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        package = {'group':'example','name':'library','version':'1.2.3','scopes':['releaseRuntimeClasspath']}
        responses = [{'vulns':[{'id':'OSV-fixture','withdrawn':value}]} for value in
            (True,False,{},[],0,'','not-a-date','2020-01-01')]
        responses += [{'next_page_token':value} for value in (0,False,[],{},None)]
        for response in responses:
            with self.subTest(response=response), self.assertRaises(ValueError):
                module.audit([package],lambda _:response)
        with tempfile.TemporaryDirectory() as folder:
            source = pathlib.Path(folder)/'inventory.json'; destination = pathlib.Path(folder)/'audit.json'
            source.write_text(json.dumps({'packages':[package]}))
            original_audit = module.audit
            with mock.patch.object(module.sys,'argv',['audit',str(source),str(destination)]), \
                 mock.patch.object(module,'audit',side_effect=lambda packages:original_audit(packages,lambda _:responses[0])):
                self.assertEqual(2,module.main())
            self.assertEqual('audit_failed',json.loads(destination.read_text())['status'])


if __name__ == '__main__':
    unittest.main()
