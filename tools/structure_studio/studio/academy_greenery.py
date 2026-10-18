"""Small independent academy greenery. No paved plot or serialized clearing air."""
from functools import partial
from .model import Model

SOURCE = 'tools/structure_studio/studio/academy_greenery.py'
CATALOG_DIR = 'R02_arcane_reborn'


def base(number, name, size, kind, ground=0):
    m = Model(f'MG-{number}-v01', name, size, civilization='魔法学院新制', role='structure')
    m.meta.update(source=SOURCE, asset_tags=['landscape','infrastructure'],
        placement_consumer='infrastructure', function_terms=['树木观赏'] if kind=='tree' else ['花草观赏'],
        ground_plane={'y':ground,'note':'外部地表上边界；根部贴地，无整块台基。' if not ground else 'Y=0 的局部种植土嵌入原地表，外部地表上边界为 Y=1。'},
        placement_anchor={'pos':[size[0]//2,ground,size[2]//2],'note':'植物定位点，不是玩家入口。'},
        preview_context={'kind':'flat','land_surface_y':ground,'padding':3,'surface':'grass'},
        greenery={'kind':kind,'usage':'SMALL_INDEPENDENT','groundMode':'SURFACE_ROOT' if ground==0 else 'EMBEDDED_SOIL',
                  'rotations':[0,90,180,270],'style':'arcane_academy','clearanceBlocks':1},
        terrain={'选址':'避让建筑、道路及其他树冠；根部须有实心承载。' if not ground else '局部种植土可替换原地表；植物下方必须保留模板土块。'},
        design_notes=['独立绿化构件，由程序选位；不作建筑阵列成员，不自动生成台地。',
                      '未写入位置保持原状，无显式空气清场；调用方须检查完整放置净空。',
                      '白桦枝干、尖塔状或分瓣树冠、淡粉花叶与少量紫晶体现学院风格。'])
    return m


def branch(m, points):
    for a,b in zip(points,points[1:]):
        p=list(a)
        m.set(*p,'birch_log[axis=y]')
        # Face-connected stepped branches, including the horizontal elbow blocks.
        for axis in (1,0,2):
            while p[axis]!=b[axis]:
                p[axis]+=1 if b[axis]>p[axis] else -1
                m.set(*p,f'birch_log[axis={"xyz"[axis]}]')


def crown(m, center, radii, leaf='birch_leaves'):
    cx,cy,cz=center;rx,ry,rz=radii
    for x in range(max(0,cx-rx),min(m.size[0],cx+rx+1)):
        for y in range(max(0,cy-ry),min(m.size[1],cy+ry+1)):
            for z in range(max(0,cz-rz),min(m.size[2],cz+rz+1)):
                if ((x-cx)/(rx+.25))**2+((y-cy)/(ry+.25))**2+((z-cz)/(rz+.25))**2>1:continue
                if (x,y,z) not in m.blocks:m.set(x,y,z,f'{leaf}[persistent=true,distance=1]')


TREES = {
 50:('银枝幼树 · 小圆冠',(5,9,5), [[(2,0,2),(2,5,2)],[(2,4,2),(1,5,2)],[(2,4,2),(3,5,3)]], [((2,6,2),(2,2,2),'birch_leaves')]),
 51:('窄冠银桦 · 双层尖冠',(5,13,5), [[(2,0,2),(2,10,2)],[(2,6,2),(1,7,2)],[(2,8,2),(3,9,2)]], [((2,7,2),(2,3,2),'birch_leaves'),((2,10,2),(1,2,1),'birch_leaves')]),
 52:('偏冠银桦 · 伸枝庭树',(7,11,7), [[(3,0,3),(3,4,3),(2,6,3),(1,7,3)],[(3,4,3),(4,6,4)]], [((2,7,3),(2,2,3),'birch_leaves'),((4,8,4),(2,2,2),'birch_leaves')]),
 53:('分叉银桦 · 双瓣树冠',(9,14,7), [[(4,0,3),(4,5,3),(2,8,3),(2,10,3)],[(4,5,3),(6,7,3),(6,9,3)]], [((2,10,3),(2,3,3),'birch_leaves'),((6,9,3),(2,3,2),'flowering_azalea_leaves')]),
 54:('星花小树 · 粉白花冠',(7,10,7), [[(3,0,3),(3,5,3)],[(3,3,3),(1,5,3)],[(3,4,3),(5,6,4)]], [((2,6,3),(2,2,3),'cherry_leaves'),((4,7,3),(2,2,2),'cherry_leaves')]),
 55:('星花庭树 · 高低三瓣',(9,13,9), [[(4,0,4),(4,6,4),(3,9,4)],[(4,5,4),(6,7,5)],[(4,4,4),(2,6,2)]], [((3,10,4),(2,2,3),'cherry_leaves'),((6,8,5),(2,2,3),'cherry_leaves'),((2,7,2),(2,2,2),'flowering_azalea_leaves')]),
}


def tree(number):
    name,size,branches,crowns=TREES[number];m=base(number,name,size,'tree')
    for points in branches:branch(m,points)
    for center,radii,leaf in crowns:crown(m,center,radii,leaf)
    return m


def shrub(number):
    if number==56:
        m=base(number,'星花矮灌木 · 单簇',(3,2,3),'shrub')
        crown(m,(1,0,1),(1,1,1),'flowering_azalea_leaves')
    elif number==57:
        m=base(number,'星花灌木 · 高低双簇',(5,3,3),'shrub')
        crown(m,(1,1,1),(1,1,1),'flowering_azalea_leaves');crown(m,(3,0,1),(1,1,1),'azalea_leaves')
    else:
        m=base(number,'银叶绿篱 · 短段',(5,2,2),'hedge')
        m.box((0,0,0),(4,0,1),'azalea_leaves[persistent=true,distance=1]')
        for x in range(1,4):m.set(x,1,0,'flowering_azalea_leaves[persistent=true,distance=1]')
    return m


def flowers(number):
    size=(3,3,3) if number==59 else (5,3,3)
    m=base(number,'紫白星花丛 · 散点' if number==59 else '紫白星花带 · 短段',size,'flowers',ground=1)
    places=[(1,1,'allium'),(0,1,'azure_bluet'),(2,2,'allium'),(1,0,'white_tulip')] if number==59 else [
        (0,1,'allium'),(1,0,'white_tulip'),(2,1,'azure_bluet'),(3,1,'allium'),(4,2,'white_tulip')]
    for x,z,flower in places:m.set(x,0,z,'dirt');m.set(x,1,z,flower)
    return m


def planter():
    m=base(61,'白石星花箱 · 紫晶端饰',(5,4,3),'planter')
    m.box((0,0,0),(4,0,2),'smooth_quartz')
    for x in range(5):
        m.set(x,1,0,'quartz_slab[type=bottom]');m.set(x,1,2,'quartz_slab[type=bottom]')
    for x in (0,4):m.set(x,1,1,'amethyst_block');m.set(x,2,1,'amethyst_cluster[facing=up]')
    for x,flower in ((1,'allium'),(2,'white_tulip'),(3,'allium')):m.set(x,1,1,'dirt');m.set(x,2,1,flower)
    return m


def low_planter(number):
    wood=number==62
    m=base(number,'木质宽花槽 · 低边花境' if wood else '石砖宽花坛 · 低边混植',(7,3,5),'planter',ground=1)
    edge='dark_oak_slab[type=bottom]' if wood else 'stone_brick_slab[type=bottom]'
    for x in range(7):
        for z in range(5):
            if x in (0,6) or z in (0,4):m.set(x,1,z,edge)
            else:m.set(x,0,z,'dirt')
    for x,z,flower in ((1,1,'white_tulip'),(3,1,'allium'),(5,1,'azure_bluet'),(2,3,'allium'),(4,3,'white_tulip')):
        m.set(x,1,z,flower)
    return m


BUILDERS={f'MG-{i}-v01':partial(tree,i) for i in TREES}
BUILDERS.update({f'MG-{i}-v01':partial(shrub,i) for i in (56,57,58)})
BUILDERS.update({f'MG-{i}-v01':partial(flowers,i) for i in (59,60)})
BUILDERS['MG-61-v01']=planter
BUILDERS.update({f'MG-{i}-v01':partial(low_planter,i) for i in (62,63)})


def export_pool():
    """Author-side selection data, not a runtime installation or placement algorithm."""
    from .server import CATALOG
    from .model import read_structure, sha256, write_json
    root=CATALOG/CATALOG_DIR
    assets=[]
    for ident,build in BUILDERS.items():
        m=build();folder=root/'models'/ident;data=read_structure(folder/'structure.nbt')
        leaves=[b['pos'] for b in data['blocks'] if data['palette'][b['state']]['name'].endswith('_leaves')]
        canopy={'min':[min(p[i] for p in leaves) for i in range(3)],'max':[max(p[i] for p in leaves) for i in range(3)]} if leaves else None
        assets.append(dict(assetId='studio:'+ident.lower(),kind=m.meta['greenery']['kind'],
            sizeXYZ=list(m.size),groundPlaneY=m.meta['ground_plane']['y'],
            anchorXYZ=m.meta['placement_anchor']['pos'],groundMode=m.meta['greenery']['groundMode'],
            canopyBounds=canopy,rotations=[0,90,180,270],clearanceBlocks=1,
            nbtSha256=sha256(folder/'structure.nbt'),authorSha256=sha256(folder/'author.json')))
    def weighted(values):return [dict(assetId=f'studio:mg-{i}-v01',weight=w) for i,w in values]
    pools={
        'street_trees':weighted([(50,3),(51,3),(52,2),(54,1)]),
        'courtyard_trees':weighted([(52,2),(53,2),(54,3),(55,1)]),
        'outer_trees':weighted([(50,1),(51,2),(52,2),(53,3),(54,1),(55,1)]),
        'low_shrubs':weighted([(56,3),(57,2)]),
        'hedge_segments':weighted([(58,1)]),
        'flower_patches':weighted([(59,2),(60,1)]),
        'planters':weighted([(61,1),(62,4),(63,4)]),
    }
    write_json(root/'greenery-pools.json',dict(schema='studio.greenery-pools.v1',
        scope='author_selection_only',style='arcane_academy',assets=assets,pools=pools,
        notes=['权重为可编辑建议，AI 可选择子池或指定单项；尺寸与落地约束仍须满足。',
               '树冠净空按完整模板和实际树冠复核；未写空气不能代替清障。',
               '花丛土层按 groundPlaneY=1 嵌入地表；其余贴地。未自动接入工作台或正式 MC 生成器。']))


if __name__=='__main__':export_pool()
