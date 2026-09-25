"""Read-only loopback server: no write API and no arbitrary filesystem access."""
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

from .model import read_structure, sha256

TOOL = Path(__file__).resolve().parents[1]
REPO = TOOL.parents[1]
CATALOG = REPO / "asset_catalogs/original_civilizations"


def asset_paths():
    roots = [CATALOG, TOOL / "fixtures"]
    return {p.parent.name: p.parent for root in roots for p in root.glob("**/author.json")}


def catalog():
    rows = []
    for key, path in sorted(asset_paths().items()):
        meta = json.loads((path / "author.json").read_text(encoding="utf-8"))
        rows.append({k: meta.get(k) for k in ("id", "name", "family", "civilization", "function_terms", "terrain", "planning_role", "size", "lifecycle")})
    return rows


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlsplit(self.path)
        try:
            if url.path == "/api/catalog":
                return self.json(catalog())
            if url.path == "/api/model":
                key = parse_qs(url.query).get("id", [""])[0]
                path = asset_paths().get(key)
                if path is None:
                    return self.send_error(404, "Unknown asset")
                payload = read_structure(path / "structure.nbt")
                payload["author"] = json.loads((path / "author.json").read_text(encoding="utf-8"))
                payload["author_sha256"] = sha256(path / "author.json")
                for kind in ("validation", "review"):
                    if (path / f"{kind}.json").exists():
                        payload[kind] = json.loads((path / f"{kind}.json").read_text(encoding="utf-8"))
                return self.json(payload)
            if url.path.startswith("/resources/"):
                return self.file(TOOL / ".cache", url.path.removeprefix("/resources/"))
            if url.path == "/favicon.ico":
                self.send_response(204)
                self.end_headers()
                return
            return self.file(TOOL / "dist", url.path.lstrip("/") or "index.html")
        except (ValueError, KeyError, OSError) as exc:
            self.send_error(400, str(exc))

    def json(self, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def file(self, root, relative):
        path = (root / unquote(relative)).resolve()
        if not path.is_relative_to(root.resolve()) or not path.is_file():
            return self.send_error(404)
        data = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(path)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)


def serve(port=8765):
    if not (TOOL / "dist/index.html").exists() or not (TOOL / ".cache/registry.json").exists():
        raise RuntimeError("Run resource preparation and npm run build first")
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"Structure Studio: http://127.0.0.1:{port}", flush=True)
    server.serve_forever()
