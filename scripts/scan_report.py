"""Shared fail-closed parser for current and immutable historical Trivy reports."""
from datetime import datetime
from urllib.parse import unquote

from sbom_schema import validate


def high_critical(scan):
    if not isinstance(scan, dict) or scan.get("SchemaVersion") != 2:
        raise ValueError("Incomplete Trivy report")
    results = scan.get("Results")
    if results is None:
        results = []  # Trivy omits empty Results. verify_report checks actual BOM coverage.
    if not isinstance(results, list):
        raise ValueError("Malformed Trivy Results")
    severe = []
    for result in results:
        if not isinstance(result, dict):
            raise ValueError("Malformed Trivy result")
        vulnerabilities = result.get("Vulnerabilities")
        if vulnerabilities is None:
            continue
        if not isinstance(vulnerabilities, list):
            raise ValueError("Malformed Trivy vulnerabilities")
        for vulnerability in vulnerabilities:
            if not isinstance(vulnerability, dict) or vulnerability.get("Severity") not in ("UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"):
                raise ValueError("Malformed Trivy vulnerability severity")
            if vulnerability["Severity"] in ("HIGH", "CRITICAL"):
                severe.append(vulnerability)
    return severe


def verify_report(scan, expected_artifact, bom):
    validate(bom)
    severe = high_critical(scan)
    if scan.get("ArtifactType") != "cyclonedx" or scan.get("ArtifactName") != expected_artifact:
        raise ValueError("Trivy report does not match the expected CycloneDX artifact")
    created_at = scan.get("CreatedAt")
    if not isinstance(created_at, str) or not created_at:
        raise ValueError("Trivy report lacks a scan timestamp")
    timestamp = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
    if timestamp.tzinfo is None:
        raise ValueError("Trivy scan timestamp lacks its timezone")
    # Vulnerability matching works on Maven module/version, independent of the
    # artifact's type/classifier. --list-all-pkgs supplies this evidence even clean.
    def module(purl):
        if not isinstance(purl, str) or not purl.startswith("pkg:maven/"):
            raise ValueError("Missing Maven package identity in scan evidence")
        return unquote(purl.split("?", 1)[0].split("#", 1)[0])
    expected = {(module(component["purl"]), component["version"])
                for component in bom["components"] if component["type"] == "library"}
    found = set()
    for result in scan.get("Results") or []:
        packages = result.get("Packages") or []
        if not isinstance(packages, list):
            raise ValueError("Malformed Trivy package inventory")
        for package in packages:
            if not isinstance(package, dict) or not isinstance(package.get("Identifier"), dict):
                raise ValueError("Malformed Trivy package identity")
            found.add((module(package["Identifier"].get("PURL")), package.get("Version")))
    if not expected <= found:
        raise ValueError("Trivy package inventory does not cover the resolved runtime SBOM")
    return severe
