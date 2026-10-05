"""Archive evidence after an explicit visual review; never invent a verdict.

Input: JSON array of {id, reviewer, checks, reviewed_views, revisions?}. The user
or reviewing agent writes this only after actually viewing the captured images.
"""
import argparse
import json
from pathlib import Path

from .model import sha256,write_json,write_bytes
from .server import TOOL,asset_paths
from .validate import validate


def publish(record,paths,registry):
    key=record["id"];dest=paths[key];source=TOOL/"runtime/captures"/key
    capture=json.loads((source/"capture.json").read_text(encoding="utf-8"))
    if capture["nbt_sha256"]!=sha256(dest/"structure.nbt") or capture["author_sha256"]!=sha256(dest/"author.json"):
        raise ValueError(f"{key}: stale capture; re-render after model/annotation changes")
    if capture["render_errors"] or capture["missing_textures"]:raise ValueError(f"{key}: render failed")
    report=validate(dest,registry)
    if not report["passed"] or not report["navigation"]["passed"] or report["warnings"]:
        raise ValueError(f"{key}: unresolved data/navigation issues")
    if not record.get("reviewer") or not record.get("checks") or not record.get("reviewed_views"):
        raise ValueError(f"{key}: explicit visual findings and reviewed views are required")
    for name in record["reviewed_views"]:
        if name not in capture["shots"] or not (source/f"{name}.png").is_file():
            raise ValueError(f"{key}: missing reviewed evidence {name}")
    target=dest/"previews";target.mkdir(exist_ok=True)
    for name in capture["shots"]:write_bytes(target/f"{name}.png",(source/f"{name}.png").read_bytes())
    write_bytes(target/"capture.json",(source/"capture.json").read_bytes())
    write_json(dest/"review.json",dict(schema="structure-studio.review.v1",id=key,status="accepted",scope="offline_asset_review",
        reviewer=record["reviewer"],nbt_sha256=capture["nbt_sha256"],author_sha256=capture["author_sha256"],
        evidence=[f"previews/{name}.png" for name in record["reviewed_views"]],checks=record["checks"],revisions=record.get("revisions",[]),
        limitations=["离线几何、外观与作者标记验收；未运行 MC，也未接入国度 Mod 运行时目录。",
                     "地形示意用于核对作者的适用条件，不证明真实地图生成、交通、流体或机器行为。",*record.get("limitations",[])]))
    print(f"Published reviewed evidence: {key}")


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("reviews",type=Path);args=p.parse_args()
    records=json.loads(args.reviews.read_text(encoding="utf-8"))
    registry=json.loads((TOOL/".cache/registry.json").read_text(encoding="utf-8"))
    paths=asset_paths(active_only=False)
    for record in records:publish(record,paths,registry)


if __name__=="__main__":main()
