"""Four reference-led merchant homes, with distinct public/private plans."""
from .model import Model


def merchant(variant):
    plans = {
        1: ('前店后宅水庭商宅', (35, 23, 40), '两间前铺夹门庭，后部家庭长翼围合水庭，后翼东端上层睡房与西露台。'),
        2: ('转角双街商宅', (36, 23, 37), '西侧长铺与后侧家居成L形，东侧开放卸货小院，楼居压住转角。'),
        3: ('双庭分客商宅', (43, 23, 54), '前庭接待交易，中间横翼分开客院与后部家院，家居在最深处。'),
        4: ('紧凑两层商宅', (28, 23, 31), '底层铺面货存，上层家居与前露台；东侧独立外阶使家人不穿过柜台。')}
    if variant not in plans: raise ValueError(variant)
    name,size,note=plans[variant]
    m=Model(f'DS-05-v{variant:02d}',name,size,family='DS-05',civilization='沙漠',role='fill',terrain={
        '选址':'稳定平缓的贸易聚落地块，有洁净用水、食品与货物补给。',
        '地块':note,'接地':'外部街面与场坪脚底均为Y=2；二层脚底Y=9。'})
    m.meta.update(source=f'tools/structure_studio/studio/desert_fill_merchants.py:merchant({variant})',
        ground_plane=dict(y=2,note='无阶外部街面上边界Y=2，与Y=1场坪方块顶面齐平；室外上楼梯另行起步。'),
        preview_context=dict(kind='flat',land_surface_y=2,bed_y=-1,padding=3,surface='sand'),
        design_notes=[note,'参考图的深门窗、落地木棚、砂岩与局部赭墙、绿荫和织物；家庭与交易流线分开。'],
        differences=[note],roof_min_y=8,
        floors=[dict(name='商铺庭院与日常生活',y=1,max_y=7),dict(name='楼居与屋面露台',y=8,max_y=15)])
    w,_,d=size
    m.box((2,0,2),(w-3,1,d-3),'sandstone')
    m.box((2,2,2),(w-3,21,d-3),'air')
    m.box((3,1,3),(w-4,1,d-4),'smooth_sandstone')

    def room(key,title,a,b,*,f=1,roof=8,ochre=False):
        x0,z0=a;x1,z1=b
        m.box((x0,f,z0),(x1,roof,z1),'terracotta' if ochre else 'smooth_sandstone')
        m.box((x0+1,f+1,z0+1),(x1-1,roof-1,z1-1),'air')
        m.box((x0,f,z0),(x1,f,z1),'cut_sandstone')
        m.box((x0,roof,z0),(x1,roof,z1),'smooth_sandstone')
        for x in range(x0,x1+1):
            for z in (z0,z1):m.set(x,roof+1,z,'smooth_sandstone_slab[type=bottom]')
        for z in range(z0,z1+1):
            for x in (x0,x1):m.set(x,roof+1,z,'smooth_sandstone_slab[type=bottom]')
        for x in (x0,x1):
            for z in (z0,z1):m.box((x,f+1,z),(x,roof,z),'cut_sandstone')
        # Small deep shuttered windows, with an individual stone head.
        for x in range(x0+3,x1-1,5):
            m.box((x,f+3,z0),(x,f+4,z0),'oak_fence')
            m.set(x,f+5,z0,'chiseled_sandstone')
            m.set(x,f+2,z0-1,'sandstone_slab[type=top]')
            m.set(x-1,f+3,z0-1,'oak_trapdoor[facing=east,open=true]')
            m.set(x+1,f+3,z0-1,'oak_trapdoor[facing=west,open=true]')
        for z in range(z0+3,z1-1,5):
            m.box((x1,f+3,z),(x1,f+4,z),'oak_fence')
            m.set(x1+1,f+2,z,'sandstone_slab[type=top]')
        m.room(key,title,(x0+1,f+1,z0+1),(x1-1,roof-1,z1-1),title)
        m.set(x1-2,roof-1,z1-2,'lantern[hanging=true]')
        return (x0,z0,x1,z1,f)

    def opening(x,z,*,f=1,axis='x'):
        if axis=='x':
            m.box((x-1,f+1,z),(x+1,f+3,z),'air')
            m.box((x-2,f+4,z),(x+2,f+4,z),'cut_sandstone')
            for xx in (x-2,x+2):m.box((xx,f+1,z),(xx,f+3,z),'cut_sandstone')
        else:
            m.box((x,f+1,z-1),(x,f+3,z+1),'air')
            m.box((x,f+4,z-2),(x,f+4,z+2),'cut_sandstone')
            for zz in (z-2,z+2):m.box((x,f+1,zz),(x,f+3,zz),'cut_sandstone')

    def roofrail(x0,z0,x1,z1,y=9,gap=None):
        for x in range(x0,x1+1):
            for z in (z0,z1):
                if not gap or not gap(x,z):m.set(x,y,z,'smooth_sandstone')
        for z in range(z0,z1+1):
            for x in (x0,x1):
                if not gap or not gap(x,z):m.set(x,y,z,'smooth_sandstone')

    def shade(x0,z0,x1,z1,*,f=1,y=6,color='orange'):
        for x,z in ((x0,z0),(x1,z0),(x0,z1),(x1,z1)):
            m.box((x,f+1,z),(x,y,z),'stripped_dark_oak_log[axis=y]')
        for z in (z0,z1):m.box((x0,y,z),(x1,y,z),'stripped_dark_oak_log[axis=x]')
        for x in range(x0,x1+1):
            m.box((x,y,z0),(x,y,z1),'stripped_dark_oak_log[axis=z]' if (x-x0)%3==0 else 'dark_oak_planks')
            m.box((x,y+1,z0),(x,y+1,z1),('white' if (x-x0)%4==0 else color)+'_carpet')
        if f==1 and z1-z0<=3:
            for x in (x0+1,x1-1):
                m.set(x,2,z0+1,'barrel[facing=up]');m.set(x,3,z0+1,'potted_fern')
                m.set(x,y-1,z1,'lantern[hanging=true]')
        if f==8:
            m.box((x0+2,9,z1-1),(x1-2,9,z1-1),'acacia_slab[type=top]')
            m.set(x0+2,10,z1-1,'lantern')

    def shop(spec,key):
        x0,z0,x1,z1,f=spec
        for x in range(x0+2,x1-1):m.set(x,f+1,z0+3,'stripped_acacia_log[axis=x]')
        for x in range(x0+2,x1-1,3):
            m.set(x,f+2,z0+3,'potted_dead_bush' if x%2 else 'potted_fern')
        for x in (x0+2,x1-2):
            m.set(x,f+1,z1-2,'barrel[facing=north]');m.set(x,f+2,z1-2,'hay_block')
        m.set(x1-2,f+2,z0+3,'lantern')
        m.point(key,'work',(x0+3,f+1,z0+3),'交易柜台',approach=(x0+3,f+1,z0+2))

    def accounts(spec,key):
        x0,z0,x1,z1,f=spec
        m.set(x0+2,f+1,z0+2,'lectern[facing=south]')
        m.box((x1-3,f+1,z1-1),(x1-1,f+2,z1-1),'bookshelf')
        m.set(x1-1,f+1,z0+1,'barrel[facing=west]')
        m.point(key,'work',(x0+2,f+1,z0+2),'账房与会商记录',approach=(x0+2,f+1,z0+3))
        cx=(x0+x1)//2;cz=z1-3
        m.box((cx-1,f+1,cz),(cx+1,f+1,cz),'acacia_slab[type=top]')
        m.set(cx,f+2,cz,'lantern')
        for xx in (cx-1,cx+1):m.set(xx,f+1,cz-1,'acacia_stairs[facing=south]')
        m.box((x1-1,f+1,z0+2),(x1-1,f+2,z0+4),'barrel[facing=west]')
        m.box((x0+1,f,z1-3),(x0+2,f,z1-1),'orange_terracotta')

    def home(spec,key,*,beds=True):
        x0,z0,x1,z1,f=spec
        for i,block in enumerate(('smoker[facing=south]','water_cauldron[level=3]','barrel[facing=south]')):
            m.set(x0+2+i,f+1,z0+1,block)
        m.point(key+'_kitchen','work',(x0+2,f+1,z0+1),'家用灶台',approach=(x0+2,f+1,z0+2))
        m.box((x0+2,f+1,z1-2),(x0+4,f+1,z1-2),'acacia_slab[type=top]')
        m.set(x0+3,f+2,z1-2,'lantern')
        m.set(x0+3,f+1,z1-3,'acacia_stairs[facing=south]')
        m.box((x0+2,f+1,z0+2),(x0+4,f+1,z0+2),'air')
        m.box((x0+5,f+1,z0+1),(x0+6,f+1,z0+1),'acacia_planks')
        m.set(x0+5,f+2,z0+1,'flower_pot')
        # Broad homes have an actual separated sleeping end and furnished lounge.
        if x1-x0>=18:
            bx=x1-9
            m.box((bx,f+1,z0+1),(bx,f+5,z1-1),'smooth_sandstone')
            m.box((bx,f+1,z0+3),(bx,f+3,z0+4),'air')
            cx=(x0+7+bx)//2
            m.box((cx-2,f,z0+3),(cx+2,f,z1-1),'orange_terracotta')
            m.box((cx-1,f+1,z1-2),(cx+1,f+1,z1-2),'acacia_slab[type=top]')
            for xx in (cx-1,cx+1):m.set(xx,f+1,z1-3,'acacia_stairs[facing=south]')
            m.box((bx-3,f+1,z0+1),(bx-1,f+2,z0+1),'bookshelf')
        else:
            m.box((x0+1,f,z0+3),(x0+5,f,z1-1),'orange_terracotta')
        if beds:
            for i,x in enumerate((x1-2,x1-5)):
                m.bed(x,f+1,z1-2,color='orange' if i else 'white',facing='north')
                m.point(key+f'_bed{i}','bed',(x,f+1,z1-2),'家人床位',approach=(x-1,f+1,z1-2))
            m.set(x1-1,f+1,z0+2,'bookshelf')
        else:
            m.box((x1-2,f+1,z0+2),(x1-2,f+2,z0+4),'barrel[facing=west]')
            m.box((x1-6,f+1,z1-2),(x1-3,f+1,z1-2),'acacia_planks')
            m.set(x1-4,f+2,z1-2,'lantern')

    def bedchamber(spec,key):
        x0,z0,x1,z1,f=spec
        for i,x in enumerate((x0+2,x1-2)):
            m.bed(x,f+1,z1-2,color='white' if i else 'orange',facing='north')
            m.point(key+str(i),'bed',(x,f+1,z1-2),'楼居家人床位',approach=(x+1 if i==0 else x-1,f+1,z1-2))
        m.set(x0+2,f+1,z0+1,'barrel[facing=south]')
        m.set(x1-2,f+1,z0+1,'water_cauldron[level=3]')
        m.box((x0+1,f,z0+3),(x1-1,f,z1-1),'orange_terracotta')
        m.box((x0+4,f+1,z0+1),(x1-3,f+2,z0+1),'bookshelf')

    def stair(x,z,key):
        # Seven continuous risers from floor Y1 to upper roof Y8.
        for i in range(7):
            y=2+i;zz=z+i
            m.box((x,1,zz),(x+2,y-1,zz),'sandstone')
            for xx in range(x,x+3):m.set(xx,y,zz,'sandstone_stairs[facing=south]')
            for xx in (x-1,x+3):
                m.box((xx,1,zz),(xx,y,zz),'cut_sandstone')
                m.set(xx,y+1,zz,'acacia_fence[north=true,south=true]')
        m.box((x,1,z+7),(x+2,8,z+8),'cut_sandstone')
        m.point(key,'circulation',(x+1,9,z+7),'上层楼梯平台',look_at=[x-3,10,z+7])

    def tree(x,z):
        m.set(x,1,z,'dirt');m.box((x,2,z),(x,6,z),'jungle_log[axis=y]')
        m.box((x-2,6,z-1),(x+2,6,z+1),'jungle_leaves[persistent=true]')
        m.box((x-1,7,z-1),(x+1,7,z+1),'jungle_leaves[persistent=true]')

    def pool(x,z):
        m.box((x,1,z),(x+4,1,z+3),'cut_sandstone')
        m.box((x+1,1,z+1),(x+3,1,z+2),'water[level=0]')
        for xx in (x,x+4):m.box((xx,2,z),(xx,2,z+3),'smooth_sandstone_slab[type=bottom]')
        for zz in (z,z+3):m.box((x+1,2,zz),(x+3,2,zz),'smooth_sandstone_slab[type=bottom]')

    if variant==1:
        a=room('shop','沿街陈列铺',(4,6),(16,15));shop(a,'trade');opening(10,6);opening(10,15)
        a=room('accounts','前庭账房与储货',(21,6),(30,15),ochre=True);accounts(a,'ledger');opening(25,6);opening(25,15)
        a=room('living','后院厨房与家庭起居',(4,27),(30,36));home(a,'home',beds=False);opening(15,27)
        a=room('bedroom','楼居双床睡房',(21,27),(30,36),f=8,roof=15,ochre=True);bedchamber(a,'sleep');opening(21,31,f=8,axis='z')
        stair(5,19,'roof');m.box((5,8,26),(16,8,27),'smooth_sandstone')
        m.box((8,9,26),(16,9,26),'smooth_sandstone')
        roofrail(4,27,20,36,gap=lambda x,z:z==27 and 5<=x<=8 or x==20 and 30<=z<=32)
        shade(9,30,17,34,f=8,y=13,color='red');pool(21,20);tree(29,20)
        shade(5,3,15,5,color='orange');shade(22,3,29,5,color='red')
        for xx in (3,31):m.box((xx,2,16),(xx,3,26),'smooth_sandstone')
        m.point('terrace','circulation',(16,9,29),'后翼家用露台',look_at=[13,10,32])
        frontx=18
    elif variant==2:
        a=room('shop','西街长铺',(4,5),(14,19));shop(a,'trade');opening(9,5);opening(14,15,axis='z')
        a=room('accounts','转角账房',(4,19),(14,32),ochre=True);accounts(a,'ledger');opening(14,24,axis='z')
        a=room('living','后侧家庭长翼',(14,24),(31,32));home(a,'home');opening(23,24)
        opening(9,19);opening(14,28,axis='z')
        a=room('bedroom','转角楼居',(4,20),(14,32),f=8,roof=15,ochre=True);bedchamber(a,'sleep');opening(14,22,f=8,axis='z')
        stair(17,12,'roof');m.box((15,8,19),(19,8,24),'smooth_sandstone');m.box((15,1,19),(15,7,24),'cut_sandstone')
        m.box((19,9,20),(19,9,23),'acacia_fence[north=true,south=true]');m.set(15,9,19,'acacia_fence[east=true,west=true]')
        roofrail(14,24,31,32,gap=lambda x,z:z==24 and 16<=x<=20)
        shade(20,27,28,30,f=8,y=13,color='red');shade(5,2,13,4,color='orange')
        tree(29,17);pool(24,9)
        m.box((32,2,6),(32,3,15),'smooth_sandstone');m.box((32,2,19),(32,3,23),'smooth_sandstone')
        m.point('terrace','circulation',(23,9,26),'南翼露台',look_at=[25,10,29]);frontx=19
    elif variant==3:
        a=room('shop','前客庭铺面',(4,6),(17,16));shop(a,'trade');opening(10,6);opening(10,16)
        a=room('accounts','客庭账房与寄货',(25,6),(38,16),ochre=True);accounts(a,'ledger');opening(31,6);opening(31,16)
        a=room('meeting','分庭会商厅',(4,25),(17,33));accounts(a,'meeting');opening(10,25);opening(10,33)
        a=room('store','分庭货仓',(25,25),(38,33));shop(a,'store');opening(31,25);opening(31,33)
        a=room('home','后院家庭长翼',(4,43),(38,50));home(a,'home');opening(21,43)
        m.box((4,2,24),(17,3,24),'smooth_sandstone');m.box((25,2,24),(38,3,24),'smooth_sandstone')
        m.box((4,2,34),(17,3,34),'smooth_sandstone');m.box((25,2,34),(38,3,34),'smooth_sandstone')
        opening(10,24);opening(31,24);opening(10,34);opening(31,34)
        m.box((18,6,26),(24,6,31),'stripped_dark_oak_log[axis=x]')
        for x in (18,24):m.box((x,2,26),(x,5,26),'stripped_dark_oak_log[axis=y]')
        shade(5,3,16,5,color='orange');shade(26,3,37,5,color='red')
        shade(5,37,14,41,color='white');pool(27,37);tree(35,38);tree(6,20)
        for xx in (3,39):m.box((xx,2,17),(xx,3,42),'smooth_sandstone')
        m.point('rear_court','circulation',(21,2,39),'私家庭院',look_at=[29,3,38]);frontx=21
        m.meta['floors']=[dict(name='前客院与后家院',y=1,max_y=7)]
    else:
        a=room('shop','底层街铺',(4,7),(19,16),ochre=True);shop(a,'trade');opening(11,7)
        a=room('store','后侧账房与货存',(4,16),(19,26));accounts(a,'ledger');opening(19,21,axis='z');opening(11,16)
        a=room('home','楼上家庭起居',(4,16),(19,26),f=8,roof=15,ochre=True);home(a,'home');opening(11,16,f=8)
        stair(22,6,'roof');m.box((19,8,13),(24,8,15),'smooth_sandstone');m.box((24,1,13),(24,7,15),'cut_sandstone')
        m.box((20,9,15),(24,9,15),'acacia_fence[east=true,west=true]');m.box((24,9,13),(24,9,14),'acacia_fence[north=true,south=true]')
        roofrail(4,7,19,15,gap=lambda x,z:x==19 and z>=13 or z==15 and 10<=x<=12)
        shade(6,9,15,12,f=8,y=13,color='red');shade(5,4,18,6,color='orange')
        m.point('terrace','circulation',(11,9,14),'楼居前露台',look_at=[10,10,10]);tree(6,28);frontx=11
    m.point('front','entrance',(frontx,2,2),'街面主入口',facing='north')
    for x0,x1 in ((3,frontx-3),(frontx+3,w-4)):
        if x0<=x1:
            m.box((x0,2,2),(x1,2,2),'smooth_sandstone')
            for x in (x0,x1):m.set(x,3,2,'chiseled_sandstone')
    # Scattered courtyard planting and clay storage animate the merchant frontage.
    for x,z in ((w-4,d-5),(3,d-5)):
        if m.blocks.get((x,2,z),('minecraft:air',()))[0]=='minecraft:air':
            m.set(x,2,z,'flower_pot')
    m.meta['connections'].append(dict(kind='pedestrian',pos=[frontx,2,2],direction='north',clearance=[3,3],note='无阶衔接外部脚底Y2街面'))
    return m
