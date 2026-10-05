"""Protect complete core layouts while changing their authored component themes."""
import unittest

from studio.chinese import manor, palace, inn
from studio.chinese_palace import palace_grand


class ChineseCoreTests(unittest.TestCase):
    def assert_changes_confined(self, before, after, lower, upper):
        changed = {p for p in before.blocks.keys() | after.blocks.keys()
                   if before.blocks.get(p) != after.blocks.get(p)}
        self.assertTrue(changed, 'A selected theme must actually change the exported geometry')
        self.assertTrue(all(all(lower[i] <= p[i] <= upper[i] for i in range(3)) for p in changed))
        for field in ('size', 'rooms', 'points', 'ground_plane', 'planning_role'):
            self.assertEqual(before.meta[field], after.meta[field], field)

    def test_manor_themes_preserve_house_and_circulation(self):
        original = manor()
        for theme in ('gourd', 'flower'):
            with self.subTest(theme=theme):
                self.assert_changes_confined(original, manor(garden_theme=theme), (21,2,36), (29,7,45))
        for shop in ('cloth', 'woodcraft', 'tools'):
            with self.subTest(shop=shop):
                self.assert_changes_confined(original, manor(shop=shop), (5,3,4), (15,7,10))

    def test_inn_keeps_six_private_rooms_twelve_beds_and_service_spaces(self):
        m = inn()
        guest_rooms = [r for r in m.meta['rooms'] if '_guest_' in r['id']]
        self.assertEqual(len(guest_rooms), 6)
        feet = {p for p, (name, props) in m.blocks.items()
                if name.endswith('_bed') and dict(props)['part'] == 'foot'}
        self.assertEqual(len(feet), 12)
        for room in guest_rooms:
            self.assertEqual(sum(all(room['min'][i] <= p[i] <= room['max'][i] for i in range(3))
                                 for p in feet), 2)
        self.assertTrue({'reception','dining','inn_kitchen','bath_linen'} <= {r['id'] for r in m.meta['rooms']})

    def test_palace_configuration_stays_inside_the_ceremonial_hall(self):
        self.assert_changes_confined(palace(), palace(ceremony='banquet'), (21,5,26), (39,10,38))
        with self.assertRaises(ValueError): manor(garden_theme='unknown')
        with self.assertRaises(ValueError): manor(shop='unknown')

    def test_grand_palace_themes_preserve_terrace_and_access(self):
        original = palace_grand()
        self.assert_changes_confined(original, palace_grand(ceremony='banquet'),
                                     (28,7,39), (52,14,56))
        for theme in ('grape', 'gourd'):
            with self.subTest(theme=theme):
                self.assert_changes_confined(original, palace_grand(garden_theme=theme),
                                             (24,2,71), (32,7,76))


if __name__ == '__main__':
    unittest.main()
