"""Two style kits for the same geometry-driven terrace facility consumer."""
from .model import Model


def fixture(key, name, size, style, terms):
    m=Model(key,name,size,civilization=style,role='structure')
    m.meta.update(source='tools/structure_studio/studio/terrace_fixtures.py',
        asset_tags=['infrastructure'],placement_consumer='infrastructure',function_terms=terms,
        ground_plane={'y':2,'note':'底板 Y=1，程序台地表面与底板对齐。'},
        placement_anchor={'pos':[size[0]//2,2,size[2]//2],'note':'中心定位基准，不是玩家使用点。'},
        preview_context={'kind':'flat','land_surface_y':2,'padding':3,'surface':'grass'},
        design_revision=1,terrain={'选址':'完整基座须在同高承载面内，避让道路和建筑。'},
        design_notes=['供通用台地设施规划选择；不是阵列建筑成员。','装饰、灯光和水景为静态方块表现。'])
    m.box((0,0,0),(size[0]-1,1,size[2]-1),'stone_bricks')
    m.point('front','entrance',(size[0]//2,2,0),'基座前方站位',approach=(size[0]//2,2,1),direction='north')
    return m


def crystal_corner():
    m=fixture('MG-41-v01','星石台地角塔 · 小基座与尖顶标柱',(7,26,7),'魔法学院新制',['景观','边缘标识'])
    m.box((1,1,1),(5,1,5),'polished_andesite')
    m.box((2,2,2),(4,16,4),'calcite')
    for x,z in ((2,2),(4,2),(2,4),(4,4)):m.box((x,2,z),(x,17,z),'polished_andesite')
    for y in (6,12,16):m.box((2,y,2),(4,y,4),'smooth_stone')
    for y in range(7,11):m.set(3,y,2,'purple_terracotta');m.set(3,y,4,'purple_terracotta')
    for y,r in ((17,3),(18,2),(19,2),(20,1),(21,1)):
        m.box((3-r,y,3-r),(3+r,y,3+r),'purpur_block')
    m.set(3,22,3,'end_rod');m.set(3,23,3,'amethyst_block');m.set(3,24,3,'amethyst_cluster[facing=up]')
    m.meta['roof_min_y']=17
    return m


def crystal_court():
    m=fixture('MG-42-v01','星石庭院标景 · 环形矮座与四角灯',(9,11,9),'魔法学院新制',['景观','集会'])
    m.box((1,1,1),(7,1,7),'polished_andesite')
    m.box((2,2,2),(6,2,6),'smooth_quartz')
    m.box((3,3,3),(5,3,5),'purpur_block')
    m.box((4,4,4),(4,7,4),'amethyst_block');m.set(4,8,4,'amethyst_cluster[facing=up]')
    m.set(3,4,4,'amethyst_block');m.set(5,4,4,'amethyst_block')
    for x,z in ((1,1),(7,1),(1,7),(7,7)):
        m.set(x,2,z,'stone_brick_wall');m.set(x,3,z,'lantern')
    return m


def timber_corner():
    m=fixture('EU-25-v01','木构台地角亭 · 支柱挑檐与悬灯',(7,15,7),'欧式新制',['景观','照明'])
    for x,z in ((1,2),(5,2),(1,5),(5,5)):m.box((x,2,z),(x,8,z),'stripped_spruce_log')
    m.box((1,8,2),(5,8,5),'dark_oak_planks')
    m.set(3,7,3,'lantern[hanging=true]')
    for y,r in ((9,3),(10,2),(11,2),(12,1)):
        m.box((3-r,y,3-r),(3+r,y,3+r),'spruce_planks')
    m.set(3,13,3,'spruce_fence')
    m.meta['roof_min_y']=9
    return m


def stone_well():
    m=fixture('EU-26-v01','小庭石井 · 围井矮座与木架棚',(9,12,9),'欧式新制',['公共取水','景观'])
    m.box((2,2,2),(6,3,6),'stone_bricks')
    m.box((3,3,3),(5,3,5),'water[level=0]')
    for x,z in ((1,2),(7,2),(1,6),(7,6)):m.box((x,2,z),(x,7,z),'spruce_log')
    m.box((1,7,2),(7,7,6),'dark_oak_planks')
    for y,r in ((8,4),(9,3),(10,2)):
        m.box((4-r,y,2),(4+r,y,6),'spruce_planks')
    m.meta['roof_min_y']=8
    return m


BUILDERS={
    'MG-41-v01':('R02_arcane_reborn',crystal_corner),
    'MG-42-v01':('R02_arcane_reborn',crystal_court),
    'EU-25-v01':('R04_european_reborn',timber_corner),
    'EU-26-v01':('R04_european_reborn',stone_well),
}
