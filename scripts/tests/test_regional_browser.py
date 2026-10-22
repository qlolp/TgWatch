"""Execute the actual dependency-free browser helper with Node and a minimal visible DOM boundary."""
import json
from pathlib import Path
import shutil
import importlib.util
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RegionalBrowserTests(unittest.TestCase):
    def test_bookmarklet_installer_contains_exact_current_source(self):
        path = ROOT / 'scripts/build-regional-bookmarklet.py'
        spec = importlib.util.spec_from_file_location('build_bookmarklet', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(module.render(), (ROOT / 'tools/regional-browser-bookmarklet.html').read_text())

    def test_visible_error_categories_and_no_feature_success_inference(self):
        self.assertIsNotNone(shutil.which('node'), 'Browser-helper tests require Node (available in CI)')
        cases = [dict(text='Not available in your country', headings=['Not available in your country'],
                      expected='REGION_RESTRICTED'),
                 dict(text='FAQ: why is this service not available in your country?', headings=['FAQ'], expected='UNKNOWN'),
                 dict(text='Verify you are human', headings=[], expected='CHALLENGE'),
                 dict(text='Welcome. Sign in', headings=['Welcome'], expected='UNKNOWN'),
                 dict(text='Sign in to continue', headings=['Sign in to continue'], expected='AUTH_REQUIRED'),
                 dict(text='Working chat conversation', headings=[], expected='UNKNOWN')]
        cases.extend([
            dict(text='How to fix “not available in your country”',
                 headings=['How to fix “not available in your country”'], expected='UNKNOWN'),
            dict(text='Gemini isn’t available in your country yet',
                 headings=['Gemini isn’t available in your country yet'], expected='REGION_RESTRICTED'),
        ])
        script = """
const helper = require(process.argv[1]);
const cases = JSON.parse(process.argv[2]);
process.stdout.write(JSON.stringify(cases.map(c => helper.classifyVisible(c.text,c.headings))));
"""
        result = subprocess.run(['node', '-e', script, str(ROOT / 'tools/regional-browser-check.js'), json.dumps(cases)],
                                text=True, capture_output=True, check=True, timeout=10)
        self.assertEqual([case['expected'] for case in cases], json.loads(result.stdout))

    def test_actual_dom_boundary_ignores_hidden_headings_and_unknown_hosts(self):
        script = """
const helper = require(process.argv[1]);
const doc = {body:{innerText:'Welcome'},querySelectorAll(){return [
 {innerText:'Not available in your country',getClientRects(){return []}}
]}};
const win = {location:{hostname:'gemini.google.com',protocol:'https:'},
 getComputedStyle(){return {display:'none',visibility:'visible'}}};
const normal = helper.run(doc,win);
win.location.hostname = 'gemini.google.com.attacker.example';
process.stdout.write(JSON.stringify([normal,helper.run(doc,win)]));
"""
        result = subprocess.run(['node', '-e', script, str(ROOT / 'tools/regional-browser-check.js')],
                                text=True, capture_output=True, check=True, timeout=10)
        reports = json.loads(result.stdout)
        self.assertEqual(['UNKNOWN', 'UNKNOWN'], [report['status'] for report in reports])
        self.assertEqual({'source', 'service', 'status'}, set(reports[0]))
        self.assertNotIn('Welcome', result.stdout)

    def test_conversation_and_quotation_headings_are_not_service_errors(self):
        script = """
const helper = require(process.argv[1]);
const doc = {body:{innerText:'Not available in your country'},querySelectorAll(){return [
 {tagName:'H1',innerText:'Not available in your country',getClientRects(){return [1]},
  closest(){return {tagName:'BLOCKQUOTE'}}}
]}};
const win = {location:{hostname:'chatgpt.com',protocol:'https:'},
 getComputedStyle(){return {display:'block',visibility:'visible',opacity:'1'}}};
process.stdout.write(JSON.stringify(helper.run(doc,win)));
"""
        result = subprocess.run(['node', '-e', script, str(ROOT / 'tools/regional-browser-check.js')],
                                text=True, capture_output=True, check=True, timeout=10)
        self.assertEqual('UNKNOWN', json.loads(result.stdout)['status'])


if __name__ == '__main__':
    unittest.main()
