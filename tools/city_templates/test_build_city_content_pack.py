import json
import tempfile
import unittest
from pathlib import Path

import build_city_content_pack as builder


class BuildCityContentPackTest(unittest.TestCase):
    def test_builds_only_catalog_templates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generated = root / "generated"
            selected = generated / "geomantia/structures/city/trek/tower.nbt"
            unused = generated / "geomantia/structures/city/trek/unused.nbt"
            selected.parent.mkdir(parents=True)
            selected.write_bytes(b"selected")
            unused.write_bytes(b"unused")
            catalog = root / "template_catalog.json"
            catalog.write_text(json.dumps({
                "schema": "city_template_catalog",
                "templates": [{"templateRef": "geomantia:city/trek/tower", "variant": "fixed_v1"}],
            }), encoding="utf-8")

            manifest = builder.build(catalog, generated, root / "bundle", "test_pack")

            self.assertEqual(1, len(manifest["templates"]))
            self.assertEqual("trek", manifest["templates"][0]["sourceModId"])
            self.assertTrue((root / "bundle/city_template_content_pack/geomantia/structures/city/trek/tower.nbt").is_file())
            self.assertFalse((root / "bundle/city_template_content_pack/geomantia/structures/city/trek/unused.nbt").exists())

    def test_rejects_missing_catalog_template(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / "template_catalog.json"
            catalog.write_text(json.dumps({
                "schema": "city_template_catalog",
                "templates": [{"templateRef": "geomantia:city/missing", "variant": "v1"}],
            }), encoding="utf-8")
            with self.assertRaisesRegex(builder.ContentPackFailure, "SOURCE_MISSING"):
                builder.build(catalog, root / "generated", root / "bundle", "test_pack")


if __name__ == "__main__":
    unittest.main()
