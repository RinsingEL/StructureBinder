import tempfile
import unittest
from pathlib import Path

from studio.living import extract
from studio.model import Model, read_structure


class LivingFacilitiesTest(unittest.TestCase):
    def export(self, model):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        model.export(Path(tmp.name))
        return read_structure(Path(tmp.name) / 'structure.nbt')

    def test_complete_bed_is_one_site_and_foot_annotation_supplies_use(self):
        m = Model('test', 'beds', (8, 5, 8)).bed(2, 1, 3)
        m.point('patient', 'bed', (2, 1, 3), '病床', approach=(3, 1, 3), use='care')
        sites = extract(self.export(m), m.meta)['facilities']
        self.assertEqual(1, len(sites))
        self.assertEqual([2, 1, 2], sites[0]['pos'])
        self.assertEqual('care', sites[0]['use'])
        self.assertEqual([3, 1, 3], sites[0]['approach'])

    def test_stale_authored_bed_never_invents_physical_capacity(self):
        m = Model('test', 'empty', (8, 5, 8)).set(0, 0, 0, 'stone')
        m.point('bed', 'bed', (2, 1, 3), 'bed')
        self.assertEqual([], extract(self.export(m), m.meta)['facilities'])

    def test_incomplete_bed_is_reported_and_not_counted(self):
        m = Model('test', 'broken', (8, 5, 8)).bed(2, 1, 3).set(2, 1, 3, 'air')
        result = extract(self.export(m), m.meta)
        self.assertEqual([], result['facilities'])
        self.assertEqual(1, len(result['warnings']))

    def test_target_pack_tags_and_authored_work_points_have_distinct_roles(self):
        data = dict(size=[8, 5, 8], sha256='test', palette=[dict(name='furniture:cushion', properties={}), dict(name='minecraft:air', properties={})],
                    blocks=[dict(pos=[2, 1, 2], state=0), dict(pos=[4, 1, 4], state=1)])
        author = dict(points=[dict(id='nurse', kind='work', pos=[4, 1, 4], approach=[4, 1, 4], name='护理')])
        self.assertEqual(['work'], [s['kind'] for s in extract(data, author)['facilities']])
        rules = dict(tags={'pack:seats': ['furniture:cushion']}, facilities=[dict(blocks=['#pack:seats'], kind='seat', adapter='marker_seat', seatHeight=.2)])
        sites = extract(data, author, rules)['facilities']
        self.assertEqual(['seat', 'work'], [s['kind'] for s in sites])
        self.assertEqual('marker_seat', sites[0]['adapter'])
        self.assertEqual('minecraft:air', sites[1]['block'])

    def test_room_use_does_not_turn_guest_beds_into_resident_beds(self):
        m = Model('test', 'inn', (8, 5, 8)).bed(2, 1, 3)
        m.room('guest', '客房', (1, 1, 1), (6, 3, 6), '住宿')
        m.meta['rooms'][0]['living_use'] = 'guest'
        self.assertEqual('guest', extract(self.export(m), m.meta)['facilities'][0]['use'])

    def test_work_block_and_author_annotation_do_not_double_count(self):
        m = Model('test', 'shop', (8, 5, 8)).set(2, 1, 3, 'smithing_table')
        m.point('smith', 'work', (2, 1, 3), '锻造', approach=(3, 1, 3))
        self.assertEqual(1, len(extract(self.export(m), m.meta)['facilities']))
