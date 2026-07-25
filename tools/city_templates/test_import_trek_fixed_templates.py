import copy
import json
import tempfile
import unittest
from pathlib import Path

import import_trek_fixed_templates as importer


class TrekFixedImporterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo_root = Path(__file__).resolve().parents[2]
        cls.manifest = importer.load_manifest(importer.DEFAULT_MANIFEST)
        cls.jar = cls.repo_root / cls.manifest["sourceJar"]
        cls.jar_hash, cls.templates = importer.inspect_all(cls.jar, cls.manifest)

    def test_selected_batch_is_twenty_single_root_templates(self):
        self.assertEqual(20, len(self.templates))
        self.assertEqual(20, len({item.target_ref for item in self.templates}))

    def test_outputs_have_no_jigsaws_or_entities(self):
        for item in self.templates:
            palette = item.nbt["palette"]
            names = [str(palette[int(block["state"])] ["Name"]) for block in item.nbt["blocks"]]
            self.assertNotIn("minecraft:jigsaw", names, item.target_ref)
            self.assertEqual(0, len(item.nbt["entities"]), item.target_ref)

    def test_processor_bake_is_byte_deterministic(self):
        _, second = importer.inspect_all(self.jar, self.manifest)
        self.assertEqual([item.encoded for item in self.templates], [item.encoded for item in second])
        processed = {item.source_configured_id: item for item in self.templates if item.processor != "minecraft:empty"}
        self.assertEqual({
            "trek:overworld/medium/farm_pillager",
            "trek:overworld/rare/villager_castle",
        }, set(processed))
        self.assertTrue(all(item.processor_replacements > 0 for item in processed.values()))

    def test_source_hash_drift_is_rejected(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["sourceJarSha256"] = "0" * 64
        with self.assertRaises(importer.ImportFailure) as raised:
            importer.inspect_all(self.jar, manifest)
        self.assertEqual("TREK_TEMPLATE_SOURCE_JAR_HASH_MISMATCH", raised.exception.code)

    def test_structural_jigsaw_is_rejected(self):
        item = copy.deepcopy(self.templates[0].nbt)
        palette = item["palette"]
        jigsaw_index = importer.palette_index(palette, importer.state_compound("minecraft:jigsaw"))
        block = item["blocks"][0]
        block["state"] = jigsaw_index
        block["nbt"] = importer.nbtlib.Compound({
            "pool": importer.nbtlib.String("trek:overworld/structural/child"),
            "final_state": importer.nbtlib.String("minecraft:stone"),
        })
        with self.assertRaises(importer.ImportFailure) as raised:
            importer.strip_marker_jigsaws(item)
        self.assertEqual("TREK_TEMPLATE_STRUCTURAL_JIGSAW_REJECTED", raised.exception.code)

    def test_catalog_requires_confirmed_entrance_and_runtime_metadata(self):
        item = self.templates[0]
        unconfirmed_entry = copy.deepcopy(item.manifest_entry)
        unconfirmed_entry["entranceConfirmed"] = False
        unconfirmed = importer.ImportedTemplate(**{**item.__dict__, "manifest_entry": unconfirmed_entry})
        runtime = {item.target_ref: {
            "templateRef": item.target_ref,
            "readable": True,
            "rawSize": item.raw_size,
            "templateHash": "sha256:test",
        }}
        with self.assertRaises(importer.ImportFailure) as raised:
            importer.build_catalog([unconfirmed], runtime)
        self.assertEqual("TREK_TEMPLATE_ENTRANCE_UNCONFIRMED", raised.exception.code)

    def test_overwrite_requires_explicit_confirmation_and_repeat_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temp:
            save = Path(temp)
            _, first_report = importer.write_outputs(save, self.templates[:1], overwrite=False)
            output = Path(first_report[0]["outputFile"])
            first_bytes = output.read_bytes()
            with self.assertRaises(importer.ImportFailure) as raised:
                importer.write_outputs(save, self.templates[:1], overwrite=False)
            self.assertEqual("TREK_TEMPLATE_OVERWRITE_CONFIRMATION_REQUIRED", raised.exception.code)
            importer.write_outputs(save, self.templates[:1], overwrite=True)
            self.assertEqual(first_bytes, output.read_bytes())

    def test_dry_run_does_not_write_save(self):
        with tempfile.TemporaryDirectory() as temp:
            save = Path(temp)
            result = importer.main(["--save-dir", str(save), "--dry-run"])
            self.assertEqual(0, result)
            self.assertFalse((save / "generated").exists())


if __name__ == "__main__":
    unittest.main()
