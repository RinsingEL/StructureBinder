import unittest
import json
import tempfile
from pathlib import Path
from unittest.mock import patch
from studio.categories import CATEGORIES, ACTIVE_CATALOG_DIRS, resolve_category
from studio.server import asset_paths, catalog


class CategoriesTest(unittest.TestCase):
    def test_categories_definition(self):
        cat_ids = [c["id"] for c in CATEGORIES]
        self.assertEqual(cat_ids, ["specialty", "common"])

    def test_active_catalog_dirs(self):
        self.assertEqual(
            ACTIVE_CATALOG_DIRS,
            {
                "S13_chinese_timber",
                "R01_elven_reborn",
                "R02_arcane_reborn",
                "R03_dwarven_reborn",
                "R04_european_reborn",
                "03_desert_stars",
            },
        )

    def test_resolve_category(self):
        # Specialty
        self.assertEqual(resolve_category({"id": "CH-13-v01"}), "specialty")
        self.assertEqual(resolve_category({"id": "EU-01-v01"}), "specialty")
        self.assertEqual(resolve_category({"id": "EL-01-v01"}), "specialty")
        self.assertEqual(resolve_category({"id": "DV-01-v01"}), "specialty")
        self.assertEqual(resolve_category({"id": "MG-01-v01"}), "specialty")
        self.assertEqual(resolve_category({"id": "DS-10-v01"}), "specialty")

        # Common
        self.assertEqual(resolve_category({"id": "CH-19-v01"}), "common")
        self.assertEqual(resolve_category({"id": "EU-03-v01"}), "common")
        self.assertEqual(resolve_category({"id": "EL-05-v01"}), "common")
        self.assertEqual(resolve_category({"id": "DV-05-v01"}), "common")
        self.assertEqual(resolve_category({"id": "MG-05-v01"}), "common")
        self.assertEqual(resolve_category({"id": "DS-01-v01"}), "common")

        # Supporting tags never overwrite cultural identity.
        self.assertEqual(resolve_category({"id": "CH-24-v01", "asset_tags": ["infrastructure"]}), "common")
        self.assertEqual(resolve_category({"id": "CH-22-v01", "asset_tags": ["landscape"]}), "specialty")
        self.assertEqual(resolve_category({"family": "CH-22", "asset_tags": ["landscape", "infrastructure"]}), "specialty")
        self.assertEqual(resolve_category({"id": "CH-130-v01"}), "common")

    def test_default_catalog_excludes_archives_and_fixtures_but_keeps_explicit_access(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            paths = {
                "CH-22-v01": root / "assets/S13_chinese_timber/models/CH-22-v01",
                "OLD": root / "assets/old_pool/models/OLD",
                "FIXTURE-01": root / "tool/fixtures/FIXTURE-01",
            }
            for key, path in paths.items():
                path.mkdir(parents=True)
                (path / "author.json").write_text(json.dumps({"id": key, "asset_tags": ["landscape"]}))
            with patch("studio.server.CATALOG", root / "assets"), patch("studio.server.TOOL", root / "tool"):
                self.assertEqual(set(asset_paths()), {"CH-22-v01"})
                self.assertEqual(set(asset_paths(active_only=False)), {"CH-22-v01", "OLD"})
                self.assertEqual(set(asset_paths(include_fixtures=True)), {"CH-22-v01", "FIXTURE-01"})
                self.assertEqual(set(asset_paths(active_only=False, include_fixtures=True)), set(paths))
                row = catalog()[0]
                self.assertEqual(row["category"], "specialty")
                self.assertEqual(row["asset_tags"], ["landscape"])
                self.assertEqual(catalog({}), [])


if __name__ == "__main__":
    unittest.main()
