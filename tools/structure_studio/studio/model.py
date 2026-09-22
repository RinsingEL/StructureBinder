"""Deterministic Minecraft 1.20.1 structure authoring.

Unwritten cells are omitted (leave surrounding terrain alone). Explicit air is
serialized, so rooms carved with box(..., 'air') actually clear when placed.
Author annotations are deliberately separate from the mod's runtime schema.
"""
from __future__ import annotations

import gzip
import hashlib
import io
import json
from pathlib import Path
import tempfile

import nbtlib
from nbtlib import Compound, Int, List, String

DATA_VERSION = 3465


def state(value: str) -> tuple[str, tuple[tuple[str, str], ...]]:
    name, _, tail = value.partition("[")
    if not name or (tail and not tail.endswith("]")):
        raise ValueError(f"Invalid block state: {value}")
    props = dict(item.split("=", 1) for item in tail[:-1].split(",") if item)
    return (name if ":" in name else "minecraft:" + name, tuple(sorted(props.items())))


def state_text(value):
    name, props = value
    return name + ("[" + ",".join(f"{k}={v}" for k, v in props) + "]" if props else "")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_bytes(path: Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = None
    try:
        # Readers keep seeing the previous complete artifact until replacement.
        with tempfile.NamedTemporaryFile(mode="wb",
                dir=path.parent, prefix=f".{path.name}.", suffix=".tmp", delete=False) as stream:
            pending = Path(stream.name)
            stream.write(data)
        pending.replace(path)
    finally:
        if pending is not None:
            pending.unlink(missing_ok=True)


def write_json(path: Path, data):
    write_bytes(path, (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))


class Model:
    def __init__(self, asset_id: str, name: str, size: tuple[int, int, int], *, family=None,
                 civilization="fixture", role="structure", terrain=None):
        if len(size) != 3 or any(type(n) is not int or n <= 0 for n in size):
            raise ValueError("Size must be three positive integers")
        self.size = tuple(size)
        self.blocks = {}
        self.block_entities = {}
        self.meta = dict(schema="structure-studio.author.v1", id=asset_id, name=name,
                         family=family or asset_id, civilization=civilization,
                         planning_role=f"planning_role.{role}", size=list(size),
                         terrain=terrain or {}, rooms=[], points=[], connections=[],
                         design_notes=[], differences=[], floors=[], roof_min_y=None,
                         lifecycle="draft", offline_limits=[
                             "不代表运行时世界生成、机器、流体、寻路或交通已经接入。",
                             "作者标记独立保存；导入国度 Mod 须另行映射当前契约。"])

    def inside(self, p):
        return len(p) == 3 and all(type(p[i]) is int and 0 <= p[i] < self.size[i] for i in range(3))

    def set(self, x, y, z, block, nbt=None):
        p = (x, y, z)
        if not self.inside(p):
            raise ValueError(f"{self.meta['id']}: block outside bounds: {p} / {self.size}")
        self.blocks[p] = state(block)
        self.block_entities.pop(p, None)
        if nbt is not None:
            self.block_entities[p] = Compound(nbt)
        return self

    def box(self, a, b, block):
        if any(a[i] > b[i] for i in range(3)):
            raise ValueError(f"Reversed box {a} {b}")
        for y in range(a[1], b[1] + 1):
            for z in range(a[2], b[2] + 1):
                for x in range(a[0], b[0] + 1):
                    self.set(x, y, z, block)
        return self

    def room(self, key, name, a, b, purpose):
        if not self.inside(a) or not self.inside(b) or any(a[i] > b[i] for i in range(3)):
            raise ValueError(f"Invalid room bounds: {key}")
        self.meta["rooms"].append(dict(id=key, name=name, min=list(a), max=list(b), purpose=purpose))
        return self

    def point(self, key, kind, p, name, *, approach=None, **extra):
        if not self.inside(p) or (approach is not None and not self.inside(approach)):
            raise ValueError(f"Point outside bounds: {key}")
        row = dict(id=key, kind=kind, pos=list(p), name=name, **extra)
        if approach is not None:
            row["approach"] = list(approach)
        self.meta["points"].append(row)
        return self

    def door(self, x, y, z, wood="spruce", facing="south", hinge="left"):
        for dy, half in enumerate(("lower", "upper")):
            self.set(x, y + dy, z, f"{wood}_door[facing={facing},half={half},hinge={hinge},open=false,powered=false]")
        return self

    def bed(self, x, y, z, color="red", facing="north"):
        dx, dz = dict(north=(0, -1), south=(0, 1), east=(1, 0), west=(-1, 0))[facing]
        for px, pz, part in ((x, z, "foot"), (x + dx, z + dz, "head")):
            self.set(px, y, pz, f"{color}_bed[facing={facing},part={part},occupied=false]",
                     {"id": String("minecraft:bed")})
        return self

    def export(self, directory: Path, registry=None):
        """Serialize, reopen, then return the actual delivered NBT representation."""
        directory.mkdir(parents=True, exist_ok=True)
        normalized = {}
        for p, (name, props) in self.blocks.items():
            props = dict(props)
            if registry is not None:
                if name not in registry:
                    raise ValueError(f"Unknown 1.20.1 block {name}")
                spec = registry[name]
                defaults = dict(spec["default"])
                for k, v in props.items():
                    if k not in spec["properties"] or v not in spec["properties"][k]:
                        raise ValueError(f"Invalid property: {name}[{k}={v}]")
                defaults.update(props)
                props = defaults
            normalized[p] = (name, tuple(sorted(props.items())))
        palette = sorted(set(normalized.values()))
        indexes = {s: i for i, s in enumerate(palette)}
        states = []
        for name, props in palette:
            item = Compound({"Name": String(name)})
            if props:
                item["Properties"] = Compound({k: String(v) for k, v in props})
            states.append(item)
        blocks = []
        for p in sorted(normalized, key=lambda p: (p[1], p[2], p[0])):
            item = Compound({"pos": List[Int](p), "state": Int(indexes[normalized[p]])})
            if p in self.block_entities:
                item["nbt"] = self.block_entities[p]
            blocks.append(item)
        doc = nbtlib.File(Compound({"DataVersion": Int(DATA_VERSION), "size": List[Int](self.size),
                                  "palette": List[Compound](states), "blocks": List[Compound](blocks),
                                  "entities": List[Compound]()}))
        stream = io.BytesIO()
        doc.write(stream)
        nbt_path = directory / "structure.nbt"
        compressed=bytearray(gzip.compress(stream.getvalue(), mtime=0))
        compressed[9]=255  # Do not encode the build host's OS in the gzip header.
        write_bytes(nbt_path, compressed)
        self.meta["nbt_sha256"] = sha256(nbt_path)
        write_json(directory / "author.json", self.meta)
        return read_structure(nbt_path)


def read_structure(path: Path):
    raw = nbtlib.load(path)
    required = {"size", "palette", "blocks", "DataVersion"}
    if not required <= raw.keys():
        raise ValueError(f"Not a single-palette structure NBT: {path}")
    size = [int(n) for n in raw["size"]]
    if len(size) != 3 or any(n <= 0 for n in size) or size[0] * size[1] * size[2] > 16_000_000:
        raise ValueError("Invalid or unsupported structure size (limit: 16M cells)")
    palette = [dict(name=str(v["Name"]), properties={str(k): str(p) for k, p in v.get("Properties", {}).items()})
               for v in raw["palette"]]
    blocks, seen = [], set()
    for item in raw["blocks"]:
        p, s = tuple(int(n) for n in item["pos"]), int(item["state"])
        if len(p) != 3 or not all(0 <= p[i] < size[i] for i in range(3)):
            raise ValueError(f"Out of bounds block: {p}")
        if p in seen:
            raise ValueError(f"Duplicate position: {p}")
        if not 0 <= s < len(palette):
            raise ValueError(f"Invalid palette index: {s}")
        seen.add(p)
        blocks.append(dict(pos=list(p), state=s))
        if "nbt" in item:
            # SNBT retains numeric tag types for renderer block entities.
            blocks[-1]["nbt"] = item["nbt"].snbt()
    return dict(size=size, palette=palette, blocks=blocks, data_version=int(raw["DataVersion"]),
                entity_count=len(raw.get("entities", [])), sha256=sha256(path))
