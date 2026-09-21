import unittest
from curate_planning_roles import classify, FILL, KEY, STRUCTURE, COMPLETE


class PlanningRolesTest(unittest.TestCase):
    def test_small_functional_core_is_not_repeated(self):
        self.assertEqual([KEY], classify('祭坛', ['宗教'], dict(width=9,height=10,depth=9))[0])

    def test_small_shops_and_stalls_are_exposed_as_fill(self):
        for name, function in [('店铺1','商业'), ('摊位1','小型配套')]:
            self.assertEqual([FILL], classify(name,[function],dict(width=5,height=5,depth=5))[0])

    def test_size_does_not_turn_large_housing_into_a_core(self):
        self.assertEqual([STRUCTURE],classify('木屋',[ '住宅'],dict(width=50,height=22,depth=23))[0])

    def test_garden_name_does_not_authorize_automatic_decoration_or_complete_exemption(self):
        roles=classify('花园',['小型配套'],dict(width=22,height=9,depth=22))[0]
        self.assertEqual([STRUCTURE],roles)
        self.assertNotIn(COMPLETE,roles)

    def test_unknown_function_does_not_enter_fill_because_it_is_small(self):
        self.assertEqual([STRUCTURE],classify('不明物件',['功能待确认'],dict(width=4,height=5,depth=4))[0])


if __name__ == '__main__':
    unittest.main()
