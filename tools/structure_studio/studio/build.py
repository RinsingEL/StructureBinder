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
from .desert_fill_homes import home
from .desert_fill_farms import garden
from .desert_fill_beasts import beast_yard
from .desert_fill_shelters import vineyard,trade_shelter
from .desert_fill_merchants import merchant
from .desert_fill_glassworks import glassworks
from .desert_fill_inns import inn
from .desert_fill_shops import shop as oasis_shop
from .waterway import waterway
from .waterway_trade import warehouse,merchant as waterway_merchant
from .mountain_forge import BUILDERS as MOUNTAIN_CORES
from .forest_symbiosis import BUILDERS as FOREST_CORES
from .cloud_navigation import BUILDERS as CLOUD_CORES
from .mountain_life import BUILDERS as MOUNTAIN_LIFE
from .mountain_trade import BUILDERS as MOUNTAIN_TRADE
from .mountain_outside import BUILDERS as MOUNTAIN_OUTSIDE
from .forest_life import BUILDERS as FOREST_LIFE
from .forest_crafts import BUILDERS as FOREST_CRAFTS
from .forest_gardens import BUILDERS as FOREST_GARDENS
from .cloud_living import BUILDERS as CLOUD_LIVING
from .cloud_food import BUILDERS as CLOUD_FOOD
from .cloud_market import BUILDERS as CLOUD_MARKET
from .arcane_academy import BUILDERS as ARCANE_CORES
from .arcane_gardens import BUILDERS as ARCANE_GARDENS
from .steppe_caravans import BUILDERS as STEPPE_CORES
from .northern_seafarers import BUILDERS as NORTHERN_CORES
from .northern_fill_homes import BUILDERS as NORTHERN_HOMES
from .northern_fill_fishers import BUILDERS as NORTHERN_FISHERS
from .northern_fill_workshops import BUILDERS as NORTHERN_WORKSHOPS
from .northern_fill_shops import BUILDERS as NORTHERN_SHOPS
from .northern_fill_storage_inns import BUILDERS as NORTHERN_STORAGE_INNS
from .northern_fill_gardens_sheds import BUILDERS as NORTHERN_GARDENS_SHEDS
from .tidal_coral import BUILDERS as TIDAL_CORES
from .steppe_crafts import BUILDERS as STEPPE_CRAFTS
from .steppe_gardens import BUILDERS as STEPPE_GARDENS
from .memorial_life import BUILDERS as MEMORIAL_LIFE
from .memorial_crafts import BUILDERS as MEMORIAL_CRAFTS
from .memorial_gardens import BUILDERS as MEMORIAL_GARDENS
from .mediterranean_living import BUILDERS as MEDITERRANEAN_LIVING
from .mediterranean_shops import BUILDERS as MEDITERRANEAN_SHOPS
from .mediterranean_gardens import BUILDERS as MEDITERRANEAN_GARDENS
from .validate import validate
from .chinese import BUILDERS as CHINESE_CORES
from .chinese_culture import BUILDERS as CHINESE_CULTURE
from .chinese_inn import BUILDERS as CHINESE_INNS
from .chinese_palace import BUILDERS as CHINESE_PALACES
from .chinese_parts import CATALOG_DIR as CHINESE_CATALOG
from .chinese_daily import BUILDERS as CHINESE_DAILY
from .chinese_landscape import BUILDERS as CHINESE_LANDSCAPE
from .arcane_reborn import BUILDERS as ARCANE_REBORN
from .elven_reborn import BUILDERS as ELVEN_REBORN
from .dwarven_reborn import BUILDERS as DWARVEN_REBORN
from .european_reborn import BUILDERS as EUROPEAN_REBORN
from .arcane_revision2 import BUILDERS as ARCANE_REVISION2
from .dwarven_revision2 import BUILDERS as DWARVEN_REVISION2
from .elven_revision2 import BUILDERS as ELVEN_REVISION2
from .european_revision2 import BUILDERS as EUROPEAN_REVISION2

BUILDERS={"SR-01-v01":("01_steam_rail",steam_station),"WT-01-v01":("02_waterway_trade",passenger_quay)}
BUILDERS["SR-F01-v01"]=("01_steam_rail",bakery)
BUILDERS.update({f"SR-F01-v{i:02d}":("01_steam_rail",partial(shop,i)) for i in range(2,9)})
BUILDERS.update({f"SR-F02-v{i:02d}":("01_steam_rail",partial(farm,i)) for i in range(1,7)})
BUILDERS.update({f"DS-{i:02d}-v01":("03_desert_stars",partial(desert,i)) for i in (1,3,6,9,10,11,12)})
BUILDERS.update({f"DS-04-v{i:02d}":("03_desert_stars",partial(home,i)) for i in range(1,7)})
BUILDERS.update({f"DS-F02-v{i:02d}":("03_desert_stars",partial(garden,i)) for i in range(1,7)})
for family,builder in (("DS-02",beast_yard),("DS-F03",vineyard),("DS-F04",trade_shelter),("DS-05",merchant),("DS-07",glassworks),("DS-08",inn)):
    BUILDERS.update({f"{family}-v{i:02d}":("03_desert_stars",partial(builder,i)) for i in range(1,5)})
BUILDERS.update({f"DS-F01-v{i:02d}":("03_desert_stars",partial(oasis_shop,i)) for i in range(1,9)})
BUILDERS.update({f"WT-{i:02d}-v01":("02_waterway_trade",partial(waterway,i)) for i in (3,4,9,10,11,12)})
for family,builder in (("WT-02",warehouse),("WT-05",waterway_merchant)):
    BUILDERS.update({f"{family}-v{i:02d}":("02_waterway_trade",partial(builder,i)) for i in range(1,5)})
for civilization,builders in (("04_mountain_forge",MOUNTAIN_CORES),("05_forest_symbiosis",FOREST_CORES),("06_cloud_navigation",CLOUD_CORES)):
    BUILDERS.update({key:(civilization,builder) for key,builder in builders.items()})
for civilization,groups in (
    ("04_mountain_forge",(MOUNTAIN_LIFE,MOUNTAIN_TRADE,MOUNTAIN_OUTSIDE)),
    ("05_forest_symbiosis",(FOREST_LIFE,FOREST_CRAFTS,FOREST_GARDENS)),
    ("06_cloud_navigation",(CLOUD_LIVING,CLOUD_FOOD,CLOUD_MARKET)),
):
    for builders in groups:
        BUILDERS.update({key:(civilization,builder) for key,builder in builders.items()})

for civilization,builders in (("07_arcane_academy",ARCANE_CORES),("08_steppe_caravans",STEPPE_CORES),("09_northern_seafarers",NORTHERN_CORES)):
    BUILDERS.update({key:(civilization,builder) for key,builder in builders.items()})

BUILDERS.update({key:("07_arcane_academy",builder) for key,builder in ARCANE_GARDENS.items()})
BUILDERS.update({key:("10_tidal_coral",builder) for key,builder in TIDAL_CORES.items()})
for builders in (STEPPE_CRAFTS,STEPPE_GARDENS):
    BUILDERS.update({key:("08_steppe_caravans",builder) for key,builder in builders.items()})


for builders in (NORTHERN_HOMES, NORTHERN_FISHERS, NORTHERN_WORKSHOPS, NORTHERN_SHOPS, NORTHERN_STORAGE_INNS, NORTHERN_GARDENS_SHEDS):
    BUILDERS.update({key:("09_northern_seafarers",builder) for key,builder in builders.items()})

for builders in (MEMORIAL_LIFE, MEMORIAL_CRAFTS, MEMORIAL_GARDENS):
    BUILDERS.update({key:("11_memorial_lanterns",builder) for key,builder in builders.items()})

for builders in (MEDITERRANEAN_LIVING, MEDITERRANEAN_SHOPS, MEDITERRANEAN_GARDENS):
    BUILDERS.update({key:("02_waterway_trade",builder) for key,builder in builders.items()})

BUILDERS.update({key:(CHINESE_CATALOG,builder) for key,builder in CHINESE_CORES.items()})
BUILDERS.update({key:(CHINESE_CATALOG,builder) for key,builder in CHINESE_CULTURE.items()})
BUILDERS.update({key:(CHINESE_CATALOG,builder) for key,builder in CHINESE_INNS.items()})
BUILDERS.update({key:(CHINESE_CATALOG,builder) for key,builder in CHINESE_PALACES.items()})

for builders in (CHINESE_DAILY,CHINESE_LANDSCAPE):
    BUILDERS.update({key:(CHINESE_CATALOG,fn) for key,fn in builders.items()})

BUILDERS.update({key:("R02_arcane_reborn",fn) for key,fn in ARCANE_REBORN.items()})

BUILDERS.update({key:("R01_elven_reborn",fn) for key,fn in ELVEN_REBORN.items()})

BUILDERS.update({key:("R03_dwarven_reborn",builder) for key,builder in DWARVEN_REBORN.items()})

BUILDERS.update({key:("R04_european_reborn",fn) for key,fn in EUROPEAN_REBORN.items()})

BUILDERS.update({key:("R02_arcane_reborn",fn) for key,fn in ARCANE_REVISION2.items()})
BUILDERS.update({key:("R03_dwarven_reborn",fn) for key,fn in DWARVEN_REVISION2.items()})
BUILDERS.update({key:("R01_elven_reborn",fn) for key,fn in ELVEN_REVISION2.items()})
BUILDERS.update({key:("R04_european_reborn",fn) for key,fn in EUROPEAN_REVISION2.items()})

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
