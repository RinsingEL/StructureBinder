"""Placement invariants for small, pool-selected vegetation assets."""
import unittest
from collections import deque
from studio.academy_greenery import BUILDERS


class AcademyGreeneryTest(unittest.TestCase):
    def test_templates_do_not_clear_or_build_a_plot(self):
        for build in BUILDERS.values():
            m=build()
            with self.subTest(id=m.meta['id']):
                self.assertEqual(m.meta['placement_consumer'],'infrastructure')
                self.assertFalse(any(s[0].endswith(':air') for s in m.blocks.values()))
                self.assertLessEqual(max(m.size[0],m.size[2]),9)
                self.assertTrue(any(p[1]==0 for p in m.blocks))
                for name,props in m.blocks.values():
                    if name.endswith('_leaves'):self.assertEqual(dict(props)['persistent'],'true')

    def test_tree_wood_is_connected_to_ground_and_branches_reach_crown(self):
        for build in BUILDERS.values():
            m=build()
            if m.meta['greenery']['kind']!='tree':continue
            with self.subTest(id=m.meta['id']):
                wood={p for p,s in m.blocks.items() if s[0].endswith('_log')}
                roots={p for p in wood if p[1]==0};self.assertEqual(len(roots),1)
                seen=set(roots);queue=deque(roots)
                while queue:
                    p=queue.popleft()
                    for delta in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
                        q=tuple(p[i]+delta[i] for i in range(3))
                        if q in wood and q not in seen:seen.add(q);queue.append(q)
                self.assertEqual(seen,wood)
                self.assertGreaterEqual(min(p[1] for p,s in m.blocks.items() if s[0].endswith('_leaves')),3)

    def test_flowers_have_authored_soil(self):
        for build in BUILDERS.values():
            m=build()
            for p,s in m.blocks.items():
                if s[0] in ('minecraft:allium','minecraft:white_tulip','minecraft:azure_bluet'):
                    self.assertEqual(m.blocks[(p[0],p[1]-1,p[2])][0],'minecraft:dirt')


if __name__=='__main__':unittest.main()
