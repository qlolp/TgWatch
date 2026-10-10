#!/usr/bin/env python3
"""Fail-closed publication policy. CI serializes this command across all releases.

Published tags/assets are immutable. A complete rerun with the same package and
SBOM is a no-op; historical scan evidence stays unchanged. A draft, incomplete
release, changed commit or changed package requires human repair.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from scan_report import verify_report
from audit_report import verify_osv, verify_runtime_inventory
from jsonschema.exceptions import ValidationError

MAX_RUN_NUMBER = 2147483547  # Android signed-int versionCode minus the CI offset.


def version_code(run_number):
    if not re.fullmatch(r"[1-9][0-9]*", run_number) or len(run_number) > 10:
        raise ValueError("GITHUB_RUN_NUMBER must be a positive decimal integer")
    run = int(run_number)
    if run > MAX_RUN_NUMBER:
        raise ValueError("GITHUB_RUN_NUMBER + 100 exceeds the Android versionCode range")
    return run + 100


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
    match = re.fullmatch(r"v[0-9]+\.[0-9]+\.([1-9][0-9]*)", tag)
    if not match:
        raise ValueError(f"Cannot determine Android code for published tag {tag!r}")
    return version_code(match[1])


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


def verify_existing(repo, tag, release, sha, assets):
    if release.get("draft"):
        raise ValueError(f"Existing draft {tag}: publication is incomplete; inspect it explicitly")
    if commit_for_tag(repo, tag) != sha:
        raise ValueError(f"Published tag {tag} points to another commit; it remains unchanged")
    published = {asset["name"]: asset for asset in release.get("assets", [])}
    if set(published) != {asset.name for asset in assets}:
        raise ValueError(f"Published assets for {tag} are incomplete or different; they remain unchanged")
    for path in assets:
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        old = published[path.name]
        if path.name in ("TgWatch.trivy.json", "TgWatch.osv.json"):
            # Scan timestamps/database contents vary across reruns. Preserve the
            # original clean report, verify its bytes instead of replacing it.
            with tempfile.TemporaryDirectory() as directory:
                gh("release", "download", tag, "--repo", repo, "--pattern", path.name, "--dir", directory)
                content = Path(directory, path.name).read_bytes()
            digest = "sha256:" + hashlib.sha256(content).hexdigest()
            if len(content) != old.get("size") or old.get("digest") not in (None, digest):
                raise ValueError("Published scan evidence failed integrity verification; it remains unchanged")
            evidence = json.loads(content)
            if path.name == "TgWatch.trivy.json":
                bom_file = next(asset for asset in assets if asset.name == "TgWatch.sbom.cdx.json")
                if verify_report(evidence, "app/build/reports/sbom/TgWatch.sbom.cdx.json", json.loads(bom_file.read_text())):
                    raise ValueError("Published scan evidence failed the severity gate; it remains unchanged")
            else:
                inventory_file = next(asset for asset in assets if asset.name == "TgWatch.dependencies.json")
                verify_osv(evidence, json.loads(inventory_file.read_text()))
            continue
        if old.get("size") != path.stat().st_size:
            raise ValueError(f"Published asset {path.name} has different size; it remains unchanged")
        digest = old.get("digest")
        if digest is None:
            # GitHub does not return digests for some older assets. Verify bytes instead.
            with tempfile.TemporaryDirectory() as directory:
                gh("release", "download", tag, "--repo", repo, "--pattern", path.name, "--dir", directory)
                digest = "sha256:" + hashlib.sha256(Path(directory, path.name).read_bytes()).hexdigest()
        if digest != "sha256:" + actual:
            raise ValueError(f"Published asset {path.name} differs; it remains unchanged")


def publish(args):
    code = version_code(args.run_number)
    if not re.fullmatch(r"[0-9]+\.[0-9]+", args.version):
        raise ValueError("versionName must be major.minor")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", args.sha):
        raise ValueError("Publication requires a full commit SHA")
    assets = [Path(path) for path in args.assets]
    if len({path.name for path in assets}) != len(assets) or not all(path.is_file() and path.stat().st_size for path in assets):
        raise ValueError("Release assets must be nonempty files with unique names")
    by_name = {path.name: path for path in assets}
    required = {"TgWatch.apk", "TgWatch.apk.sha256", "TgWatch.osv.json", "TgWatch.dependencies.json", "TgWatch.sbom.cdx.json", "TgWatch.trivy.json"}
    if set(by_name) != required:
        raise ValueError("Unified release needs complete APK/checksum, SBOM, Trivy and full dependency/OSV evidence")
    inventory = json.loads(by_name["TgWatch.dependencies.json"].read_text())
    bom = json.loads(by_name["TgWatch.sbom.cdx.json"].read_text())
    verify_osv(json.loads(by_name["TgWatch.osv.json"].read_text()), inventory)
    verify_runtime_inventory(bom, inventory)
    if verify_report(json.loads(by_name["TgWatch.trivy.json"].read_text()), "app/build/reports/sbom/TgWatch.sbom.cdx.json", bom):
        raise ValueError("Trivy HIGH/CRITICAL findings block release publication")
    tag = f"v{args.version}.{args.run_number}"
    pages = api(f"repos/{args.repo}/releases", "--paginate", "--slurp")
    releases = [release for page in pages for release in page]
    existing = next((release for release in releases if release["tag_name"] == tag), None)
    if existing:
        verify_existing(args.repo, tag, existing, args.sha, assets)
        print(f"{tag} already published with identical assets; left unchanged")
        return
    tag_commit = commit_for_tag(args.repo, tag, allow_missing=True)
    if tag_commit is not None and tag_commit != args.sha:
        raise ValueError(f"Existing tag {tag} points to another commit; it remains unchanged")
    latest = optional_api(f"repos/{args.repo}/releases/latest")
    codes = [tag_code(release["tag_name"]) for release in releases
             if not release.get("draft") and not release.get("prerelease")]
    if latest:
        codes.append(tag_code(latest["tag_name"]))
    promote = not codes or code > max(codes)
    # Explicit false is essential: gh/GitHub otherwise chooses latest automatically.
    gh("release", "create", tag, *map(str, assets), "--repo", args.repo,
       "--target", args.sha, "--title", f"TG Монитор {args.version}.{args.run_number}",
       "--notes-file", args.notes, f"--latest={str(promote).lower()}")
    print(f"Published {tag}, Android code {code}, latest={str(promote).lower()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--run-number", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--notes", required=True)
    parser.add_argument("--assets", nargs="+", required=True)
    try:
        publish(parser.parse_args())
    except (ValueError, RuntimeError, KeyError, TypeError, OSError, ValidationError) as error:
        print(f"Release policy refused publication: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
