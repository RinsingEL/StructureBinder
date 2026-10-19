import unittest

from studio.lighting import field


class LightingTests(unittest.TestCase):
    def data(self, lamp_y, wall=False):
        registry = {
            'minecraft:air': dict(transparent=True, collisions=[[]], light_emission=0, light_filter=0),
            'minecraft:lantern': dict(transparent=True, collisions=[[[0, 0, 0, 1, 1, 1]]], light_emission=15, light_filter=0),
            'minecraft:stone': dict(transparent=False, collisions=[[[0, 0, 0, 1, 1, 1]]], light_emission=0, light_filter=15),
        }
        data = dict(size=[9, 24, 9], palette=[dict(name=n, properties={}) for n in registry],
                    blocks=[dict(pos=[2, lamp_y, 4], state=1)])
        if wall:
            data['blocks'] += [dict(pos=[4, y, z], state=2) for y in range(24) for z in range(9)]
        return data, registry

    def test_high_lamp_cannot_light_distant_floor_but_low_lamp_can(self):
        for lamp_y, expected in ((20, 0), (5, 12)):
            levels, _ = field(*self.data(lamp_y))
            self.assertEqual(levels[2 * 81 + 4 * 9 + 2], expected)

    def test_opaque_wall_blocks_light_and_no_skylight_is_invented(self):
        levels, _ = field(*self.data(3, wall=True))
        self.assertGreater(levels[3 * 81 + 4 * 9 + 3], 0)
        self.assertEqual(levels[3 * 81 + 4 * 9 + 6], 0)

    def test_missing_light_registry_metadata_is_rejected(self):
        data, registry = self.data(3)
        del registry['minecraft:air']['light_filter']
        with self.assertRaisesRegex(ValueError, 'Registry needs lighting fields'):
            field(data, registry)


if __name__ == '__main__':
    unittest.main()
