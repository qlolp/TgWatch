"""Shared checks for downloaded and locally built production APKs."""
import hashlib
from pathlib import Path
import re
import subprocess
from release_apps import application

PRODUCTION_CERTIFICATE = "ab74727a44f59684045e7db4c820be309f886433a18a3c06f3439417f3acbef1"


def verify_checksum(apk: Path, checksum: Path):
    match = re.fullmatch(r"([0-9a-fA-F]{64})(?:\s+\*?([^\s]+))?", checksum.read_text().strip())
    if not match:
        raise ValueError("Checksum asset is not a single SHA256 line")
    expected, recorded = match.group(1).lower(), match.group(2)
    if recorded and recorded != apk.name:
        raise ValueError(f"Checksum names {recorded}, not {apk.name}")
    actual = hashlib.sha256(apk.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError(f"APK checksum mismatch: expected {expected}, got {actual}")
    return actual


def verify_apk_identity(apk, version, code, aapt2, application_id=None):
    app = application(application_id or 'ru.tgwatch')
    result = subprocess.run([str(aapt2), "dump", "badging", str(apk)],
                            text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(f"APK could not be inspected: {result.stderr.strip()}")
    packages = re.findall(r"^package: (.+)$", result.stdout, re.M)
    if len(packages) != 1:
        raise ValueError("APK has no single package metadata line")
    fields = re.findall(r"([A-Za-z]+)='([^']*)'", packages[0])
    expected = {"name": application_id or "ru.tgwatch", "versionName": version, "versionCode": str(code)}
    for key, value in expected.items():
        matches = [entry for field, entry in fields if field == key]
        if matches != [value]:
            label = "package name" if key == "name" else key
            raise ValueError(f"APK {label} does not match expected {value}")
    if application_id is not None and re.findall(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'$", result.stdout, re.M) != [str(app['min_sdk'])]:
        raise ValueError('APK minSdk differs from the approved application floor')


def verify_certificate_report(output):
    # SDK37 labels a single signer by scheme; older tools use "Signer #1".
    # Collect every certificate line first: unknown/additional signers must fail closed.
    lines = [line for line in output.splitlines() if "certificate SHA-256 digest:" in line]
    match = re.fullmatch(
        r"(?:Signer #1|V[12] Signer:|V3\.0 Signer:) certificate SHA-256 digest: ([0-9a-fA-F]{64})",
        lines[0]) if len(lines) == 1 else None
    if match is None or match[1].lower() != PRODUCTION_CERTIFICATE:
        raise ValueError("APK production signing identity mismatch")
    return match[1].lower()


def verify_production_signature(apk, apksigner):
    result = subprocess.run([str(apksigner), "verify", "--print-certs", str(apk)],
                            text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(f"APK signature verification failed: {result.stderr.strip()}")
    verify_certificate_report(result.stdout)
