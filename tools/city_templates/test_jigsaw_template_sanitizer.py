import copy
import json
import tempfile
import unittest
from pathlib import Path

import nbtlib

import jigsaw_template_sanitizer as sanitizer


def template_bytes(final_state: str = "minecraft:air",
                   pool: str = "example:village/houses",
                   orientation: str = "north_up") -> bytes:
    palette = nbtlib.List[nbtlib.Compound]([
        sanitizer.state_compound("minecraft:stone"),
        sanitizer.state_compound("minecraft:jigsaw", {"orientation": orientation}),
    ])
    blocks = nbtlib.List[nbtlib.Compound]([
        nbtlib.Compound({
            "pos": nbtlib.List[nbtlib.Int]([1, 1, 0]),
            "state": nbtlib.Int(1),
            "nbt": nbtlib.Compound({
                "name": nbtlib.String("example:entrance"),
                "target": nbtlib.String("example:street"),
                "pool": nbtlib.String(pool),
                "joint": nbtlib.String("rollable"),
                "final_state": nbtlib.String(final_state),
            }),
        }),
        nbtlib.Compound({
            "pos": nbtlib.List[nbtlib.Int]([1, 0, 1]),
            "state": nbtlib.Int(0),
        }),
    ])
    value = nbtlib.File({
        "DataVersion": nbtlib.Int(3465),
        "size": nbtlib.List[nbtlib.Int]([3, 4, 5]),
        "palette": palette,
        "blocks": blocks,
        "entities": nbtlib.List[nbtlib.Compound](),
    })
    return sanitizer.encode_nbt(value)


class JigsawTemplateSanitizerTest(unittest.TestCase):
    def test_audit_reports_connector_facts_and_unapproved_manifest_draft(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "source"
            source.mkdir()
            (source / "House One.nbt").write_bytes(template_bytes(
                "minecraft:oak_stairs[facing=north,half=bottom]"))

            report, draft = sanitizer.build_audit(source, "geomantia", "city/imported")

        self.assertEqual(1, report["summary"]["reviewRequiredCount"])
        item = report["templates"][0]
        self.assertEqual("geomantia:city/imported/house_one", item["targetRef"])
        self.assertEqual("NORTH", item["connectors"][0]["direction"])
        self.assertEqual("example:village/houses", item["connectors"][0]["pool"])
        self.assertEqual(0, item["roadEntranceCandidates"][0]["boundaryDistance"])
        self.assertFalse(draft["templates"][0]["standaloneConfirmed"])
        self.assertEqual(1, draft["templates"][0]["expectedJigsawCount"])

    def test_replacement_uses_exact_final_state_and_removes_block_entity(self):
        nbt = sanitizer.decode_nbt(template_bytes(
            "minecraft:oak_stairs[facing=east,half=top]"))

        connectors = sanitizer.replace_jigsaws_with_final_state(nbt)

        self.assertEqual(1, len(connectors))
        self.assertEqual([], sanitizer.inspect_jigsaws(nbt))
        block = nbt["blocks"][0]
        state = nbt["palette"][int(block["state"])]
        self.assertEqual("minecraft:oak_stairs", str(state["Name"]))
        self.assertEqual("east", str(state["Properties"]["facing"]))
        self.assertEqual("top", str(state["Properties"]["half"]))
        self.assertNotIn("nbt", block)

    def test_rejected_connector_does_not_partially_mutate_template(self):
        nbt = sanitizer.decode_nbt(template_bytes())
        before = sanitizer.encode_nbt(copy.deepcopy(nbt))

        with self.assertRaises(sanitizer.SanitizeFailure) as raised:
            sanitizer.replace_jigsaws_with_final_state(nbt, allowed=lambda value: False)

        self.assertEqual("JIGSAW_CONNECTOR_NOT_APPROVED", raised.exception.code)
        self.assertEqual(before, sanitizer.encode_nbt(nbt))

    def test_invalid_final_state_is_reported_without_writing(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "source"
            source.mkdir()
            (source / "broken.nbt").write_bytes(template_bytes("not a block state"))

            report, draft = sanitizer.build_audit(source, "geomantia", "city/imported")

        self.assertEqual(1, report["summary"]["invalidCount"])
        self.assertEqual("JIGSAW_FINAL_STATE_INVALID", report["templates"][0]["failureCode"])
        self.assertEqual([], draft["templates"])

    def test_only_confirmed_templates_are_sanitized(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source"
            output = root / "output"
            source.mkdir()
            first = source / "approved.nbt"
            second = source / "pending.nbt"
            first.write_bytes(template_bytes("minecraft:stone"))
            second.write_bytes(template_bytes("minecraft:air", "other:structural_pool"))
            manifest = {
                "schema": sanitizer.MANIFEST_SCHEMA,
                "templates": [
                    self._entry(first, "approved.nbt", "geomantia:city/approved", True),
                    self._entry(second, "pending.nbt", "geomantia:city/pending", False),
                ],
            }

            report = sanitizer.sanitize(source, output, manifest, overwrite=False, dry_run=False)
            target = output / "data/geomantia/structures/city/approved.nbt"

            self.assertTrue(target.is_file())
            self.assertFalse((output / "data/geomantia/structures/city/pending.nbt").exists())
            self.assertEqual([], sanitizer.inspect_jigsaws(sanitizer.decode_nbt(target.read_bytes())))
        self.assertEqual(1, report["summary"]["sanitizedCount"])
        self.assertEqual(1, report["summary"]["skippedUnconfirmedCount"])
        self.assertEqual("NORTH", report["templates"][0]["roadEntranceCandidates"][0]["direction"])

    def test_source_hash_drift_and_overwrite_are_hard_failures(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source"
            output = root / "output"
            source.mkdir()
            source_file = source / "house.nbt"
            source_file.write_bytes(template_bytes())
            entry = self._entry(source_file, "house.nbt", "geomantia:city/house", True)
            manifest = {"schema": sanitizer.MANIFEST_SCHEMA, "templates": [entry]}
            sanitizer.sanitize(source, output, manifest, overwrite=False, dry_run=False)

            with self.assertRaises(sanitizer.SanitizeFailure) as overwrite:
                sanitizer.sanitize(source, output, manifest, overwrite=False, dry_run=False)
            self.assertEqual("JIGSAW_OVERWRITE_CONFIRMATION_REQUIRED", overwrite.exception.code)

            source_file.write_bytes(template_bytes("minecraft:stone"))
            with self.assertRaises(sanitizer.SanitizeFailure) as drift:
                sanitizer.prepare_sanitize(source, output, manifest)
            self.assertEqual("JIGSAW_SOURCE_HASH_MISMATCH", drift.exception.code)

    def test_manifest_loader_rejects_unreviewed_shape_and_path_escape(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "manifest.json"
            value = {
                "schema": sanitizer.MANIFEST_SCHEMA,
                "templates": [{
                    "sourceFile": "../house.nbt",
                    "sourceSha256": "0" * 64,
                    "targetRef": "geomantia:city/house",
                    "standaloneConfirmed": False,
                    "connectorPolicy": "replace_all_with_final_state",
                    "expectedJigsawCount": 1,
                }],
            }
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(sanitizer.SanitizeFailure) as raised:
                sanitizer.load_manifest(path)
        self.assertEqual("JIGSAW_MANIFEST_SOURCE_INVALID", raised.exception.code)

    @staticmethod
    def _entry(path: Path, source_file: str, target_ref: str,
               confirmed: bool) -> dict[str, object]:
        return {
            "sourceFile": source_file,
            "sourceSha256": sanitizer.sha256_file(path),
            "targetRef": target_ref,
            "standaloneConfirmed": confirmed,
            "connectorPolicy": "replace_all_with_final_state",
            "expectedJigsawCount": 1,
        }


if __name__ == "__main__":
    unittest.main()
