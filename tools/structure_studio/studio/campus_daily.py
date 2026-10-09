"""Complete independent support buildings and capped wing variants."""
from .academy_support import house
from .academy_wings import teaching_wing, dorm_court


def lab():
    m=house(43,'小实验室 · 双侧实验台与器材柜',23,25,['实验教学','研究'],furnishing='shed')
    m.meta['source']=__file__;m.meta['roof_min_y']=9
    for x in (6,16):
        m.box((x,2,9),(x,2,17),'polished_andesite')
        for z in (10,14):m.set(x,3,z,'brewing_stand')
        m.set(x,3,17,'cauldron')
    m.point('lab','work',(11,2,16),'中央实验操作区')
    return m


def washhouse():
    m=house(44,'小洗浴房 · 更衣柜与双池',23,25,['洗浴','洗衣','生活服务'],furnishing='shed')
    m.meta['source']=__file__;m.meta['roof_min_y']=9
    for xa in (6,14):
        m.box((xa,1,10),(xa+3,1,17),'stone_bricks')
        m.box((xa,2,10),(xa+3,2,17),'smooth_stone')
        m.box((xa+1,2,11),(xa+2,2,16),'water[level=0]')
    m.point('wash','work',(11,2,14),'池间洗浴通路')
    return m


def shop():
    m=house(45,'街角小商店 · 门前棚摊与货架',25,25,['日用品交易','生活服务'],furnishing='shed')
    m.meta['source']=__file__;m.meta['roof_min_y']=9
    m.box((3,2,1),(7,2,2),'barrel');m.box((3,3,1),(7,3,2),'dark_oak_slab[type=top]')
    for x in (3,7):m.box((x,2,0),(x,5,0),'spruce_fence')
    m.box((2,6,0),(8,6,3),'purple_wool')
    m.set(19,2,2,'barrel');m.set(20,2,2,'chest[facing=north]');m.set(19,3,2,'flower_pot')
    m.point('stall','work',(5,2,3),'门前小摊服务位')
    return m


def capped(builder,ident,name):
    m=builder();m.meta['id']=ident;m.meta['name']=name;m.meta['design_revision']=2;m.meta['source']=__file__
    for port in m.meta['connections']:
        if port['standing_y']<=2:continue
        x,y,z=port['pos'];direction=port['direction']
        for delta in range(-2,3):
            xx,zz=(x,z+delta) if direction in ('east','west') else (x+delta,z)
            m.set(xx,y,zz,'iron_bars[north=true,south=true]' if direction in ('east','west') else 'iron_bars[east=true,west=true]')
    removed={p['point_id'] for p in m.meta['connections']}
    m.meta['points']=[p for p in m.meta['points'] if p['id'] not in removed]
    m.meta['connections']=[];m.meta['design_notes']=['独立完整建筑；二层侧端为封闭护栏阳台，地面侧门保留，不要求连接廊。']
    return m


BUILDERS={'MG-43-v01':lab,'MG-44-v01':washhouse,'MG-45-v01':shop,
 'MG-34-v02':lambda:capped(teaching_wing,'MG-34-v02','双层教学翼楼 · 完整阳台收口'),
 'MG-37-v02':lambda:capped(dorm_court,'MG-37-v02','双翼宿舍 · 完整阳台收口')}
