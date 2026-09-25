import json
from pathlib import Path
import tempfile
import unittest
from PIL import Image
from export_core_atlas import export


class CoreAtlasExportTest(unittest.TestCase):
    def fixture(self, root):
        bundle = root/'bundle'
        bundle.mkdir()
        model = root/'models/core'
        (model/'previews').mkdir(parents=True)
        Image.new('RGB', (24, 16), 'green').save(model/'previews/front.png')
        self.write(model/'previews/capture.json', dict(nbt_sha256='abc', shots=['front']))
        self.write(bundle/'template_catalog.json', dict(templates=[dict(templateRef='test:core',rawSize=dict(width=10,height=5,depth=8)), dict(templateRef='test:fill')]))
        self.write(bundle/'asset_names.json', [dict(templateRef='test:core',displayName='Core')])
        self.write(bundle/'studio_export_provenance.json', dict(templates=[dict(templateRef='test:core',sourceNbt='models/core/structure.nbt',sourceSha256='sha256:abc')]))
        profiles=[dict(structureId='test:core',reviewState='approved',planningRoleTerms=['planning_role.key'],functionTerms=['教学'],styleTerms=['学院']),dict(structureId='test:fill',reviewState='approved',planningRoleTerms=['planning_role.fill'],functionTerms=['居住'],styleTerms=['学院'])]
        (bundle/'StructureProfile.jsonl').write_text('\n'.join(json.dumps(p) for p in profiles), encoding='utf-8')
        return bundle, model

    def write(self, path, value):
        path.write_text(json.dumps(value), encoding='utf-8')

    def test_only_core_images_with_full_support_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            bundle, _=self.fixture(root)
            result=export(bundle, root)
            self.assertEqual(['test:core'], [c['templateRef'] for c in result['cores']])
            self.assertEqual({'居住':1,'教学':1}, result['supportSummary']['functions'])
            self.assertTrue((bundle/result['pages'][0]['file']).is_file())

    def test_stale_capture_rejected_before_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            bundle, model=self.fixture(root)
            self.write(model/'previews/capture.json',dict(nbt_sha256='old',shots=['front']))
            with self.assertRaisesRegex(ValueError,'Stale'): export(bundle,root)
            self.assertFalse((bundle/'realm_core_atlas.json').exists())


if __name__=='__main__': unittest.main()
