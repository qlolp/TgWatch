"""Real encrypted keystore tests; only the unavailable platform Keychain boundary is replaced."""
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('signing',Path(__file__).with_name('signing-keychain.py'))
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class MemoryKeychain:
    def save(self,account,password): self.account,self.password=account,password
    def load(self,account):
        assert account==self.account
        return self.password


class ProtectTest(unittest.TestCase):
    def test_failed_readback_keeps_original_and_verified_success_keeps_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            store=Path(folder)/'tgwatch-release.p12'
            password=secrets.token_urlsafe(36)
            executable=str(Path(os.environ['JAVA_HOME'])/'bin/keytool') if os.environ.get('JAVA_HOME') else 'keytool'
            subprocess.run([executable,'-genkeypair','-keystore',str(store),'-storetype','PKCS12',
                '-storepass:env','FIXTURE_PASSWORD','-keypass:env','FIXTURE_PASSWORD','-alias','tgwatch',
                '-keyalg','RSA','-keysize','2048','-validity','1','-dname','CN=Temporary test'],
                env={**os.environ,'FIXTURE_PASSWORD':password},capture_output=True,check=True)
            sidecar=store.with_name('tgwatch-signing-password.txt'); sidecar.write_text(password)
            original=store.read_bytes(); before=signing.certificate(store,password)
            boundary=MemoryKeychain()
            def unavailable(_): raise RuntimeError('Readback denied')
            with self.assertRaises(RuntimeError): signing.protect(store,boundary,before,unavailable)
            self.assertTrue(sidecar.exists())
            self.assertEqual(original,store.read_bytes())
            with self.assertRaises(RuntimeError): signing.protect(store,boundary,'0'*64,unavailable)
            self.assertTrue(sidecar.exists())
            def verify(fingerprint):
                self.assertEqual(fingerprint,signing.certificate(store,boundary.load(str(store.resolve()))))
            signing.protect(store,boundary,before,verify)
            self.assertFalse(sidecar.exists())
            self.assertEqual(original,store.read_bytes())
            self.assertEqual(before,signing.certificate(store,boundary.load(str(store.resolve()))))


if __name__=='__main__': unittest.main()
