"""Small infrastructure tiles consumed along a planting contour, not array members."""
from .model import Model


def tile(number, label, shape):
    m=Model(f'MG-{number}-v01',label,(1,2,1),civilization='通用石质园景',role='infrastructure')
    m.set(0,0,0,'stone_bricks')
    m.set(0,1,0,'stone_brick_slab[type=bottom]' if shape=='end' else
          f'stone_brick_stairs[facing=south,half=bottom,shape={shape},waterlogged=false]')
    m.meta.update(asset_tags=['infrastructure','landscape_border'],placement_consumer='infrastructure',
        source='tools/structure_studio/studio/landscape_fixtures.py',ground_plane={'y':1,'note':'Y=0 承载，Y=1 低边框。'},
        roof_min_y=None,function_terms=['花坛收边'],landscape_border={'kind':shape,'facing':'south','tileSize':1},
        design_notes=['旋转后沿种植边界拼接；端头降为半砖。模板不含植物，不产生独立阵列或台地。'])
    return m


BUILDERS={
 'MG-46':lambda:tile(46,'石质花坛边 · 直段','straight'),
 'MG-47':lambda:tile(47,'石质花坛边 · 外转角','outer_left'),
 'MG-48':lambda:tile(48,'石质花坛边 · 内转角','inner_left'),
 'MG-49':lambda:tile(49,'石质花坛边 · 半砖端头','end'),
}


if __name__=='__main__':
    import json
    from .server import CATALOG,TOOL
    registry=json.loads((TOOL/'.cache/registry.json').read_text(encoding='utf-8'))
    for builder in BUILDERS.values():
        m=builder();m.export(CATALOG/'R02_arcane_reborn'/'models'/m.meta['id'],registry);print(m.meta['id'])
