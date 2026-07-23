import json
from pathlib import Path

import nbtlib


ROOT = Path(__file__).resolve().parents[5]
TASK_ROOT = Path(__file__).resolve().parent
NBT_ROOT = ROOT / "run/saves/落地测试/generated/geomantia/structures/city/stubbs"
METADATA_PATH = TASK_ROOT / "template_metadata_live.json"
DEBUG_CATALOG_PATH = TASK_ROOT / "current_town_structure_debug_catalog.json"
TEMPLATE_CATALOG_PATH = TASK_ROOT / "current_town_template_catalog.json"

ROTATIONS = ["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"]


def relative_ref(template_ref):
    prefix = "geomantia:city/stubbs/"
    if not template_ref.startswith(prefix):
        raise ValueError(f"Unexpected template namespace: {template_ref}")
    return template_ref[len(prefix):]


def semantic(relative):
    category, name = relative.split("/", 1)
    stem = name.removesuffix("_01").removesuffix("_02").removesuffix("_03")
    if category == "civic" and ("tower" in stem or "gate" in stem):
        return "defense_guard_tower" if "tower" in stem else "defense_guard_gate"
    if category == "residential":
        if "village_house_small" in stem:
            return "residential_small_house"
        if "dorm" in stem:
            return "residential_dormitory"
        return "residential_house"
    return f"{category}_{stem}"


def function_term(relative):
    category, name = relative.split("/", 1)
    if category == "civic" and ("tower" in name or "gate" in name):
        return "function.defense"
    return f"function.{category}"


def primary_entrance(relative, nbt):
    palette = nbt["palette"]
    size = [int(value) for value in nbt["size"]]
    candidates = []
    for block in nbt["blocks"]:
        state = palette[int(block["state"])]
        name = str(state["Name"])
        properties = {key: str(value) for key, value in state.get("Properties", {}).items()}
        position = [int(value) for value in block["pos"]]
        if "door" in name and properties.get("half", "lower") == "lower":
            candidates.append((position, properties.get("facing", ""), 0))
        elif "fence_gate" in name:
            candidates.append((position, properties.get("facing", ""), 1))

    for kind in (0, 1):
        pool = [candidate for candidate in candidates if candidate[2] == kind]
        if not pool:
            continue
        min_y = min(candidate[0][1] for candidate in pool)
        pool = [candidate for candidate in pool if candidate[0][1] == min_y]
        groups = []
        unused = set(range(len(pool)))
        while unused:
            stack = [unused.pop()]
            group = []
            while stack:
                index = stack.pop()
                group.append(pool[index])
                position, facing, _ = pool[index]
                for neighbor in list(unused):
                    other_position, other_facing, _ = pool[neighbor]
                    distance = abs(position[0] - other_position[0]) + abs(position[2] - other_position[2])
                    if facing == other_facing and distance == 1:
                        unused.remove(neighbor)
                        stack.append(neighbor)
            groups.append(group)

        direction_order = {"south": 0, "north": 1, "east": 2, "west": 3}

        def score(group):
            center_x = sum(item[0][0] for item in group) / len(group)
            center_z = sum(item[0][2] for item in group) / len(group)
            edge_distance = min(center_x, size[0] - 1 - center_x, center_z, size[2] - 1 - center_z)
            return -len(group), edge_distance, direction_order.get(group[0][1], 9), -center_z, -center_x

        selected = sorted(groups, key=score)[0]
        x = int(sum(item[0][0] for item in selected) / len(selected) + 0.5)
        z = int(sum(item[0][2] for item in selected) / len(selected) + 0.5)
        direction = selected[0][1].upper()
        if direction not in {"NORTH", "SOUTH", "EAST", "WEST"}:
            raise RuntimeError(f"Invalid entrance direction for {relative}: {direction}")
        return {
            "entranceId": relative.replace("/", "_") + "_front",
            "position": {"x": x, "z": z},
            "direction": direction,
        }
    raise RuntimeError(f"No lower door or fence gate found in {relative}")


def clearance(relative):
    if relative.startswith("civic/") or relative in {
        "agriculture/windmill_01",
        "agriculture/barn_windmill_01",
        "commercial/merchant_house_01",
        "commercial/theater_01",
        "commercial/library_01",
    }:
        return 6
    if "village_house_small" in relative:
        return 4
    return 5


def main():
    snapshot = json.loads(METADATA_PATH.read_text(encoding="utf8"))
    metadata = snapshot["templates"]
    unreadable = [item["templateRef"] for item in metadata if not item["readable"]]
    if unreadable:
        raise RuntimeError(f"Unreadable templates: {unreadable}")

    debug_structures = []
    template_entries = []
    for item in metadata:
        template_ref = item["templateRef"]
        relative = relative_ref(template_ref)
        nbt = nbtlib.load(NBT_ROOT / f"{relative}.nbt")
        nbt_size = {name: int(value) for name, value in zip(("width", "height", "depth"), nbt["size"])}
        if nbt_size != item["rawSize"]:
            raise RuntimeError(f"NBT/runtime size mismatch for {template_ref}")

        function = function_term(relative)
        building_semantic = semantic(relative)
        entrance = primary_entrance(relative, nbt)
        if not (0 <= entrance["position"]["x"] < nbt_size["width"]):
            raise RuntimeError(f"Entrance x outside template for {template_ref}")
        if not (0 <= entrance["position"]["z"] < nbt_size["depth"]):
            raise RuntimeError(f"Entrance z outside template for {template_ref}")

        debug_structures.append({
            "structureId": template_ref,
            "profileType": "single",
            "sampleType": "structure_template_nbt",
            "placementKind": "city_template_nbt",
            "footprintMode": "fixed_footprint",
            "semanticTerms": [function, "style.stubbs_medieval", "placement.grounded", "quality.runtime_verified"],
            "functionTerms": [function],
            "styleTerms": ["style.stubbs_medieval"],
            "placementTerms": ["placement.grounded"],
            "usageTerms": ["usage.single_structure"],
            "templateRoleTerms": ["template_role.city_building"],
            "qualityTerms": ["quality.runtime_verified"],
            "allowedRotations": ROTATIONS,
            "clearanceBlocks": clearance(relative),
            "fixedFootprint": {
                "widthBlocks": nbt_size["width"],
                "depthBlocks": nbt_size["depth"],
                "heightBlocks": nbt_size["height"],
            },
        })
        template_entries.append({
            "buildingSemantic": building_semantic,
            "style": "stubbs_medieval",
            "templateId": template_ref,
            "templateRef": template_ref,
            "contentHash": item["templateHash"],
            "variant": "stubbs_v1",
            "rawSize": nbt_size,
            "allowedRotations": ROTATIONS,
            "allowedMirrors": ["NONE"],
            "roadEntrances": [entrance],
            "terrainPosePolicy": "structure_start_beard_thin",
            "supportPolicy": "full_footprint_support",
            "clearanceBlocks": clearance(relative),
        })

    DEBUG_CATALOG_PATH.write_text(json.dumps({
        "schemaVersion": "city_structure_debug_catalog.v0.1",
        "catalogMode": "debug",
        "structures": debug_structures,
    }, indent=2) + "\n", encoding="utf8")
    TEMPLATE_CATALOG_PATH.write_text(json.dumps({
        "schemaVersion": "city_template_catalog.v0.1",
        "templates": template_entries,
    }, indent=2) + "\n", encoding="utf8")
    print(json.dumps({
        "debugCatalog": str(DEBUG_CATALOG_PATH),
        "templateCatalog": str(TEMPLATE_CATALOG_PATH),
        "templateCount": len(template_entries),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
