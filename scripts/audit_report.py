"""Validate OSV audit evidence against every resolved runtime and test package."""
from datetime import datetime

OSV_SOURCE = "https://api.osv.dev/v1/query"
SCOPES = {"releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath", "debugAndroidTestRuntimeClasspath"}


def identities(packages):
    if not isinstance(packages, list) or not packages:
        raise ValueError("Missing resolved dependency inventory")
    selected = {}
    for package in packages:
        if not isinstance(package, dict):
            raise ValueError("Malformed dependency inventory package")
        coordinate = tuple(package.get(key) for key in ("group", "name", "version"))
        scopes = package.get("scopes")
        if not all(isinstance(value, str) and value for value in coordinate) or not isinstance(scopes, list) or not scopes:
            raise ValueError("Package needs exact resolved coordinate and scopes")
        if not all(isinstance(scope, str) and scope in SCOPES for scope in scopes) or len(set(scopes)) != len(scopes):
            raise ValueError("Malformed resolved dependency scopes")
        if coordinate in selected:
            raise ValueError("Duplicate resolved dependency coordinate")
        selected[coordinate] = frozenset(scopes)
    return selected


def verify_osv(report, inventory):
    expected = identities(inventory.get("packages"))
    if not any("releaseRuntimeClasspath" in scopes for scopes in expected.values()):
        raise ValueError("Resolved runtime scope missing")
    if not isinstance(report, dict) or report.get("source") != OSV_SOURCE or "error" in report or report.get("status") not in (None, "passed"):
        raise ValueError("OSV audit is unavailable or incomplete")
    timestamp = report.get("checked_at")
    if not isinstance(timestamp, str) or datetime.fromisoformat(timestamp.replace("Z", "+00:00")).tzinfo is None:
        raise ValueError("OSV audit timestamp is missing its timezone")
    if identities(report.get("packages")) != expected:
        raise ValueError("OSV audit does not cover every exact runtime/test dependency and scope")
    for package in report["packages"]:
        findings = package.get("vulnerabilities")
        if not isinstance(findings, list) or not all(isinstance(value, str) and value for value in findings):
            raise ValueError("Malformed OSV findings")
        if findings:
            raise ValueError("OSV findings block release publication")


def verify_runtime_inventory(bom, inventory):
    expected = identities(inventory.get("packages"))
    for component in bom["components"]:
        coordinate = tuple(component.get(key) for key in ("group", "name", "version"))
        if "releaseRuntimeClasspath" not in expected.get(coordinate, ()):
            raise ValueError("SBOM runtime artifact is missing from the resolved OSV inventory")
