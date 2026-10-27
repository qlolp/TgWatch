"""Accept actual SDK signer reports without accepting a different signing identity."""
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from apk_verification import PRODUCTION_CERTIFICATE, verify_production_signature


class SignatureOutputTests(unittest.TestCase):
    def verify(self, output, returncode=0):
        result = subprocess.CompletedProcess([], returncode, stdout=output, stderr='verification failed')
        with mock.patch('apk_verification.subprocess.run', return_value=result):
            verify_production_signature(Path('release.apk'), 'apksigner')

    def test_build_tools37_single_signer_output_is_accepted(self):
        # Observed from official Build Tools37 verifying the published1.24.0 APK.
        self.verify('V2 Signer: certificate DN: CN=TG Watch, OU=Android, O=TgWatch\n'
                    f'V2 Signer: certificate SHA-256 digest: {PRODUCTION_CERTIFICATE}\n'
                    'V2 Signer: certificate SHA-1 digest: e23cab4b3bd29d4a468647afb592bb61bedf216b\n')

    def test_supported_single_signer_scheme_labels_preserve_the_pinned_identity(self):
        for label in ('Signer #1', 'V1 Signer:', 'V2 Signer:', 'V3.0 Signer:'):
            with self.subTest(label=label):
                self.verify(f'{label} certificate SHA-256 digest: {PRODUCTION_CERTIFICATE.upper()}\n')

    def test_extra_or_unknown_certificate_lines_are_rejected_even_after_a_valid_legacy_line(self):
        good = f'Signer #1 certificate SHA-256 digest: {PRODUCTION_CERTIFICATE}\n'
        for extra in (good, 'V2 Signer #2: certificate SHA-256 digest: ' + '0' * 64 + '\n',
                      'V2 Signer: certificate SHA-256 digest: ' + '0' * 64 + '\n',
                      'Unknown Signer: certificate SHA-256 digest: ' + PRODUCTION_CERTIFICATE + '\n'):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                self.verify(good + extra)

    def test_missing_malformed_foreign_or_multiple_signers_are_rejected(self):
        for report in ('', 'V2 Signer: certificate SHA-256 digest: bad\n',
                       'V2 Signer: certificate SHA-256 digest: ' + '0' * 64 + '\n',
                       f'V2 Signer #1: certificate SHA-256 digest: {PRODUCTION_CERTIFICATE}\n',
                       f'Unknown Signer: certificate SHA-256 digest: {PRODUCTION_CERTIFICATE}\n'):
            with self.subTest(report=report), self.assertRaises(ValueError):
                self.verify(report)

    def test_invalid_cryptographic_signature_is_never_accepted(self):
        with self.assertRaises(RuntimeError):
            self.verify(f'V2 Signer: certificate SHA-256 digest: {PRODUCTION_CERTIFICATE}\n', returncode=1)
