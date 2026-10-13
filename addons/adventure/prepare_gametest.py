"""Create a fresh disposable GameTest fixture under this repository's build directory."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--mode', choices=('core', 'adventure'), required=True)
parser.add_argument('--directory', required=True)
args = parser.parse_args()
repository = Path(__file__).resolve().parents[2]
build = (repository / 'build').resolve()
target = (repository / args.directory).resolve()
if not target.is_relative_to(build) or target == build or target.exists():
    parser.error('Choose a new, nonexistent subdirectory of this repository build/; existing worlds are never modified.')
config = target / 'config/geomantia'
config.mkdir(parents=True)

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')

write(config / 'planning_area_access.json', {
    'schema': 'geomantia_planning_area_access.v0.1', 'enabled': args.mode == 'core',
    'initialActivityRadiusBlocks': 2048, 'firstCityMinimumDistanceBlocks': 3072,
    'managedDimensions': ['minecraft:overworld'], 'nearSeaDistanceBlocks': 1024, 'oceanRegionSpanBlocks': 4096})
write(config / 'mcp_server.json', {'enabled': False, 'port': 5075, 'flashPort': 5076,
                                  'embeddedAdvanced': False, 'embeddedFlash': False})
write(config / 'provider.json', {'enabled': False})
if args.mode == 'core':
    # Normal random starter selection can place GameTest's fixed origin outside the permitted area.
    write(target / 'world/geomantia_starter_realm.json', {
        'schema': 'geomantia_starter_realm.v1', 'selection': 'land_v1', 'centerBlockX': 0, 'centerBlockZ': 0,
        'spawnX': 0, 'spawnY': 64, 'spawnZ': 0, 'radiusBlocks': 2048, 'shape': 'preparing'})
print(target)
