#!/usr/bin/env python3
"""Verify the highest stable published predecessor for an additional signed-upgrade gate.

The immutable 1.10.47 upgrade gate remains independent. An absent predecessor skips
only this additional path; invalid inventory, downloads or APK metadata fail closed.
"""
import argparse
import hashlib
import importlib.util
from pathlib import Path
import re
import subprocess
import sys


def _release_policy():
    spec = importlib.util.spec_from_file_location(
        "release_policy", Path(__file__).resolve().with_name("release-policy.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def apk_asset(release, name="TgWatch.apk"):
    assets = release.get("assets")
    if not isinstance(assets, list) or any(not isinstance(asset, dict) for asset in assets):
        raise ValueError("Invalid baseline release asset inventory")
    matches = [asset for asset in assets if asset.get("name") == name]
    if len(matches) != 1:
        raise ValueError(f"Release {release['tag_name']} requires exactly one {name}")
    asset = matches[0]
    if type(asset.get("size")) is not int or asset["size"] <= 0:
        raise ValueError(f"Invalid {name} asset size")
    if asset.get("state", "uploaded") != "uploaded":
        raise ValueError(f"Baseline {name} asset is not uploaded")
    digest = asset.get("digest")
    if digest is not None and (not isinstance(digest, str) or
                               not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", digest)):
        raise ValueError(f"Invalid {name} asset digest")
    return asset


def select_baseline(releases, current_version):
    """Pick the highest stable predecessor, then validate its complete asset metadata.

    Missing assets in the selected release must fail rather than select an older
    release. Older historical releases need not have modern checksum assets.
    """
    policy = _release_policy()
    current = policy.semver(current_version)
    current_code = policy.version_code(current_version)
    if not isinstance(releases, list):
        raise ValueError("Invalid release inventory")
    best, best_version = None, None
    for release in releases:
        if not isinstance(release, dict):
            raise ValueError("Invalid release inventory entry")
        if any(type(release.get(flag, False)) is not bool for flag in ("draft", "prerelease")):
            raise ValueError("Invalid release publication flags")
        tag = release.get("tag_name")
        if release.get("draft") or release.get("prerelease") or tag == "build":
            continue
        if not isinstance(tag, str):
            raise ValueError("Invalid stable release tag")
        code = policy.tag_code(tag)
        version = tuple(map(int, tag[1:].split(".")))
        if code >= current_code or version >= current:
            continue
        if best is None or version > best_version:
            best, best_version = release, version
    if best is not None:
        apk_asset(best)
        apk_asset(best, "TgWatch.apk.sha256")
    return best


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


def verify_download(path, asset):
    if path.stat().st_size != asset["size"]:
        raise ValueError(f"Downloaded {path.name} size differs from release metadata")
    digest = asset.get("digest")
    if digest is not None and hashlib.sha256(path.read_bytes()).hexdigest() != digest[7:].lower():
        raise ValueError(f"Downloaded {path.name} digest differs from release metadata")


def verify_apk_version(apk, tag, aapt2):
    result = subprocess.run([str(aapt2), "dump", "badging", str(apk)],
                            text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(f"APK could not be inspected: {result.stderr.strip()}")
    packages = re.findall(r"^package: (.+)$", result.stdout, re.M)
    if len(packages) != 1:
        raise ValueError("APK has no single package metadata line")
    fields = re.findall(r"([A-Za-z]+)='([^']*)'", packages[0])
    values = {}
    for key in ("name", "versionName", "versionCode"):
        matches = [value for field, value in fields if field == key]
        if len(matches) != 1:
            raise ValueError(f"APK requires exactly one {key} field")
        values[key] = matches[0]
    if values["name"] != "ru.tgwatch":
        raise ValueError("APK package does not match ru.tgwatch")
    if values["versionName"] != tag[1:]:
        raise ValueError(f"APK versionName does not match baseline tag {tag}")
    if values["versionCode"] != str(_release_policy().tag_code(tag)):
        raise ValueError(f"APK versionCode does not match baseline tag {tag}")


def download(repo, tag, asset, destination):
    result = subprocess.run(
        ["gh", "release", "download", tag, "--repo", repo, "--pattern", asset, "--dir", str(destination)],
        text=True, capture_output=True, timeout=180)
    if result.returncode:
        raise RuntimeError(f"gh release download {tag} failed: {result.stderr.strip()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--version-file", default=str(Path(__file__).resolve().parents[1] / "version.properties"))
    parser.add_argument("--out", default="previous-latest")
    parser.add_argument("--aapt2", default="aapt2")
    parser.add_argument("--github-output", help="Append available=true/false after a successful check")
    args = parser.parse_args()
    try:
        out = Path(args.out)
        if out.exists() and (not out.is_dir() or any(out.iterdir())):
            raise ValueError("Baseline output directory must be empty")
        policy = _release_policy()
        version = policy.read_version(args.version_file)
        baseline = select_baseline(policy.published_releases(args.repo), version)
        if baseline is None:
            message = f"No published predecessor of {version}; additional upgrade path skipped"
        else:
            tag = baseline["tag_name"]
            out.mkdir(parents=True, exist_ok=True)
            for name in ("TgWatch.apk", "TgWatch.apk.sha256"):
                download(args.repo, tag, name, out)
                verify_download(out / name, apk_asset(baseline, name))
            digest = verify_checksum(out / "TgWatch.apk", out / "TgWatch.apk.sha256")
            verify_apk_version(out / "TgWatch.apk", tag, args.aapt2)
            (out / "tag.txt").write_text(tag + "\n")
            message = f"Upgrade baseline {tag} verified: {digest}"
        if args.github_output:
            with Path(args.github_output).open("a") as output:
                output.write(f"available={str(baseline is not None).lower()}\n")
        print(message)
        return 0
    except (ValueError, RuntimeError, OSError, KeyError, TypeError, UnicodeError,
            subprocess.TimeoutExpired) as error:
        print(f"Upgrade baseline refused: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
