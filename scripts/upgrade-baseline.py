#!/usr/bin/env python3
"""Select and verify the previous published release used by the signed-upgrade check.

The upgrade gate must cover the release an installed user would actually upgrade from, so
the baseline comes from the published inventory instead of a hard-coded tag. Only the
production certificate stays pinned: that identity has to survive every future release.
"""
import argparse
import hashlib
import importlib.util
import json
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
    assets = [asset for asset in release.get("assets", [])
              if isinstance(asset, dict) and asset.get("name") == name]
    return assets[0] if len(assets) == 1 else None


def select_baseline(releases, current_version, versioned_only=True):
    """Highest published release strictly older than the current one.

    Drafts and the legacy rolling "build" release cannot define an upgrade path; a missing
    predecessor is a real answer for the first release of a new application.
    """
    policy = _release_policy()
    current_code = policy.version_code(current_version)
    current_semver = policy.semver(current_version)
    best = None
    for release in releases:
        if not isinstance(release, dict):
            raise ValueError("Invalid release inventory entry")
        tag = release.get("tag_name")
        if release.get("draft") or (versioned_only and tag == "build"):
            continue
        if not isinstance(tag, str) or not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
            raise ValueError(f"Unrecognized release tag {tag!r}; refusing a silent choice")
        code = policy.tag_code(tag)
        version = tuple(map(int, tag[1:].split(".")))
        if code >= current_code or version >= current_semver:
            continue
        if apk_asset(release) is None:
            raise ValueError(f"Release {tag} has no single TgWatch.apk; refusing an upgrade baseline")
        if best is None or code > policy.tag_code(best["tag_name"]):
            best = release
    return best


def verify_checksum(apk: Path, checksum: Path):
    text = checksum.read_text().strip()
    match = re.fullmatch(r"([0-9a-fA-F]{64})(?:\s+\*?([^\s]+))?", text)
    if not match:
        raise ValueError("Checksum asset is not a single sha256 line")
    expected, recorded = match.group(1).lower(), match.group(2)
    if recorded and recorded != apk.name:
        raise ValueError(f"Checksum names {recorded}, not {apk.name}")
    actual = hashlib.sha256(apk.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError(f"APK checksum mismatch: expected {expected}, got {actual}")
    return actual


def download(repo, tag, asset, destination):
    result = subprocess.run(
        ["gh", "release", "download", tag, "--repo", repo, "--pattern", asset, "--dir", str(destination)],
        text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"gh release download {tag} failed: {result.stderr.strip()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--version-file", default=str(Path(__file__).resolve().parents[1] / "version.properties"))
    parser.add_argument("--out", default="previous")
    args = parser.parse_args()
    version = _release_policy().read_version(args.version_file)
    releases = _release_policy().published_releases(args.repo)
    baseline = select_baseline(releases, version)
    if baseline is None:
        print(f"No published predecessor of {version}; signed upgrade check skipped")
        return 0
    tag = baseline["tag_name"]
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    download(args.repo, tag, "TgWatch.apk", out)
    download(args.repo, tag, "TgWatch.apk.sha256", out)
    digest = verify_checksum(out / "TgWatch.apk", out / "TgWatch.apk.sha256")
    (out / "tag.txt").write_text(tag + "\n")
    print(f"Upgrade baseline {tag} verified: {digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
