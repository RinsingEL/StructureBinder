import copy
import unittest

import curate_trek_v3_catalog as curator


class CurateTrekV3CatalogTest(unittest.TestCase):
    def setUp(self):
        self.templates = {
            "schema": "city_template_catalog",
            "templates": [
                {
                    "buildingSemantic": "trek_v3_building",
                    "style": "trek_v3",
                    "templateId": "geomantia:city/trek_v3/village/desert/houses/desert_small_house_1",
                    "templateRef": "geomantia:city/trek_v3/village/desert/houses/desert_small_house_1",
                    "contentHash": "sha256:test",
                    "variant": "trek_v3_sanitized_v1",
                    "rawSize": {"width": 7, "height": 5, "depth": 9},
                    "allowedRotations": ["NONE"],
                    "allowedMirrors": ["NONE"],
                    "roadEntrances": [],
                    "terrainPosePolicy": "structure_start_beard_thin",
                    "supportPolicy": "full_footprint_support",
                    "clearanceBlocks": 2,
                }
            ],
        }
        self.references = {
            "structureRefs": [{
                "structureRef": "trek:village/desert/houses/desert_small_house_1",
                "templateCandidates": [{
                    "templateId": "geomantia:city/trek_v3/village/desert/houses/desert_small_house_1",
                    "variantId": "trek_v3_sanitized_v1",
                }],
            }],
            "fillPools": [{"poolRef": "pool:civic", "structureRefs": [
                "trek:village/desert/houses/desert_small_house_1"
            ]}],
            "styleProfiles": [{"profileRef": "style:trek_v3"}],
        }
        self.profiles = {
            "trek:village/desert/houses/desert_small_house_1": {
                "structureId": "trek:village/desert/houses/desert_small_house_1",
                "functionTerms": ["function.residential"],
                "planningRoleTerms": ["planning_role.fill"],
                "styleTerms": ["style.沙石风"],
            }
        }

    def test_curates_rotation_semantic_style_and_ai_references(self):
        summary = curator.curate(self.templates, self.references, self.profiles)
        template = self.templates["templates"][0]
        self.assertEqual(curator.ROTATIONS, template["allowedRotations"])
        self.assertEqual("trek_desert_sandstone", template["style"])
        self.assertEqual("residential_small_house", template["buildingSemantic"])
        self.assertEqual(1, summary["rotationsChanged"])
        self.assertIn(
            {"profileRef": "style:trek_desert_sandstone"},
            self.references["styleProfiles"],
        )
        pools = {item["poolRef"]: item["structureRefs"] for item in self.references["fillPools"]}
        self.assertNotIn("pool:civic", pools)
        self.assertEqual(
            ["trek:village/desert/houses/desert_small_house_1"],
            pools["pool:trek_desert_sandstone_residential_fill"],
        )

    def test_second_pass_is_idempotent(self):
        curator.curate(self.templates, self.references, self.profiles)
        expected_templates = copy.deepcopy(self.templates)
        expected_references = copy.deepcopy(self.references)
        summary = curator.curate(self.templates, self.references, self.profiles)
        self.assertEqual(expected_templates, self.templates)
        self.assertEqual(expected_references, self.references)
        self.assertEqual(0, summary["rotationsChanged"])
        self.assertEqual(0, summary["stylesChanged"])
        self.assertEqual(0, summary["semanticsChanged"])

    def test_unknown_semantic_is_rejected(self):
        self.references["structureRefs"][0]["structureRef"] = "trek:village/desert/houses/unknown_1"
        self.profiles["trek:village/desert/houses/unknown_1"] = self.profiles.pop(
            "trek:village/desert/houses/desert_small_house_1"
        )
        with self.assertRaises(curator.CurationFailure):
            curator.curate(self.templates, self.references, self.profiles)


if __name__ == "__main__":
    unittest.main()
