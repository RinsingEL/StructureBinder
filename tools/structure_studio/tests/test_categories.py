import unittest
from studio.categories import CATEGORIES, ACTIVE_CATALOG_DIRS, resolve_category


class CategoriesTest(unittest.TestCase):
    def test_categories_definition(self):
        cat_ids = [c["id"] for c in CATEGORIES]
        self.assertEqual(cat_ids, ["specialty", "common", "infrastructure", "landscape"])

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

        # Infrastructure
        self.assertEqual(resolve_category({"id": "CH-24-v01", "asset_tags": ["infrastructure"]}), "infrastructure")
        self.assertEqual(resolve_category({"id": "EU-08-v01", "asset_tags": ["infrastructure"]}), "infrastructure")

        # Landscape
        self.assertEqual(resolve_category({"id": "CH-26-v01", "asset_tags": ["landscape"]}), "landscape")
        self.assertEqual(resolve_category({"id": "EU-10-v01", "asset_tags": ["landscape"]}), "landscape")


if __name__ == "__main__":
    unittest.main()
