"""Package an assembled, isolated Hermes source runtime; see runtime/hermes/README.md."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("runtime", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    root = args.runtime.resolve()
    for name in ("python/python.exe", "node/node.exe", "hermes/hermes_cli/main.py",
                 "hermes/agent/opencode_affinity.py", "site-packages/mcp/__init__.py",
                 "site-packages/aiohttp/__init__.py", "geomantia-runtime.json"):
        if not (root / name).is_file():
            raise RuntimeError(f"Missing runtime component: {name}")
    metadata = json.loads((root / "geomantia-runtime.json").read_text(encoding="utf-8"))
    if root.name != f"hermes-{metadata['version']}-win-x64":
        raise RuntimeError("Runtime directory/version mismatch")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(root.rglob("*")):
            if not path.is_file() or "__pycache__" in path.parts or path.suffix in (".pyc", ".pyo"):
                continue
            # pip-generated command wrappers contain build-machine paths; we launch source via Python.
            relative = path.relative_to(root)
            if relative.parts[:2] == ("site-packages", "bin"):
                continue
            info = zipfile.ZipInfo((Path(root.name) / relative).as_posix(), (2026, 9, 14, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
    with zipfile.ZipFile(args.output) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("Archive integrity check failed")
    digest = hashlib.file_digest(args.output.open("rb"), "sha256").hexdigest().upper()
    print(f"SHA256={digest}\nBytes={args.output.stat().st_size}")


if __name__ == "__main__":
    main()
