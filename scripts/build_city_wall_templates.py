"""Import the user-selected guard tower; regenerate the matching wall module.

Usage: python scripts/build_city_wall_templates.py <ac3 tower.litematic>
Requires litemapy and nbtlib. The source file is never modified.
"""
import gzip
import hashlib
import json
import sys
from pathlib import Path
from litemapy import Schematic
from nbtlib import Compound, List, Int, String, File


def state(name):
    return Compound({"Name": String("minecraft:" + name)})


def template(size, blocks, palette):
    return File(Compound({"DataVersion": Int(3465), "size": List[Int](size),
                          "palette": List[Compound](palette), "entities": List[Compound](),
                          "blocks": List[Compound]([Compound({"pos": List[Int](pos), "state": Int(value)})
                                                   for pos, value in sorted(blocks.items())])}), gzipped=True)


source = Path(sys.argv[1]).resolve()
destination = Path(__file__).resolve().parents[1] / "src/main/resources/data/geomantia/structures/city_walls"
destination.mkdir(parents=True, exist_ok=True)
regions = Schematic.load(str(source)).regions
if len(regions) != 1:
    raise ValueError("Expected exactly one guard tower region")
original = next(iter(regions.values())).to_structure_nbt(mc_version=3465)
if list(map(int, original["size"])) != [7, 15, 10]:
    raise ValueError("Unexpected guard tower dimensions; review connections before importing")
palette = list(original["palette"])
stone = len(palette); palette.append(state("stone_bricks"))
air = len(palette); palette.append(state("air"))
blocks = {tuple(map(int, b["pos"])): int(b["state"]) for b in original["blocks"]}
# Connect the lookout floor in four directions. Preserve the stairwell at x=0..1,z=3..4.
for x in range(7):
    for z in range(10):
        if 2 <= x <= 4 or 5 <= z <= 7:
            blocks[x, 9, z] = stone
            blocks[x, 10, z] = air
            blocks[x, 11, z] = air
template([7, 15, 10], blocks, palette).save(destination / "guard_tower.nbt")
wall = {}
for x in range(16):
    for z in range(5):
        for y in range(12):
            wall[x, y, z] = 0 if y <= 9 or (z in (0, 4) and (y == 10 or x % 4 < 2)) else 1
template([16, 12, 5], wall, [state("stone_bricks"), state("air")]).save(destination / "wall_straight.nbt")
for name in ("guard_tower.nbt", "wall_straight.nbt"):
    path = destination / name
    path.write_bytes(gzip.compress(gzip.decompress(path.read_bytes()), mtime=0))
manifest = {"sourceFile": source.name, "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "sourceSize": [7, 15, 10], "towerAnchor": [3, 0, 6], "walkwayFloorY": 9,
            "passageHeadroom": 2, "changes": "Four-way lookout passages; stairwell and roof retained",
            "files": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(destination.glob("*.nbt"))}}
(destination / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(manifest, ensure_ascii=False))
