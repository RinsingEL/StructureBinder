"""Physical requirements; visual character is deliberately not an automated pass."""
import unittest
from studio.european_character import BUILDERS


class CharacterTests(unittest.TestCase):
    def test_versions_and_physical_beds(self):
        expected={'EU-27-v05':2,'EU-32-v02':5,'EU-07-v02':0,'EU-55-v02':0}
        self.assertEqual(set(BUILDERS),set(expected))
        for key,fn in BUILDERS.items():
            m=fn();beds=[p for p,(name,props) in m.blocks.items()
                if name.endswith('_bed') and dict(props).get('part')=='head']
            self.assertEqual(len(beds),expected[key],key)
            self.assertEqual(m.meta['workplace_policy'],'authored')

    def test_tavern_jetty_has_real_support_and_separate_kitchen(self):
        m=BUILDERS['EU-32-v02']()
        self.assertTrue(m.blocks[(10,5,3)][0].endswith('_log'))
        self.assertEqual({r['id'] for r in m.meta['rooms']},{'taproom','guest','kitchen'})
        beds=[p for p in m.meta['points'] if p['kind']=='bed']
        self.assertEqual(sum(p['use']=='public' for p in beds),4)
        self.assertEqual(sum(p['use']=='staff' for p in beds),1)

    def test_smithy_is_open_to_the_street(self):
        m=BUILDERS['EU-07-v02']()
        for x in range(4,10):
            for y in (2,3):self.assertEqual(m.blocks[(x,y,2)][0],'minecraft:air')
        self.assertEqual(m.blocks[(6,2,11)][0],'minecraft:blast_furnace')

    def test_store_has_actual_floor_and_land_to_dock_height(self):
        m=BUILDERS['EU-55-v02']()
        self.assertEqual([f['y'] for f in m.meta['floors']],[3,9])
        self.assertEqual(m.blocks[(14,3,10)][0],'minecraft:spruce_planks')
        self.assertEqual(m.meta['ground_plane']['y'],2)


if __name__=='__main__':unittest.main()
