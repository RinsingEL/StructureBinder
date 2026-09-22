"""Build selected authored assets by exact ID or family prefix."""
import argparse
import json
from functools import partial

from .server import CATALOG,TOOL
from .transport import steam_station,passenger_quay
from .agriculture import farm
from .samples import bakery
from .shops import shop
from .desert import desert
from .oasis_life import home,garden
from .validate import validate

BUILDERS={"SR-01-v01":("01_steam_rail",steam_station),"WT-01-v01":("02_waterway_trade",passenger_quay)}
BUILDERS["SR-F01-v01"]=("01_steam_rail",bakery)
BUILDERS.update({f"SR-F01-v{i:02d}":("01_steam_rail",partial(shop,i)) for i in range(2,9)})
BUILDERS.update({f"SR-F02-v{i:02d}":("01_steam_rail",partial(farm,i)) for i in range(1,7)})
BUILDERS.update({f"DS-{i:02d}-v01":("03_desert_stars",partial(desert,i)) for i in (1,3,6,9,10,11,12)})
BUILDERS.update({f"DS-04-v{i:02d}":("03_desert_stars",partial(home,i)) for i in range(1,7)})
BUILDERS.update({f"DS-F02-v{i:02d}":("03_desert_stars",partial(garden,i)) for i in range(1,7)})


def main():
    p=argparse.ArgumentParser();p.add_argument("ids",nargs="+",help="Asset ID, family ID, or all")
    args=p.parse_args()
    selected=[key for key in BUILDERS if "all" in args.ids or key in args.ids or any(key.startswith(v+"-v") for v in args.ids)]
    unmatched=[v for v in args.ids if v!="all" and not any(key==v or key.startswith(v+"-v") for key in BUILDERS)]
    if unmatched:p.error("No matching authored assets: "+", ".join(unmatched))
    if not selected:p.error("No matching authored assets")
    registry=json.loads((TOOL/".cache/registry.json").read_text(encoding="utf-8"))
    failed=False
    for key in selected:
        civ,fn=BUILDERS[key];m=fn();destination=CATALOG/civ/"models"/key
        m.export(destination,registry);report=validate(destination,registry)
        print(json.dumps(dict(id=key,blocks=report["visible_blocks"],passed=report["passed"],navigation_ok=report["navigation"]["passed"],warnings=report["warnings"],errors=report["errors"]),ensure_ascii=False),flush=True)
        failed|=not report["passed"] or not report["navigation"]["passed"] or bool(report["warnings"])
    raise SystemExit(1 if failed else 0)


if __name__=="__main__":main()
