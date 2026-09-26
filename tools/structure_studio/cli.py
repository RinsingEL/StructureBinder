import argparse
import json
from pathlib import Path

from studio.resources import prepare
from studio.server import TOOL, asset_paths, serve
from studio.validate import validate


def main():
    parser = argparse.ArgumentParser(description="Offline Minecraft structure studio")
    commands = parser.add_subparsers(dest="command", required=True)
    cmd = commands.add_parser("prepare", help="Read local Minecraft 1.20.1 resources")
    cmd.add_argument("--jar", type=Path, required=True)
    cmd = commands.add_parser("serve", help="Start local preview and manual frontage authoring")
    cmd.add_argument("--port", type=int, default=8765)
    cmd = commands.add_parser("validate", help="Validate exported NBT and annotations")
    cmd.add_argument("ids", nargs="*")
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.jar, TOOL / ".cache")
    elif args.command == "serve":
        serve(args.port)
    else:
        registry = json.loads((TOOL / ".cache/registry.json").read_text(encoding="utf-8"))
        paths = asset_paths()
        selected = args.ids or list(paths)
        failed = 0
        for key in selected:
            if key not in paths:
                parser.error(f"Unknown asset: {key}")
            result = validate(paths[key], registry)
            failed += not result["passed"]
            print(json.dumps(dict(id=key, **result), ensure_ascii=False))
        raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
