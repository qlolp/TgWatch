#!/usr/bin/env python3
"""Generate and validate CycloneDX 1.6 from Gradle's resolved runtime artifacts.

The inventory is created after dependency resolution, never from dependency
declarations or APK contents. Hash the exact resolved JAR/AAR bytes.
"""
import hashlib
import json
from pathlib import Path
import sys
from urllib.parse import quote, urlencode

from sbom_schema import validate


def generate(inventory):
    root_ref = "ru.tgwatch:releaseRuntimeClasspath"
    components = {}
    for artifact in inventory["artifacts"]:
        group, name, version, kind = (artifact[field] for field in ("group", "name", "version", "type"))
        if not all(isinstance(value, str) and value for value in (group, name, version, kind)):
            raise ValueError("Resolved artifact coordinates and type must be nonempty strings")
        qualifiers = {"type": kind}
        if artifact.get("classifier"):
            qualifiers["classifier"] = artifact["classifier"]
        purl = f"pkg:maven/{quote(group, safe='')}/{quote(name, safe='')}@{quote(version, safe='')}?{urlencode(sorted(qualifiers.items()), quote_via=quote)}"
        digest = hashlib.sha256(Path(artifact["file"]).read_bytes()).hexdigest()
        component = {"type": "library", "group": group, "name": name, "version": version,
                     "purl": purl, "bom-ref": purl, "hashes": [{"alg": "SHA-256", "content": digest}]}
        if purl in components and components[purl] != component:
            raise ValueError(f"Conflicting resolved artifact bytes for {purl}")
        components[purl] = component
    bom = {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
           "metadata": {"component": {"type": "application", "name": "TgWatch",
                        "version": inventory["applicationVersion"], "bom-ref": root_ref}},
           "components": [components[key] for key in sorted(components)],
           "dependencies": [{"ref": root_ref, "dependsOn": sorted(components)}]}
    validate(bom)
    return bom


def main():
    if len(sys.argv) != 3:
        print("Usage: generate-sbom.py resolved-inventory.json output.cdx.json", file=sys.stderr)
        return 1
    try:
        bom = generate(json.loads(Path(sys.argv[1]).read_text()))
        output = Path(sys.argv[2])
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(bom, indent=2, ensure_ascii=False) + "\n")
        print(f"Validated CycloneDX 1.6: {len(bom['components'])} resolved release runtime artifacts")
    except Exception as error:
        print(f"SBOM generation/validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
