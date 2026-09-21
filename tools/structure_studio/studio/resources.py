"""Prepare a local-only render cache from the user's installed client JAR."""
import io
import json
import math
import zipfile
from pathlib import Path

from PIL import Image

from .model import sha256, write_json


def prepare(jar: Path, cache: Path):
    cache.mkdir(parents=True, exist_ok=True)
    blocks, models, textures = {}, {}, []
    with zipfile.ZipFile(jar) as archive:
        version = json.loads(archive.read("version.json"))
        if version["id"] != "1.20.1":
            raise ValueError(f"Expected Minecraft 1.20.1, found {version['id']}")
        for name in sorted(archive.namelist()):
            if name.startswith("assets/minecraft/blockstates/") and name.endswith(".json"):
                blocks["minecraft:" + name.removeprefix("assets/minecraft/blockstates/")[:-5]] = json.loads(archive.read(name))
            elif name.startswith("assets/minecraft/models/block/") and name.endswith(".json"):
                models["minecraft:" + name.removeprefix("assets/minecraft/models/")[:-5]] = json.loads(archive.read(name))
            elif name.startswith(("assets/minecraft/textures/block/", "assets/minecraft/textures/entity/")) and name.endswith(".png"):
                img = Image.open(io.BytesIO(archive.read(name))).convert("RGBA")
                # First animation frame; entity textures keep their complete UV layout.
                if name + ".mcmeta" in archive.namelist():
                    info = json.loads(archive.read(name + ".mcmeta")).get("animation", {})
                    w = info.get("width", min(img.size))
                    h = info.get("height", w)
                    frame = info.get("frames", [0])[0]
                    frame = frame.get("index", 0) if isinstance(frame, dict) else frame
                    cols = img.width // w
                    x, y = (frame % cols) * w, (frame // cols) * h
                    img = img.crop((x, y, x + w, y + h))
                textures.append(("minecraft:" + name.removeprefix("assets/minecraft/textures/")[:-4], img))
    models["minecraft:builtin/entity"] = {"elements": []}
    atlas_width = 2048
    x, y, row_height, placed = 20, 0, 20, []
    for name, img in sorted(textures, key=lambda pair: (-pair[1].height, pair[0])):
        if img.width + 4 > atlas_width:
            raise ValueError(f"Texture too wide: {name}")
        if x + img.width + 4 > atlas_width:
            x, y, row_height = 0, y + row_height, 0
        placed.append((name, img, x + 2, y + 2))
        row_height = max(row_height, img.height + 4)
        x += img.width + 4
    atlas_height = 2 ** math.ceil(math.log2(y + row_height))
    atlas = Image.new("RGBA", (atlas_width, atlas_height))
    atlas.paste((255, 0, 255, 255), (0, 0, 16, 16))
    atlas.paste((0, 0, 0, 255), (8, 0, 16, 8))
    atlas.paste((0, 0, 0, 255), (0, 8, 8, 16))
    uv = {}
    for name, img, px, py in placed:
        atlas.paste(img, (px, py))
        # Duplicate edge pixels into a gutter to avoid distant texture bleeding.
        atlas.paste(img.crop((0, 0, 1, img.height)).resize((2, img.height)), (px - 2, py))
        atlas.paste(img.crop((img.width - 1, 0, img.width, img.height)).resize((2, img.height)), (px + img.width, py))
        atlas.paste(img.crop((0, 0, img.width, 1)).resize((img.width, 2)), (px, py - 2))
        atlas.paste(img.crop((0, img.height - 1, img.width, img.height)).resize((img.width, 2)), (px, py + img.height))
        uv[name] = [px / atlas_width, py / atlas_height, (px + img.width) / atlas_width, (py + img.height) / atlas_height]
    atlas.save(cache / "atlas.png")
    write_json(cache / "resources.json", dict(version="1.20.1", blocks=blocks, models=models, textures=uv,
                                               source_sha256=sha256(jar), animation="first frame"))
    print(f"Prepared {len(blocks)} blockstates, {len(models)} models, {len(textures)} textures; atlas {atlas.size}")
