"""Four sand-and-glass workshops from the user's kiln-led reference sheet."""
from .model import Model


def _room(m, key, name, x0, z0, x1, z1, f=2, height=6):
    top = f + height
    m.box((x0, f-1, z0), (x1, top, z1), 'smooth_sandstone')
    m.box((x0+1, f, z0+1), (x1-1, top-1, z1-1), 'air')
    m.box((x0, f, z0), (x1, f, z0), 'cut_sandstone')
    for x in (x0, x1):
        for z in (z0, z1):
            m.box((x, f, z), (x, top, z), 'cut_sandstone')
    # Recessed glazed openings and short roof screens break the store blocks.
    m.box((x0+2,f+2,z0),(x0+4,f+3,z0),'cyan_stained_glass')
    m.box((x0,f+2,z0+3),(x0,f+3,z0+5),'light_blue_stained_glass')
    m.box((x0,top+1,z1),(x1,top+1,z1),'sandstone_slab[type=bottom]')
    for x in (x0,x1):
        m.box((x,top+1,z1-3),(x,top+1,z1),'sandstone_slab[type=bottom]')
    for x in (x0+1,x1-1):
        m.set(x,f+4,z0-1,'lantern[hanging=true]')
        m.set(x,f+5,z0-1,'dark_oak_fence')
        m.set(x,f+5,z0,'dark_oak_planks')
    m.room(key, name, (x0+1,f,z0+1), (x1-1,top-1,z1-1), name)


def _opening(m, x, z, f=2, axis='x'):
    if axis == 'x':
        m.box((x-1,f,z), (x+1,f+3,z), 'air')
        m.box((x-2,f+4,z), (x+2,f+4,z), 'stripped_acacia_log[axis=x]')
    else:
        m.box((x,f,z-1), (x,f+3,z+1), 'air')
        m.box((x,f+4,z-2), (x,f+4,z+2), 'stripped_acacia_log[axis=z]')


def _shade(m, x0, z0, x1, z1, f=2, color=None):
    y = f+5
    for x in (x0, x1):
        for z in (z0, z1):
            m.box((x,f,z), (x,y-1,z), 'stripped_dark_oak_log[axis=y]')
    for z in (z0,z1):
        m.box((x0,y-1,z), (x1,y-1,z), 'stripped_dark_oak_log[axis=x]')
    for x in (x0,x1):
        m.box((x,y-1,z0), (x,y-1,z1), 'stripped_dark_oak_log[axis=z]')
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            material = (color if (z-z0)%4 else 'white')+'_wool' if color else ('spruce_slab[type=bottom]' if (x-x0)%4==0 else 'acacia_slab[type=bottom]')
            m.set(x,y,z,material)
    m.set(x0+1,y-2,z0,'lantern[hanging=true]')


def _rack(m,key,x,z,f=2,w=5):
    m.box((x,f,z),(x+w-1,f,z),'acacia_planks')
    for i in range(w):
        color = ('cyan','light_blue','green','yellow','orange')[i%5]
        m.set(x+i,f+1,z,color+'_stained_glass')
        if i%2 == 0:
            m.set(x+i,f+2,z,'flower_pot')
    m.point(key,'work',(x+w//2,f+1,z),'成品冷却与玻璃陈列',approach=(x+w//2,f,z-1))


def _raw(m,x,z,f=2):
    m.box((x,f,z),(x+3,f,z+2),'sand')
    for xx in (x-1,x+4):
        m.box((xx,f,z),(xx,f+1,z+2),'cut_sandstone')
    m.box((x,f,z+3),(x+3,f+1,z+3),'cut_sandstone')
    m.set(x+1,f+1,z+1,'sand')
    m.point('raw','work',(x+1,f,z),'原砂与配料槽',approach=(x+1,f,z-1))
    m.set(x+5,f,z+1,'barrel[facing=up]')
    m.point('fuel','work',(x+5,f,z+1),'燃料及包材储备',approach=(x+5,f,z))
    m.set(x+5,f+1,z+1,'barrel[facing=up]')


def _cold(m,x,z,f=2):
    m.box((x,f,z),(x+4,f,z),'acacia_planks')
    m.set(x,f,z,'crafting_table')
    m.set(x+2,f+1,z,'flower_pot')
    m.set(x+4,f,z,'smithing_table')
    m.point('mould','work',(x+2,f,z),'模具与冷加工台',approach=(x+2,f,z-1))
    m.set(x,f,z+3,'water_cauldron[level=3]')
    m.set(x+3,f,z+3,'anvil[facing=north]')
    m.point('tools','work',(x+3,f,z+3),'玻璃修整器具',approach=(x+3,f,z+2))
    m.set(x+4,f,z+3,'barrel[facing=up]')
    m.set(x+4,f+1,z+3,'lantern')


def _rest(m,x,z,f=2):
    m.box((x,f,z),(x+2,f,z),'acacia_stairs[facing=south]')
    m.set(x+4,f,z,'water_cauldron[level=3]')
    m.point('rest','work',(x+4,f,z),'离窑饮水与歇工',approach=(x+4,f,z+1))


def _kiln(m,x,z,f=2):
    # A broad fire chamber steps into a tall hollow flue; the stack is the massing.
    m.box((x,f,z),(x+6,f+5,z+6),'terracotta')
    for xx in (x,x+6):
        m.box((xx,f,z),(xx,f+5,z+6),'cut_sandstone')
    m.box((x,f+5,z),(x+6,f+5,z+6),'cut_sandstone')
    m.box((x+1,f+6,z+1),(x+5,f+9,z+5),'smooth_sandstone')
    m.box((x+2,f+6,z+1),(x+4,f+8,z+1),'terracotta')
    m.box((x+2,f+10,z+2),(x+4,f+15,z+4),'terracotta')
    for y in (f+10,f+14):
        m.box((x+2,y,z+2),(x+4,y,z+4),'cut_sandstone')
    m.box((x+1,f+2,z+1),(x+5,f+4,z+5),'air')
    m.box((x+3,f+4,z+3),(x+3,f+16,z+3),'air')
    for xx in range(x+2,x+5):
        m.set(xx,f,z,'furnace[facing=north,lit=true]')
        m.set(xx,f+1,z,'furnace[facing=north,lit=true]')
    m.box((x+2,f+2,z),(x+4,f+2,z),'sandstone_slab[type=top]')
    for xx in range(x+2,x+5):
        for zz in range(z+2,z+5):
            if (xx,zz)!=(x+3,z+3):
                m.set(xx,f+16,zz,'stone_brick_wall')
    m.set(x+3,f+1,z+7,'lever[face=wall,facing=south,powered=false]')
    m.point('kiln','work',(x+3,f,z),'窑炉装料与热加工',approach=(x+3,f,z-1))
    m.point('kiln_service','work',(x+3,f+1,z+7),'窑后检修口',approach=(x+3,f,z+8))
    for xx in (x+1,x+5):
        m.set(xx,f+2,z-1,'iron_bars')
        m.set(xx,f+3,z-1,'lantern[hanging=true]')
        m.set(xx,f+4,z-1,'dark_oak_fence')
        m.set(xx,f+4,z,'dark_oak_planks')
    m.room('kiln','窑炉热加工与后维护',(x,f,z-1),(x+6,f+5,z+8),'窑前操作和独立窑后检修')


def _rail(m,x0,z0,x1,z1,f=2):
    state = 'dark_oak_fence[north=true,south=true]' if x0==x1 else 'dark_oak_fence[east=true,west=true]'
    m.box((x0,f,z0),(x1,f,z1),state)


def glassworks(variant):
    if variant not in (1,2,3,4):
        raise ValueError('Glassworks variant must be 1..4')
    sizes = {1:(33,22,35),2:(43,24,43),3:(37,26,39),4:(41,28,45)}
    names = {1:'小型窑作坊',2:'院内窑坊',3:'临街展示工坊',4:'分台工坊'}
    m=Model(f'DS-07-v{variant:02d}',names[variant],sizes[variant],family='DS-07',
            civilization='沙漠',role='fill',terrain={
                '选址':'有原砂、燃料供给及货物接驳的工商业地块',
                '地基':'完整支撑地基；门外地面上边界为局部 y=2，台地通过建筑内阶梯连接',
                '配套':'原砂、燃料、窑前热加工、冷加工、成品陈列和独立窑后维护通路'})
    m.meta.update(source=f'tools/structure_studio/studio/desert_fill_glassworks.py:glassworks({variant})',
                  ground_plane=dict(y=2,note='主入口门外砂岩场坪上边界，玩家脚底 y=2；内部高台不改变外部接地线'),
                  roof_min_y=7,floors=[dict(name='工作地坪',y=2,max_y=6)],
                  design_notes=['参考用户图3：退台土色砖窑与真实贯通烟道决定轮廓，低木棚分隔热冷加工。',
                                '原版熔炉、器具与空心烟道为静态工艺表达，不声明热工配方、烟气或自动生产机制。'],
                  differences=[names[variant]+'：独立平面、窑位与工作动线。'],
                  preview_context=dict(kind='flat',land_surface_y=2,bed_y=-2,padding=4,surface='sand'))
    w,h,d=m.size
    m.box((1,0,1),(w-2,1,d-2),'sandstone')
    m.box((1,2,1),(w-2,h-1,d-2),'air')
    for x in range(1,w-1):
        for z in range(1,d-1):
            if (x+z)%7==0:
                m.set(x,1,z,'cut_sandstone')
    if variant==1:
        _kiln(m,17,13)
        _shade(m,4,9,14,21)
        _cold(m,6,15)
        _rack(m,'sales',5,7,w=7)
        _rail(m,4,5,12,5)
        m.set(4,2,7,'decorated_pot')
        _room(m,'raw_room','后侧原料库',4,24,15,31)
        _opening(m,10,24)
        _raw(m,6,27)
        _shade(m,25,21,30,30)
        _rest(m,25,24)
        m.room('cold','低棚冷加工',(5,2,10),(13,6,20),'模具、整修与水盆')
        m.point('front','entrance',(17,2,3),'小窑铺前入口',facing='north')
    elif variant==2:
        _kiln(m,18,24)
        _shade(m,5,23,15,35)
        _cold(m,7,28)
        _shade(m,28,23,38,35)
        _rack(m,'cooling',30,29,w=6)
        _rest(m,30,33)
        _room(m,'raw_room','院内原料库',4,6,15,17)
        _opening(m,15,12,axis='z')
        _raw(m,6,9)
        _room(m,'sales_room','成品交付铺',28,6,39,17)
        _opening(m,28,12,axis='z')
        _rack(m,'sales',30,11,w=6)
        _shade(m,5,18,14,21,color='red')
        _shade(m,29,18,38,21,color='orange')
        m.box((3,2,4),(17,3,4),'smooth_sandstone')
        m.box((25,2,4),(40,3,4),'smooth_sandstone')
        for x in (17,25):
            m.box((x,2,4),(x,5,4),'cut_sandstone')
        m.room('court','工作内院',(16,2,6),(27,6,22),'原料进入、窑前操作与冷加工分流')
        m.point('front','entrance',(21,2,3),'工作院门',facing='north')
    elif variant==3:
        _room(m,'sales_room','沿街成品展示铺',4,6,22,17)
        _opening(m,13,6)
        m.box((16,4,6),(20,5,6),'cyan_stained_glass')
        _opening(m,22,14,axis='z')
        _rack(m,'sales',6,11,w=8)
        m.set(18,2,11,'lectern[facing=north]')
        m.point('account','work',(18,2,11),'接待与交易账目',approach=(18,2,10))
        _shade(m,4,3,22,5,color='red')
        _room(m,'gallery_room','楼上精品陈列与歇工',4,6,22,17,f=9)
        _opening(m,22,14,f=9,axis='z')
        _rack(m,'gallery',6,10,f=9,w=8)
        _rest(m,15,10,f=9)
        m.bed(7,9,15,color='cyan',facing='east')
        m.point('bed','bed',(7,9,15),'夜间看窑休息床',approach=(7,9,14))
        for x0,x1 in ((6,10),(16,20)):
            m.box((x0,11,6),(x1,13,6),'cyan_stained_glass')
        m.box((23,1,13),(28,8,17),'smooth_sandstone')
        for i in range(7):
            m.box((24,2,6+i),(27,2+i,6+i),'sandstone')
            m.box((24,2+i,6+i),(27,2+i,6+i),'sandstone_stairs[facing=south]')
            m.set(28,3+i,6+i,'dark_oak_fence')
        _rail(m,28,13,28,17,f=9)
        _rail(m,23,17,27,17,f=9)
        m.point('upper','circulation',(25,9,15),'楼梯上层平台')
        _raw(m,4,21)
        _kiln(m,10,26)
        _shade(m,21,22,32,34)
        _cold(m,23,26)
        _rack(m,'cooling',23,33,w=7)
        m.meta.update(roof_min_y=15,floors=[dict(name='临街铺与后院生产',y=2,max_y=7),dict(name='楼上展厅',y=9,max_y=14)])
        m.point('front','entrance',(13,2,2),'沿街展示入口',facing='north')
        m.point('cargo','entrance',(34,2,23),'侧巷原料入口',facing='east')
    else:
        # Two real work terraces: storage/sales below, the open kiln yard above.
        m.box((3,2,24),(37,5,41),'smooth_sandstone')
        _room(m,'raw_room','低台原料库',4,7,15,17)
        _opening(m,15,12,axis='z')
        _raw(m,6,10)
        _room(m,'sales_room','低台成品交付',26,7,37,17)
        _opening(m,26,12,axis='z')
        _rack(m,'sales',28,11,w=7)
        _shade(m,27,18,36,22,color='red')
        for i in range(4):
            m.box((18,2,20+i),(23,2+i,20+i),'sandstone')
            m.box((18,2+i,20+i),(23,2+i,20+i),'sandstone_stairs[facing=south]')
            for x in (17,24):
                m.box((x,2,20+i),(x,2+i,20+i),'smooth_sandstone')
                m.set(x,3+i,20+i,'dark_oak_fence')
        _kiln(m,8,28,f=6)
        _shade(m,21,26,35,38,f=6)
        _cold(m,24,30,f=6)
        _rack(m,'cooling',24,37,f=6,w=7)
        _rest(m,27,25,f=6)
        for x0,x1 in ((3,17),(24,37)):
            _rail(m,x0,24,x1,24,f=6)
        _rail(m,3,24,3,41,f=6)
        _rail(m,37,24,37,41,f=6)
        _rail(m,3,41,37,41,f=6)
        m.room('upper_yard','上台热冷加工院',(4,6,25),(36,10,40),'窑前热加工、后检修、冷却和整修')
        m.point('upper','circulation',(20,6,26),'分台阶梯上口')
        m.point('front','entrance',(20,2,3),'下台进料入口',facing='north')
        m.meta.update(roof_min_y=11,floors=[dict(name='低台存料与交付',y=2,max_y=7),dict(name='上台加工',y=6,max_y=10)])
    m.meta['connections']=[dict(kind='pedestrian',pos=list(p['pos']),direction=p['facing'],clearance=[3,4],
                               note='作者明确的外部地面线 y=2；与外部街道平接') for p in m.meta['points'] if p['kind']=='entrance']
    return m
