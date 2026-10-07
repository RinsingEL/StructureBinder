import unittest
from build_studio_test_bundle import project_entrances, authored_classification


class EntranceExportTest(unittest.TestCase):
    def test_author_classification_uses_shared_family_rule_not_name_or_role(self):
        self.assertEqual(dict(category='common',assetTags=['infrastructure']),authored_classification(dict(id='CH-24-v01',name='核心',planning_role='planning_role.key',asset_tags=['infrastructure'])))
        self.assertEqual('specialty',authored_classification(dict(id='CH-22-v01',planning_role='planning_role.fill'))['category'])
        for tags in (None,'landscape',['landscape','landscape'],['guessed']):
            with self.assertRaises(ValueError): authored_classification(dict(id='CH-24-v01',asset_tags=tags))

    def fixtures(self):
        registry = {'minecraft:stone': dict(default={}, state_order=[], properties={}, collisions=[[[0,0,0,1,1,1]]])}
        data = dict(size=[7,6,8], palette=[dict(name='minecraft:stone',properties={})], blocks=[])
        author = dict(id='test',points=[dict(id='front',kind='entrance',pos=[3,2,3],facing='north')])
        return author,data,registry

    def test_all_explicit_facings_and_missing_facing(self):
        a,d,r = self.fixtures()
        for facing,position in [('north',{'x':3,'z':0}),('south',{'x':3,'z':7}),('west',{'x':0,'z':3}),('east',{'x':6,'z':3})]:
            a['points'][0]['facing']=facing
            ports,evidence=project_entrances(a,d,r)
            self.assertEqual(position,ports[0]['position'])
            self.assertFalse(evidence[0]['worldRoadHeightValidated'])
        del a['points'][0]['facing']
        with self.assertRaises(ValueError): project_entrances(a,d,r)

    def test_body_obstruction_rejected_but_floor_allowed(self):
        a,d,r=self.fixtures()
        d['blocks']=[dict(pos=[3,1,1],state=0)]
        project_entrances(a,d,r)
        d['blocks'].append(dict(pos=[3,2,1],state=0))
        with self.assertRaises(ValueError): project_entrances(a,d,r)

    def test_missing_and_outside_entrances_fail(self):
        a,d,r=self.fixtures()
        a['points'][0]['pos']=[9,2,3]
        with self.assertRaises(ValueError): project_entrances(a,d,r)
        a['points']=[]
        with self.assertRaises(ValueError): project_entrances(a,d,r)


if __name__ == '__main__': unittest.main()
