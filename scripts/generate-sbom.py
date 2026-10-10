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
    module_refs = {}
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
        module_refs.setdefault(f"{group}:{name}:{version}", set()).add(purl)
    graph = inventory.get("runtimeGraph")
    dependencies = [{"ref": root_ref, "dependsOn": sorted(components)}]
    if graph is not None:
        graph_nodes = {}
        for node in graph:
            if node["ref"] in graph_nodes or not isinstance(node["dependsOn"], list):
                raise ValueError("Malformed or duplicate resolved runtime graph node")
            graph_nodes[node["ref"]] = node["dependsOn"]
        if root_ref not in graph_nodes or not module_refs.keys() <= graph_nodes.keys():
            raise ValueError("Resolved runtime graph does not cover its artifacts/root")
        if any(target not in graph_nodes for targets in graph_nodes.values() for target in targets):
            raise ValueError("Resolved runtime graph contains a dangling dependency")
        def artifact_targets(node, seen):
            if node in module_refs:
                return module_refs[node]
            if node in seen:
                return set()
            # Platform/BOM nodes have no JAR/AAR to hash. Preserve their resolved
            # edges by following them to the actual runtime artifacts.
            return set().union(*(artifact_targets(child, seen | {node}) for child in graph_nodes[node]))
        def children(node):
            return sorted(set().union(*(artifact_targets(child, {node}) for child in graph_nodes[node])))
        dependencies = [{"ref": root_ref, "dependsOn": children(root_ref)}]
        for module in sorted(module_refs):
            dependencies.extend({"ref": ref, "dependsOn": children(module)} for ref in sorted(module_refs[module]))
    bom = {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
           "metadata": {"component": {"type": "application", "name": "TgWatch",
                        "version": inventory["applicationVersion"], "bom-ref": root_ref}},
           "components": [components[key] for key in sorted(components)],
           "dependencies": dependencies}
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
