"""Batch admission invariants, separate from the existing full navigation audit."""
import unittest
from studio.european_village import get_builders


class EuropeanVillageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.models={key:fn() for key,fn in get_builders().items()}

    def test_complete_scope_without_overwriting_originals(self):
        families={m.meta['family'] for m in self.models.values()}
        self.assertTrue({f'EU-{n:02d}' for n in range(27,68)}<=families)
        self.assertTrue({f'EU-{n:02d}-v02' for n in (2,3,5,15,16,18,21)}<=self.models.keys())
        self.assertFalse(any(f'EU-{n:02d}-v01' in self.models for n in range(1,27)))

    def test_every_physical_bed_has_an_authored_use_and_approach(self):
        for key,m in self.models.items():
            with self.subTest(asset=key):
                physical={p for p,(name,props) in m.blocks.items() if name.endswith('_bed') and dict(props).get('part')=='foot'}
                annotated={tuple(p['pos']) for p in m.meta['points'] if p['kind']=='bed'}
                self.assertTrue(physical<=annotated, physical-annotated)
                for p in m.meta['points']:
                    if p['kind']=='bed':self.assertIn('approach',p)
                self.assertEqual(m.meta['workplace_policy'],'authored')

    def test_residential_capacities_are_real_bed_pairs(self):
        for key,count in (('EU-05-v02',9),('EU-21-v02',24),('EU-27-v01',2),('EU-28-v01',4)):
            m=self.models[key]
            heads=[p for p,(n,props) in m.blocks.items() if n.endswith('_bed') and dict(props).get('part')=='head']
            self.assertEqual(len(heads),count,key)

    def test_l_wing_does_not_fill_its_courtyard_with_a_plinth(self):
        for key in ('EU-27-v03','EU-28-v03'):
            m=self.models[key]
            self.assertNotIn((m.size[0]-3,1,3),m.blocks,key)


if __name__=='__main__':unittest.main()
