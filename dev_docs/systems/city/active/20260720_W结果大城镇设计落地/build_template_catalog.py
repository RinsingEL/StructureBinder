import json
from pathlib import Path
from urllib.request import Request, urlopen

import nbtlib


ROOT = Path(__file__).resolve().parents[5]
NBT_ROOT = ROOT / "run/saves/落地测试/generated/geomantia/structures/city/stubbs"
OUTPUT = Path(__file__).with_name("template_catalog_large_town.json")
QUERY_URL = "http://127.0.0.1:5000/realm/city/query_template_metadata"

ROTATIONS = ["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"]

TEMPLATES = {
    "agriculture/windmill_01": "agriculture_windmill",
    "agriculture/barn_windmill_01": "agriculture_windmill_barn",
    "agriculture/barn_01": "agriculture_barn",
    "agriculture/farm_storage_house_01": "agriculture_storage",
    "agriculture/horse_stall_02": "agriculture_stable",
    "agriculture/farm_home_01": "agriculture_farm_home",
    "civic/town_hall_01": "civic_town_hall",
    "civic/guard_outpost_01": "civic_guard_hall",
    "civic/guardian_tower_01": "defense_guard_tower",
    "civic/guardian_tower_02": "defense_guard_tower",
    "civic/guard_tower_03": "defense_guard_tower",
    "civic/guard_gate_01": "defense_guard_gate",
    "commercial/tavern_01": "commercial_tavern",
    "commercial/motel_01": "commercial_lodging",
    "commercial/general_store_01": "commercial_general_store",
    "commercial/town_shop_01": "commercial_town_shop",
    "commercial/bakery_01": "commercial_bakery",
    "commercial/bake_shop_01": "commercial_bake_shop",
    "commercial/flower_shop_01": "commercial_flower_shop",
    "commercial/small_butcher_shop_01": "commercial_butcher",
    "commercial/town_workshop_01": "commercial_workshop",
    "commercial/merchant_house_01": "commercial_merchant_house",
    "commercial/library_01": "commercial_library",
    "commercial/theater_01": "commercial_theater",
    "commercial/laundry_facility_01": "commercial_laundry",
    "residential/city_home_01": "residential_house",
    "residential/city_home_02": "residential_house",
    "residential/civilian_house_01": "residential_house",
    "residential/civilian_house_02": "residential_house",
    "residential/civilian_house_03": "residential_house",
    "residential/dorm_for_the_poor_01": "residential_dormitory",
    "residential/household_01": "residential_house",
    "residential/household_02": "residential_house",
    "residential/household_03": "residential_house",
    "residential/household_04": "residential_house",
    "residential/household_05": "residential_house",
    "residential/household_06": "residential_house",
    "residential/household_07": "residential_house",
    "residential/village_house_small_01": "residential_small_house",
    "residential/village_house_small_02": "residential_small_house",
}

# These entries already appeared in successful D4 template catalogs. Preserve their reviewed entrance facts.
REVIEWED_ENTRANCES = {
    "agriculture/barn_01": ("barn_south_gate", 10, 18, "SOUTH"),
    "agriculture/farm_home_01": ("farm_home_north_front", 5, 7, "NORTH"),
    "agriculture/farm_storage_house_01": ("warehouse_south_loading_door", 9, 23, "SOUTH"),
    "civic/town_hall_01": ("town_hall_south_front", 11, 5, "SOUTH"),
    "commercial/general_store_01": ("general_store_front_door", 7, 12, "WEST"),
    "commercial/tavern_01": ("tavern_front_door", 12, 18, "EAST"),
    "commercial/town_workshop_01": ("workshop_front_door", 10, 4, "SOUTH"),
    "residential/civilian_house_03": ("civilian_house_03_front_door", 10, 5, "EAST"),
    "residential/household_01": ("household_01_front_door", 7, 4, "SOUTH"),
    "residential/household_02": ("household_02_front_door", 4, 5, "EAST"),
}


def query_metadata(template_refs):
    payload = json.dumps({"templateRefs": template_refs, "dimensionId": "minecraft:overworld"}).encode()
    request = Request(QUERY_URL, data=payload, headers={"Content-Type": "application/json"}, method="POST")
    with urlopen(request, timeout=60) as response:
        result = json.load(response)
    metadata = {entry["templateRef"]: entry for entry in result["templates"]}
    unreadable = [ref for ref in template_refs if not metadata.get(ref, {}).get("readable")]
    if unreadable:
        raise RuntimeError(f"Unreadable templates: {unreadable}")
    return metadata


def primary_entrance(relative_ref, nbt):
    if relative_ref in REVIEWED_ENTRANCES:
        entrance_id, x, z, direction = REVIEWED_ENTRANCES[relative_ref]
        return entrance_id, x, z, direction

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
        entrance_id = relative_ref.replace("/", "_") + "_front"
        return entrance_id, x, z, direction
    raise RuntimeError(f"No door or fence gate found in {relative_ref}")


def clearance(relative_ref):
    if relative_ref.startswith("civic/") or relative_ref in {
        "agriculture/windmill_01",
        "agriculture/barn_windmill_01",
        "commercial/merchant_house_01",
        "commercial/theater_01",
        "commercial/library_01",
    }:
        return 6
    if "village_house_small" in relative_ref:
        return 4
    return 5


def main():
    template_refs = [f"geomantia:city/stubbs/{relative_ref}" for relative_ref in TEMPLATES]
    metadata = query_metadata(template_refs)
    entries = []
    for relative_ref, semantic in TEMPLATES.items():
        template_ref = f"geomantia:city/stubbs/{relative_ref}"
        nbt = nbtlib.load(NBT_ROOT / f"{relative_ref}.nbt")
        raw_size = {name: int(value) for name, value in zip(("width", "height", "depth"), nbt["size"])}
        if raw_size != metadata[template_ref]["rawSize"]:
            raise RuntimeError(f"NBT/runtime size mismatch for {template_ref}")
        entrance_id, x, z, direction = primary_entrance(relative_ref, nbt)
        entries.append({
            "buildingSemantic": semantic,
            "style": "stubbs_medieval",
            "templateId": template_ref,
            "templateRef": template_ref,
            "contentHash": metadata[template_ref]["templateHash"],
            "variant": "stubbs_v1",
            "rawSize": raw_size,
            "allowedRotations": ROTATIONS,
            "allowedMirrors": ["NONE"],
            "roadEntrances": [{
                "entranceId": entrance_id,
                "position": {"x": x, "z": z},
                "direction": direction,
            }],
            "terrainPosePolicy": "flat_or_small_step",
            "supportPolicy": "full_footprint_support",
            "clearanceBlocks": clearance(relative_ref),
        })

    OUTPUT.write_text(json.dumps({"schemaVersion": "city_template_catalog.v0.1", "templates": entries}, indent=2) + "\n")
    print(json.dumps({"output": str(OUTPUT), "templateCount": len(entries)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
