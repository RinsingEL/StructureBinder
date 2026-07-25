#!/usr/bin/env python3
"""Import approved single-root Trek structures as deterministic fixed City NBT templates."""

from __future__ import annotations

import argparse
import copy
import gzip
import hashlib
import io
import json
import re
import sys
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import nbtlib
from PIL import Image, ImageDraw


MANIFEST_SCHEMA = "geomantia_trek_fixed_import_manifest.v1"
CATALOG_SCHEMA = "city_template_catalog.v0.1"
DEFAULT_MANIFEST = Path(__file__).with_name("trek_fixed_manifest.json")
DEFAULT_QUERY_URL = "http://127.0.0.1:5000/realm/city/query_template_metadata"
ALLOWED_MARKER_PREFIX = "trek:mobs/"
ROTATIONS = ["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"]
BLOCK_STATE_PATTERN = re.compile(r"^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[([^]]+)\])?$")


class ImportFailure(RuntimeError):
    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


@dataclass(frozen=True)
class ImportedTemplate:
    manifest_entry: dict[str, Any]
    source_configured_id: str
    source_pool: str
    source_nbt: str
    processor: str
    target_ref: str
    relative_target: Path
    nbt: nbtlib.File
    encoded: bytes
    raw_size: dict[str, int]
    marker_count: int
    entity_count: int
    processor_replacements: int


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schemaVersion") != MANIFEST_SCHEMA:
        raise ImportFailure("TREK_TEMPLATE_MANIFEST_SCHEMA_UNSUPPORTED", str(value.get("schemaVersion")))
    templates = value.get("templates")
    if not isinstance(templates, list) or not templates:
        raise ImportFailure("TREK_TEMPLATE_MANIFEST_EMPTY", "templates must be a non-empty array")
    configured_ids = [entry.get("sourceConfiguredId") for entry in templates]
    if len(configured_ids) != len(set(configured_ids)):
        raise ImportFailure("TREK_TEMPLATE_MANIFEST_DUPLICATE", "sourceConfiguredId must be unique")
    return value


def zip_json(archive: zipfile.ZipFile, name: str) -> dict[str, Any]:
    try:
        return json.loads(archive.read(name))
    except KeyError as ex:
        raise ImportFailure("TREK_TEMPLATE_SOURCE_ENTRY_MISSING", name) from ex


def resource_path(resource_id: str, category: str, suffix: str) -> str:
    if ":" not in resource_id:
        raise ImportFailure("TREK_TEMPLATE_RESOURCE_ID_INVALID", resource_id)
    namespace, path = resource_id.split(":", 1)
    return f"data/{namespace}/{category}/{path}{suffix}"


def decode_nbt(value: bytes) -> nbtlib.File:
    raw = gzip.decompress(value) if value.startswith(b"\x1f\x8b") else value
    return nbtlib.File.parse(io.BytesIO(raw))


def encode_nbt(value: nbtlib.File) -> bytes:
    output = io.BytesIO()
    value.write(output, byteorder="big")
    return gzip.compress(output.getvalue(), compresslevel=9, mtime=0)


def state_compound(name: str, properties: dict[str, str] | None = None) -> nbtlib.Compound:
    state = nbtlib.Compound({"Name": nbtlib.String(name)})
    if properties:
        state["Properties"] = nbtlib.Compound({key: nbtlib.String(val) for key, val in properties.items()})
    return state


def state_key(state: nbtlib.Compound) -> tuple[str, tuple[tuple[str, str], ...]]:
    properties = state.get("Properties", {})
    return str(state["Name"]), tuple(sorted((str(key), str(value)) for key, value in properties.items()))


def parse_final_state(value: str) -> nbtlib.Compound:
    match = BLOCK_STATE_PATTERN.fullmatch(value.strip())
    if not match:
        raise ImportFailure("TREK_TEMPLATE_FINAL_STATE_INVALID", value)
    properties: dict[str, str] = {}
    if match.group(2):
        for item in match.group(2).split(","):
            if "=" not in item:
                raise ImportFailure("TREK_TEMPLATE_FINAL_STATE_INVALID", value)
            key, property_value = item.split("=", 1)
            properties[key.strip()] = property_value.strip()
    return state_compound(match.group(1), properties)


def palette_index(palette: nbtlib.List, state: nbtlib.Compound) -> int:
    key = state_key(state)
    for index, existing in enumerate(palette):
        if state_key(existing) == key:
            return index
    palette.append(state)
    return len(palette) - 1


def strip_marker_jigsaws(nbt: nbtlib.File) -> int:
    palette = nbt["palette"]
    marker_count = 0
    for block in nbt["blocks"]:
        state = palette[int(block["state"])]
        if str(state["Name"]) != "minecraft:jigsaw":
            continue
        block_entity = block.get("nbt")
        pool = str(block_entity.get("pool", "")) if block_entity is not None else ""
        if not pool.startswith(ALLOWED_MARKER_PREFIX):
            raise ImportFailure("TREK_TEMPLATE_STRUCTURAL_JIGSAW_REJECTED", pool or "missing pool")
        final_state = str(block_entity.get("final_state", ""))
        block["state"] = nbtlib.Int(palette_index(palette, parse_final_state(final_state)))
        block.pop("nbt", None)
        marker_count += 1
    remaining = sum(1 for block in nbt["blocks"]
                    if str(palette[int(block["state"])] ["Name"]) == "minecraft:jigsaw")
    if remaining:
        raise ImportFailure("TREK_TEMPLATE_JIGSAW_REMAINS", str(remaining))
    return marker_count


def clear_entities(nbt: nbtlib.File) -> int:
    count = len(nbt.get("entities", []))
    nbt["entities"] = nbtlib.List[nbtlib.Compound]()
    return count


def deterministic_roll(seed_parts: Iterable[str]) -> float:
    digest = hashlib.sha256("\0".join(seed_parts).encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") / float(1 << 64)


def load_processor_rules(archive: zipfile.ZipFile, processor_id: str) -> list[dict[str, Any]]:
    if processor_id == "minecraft:empty":
        return []
    value = zip_json(archive, resource_path(processor_id, "worldgen/processor_list", ".json"))
    processors = value.get("processors", [])
    if len(processors) != 1 or processors[0].get("processor_type") != "minecraft:rule":
        raise ImportFailure("TREK_TEMPLATE_PROCESSOR_UNSUPPORTED", processor_id)
    rules = processors[0].get("rules", [])
    for rule in rules:
        predicate = rule.get("input_predicate", {})
        if predicate.get("predicate_type") != "minecraft:random_block_match":
            raise ImportFailure("TREK_TEMPLATE_PROCESSOR_UNSUPPORTED", processor_id)
        if rule.get("location_predicate", {}).get("predicate_type") != "minecraft:always_true":
            raise ImportFailure("TREK_TEMPLATE_PROCESSOR_UNSUPPORTED", processor_id)
        if not isinstance(predicate.get("probability"), (int, float)):
            raise ImportFailure("TREK_TEMPLATE_PROCESSOR_UNSUPPORTED", processor_id)
    return rules


def bake_processor(nbt: nbtlib.File, rules: list[dict[str, Any]], seed_prefix: list[str]) -> int:
    if not rules:
        return 0
    palette = nbt["palette"]
    replacements = 0
    for block in nbt["blocks"]:
        current = palette[int(block["state"])]
        current_name = str(current["Name"])
        x, y, z = (int(value) for value in block["pos"])
        for index, rule in enumerate(rules):
            predicate = rule["input_predicate"]
            if current_name != predicate["block"]:
                continue
            roll = deterministic_roll([*seed_prefix, str(index), f"{x},{y},{z}"])
            if roll < float(predicate["probability"]):
                output = rule["output_state"]
                state = state_compound(output["Name"], output.get("Properties"))
                block["state"] = nbtlib.Int(palette_index(palette, state))
                replacements += 1
            break
    return replacements


def inspect_entry(archive: zipfile.ZipFile, manifest: dict[str, Any], entry: dict[str, Any],
                  jar_hash: str) -> ImportedTemplate:
    configured_id = entry["sourceConfiguredId"]
    configured = zip_json(archive, resource_path(configured_id, "worldgen/structure", ".json"))
    start_pool = configured.get("start_pool")
    if not isinstance(start_pool, str):
        raise ImportFailure("TREK_TEMPLATE_START_POOL_INVALID", configured_id)
    pool = zip_json(archive, resource_path(start_pool, "worldgen/template_pool", ".json"))
    elements = pool.get("elements")
    if not isinstance(elements, list) or len(elements) != 1:
        raise ImportFailure("TREK_TEMPLATE_STRUCTURAL_JIGSAW_REJECTED",
                            f"{configured_id} start pool has {len(elements or [])} roots")
    root = elements[0].get("element", {})
    if root.get("element_type") != "minecraft:single_pool_element" or not isinstance(root.get("location"), str):
        raise ImportFailure("TREK_TEMPLATE_STRUCTURAL_JIGSAW_REJECTED", configured_id)
    source_nbt = root["location"]
    processor_id = root.get("processors", "minecraft:empty")
    try:
        source_bytes = archive.read(resource_path(source_nbt, "structures", ".nbt"))
    except KeyError as ex:
        raise ImportFailure("TREK_TEMPLATE_SOURCE_ENTRY_MISSING", source_nbt) from ex
    nbt = decode_nbt(source_bytes)
    marker_count = strip_marker_jigsaws(nbt)
    entity_count = clear_entities(nbt)
    rules = load_processor_rules(archive, processor_id)
    processor_replacements = bake_processor(
        nbt, rules,
        [manifest["importerVersion"], jar_hash, configured_id, processor_id],
    )
    raw_size_values = [int(value) for value in nbt["size"]]
    raw_size = dict(zip(("width", "height", "depth"), raw_size_values))
    source_path = configured_id.split(":", 1)[1]
    target_path = f"{manifest['targetPrefix'].strip('/')}/{source_path}"
    target_ref = f"{manifest['targetNamespace']}:{target_path}"
    return ImportedTemplate(
        manifest_entry=entry,
        source_configured_id=configured_id,
        source_pool=start_pool,
        source_nbt=source_nbt,
        processor=processor_id,
        target_ref=target_ref,
        relative_target=Path(*target_path.split("/")),
        nbt=nbt,
        encoded=encode_nbt(nbt),
        raw_size=raw_size,
        marker_count=marker_count,
        entity_count=entity_count,
        processor_replacements=processor_replacements,
    )


def inspect_all(jar_path: Path, manifest: dict[str, Any]) -> tuple[str, list[ImportedTemplate]]:
    jar_hash = sha256_file(jar_path)
    expected_hash = manifest["sourceJarSha256"].lower()
    if jar_hash != expected_hash:
        raise ImportFailure("TREK_TEMPLATE_SOURCE_JAR_HASH_MISMATCH",
                            f"expected={expected_hash}, actual={jar_hash}")
    with zipfile.ZipFile(jar_path) as archive:
        imported = [inspect_entry(archive, manifest, entry, jar_hash) for entry in manifest["templates"]]
    return jar_hash, imported


def block_color(name: str) -> tuple[int, int, int]:
    families = [
        (("air", "void"), (245, 244, 238)),
        (("water",), (79, 137, 191)),
        (("grass", "leaves", "vine", "moss"), (91, 132, 72)),
        (("dirt", "mud", "podzol", "path"), (127, 99, 66)),
        (("stone", "cobble", "deepslate", "brick"), (127, 126, 121)),
        (("spruce", "dark_oak"), (91, 66, 43)),
        (("oak", "wood", "log", "plank"), (159, 124, 73)),
        (("red", "brick", "terracotta"), (165, 74, 59)),
        (("glass",), (165, 199, 202)),
    ]
    for words, color in families:
        if any(word in name for word in words):
            return color
    digest = hashlib.sha256(name.encode("utf-8")).digest()
    return 90 + digest[0] % 90, 90 + digest[1] % 90, 90 + digest[2] % 90


def block_map(item: ImportedTemplate) -> dict[tuple[int, int, int], str]:
    palette = item.nbt["palette"]
    return {
        tuple(int(value) for value in block["pos"]): str(palette[int(block["state"])] ["Name"])
        for block in item.nbt["blocks"]
    }


def render_grid(path: Path, width: int, height: int, cells: list[list[str]], title: str,
                entrance: dict[str, Any] | None = None) -> None:
    cell = max(2, min(12, 760 // max(width, height, 1)))
    margin = 32
    image = Image.new("RGB", (width * cell + margin * 2, height * cell + margin * 2), (248, 246, 239))
    draw = ImageDraw.Draw(image)
    draw.text((margin, 8), title, fill=(30, 30, 28))
    for row in range(height):
        for column in range(width):
            color = block_color(cells[row][column])
            x0 = margin + column * cell
            y0 = margin + row * cell
            draw.rectangle((x0, y0, x0 + cell - 1, y0 + cell - 1), fill=color)
    if entrance is not None:
        x = int(entrance["x"])
        z = int(entrance["z"])
        x0 = margin + x * cell
        y0 = margin + z * cell
        radius = max(2, cell // 2)
        draw.ellipse((x0 - radius, y0 - radius, x0 + cell + radius, y0 + cell + radius),
                     outline=(220, 34, 34), width=max(1, cell // 3))
        draw.text((margin, image.height - 22), f"entrance {x},{z} {entrance['direction']}", fill=(160, 25, 25))
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=False)


def render_previews(item: ImportedTemplate, preview_root: Path) -> list[str]:
    sx, sy, sz = (item.raw_size[key] for key in ("width", "height", "depth"))
    blocks = block_map(item)
    entrance = item.manifest_entry.get("entrance")
    outputs: list[Path] = []
    top = [["minecraft:air" for _ in range(sx)] for _ in range(sz)]
    for z in range(sz):
        for x in range(sx):
            for y in range(sy - 1, -1, -1):
                name = blocks.get((x, y, z), "minecraft:air")
                if name not in ("minecraft:air", "minecraft:structure_void"):
                    top[z][x] = name
                    break
    target_dir = preview_root.joinpath(*item.relative_target.parts[2:])
    top_path = target_dir / "top.png"
    render_grid(top_path, sx, sz, top, f"{item.target_ref} top", entrance)
    outputs.append(top_path)
    for direction in ("north", "south"):
        side = [["minecraft:air" for _ in range(sx)] for _ in range(sy)]
        z_values = range(sz) if direction == "north" else range(sz - 1, -1, -1)
        for row, y in enumerate(range(sy - 1, -1, -1)):
            for x in range(sx):
                for z in z_values:
                    name = blocks.get((x, y, z), "minecraft:air")
                    if name not in ("minecraft:air", "minecraft:structure_void"):
                        side[row][x] = name
                        break
        output = target_dir / f"{direction}.png"
        render_grid(output, sx, sy, side, f"{item.target_ref} {direction}")
        outputs.append(output)
    for direction in ("west", "east"):
        side = [["minecraft:air" for _ in range(sz)] for _ in range(sy)]
        x_values = range(sx) if direction == "west" else range(sx - 1, -1, -1)
        for row, y in enumerate(range(sy - 1, -1, -1)):
            for z in range(sz):
                for x in x_values:
                    name = blocks.get((x, y, z), "minecraft:air")
                    if name not in ("minecraft:air", "minecraft:structure_void"):
                        side[row][z] = name
                        break
        output = target_dir / f"{direction}.png"
        render_grid(output, sz, sy, side, f"{item.target_ref} {direction}")
        outputs.append(output)
    return [str(path) for path in outputs]


def load_runtime_metadata(path: Path | None, query_url: str | None,
                          template_refs: list[str]) -> dict[str, dict[str, Any]]:
    if path is not None:
        payload = json.loads(path.read_text(encoding="utf-8"))
    elif query_url:
        request = urllib.request.Request(
            query_url,
            data=json.dumps({"templateRefs": template_refs, "dimensionId": "minecraft:overworld"}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=90) as response:
            payload = json.load(response)
    else:
        return {}
    values = payload.get("templates", payload if isinstance(payload, list) else [])
    result = {value["templateRef"]: value for value in values}
    for template_ref in template_refs:
        value = result.get(template_ref)
        if value is None or not value.get("readable"):
            raise ImportFailure("TREK_TEMPLATE_RUNTIME_METADATA_UNREADABLE", template_ref)
    return result


def build_catalog(imported: list[ImportedTemplate], runtime: dict[str, dict[str, Any]]) -> dict[str, Any]:
    entries: list[dict[str, Any]] = []
    for item in imported:
        source = item.manifest_entry
        if not source.get("entranceConfirmed") or not isinstance(source.get("entrance"), dict):
            raise ImportFailure("TREK_TEMPLATE_ENTRANCE_UNCONFIRMED", item.source_configured_id)
        metadata = runtime.get(item.target_ref)
        if metadata is None:
            raise ImportFailure("TREK_TEMPLATE_RUNTIME_METADATA_REQUIRED", item.target_ref)
        runtime_size = metadata.get("rawSize")
        if runtime_size != item.raw_size:
            raise ImportFailure("TREK_TEMPLATE_RUNTIME_SIZE_MISMATCH",
                                f"{item.target_ref}: importer={item.raw_size}, runtime={runtime_size}")
        entries.append({
            "buildingSemantic": source["buildingSemantic"],
            "style": source["style"],
            "templateId": item.target_ref,
            "templateRef": item.target_ref,
            "contentHash": metadata["templateHash"],
            "variant": "trek_b0_6_fixed_v1",
            "rawSize": runtime_size,
            "allowedRotations": ROTATIONS,
            "allowedMirrors": ["NONE"],
            "roadEntrances": [{
                "entranceId": source["entrance"]["entranceId"],
                "position": {"x": source["entrance"]["x"], "z": source["entrance"]["z"]},
                "direction": source["entrance"]["direction"],
            }],
            "terrainPosePolicy": "structure_start_beard_thin",
            "supportPolicy": "full_footprint_support",
            "clearanceBlocks": source["clearanceBlocks"],
        })
    return {"schemaVersion": CATALOG_SCHEMA, "templates": entries}


def write_outputs(save_dir: Path, imported: list[ImportedTemplate], overwrite: bool) -> tuple[Path, list[dict[str, Any]]]:
    structure_root = save_dir / "generated" / "geomantia" / "structures"
    preview_root = save_dir / "generated" / "geomantia" / "city_template_previews"
    report_entries: list[dict[str, Any]] = []
    for item in imported:
        output = structure_root / item.relative_target.with_suffix(".nbt")
        if output.exists() and not overwrite:
            raise ImportFailure("TREK_TEMPLATE_OVERWRITE_CONFIRMATION_REQUIRED", str(output))
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(item.encoded)
        previews = render_previews(item, preview_root)
        report_entries.append({
            "sourceConfiguredId": item.source_configured_id,
            "sourceStartPool": item.source_pool,
            "sourceRootNbt": item.source_nbt,
            "processor": item.processor,
            "strippedJigsawMarkers": item.marker_count,
            "strippedEntities": item.entity_count,
            "processorReplacements": item.processor_replacements,
            "rawSize": item.raw_size,
            "outputFileSha256": f"sha256:{sha256_bytes(item.encoded)}",
            "runtimeHash": "",
            "runtimeRawSize": None,
            "targetRef": item.target_ref,
            "outputFile": str(output),
            "previews": previews,
        })
    return structure_root / "city" / "trek", report_entries


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--save-dir", required=True, type=Path,
                        help="Explicit Minecraft save directory; latest-save guessing is forbidden.")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--overwrite", action="store_true",
                        help="Required when any target NBT already exists.")
    parser.add_argument("--query-url", nargs="?", const=DEFAULT_QUERY_URL,
                        help="Query city_query_template_metadata after writing and build the catalog.")
    parser.add_argument("--runtime-metadata", type=Path,
                        help="Use a captured city_query_template_metadata response instead of HTTP.")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        manifest_path = args.manifest.resolve()
        manifest = load_manifest(manifest_path)
        repo_root = Path(__file__).resolve().parents[2]
        jar_path = (args.jar or repo_root / manifest["sourceJar"]).resolve()
        save_dir = args.save_dir.resolve()
        if not save_dir.is_dir():
            raise ImportFailure("TREK_TEMPLATE_SAVE_DIR_MISSING", str(save_dir))
        jar_hash, imported = inspect_all(jar_path, manifest)
        if args.dry_run:
            print(json.dumps({
                "status": "dry_run_ok",
                "sourceJar": str(jar_path),
                "sourceJarSha256": jar_hash,
                "templateCount": len(imported),
                "targetSave": str(save_dir),
                "targets": [item.target_ref for item in imported],
            }, ensure_ascii=False, indent=2))
            return 0
        output_root, report_entries = write_outputs(save_dir, imported, args.overwrite)
        runtime = load_runtime_metadata(args.runtime_metadata, args.query_url,
                                        [item.target_ref for item in imported])
        for entry in report_entries:
            metadata = runtime.get(entry["targetRef"])
            if metadata:
                entry["runtimeHash"] = metadata["templateHash"]
                entry["runtimeRawSize"] = metadata["rawSize"]
        report = {
            "schemaVersion": "geomantia_trek_fixed_import_report.v1",
            "importerVersion": manifest["importerVersion"],
            "sourceJar": str(jar_path),
            "sourceJarSha256": jar_hash,
            "targetSave": str(save_dir),
            "templateCount": len(imported),
            "runtimeMetadataConfirmed": bool(runtime),
            "templates": report_entries,
        }
        report_path = output_root / "trek_fixed_import_report.json"
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        catalog_path = None
        if runtime:
            catalog = build_catalog(imported, runtime)
            catalog_path = output_root / "template_catalog.json"
            catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({
            "status": "imported",
            "outputRoot": str(output_root),
            "report": str(report_path),
            "catalog": str(catalog_path) if catalog_path else None,
            "runtimeMetadataConfirmed": bool(runtime),
        }, ensure_ascii=False, indent=2))
        return 0
    except ImportFailure as ex:
        print(json.dumps({"status": "failed", "reasonCode": ex.code, "detail": ex.detail},
                         ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
