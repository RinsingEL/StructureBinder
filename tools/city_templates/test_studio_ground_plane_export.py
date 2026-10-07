"""Explicit ground contact height survives catalog assembly; legacy remains absent."""
from contextlib import redirect_stdout
import io
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import build_studio_test_bundle as exporter


class GroundPlaneExportTests(unittest.TestCase):
    def test_valid_zero_surface_and_underground_heights(self):
        for y in (0, 2, 15):
            with self.subTest(y=y):
                self.assertEqual(y, exporter.ground_plane_y(dict(
                    id='test', size=[4, 20, 5], ground_plane=dict(y=y, note='外部地面脚底'))))

    def test_absent_does_not_infer_from_preview_or_entrance(self):
        self.assertIsNone(exporter.ground_plane_y(dict(id='legacy', size=[4, 20, 5],
            preview_context=dict(land_surface_y=15),
            points=[dict(kind='entrance', pos=[2, 9, 0])])))

    def test_present_invalid_is_rejected(self):
        cases = [None, [], {}, dict(y=2), dict(note='地面'),
                 dict(y=True, note='地面'), dict(y=2.0, note='地面'),
                 dict(y='2', note='地面'), dict(y=-1, note='地面'),
                 dict(y=20, note='地面'), dict(y=2, note='  '),
                 dict(y=2, note=8)]
        for value in cases:
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'Invalid ground_plane: test'):
                exporter.ground_plane_y(dict(id='test', size=[4, 20, 5], ground_plane=value))

    def test_catalog_codec_provenance_and_summary_preserve_explicit_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            style = '03_desert_stars'
            assets = root / 'asset_catalogs/original_civilizations' / style / 'models'
            ids = ['GP-00-v01', 'GP-02-v01', 'GP-15-v01', 'GP-legacy-v01']
            for key, y in zip(ids, (0, 2, 15, None)):
                asset = assets / key
                asset.mkdir(parents=True)
                (asset / 'structure.nbt').write_bytes(b'isolated fake NBT for codec stub')
                author = dict(id=key, name=key, size=[4,20,5], nbt_sha256='test-source',
                    civilization='沙漠', planning_role='planning_role.key', function_terms=['市集'],asset_tags=['infrastructure'],
                    preview_context=dict(kind='flat', land_surface_y=17),
                    points=[dict(id='front', kind='entrance', pos=[2,3,0], facing='north')])
                if y is not None:
                    author['ground_plane'] = dict(y=y, note='作者明确的外部地面上边界')
                exporter.write(asset / 'author.json', author)
                exporter.write(asset / 'validation.json', dict(passed=True,
                    navigation=dict(passed=True), nbt_sha256='test-source'))
            cache = root / 'tools/structure_studio/.cache'
            cache.mkdir(parents=True)
            exporter.write(cache / 'registry.json', {})
            baseline = root / 'baseline'
            baseline.mkdir()
            exporter.write(baseline / 'blueprint_reference_catalog.json', {})
            classpath = root / 'classpath.txt'
            classpath.write_text('stub-codec')
            args = SimpleNamespace(output=root/'bundle', baseline=baseline,
                                   classpath_file=classpath, java=Path('java'))
            observed_codec = []

            def codec(command, **kwargs):
                rows = exporter.read(args.output / 'codec_input.json')
                observed_codec.extend(rows)
                exporter.write(args.output / 'runtime_metadata.json', [dict(
                    templateRef=row['templateRef'], rawSize=row['rawSize'],
                    templateHash=row['sourceSha256']) for row in rows])

            data = dict(size=[4,20,5], sha256='test-source', data_version=3465,
                        palette=[], blocks=[])
            output = io.StringIO()
            with patch.object(exporter, 'ROOT', root), patch.object(exporter, 'STYLES', (style,)), \
                    patch.object(exporter, 'read_structure', return_value=data), \
                    patch.object(exporter.subprocess, 'run', side_effect=codec) as run, \
                    patch.object(exporter, 'export_core_atlas'), redirect_stdout(output):
                exporter.build(args)
                profiles=[exporter.json.loads(line) for line in (args.output/'StructureProfile.jsonl').read_text().splitlines()]
                self.assertTrue(all(p['category']=='common' and p['assetTags']==['infrastructure'] for p in profiles))
                pools=exporter.read(args.output/'blueprint_reference_catalog.json')['fillPools']
                self.assertEqual(4,len(pools[0]['structureRefs']), 'Old key author roles remain selectable in explicit city fill pools')
                provenance = exporter.read(args.output/'studio_export_provenance.json')
                catalog = exporter.read(args.output/'template_catalog.json')['templates']
                portable_codec = exporter.read(args.output/'codec_input.json')
                for rows in (catalog, observed_codec, portable_codec, provenance['templates']):
                    self.assertEqual([0, 2, 15], [row['groundPlaneY'] for row in rows[:3]])
                    self.assertNotIn('groundPlaneY', rows[3])
                coverage = dict(markedCount=3, unmarkedCount=1, unmarkedIds=[ids[3]])
                self.assertEqual(coverage, provenance['groundPlaneCoverage'])
                self.assertEqual(coverage, exporter.json.loads(output.getvalue())['groundPlaneCoverage'])
                self.assertEqual('作者明确的外部地面上边界',
                                 provenance['templates'][0]['author']['ground_plane']['note'])
                manifest = exporter.read(args.output/'city_template_content_pack.json')
                self.assertEqual(exporter.digest(args.output/'template_catalog.json'), manifest['catalogSha256'])
                # Invalid author data fails before invoking a codec or creating output.
                author_file = assets / ids[0] / 'author.json'
                author = exporter.read(author_file)
                author['ground_plane'] = dict(y=True, note='invalid')
                exporter.write(author_file, author)
                args.output = root/'invalid-bundle'
                run.reset_mock()
                with self.assertRaisesRegex(ValueError, 'Invalid ground_plane'):
                    exporter.build(args)
                run.assert_not_called()
                self.assertFalse(args.output.exists())


if __name__ == '__main__':
    unittest.main()
