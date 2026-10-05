"""Studio browsing rules; independent of supporting tags and city planning roles."""
import json
from pathlib import Path

RULES = json.loads((Path(__file__).resolve().parents[1] / "catalog.json").read_text(encoding="utf-8"))
CATEGORIES = RULES["categories"]
ACTIVE_CATALOG_DIRS = set(RULES["active_dirs"])
SPECIALTY_FAMILIES = set(RULES["specialty_families"])


def resolve_category(meta: dict) -> str:
    """Resolve cultural identity; infrastructure/landscape remain independent."""
    family = meta.get("family") or ""
    asset_family = (meta.get("id") or "").split("-v", 1)[0]
    return "specialty" if family in SPECIALTY_FAMILIES or asset_family in SPECIALTY_FAMILIES else "common"
