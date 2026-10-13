#!/usr/bin/env python3
"""Fail-closed publication policy. CI serializes this command across all releases.

Published tags/assets are immutable. Existing releases, including drafts, are refused without mutation.
Historical scan evidence stays unchanged. Version tags must match source,
be reachable from main, and advance both SemVer and Android installation order.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

from scan_report import verify_report
from audit_report import verify_osv, verify_runtime_inventory
from jsonschema.exceptions import ValidationError


def semver(version):
    if not re.fullmatch(r"(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})", version):
        raise ValueError("Version must be canonical major.minor.patch, with minor/patch in 0..999")
    parts = tuple(map(int, version.split(".")))
    code = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
    if not 0 < code <= 2147483647:
        raise ValueError("SemVer exceeds Android's positive signed-int versionCode range")
    return parts


def version_code(version):
    major, minor, patch = semver(version)
    return major * 1_000_000 + minor * 1_000 + patch


def read_version(path):
    entries = []
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if not re.fullmatch(r"version=[^\s]+", line):
            raise ValueError("version.properties allows only the version key")
        entries.append(line.split("=", 1)[1])
    if len(entries) != 1:
        raise ValueError("version.properties requires exactly one version key")
    version_code(entries[0])
    return entries[0]


def gh(*args):
    result = subprocess.run(["gh", *args], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"gh {args[0]} failed: {result.stderr.strip()}")
    return result.stdout


def api(endpoint, *options):
    return json.loads(gh("api", endpoint, *options))


def optional_api(endpoint):
    result = subprocess.run(["gh", "api", endpoint],
                            text=True, capture_output=True)
    if result.returncode:
        # A missing latest is normal for the first release; auth/network errors are not.
        if "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(f"Could not inspect {endpoint}: {result.stderr.strip()}")
    return json.loads(result.stdout)


def tag_code(tag):
    # Historical releases used patch = CI run and code = run + 100.
    # Preserve their actual upgrade ordering instead of reinterpreting old APKs.
    match = re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag)
    if not match:
        raise ValueError(f"Cannot determine Android code for published tag {tag!r}")
    major, minor, patch = map(int, match.groups())
    if major == 1 and minor <= 12:
        if not 0 < patch <= 2147483547:
            raise ValueError(f"Unknown historical release versionCode: {tag}")
        return patch + 100
    return version_code(tag[1:])


def legacy_build_order(repo, release):
    # The immutable pre-SemVer debug APK was downloaded, hashed and inspected:
    # versionName 1.6, versionCode 7. It cannot define production signing identity.
    expected_digest = "sha256:0a5f4a61d30bb18f2d9f00117630657d5a0e790c3e593a18560ffcd927d53368"
    assets = release.get("assets", [])
    if not isinstance(assets, list) or any(not isinstance(asset, dict) for asset in assets):
        raise ValueError("Invalid legacy build asset inventory")
    matches = [asset for asset in assets if asset.get("name") == "TgWatch.apk"]
    apk = matches[0] if len(matches) == 1 else None
    if release.get("tag_name") != "build" or repo != "qlolp/TgWatch" or release.get("id") != 408759859 or release.get("draft") or \
            not apk or apk.get("id") != 627276037 or apk.get("size") != 905179 or \
            apk.get("digest") != expected_digest:
        raise ValueError("Unrecognized or changed legacy build release; refusing publication")
    if commit_for_tag(repo, "build") != "fef8aef77da5a8b5d2363ef2167deab3a3d4d4d2":
        raise ValueError("Legacy build tag changed; refusing publication")
    return 7, (1, 6, 0)


MAX_RELEASE_PAGES = 100


def published_releases(repo):
    releases = []
    for page in range(1, MAX_RELEASE_PAGES + 1):
        entries = api(f"repos/{repo}/releases?per_page=100&page={page}")
        if not isinstance(entries, list) or len(entries) > 100 or any(
                not isinstance(entry, dict) or not isinstance(entry.get("tag_name"), str)
                for entry in entries):
            raise ValueError("Invalid release inventory page; refusing incomplete evidence")
        releases.extend(entries)
        if len(entries) < 100:
            return releases
    raise ValueError("Release inventory exceeded pagination limit; refusing incomplete evidence")


def preflight(repo, version, sha, ref):
    code = version_code(version)
    if not re.fullmatch(r"[0-9a-fA-F]{40}", sha):
        raise ValueError("Publication requires a full commit SHA")
    tag = f"v{version}"
    if ref not in (f"refs/tags/{tag}", "refs/heads/main"):
        raise ValueError("Release ref must be the exact source version tag or main")
    comparison = api(f"repos/{repo}/compare/{sha}...main")
    if comparison.get("status") not in ("ahead", "identical"):
        raise ValueError("Release commit is not reachable from main")
    tag_commit = commit_for_tag(repo, tag, allow_missing=True)
    if tag_commit is not None and tag_commit != sha:
        raise ValueError(f"Existing tag {tag} points to another commit; it remains unchanged")
    if ref.startswith("refs/tags/") and tag_commit is None:
        raise ValueError("Triggered release tag is missing")
    releases = published_releases(repo)
    if any(release["tag_name"] == tag for release in releases):
        raise ValueError(f"Existing release {tag} remains immutable; publication refused")
    latest = optional_api(f"repos/{repo}/releases/latest")
    for release in releases + ([latest] if latest else []):
        old_tag = release["tag_name"]
        if old_tag == "build":
            old_code, old_version = legacy_build_order(repo, release)
        else:
            old_code = tag_code(old_tag)
            old_version = tuple(map(int, old_tag[1:].split(".")))
        # Check both Android installation order and the public version order.
        if code <= old_code or semver(version) <= old_version:
            raise ValueError(f"Release {tag} is not newer than published {old_tag}")
    return tag


def commit_for_tag(repo, tag, allow_missing=False):
    reference = optional_api(f"repos/{repo}/git/ref/tags/{tag}") if allow_missing else api(f"repos/{repo}/git/ref/tags/{tag}")
    if reference is None:
        return None
    obj = reference["object"]
    # Peel annotated tags too; do not mistake their object SHA for the commit SHA.
    for _ in range(8):
        if obj["type"] == "commit":
            return obj["sha"]
        if obj["type"] != "tag":
            break
        obj = api(f"repos/{repo}/git/tags/{obj['sha']}")["object"]
    raise ValueError("Release tag does not resolve to a commit")


def verify_apk_assets(by_name):
    """The policy is the last gate before the mutation: the bytes must match their checksum."""
    apk = by_name["TgWatch.apk"]
    line = by_name["TgWatch.apk.sha256"].read_text().strip()
    match = re.fullmatch(r"([0-9a-fA-F]{64})(?:\s+\*?([^\s]+))?", line)
    if not match:
        raise ValueError("Checksum asset is not a single sha256 line")
    digest, recorded = match.group(1).lower(), match.group(2)
    if recorded and recorded != apk.name:
        raise ValueError(f"Checksum asset names {recorded}, not {apk.name}")
    if hashlib.sha256(apk.read_bytes()).hexdigest() != digest:
        raise ValueError("APK does not match its checksum asset")
    return digest


def publish(args):
    version = read_version(args.version_file)
    tag = preflight(args.repo, version, args.sha, args.ref)
    code = version_code(version)
    if args.preflight:
        print(f"Release preflight passed: {tag}, Android code {code}")
        return
    if not args.assets or not args.notes or not Path(args.notes).is_file():
        raise ValueError("Publication requires complete assets and an existing notes file")
    assets = [Path(path) for path in args.assets]
    if len({path.name for path in assets}) != len(assets) or not all(path.is_file() and path.stat().st_size for path in assets):
        raise ValueError("Release assets must be nonempty files with unique names")
    by_name = {path.name: path for path in assets}
    required = {"TgWatch.apk", "TgWatch.apk.sha256", "TgWatch.osv.json", "TgWatch.dependencies.json", "TgWatch.sbom.cdx.json", "TgWatch.trivy.json"}
    if set(by_name) != required:
        raise ValueError("Unified release needs complete APK/checksum, SBOM, Trivy and full dependency/OSV evidence")
    verify_apk_assets(by_name)
    inventory = json.loads(by_name["TgWatch.dependencies.json"].read_text())
    bom = json.loads(by_name["TgWatch.sbom.cdx.json"].read_text())
    verify_osv(json.loads(by_name["TgWatch.osv.json"].read_text()), inventory)
    verify_runtime_inventory(bom, inventory)
    if verify_report(json.loads(by_name["TgWatch.trivy.json"].read_text()), "app/build/reports/sbom/TgWatch.sbom.cdx.json", bom):
        raise ValueError("Trivy HIGH/CRITICAL findings block release publication")
    # Repeat preflight immediately before the sole mutation; CI serializes publishers.
    preflight(args.repo, version, args.sha, args.ref)
    gh("release", "create", tag, *map(str, assets), "--repo", args.repo,
       "--target", args.sha, "--title", f"TG Монитор {version}",
       "--notes-file", args.notes, "--latest=true")
    print(f"Published {tag}, Android code {code}, latest=true")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--version-file", default=str(Path(__file__).resolve().parents[1] / "version.properties"))
    parser.add_argument("--ref", required=True)
    parser.add_argument("--preflight", action="store_true")
    parser.add_argument("--sha", required=True)
    parser.add_argument("--notes")
    parser.add_argument("--assets", nargs="+")
    try:
        publish(parser.parse_args())
    except (ValueError, RuntimeError, KeyError, TypeError, OSError, ValidationError) as error:
        print(f"Release policy refused publication: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
