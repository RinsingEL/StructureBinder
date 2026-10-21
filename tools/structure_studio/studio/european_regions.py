"""Regional working buildings and explicit shoreline/slope variants."""
from functools import partial
from copy import deepcopy
from .model import Model
from .european_village import (base,room_shell,windows,entry,roof,cross_roof,
    bed,station,seat,hearth,light,awning,cottage,family,farmhouse)

# These IDs continue after EU-45. Names describe actual room programs, not biomes.
SPECS={
46:('独立谷仓',11,15,('粮食仓储',),'barn'),
47:('村口小粮磨',13,15,('粮食加工',),'mill'),
48:('小型烘焙屋',11,13,('食品制作','零售'),'bake'),
49:('独立粮食晒场',13,13,('粮食晾晒',),'yard'),
50:('林务小屋',9,13,('林务管理','家庭居住'),'forester'),
51:('木料堆场',13,15,('木料仓储',),'timber'),
52:('炭烧棚',13,15,('木炭制作',),'charcoal'),
53:('小渡口候船棚',11,13,('水路客运',),'ferry'),
54:('桥头收费屋',9,11,('货运查验',),'toll'),
55:('岸边仓房',11,15,('货物仓储','水陆转运'),'warehouse'),
56:('分段木栈桥',7,13,('步行通行','装卸'),'pier'),
57:('山口旅舍',13,17,('旅客住宿','餐饮'),'hostel'),
58:('山口小关卡',13,15,('防御','驻守'),'checkpoint'),
59:('矿口作业棚',11,13,('矿料转运',),'mine'),
60:('矿料分拣仓',13,15,('矿料分拣','货物仓储'),'sorting'),
61:('渔网棚',9,13,('渔网制作',),'nets'),
62:('腌鱼作坊',11,13,('食品加工',),'fish'),
63:('小船棚',13,19,('船舶修造',),'boat'),
64:('庄园管事屋',11,13,('庄园管理','家庭居住'),'steward'),
65:('侍从住处',13,15,('集体居住',),'servants'),
66:('朝圣旅舍',13,19,('旅客住宿',),'pilgrim'),
67:('水磨进出水渠段',7,15,('水利管理',),'canal'),
}


def shed_roof(m,bounds):
    x0,z0,x1,z1=bounds
    for x in range(x0-1,x1+2):
        y=6+(x-x0+1)//3
        m.box((x,y,z0-1),(x,y,z1+1),'spruce_planks')
        for z in (z0,z1):m.box((x,5,z),(x,y-1,z),'spruce_planks')
    for z in (z0+1,z1-1):
        for x in (x0,x1):m.box((x,2,z),(x,5+(x-x0+1)//3,z),'spruce_log')


def regional(number):
    name,w,d,terms,kind=SPECS[number]
    m=base(number,1,name,w,d,26,terms)
    m.meta['differences']=[f'{name}：按真实作业空间设计，独立于大型城镇同用途地标。']
    if kind in ('pier','canal'):
        return water_module(m,kind)
    if kind=='yard':
        m.box((1,0,1),(w-2,1,d-2),'dirt');m.box((2,1,2),(w-3,1,d-3),'coarse_dirt')
        for x in (2,w-3):
            for z in (3,6,9):m.set(x,2,z,'hay_block')
        m.set(w//2,1,0,'gravel');m.point('entry','entrance',(w//2,2,0),'晒场入口')
        station(m,2,2,d-3,'barrel[facing=east]','晒粮翻粮');return m
    b=(1,2,w-2,d-2)
    wall='stone_bricks' if kind in ('mine','sorting','checkpoint','fish') else 'spruce_planks' if kind in ('forester','timber','nets','boat') else 'white_terracotta'
    room_shell(m,b,height=4,plaster=wall,name=name)
    windows(m,b)
    if kind in ('barn','timber','nets','charcoal','boat','mine','ferry'):
        # Open bay structures need long working faces instead of house facades.
        for x in range(2,w-2):m.box((x,2,2),(x,4,2),'air')
        for z in range(4,d-3):m.box((1,2,z),(1,3,z),'air')
        if kind in ('timber','nets','ferry','mine','charcoal'):shed_roof(m,b)
        else:cross_roof(m,b,6,'brick')
        m.set(w//2,1,1,'cobblestone');m.point('entry','entrance',(w//2,2,1),'开放作业面入口')
    else:
        roof(m,b,6,'stone_brick' if kind in ('sorting','checkpoint','hostel','fish') else 'brick');entry(m,w//2)
    for z in range(5,d-3,5):light(m,w//2,5,z)
    if kind=='barn':
        for x in (2,w-4):
            m.box((x,2,6),(x+1,3,d-3),'hay_block')
        station(m,2,2,4,'barrel[facing=east]','粮仓保管')
    elif kind=='mill':
        # Small indoor hand/animal-power mill: no fictitious flowing-water claim.
        m.box((3,2,7),(5,2,9),'smooth_stone_slab[type=double]')
        m.set(4,3,8,'grindstone[face=floor,facing=north]')
        station(m,4,3,8,'grindstone[face=floor,facing=north]','磨粮',(4,2,6))
        m.box((w-4,2,8),(w-3,3,d-3),'hay_block')
        station(m,2,2,4,'barrel[facing=east]','称量装袋')
    elif kind=='bake':
        m.box((2,2,8),(5,4,10),'bricks');m.set(3,2,7,'furnace[facing=north]')
        station(m,3,2,7,'furnace[facing=north]','烘焙',(3,2,6))
        station(m,w-3,2,5,'barrel[facing=west]','面包售卖',(w-4,2,5));awning(m,2,w-3,0)
        m.box((2,3,2),(3,3,2),'glass_pane')
    elif kind in ('forester','steward'):
        for x in (3,5):bed(m,x,2,d-4,f'bed_{x}')
        station(m,2,2,4,'cartography_table' if kind=='forester' else 'lectern[facing=east]','林务巡护登记' if kind=='forester' else '庄园管事登记')
        m.set(w-3,2,5,'bookshelf');light(m,w//2,5,d-5)
    elif kind=='timber':
        for x in (2,w-4):m.box((x,2,6),(x+1,3,d-3),'stripped_spruce_log[axis=z]')
        station(m,2,2,4,'crafting_table','木料整理');m.meta['rooms'][0]['purpose']='有顶长材堆放与中央装卸通道'
    elif kind=='charcoal':
        m.box((3,2,8),(7,3,11),'bricks');m.box((4,4,9),(6,4,10),'bricks')
        station(m,5,2,7,'furnace[facing=north]','炭窑维护',(5,2,6))
        m.box((9,2,8),(10,2,11),'oak_log[axis=z]')
        m.meta['terrain']['选址']+=' 炭窑旁预留外部防火与卸料空间；不模拟燃烧。'
    elif kind=='ferry':
        for z in (5,8):seat(m,3,2,z)
        station(m,w-3,2,8,'barrel[facing=west]','渡口值守',(w-4,2,8))
        m.meta['terrain']['选址']='候船棚落于岸上；正面陆路入口，另配栈桥接水面，不宣称船舶运行。'
    elif kind=='toll':
        station(m,2,2,6,'lectern[facing=east]','桥头登记收费')
        m.box((2,3,2),(3,3,2),'air');awning(m,1,4,0)
    elif kind=='warehouse':
        for x in (2,w-3):m.box((x,2,5),(x,3,d-3),'barrel[facing=east]' if x==2 else 'barrel[facing=west]')
        m.box((w//2-1,2,d-2),(w//2+1,4,d-2),'air')
        station(m,2,2,3,'lectern[facing=east]','仓管登记')
        m.meta['terrain']['选址']='岸上仓房，后部装卸门接独立栈桥；需核对两侧入口高程。'
    elif kind in ('hostel','servants','pilgrim'):
        zrows=(7,11,15) if kind=='pilgrim' else (7,11)
        for z in zrows:
            for x in (3,5,7,9):bed(m,x,2,z,f'bed_{x}_{z}',use='resident' if kind=='servants' else 'public')
        station(m,2,2,4,'barrel[facing=east]','住宿值守')
        if kind=='hostel':m.set(w-3,2,4,'smoker[facing=west]')
        m.meta['terrain']['选址']+=' 旅舍本体需要稳定承载面，山口朝向与道路另验。'
    elif kind=='checkpoint':
        # Leave a broad through-gate and side duty rooms.
        for z in (2,d-2):m.box((w//2-1,2,z),(w//2+1,4,z),'air')
        for x in (3,w-4):
            m.box((x,2,4),(x,3,d-4),'stone_bricks');m.box((x,2,7),(x,3,8),'air')
        station(m,2,2,5,'barrel[facing=east]','关卡守卫',(2,2,6))
        m.box((w//2,1,d-1),(w//2,1,d-1),'cobblestone')
        m.point('exit','circulation',(w//2,2,d-1),'通行后口')
    elif kind in ('mine','sorting'):
        for x in (2,w-4):m.box((x,2,8),(x+1,2,d-3),'raw_iron_block')
        station(m,2,2,4,'stonecutter[facing=east]','矿料分拣')
        station(m,w-3,2,5,'barrel[facing=west]','矿料装运',(w-4,2,5))
        m.meta['terrain']['选址']+=' 服务真实矿业资源与运输通道；模板本身不生成矿井。'
    elif kind=='nets':
        m.box((w-3,3,5),(w-3,3,d-3),'cobweb')
        station(m,2,2,6,'loom[facing=east]','渔网编修');m.set(2,2,9,'barrel[facing=east]')
    elif kind=='fish':
        for z in (5,8):station(m,2,2,z,'barrel[facing=east]','腌鱼加工')
        m.box((w-3,2,5),(w-3,3,d-3),'barrel[facing=west]')
        m.set(w-3,2,3,'water_cauldron[level=3]')
    elif kind=='boat':
        # A timber boat hull on dry supports; not an entity or working vessel.
        for z in range(5,15):
            half=1 if z in (5,14) else 2
            m.box((w//2-half,2,z),(w//2+half,2,z),'spruce_planks')
            for x in (w//2-half,w//2+half):m.set(x,3,z,'spruce_slab')
        station(m,2,2,6,'crafting_table','小船修造')
        m.meta['terrain']['选址']='岸上干作业棚，开放端朝向下水作业场；船体为静态建模，不是船实体。'
    return m


def water_module(m,kind):
    w,h,d=m.size
    if kind=='pier':
        m.box((1,3,0),(w-2,3,d-1),'spruce_planks')
        for x in (1,w-2):
            for z in (1,6,11):m.box((x,0,z),(x,4,z),'spruce_log')
        for z in range(2,d-1):
            m.set(1,4,z,'spruce_fence');m.set(w-2,4,z,'spruce_fence')
        m.point('entry','entrance',(w//2,4,0),'岸侧栈桥入口')
        m.point('end','circulation',(w//2,4,d-1),'水侧装卸端')
        m.meta.update(ground_plane={'y':4,'note':'桥面脚底 Y=4，水面参考 Y=2；桩底 Y=0。'},
            preview_context={'kind':'shore','land_surface_y':4,'shore_z':2,'water_surface_y':2,'bed_y':0,'padding':3},
            terrain={'选址':'岸侧步道 Y=4 接桥头，水面参考 Y=2；桩底 Y=0 需实际承载，不能放在任意深水。'},
            floors=[{'name':'栈桥','y':3,'max_y':6}],roof_min_y=7)
    else:
        m.box((1,0,0),(5,0,d-1),'stone_bricks')
        for x in (1,5):m.box((x,1,0),(x,2,d-1),'stone_bricks')
        m.box((2,1,0),(4,1,d-1),'water')
        m.box((0,0,0),(0,1,d-1),'cobblestone')
        m.point('entry','entrance',(0,2,0),'渠旁维护道')
        m.point('maintain','work',(0,2,7),'渠旁检修',approach=(0,2,7),use='staff')
        m.meta['terrain']['选址']='同标高直渠段；渠底 Y=0、水面 Y=2，需匹配上下游水位与实际水源；不模拟流量。'
    m.meta['asset_tags']=['infrastructure']
    return m


def slope_cottage():
    src=cottage(1);w,h,d=src.size
    m=Model('EU-27-v04','木架小屋 · 两格坡差与上坡寝室',(w,h+2,d+3),family='EU-27',civilization='欧洲中世纪新制',role='fill')
    m.meta=deepcopy(src.meta);m.meta.update(id='EU-27-v04',name='木架小屋 · 两格坡差与上坡寝室',size=list(m.size))
    for (x,y,z),block in src.blocks.items():m.blocks[(x,y+2,z+3)]=block
    for (x,y,z),value in src.block_entities.items():m.block_entities[(x,y+2,z+3)]=deepcopy(value)
    for room in m.meta['rooms']:
        for key in ('min','max'):room[key][1]+=2;room[key][2]+=3
    for p in m.meta['points']:
        for key in ('pos','approach'):
            if key in p:p[key][1]+=2;p[key][2]+=3
        if p['kind']=='entrance':p['kind']='circulation'
    for x in (3,4,5):
        for i in range(3):
            m.set(x,i+1,i,'stone_brick_stairs[facing=south]')
        m.set(x,3,3,'cobblestone')
    m.point('lower_entry','entrance',(4,2,0),'低街入口')
    for f in m.meta['floors']:f['y']+=2;f['max_y']+=2
    m.meta['roof_min_y']+=2
    m.meta.update(terrain={'选址':'街面脚底 Y=2，屋体脚底 Y=4；沿 +Z 升高两格，模型内台阶连接，不适配任意坡度。'},
       preview_context={'kind':'slope','land_surface_y':4,'run':32,'rise':2,'slope_origin_z':3,'padding':3},
       differences=['相比平地版增加低街入户台阶、两格高差基础；不计为纯平移变体。'])
    # Fill the exposed front foundation only; no bounding-box terrain platform.
    m.box((1,0,5),(7,2,5),'cobblestone')
    return m


def wing_home(number):
    large=number==28;w,d=(15,15) if large else (11,13)
    m=base(number,3,'半木架家宅 · 短L形餐厅翼' if large else '木架小屋 · 短翼储物间',w,d,22)
    main=(1,2,7 if large else 6,d-2)
    wing=(main[2],8 if large else 7,w-2,d-2)
    room_shell(m,main,name='住屋');windows(m,main);roof(m,main,6)
    room_shell(m,wing,name='餐厅与灶间' if large else '储物短翼');windows(m,wing)
    cross_roof(m,wing,6,'stone_brick')
    m.box((main[2],2,wing[1]+1),(main[2],3,wing[1]+2),'air')
    entry(m,4)
    for x in (3,5):bed(m,x,2,d-4,f'bed_{x}')
    if large:
        for x in (3,5):bed(m,x,2,6,f'frontbed_{x}')
        for z in (10,12):seat(m,10,2,z)
        m.box((12,2,10),(12,2,12),'spruce_planks')
        hearth(m,8,9)
    else:
        m.set(w-3,2,d-3,'barrel[facing=west]');hearth(m,2,3)
    light(m,4,5,8);light(m,w-4,5,d-4)
    m.meta['differences']=['真正L形基础与相交屋顶，主屋和侧翼连通；凹角不铺矩形底板。']
    return m


BUILDERS={f'EU-{n:02d}-v01':partial(regional,n) for n in SPECS}
BUILDERS['EU-27-v04']=slope_cottage
BUILDERS['EU-27-v03']=partial(wing_home,27)
BUILDERS['EU-28-v03']=partial(wing_home,28)
