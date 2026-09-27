import json
from pathlib import Path
import tempfile
import unittest
from studio.grounding import resolve_ground_plane
from studio.model import Model
from studio.validate import validate

class GroundPlaneTests(unittest.TestCase):
    def test_missing_never_infers_entry_or_preview(self):
        a={'size':[5,8,5],'points':[{'kind':'entrance','pos':[1,4,1]}],'preview_context':{'land_surface_y':3}}
        self.assertEqual('unmarked',resolve_ground_plane(a)['status'])
        self.assertIsNone(resolve_ground_plane(a)['y'])

    def test_valid_bounds_and_invalid_values(self):
        for y in (0,7):
            self.assertEqual('marked',resolve_ground_plane({'ground_plane':{'y':y,'note':'外部地面'}},[5,8,5])['status'])
        for p in (None,[],{}, {'y':True,'note':'test'}, {'y':1.5,'note':'test'}, {'y':-1,'note':'test'}, {'y':8,'note':'test'}, {'y':2,'note':''}, {'y':2,'note':'  '}, {'y':2,'note':5}):
            with self.subTest(p=p):self.assertEqual('invalid',resolve_ground_plane({'ground_plane':p},[5,8,5])['status'])

    def test_validation_reads_optional_field_from_author(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp);Model('GROUND','ground',(3,4,3)).set(0,0,0,'stone').export(d)
            self.assertFalse(any('ground_plane' in e for e in validate(d,save=False)['errors']))
            f=d/'author.json';a=json.loads(f.read_text(encoding='utf-8'));a['ground_plane']={'y':True,'note':'bad'};f.write_text(json.dumps(a),encoding='utf-8')
            self.assertTrue(any('ground_plane' in e for e in validate(d,save=False)['errors']))
            a['ground_plane']={'y':3,'note':'external feet plane'};f.write_text(json.dumps(a),encoding='utf-8')
            self.assertFalse(any('ground_plane' in e for e in validate(d,save=False)['errors']))

    def test_catalog_exposes_grounding_without_changing_author(self):
        from unittest.mock import patch
        from studio.server import catalog
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp);m=Model('GROUND','ground',(3,4,3)).set(0,0,0,'stone')
            m.meta['ground_plane']={'y':2,'note':'external grade'};m.export(d)
            before=(d/'author.json').read_bytes()
            with patch('studio.server.asset_paths',return_value={'GROUND':d}):
                row=catalog()[0]
            self.assertEqual({'status':'marked','y':2,'note':'external grade','message':'作者已标外部地面'},row['grounding'])
            self.assertEqual(before,(d/'author.json').read_bytes())
