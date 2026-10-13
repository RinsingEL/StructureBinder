from pathlib import Path
p=Path('country_designer_mcp/test/city-blueprint-tools.test.mjs');s=p.read_text(encoding='utf-8').replace('/waiting_for_patch_review/','/D3 后直接准备 D4 context/').replace('district and integration expose optional rectangle layout without placement coordinates','district and integration expose rectangle layout with explicit root coordinates');p.write_text(s,encoding='utf-8')
