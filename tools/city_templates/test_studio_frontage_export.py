"""Exercise the full bundle assembly with isolated sources and a stub native codec."""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import build_studio_test_bundle as exporter
from studio.frontage import save
from studio.model import sha256


class StudioFrontageExportTests(unittest.TestCase):
    def test_invalid_frontage_aborts_then_first_default_and_manual_override_export(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            asset = root / 'asset_catalogs/original_civilizations/03_desert_stars/models/DS-F04-v03'
            source = exporter.ROOT / 'asset_catalogs/original_civilizations/03_desert_stars/models/DS-F04-v03'
            asset.mkdir(parents=True)
            for name in ['author.json', 'structure.nbt', 'validation.json']:
                shutil.copy2(source / name, asset / name)
            author = exporter.read(asset / 'author.json'); author.pop('frontage', None)
            exporter.write(asset / 'author.json', author)
            cache = root / 'tools/structure_studio/.cache'; cache.mkdir(parents=True)
            exporter.write(cache / 'registry.json', {})
            baseline = root / 'baseline'; baseline.mkdir()
            exporter.write(baseline / 'blueprint_reference_catalog.json', {})
            classpath = root / 'classpath.txt'; classpath.write_text('test-codec')
            args = SimpleNamespace(output=root / 'bundle', baseline=baseline, java=Path('java'), classpath_file=classpath)
            def projection(author, data, registry):
                return [dict(entranceId=p['id'], position=dict(x=p['pos'][0], z=p['pos'][2]), direction=p['facing'].upper())
                        for p in author['points'] if p['kind'] == 'entrance'], []
            def codec(command, **kwargs):
                rows = exporter.read(args.output / 'codec_input.json')
                exporter.write(args.output / 'runtime_metadata.json', [dict(templateRef=r['templateRef'],
                    rawSize=r['rawSize'], templateHash=r['sourceSha256']) for r in rows])
            with patch.object(exporter, 'ROOT', root), patch.object(exporter, 'STYLES', ('03_desert_stars',)), \
                    patch.object(exporter, 'project_entrances', side_effect=projection), \
                    patch.object(exporter.subprocess, 'run', side_effect=codec) as run, \
                    patch.object(exporter, 'export_core_atlas'), redirect_stdout(io.StringIO()):
                author['frontage'] = {'policy': 'INVALID'}
                exporter.write(asset / 'author.json', author)
                with self.assertRaisesRegex(ValueError, 'STUDIO_FRONTAGE_REQUIRED: DS-F04-v03'):
                    exporter.build(args)
                self.assertFalse(args.output.exists())
                run.assert_not_called()
                author.pop('frontage')
                exporter.write(asset / 'author.json', author)
                exporter.build(args)
                default = exporter.read(args.output / 'template_catalog.json')['templates'][0]
                self.assertEqual('NORTH', next(p for p in default['roadEntrances'] if p['entranceId'] == 'front')['direction'])
                args.output = root / 'manual-bundle'
                save(asset, dict(author_sha256=sha256(asset / 'author.json'), nbt_sha256=sha256(asset / 'structure.nbt'),
                                 policy='FIXED_FRONT', entrance_id='side'))
                exporter.build(args)
            catalog = exporter.read(args.output / 'template_catalog.json')
            template = catalog['templates'][0]
            self.assertEqual('FIXED_FRONT', template['frontagePolicy'])
            self.assertEqual('EAST', next(p for p in template['roadEntrances'] if p['entranceId'] == 'front')['direction'])
            manifest = exporter.read(args.output / 'city_template_content_pack.json')
            self.assertEqual(exporter.digest(args.output / 'template_catalog.json'), manifest['catalogSha256'])
            self.assertEqual(sha256(asset / 'structure.nbt'), sha256(args.output / 'city_template_content_pack/studio/structures/ds-f04-v03.nbt'))


if __name__ == '__main__':
    unittest.main()
