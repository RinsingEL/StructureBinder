"""Read-only regression for the explicitly inspected development bundle entrance corrections.

Requires nbtlib. This is a ten-template evidence check, not a general Minecraft collision oracle.
Run: python tools/city_templates/verify_validation_02_entrances.py [bundle-directory]
The table also preserves the configuration correction outside the gitignored run directory.
"""
import json
import hashlib
import sys
from pathlib import Path
import nbtlib

# template path: original door/reference x,z; corrected road port x,z; outward direction
CASES = {
    "agriculture/windmill_01": ((14, 4), (14, 0), "NORTH"),
    "agriculture/barn_windmill_01": ((5, 15), (0, 15), "WEST"),
    "civic/town_hall_01": ((11, 5), (11, 0), "NORTH"),
    "commercial/tavern_01": ((12, 18), (0, 18), "WEST"),
    "commercial/general_store_01": ((7, 12), (0, 12), "WEST"),
    "commercial/flower_shop_01": ((13, 10), (13, 0), "NORTH"),
    "commercial/laundry_facility_01": ((19, 17), (19, 27), "SOUTH"),
    "residential/civilian_house_03": ((10, 5), (16, 5), "EAST"),
    "residential/household_01": ((7, 4), (7, 0), "NORTH"),
    "residential/household_02": ((4, 5), (0, 5), "WEST"),
}

def verify(root):
    manifest = json.loads((root / "city_template_content_pack.json").read_text(encoding="utf-8"))
    assert manifest["catalogSha256"] == "sha256:" + hashlib.sha256((root / "template_catalog.json").read_bytes()).hexdigest(), "Content pack catalog hash is stale"
    for entry in manifest["templates"]:
        source = (root / "city_template_content_pack" / entry["sourceFile"]).resolve()
        assert source.is_relative_to((root / "city_template_content_pack").resolve()), "Unsafe content path"
        assert entry["sourceSha256"] == "sha256:" + hashlib.sha256(source.read_bytes()).hexdigest(), entry["templateRef"]
    catalog = json.loads((root / "template_catalog.json").read_text(encoding="utf-8"))
    by_ref = {t["templateRef"]: t for t in catalog["templates"]}
    report = []
    for name, (door, port, direction) in CASES.items():
        ref = "geomantia:city/stubbs/" + name
        t = by_ref[ref]
        nbt = nbtlib.load(root / "city_template_content_pack/geomantia/structures/city/stubbs" / (name + ".nbt"))
        assert "palettes" not in nbt, (ref, "multiple palettes")
        assert list(map(int, nbt["size"])) == [t["rawSize"][k] for k in ("width", "height", "depth")], ref
        blocks = {tuple(map(int, b["pos"])): str(nbt["palette"][int(b["state"])]["Name"]) for b in nbt["blocks"]}
        assert blocks[(door[0], 1, door[1])].endswith("_door"), (ref, "door reference changed")
        e = t["roadEntrances"][0]
        assert e["position"] == dict(zip(("x", "z"), port)) and e["direction"] == direction, (ref, "catalog regressed")
        dx, dz = {"NORTH": (0, -1), "SOUTH": (0, 1), "WEST": (-1, 0), "EAST": (1, 0)}[direction]
        x, z = door[0] + dx, door[1] + dz
        checked = 0
        while True:
            assert blocks[(x, 0, z)] in {"minecraft:gravel", "minecraft:grass_block", "minecraft:smooth_stone"}, (ref, x, z, "ground")
            assert blocks[(x, 1, z)] == blocks[(x, 2, z)] == "minecraft:air", (ref, x, z, "clearance")
            checked += 1
            if (x, z) == port:
                break
            assert checked < max(t["rawSize"]["width"], t["rawSize"]["depth"]), (ref, "port unreachable")
            x += dx
            z += dz
        assert not (0 <= x + dx < t["rawSize"]["width"] and 0 <= z + dz < t["rawSize"]["depth"]), (ref, "not outward boundary")
        report.append({"template": ref, "door": door, "port": port, "direction": direction, "clear_supported_cells": checked})
    return report

if __name__ == "__main__":
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2] / "run/config/structureTemplate/terrasense/pcl_validation_02"
    print(json.dumps({"verified": verify(root)}, ensure_ascii=False, indent=2))
