"""Offline structural checks. Does not pretend to simulate Minecraft gameplay."""
from collections import Counter
import json
from pathlib import Path

from .model import DATA_VERSION, read_structure, write_json
from .navigation import audit

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
DIRECTIONS = dict(north=(0, -1), south=(0, 1), east=(1, 0), west=(-1, 0))
# Vanilla 1.20.1 dirt tag plus AzaleaBlock/BushBlock's additional substrates.
AZALEA_SOIL = {"minecraft:" + name for name in (
    "dirt", "grass_block", "podzol", "coarse_dirt", "mycelium", "rooted_dirt",
    "moss_block", "mud", "muddy_mangrove_roots", "clay", "farmland")}


def validate(directory: Path, registry=None, *, save=True):
    errors, warnings = [], []
    try:
        data = read_structure(directory / "structure.nbt")
        meta = json.loads((directory / "author.json").read_text(encoding="utf-8"))
    except (ValueError, KeyError, OSError) as exc:
        return dict(passed=False, errors=[str(exc)], warnings=[])
    size = data["size"]
    if data["data_version"] != DATA_VERSION:
        errors.append(f"Expected DataVersion {DATA_VERSION}")
    if meta.get("nbt_sha256") != data["sha256"]:
        errors.append("Author annotations refer to a different NBT hash")
    if meta.get("size") != size:
        errors.append("Author/NBT size mismatch")
    grid = {tuple(b["pos"]): data["palette"][b["state"]] for b in data["blocks"]}
    for value in data["palette"]:
        name, props = value["name"], value["properties"]
        if registry is None:
            continue
        spec = registry.get(name)
        if spec is None:
            errors.append(f"Unknown block: {name}")
            continue
        if props.keys() != spec["properties"].keys():
            errors.append(f"Missing/extra state properties: {name}")
        for k, v in props.items():
            if v not in spec["properties"].get(k, []):
                errors.append(f"Invalid property {name}.{k}={v}")
    if registry is None:
        warnings.append("Block registry unavailable; block properties were not verified")
    for (x, y, z), block in grid.items():
        name, props = block["name"], block["properties"]
        if name in {"minecraft:azalea", "minecraft:flowering_azalea"}:
            soil = grid.get((x, y-1, z), {}).get("name")
            if soil not in AZALEA_SOIL:
                errors.append(f"Azalea without valid substrate at {(x,y,z)}: {soil}")
        if name.endswith("_door"):
            half = props.get("half")
            other = grid.get((x, y + (1 if half == "lower" else -1), z), {})
            if other.get("name") != name or other.get("properties", {}).get("half") == half:
                errors.append(f"Unpaired door at {(x,y,z)}")
            elif any(other["properties"].get(k) != props.get(k) for k in ("facing", "hinge", "open", "powered")):
                errors.append(f"Door properties disagree at {(x,y,z)}")
        if name.endswith("_bed"):
            dx, dz = DIRECTIONS.get(props.get("facing"), (0, 0))
            mul = 1 if props.get("part") == "foot" else -1
            other = grid.get((x + dx * mul, y, z + dz * mul), {})
            if (other.get("name") != name or other.get("properties", {}).get("part") == props.get("part")
                    or other.get("properties", {}).get("facing") != props.get("facing")):
                errors.append(f"Unpaired bed at {(x,y,z)}")
    point_ids = [p["id"] for p in meta.get("points", [])]
    if len(point_ids) != len(set(point_ids)):
        errors.append("Duplicate annotation point IDs")
    def inside(p):
        return len(p) == 3 and all(isinstance(p[i], int) and 0 <= p[i] < size[i] for i in range(3))
    for room in meta.get("rooms", []):
        if not inside(room["min"]) or not inside(room["max"]) or any(a > b for a,b in zip(room["min"],room["max"])):
            errors.append(f"Invalid room bounds: {room['id']}")
    for p in meta.get("points", []):
        for field in ("pos", "approach"):
            if field in p and not inside(p[field]):
                errors.append(f"Out of bounds {field}: {p['id']}")
        if p["kind"] == "entrance" or "approach" in p:
            x, y, z = p.get("approach", p["pos"])
            for dy in range(2):
                name = grid.get((x, y + dy, z), {"name": "minecraft:air"})["name"]
                if name not in AIR and not name.endswith("_door"):
                    warnings.append(f"Check clearance at {p['id']} {(x,y+dy,z)}: {name}")
            floor = grid.get((x, y - 1, z), {"name": "minecraft:air"})["name"]
            if floor in AIR or floor in {"minecraft:water", "minecraft:lava"}:
                warnings.append(f"No dry standing support at {p['id']}")
    visible = Counter(b["name"] for b in grid.values() if b["name"] not in AIR)
    if not visible:
        errors.append("Empty structure")
    agriculture=None
    if "agriculture" in meta:
        dry=[];unsupported=[];plant_count=0
        for (x,y,z),b in grid.items():
            if b["name"] not in {"minecraft:wheat","minecraft:carrots","minecraft:potatoes","minecraft:beetroots"}:continue
            plant_count+=1
            if grid.get((x,y-1,z),{}).get("name")!="minecraft:farmland":unsupported.append([x,y,z])
            if not any(grid.get((wx,wy,wz),{}).get("name")=="minecraft:water"
                       for wx in range(x-4,x+5) for wy in (y-1,y) for wz in range(z-4,z+5)):dry.append([x,y,z])
        if unsupported:errors.append(f"Crops without farmland: {unsupported[:12]}")
        if dry:errors.append(f"Irrigated farm has dry crop cells: {dry[:12]}")
        agriculture=dict(planted_cells=plant_count,missing_soil=unsupported,dry_cells=dry,
                         scope="最终 NBT 的四类原版作物、耕地和同层/高一层 4 格水源核对；不模拟光照或生长 tick")
    navigation = audit(data,meta,registry) if registry is not None else {"passed":False,"unsupported":"registry missing"}
    if not navigation["passed"]:
        warnings.append(f"Offline walking needs review: {navigation}")
    report = dict(schema="structure-studio.validation.v1", nbt_sha256=data["sha256"], navigation=navigation,
                  passed=not errors, errors=errors, warnings=warnings, size=size,
                  visible_blocks=sum(visible.values()), explicit_air=sum(1 for b in grid.values() if b["name"] in AIR),
                  palette=len(data["palette"]), block_entities=sum("nbt" in b for b in data["blocks"]),agriculture=agriculture,
                  materials=dict(visible.most_common()),
                  scope="NBT、方块状态、门床配对、杜鹃底座、标记边界和站位净空初筛；楼梯通路、视觉和实际游戏行为另行验收。")
    if save:
        write_json(directory / "validation.json", report)
    return report
