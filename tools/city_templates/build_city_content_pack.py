#!/usr/bin/env python3
"""Build a deployable City template content pack from reviewed generated NBT files."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from typing import Any


SCHEMA = "city_template_content_pack.v0.1"
MANIFEST_FILE = "city_template_content_pack.json"
PAYLOAD_DIRECTORY = "city_template_content_pack"


class ContentPackFailure(RuntimeError):
    pass


def sha256_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def _template_parts(template_ref: str) -> tuple[str, str]:
    if template_ref.count(":") != 1:
        raise ContentPackFailure(f"CITY_TEMPLATE_CONTENT_TEMPLATE_REF_INVALID: {template_ref}")
    namespace, path = template_ref.split(":", 1)
    if not namespace or not path or ".." in path or path.startswith("/"):
        raise ContentPackFailure(f"CITY_TEMPLATE_CONTENT_TEMPLATE_REF_INVALID: {template_ref}")
    return namespace, path


def build(catalog_path: Path, generated_root: Path, output_directory: Path,
          pack_id: str, overwrite: bool = False) -> dict[str, Any]:
    catalog_bytes = catalog_path.read_bytes()
    catalog = json.loads(catalog_bytes)
    if catalog.get("schema") != "city_template_catalog":
        raise ContentPackFailure("CITY_TEMPLATE_CONTENT_CATALOG_SCHEMA_UNSUPPORTED")
    templates = catalog.get("templates")
    if not isinstance(templates, list) or not templates:
        raise ContentPackFailure("CITY_TEMPLATE_CONTENT_CATALOG_EMPTY")
    if not pack_id.strip():
        raise ContentPackFailure("CITY_TEMPLATE_CONTENT_PACK_ID_MISSING")

    payload_root = output_directory / PAYLOAD_DIRECTORY
    entries: list[dict[str, str]] = []
    seen: set[str] = set()
    prepared: list[tuple[Path, Path, dict[str, str]]] = []
    for template in templates:
        template_ref = template.get("templateRef")
        if not isinstance(template_ref, str):
            raise ContentPackFailure("CITY_TEMPLATE_CONTENT_TEMPLATE_REF_MISSING")
        if template_ref in seen:
            continue
        seen.add(template_ref)
        namespace, path = _template_parts(template_ref)
        source = generated_root / namespace / "structures" / Path(*path.split("/"))
        source = source.with_suffix(".nbt")
        if not source.is_file():
            raise ContentPackFailure(f"CITY_TEMPLATE_CONTENT_SOURCE_MISSING: {template_ref}: {source}")
        relative = Path(namespace) / "structures" / Path(*path.split("/"))
        relative = relative.with_suffix(".nbt")
        entry = {
            "templateRef": template_ref,
            "sourceFile": relative.as_posix(),
            "sourceSha256": sha256_bytes(source.read_bytes()),
            "sourceIdentity": template_ref,
            "converterId": str(template.get("variant", "reviewed_fixed_nbt")),
        }
        family = path.split("/", 2)[1] if path.startswith("city/") and "/" in path[5:] else ""
        if family in {"trek", "trek_v3"}:
            entry["sourceModId"] = "trek"
        prepared.append((source, payload_root / relative, entry))
        entries.append(entry)

    existing_manifest = output_directory / MANIFEST_FILE
    if (existing_manifest.exists() or payload_root.exists()) and not overwrite:
        raise ContentPackFailure("CITY_TEMPLATE_CONTENT_OVERWRITE_CONFIRMATION_REQUIRED")
    output_directory.mkdir(parents=True, exist_ok=True)
    for source, target, _ in prepared:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    manifest = {
        "schema": SCHEMA,
        "packId": pack_id.strip(),
        "catalogSha256": sha256_bytes(catalog_bytes),
        "templates": entries,
    }
    temporary = existing_manifest.with_suffix(existing_manifest.suffix + ".tmp")
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(existing_manifest)
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--generated-root", type=Path, required=True,
                        help="The source world's generated directory")
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="Managed City planning bundle directory")
    parser.add_argument("--pack-id", required=True)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        manifest = build(args.catalog.resolve(), args.generated_root.resolve(),
                         args.output_dir.resolve(), args.pack_id, args.overwrite)
        print(json.dumps({"status": "ok", "packId": manifest["packId"],
                          "templateCount": len(manifest["templates"]),
                          "manifest": str((args.output_dir / MANIFEST_FILE).resolve())},
                         ensure_ascii=False, indent=2))
        return 0
    except (ContentPackFailure, OSError, json.JSONDecodeError) as ex:
        print(json.dumps({"status": "failed", "error": str(ex)}, ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
