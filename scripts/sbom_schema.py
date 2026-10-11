"""Offline official CycloneDX schema plus the release runtime inventory policy."""
import json
from pathlib import Path

from jsonschema import Draft7Validator
from referencing import Registry, Resource


def validate(bom):
    schema_dir = Path(__file__).resolve().parent / "schemas"
    registry = Registry()
    main_schema = None
    for name in ("bom-1.6.schema.json", "jsf-0.82.schema.json", "spdx.schema.json"):
        schema = json.loads((schema_dir / name).read_text())
        registry = registry.with_resource(f"http://cyclonedx.org/schema/{name}", Resource.from_contents(schema))
        if name.startswith("bom-"):
            main_schema = schema
    Draft7Validator(main_schema, registry=registry).validate(bom)
    refs = set()
    for component in bom["components"]:
        if not component.get("version") or not component.get("purl", "").startswith("pkg:maven/"):
            raise ValueError("Every runtime component needs its resolved version and Maven purl")
        hashes = component.get("hashes", [])
        if len(hashes) != 1 or hashes[0]["alg"] != "SHA-256" or len(hashes[0]["content"]) != 64:
            raise ValueError("Every runtime component needs an artifact SHA-256")
        if component["bom-ref"] in refs:
            raise ValueError("Runtime component references must be unique")
        refs.add(component["bom-ref"])
