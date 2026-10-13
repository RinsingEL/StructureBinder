import copy
import json
import tempfile
import unittest
from pathlib import Path
from lab import HERE, SOURCE, OUTPUT, read, write, build, compile_pack, new_state, perform, program, digest


class CoreTaskTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pack = build()

    def new(self, ident='starfall_welcome', loadout='arcane'):
        self.state = new_state(self.pack, ident, loadout)
        self.p = program(self.pack, ident)
        self.host, self.partner = self.p['actors']
        self.sequence = 0

    def act(self, action, target=None):
        self.sequence += 1
        req = dict(action=action,eventId='test_'+str(self.sequence),expectedRevision=self.state['revision'])
        if target is not None:
            req['target'] = target
        self.state = perform(self.pack, self.state, req)
        return req

    def introduction(self):
        self.act('talk',self.host['id'])
        self.act('travel',self.partner['place'])
        self.act('talk',self.partner['id'])
        self.act('travel',self.host['place'])

    def project(self):
        self.act('travel',self.p['site'])
        self.act('record')
        self.act('travel',self.host['place'])
        self.act('report')

    def test_two_bindings_reuse_types_but_differ_in_people_sites_and_effects(self):
        a,b=self.pack['programs']
        self.assertEqual([s['type'] for s in a['stages']],[s['type'] for s in b['stages']])
        self.assertNotEqual(a['site'],b['site'])
        self.assertNotEqual(a['actors'][0]['id'],b['actors'][0]['id'])
        self.assertNotEqual(a['themeData']['effect'],b['themeData']['effect'])

    def test_all_programs_contributions_and_reward_choices_are_playable(self):
        results=[]
        for p in self.pack['programs']:
            for route in p['routes']:
                for reward in p['rewards']:
                    self.new(p['id'],route)
                    self.introduction()
                    self.act('contribute',route)
                    self.assertEqual(0,self.state['inventory'][p['routes'][route]['item']])
                    self.project()
                    self.act('claim',reward)
                    self.assertTrue(self.state['completed'])
                    self.assertEqual(12,self.state['favor'])
                    self.assertEqual(92,self.state['coins'])
                    self.assertEqual(p['rewards'][reward]['items'],{i:n for i,n in self.state['inventory'].items() if n})
                    old=self.state['coins']
                    item=next(iter(self.state['service']))
                    self.act('buy',item)
                    self.assertEqual(old-p['service'][item]['price'],self.state['coins'])
                    self.act('talk',self.host['id'])
                    self.assertIn(p['themeData']['effect'],self.state['history'][-1])
                    results.append({'program':p['id'],'route':route,'reward':reward,'completed':True,'newServiceUsed':True})
        write(OUTPUT/'path-verification.json',{'passed':True,'paths':results,'scope':'pure_rule_engine_not_minecraft'})

    def test_unknown_target_wrong_place_and_missing_material_do_not_mutate(self):
        self.new()
        for action,target in [('talk',self.partner['id']),('travel','imaginary_dungeon'),('record',None),('claim','arcane_starter')]:
            before=copy.deepcopy(self.state)
            with self.assertRaises(ValueError):self.act(action,target)
            self.assertEqual(before,self.state)
        self.introduction()
        before=copy.deepcopy(self.state)
        with self.assertRaisesRegex(ValueError,'物资不足'):self.act('contribute','industry')
        self.assertEqual(before,self.state)

    def test_contribution_and_rewards_are_once_only_and_receipts_are_replay_safe(self):
        self.new();self.introduction()
        req=self.act('contribute','arcane')
        self.assertEqual(self.state,perform(self.pack,self.state,req))
        with self.assertRaises(ValueError):self.act('contribute','arcane')
        self.project();req=self.act('claim','field_starter')
        self.assertEqual(self.state,perform(self.pack,self.state,req))
        before=copy.deepcopy(self.state)
        with self.assertRaises(ValueError):self.act('claim','field_starter')
        self.assertEqual(before,self.state)

    def test_save_reload_keeps_progress_and_receipts(self):
        self.new();self.introduction();req=self.act('contribute','arcane')
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'session.json';write(p,self.state);self.state=read(p)
        self.assertEqual(self.state,perform(self.pack,self.state,req))
        self.project();self.act('claim','workshop_starter');self.assertTrue(self.state['completed'])

    def test_stale_view_receipt_reuse_and_changed_pack_are_rejected(self):
        self.new()
        with self.assertRaisesRegex(ValueError,'过期'):
            perform(self.pack,self.state,dict(action='talk',target=self.host['id'],eventId='stale',expectedRevision=99))
        req=self.act('talk',self.host['id']);req['action']='record'
        with self.assertRaisesRegex(ValueError,'不可替换'):perform(self.pack,self.state,req)
        self.state['packHash']='old'
        with self.assertRaisesRegex(ValueError,'改变'):self.act('travel',self.partner['place'])

    def test_cannot_report_before_record_or_claim_while_away(self):
        self.new();self.introduction();self.act('contribute','arcane')
        with self.assertRaises(ValueError):self.act('report')
        self.project();self.act('travel',self.p['site'])
        with self.assertRaises(ValueError):self.act('claim','arcane_starter')

    def compile_changed(self, change):
        author,intent,evidence,runtime,economy=(read(p) for p in [HERE/'author-catalog.json',HERE/'ai-programs.json',SOURCE/'evidence.json',SOURCE/'runtime.json',SOURCE/'economy.json'])
        change(author,intent,evidence,runtime,economy)
        return compile_pack(author,intent,evidence,runtime,economy)

    def test_unknown_type_or_fabricated_site_cannot_compile(self):
        with self.assertRaises(ValueError):
            self.compile_changed(lambda a,i,e,r,c:i['programs'][0].update(program='magic_script'))
        with self.assertRaises(ValueError):
            self.compile_changed(lambda a,i,e,r,c:i['programs'][0].update(siteFunction='附近地牢'))
        with self.assertRaises(ValueError):
            self.compile_changed(lambda a,i,e,r,c:a['types']['shared_research'].update(checks=['cast_any_spell']))

    def test_missing_npc_and_reward_budget_fail_before_play(self):
        with self.assertRaises(ValueError):
            self.compile_changed(lambda a,i,e,r,c:i['programs'][0].update(hostGroup='nonexistent_family'))
        with self.assertRaisesRegex(ValueError,'预算'):
            self.compile_changed(lambda a,i,e,r,c:a['rewards']['arcane_starter'].update(items={'ars_nouveau:source_gem':64}))

    def test_input_tampering_and_fabricated_reward_or_inventory_rejected(self):
        self.new()
        with self.assertRaisesRegex(ValueError,'字段'):
            perform(self.pack,self.state,dict(action='talk',target=self.host['id'],eventId='x',expectedRevision=0,coins=999))
        self.introduction();self.act('contribute','arcane');self.project()
        with self.assertRaisesRegex(ValueError,'允许'):
            self.act('claim','creative_inventory')


if __name__=='__main__':
    unittest.main()
