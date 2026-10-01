"""Steppe caravans: felt structures, seasonal occupation and open route corridors."""
from .model import Model
from .components import bench, shelf, crate_stack, pendant


def base(key,name,size,condition,role='key'):
    m=Model(key,name,size,civilization='游牧',role=role,terrain={
        '选址':condition,'高程':'入口与营地脚底Y=3；局部巡路台脚底Y=6，见标记与台阶。',
        '资源与季节':'依赖外部季节水草及商路补给；水槽为运水储备，不凭空生成水源。严寒风暴季撤帐或转入冬营。',
        '适配边界':'朝南开口按当地背风方向旋转；保留营地外人畜廊道，禁止直接压平山体或河滩。',
        '运行边界':'帐篷、马具、货车与遗存均为原版静态表达，动物、交易及迁徙尚未接入。'})
    m.meta.update(ground_plane=dict(y=3,note='营路与营地铺面顶面脚底Y=3，地坪方块Y=2；局部巡路台另行登阶。'),
        source='tools/structure_studio/studio/steppe_caravans.py:'+key,
        roof_min_y=7,floors=[dict(name='营地使用层',y=2,max_y=6)],
        preview_context=dict(kind='flat',land_surface_y=3,bed_y=-1,padding=5,surface='grass'))
    m.meta['design_notes']=['暖白毡面、橙赭织带、深木框与粗石形成共同材料语言；不同用途分别安排开敞大帐、圆形家帐、露天石阵和路线营地。']
    return m


def pad(m,x0,z0,x1,z1,material='coarse_dirt',f=2):
    m.box((x0,0,z0),(x1,f,z1),'dirt')
    m.box((x0,f,z0),(x1,f,z1),material)
    m.box((x0,f+1,z0),(x1,m.size[1]-1,z1),'air')


def entry(m,key,x,z,face='south'):
    m.point(key,'entrance',(x,3,z),'营路接驳入口',facing=face)
    m.meta['connections'].append(dict(kind='pedestrian',pos=[x,2,z],direction=face,clearance=[3,3],note='须接同高程营路，另留牲畜及车队空间'))


def work(m,key,x,z,block,label,approach=None,y=3):
    m.set(x,y,z,block);m.point(key,'work',(x,y,z),label,approach=approach or (x,y,z+1))


def store(m,key,x,z,w=3,label='行旅物资柜'):
    shelf(m,x,3,z,w,'dark_oak')
    m.point(key,'storage',(x+1,4,z),label,approach=(x+1,3,z+1))


def table(m,key,x,z,w=4,label='餐叙桌',color='orange'):
    m.box((x,3,z),(x+w-1,3,z+1),'dark_oak_slab[type=top]')
    m.set(x+w-1,4,z,'flower_pot')
    bench(m,x,3,z-2,w,'south','dark_oak');bench(m,x,3,z+3,w,'north','dark_oak')
    m.point(key,'work',(x+1,3,z),label,approach=(x-1,3,z))


def bed(m,key,x,z,color='orange'):
    m.bed(x,3,z,color,'north');m.set(x,3,z+1,'barrel[facing=up]')
    m.point(key,'sleep',(x,3,z),'铺毡床位',approach=(x+1,3,z))


def yurt(m,cx,cz,r,color='orange',entrance=True):
    # Circular wall follows the same sampled disc as the roof; no open eave gaps.
    for x in range(cx-r,cx+r+1):
        for z in range(cz-r,cz+r+1):
            d=(x-cx)**2+(z-cz)**2
            if d>r*r:continue
            m.box((x,0,z),(x,2,z),'dirt');m.set(x,2,z,'dark_oak_planks')
            m.box((x,3,z),(x,14,z),'air')
            rim=any((x+dx-cx)**2+(z+dz-cz)**2>r*r for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)])
            if rim:
                for y in range(3,7):m.set(x,y,z,color+'_wool' if y==4 else 'white_wool')
            distance=max(abs(x-cx),abs(z-cz),int((d)**.5))
            yy=7+(r-distance)//2
            m.set(x,yy,z,color+'_wool' if (x==cx or z==cz) else 'white_wool')
    for x,z in [(cx-r,cz),(cx+r,cz),(cx,cz-r),(cx-2,cz+2),(cx+2,cz+2)]:
        if (x,z)==(cx,cz-r):top=7
        else:top=7+max(0,(r-max(abs(x-cx),abs(z-cz)))//2)
        m.box((x,3,z),(x,top,z),'dark_oak_log[axis=y]')
    if entrance:
        m.box((cx-1,3,cz+r-1),(cx+1,5,cz+r),'air')
        m.box((cx-1,6,cz+r),(cx+1,6,cz+r),color+'_wool')
    # A roof vent is fully framed, with a daylight opening above the central hearth.
    top=7+r//2
    for dx in (-1,0,1):
        for dz in (-1,0,1):
            m.set(cx+dx,top,cz+dz,'dark_oak_planks' if dx or dz else 'air')
    m.set(cx,top-1,cz,'air')
    pendant(m,cx-1,6,cz+2,top)


def kitchen(m,key,x,z):
    m.box((x,3,z),(x+5,3,z),'dark_oak_planks')
    work(m,key+'cook',x,z,'smoker[facing=south]','烹饪与热食')
    m.box((x,4,z),(x,11,z),'cobblestone_wall')
    work(m,key+'water',x+2,z,'water_cauldron[level=3]','运水储槽')
    m.set(x+4,4,z,'flower_pot');m.set(x+5,4,z,'barrel[facing=south]')


def canopy(m,x0,z0,x1,z1,color='orange',height=7):
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,3,z),(x,height,z),'dark_oak_log[axis=y]')
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            rise=min(x-x0,x1-x)
            m.set(x,height+min(3,rise//2),z,color+'_wool' if (z-z0)%5==0 else 'white_wool')
    for z in (z0,z1):m.box((x0,height-1,z),(x1,height-1,z),'dark_oak_log[axis=x]')
    mid=(x0+x1)//2
    m.box((mid,3,z0),(mid,height+3,z0),'dark_oak_log[axis=y]')
    m.box((mid,3,z1),(mid,height+3,z1),'dark_oak_log[axis=y]')
    for x,z in [(x0+2,z0+3),(x1-2,z1-3)]:
        pendant(m,x,height-1,z,height+2)


def cart(m,x,z,broken=False):
    m.box((x,3,z),(x+4,3,z+7),'dark_oak_planks')
    for zz in (z+1,z+6):
        m.box((x-1,3,zz),(x+5,3,zz),'dark_oak_log[axis=x]')
        for xx in (x-1,x+5):m.set(xx,2,zz,'grindstone[face=floor,facing=north]')
    for xx in (x,x+4):m.box((xx,4,z),(xx,4,z+7),'dark_oak_trapdoor[open=true,facing=east]')
    if not broken:
        for zz in (z,z+3,z+7):
            for xx in (x,x+4):m.box((xx,4,zz),(xx,6,zz),'dark_oak_fence[north=true,south=true]')
            m.box((x,7,zz),(x+4,7,zz),'white_wool')
        m.box((x,7,z),(x+4,7,z+7),'white_wool')
        crate_stack(m,x+1,4,z+2,2,3,2)
    else:
        m.box((x+2,3,z+4),(x+4,5,z+7),'air');m.set(x+1,4,z+1,'barrel[facing=up]')
    m.box((x+1,3,z+8),(x+1,3,z+10),'dark_oak_fence[north=true,south=true]')


def market():
    m=base('SC-01','行旅大帐集市',(49,20,43),'季节商路与集会交汇处的缓坡台地；集市西侧外留牲畜绕行线，南面6格净宽车道，不占饮水点。')
    pad(m,2,2,46,40)
    pad(m,8,7,34,32,'dark_oak_planks')
    canopy(m,8,7,34,32,'orange',8)
    for x in (8,34):m.box((x,3,19),(x,8,19),'dark_oak_log[axis=y]')
    m.box((8,8,19),(34,8,19),'dark_oak_log[axis=x]')
    # Two separated canopy wings leave a visible central covered pedestrian street.
    for x0 in (11,26):
        for i,z in enumerate((11,20)):
            m.box((x0,3,z),(x0+4,3,z+2),'dark_oak_planks')
            m.box((x0,4,z),(x0+4,4,z),'orange_carpet' if i==0 else 'red_carpet')
            block=['loom[facing=south]','crafting_table'][i] if x0==11 else ['barrel[facing=south]','brewing_stand'][i]
            work(m,f'trade{x0}_{i}',x0+2,z,block,['织毡与皮货交易','工具修配交易'][i] if x0==11 else ['粮料称量','草药补给'][i],(x0+2,3,z-1))
            m.set(x0+2,4,z,'air')
            sample=('white_wool' if i==0 else 'grindstone[face=floor,facing=south]') if x0==11 else ('hay_block' if i==0 else 'flower_pot')
            m.set(x0,4,z+2,sample);m.set(x0+4,4,z+2,'flower_pot')
            store(m,f'stock{x0}_{i}',x0,z+5,4,'摊主分类周转货柜')
    table(m,'rest',15,28,4,'旅人遮蔽休息与议价桌')
    store(m,'escrow',10,8,5,'登记寄存物资')
    work(m,'ledger',18,8,'lectern[facing=south]','寄存与交易登记')
    cart(m,39,8);cart(m,39,24)
    m.point('unload','work',(39,4,10),'车队卸货侧',approach=(37,3,10))
    canopy(m,3,8,6,18,'red',6);m.set(4,3,10,'hay_block');m.set(5,3,10,'hay_block')
    work(m,'water',4,14,'water_cauldron[level=3]','临时饮水补给',approach=(5,3,14))
    m.room('trade','四行业交易大帐',(8,3,7),(34,7,32),'摊位、中央人行街、寄存与公共休息')
    m.room('cargo','车队卸货带',(37,3,7),(46,7,37),'货车靠边装卸；不穿越中央顾客街')
    entry(m,'entry',21,39);entry(m,'rear',21,3,'north')
    m.meta['differences']=['纵向大帐与独立装卸侧带；四种行业摊位和公共桌，不把车队混入人行走廊。']
    return m


def council():
    m=base('SC-05','议事家帐与炊事副帐',(43,19,39),'家族常住或长季驻扎台地；主帐南向背风、炊事副帐在下风侧，与主帐之间留防火间距。')
    pad(m,2,2,40,36,'grass_block');yurt(m,15,16,12,'red');yurt(m,34,11,6,'orange')
    m.box((14,2,29),(16,2,36),'coarse_dirt')
    # Rear sleeping chambers have doors and clothing chests, away from public visitors.
    m.box((4,3,12),(26,5,12),'white_wool')
    for x in (10,20):m.door(x,3,12,'dark_oak','south')
    m.box((15,3,5),(15,5,12),'red_wool')
    bed(m,'bed1',9,9,'red');bed(m,'bed2',12,9,'orange');bed(m,'bed3',19,9,'red')
    store(m,'linen',18,6,4,'家族衣物与冬毡')
    table(m,'council',8,17,5,'会客与家族议事长桌')
    m.box((19,2,16),(23,2,23),'red_wool')
    bench(m,20,3,21,3,'north','dark_oak')
    work(m,'history',21,16,'lectern[facing=south]','家谱与路线讲述')
    store(m,'reserve',20,14,3,'公共备用物资')
    work(m,'mending',7,23,'loom[facing=east]','家庭缝补',approach=(8,3,23))
    work(m,'hearth',15,16,'campfire[lit=false]','中央保温炉与上方通风口',approach=(16,3,16))
    kitchen(m,'cook',31,10);store(m,'food',31,8,4,'炊事原料')
    table(m,'meal',29,24,5,'帐外共享餐叙与备餐')
    m.room('home','带隐私门的寝区',(7,3,5),(23,6,12),'床铺、家族衣物及床侧通路')
    m.room('council','会客与家庭起居',(5,3,13),(25,6,26),'议事长桌、读写讲述、缝补与中央炉')
    m.room('cook','下风炊事副帐',(29,3,6),(39,6,16),'备餐、水槽、炊炉及原料储藏')
    entry(m,'entry',15,35);m.meta['differences']=['圆形主帐与较小炊帐组成非对称家族生活场；公众前区与带门寝区明确分开。']
    return m


def stones():
    m=base('SC-10','祖灵石阵与守护小帐',(41,20,39),'远离放牧主通路的干燥脊肩，石阵朝向当地日出缺口；仅具有祖灵仪式需求的组团明确选用，保留远眺界面。')
    pad(m,2,2,38,36,'grass_block')
    cx,cz=15,17
    positions=[(-10,0),(-8,-6),(-3,-10),(4,-9),(9,-4),(10,3),(6,8),(-7,8)]
    for i,(dx,dz) in enumerate(positions):
        x,z=cx+dx,cz+dz;h=5+i%3
        m.box((x-1,2,z),(x+1,h,z),'mossy_cobblestone' if i%2 else 'andesite')
        m.set(x,h+1,z,'chiseled_stone_bricks')
        m.set(x,3,z+1,'orange_glazed_terracotta[facing=south]')
    for x in range(6,25):
        for z in range(8,27):
            d=(x-cx)**2+(z-cz)**2
            if 42<=d<=65:m.set(x,2,z,'gravel')
    m.box((14,2,15),(16,2,19),'polished_andesite')
    work(m,'offering',15,17,'chiseled_stone_bricks','祖灵供物台',approach=(15,3,19));m.set(15,4,17,'flower_pot')
    bench(m,10,3,24,3,'north','dark_oak');bench(m,18,3,24,3,'north','dark_oak')
    m.box((14,2,28),(16,2,36),'gravel')
    yurt(m,32,12,6,'brown');bed(m,'keeper',30,10,'brown')
    work(m,'record',34,11,'lectern[facing=south]','仪式与口述史记录')
    work(m,'water',35,13,'water_cauldron[level=3]','守护者饮水')
    store(m,'offerings',31,8,3,'仪式用品与冬衣')
    canopy(m,28,24,37,35,'brown',7);kitchen(m,'keeper',29,25)
    table(m,'prepare',30,30,3,'供物与守护者用餐准备')
    m.room('ritual','露天石阵',(4,3,6),(26,8,28),'环行仪式、供物与观礼，南侧敞开')
    m.room('guardian','守护者小帐',(27,3,7),(37,6,17),'睡眠、记录、衣物与饮水')
    m.room('prepare','仪式备物棚',(28,3,24),(37,6,35),'灶台、备物及用餐')
    entry(m,'entry',15,35);m.meta['differences']=['露天散列石阵与偏置守护小帐，以视线、环行和仪式南入口组织；非全营地必选。']
    return m


def abandoned():
    m=base('SC-11','迁徙旧营地遗存',(39,15,35),'已因水草枯竭或路线改迁而放弃的旧台地；旧路保留但不把残存水槽当作可用水源。','structure')
    pad(m,2,2,36,32,'grass_block')
    for cx,cz,r in [(12,13,8),(28,10,5)]:
        for x in range(cx-r,cx+r+1):
            for z in range(cz-r,cz+r+1):
                d=(x-cx)**2+(z-cz)**2
                if d<=r*r:m.set(x,2,z,'coarse_dirt' if (x+z)%4 else 'gravel')
                if r*r-15<=d<=r*r and (x+2*z)%3:m.set(x,3,z,'mossy_cobblestone_slab[type=bottom]')
        for dx,dz,h in [(-r,0,5),(r-1,-1,4),(0,-r,4)]:m.box((cx+dx,3,cz+dz),(cx+dx,h,cz+dz),'dark_oak_log[axis=y]')
        m.set(cx,3,cz,'campfire[lit=false]')
    m.box((10,3,7),(16,3,7),'dark_oak_log[axis=x]')
    m.box((11,4,7),(14,4,7),'brown_wool');m.set(12,3,9,'brown_carpet')
    work(m,'hearth',12,13,'campfire[lit=false]','冷灰灶台与拆帐痕迹',approach=(12,3,15))
    work(m,'cache',8,10,'barrel[facing=up]','残存密封储物桶',approach=(9,3,10))
    m.set(7,3,10,'dark_oak_slab[type=bottom]');m.set(8,3,9,'gravel')
    work(m,'drywater',28,10,'cauldron','干涸储水槽')
    cart(m,25,19,True);m.point('cart','work',(27,4,20),'断裂货车与遗留箱',approach=(23,3,20))
    for z in range(4,33):
        for x in (19,20,21):m.set(x,2,z,'gravel' if z%5 else 'coarse_dirt')
    m.box((4,2,24),(18,2,26),'coarse_dirt')
    work(m,'marker',8,25,'cobblestone_wall','改迁方向旧路标',approach=(8,3,27));m.set(8,4,25,'dark_oak_fence')
    m.room('remains','拆帐基圈与旧灶',(4,3,4),(20,5,22),'无完整屋顶或现住床位，地表磨损与残料解释迁离')
    m.room('ruinedcart','弃置车与干水槽',(23,3,5),(34,6,29),'空水槽、断轮车体和残存箱')
    entry(m,'south',20,31);entry(m,'north',20,3,'north')
    m.meta['roof_min_y']=12;m.meta['differences']=['废弃状态：拆除毡顶、残柱基圈、冷灶、空水槽与断车；不误标成仍可住的营地。']
    return m


def patrol():
    m=base('SC-12','山口巡路营与换具棚',(47,21,43),'真实山口西侧背风缓台：东缘示意岩脊、南北贯通7格路槽；营帐避开风口，岗台面向上坡北口，不能部署在任意平地充当关口。','structure')
    pad(m,2,2,44,40,'grass_block')
    for z in range(2,41):
        m.box((30,2,z),(36,2,z),'gravel')
        for x in range(39,45):
            h=3+(x-39)//2+(z//8)%2
            m.box((x,3,z),(x,h,z),'stone')
    yurt(m,13,12,9,'brown')
    m.box((6,3,11),(20,5,11),'white_wool');m.door(13,3,11,'dark_oak','south')
    bed(m,'guard1',9,8,'brown');bed(m,'guard2',16,8,'orange')
    store(m,'winter',11,5,4,'冬毡与巡路补给')
    table(m,'briefing',7,16,4,'巡路交班与路线议事')
    work(m,'routes',18,12,'cartography_table','山口路线记录',approach=(19,3,12))
    kitchen(m,'guard',15,14)
    canopy(m,5,27,23,37,'orange',7)
    # Broad horse handling aisle in front of tack workbench, feed and water.
    store(m,'tack',7,28,4,'鞍具与修理皮料')
    work(m,'repair',14,28,'crafting_table','马具维修',approach=(14,3,29))
    work(m,'water',20,29,'water_cauldron[level=3]','运水饮马槽')
    m.box((19,3,33),(21,4,35),'hay_block')
    m.box((6,3,35),(6,5,35),'dark_oak_log');m.box((11,3,35),(11,5,35),'dark_oak_log')
    m.box((6,5,35),(11,5,35),'dark_oak_log[axis=x]')
    m.point('hitch','work',(8,4,35),'有顶拴马与检具区',approach=(8,3,33))
    # Raised stone lookout approached by three full-width stair courses.
    m.box((23,0,5),(28,5,12),'cobblestone');m.box((23,6,5),(28,9,12),'air')
    for z in (5,12):m.box((23,6,z),(28,6,z),'cobblestone_wall[east=low,west=low]')
    for x in (23,28):m.box((x,6,5),(x,6,12),'cobblestone_wall[north=low,south=low]')
    m.box((25,6,12),(26,7,12),'air')
    for i in range(3):
        zz=15-i;yy=3+i
        m.box((25,0,zz),(26,yy-1,zz),'cobblestone')
        m.box((25,yy,zz),(26,yy,zz),'cobblestone_stairs[facing=north]')
    work(m,'lookout',25,7,'lectern[facing=south]','北口视野与值守记录',approach=(25,6,8),y=6)
    m.room('sleep','巡路员帐',(5,3,4),(21,6,20),'睡眠、交班、路线登记、厨房及冬毡')
    m.room('tack','换具饮马棚',(5,3,27),(23,6,37),'马具维修、饲料、运水与检具通路')
    m.room('watch','北口岗台',(23,6,5),(28,9,12),'面向山口观察，梯道避开主交通')
    entry(m,'south',33,39);entry(m,'north',33,3,'north')
    m.meta['floors'].append(dict(name='巡路岗台',y=5,max_y=9))
    m.meta['differences']=['主路南北贯通，侧帐与换具棚退让车道；东侧岩脊及独立台阶岗台表达实际山口关系。']
    return m


BUILDERS={'SC-01':market,'SC-05':council,'SC-10':stones,'SC-11':abandoned,'SC-12':patrol}
