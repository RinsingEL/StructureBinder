import unittest
from studio.chinese_daily import shop,arch_bridge
from studio.chinese_landscape import BUILDERS

class ChineseDailyTests(unittest.TestCase):
    def test_shop_theme_changes_goods_without_changing_walkways(self):
        a=shop()
        for theme in ('cloth','woodcraft','tools'):
            b=shop(goods=theme)
            for field in ('rooms','points','size'):self.assertEqual(a.meta[field],b.meta[field])
            changed={p for p in a.blocks.keys()|b.blocks.keys() if a.blocks.get(p)!=b.blocks.get(p)}
            self.assertTrue(changed)
            self.assertTrue(all(6<=x<=22 and y==3 and 8<=z<=21 for x,y,z in changed))

    def test_landscapes_keep_distinct_geometry_and_explicit_tags(self):
        models=[fn() for fn in BUILDERS.values()]
        self.assertTrue(all(m.meta['asset_tags']==['landscape'] for m in models))
        self.assertEqual(len({tuple(sorted(m.blocks.items())) for m in models}),4)
        self.assertEqual(arch_bridge().meta['asset_tags'],['infrastructure'])

if __name__=='__main__':unittest.main()
