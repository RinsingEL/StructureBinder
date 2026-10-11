import unittest
from classification_coverage import coverage


class ClassificationCoverageTests(unittest.TestCase):
    def test_aliases_unknowns_and_broad_terms_remain_distinct(self):
        rows = [dict(structureId='test:observatory', functionTerms=['天象观测', '研究']),
                dict(structureId='test:garden', functionTerms=['花草观赏', '阅读', '休憩'], assetTags=['landscape']),
                dict(structureId='test:service', functionTerms=['生活服务']),
                dict(structureId='test:unknown', functionTerms=['未收录的新用途'])]
        before = repr(rows)
        result = coverage(rows)
        self.assertEqual(3, result['classifiedCount'])
        self.assertEqual(['test:unknown'], result['unclassifiedRefs'])
        self.assertEqual(['未收录的新用途'], result['unmappedFunctionTerms'])
        self.assertEqual(['services'], result['entries'][2]['functionIds'])
        self.assertIn('education.observation.astronomy', result['entries'][0]['functionIds'])
        self.assertEqual(1, result['assetTagCounts']['landscape'])
        self.assertEqual(before, repr(rows))

    def test_explicit_asset_rules_and_empty_terms(self):
        result = coverage([dict(structureId='studio:ds-f01-v01', functionTerms=['零售', '烘焙']),
                           dict(structureId='test:empty', functionTerms=[])])
        self.assertIn('retail.food.bread', result['entries'][0]['functionIds'])
        self.assertEqual(1, result['missingFunctionTermsCount'])


if __name__ == '__main__':
    unittest.main()
