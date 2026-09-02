#!/usr/bin/env python3
"""Synchronize reviewed Trek v3 semantics into the active City catalogs."""

from __future__ import annotations

import argparse
import json
from collections import OrderedDict
from pathlib import Path
from typing import Any


ROTATIONS = ["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"]
TREK_V3_VARIANT = "trek_v3_sanitized_v1"

STYLE_KEYS = {
    "style.沙石风": "trek_desert_sandstone",
    "style.wood_stone": "trek_plains_wood_stone",
    "style.吊脚式": "trek_swamp_stilt",
    "style.vanilla_plains": "trek_vanilla_plains",
    "style.木制结构": "trek_swamp_wood",
}

FUNCTION_POOL_KEYS = {
    "function.residential": "residential",
    "function.utility": "utility",
    "function.商业": "commercial",
    "function.农业": "agriculture",
    "function.矿业": "mining",
    "function.防御": "defense",
    "function.行政": "administration",
    "function.transport": "transport",
    "function.科文": "culture_education",
    "function.制图台": "cartography",
}

SEMANTIC_PARTS = (
    ("small_house", "residential_small_house"),
    ("medium_house", "residential_medium_house"),
    ("big_house", "residential_large_house"),
    ("fletcher", "craft_fletcher"),
    ("shepherd", "agriculture_shepherd"),
    ("tannery", "craft_tannery"),
    ("cartographer", "civic_cartographer"),
    ("toolsmith", "craft_toolsmith"),
    ("weaponsmith", "craft_weaponsmith"),
    ("butcher", "commercial_butcher"),
    ("church", "civic_church"),
    ("temple", "civic_temple"),
    ("armorer", "craft_armorer"),
    ("farm", "agriculture_farm"),
    ("stable", "agriculture_stable"),
    ("library", "civic_library"),
    ("mason", "craft_mason"),
)


class CurationFailure(RuntimeError):
    pass


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise CurationFailure(f"Expected JSON object: {path}")
    return value


def load_profiles(path: Path) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        value = json.loads(line)
        structure_id = value.get("structureId")
        if not isinstance(structure_id, str) or not structure_id:
            raise CurationFailure(f"Missing structureId at {path}:{line_number}")
        if structure_id in result:
            raise CurationFailure(f"Duplicate StructureProfile: {structure_id}")
        result[structure_id] = value
    return result


def semantic_for(structure_ref: str) -> str:
    leaf = structure_ref.rsplit("/", 1)[-1]
    for marker, semantic in SEMANTIC_PARTS:
        if marker in leaf:
            return semantic
    raise CurationFailure(f"No reviewed building semantic mapping for {structure_ref}")


def style_for(profile: dict[str, Any], structure_ref: str) -> str:
    for term in profile.get("styleTerms", []):
        if term in STYLE_KEYS:
            return STYLE_KEYS[term]
    raise CurationFailure(f"No supported reviewed style term for {structure_ref}")


def is_fill(profile: dict[str, Any]) -> bool:
    return "planning_role.fill" in profile.get("planningRoleTerms", [])


def candidate_index(reference_catalog: dict[str, Any]) -> tuple[dict[str, str], list[str]]:
    by_template: dict[str, str] = {}
    ordered_refs: list[str] = []
    for item in reference_catalog["structureRefs"]:
        structure_ref = item["structureRef"]
        ordered_refs.append(structure_ref)
        for candidate in item["templateCandidates"]:
            template_id = candidate["templateId"]
            previous = by_template.setdefault(template_id, structure_ref)
            if previous != structure_ref:
                raise CurationFailure(
                    f"Template {template_id} is mapped from both {previous} and {structure_ref}"
                )
    return by_template, ordered_refs


def replace_pool(pools: OrderedDict[str, dict[str, Any]], pool_ref: str,
                 structure_refs: list[str]) -> None:
    if structure_refs:
        pools[pool_ref] = {"poolRef": pool_ref, "structureRefs": structure_refs}
    else:
        pools.pop(pool_ref, None)


def curate(template_catalog: dict[str, Any], reference_catalog: dict[str, Any],
           profiles: dict[str, dict[str, Any]]) -> dict[str, int]:
    template_to_structure, ordered_refs = candidate_index(reference_catalog)
    templates_by_id = {item["templateId"]: item for item in template_catalog["templates"]}
    changed_rotations = 0
    changed_styles = 0
    changed_semantics = 0

    for template in template_catalog["templates"]:
        if template["variant"] != TREK_V3_VARIANT:
            continue
        template_id = template["templateId"]
        structure_ref = template_to_structure.get(template_id)
        if structure_ref is None:
            raise CurationFailure(f"Trek v3 template has no Reference Catalog mapping: {template_id}")
        profile = profiles.get(structure_ref)
        if profile is None:
            raise CurationFailure(f"Trek v3 template has no StructureProfile: {structure_ref}")
        style = style_for(profile, structure_ref)
        semantic = semantic_for(structure_ref)
        if template["allowedRotations"] != ROTATIONS:
            template["allowedRotations"] = list(ROTATIONS)
            changed_rotations += 1
        if template["style"] != style:
            template["style"] = style
            changed_styles += 1
        if template["buildingSemantic"] != semantic:
            template["buildingSemantic"] = semantic
            changed_semantics += 1

    missing_templates = []
    style_by_structure: dict[str, set[str]] = {}
    for item in reference_catalog["structureRefs"]:
        structure_ref = item["structureRef"]
        styles: set[str] = set()
        for candidate in item["templateCandidates"]:
            template = templates_by_id.get(candidate["templateId"])
            if template is None:
                missing_templates.append(candidate["templateId"])
                continue
            styles.add(template["style"])
        style_by_structure[structure_ref] = styles
    if missing_templates:
        raise CurationFailure(f"Unknown template candidates: {sorted(set(missing_templates))}")

    style_profiles = OrderedDict(
        (item["profileRef"], item) for item in reference_catalog["styleProfiles"]
    )
    for style in sorted({template["style"] for template in template_catalog["templates"]}):
        profile_ref = f"style:{style}"
        style_profiles.setdefault(profile_ref, {"profileRef": profile_ref})
    reference_catalog["styleProfiles"] = list(style_profiles.values())

    pools = OrderedDict((item["poolRef"], item) for item in reference_catalog["fillPools"])
    fill_refs = [ref for ref in ordered_refs if ref in profiles and is_fill(profiles[ref])]
    trek_fill = [ref for ref in fill_refs if ref.startswith("trek:") or "/trek/" in ref]
    stubbs_fill = [ref for ref in fill_refs if "/stubbs/" in ref]
    replace_pool(pools, "pool:trek_fill", trek_fill)
    replace_pool(pools, "pool:stubbs_fill", stubbs_fill)
    replace_pool(pools, "pool:mixed_fill", trek_fill + stubbs_fill)

    for term, key in FUNCTION_POOL_KEYS.items():
        members = [ref for ref in fill_refs if term in profiles[ref].get("functionTerms", [])]
        replace_pool(pools, f"pool:{key}", members)

    concrete_styles = sorted({style for styles in style_by_structure.values() for style in styles})
    for style in concrete_styles:
        members = [ref for ref in fill_refs if style in style_by_structure[ref]]
        replace_pool(pools, f"pool:{style}_fill", members)
        for term, function_key in FUNCTION_POOL_KEYS.items():
            intersection = [
                ref for ref in members if term in profiles[ref].get("functionTerms", [])
            ]
            replace_pool(pools, f"pool:{style}_{function_key}_fill", intersection)

    plains_styles = {"trek_plains_wood_stone", "trek_vanilla_plains"}
    for term, suffix in (
        ("function.residential", "residential"),
        ("function.商业", "commercial"),
        ("function.农业", "agriculture"),
    ):
        members = [
            ref for ref in fill_refs
            if style_by_structure[ref] & plains_styles
            and term in profiles[ref].get("functionTerms", [])
        ]
        replace_pool(pools, f"pool:trek_plains_{suffix}_fill", members)

    # The old pool:civic pointed at a mining workshop and had no truthful civic semantics.
    pools.pop("pool:civic", None)
    reference_catalog["fillPools"] = list(pools.values())

    validate(template_catalog, reference_catalog, profiles)
    return {
        "trekV3Templates": sum(
            item["variant"] == TREK_V3_VARIANT for item in template_catalog["templates"]
        ),
        "rotationsChanged": changed_rotations,
        "stylesChanged": changed_styles,
        "semanticsChanged": changed_semantics,
        "styleProfiles": len(reference_catalog["styleProfiles"]),
        "fillPools": len(reference_catalog["fillPools"]),
    }


def validate(template_catalog: dict[str, Any], reference_catalog: dict[str, Any],
             profiles: dict[str, dict[str, Any]]) -> None:
    template_keys = {
        (item["templateId"], item["variant"]) for item in template_catalog["templates"]
    }
    if len(template_keys) != len(template_catalog["templates"]):
        raise CurationFailure("Duplicate templateId + variant in Template Catalog")
    structure_refs = [item["structureRef"] for item in reference_catalog["structureRefs"]]
    if len(set(structure_refs)) != len(structure_refs):
        raise CurationFailure("Duplicate structureRef in Reference Catalog")
    for item in reference_catalog["structureRefs"]:
        if item["structureRef"] not in profiles:
            raise CurationFailure(f"Reference Catalog structure has no StructureProfile: {item['structureRef']}")
        for candidate in item["templateCandidates"]:
            key = (candidate["templateId"], candidate["variantId"])
            if key not in template_keys:
                raise CurationFailure(f"Reference Catalog candidate is unknown: {key}")
    style_refs = {item["profileRef"] for item in reference_catalog["styleProfiles"]}
    for template in template_catalog["templates"]:
        if f"style:{template['style']}" not in style_refs:
            raise CurationFailure(f"No styleProfile for template style: {template['style']}")
        if template["variant"] == TREK_V3_VARIANT and template["allowedRotations"] != ROTATIONS:
            raise CurationFailure(f"Trek v3 rotations are incomplete: {template['templateId']}")
    known_refs = set(structure_refs)
    pool_refs: set[str] = set()
    for pool in reference_catalog["fillPools"]:
        pool_ref = pool["poolRef"]
        if pool_ref in pool_refs:
            raise CurationFailure(f"Duplicate fill pool: {pool_ref}")
        pool_refs.add(pool_ref)
        if not pool["structureRefs"]:
            raise CurationFailure(f"Empty fill pool: {pool_ref}")
        unknown = set(pool["structureRefs"]) - known_refs
        if unknown:
            raise CurationFailure(f"Unknown refs in {pool_ref}: {sorted(unknown)}")


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config-dir", required=True, type=Path)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    template_path = args.config_dir / "template_catalog.json"
    reference_path = args.config_dir / "blueprint_reference_catalog.json"
    profile_path = args.config_dir / "StructureProfile.jsonl"
    template_catalog = load_json(template_path)
    reference_catalog = load_json(reference_path)
    profiles = load_profiles(profile_path)
    summary = curate(template_catalog, reference_catalog, profiles)
    if args.write:
        write_json(template_path, template_catalog)
        write_json(reference_path, reference_catalog)
    summary["written"] = bool(args.write)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
