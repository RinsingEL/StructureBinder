import json
from pathlib import Path
import tempfile
import unittest

import nbtlib
from nbtlib import Int, List

from studio.model import Model, read_structure, sha256, write_json
from studio.navigation import audit
from studio.server import TOOL
from studio.validate import validate


class StudioTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registry=json.loads((TOOL/".cache/registry.json").read_text(encoding="utf-8"))

    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.path=Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_deterministic_roundtrip_preserves_air_and_block_entities(self):
        m=Model("T","test",(4,4,4))
        m.set(0,0,0,"stone").set(1,0,0,"air").bed(1,1,1,facing="south")
        actual=m.export(self.path,self.registry)
        first=(self.path/"structure.nbt").read_bytes()
        m.export(self.path,self.registry)
        self.assertEqual(first,(self.path/"structure.nbt").read_bytes())
        self.assertEqual(4,len(actual["blocks"]))
        self.assertEqual(2,sum("nbt" in b for b in actual["blocks"]))
        self.assertIn("minecraft:air",[s["name"] for s in actual["palette"]])
        self.assertFalse(any(b["pos"]==[3,3,3] for b in actual["blocks"]))

    def test_reject_invalid_state_before_export(self):
        m=Model("T","test",(4,4,4)).set(1,1,1,"oak_stairs[facing=up]")
        with self.assertRaisesRegex(ValueError,"Invalid property"):
            m.export(self.path,self.registry)

    def test_invalid_final_nbt_is_rejected(self):
        m=Model("T","test",(4,4,4)).set(1,1,1,"stone")
        m.export(self.path,self.registry)
        doc=nbtlib.load(self.path/"structure.nbt")
        doc["blocks"].append(doc["blocks"][0])
        doc.save(self.path/"structure.nbt",gzipped=True)
        with self.assertRaisesRegex(ValueError,"Duplicate position"):
            read_structure(self.path/"structure.nbt")

    def test_missing_door_half_and_bed_half_reported(self):
        m=Model("T","test",(5,5,5)).door(1,1,1).bed(3,1,2)
        m.set(1,2,1,"air").set(3,1,1,"air")
        m.export(self.path,self.registry)
        errors=validate(self.path,self.registry)["errors"]
        self.assertTrue(any("Unpaired door" in e for e in errors))
        self.assertTrue(any("Unpaired bed" in e for e in errors))

    def test_stale_annotations_rejected(self):
        m=Model("T","test",(3,3,3)).set(0,0,0,"stone")
        m.export(self.path,self.registry)
        meta=json.loads((self.path/"author.json").read_text(encoding="utf-8"))
        meta["nbt_sha256"]="old"
        write_json(self.path/"author.json",meta)
        self.assertFalse(validate(self.path,self.registry)["passed"])

    def test_stairway_reachable_but_low_ceiling_rejected(self):
        m=Model("T","stairs",(6,10,12))
        m.box((0,0,0),(5,0,11),"stone")
        for i in range(5):
            for x in (2,3):
                m.set(x,i+1,8-i,"oak_stairs[facing=north,half=bottom]")
        m.box((1,5,1),(4,5,3),"oak_planks")
        m.point("in","entrance",(2,1,10),"start")
        m.point("out","work",(2,6,2),"landing",approach=(2,6,2))
        data=m.export(self.path,self.registry)
        self.assertTrue(audit(data,m.meta,self.registry)["passed"])
        m.box((1,3,8),(4,3,8),"stone")
        data=m.export(self.path,self.registry)
        self.assertIn("out",audit(data,m.meta,self.registry)["unreachable"])

    def test_sealed_room_is_unreachable(self):
        m=Model("T","sealed",(7,5,7))
        m.box((0,0,0),(6,0,6),"stone")
        m.box((0,1,3),(6,4,3),"stone")
        m.point("in","entrance",(3,1,1),"start")
        m.point("work","work",(3,1,5),"inside",approach=(3,1,5))
        data=m.export(self.path,self.registry)
        self.assertIn("work",audit(data,m.meta,self.registry)["unreachable"])


if __name__=="__main__":unittest.main()
