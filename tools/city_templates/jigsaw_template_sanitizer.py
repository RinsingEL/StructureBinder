#!/usr/bin/env python3
"""Audit and sanitize reviewed standalone structure templates containing Jigsaw blocks."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable

import nbtlib


MANIFEST_SCHEMA = "city_standalone_jigsaw_sanitize_manifest"
AUDIT_SCHEMA = "city_standalone_jigsaw_audit"
SANITIZE_REPORT_SCHEMA = "city_standalone_jigsaw_sanitize_report"
BLOCK_STATE_PATTERN = re.compile(r"^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[([^]]+)\])?$")
RESOURCE_LOCATION_PATTERN = re.compile(r"^([a-z0-9_.-]+):([a-z0-9_./-]+)$")
CARDINAL_DIRECTIONS = {"north", "south", "east", "west"}


class SanitizeFailure(RuntimeError):
    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


@dataclass(frozen=True)
class PreparedTemplate:
    source_file: str
    target_ref: str
    target_file: Path
    encoded: bytes
    source_hash: str
    output_hash: str
    raw_size: dict[str, int]
    connectors: list[dict[str, Any]]
    entity_count: int


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decode_nbt(value: bytes) -> nbtlib.File:
    try:
        raw = gzip.decompress(value) if value.startswith(b"\x1f\x8b") else value
        return nbtlib.File.parse(io.BytesIO(raw))
    except Exception as ex:
        raise SanitizeFailure("JIGSAW_TEMPLATE_NBT_INVALID", str(ex)) from ex


def encode_nbt(value: nbtlib.File) -> bytes:
    output = io.BytesIO()
    value.write(output, byteorder="big")
    return gzip.compress(output.getvalue(), compresslevel=9, mtime=0)


def state_compound(name: str, properties: dict[str, str] | None = None) -> nbtlib.Compound:
    state = nbtlib.Compound({"Name": nbtlib.String(name)})
    if properties:
        state["Properties"] = nbtlib.Compound({
            key: nbtlib.String(value) for key, value in properties.items()
        })
    return state


def state_key(state: nbtlib.Compound) -> tuple[str, tuple[tuple[str, str], ...]]:
    properties = state.get("Properties", {})
    return str(state["Name"]), tuple(sorted(
        (str(key), str(value)) for key, value in properties.items()
    ))


def parse_final_state(value: str) -> nbtlib.Compound:
    match = BLOCK_STATE_PATTERN.fullmatch(value.strip())
    if not match:
        raise SanitizeFailure("JIGSAW_FINAL_STATE_INVALID", value)
    properties: dict[str, str] = {}
    if match.group(2):
        for item in match.group(2).split(","):
            if "=" not in item:
                raise SanitizeFailure("JIGSAW_FINAL_STATE_INVALID", value)
            key, property_value = item.split("=", 1)
            key = key.strip()
            property_value = property_value.strip()
            if not key or not property_value:
                raise SanitizeFailure("JIGSAW_FINAL_STATE_INVALID", value)
            properties[key] = property_value
    return state_compound(match.group(1), properties)


def palette_index(palette: nbtlib.List, state: nbtlib.Compound) -> int:
    key = state_key(state)
    for index, existing in enumerate(palette):
        if state_key(existing) == key:
            return index
    palette.append(state)
    return len(palette) - 1


def template_size(nbt: nbtlib.File) -> dict[str, int]:
    values = nbt.get("size")
    if values is None or len(values) != 3:
        raise SanitizeFailure("JIGSAW_TEMPLATE_SIZE_INVALID", str(values))
    width, height, depth = (int(value) for value in values)
    if width <= 0 or height <= 0 or depth <= 0:
        raise SanitizeFailure("JIGSAW_TEMPLATE_SIZE_INVALID", f"{width},{height},{depth}")
    return {"width": width, "height": height, "depth": depth}


def _cardinal_direction(state: nbtlib.Compound) -> str | None:
    properties = state.get("Properties", {})
    orientation = str(properties.get("orientation", ""))
    front = orientation.split("_", 1)[0]
    if front in CARDINAL_DIRECTIONS:
        return front.upper()
    facing = str(properties.get("facing", ""))
    return facing.upper() if facing in CARDINAL_DIRECTIONS else None


def inspect_jigsaws(nbt: nbtlib.File) -> list[dict[str, Any]]:
    palette = nbt.get("palette")
    blocks = nbt.get("blocks")
    if palette is None or blocks is None:
        raise SanitizeFailure("JIGSAW_TEMPLATE_SHAPE_INVALID", "palette and blocks are required")
    size = template_size(nbt)
    connectors: list[dict[str, Any]] = []
    connector_positions: set[tuple[int, int, int]] = set()
    for block in blocks:
        state_index = int(block["state"])
        if state_index < 0 or state_index >= len(palette):
            raise SanitizeFailure("JIGSAW_TEMPLATE_PALETTE_INDEX_INVALID", str(state_index))
        state = palette[state_index]
        if str(state["Name"]) != "minecraft:jigsaw":
            continue
        position = [int(value) for value in block["pos"]]
        position_key = tuple(position)
        if not (0 <= position[0] < size["width"]
                and 0 <= position[1] < size["height"]
                and 0 <= position[2] < size["depth"]):
            raise SanitizeFailure("JIGSAW_POSITION_OUT_OF_BOUNDS", str(position))
        if position_key in connector_positions:
            raise SanitizeFailure("JIGSAW_POSITION_DUPLICATE", str(position))
        connector_positions.add(position_key)
        block_entity = block.get("nbt")
        if block_entity is None:
            raise SanitizeFailure("JIGSAW_BLOCK_ENTITY_MISSING", str(position))
        final_state = str(block_entity.get("final_state", ""))
        parse_final_state(final_state)
        direction = _cardinal_direction(state)
        connector = {
            "index": len(connectors),
            "position": {"x": position[0], "y": position[1], "z": position[2]},
            "orientation": str(state.get("Properties", {}).get("orientation", "")),
            "direction": direction,
            "name": str(block_entity.get("name", "")),
            "target": str(block_entity.get("target", "")),
            "pool": str(block_entity.get("pool", "")),
            "joint": str(block_entity.get("joint", "")),
            "finalState": final_state,
        }
        connector["roadEntranceCandidate"] = _road_entrance_candidate(connector, size)
        connectors.append(connector)
    return connectors


def _road_entrance_candidate(connector: dict[str, Any],
                             size: dict[str, int]) -> dict[str, Any] | None:
    direction = connector["direction"]
    if direction is None:
        return None
    position = connector["position"]
    x = position["x"]
    z = position["z"]
    boundary_distance = min(x, z, size["width"] - 1 - x, size["depth"] - 1 - z)
    return {
        "sourceJigsawPosition": dict(position),
        "position": {"x": x, "z": z},
        "direction": direction,
        "boundaryDistance": boundary_distance,
        "confirmationRequired": True,
    }


def replace_jigsaws_with_final_state(
        nbt: nbtlib.File,
        allowed: Callable[[dict[str, Any]], bool] | None = None) -> list[dict[str, Any]]:
    connectors = inspect_jigsaws(nbt)
    if allowed is not None:
        for connector in connectors:
            if not allowed(connector):
                position = connector["position"]
                raise SanitizeFailure(
                    "JIGSAW_CONNECTOR_NOT_APPROVED",
                    f"position=({position['x']}, {position['y']}, {position['z']}), "
                    f"pool={connector['pool']}",
                )
    palette = nbt["palette"]
    connector_by_position = {
        (value["position"]["x"], value["position"]["y"], value["position"]["z"]): value
        for value in connectors
    }
    for block in nbt["blocks"]:
        state = palette[int(block["state"])]
        if str(state["Name"]) != "minecraft:jigsaw":
            continue
        position = tuple(int(value) for value in block["pos"])
        connector = connector_by_position[position]
        replacement = parse_final_state(connector["finalState"])
        block["state"] = nbtlib.Int(palette_index(palette, replacement))
        block.pop("nbt", None)
    remaining = inspect_jigsaws(nbt)
    if remaining:
        raise SanitizeFailure("JIGSAW_REMAINS_AFTER_SANITIZE", str(len(remaining)))
    return connectors


def _json_write(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _slug_resource_path(relative: Path) -> str:
    parts: list[str] = []
    for part in relative.with_suffix("").parts:
        normalized = re.sub(r"[^a-z0-9_.-]+", "_", part.lower()).strip("._")
        if not normalized:
            raise SanitizeFailure("JIGSAW_TARGET_PATH_INVALID", relative.as_posix())
        parts.append(normalized)
    return "/".join(parts)


def _target_ref(namespace: str, prefix: str, relative: Path) -> str:
    prefix = prefix.strip("/")
    path = "/".join(value for value in (prefix, _slug_resource_path(relative)) if value)
    target_ref = f"{namespace}:{path}"
    _target_ref_parts(target_ref)
    return target_ref


def _target_ref_parts(target_ref: str) -> tuple[str, str]:
    match = RESOURCE_LOCATION_PATTERN.fullmatch(target_ref)
    if match is None:
        raise SanitizeFailure("JIGSAW_TARGET_REF_INVALID", target_ref)
    namespace, path = match.groups()
    segments = path.split("/")
    if any(not value or value in {".", ".."} for value in segments):
        raise SanitizeFailure("JIGSAW_TARGET_REF_INVALID", target_ref)
    return namespace, path


def _source_files(source: Path) -> tuple[Path, list[Path]]:
    source = source.resolve()
    if source.is_file():
        if source.suffix.lower() != ".nbt":
            raise SanitizeFailure("JIGSAW_SOURCE_NOT_NBT", str(source))
        return source.parent, [source]
    if not source.is_dir():
        raise SanitizeFailure("JIGSAW_SOURCE_NOT_FOUND", str(source))
    files = sorted(path for path in source.rglob("*.nbt") if path.is_file())
    if not files:
        raise SanitizeFailure("JIGSAW_SOURCE_EMPTY", str(source))
    return source, files


def build_audit(source: Path, target_namespace: str,
                target_prefix: str) -> tuple[dict[str, Any], dict[str, Any]]:
    source_root, files = _source_files(source)
    entries: list[dict[str, Any]] = []
    draft_templates: list[dict[str, Any]] = []
    target_refs: set[str] = set()
    for path in files:
        relative = path.relative_to(source_root)
        source_hash = sha256_file(path)
        try:
            target_ref = _target_ref(target_namespace, target_prefix, relative)
            if target_ref in target_refs:
                raise SanitizeFailure("JIGSAW_TARGET_REF_DUPLICATE", target_ref)
            nbt = decode_nbt(path.read_bytes())
            connectors = inspect_jigsaws(nbt)
            size = template_size(nbt)
            candidates = [value["roadEntranceCandidate"] for value in connectors
                          if value["roadEntranceCandidate"] is not None]
            status = "review_required" if connectors else "no_jigsaw"
            entry = {
                "sourceFile": relative.as_posix(),
                "sourceSha256": source_hash,
                "targetRef": target_ref,
                "status": status,
                "rawSize": size,
                "jigsawCount": len(connectors),
                "entityCount": len(nbt.get("entities", [])),
                "connectors": connectors,
                "roadEntranceCandidates": candidates,
            }
            if connectors:
                draft_templates.append({
                    "sourceFile": relative.as_posix(),
                    "sourceSha256": source_hash,
                    "targetRef": target_ref,
                    "standaloneConfirmed": False,
                    "connectorPolicy": "replace_all_with_final_state",
                    "expectedJigsawCount": len(connectors),
                })
            target_refs.add(target_ref)
        except SanitizeFailure as ex:
            entry = {
                "sourceFile": relative.as_posix(),
                "sourceSha256": source_hash,
                "status": "invalid",
                "failureCode": ex.code,
                "failureDetail": ex.detail,
            }
        entries.append(entry)
    report = {
        "schema": AUDIT_SCHEMA,
        "sourceRoot": str(source_root),
        "summary": {
            "templateCount": len(entries),
            "reviewRequiredCount": sum(value["status"] == "review_required" for value in entries),
            "noJigsawCount": sum(value["status"] == "no_jigsaw" for value in entries),
            "invalidCount": sum(value["status"] == "invalid" for value in entries),
        },
        "templates": entries,
    }
    draft = {
        "schema": MANIFEST_SCHEMA,
        "sourceRoot": str(source_root),
        "templates": draft_templates,
    }
    return report, draft


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception as ex:
        raise SanitizeFailure("JIGSAW_MANIFEST_INVALID", str(ex)) from ex
    if value.get("schema") != MANIFEST_SCHEMA:
        raise SanitizeFailure("JIGSAW_MANIFEST_SCHEMA_UNSUPPORTED", str(value.get("schema")))
    templates = value.get("templates")
    if not isinstance(templates, list) or not templates:
        raise SanitizeFailure("JIGSAW_MANIFEST_EMPTY", "templates must be a non-empty array")
    source_files: set[str] = set()
    target_refs: set[str] = set()
    for index, entry in enumerate(templates):
        if not isinstance(entry, dict):
            raise SanitizeFailure("JIGSAW_MANIFEST_ENTRY_INVALID", str(index))
        source_file = entry.get("sourceFile")
        source_hash = entry.get("sourceSha256")
        target_ref = entry.get("targetRef")
        if (not isinstance(source_file, str) or not source_file
                or Path(source_file).is_absolute() or ".." in Path(source_file).parts):
            raise SanitizeFailure("JIGSAW_MANIFEST_SOURCE_INVALID", str(source_file))
        if not isinstance(source_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", source_hash):
            raise SanitizeFailure("JIGSAW_MANIFEST_HASH_INVALID", str(source_hash))
        if not isinstance(target_ref, str):
            raise SanitizeFailure("JIGSAW_TARGET_REF_INVALID", str(target_ref))
        _target_ref_parts(target_ref)
        if entry.get("connectorPolicy") != "replace_all_with_final_state":
            raise SanitizeFailure("JIGSAW_CONNECTOR_POLICY_UNSUPPORTED",
                                  str(entry.get("connectorPolicy")))
        if not isinstance(entry.get("standaloneConfirmed"), bool):
            raise SanitizeFailure("JIGSAW_STANDALONE_REVIEW_MISSING", source_file)
        expected = entry.get("expectedJigsawCount")
        if not isinstance(expected, int) or expected <= 0:
            raise SanitizeFailure("JIGSAW_EXPECTED_COUNT_INVALID", str(expected))
        if source_file in source_files or target_ref in target_refs:
            raise SanitizeFailure("JIGSAW_MANIFEST_DUPLICATE", f"{source_file}, {target_ref}")
        source_files.add(source_file)
        target_refs.add(target_ref)
    return value


def _resolved_source(source_root: Path, relative: str) -> Path:
    root = source_root.resolve()
    path = (root / Path(relative)).resolve()
    try:
        path.relative_to(root)
    except ValueError as ex:
        raise SanitizeFailure("JIGSAW_MANIFEST_SOURCE_INVALID", relative) from ex
    if not path.is_file():
        raise SanitizeFailure("JIGSAW_SOURCE_NOT_FOUND", str(path))
    return path


def _target_file(output_root: Path, target_ref: str) -> Path:
    namespace, path = _target_ref_parts(target_ref)
    root = output_root.resolve()
    target = (root / "data" / namespace / "structures"
              / Path(*path.split("/")).with_suffix(".nbt")).resolve()
    try:
        target.relative_to(root)
    except ValueError as ex:
        raise SanitizeFailure("JIGSAW_TARGET_REF_INVALID", target_ref) from ex
    return target


def prepare_sanitize(source_root: Path, output_root: Path,
                     manifest: dict[str, Any]) -> tuple[list[PreparedTemplate], list[dict[str, Any]]]:
    prepared: list[PreparedTemplate] = []
    skipped: list[dict[str, Any]] = []
    for entry in manifest["templates"]:
        if not entry["standaloneConfirmed"]:
            skipped.append({
                "sourceFile": entry["sourceFile"],
                "targetRef": entry["targetRef"],
                "reason": "standalone_not_confirmed",
            })
            continue
        source = _resolved_source(source_root, entry["sourceFile"])
        source_bytes = source.read_bytes()
        actual_hash = sha256_bytes(source_bytes)
        if actual_hash != entry["sourceSha256"]:
            raise SanitizeFailure("JIGSAW_SOURCE_HASH_MISMATCH",
                                  f"{entry['sourceFile']}: expected={entry['sourceSha256']}, actual={actual_hash}")
        nbt = decode_nbt(source_bytes)
        connectors = inspect_jigsaws(nbt)
        if len(connectors) != entry["expectedJigsawCount"]:
            raise SanitizeFailure("JIGSAW_COUNT_MISMATCH",
                                  f"{entry['sourceFile']}: expected={entry['expectedJigsawCount']}, actual={len(connectors)}")
        replace_jigsaws_with_final_state(nbt)
        encoded = encode_nbt(nbt)
        prepared.append(PreparedTemplate(
            source_file=entry["sourceFile"],
            target_ref=entry["targetRef"],
            target_file=_target_file(output_root, entry["targetRef"]),
            encoded=encoded,
            source_hash=actual_hash,
            output_hash=sha256_bytes(encoded),
            raw_size=template_size(nbt),
            connectors=connectors,
            entity_count=len(nbt.get("entities", [])),
        ))
    return prepared, skipped


def sanitize(source_root: Path, output_root: Path, manifest: dict[str, Any],
             overwrite: bool, dry_run: bool) -> dict[str, Any]:
    prepared, skipped = prepare_sanitize(source_root, output_root, manifest)
    existing = [str(item.target_file) for item in prepared if item.target_file.exists()]
    if existing and not overwrite and not dry_run:
        raise SanitizeFailure("JIGSAW_OVERWRITE_CONFIRMATION_REQUIRED", ", ".join(existing))
    if not dry_run:
        for item in prepared:
            item.target_file.parent.mkdir(parents=True, exist_ok=True)
            temporary: Path | None = None
            try:
                with tempfile.NamedTemporaryFile(
                        dir=item.target_file.parent, prefix=f".{item.target_file.name}.",
                        suffix=".tmp", delete=False) as output:
                    output.write(item.encoded)
                    temporary = Path(output.name)
                temporary.replace(item.target_file)
            finally:
                if temporary is not None and temporary.exists():
                    temporary.unlink()
    return {
        "schema": SANITIZE_REPORT_SCHEMA,
        "sourceRoot": str(source_root.resolve()),
        "outputRoot": str(output_root.resolve()),
        "dryRun": dry_run,
        "existingTargets": existing,
        "summary": {
            "manifestTemplateCount": len(manifest["templates"]),
            "sanitizedCount": len(prepared),
            "skippedUnconfirmedCount": len(skipped),
        },
        "templates": [{
            "sourceFile": item.source_file,
            "sourceSha256": item.source_hash,
            "targetRef": item.target_ref,
            "outputFile": str(item.target_file),
            "outputSha256": item.output_hash,
            "rawSize": item.raw_size,
            "replacedJigsawCount": len(item.connectors),
            "remainingJigsawCount": 0,
            "entityCount": item.entity_count,
            "roadEntranceCandidates": [
                value["roadEntranceCandidate"] for value in item.connectors
                if value["roadEntranceCandidate"] is not None
            ],
            "written": not dry_run,
        } for item in prepared],
        "skipped": skipped,
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    audit = subparsers.add_parser("audit", help="inspect NBT files without changing them")
    audit.add_argument("--source", type=Path, required=True)
    audit.add_argument("--report", type=Path, required=True)
    audit.add_argument("--manifest-draft", type=Path)
    audit.add_argument("--target-namespace", default="geomantia")
    audit.add_argument("--target-prefix", default="city/imported")

    clean = subparsers.add_parser("sanitize", help="write reviewed standalone templates as fixed NBT")
    clean.add_argument("--source-root", type=Path, required=True)
    clean.add_argument("--manifest", type=Path, required=True)
    clean.add_argument("--output-root", type=Path, required=True)
    clean.add_argument("--report", type=Path, required=True)
    clean.add_argument("--overwrite", action="store_true")
    clean.add_argument("--dry-run", action="store_true")
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = _parser().parse_args(list(argv) if argv is not None else None)
    try:
        if args.command == "audit":
            report, draft = build_audit(args.source, args.target_namespace, args.target_prefix)
            _json_write(args.report, report)
            if args.manifest_draft is not None:
                _json_write(args.manifest_draft, draft)
            print(json.dumps(report["summary"], ensure_ascii=False))
            return 1 if report["summary"]["invalidCount"] else 0
        manifest = load_manifest(args.manifest)
        report = sanitize(args.source_root, args.output_root, manifest,
                          overwrite=args.overwrite, dry_run=args.dry_run)
        _json_write(args.report, report)
        print(json.dumps(report["summary"], ensure_ascii=False))
        return 0
    except SanitizeFailure as ex:
        print(json.dumps({"failureCode": ex.code, "failureDetail": ex.detail}, ensure_ascii=False),
              file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
