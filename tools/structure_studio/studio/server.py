"""Loopback preview with a confined, optimistic author-frontage write endpoint."""
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import hashlib
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

from .model import read_structure, sha256
from .grounding import resolve_ground_plane
from .frontage import resolve, save as save_frontage

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
        row = {k: meta.get(k) for k in ("id", "name", "family", "civilization", "function_terms", "asset_tags", "terrain", "planning_role", "size", "lifecycle")}
        row["frontage"] = resolve(meta)
        row["grounding"] = resolve_ground_plane(meta)
        rows.append(row)
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
                author_bytes = (path / "author.json").read_bytes()
                payload["author"] = json.loads(author_bytes)
                payload["author_sha256"] = hashlib.sha256(author_bytes).hexdigest()
                payload["frontage"] = resolve(payload["author"], payload["sha256"])
                payload["grounding"] = resolve_ground_plane(payload["author"], payload["size"])
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

    def do_POST(self):
        if urlsplit(self.path).path != "/api/frontage":
            return self.json({"error": "Unknown write endpoint"}, 404)
        port = self.server.server_address[1]
        hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
        host = self.headers.get("Host", "")
        if (host not in hosts or self.headers.get("Origin") != f"http://{host}"
                or self.headers.get("X-Studio-Write") != "frontage"
                or self.headers.get("Content-Type", "").split(";")[0] != "application/json"):
            return self.json({"error": "请从本地 Structure Studio 页面保存标注。"}, 403)
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 8192:
                return self.json({"error": "Invalid request size"}, 413)
            request = json.loads(self.rfile.read(length))
            if not isinstance(request, dict) or set(request) - {"id", "policy", "entrance_id", "author_sha256", "nbt_sha256"}:
                raise ValueError("Invalid frontage request")
            if not isinstance(request.get("id"), str):
                raise ValueError("请选择素材编号。")
            path = asset_paths().get(request["id"])
            if path is None:
                return self.json({"error": "Unknown asset"}, 404)
            return self.json(save_frontage(path, request))
        except FileExistsError as exc:
            return self.json({"error": str(exc)}, 409)
        except (ValueError, KeyError, TypeError, OSError) as exc:
            return self.json({"error": str(exc)}, 400)

    def json(self, payload, status=200):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
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
