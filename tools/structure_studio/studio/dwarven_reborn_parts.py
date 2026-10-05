"""New dwarven masonry language: faceted vaults, copper straps and deep portals."""
from .model import Model

CATALOG_DIR='R03_dwarven_reborn'
STONE='deepslate_bricks'
TRIM='polished_andesite'
METAL='waxed_cut_copper'


def base(n,name,w,h,d,*,role='key',tags=(),terms=()):
    m=Model(f'DV-{n:02d}-v01',name,(w,h,d),family=f'DV-{n:02d}',civilization='矮人新制',role=role,
        terrain={'选址':'完整稳定的岩台或城镇承载地面，基础须连续落地；外路脚底与本地 Y=2 相接。',
                 '边界':'岩壁、阶台与支墩由本模型自带；不依赖洞穴、山体贴合或特定生物群系。背后接山时须另行处理外壳与山体交接。',
                 '供给':'居住与工作空间需真实饮水、食物和燃料来源；炉火、起重器和交易设施仅为静态表达。'})
    m.box((0,0,0),(w-1,1,d-1),'stone_bricks')
    m.box((0,2,0),(w-1,h-1,d-1),'air')
    m.box((1,1,1),(w-2,1,d-2),'polished_andesite')
    m.meta.update(source='tools/structure_studio/studio/dwarven_reborn.py:BUILDERS',asset_tags=list(tags),function_terms=list(terms),
      ground_plane={'y':2,'note':'外围路面脚底为 Y=2；室内高差均通过实体楼梯连接。'},
      preview_context={'kind':'flat','land_surface_y':2,'padding':3,'surface':'grass'},
      floors=[{'name':'地坪与功能平面','y':1,'max_y':6}],roof_min_y=10,
      design_notes=['2026-10-05 全新独立建模；以厚重石构、深凹门廊、几何角拱和铜铁节点构成矮人风格。',
                    '文化核心依据议政、锻造、矿业组织与祖先纪念的独立空间设计，未沿用旧矮人布局。'])
    return m


def slab_floor(m,a,b,mat='polished_andesite'):
    m.box(a,b,mat)


def room_shell(m,x0,z0,x1,z1,*,floor=1,wall=7,roof=True,material=STONE):
    m.box((x0,1,z0),(x1,floor,z1),'stone_bricks')
    m.box((x0,floor+1,z0),(x1,floor+wall,z1),material)
    m.box((x0+1,floor+1,z0+1),(x1-1,floor+wall,z1-1),'air')
    m.box((x0+1,floor,z0+1),(x1-1,floor,z1-1),'polished_andesite')
    for x in (x0,x1):
        for z in (z0,z1):
            m.box((x,floor+1,z),(x,floor+wall,z),TRIM)
    for y in (floor+2,floor+wall-1):
        for z in (z0,z1):m.box((x0,y,z),(x1,y,z),METAL)
    if roof:vault(m,x0,z0,x1,z1,floor+wall+1)


def vault(m,x0,z0,x1,z1,y):
    """Angular barrel vault with closed gables and proud copper ribs."""
    mid=(x0+x1)//2
    for x in range(x0,x1+1):
        rise=min(x-x0,x1-x)//2
        yy=y+rise
        m.box((x,y,z0),(x,yy,z0),STONE)
        m.box((x,y,z1),(x,yy,z1),STONE)
        m.box((x,yy,z0),(x,yy,z1),'deepslate_tiles')
        for z in sorted({z0,z1,*range(z0+4,z1,7)}):m.set(x,yy+1,z,METAL)
    m.box((mid,y+(x1-x0)//4+1,z0),(mid,y+(x1-x0)//4+1,z1),'waxed_cut_copper_slab')


def portal(m,cx,z,*,floor=1,width=5,height=7,depth=2):
    r=width//2
    for dz in range(depth):
        for x in (cx-r-1,cx+r+1):
            m.box((x,floor+1,z+dz),(x,floor+height,z+dz),TRIM if dz==0 else STONE)
        m.box((cx-r,floor+height,z+dz),(cx+r,floor+height+1,z+dz),TRIM)
        m.box((cx-r,floor+1,z+dz),(cx+r,floor+height-1,z+dz),'air')
        for x,face in ((cx-r,'east'),(cx+r,'west')):m.set(x,floor+height-1,z+dz,f'polished_andesite_stairs[facing={face},half=top]')
    for x in (cx-r-2,cx+r+2):
        m.set(x,floor+4,z,'chiseled_deepslate')
        m.set(x,floor+5,z,'lantern')
    m.box((cx-r-1,floor+height+2,z),(cx+r+1,floor+height+2,z),METAL)


def stairs(m,x0,x1,z0,steps,*,base_y=2):
    for s in range(steps):
        z=z0+s;y=base_y+s
        if y>1:m.box((x0,1,z),(x1,y-1,z),'stone_bricks')
        m.box((x0,y,z),(x1,y,z),'polished_andesite_stairs[facing=south]')


def entrance(m,x,z=0):
    m.point('street','entrance',(x,2,z),'外部道路接口',facing='north')


def door(m,x,z,floor=1,side='north',key=None):
    m.box((x,floor+1,z),(x,floor+3,z),'air')
    m.door(x,floor+1,z,wood='dark_oak',facing=side)
    if side=='north':
        if floor>1:stairs(m,x,x,z-(floor-1),floor-1)
        p=(x,floor+1,z+1)
    elif side=='south':p=(x,floor+1,z-1)
    elif side=='east':p=(x-1,floor+1,z)
    else:p=(x+1,floor+1,z)
    if key:m.point(key,'circulation',p,'室内出入口')


def buttress(m,x,z,y,*,height=10):
    m.box((x,2,z),(x+2,height,z+2),STONE)
    for yy in (3,height-2):m.box((x,yy,z),(x+2,yy,z+2),METAL)
    m.box((x,height+1,z),(x+2,height+1,z+2),'polished_andesite_slab')


def brazier(m,x,y,z):
    m.box((x-1,y,z-1),(x+1,y,z+1),'polished_blackstone_bricks')
    m.set(x,y+1,z,'campfire[lit=true,signal_fire=false]')
    for dx,dz in ((-1,0),(1,0),(0,-1),(0,1)):m.set(x+dx,y+1,z+dz,'iron_bars')


def table(m,x,z,w=5,d=2,y=2):
    for xx in (x,x+w-1):
        for zz in (z,z+d-1):m.set(xx,y,zz,'dark_oak_fence')
    m.box((x,y+1,z),(x+w-1,y+1,z+d-1),'dark_oak_slab[type=top]')
    for xx in range(x,x+w,2):
        m.set(xx,y,z-1,'dark_oak_stairs[facing=south]')
        m.set(xx,y,z+d,'dark_oak_stairs[facing=north]')


def chest(m,x,y,z,face='north'):
    m.set(x,y,z,f'barrel[facing={face}]')


def supplies(m,x,z,y=2):
    for dx in (0,2):
        for yy in (y,y+1):chest(m,x+dx,yy,z)
    m.set(x+1,y,z,'crafting_table')


def bed(m,x,z,*,y=2,key='bed'):
    m.bed(x,y,z,'brown','north')
    m.point(key,'rest',(x,y,z),'床位',approach=(x+1,y,z))


def wash(m,x,z,*,y=2,key='wash'):
    m.set(x,y,z,'water_cauldron[level=3]')
    m.point(key,'service',(x,y,z),'盥洗用水',approach=(x,y,z-1))


def rune(m,cx,y,z):
    for dx,dy in ((0,0),(-1,1),(1,1),(-2,2),(2,2),(-1,3),(1,3),(0,4)):
        m.set(cx+dx,y+dy,z,METAL)


def face_statue(m,cx,z,*,base_y=2,scale=1):
    """Freestanding stylized ancestor bust: brow, glowing eyes and stepped beard."""
    m.box((cx-3,base_y,z),(cx+3,base_y+1,z+4),'polished_deepslate')
    m.box((cx-2,base_y+2,z+1),(cx+2,base_y+7,z+3),'stone_bricks')
    m.box((cx-3,base_y+7,z),(cx+3,base_y+8,z+4),METAL)
    for x in (cx-2,cx+2):m.set(x,base_y+6,z,'ochre_froglight')
    m.box((cx,base_y+4,z-1),(cx,base_y+6,z),'polished_andesite')
    for i in range(3):m.box((cx-2+i,base_y+2-i,z),(cx+2-i,base_y+3-i,z),'cobbled_deepslate')
    m.box((cx-2,base_y+9,z+1),(cx+2,base_y+9,z+3),'waxed_cut_copper_slab')
