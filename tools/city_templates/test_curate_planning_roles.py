import unittest
import tempfile
from pathlib import Path
from curate_planning_roles import classify, curate, read, write, footprint_scale, height_class, FILL, KEY, STRUCTURE, COMPLETE


class PlanningRolesTest(unittest.TestCase):
    def test_small_functional_core_is_not_repeated(self):
        self.assertEqual([KEY], classify('祭坛', ['宗教'], dict(width=9,height=10,depth=9))[0])

    def test_small_shops_and_stalls_are_exposed_as_fill(self):
        for name, function in [('店铺1','商业'), ('摊位1','小型配套')]:
            self.assertEqual([FILL], classify(name,[function],dict(width=5,height=5,depth=5))[0])

    def test_size_does_not_turn_large_housing_into_a_core(self):
        self.assertEqual([FILL],classify('木屋',[ '住宅'],dict(width=50,height=22,depth=23))[0])

    def test_named_uses_recover_coarse_function_labels(self):
        for name in ('烘焙房', '草料棚', '凉亭'):
            self.assertEqual([FILL], classify(name,['功能待确认'],dict(width=15,height=12,depth=12))[0])
        self.assertEqual([KEY], classify('祭祀金字塔',['功能待确认'],dict(width=21,height=20,depth=20))[0])

    def test_footprint_and_height_are_independent(self):
        size=dict(width=7,depth=7,height=66)
        self.assertEqual('small',footprint_scale(size)[0])
        self.assertEqual('very_tall',height_class(size))

    def test_migration_reaches_large_housing_without_mixing_small_pools_or_losing_manual_roles(self):
        with tempfile.TemporaryDirectory() as temp:
            bundle=Path(temp)
            profiles=[dict(structureId='test:house',functionTerms=['住宅'],styleTerms=['中世纪'],planningRoleTerms=[STRUCTURE]),
                      dict(structureId='test:manual',functionTerms=['住宅'],styleTerms=['中世纪'],planningRoleTerms=[KEY])]
            (bundle/'StructureProfile.jsonl').write_text('\n'.join(__import__('json').dumps(p) for p in profiles),encoding='utf8')
            write(bundle/'asset_names.json',[dict(templateRef=p['structureId'],displayName='木屋',functionTerms=p['functionTerms']) for p in profiles])
            write(bundle/'template_catalog.json',dict(templates=[dict(templateRef=p['structureId'],contentHash='hash',rawSize=dict(width=50,depth=23,height=22)) for p in profiles]))
            write(bundle/'blueprint_reference_catalog.json',dict(fillPools=[dict(poolRef='pool:legacy',structureRefs=[])]))
            write(bundle/'StructureVocabulary.snapshot.json',dict(terms=[]))
            output=bundle/'audit.json'
            write(output,dict(policy='conservative_function_and_footprint_v1',assignments=[dict(templateRef='test:house',planningRoleTerms=[STRUCTURE])]))
            curate(bundle,output)  # Dry run must not prevent the subsequent migration.
            curate(bundle,output,apply=True)
            rows={row['templateRef']:row for row in read(output)['assignments']}
            self.assertEqual([FILL],rows['test:house']['planningRoleTerms'])
            self.assertEqual([KEY],rows['test:manual']['planningRoleTerms'])
            self.assertIn('占地50×23·高22',rows['test:house']['displayName'])
            pools=read(bundle/'blueprint_reference_catalog.json')['fillPools']
            self.assertEqual(['test:house'],next(p['structureRefs'] for p in pools if p['poolRef']=='pool:scaled_housing_medieval_extra_large_medium'))
            self.assertFalse(any('test:house' in p['structureRefs'] for p in pools if p['poolRef'].startswith('pool:public_')))
            before={name:(bundle/name).read_bytes() for name in ('asset_names.json','StructureProfile.jsonl','blueprint_reference_catalog.json')}
            curate(bundle,output,apply=True)
            self.assertEqual(before,{name:(bundle/name).read_bytes() for name in before})

    def test_garden_name_does_not_authorize_automatic_decoration_or_complete_exemption(self):
        roles=classify('花园',['小型配套'],dict(width=22,height=9,depth=22))[0]
        self.assertEqual([STRUCTURE],roles)
        self.assertNotIn(COMPLETE,roles)

    def test_unknown_function_does_not_enter_fill_because_it_is_small(self):
        self.assertEqual([STRUCTURE],classify('不明物件',['功能待确认'],dict(width=4,height=5,depth=4))[0])


if __name__ == '__main__':
    unittest.main()
