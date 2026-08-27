#!/usr/bin/env python3
"""Import approved single-root Trek structures as deterministic fixed City NBT templates."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import sys
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import nbtlib
from PIL import Image, ImageDraw

try:
    from .jigsaw_template_sanitizer import (
        SanitizeFailure as JigsawSanitizeFailure,
        decode_nbt,
        encode_nbt,
        palette_index,
        replace_jigsaws_with_final_state,
        state_compound,
    )
except ImportError:
    from jigsaw_template_sanitizer import (
        SanitizeFailure as JigsawSanitizeFailure,
        decode_nbt,
        encode_nbt,
        palette_index,
        replace_jigsaws_with_final_state,
        state_compound,
    )


MANIFEST_SCHEMA = "geomantia_trek_fixed_import_manifest.v1"
CATALOG_SCHEMA = "city_template_catalog.v0.1"
PROFILE_SOURCE_SCHEMA = "terrasense_structure_profile_source.v0.1"
VOCABULARY_SCHEMA = "terrasense_structure_vocabulary_snapshot.v0.1"
DEFAULT_MANIFEST = Path(__file__).with_name("trek_fixed_manifest.json")
DEFAULT_QUERY_URL = "http://127.0.0.1:5000/realm/city/query_template_metadata"
ALLOWED_MARKER_PREFIX = "trek:mobs/"
ROTATIONS = ["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"]
ENTRANCE_DIRECTIONS = {"NORTH", "SOUTH", "EAST", "WEST"}
ENTRANCE_EVIDENCE_KINDS = {"door", "gate", "opening", "water_access"}
TERM_FIELDS = (
    ("functionTerms", "function"),
    ("styleTerms", "style"),
    ("placementTerms", "placement"),
    ("usageTerms", "usage"),
    ("templateRoleTerms", "template_role"),
    ("qualityTerms", "quality"),
)


class ImportFailure(RuntimeError):
    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


@dataclass(frozen=True)
class ImportedTemplate:
    manifest_entry: dict[str, Any]
    entrance_review_version: str
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
    entrance_review_version = value.get("entranceReviewVersion")
    if not isinstance(entrance_review_version, str) or not entrance_review_version:
        raise ImportFailure("TREK_TEMPLATE_ENTRANCE_REVIEW_INVALID",
                            "entranceReviewVersion must be a non-empty string")
    source_ids: list[str] = []
    for entry in templates:
        configured_id = entry.get("sourceConfiguredId")
        template_id = entry.get("sourceTemplateId")
        if (isinstance(configured_id, str) and configured_id) == (isinstance(template_id, str) and template_id):
            raise ImportFailure("TREK_TEMPLATE_SOURCE_ID_INVALID",
                                "each template requires exactly one sourceConfiguredId or sourceTemplateId")
        source_ids.append(configured_id or template_id)
        if template_id:
            if entry.get("standaloneConfirmed") is not True:
                raise ImportFailure("TREK_TEMPLATE_STANDALONE_UNCONFIRMED", template_id)
            if entry.get("connectorPolicy") not in {"markers_only", "replace_all_with_final_state", "none"}:
                raise ImportFailure("TREK_TEMPLATE_CONNECTOR_POLICY_INVALID", template_id)
            target_path = entry.get("targetPath")
            if not isinstance(target_path, str) or not target_path or target_path.startswith("/"):
                raise ImportFailure("TREK_TEMPLATE_TARGET_PATH_INVALID", str(target_path))
    if len(source_ids) != len(set(source_ids)):
        raise ImportFailure("TREK_TEMPLATE_MANIFEST_DUPLICATE", "source template identities must be unique")
    profile_export = value.get("profileExport")
    if not isinstance(profile_export, dict):
        raise ImportFailure("TREK_TEMPLATE_SEMANTIC_PROFILE_INVALID", "profileExport must be an object")
    defaults = profile_export.get("defaults")
    vocabulary = profile_export.get("vocabularyTerms")
    if not isinstance(defaults, dict) or not isinstance(vocabulary, list) or not vocabulary:
        raise ImportFailure("TREK_TEMPLATE_SEMANTIC_PROFILE_INVALID",
                            "profileExport defaults and vocabularyTerms are required")
    for field in ("profileSetId", "terrasenseRunId", "exportedAt", "sourceWorkspace"):
        if not isinstance(profile_export.get(field), str) or not profile_export[field]:
            raise ImportFailure("TREK_TEMPLATE_SEMANTIC_PROFILE_INVALID", f"profileExport.{field}")

    used_terms: set[str] = set()
    for entry in templates:
        if entry.get("entranceConfirmed"):
            entrance = entry.get("entrance")
            evidence = entry.get("entranceEvidence")
            if not isinstance(entrance, dict) or not isinstance(evidence, dict):
                raise ImportFailure("TREK_TEMPLATE_ENTRANCE_REVIEW_INVALID",
                                    f"{entry.get('sourceConfiguredId')}: entrance and evidence are required")
            for reviewed_entrance in entry_entrances(entry):
                if (not isinstance(reviewed_entrance.get("entranceId"), str)
                        or not reviewed_entrance["entranceId"]
                        or reviewed_entrance.get("direction") not in ENTRANCE_DIRECTIONS
                        or any(not isinstance(reviewed_entrance.get(axis), int) for axis in ("x", "z"))):
                    raise ImportFailure("TREK_TEMPLATE_ENTRANCE_REVIEW_INVALID",
                                        f"{configured_id or template_id}: invalid entrance")
            if (evidence.get("kind") not in ENTRANCE_EVIDENCE_KINDS
                    or any(not isinstance(evidence.get(axis), int) for axis in ("x", "y", "z"))
                    or not isinstance(evidence.get("block"), str) or not evidence["block"]):
                raise ImportFailure("TREK_TEMPLATE_ENTRANCE_REVIEW_INVALID",
                                    f"{entry.get('sourceConfiguredId')}: invalid entranceEvidence")
        for field, prefix in TERM_FIELDS:
            source = entry if field in ("functionTerms", "styleTerms", "placementTerms") else defaults
            terms = source.get(field)
            if not isinstance(terms, list) or not terms or any(not isinstance(term, str) for term in terms):
                raise ImportFailure("TREK_TEMPLATE_SEMANTIC_PROFILE_INVALID",
                                    f"{entry.get('sourceConfiguredId')}: {field}")
            if len(terms) != len(set(terms)) or any(not term.startswith(f"{prefix}.") for term in terms):
                raise ImportFailure("TREK_TEMPLATE_SEMANTIC_PROFILE_INVALID",
                                    f"{entry.get('sourceConfiguredId')}: {field} has invalid terms")
            used_terms.update(terms)

    vocabulary_ids = [term.get("term_id") for term in vocabulary if isinstance(term, dict)]
    if (len(vocabulary_ids) != len(vocabulary)
            or any(not isinstance(term_id, str) or not term_id for term_id in vocabulary_ids)
            or len(vocabulary_ids) != len(set(vocabulary_ids))):
        raise ImportFailure("TREK_TEMPLATE_SEMANTIC_PROFILE_INVALID",
                            "vocabularyTerms must have unique term_id values")
    for term in vocabulary:
        term_id = term["term_id"]
        if (term.get("status") != "approved" or not isinstance(term.get("vocab_type"), str)
                or not term_id.startswith(f"{term['vocab_type']}.")):
            raise ImportFailure("TREK_TEMPLATE_SEMANTIC_PROFILE_INVALID", f"invalid vocabulary term {term_id}")
    if used_terms != set(vocabulary_ids):
        missing = sorted(used_terms - set(vocabulary_ids))
        unused = sorted(set(vocabulary_ids) - used_terms)
        raise ImportFailure("TREK_TEMPLATE_SEMANTIC_VOCABULARY_INCOMPLETE",
                            f"missing={missing}, unused={unused}")
    return value


def profile_term_groups(manifest: dict[str, Any], entry: dict[str, Any]) -> dict[str, list[str]]:
    defaults = manifest["profileExport"]["defaults"]
    return {
        field: list(entry[field] if field in ("functionTerms", "styleTerms", "placementTerms")
                    else defaults[field])
        for field, _ in TERM_FIELDS
    }


def entry_entrances(entry: dict[str, Any]) -> list[dict[str, Any]]:
    entrances = entry.get("entrances")
    if entrances is None:
        entrance = entry.get("entrance")
        return [entrance] if isinstance(entrance, dict) else []
    if (not isinstance(entrances, list) or not entrances
            or any(not isinstance(value, dict) for value in entrances)):
        raise ImportFailure("TREK_TEMPLATE_ENTRANCE_REVIEW_INVALID", "entrances must be non-empty objects")
    return entrances


def validate_confirmed_entrance(nbt: nbtlib.File, entry: dict[str, Any],
                                raw_size: dict[str, int]) -> None:
    if not entry.get("entranceConfirmed"):
        return
    entrance = entry["entrance"]
    evidence = entry["entranceEvidence"]
    source_id = entry.get("sourceConfiguredId", entry.get("sourceTemplateId", "unknown"))
    for reviewed_entrance in entry_entrances(entry):
        if not (0 <= reviewed_entrance["x"] < raw_size["width"]
                and 0 <= reviewed_entrance["z"] < raw_size["depth"]):
            raise ImportFailure("TREK_TEMPLATE_ENTRANCE_REVIEW_INVALID",
                                f"{source_id}: entrance is outside rawSize")
    if not (0 <= entrance["x"] < raw_size["width"] and 0 <= entrance["z"] < raw_size["depth"]):
        raise ImportFailure("TREK_TEMPLATE_ENTRANCE_REVIEW_INVALID",
                            f"{source_id}: entrance is outside rawSize")
    if not (0 <= evidence["x"] < raw_size["width"]
            and 0 <= evidence["y"] < raw_size["height"]
            and 0 <= evidence["z"] < raw_size["depth"]):
        raise ImportFailure("TREK_TEMPLATE_ENTRANCE_REVIEW_INVALID",
                            f"{source_id}: evidence is outside rawSize")
    distance = abs(entrance["x"] - evidence["x"]) + abs(entrance["z"] - evidence["z"])
    if distance > 2:
        raise ImportFailure("TREK_TEMPLATE_ENTRANCE_REVIEW_INVALID",
                            f"{source_id}: evidence is {distance} blocks from entrance")

    expected_name = evidence["block"]
    actual_name = "minecraft:air"
    position = (evidence["x"], evidence["y"], evidence["z"])
    palette = nbt["palette"]
    for block in nbt["blocks"]:
        if tuple(int(value) for value in block["pos"]) == position:
            actual_name = str(palette[int(block["state"])] ["Name"])
            break
    if actual_name != expected_name:
        raise ImportFailure("TREK_TEMPLATE_ENTRANCE_EVIDENCE_MISMATCH",
                            f"{source_id}: {position} expected={expected_name}, actual={actual_name}")
    kind = evidence["kind"]
    if kind == "door" and ("_door" not in actual_name or "trapdoor" in actual_name):
        raise ImportFailure("TREK_TEMPLATE_ENTRANCE_REVIEW_INVALID",
                            f"{source_id}: door evidence is {actual_name}")
    if kind == "gate" and "fence_gate" not in actual_name:
        raise ImportFailure("TREK_TEMPLATE_ENTRANCE_REVIEW_INVALID",
                            f"{source_id}: gate evidence is {actual_name}")


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


def strip_marker_jigsaws(nbt: nbtlib.File) -> int:
    try:
        connectors = replace_jigsaws_with_final_state(
            nbt, allowed=lambda value: value["pool"].startswith(ALLOWED_MARKER_PREFIX))
        return len(connectors)
    except JigsawSanitizeFailure as ex:
        if ex.code == "JIGSAW_CONNECTOR_NOT_APPROVED":
            raise ImportFailure("TREK_TEMPLATE_STRUCTURAL_JIGSAW_REJECTED", ex.detail) from ex
        if ex.code == "JIGSAW_FINAL_STATE_INVALID":
            raise ImportFailure("TREK_TEMPLATE_FINAL_STATE_INVALID", ex.detail) from ex
        if ex.code == "JIGSAW_REMAINS_AFTER_SANITIZE":
            raise ImportFailure("TREK_TEMPLATE_JIGSAW_REMAINS", ex.detail) from ex
        raise ImportFailure("TREK_TEMPLATE_JIGSAW_INVALID", ex.detail) from ex


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
    configured_id = entry.get("sourceConfiguredId")
    direct_template_id = entry.get("sourceTemplateId")
    source_identity = configured_id or direct_template_id
    if configured_id:
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
    else:
        start_pool = ""
        source_nbt = direct_template_id
        processor_id = "minecraft:empty"
    try:
        source_bytes = archive.read(resource_path(source_nbt, "structures", ".nbt"))
    except KeyError as ex:
        raise ImportFailure("TREK_TEMPLATE_SOURCE_ENTRY_MISSING", source_nbt) from ex
    nbt = decode_nbt(source_bytes)
    if direct_template_id and entry["connectorPolicy"] == "replace_all_with_final_state":
        marker_count = len(replace_jigsaws_with_final_state(nbt, allowed=lambda _value: True))
    elif direct_template_id and entry["connectorPolicy"] == "none":
        marker_count = 0
        palette = nbt["palette"]
        if any(str(palette[int(block["state"])] ["Name"]) == "minecraft:jigsaw"
               for block in nbt["blocks"]):
            raise ImportFailure("TREK_TEMPLATE_CONNECTOR_POLICY_INVALID",
                                f"{source_identity}: connectorPolicy=none but Jigsaw remains")
    else:
        marker_count = strip_marker_jigsaws(nbt)
    entity_count = clear_entities(nbt)
    rules = load_processor_rules(archive, processor_id)
    processor_replacements = bake_processor(
        nbt, rules,
        [manifest["importerVersion"], jar_hash, source_identity, processor_id],
    )
    raw_size_values = [int(value) for value in nbt["size"]]
    raw_size = dict(zip(("width", "height", "depth"), raw_size_values))
    validate_confirmed_entrance(nbt, entry, raw_size)
    source_path = entry.get("targetPath") or source_identity.split(":", 1)[1]
    target_path = f"{manifest['targetPrefix'].strip('/')}/{source_path}"
    target_ref = f"{manifest['targetNamespace']}:{target_path}"
    return ImportedTemplate(
        manifest_entry=entry,
        entrance_review_version=entry.get("entranceReviewVersion", manifest["entranceReviewVersion"]),
        source_configured_id=source_identity,
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
                "entranceId": entrance["entranceId"],
                "position": {"x": entrance["x"], "z": entrance["z"]},
                "direction": entrance["direction"],
            } for entrance in entry_entrances(source)],
            "terrainPosePolicy": "structure_start_beard_thin",
            "supportPolicy": "full_footprint_support",
            "clearanceBlocks": source["clearanceBlocks"],
        })
    return {"schemaVersion": CATALOG_SCHEMA, "templates": entries}


def build_structure_profiles(manifest: dict[str, Any],
                             imported: list[ImportedTemplate]) -> list[dict[str, Any]]:
    profiles: list[dict[str, Any]] = []
    for item in imported:
        groups = profile_term_groups(manifest, item.manifest_entry)
        namespace, path = item.target_ref.split(":", 1)
        profiles.append({
            "structureId": item.target_ref,
            "sourceProfileRef": f"{namespace}://{path}",
            "reviewState": "approved",
            "functionTerms": groups["functionTerms"],
            "planningRoleTerms": [],
            "terrainModes": ["SURFACE"],
            "styleTerms": groups["styleTerms"],
        })
    return profiles


def write_profile_package(profile_dir: Path, manifest: dict[str, Any],
                          imported: list[ImportedTemplate]) -> dict[str, Path]:
    profile_dir = profile_dir.resolve()
    profile_dir.mkdir(parents=True, exist_ok=True)
    profiles = build_structure_profiles(manifest, imported)
    profile_path = profile_dir / "StructureProfile.jsonl"
    vocabulary_path = profile_dir / "StructureVocabulary.snapshot.json"
    source_path = profile_dir / "TerraSenseStructureProfileSource.official.json"
    profile_path.write_text(
        "".join(json.dumps(profile, ensure_ascii=False, separators=(",", ":")) + "\n"
                for profile in profiles),
        encoding="utf-8",
    )
    profile_export = manifest["profileExport"]
    used_term_ids = {term for profile in profiles
                     for field in ("functionTerms", "planningRoleTerms", "styleTerms")
                     for term in profile[field]}
    vocabulary_terms = [term for term in profile_export["vocabularyTerms"]
                        if term["term_id"] in used_term_ids]
    vocabulary = {
        "schemaVersion": VOCABULARY_SCHEMA,
        "snapshotId": profile_export["profileSetId"],
        "exportedAt": profile_export["exportedAt"],
        "sourceWorkspace": profile_export["sourceWorkspace"],
        "terms": vocabulary_terms,
        "quality": {
            "approvedTerms": len(vocabulary_terms),
            "sourceTerms": len(profile_export["vocabularyTerms"]),
        },
    }
    vocabulary_path.write_text(json.dumps(vocabulary, ensure_ascii=False, indent=2) + "\n",
                               encoding="utf-8")
    source = {
        "schemaVersion": PROFILE_SOURCE_SCHEMA,
        "sourceType": "structure_profile_jsonl",
        "catalogMode": "official",
        "profilePath": str(profile_path),
        "vocabularySnapshotPath": str(vocabulary_path),
        "terrasenseRunId": profile_export["terrasenseRunId"],
        "allowDebugUnapproved": False,
        "quality": {"exportedProfiles": len(profiles), "skipped": 0, "warnings": 0},
    }
    source_path.write_text(json.dumps(source, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"profiles": profile_path, "vocabulary": vocabulary_path, "source": source_path}


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
            "sourceKind": "direct_template" if "sourceTemplateId" in item.manifest_entry else "configured_structure",
            "sourceTemplateId": item.manifest_entry.get("sourceTemplateId", ""),
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
            "entranceReviewVersion": item.entrance_review_version,
            "entranceConfirmed": item.manifest_entry["entranceConfirmed"],
            "entrance": item.manifest_entry["entrance"],
            "entranceEvidence": item.manifest_entry["entranceEvidence"],
            "outputFile": str(output),
            "previews": previews,
        })
    return structure_root / "city" / "trek", report_entries


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--save-dir", type=Path,
                        help="Explicit Minecraft save directory; latest-save guessing is forbidden.")
    parser.add_argument("--profile-dir", type=Path,
                        help="Export fixed-template TerraSense profiles without requiring a world write.")
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
        if args.save_dir is None and args.profile_dir is None:
            raise ImportFailure("TREK_TEMPLATE_OUTPUT_TARGET_REQUIRED",
                                "pass --save-dir and/or --profile-dir")
        save_dir = args.save_dir.resolve() if args.save_dir is not None else None
        profile_dir = args.profile_dir.resolve() if args.profile_dir is not None else None
        if save_dir is not None and not save_dir.is_dir():
            raise ImportFailure("TREK_TEMPLATE_SAVE_DIR_MISSING", str(save_dir))
        jar_hash, imported = inspect_all(jar_path, manifest)
        if args.dry_run:
            print(json.dumps({
                "status": "dry_run_ok",
                "sourceJar": str(jar_path),
                "sourceJarSha256": jar_hash,
                "templateCount": len(imported),
                "targetSave": str(save_dir) if save_dir else None,
                "profileDir": str(profile_dir) if profile_dir else None,
                "targets": [item.target_ref for item in imported],
            }, ensure_ascii=False, indent=2))
            return 0
        profile_package = (write_profile_package(profile_dir, manifest, imported)
                           if profile_dir is not None else None)
        if save_dir is None:
            print(json.dumps({
                "status": "profiles_exported",
                "templateCount": len(imported),
                "profilePackage": {key: str(path) for key, path in profile_package.items()},
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
            "profilePackage": ({key: str(path) for key, path in profile_package.items()}
                               if profile_package else None),
        }, ensure_ascii=False, indent=2))
        return 0
    except ImportFailure as ex:
        print(json.dumps({"status": "failed", "reasonCode": ex.code, "detail": ex.detail},
                         ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
