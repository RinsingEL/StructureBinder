"""Import selected tree structures, excluding display ground and surrounding furniture.

Usage: python scripts/build_roadside_tree_templates.py <各种各样的树 directory>
Requires litemapy and nbtlib. Never modifies the source files.
"""
import gzip
import hashlib
import json
import sys
from pathlib import Path
from litemapy import Schematic
from nbtlib import Compound, List, Int, String, File


def main():
    source_dir = Path(sys.argv[1]).resolve()
    destination = Path(__file__).resolve().parents[1] / "src/main/resources/data/geomantia/structures/roadside"
    destination.mkdir(parents=True, exist_ok=True)
    manifest = []
    for name, filename in (("small_oak", "aq17 小橡树.litematic"),
                           ("small_conifer", "bh34 小深色橡树.litematic"),
                           ("park_oak", "by51 小公园橡树.litematic")):
        source = source_dir / filename
        regions = Schematic.load(str(source)).regions
        if len(regions) != 1:
            raise ValueError(f"Expected one region: {filename}")
        original = next(iter(regions.values())).to_structure_nbt(mc_version=3465)
        palette, remap = [], {}
        for i, state in enumerate(original["palette"]):
            block = str(state["Name"])
            if not block.endswith(("_log", "_wood", "_leaves")):
                continue
            state = Compound(state)
            if block.endswith("_leaves"):
                properties = Compound(state.get("Properties", {}))
                properties["persistent"] = String("true")
                state["Properties"] = properties
            remap[i] = len(palette)
            palette.append(state)
        blocks = [(tuple(map(int, b["pos"])), remap[int(b["state"])])
                  for b in original["blocks"] if int(b["state"]) in remap]
        if not blocks:
            raise ValueError(f"No tree blocks: {filename}")
        minimum = [min(p[axis] for p, _ in blocks) for axis in range(3)]
        size = [max(p[axis] for p, _ in blocks) - minimum[axis] + 1 for axis in range(3)]
        normalized = [(tuple(p[a] - minimum[a] for a in range(3)), state) for p, state in blocks]
        roots = [p for p, state in normalized if p[1] == 0
                 and str(palette[state]["Name"]).endswith(("_log", "_wood"))]
        if not roots:
            raise ValueError(f"No grounded trunk: {filename}")
        root = min(roots, key=lambda p: sum((p[a] - sum(r[a] for r in roots) / len(roots)) ** 2
                                           for a in (0, 2)))
        output = File(Compound({"DataVersion": Int(3465), "size": List[Int](size),
            "palette": List[Compound](palette), "entities": List[Compound](),
            "blocks": List[Compound]([Compound({"pos": List[Int](p), "state": Int(state)})
                                      for p, state in sorted(normalized)])}), gzipped=True)
        path = destination / (name + ".nbt")
        output.save(path)
        path.write_bytes(gzip.compress(gzip.decompress(path.read_bytes()), mtime=0))
        manifest.append({"templateRef": "geomantia:roadside/" + name, "sourceFile": filename,
            "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "size": size,
            "root": list(root), "blockCount": len(normalized)})
    (destination / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                               encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=True))


if __name__ == "__main__":
    main()
