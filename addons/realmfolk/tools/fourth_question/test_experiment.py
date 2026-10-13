import copy
import hashlib
import tempfile
import unittest
from pathlib import Path

from experiment import HERE, read, write, compile_intent, freeze_d4, nearby_candidates


class FourthQuestionTest(unittest.TestCase):
    def setUp(self):
        self.intent = read(HERE / 'academy.intent.json')
        self.policy = read(HERE / 'author-policy.json')
        self.evidence = read(HERE / 'results/academy/evidence.json')
        self.economy = read(HERE / 'results/academy/economy.json')

    def compile(self):
        return compile_intent(self.intent, self.policy, self.evidence, self.economy)

    def test_same_inputs_reproduce_and_do_not_mutate_upstream(self):
        before = copy.deepcopy((self.intent, self.policy, self.evidence, self.economy))
        self.assertEqual(self.compile(), self.compile())
        self.assertEqual(before, (self.intent, self.policy, self.evidence, self.economy))

    def test_never_guess_unknown_work_function(self):
        self.intent['groups'][0]['work'] = ['未建造的炼金塔']
        with self.assertRaisesRegex(ValueError, 'Unknown work'):
            self.compile()

    def test_unknown_pool_and_unprovided_ability_rejected(self):
        self.intent['groups'][0]['pools'] = ['invented_magic']
        with self.assertRaisesRegex(ValueError, 'Unknown goods'):
            self.compile()
        self.intent = read(HERE / 'academy.intent.json')
        self.intent['groups'][0]['requestedAbilities'] = ['invented_behavior']
        with self.assertRaisesRegex(ValueError, 'Unknown requested'):
            self.compile()

    def test_absent_item_source_does_not_make_unobtainable_commission(self):
        self.policy['needs']['kitchen_supplies']['item'] = 'minecraft:wheat'
        with self.assertRaisesRegex(ValueError, 'no authored acquisition'):
            self.compile()

    def test_same_id_and_invalid_weights_rejected(self):
        self.intent['groups'][1]['id'] = self.intent['groups'][0]['id']
        with self.assertRaisesRegex(ValueError, 'duplicate group'):
            self.compile()
        self.intent = read(HERE / 'academy.intent.json')
        self.intent['groups'][0]['roster']['specialist'] = float('nan')
        with self.assertRaisesRegex(ValueError, 'relative role weight'):
            self.compile()

    def test_rejected_or_changed_d4_cannot_be_consumed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write(root / 'city_blueprint.json', {'districtDesigns': []})
            digest = 'sha256:' + hashlib.sha256((root / 'city_blueprint.json').read_bytes()).hexdigest()
            write(root / 'city_blueprint_geometry_commit.json', {'blueprintHash': digest})
            write(root / 'city_blueprint_catalog_snapshot.json', {})
            trace = {'status': 'rejected', 'cityBlueprintHash': digest}
            write(root / 'city_blueprint_submission_trace.json', trace)
            with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                freeze_d4(root)
            trace['status'] = 'accepted'
            write(root / 'city_blueprint_submission_trace.json', trace)
            write(root / 'city_blueprint.json', {'districtDesigns': [], 'changed': True})
            with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                freeze_d4(root)

    def test_housing_matches_retained_functions_and_capabilities_are_not_faked(self):
        submission, report = self.compile()
        for p in submission['professions'].values():
            self.assertNotIn('teach_magic', p['capabilities'])
            self.assertNotIn('nearby_hunt', p['capabilities'])
        self.assertTrue(report['pendingAbilities'])
        self.policy['population']['housingTerms'] = ['未建造的住宅']
        with self.assertRaisesRegex(ValueError, 'Housing'):
            self.compile()

    def test_adventure_generation_is_not_kill_completion(self):
        base = dict(id='ready', dimension='minecraft:overworld', complete=True, tier=2, x=100, z=0)
        sites = [base, dict(base, id='pending', complete=False), dict(base, id='far', x=10000),
                 dict(base, id='boss', tier=3), dict(base, id='nether', dimension='minecraft:the_nether')]
        result = nearby_candidates(sites, (0, 0), 'minecraft:overworld', 2048, 2)
        self.assertEqual(['ready'], [s['id'] for s in result['locations']])
        self.assertEqual({'not_materialized', 'too_far', 'tier_out_of_range', 'different_dimension'}, {s['reason'] for s in result['excluded']})
        self.assertEqual([], result['huntContracts'])


if __name__ == '__main__':
    unittest.main()
