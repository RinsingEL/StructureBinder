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
        cls.profiles = importer.build_structure_profiles(cls.manifest, cls.templates)

    def test_selected_batch_contains_twenty_configured_and_four_reviewed_direct_templates(self):
        self.assertEqual(24, len(self.templates))
        self.assertEqual(24, len({item.target_ref for item in self.templates}))
        direct = [item for item in self.templates if "sourceTemplateId" in item.manifest_entry]
        self.assertEqual(4, len(direct))
        self.assertEqual({
            "geomantia:city/trek/landmark/plains_fountain_01",
            "geomantia:city/trek/defense/mangrove_watchtower_1",
            "geomantia:city/trek/defense/mangrove_watchtower_2",
            "geomantia:city/trek/industry/claw_pillager",
        }, {item.target_ref for item in direct})

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

    def test_profiles_use_stubbs_fixed_template_contract(self):
        self.assertEqual(24, len(self.profiles))
        for profile in self.profiles:
            self.assertEqual("approved", profile["reviewState"])
            self.assertEqual([], profile["planningRoleTerms"])
            self.assertEqual(["SURFACE"], profile["terrainModes"])
            self.assertNotIn("terrainTerms", profile)
            self.assertEqual({"structureId", "sourceProfileRef", "reviewState", "functionTerms",
                              "planningRoleTerms", "terrainModes", "styleTerms"}, set(profile))

    def test_trade_ships_are_waterfront_profiles(self):
        for suffix in ("/dark_oak_trade", "/small_red_trade"):
            profile = next(value for value in self.profiles if value["structureId"].endswith(suffix))
            self.assertEqual(["function.港口", "function.商业"], profile["functionTerms"])
            self.assertEqual(["SURFACE"], profile["terrainModes"])

    def test_maison_is_residential_not_old_mill_assembly_profile(self):
        maison = next(profile for profile in self.profiles if profile["structureId"].endswith("/maison"))
        self.assertEqual(["function.residential"], maison["functionTerms"])
        self.assertNotIn("function.磨坊", maison["functionTerms"])
        self.assertNotIn("function.农业", maison["functionTerms"])

    def test_manifest_rejects_semantic_term_missing_from_vocabulary(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["templates"][0]["functionTerms"] = ["function.不存在"]
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "manifest.json"
            path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
            with self.assertRaises(importer.ImportFailure) as raised:
                importer.load_manifest(path)
        self.assertEqual("TREK_TEMPLATE_SEMANTIC_VOCABULARY_INCOMPLETE", raised.exception.code)

    def test_all_entrances_are_confirmed_with_fixed_nbt_evidence(self):
        self.assertEqual("fixed_nbt_and_terrasense_four_view_20260727",
                         self.manifest["entranceReviewRef"])
        self.assertTrue(all(item.manifest_entry["entranceConfirmed"] for item in self.templates))
        for item in self.templates:
            importer.validate_confirmed_entrance(item.nbt, item.manifest_entry, item.raw_size)
        entries = {item.source_configured_id: item.manifest_entry["entrance"] for item in self.templates}
        self.assertEqual(
            {"entranceId": "farm_pillager_north_gate", "x": 9, "z": 1, "direction": "NORTH"},
            entries["trek:overworld/medium/farm_pillager"],
        )
        self.assertEqual(
            {"entranceId": "square_tower_east_ladder", "x": 10, "z": 7, "direction": "EAST"},
            entries["trek:overworld/medium/square_tower"],
        )
        self.assertEqual(
            {"entranceId": "tower_west_opening", "x": 2, "z": 4, "direction": "WEST"},
            entries["trek:overworld/medium/tower"],
        )
        fountain = next(item for item in self.templates
                        if item.target_ref.endswith("/landmark/plains_fountain_01"))
        self.assertEqual(4, len(importer.entry_entrances(fountain.manifest_entry)))
        self.assertEqual("live_game_review_20260826", fountain.entrance_review_ref)
        self.assertEqual(10, fountain.marker_count)
        short_tower = next(item for item in self.templates
                           if item.target_ref.endswith("/defense/mangrove_watchtower_1"))
        tall_tower = next(item for item in self.templates
                          if item.target_ref.endswith("/defense/mangrove_watchtower_2"))
        claw = next(item for item in self.templates
                    if item.target_ref.endswith("/industry/claw_pillager"))
        self.assertEqual((3, 4, 0), (short_tower.marker_count, tall_tower.marker_count, claw.marker_count))

    def test_entrance_evidence_drift_is_rejected(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["templates"][0]["entranceEvidence"]["block"] = "minecraft:stone"
        with self.assertRaises(importer.ImportFailure) as raised:
            importer.inspect_all(self.jar, manifest)
        self.assertEqual("TREK_TEMPLATE_ENTRANCE_EVIDENCE_MISMATCH", raised.exception.code)

    def test_confirmed_batch_builds_full_catalog_when_runtime_metadata_is_available(self):
        runtime = {
            item.target_ref: {
                "templateRef": item.target_ref,
                "readable": True,
                "rawSize": item.raw_size,
                "templateHash": f"sha256:test-{index}",
            }
            for index, item in enumerate(self.templates)
        }
        catalog = importer.build_catalog(self.templates, runtime)
        self.assertEqual(24, len(catalog["templates"]))
        fountain = next(entry for entry in catalog["templates"]
                        if entry["templateId"].endswith("/landmark/plains_fountain_01"))
        self.assertEqual(4, len(fountain["roadEntrances"]))
        self.assertTrue(all(entry["terrainPosePolicy"] == "structure_start_beard_thin"
                            for entry in catalog["templates"]))

    def test_profile_package_contains_profiles_vocabulary_and_official_source(self):
        with tempfile.TemporaryDirectory() as temp:
            outputs = importer.write_profile_package(Path(temp), self.manifest, self.templates)
            first_bytes = {key: path.read_bytes() for key, path in outputs.items()}
            second_outputs = importer.write_profile_package(Path(temp), self.manifest, self.templates)
            self.assertEqual(first_bytes, {key: path.read_bytes() for key, path in second_outputs.items()})
            profiles = [json.loads(line) for line in outputs["profiles"].read_text(
                encoding="utf-8").splitlines()]
            vocabulary = json.loads(outputs["vocabulary"].read_text(encoding="utf-8"))
            source = json.loads(outputs["source"].read_text(encoding="utf-8"))
        self.assertEqual(24, len(profiles))
        used_terms = {term for profile in profiles
                      for field in ("functionTerms", "planningRoleTerms", "styleTerms")
                      for term in profile[field]}
        vocabulary_terms = {term["term_id"] for term in vocabulary["terms"]}
        self.assertEqual(used_terms, vocabulary_terms)
        self.assertEqual(24, source["quality"]["exportedProfiles"])
        self.assertTrue(Path(source["profilePath"]).is_absolute())
        self.assertTrue(Path(source["vocabularySnapshotPath"]).is_absolute())

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
            self.assertTrue(first_report[0]["entranceConfirmed"])
            self.assertEqual(self.manifest["entranceReviewRef"],
                             first_report[0]["entranceReviewRef"])
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
