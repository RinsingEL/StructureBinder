"""Forest symbiosis: inhabited roots, supported crowns and productive clearings."""
from functools import partial
from .model import Model
from .components import shell, window, shelf, bench, pendant, crate_stack
from .samples import railing


def base(key, name, size, condition, role='fill', f=2):
    m = Model(key, name, size, family=key.rsplit('-v', 1)[0], civilization='精灵', role=role,
        terrain={'选址':condition,'落地':'只整理建筑本体与步道；保留未写入的林地。附树版本包含独立柱脚和树根占地，不能套入任意现存树体。',
                 '高程':f'主入口脚底 Y={f+1}；高位层和坡地接驳另见楼层与使用点',
                 '运行边界':'种植、蜂具、藤果、兽栏与遗留物均为原版静态空间表达；实际生产、动物和生态行为须另行接入。'})
    m.meta.update(ground_plane=dict(y=f+1,note=f'林下外部步道与地坪顶面脚底Y={f+1}；树冠与内部坡台另行接驳。'),
        source='tools/structure_studio/studio/forest_symbiosis.py:'+key, roof_min_y=f+6,
        floors=[dict(name='林下使用层',y=f,max_y=f+5)],
        preview_context=dict(kind='flat',land_surface_y=f+1,bed_y=-1,padding=4,surface='podzol'))
    return m


def ground(m,x0,z0,x1,z1,f=2,wood=False):
    m.box((x0,0,z0),(x1,f,z1),'mossy_cobblestone')
    m.box((x0,f,z0),(x1,f,z1),'spruce_planks' if wood else 'moss_block')
    m.box((x0,f+1,z0),(x1,m.size[1]-1,z1),'air')


def path(m,x0,z0,x1,z1,f=2):
    m.box((x0,0,z0),(x1,f,z1),'mossy_cobblestone')
    m.box((x0,f,z0),(x1,f,z1),'spruce_planks')


def tree(m,x,z,h=19,r=4,f=2):
    # Broad living trunk, low buttress roots and supported crown. No false floating foliage.
    m.box((x-1,0,z-1),(x+1,h,z+1),'dark_oak_log[axis=y]')
    for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)):
        for i in range(2,r+1):
            yy=f+max(0,r-i)
            m.box((x+dx*i,0,z+dz*i),(x+dx*i,yy,z+dz*i),'dark_oak_log[axis='+('x' if dx else 'z')+']')
    for yy,rad in ((h-5,r),(h-4,r+2),(h-3,r+3),(h-2,r+3),(h-1,r+2),(h,r+1),(h+1,r-1)):
        for xx in range(x-rad,x+rad+1):
            for zz in range(z-rad,z+rad+1):
                if 0<=xx<m.size[0] and 0<=zz<m.size[2] and abs(xx-x)+abs(zz-z)<=rad+rad//2 and (xx-x)**2+(zz-z)**2<=rad*rad+2+(2 if (xx+zz)%3 else -2):
                    if abs(xx-x)>1 or abs(zz-z)>1 or yy>h:
                        m.set(xx,yy,zz,'oak_leaves[persistent=true,distance=1]')
    for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)):
        for i in range(2,r+2):
            xx,zz=x+dx*i,z+dz*i
            if 0<=xx<m.size[0] and 0<=zz<m.size[2]:m.set(xx,h-7+(i//2),zz,'dark_oak_log[axis='+('x' if dx else 'z')+']')
        xx,zz=x+dx*(r-1),z+dz*(r-1)
        m.box((xx,h-6,zz),(xx,h-3,zz),'dark_oak_log[axis=y]')


def roof(m,x0,z0,x1,z1,y,kind='gable'):
    if kind=='gable':
        mid=(x0+x1)//2
        for x in range(x0-1,x1+2):
            rise=min(x-x0+1,x1+1-x)
            yy=y+rise//2
            for z in range(z0-1,z1+2):
                m.set(x,yy,z,'dark_oak_slab[type=top]' if rise%2 else 'dark_oak_planks')
            if rise>1:
                for z in (z0,z1):m.box((x,y,z),(x,yy-1,z),'spruce_planks')
        for z in range(z0-1,z1+2):m.set(mid,y+(min(mid-x0+1,x1+1-mid))//2+1,z,'moss_carpet')
    else:
        # A low, stepped hipped roof with a solid top; green ridge traces the tree canopy.
        for i in range(4):
            xa,xb,za,zb=x0-1+i,x1+1-i,z0-1+i,z1+1-i
            if xa>xb or za>zb:break
            m.box((xa,y+i,za),(xb,y+i,zb),'dark_oak_planks')
            if xa+1<=xb-1 and za+1<=zb-1 and i<3:m.box((xa+1,y+i,za+1),(xb-1,y+i,zb-1),'air')
        if xa<=xb and za<=zb:m.box((xa,y+i+1,za),(xb,y+i+1,zb),'moss_carpet')
    # The eave starts outside the wall. A continuous full-height wall plate closes
    # the inner hip-ring gap and the half-block gap below gable top slabs.
    for z in (z0,z1):m.box((x0,y,z),(x1,y,z),'dark_oak_log[axis=x]')
    for x in (x0,x1):m.box((x,y,z0),(x,y,z1),'dark_oak_log[axis=z]')


def lodge(m,x0,z0,x1,z1,f=2,kind='gable',door=None):
    ground(m,x0,z0,x1,z1,f,True)
    shell(m,(x0,f,z0),(x1,f+5,z1),'stripped_spruce_log[axis=y]',floor='spruce_planks')
    m.box((x0,f+1,z0),(x1,f+1,z0),'mud_bricks')
    m.box((x0,f+1,z1),(x1,f+1,z1),'mud_bricks')
    for x in (x0,x1):
        m.box((x,f+1,z0),(x,f+1,z1),'mud_bricks')
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,f+1,z),(x,f+6,z),'dark_oak_log[axis=y]')
    for z in (z0,z1):
        window(m,(x0+2,f+3,z),(min(x0+4,x1-2),f+4,z),color='lime_stained_glass')
        if x1-x0>12:window(m,(x1-4,f+3,z),(x1-2,f+4,z),color='lime_stained_glass')
    for x in (x0,x1):
        window(m,(x,f+3,z0+3),(x,f+4,min(z0+5,z1-2)),axis='z',color='lime_stained_glass')
    roof(m,x0,z0,x1,z1,f+6,kind)
    dx,dz,face=door or ((x0+x1)//2,z0,'north')
    m.door(dx,f+1,dz,'spruce',face)
    pendant(m,(x0+x1)//2,f+5,(z0+z1)//2,f+7)


def pergola(m,x0,z0,x1,z1,f=2,cover=True):
    path(m,x0,z0,x1,z1,f)
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,f+1,z),(x,f+5,z),'stripped_dark_oak_log[axis=y]')
    for x in range(x0,x1+1):
        for z in (z0,z1):m.set(x,f+5,z,'dark_oak_log[axis=x]')
    for z in range(z0,z1+1,3):m.box((x0,f+5,z),(x1,f+5,z),'dark_oak_log[axis=x]')
    if cover:
        m.box((x0-1,f+6,z0-1),(x1+1,f+6,z1+1),'spruce_slab[type=bottom]')
        for x in range(x0,x1+1,3):
            m.set(x,f+6,z1,'spruce_planks')
            m.set(x,f+7,z1,'moss_carpet')


def entry(m,x,z,y=3,key='entry',face='north'):
    m.point(key,'entrance',(x,y,z),'林间步道入口',facing=face)
    m.meta['connections'].append(dict(kind='pedestrian',pos=[x,y-1,z],direction=face,clearance=[3,3],note=f'外接步道脚底 Y={y}，须保留树根与基础占地'))


def station(m,key,x,y,z,block,label,approach=None):
    m.set(x,y,z,block)
    m.point(key,'work',(x,y,z),label,approach=approach or (x,y,z+1))


def storage(m,key,x,y,z,w=4,label='分类储藏'):
    shelf(m,x,y,z,w,'spruce')
    m.point(key,'storage',(x+1,y+1,z),label,approach=(x+1,y,z-1))


def living(m,key,x0,z0,x1,z1,f=2,beds=1):
    y=f+1
    for i in range(beds):
        x=x0+2+i*3
        m.bed(x,y,z1-2,'green','north');m.point(key+'bed'+str(i),'sleep',(x,y,z1-2),'林居床位',approach=(x+1,y,z1-2))
    station(m,key+'cook',x1-2,y,z0+2,'smoker[facing=west]','烹饪炉',approach=(x1-3,y,z0+2))
    m.box((x1-2,y+1,z0+2),(x1-2,f+12,z0+2),'cobblestone')
    m.set(x1-2,y,z0+4,'water_cauldron[level=3]')
    storage(m,key+'store',x1-5,y,z1-1,4,'衣物、食物与随身物品')
    m.set(x0+3,y,z0+3,'spruce_slab[type=top]');m.set(x0+3,y+1,z0+3,'flower_pot')
    bench(m,x0+2,y,z0+5,3,'north')
    m.room(key,'家庭生活空间',(x0+1,y,z0+1),(x1-1,f+5,z1-1),'烹饪、饮水、餐桌、床位与储藏齐备；中央保留穿行')


def seed_archive():
    m=base('FS-03-v01','三叶庭 · 种子档案馆',(45,27,37),'潮湿林地中的排水良好高地；前庭接步道，采光育苗庭位于南侧林隙','key')
    ground(m,2,2,42,33);path(m,19,1,25,33)
    lodge(m,4,6,18,24,kind='hip',door=(18,14,'east'))
    lodge(m,27,6,40,24,kind='hip',door=(27,14,'west'))
    pergola(m,12,28,32,33)
    tree(m,22,22,22,4)
    shelf(m,6,3,8,10,'spruce','bookshelf');storage(m,'seed',6,3,22,10,'按林层分类的种子样本柜')
    station(m,'register',7,3,14,'lectern[facing=south]','种源登记台');m.set(9,3,14,'cartography_table')
    shelf(m,29,3,8,9,'spruce');storage(m,'reserve',29,3,22,8,'干燥封装储备')
    station(m,'sort',31,3,14,'crafting_table','筛选和封装');m.set(35,3,14,'composter')
    for x0,z0,w in ((6,17,5),(12,17,4),(29,11,4),(35,18,4)):
        shelf(m,x0,3,z0,w,'spruce')
    m.box((6,3,14),(10,3,14),'spruce_slab[type=top]');m.set(7,3,14,'lectern[facing=south]')
    bench(m,11,3,11,4,'south');bench(m,31,3,19,3,'north')
    for x in (14,18,26,30):
        m.set(x,3,30,'mud_bricks');m.set(x,4,30,'flower_pot')
    station(m,'nursery',22,3,30,'composter','育苗育土工作台',approach=(22,3,29))
    m.room('records','种源记录厅',(5,3,7),(17,7,23),'种源登记、样本分类和记录查阅')
    m.room('reserve','干燥种子库',(28,3,7),(39,7,23),'储备柜、筛选与封装作业')
    m.room('nursery','采光育苗廊',(13,3,29),(31,7,32),'面向林隙的育苗展示与换土空间')
    entry(m,22,2);m.meta['design_notes']=['三座功能体围绕保留树体，西记录、东储备、南育苗；前后通路绕开可见板根。','种子柜和花盆表达保存与育苗空间，不声明原版种子库系统。']
    return m


def beast_inn():
    m=base('FS-04-v01','蹄铃驿 · 林间兽驿',(43,24,35),'宽度足够的林路停歇处；兽栏与旅客门口分开，南侧须有真实动物到达路径','key')
    ground(m,2,2,40,31);path(m,21,2,26,31)
    lodge(m,4,6,20,27,door=(20,13,'east'))
    # Partition sleep and public living space while preserving a central door.
    m.box((5,3,18),(19,7,18),'spruce_planks');m.door(13,3,18,'spruce','south')
    living(m,'guest',4,18,20,27,beds=2)
    station(m,'welcome',7,3,9,'lectern[facing=south]','住宿与旅具登记')
    bench(m,7,3,14,5,'north');m.box((8,3,12),(12,3,12),'spruce_slab[type=top]')
    pergola(m,28,8,38,24)
    for z in (8,16,24):railing(m,(29,3,z),(38,3,z),'spruce','x')
    railing(m,(38,3,9),(38,3,23),'spruce','z')
    for z in (11,20):
        m.set(36,3,z,'hay_block');m.set(36,3,z+1,'water_cauldron[level=3]')
        m.point('care'+str(z),'work',(36,3,z),'饲喂与饮水',approach=(35,3,z))
    storage(m,'gear',28,3,29,9,'饲料、鞍具和维修物资')
    m.room('arrival','驿站接待厅',(5,3,7),(19,7,17),'旅人登记、公共座位和餐桌')
    m.room('stable','双间照护棚',(29,3,9),(37,7,23),'独立照护隔间，宽侧开口接南北赶畜通路')
    entry(m,23,2);m.meta['design_notes']=['客房位于主屋后段，栏棚与主屋之间是宽阔的照护与卸具通道；动物行为未实现。']
    return m


def crown_hall():
    m=base('FS-05-v01','枝议台 · 树冠议事堂',(43,35,42),'可容纳完整根盘的大林隙；议事层由落地木柱和树旁梁架共同支承，北侧长阶连接林路','key')
    ground(m,3,2,38,37);path(m,4,2,10,16)
    tree(m,32,29,29,5)
    # Continuous nine-step north ascent, safely separated from roots.
    for z in range(5,13):
        y=z-2
        m.box((5,2,z),(9,y,z),'mossy_cobblestone')
        for x in range(5,10):m.set(x,y,z,'spruce_stairs[facing=south]')
    m.box((5,10,13),(18,10,17),'spruce_planks')
    for x,z in ((5,13),(9,17),(14,14),(14,33),(29,14),(29,33)):
        m.box((x,0,z),(x,10,z),'dark_oak_log[axis=y]')
    for x in (14,29):m.box((x,9,14),(x,9,33),'dark_oak_log[axis=z]')
    # Lodge foundation is cleared underneath to leave the elevated frame legible.
    lodge(m,14,14,29,33,10,'hip',door=(14,16,'west'))
    m.box((14,0,14),(29,9,33),'air')
    m.box((14,0,14),(29,1,33),'mossy_cobblestone');m.box((14,2,14),(29,2,33),'moss_block')
    for z in (15,23,32):m.box((14,9,z),(29,9,z),'dark_oak_log[axis=x]')
    for x in (14,29):
        for z in (14,23,33):m.box((x,0,z),(x,9,z),'dark_oak_log[axis=y]')
    tree(m,32,29,29,5)
    m.box((19,11,21),(24,11,25),'spruce_slab[type=top]')
    bench(m,19,11,18,6,'south');bench(m,19,11,28,6,'north')
    station(m,'speak',22,11,21,'lectern[facing=north]','议事发言与记录',approach=(22,11,20))
    storage(m,'archive',17,11,32,9,'议决档案与公共物品')
    m.point('landing','circulation',(10,11,15),'树冠层落脚平台')
    railing(m,(10,11,13),(13,11,13),'spruce','x');railing(m,(5,11,17),(13,11,17),'spruce','x')
    railing(m,(5,11,14),(5,11,16),'spruce','z')
    for z in range(5,13):
        for x in (4,10):
            m.box((x,0,z),(x,z-2,z),'mossy_cobblestone');m.set(x,z-1,z,'spruce_fence')
    m.room('council','树冠议事厅',(15,11,15),(28,15,32),'围桌议事、发言、档案与有遮蔽等候')
    m.meta.update(roof_min_y=16,floors=[dict(name='林下到达与柱脚',y=2,max_y=9),dict(name='树冠议事层',y=10,max_y=15)])
    entry(m,7,2);m.meta['design_notes']=['实际可步行的宽长阶上升八格；高位桥台连接完整室内，根部和柱脚分别可见。','树冠只是建筑适配环境，不表达活树自动生长或跨树网络。']
    return m


def clearing_market():
    m=base('FS-09-v01','叶隙交换场 · 林间市集',(45,27,39),'数条林路交汇的天然空隙；需要树根以外的排水良好公共地面','key')
    ground(m,2,2,42,36);path(m,19,2,25,35)
    for x0,z0,x1,z1,key,block in ((4,7,16,17,'food','smoker[facing=south]'),(28,7,40,17,'weave','loom[facing=south]'),(4,25,16,34,'tools','crafting_table')):
        pergola(m,x0,z0,x1,z1)
        m.box((x0+2,3,z0+3),(x1-2,3,z0+3),'spruce_planks')
        station(m,key,x0+3,3,z0+3,block,'交换柜台 '+key)
        storage(m,key+'stock',x0+2,3,z1-1,x1-x0-3,'棚内周转物资')
        m.room(key,'分业交换棚',(x0+1,3,z0+1),(x1-1,7,z1-1),'面向公共通道的独立柜台、货架和遮雨顶')
    tree(m,32,28,23,5);bench(m,20,3,31,5,'north')
    station(m,'ledger',22,3,20,'lectern[facing=north]','公共交换账册',approach=(22,3,19))
    m.set(19,3,25,'water_cauldron[level=3]')
    entry(m,22,2);m.meta['design_notes']=['三座不同尺寸的货棚围合清晰的十字通路，树荫座位独立于装卸和交易站位。']
    return m


def nature_school():
    m=base('FS-10-v01','年轮学舍 · 自然学堂',(45,27,36),'面向林隙的观察边缘；观察台应朝向真实林地而非其它建筑背墙','key')
    ground(m,2,2,42,32);lodge(m,5,6,27,24,kind='hip')
    m.box((19,3,7),(19,7,23),'spruce_planks');m.door(19,3,17,'spruce','east')
    for z in (12,17):
        for x in (8,13):
            m.box((x,3,z),(x+2,3,z),'spruce_slab[type=top]');bench(m,x,3,z+2,3,'north')
    station(m,'teach',11,3,9,'lectern[facing=south]','自然课讲台')
    shelf(m,7,3,23,9,'spruce','bookshelf')
    storage(m,'specimen',21,3,23,4,'矿石、种荚与叶片标本')
    station(m,'observe',23,3,11,'cartography_table','物候观察记录')
    pergola(m,31,8,40,24,cover=False);path(m,27,16,33,20)
    m.door(27,3,18,'spruce','east');railing(m,(40,3,9),(40,3,23),'spruce','z')
    for z in (10,22):m.set(37,3,z,'moss_block');m.set(37,4,z,'flowering_azalea')
    m.point('deck','circulation',(36,3,17),'林相观察台',look_at=[44,8,17])
    tree(m,35,29,21,4)
    m.room('class','林下教室',(6,3,7),(18,7,23),'讲台、双列桌椅与阅览书架')
    m.room('specimen','标本与记录室',(20,3,7),(26,7,23),'标本、记录和观察出入口')
    m.room('deck','露天观察台',(32,3,9),(39,7,23),'面向林缘的观察与示范植物空间')
    path(m,14,2,18,6);entry(m,16,2);return m


def old_nursery():
    m=base('FS-11-v01','返绿育苗所 · 废弃设施',(43,25,37),'已经退出生产且林地回生的旧育苗场；保留干燥访客路径，不作为正常生产填充','structure')
    ground(m,2,2,39,33);path(m,17,2,23,32)
    lodge(m,4,6,16,20,door=(16,13,'east'))
    storage(m,'remnant',6,3,18,7,'残留育苗盘与旧账册')
    station(m,'records',7,3,9,'lectern[facing=south]','旧种植日志')
    # Local roof collapse, deliberately away from the dry archive and marked route.
    m.box((4,8,16),(10,16,21),'air');m.box((4,3,18),(4,6,20),'air')
    m.box((6,3,15),(8,3,16),'mossy_cobblestone')
    pergola(m,26,6,37,27,cover=False)
    for x in (28,33):
        for z in (9,15,21):
            m.box((x,2,z),(x+1,2,z+2),'moss_block');m.set(x,3,z,'fern')
            if z==15:m.set(x+1,3,z+1,'flowering_azalea')
    m.box((26,7,20),(31,8,27),'air')
    station(m,'bench',28,3,25,'composter','旧换土台',approach=(28,3,24))
    tree(m,11,28,21,5);m.room('archive','残存管理房',(5,3,7),(15,7,19),'留存旧账册，西南侧屋顶局部失落并长入苔藓')
    m.room('beds','回生苗床',(27,3,7),(36,7,26),'废弃棚架、残存换土台和已经复生的林下植被')
    entry(m,20,2);m.meta['design_notes']=['破损有明确位置：管理屋西南屋面、东棚末段梁架；保留可探索的安全通路。','生态恢复是手工构图，未模拟随时间变化。'];return m


def root_ruin():
    m=base('FS-12-v01','根抱旧庭 · 古树根部遗址',(43,33,41),'保存石砌房间和巨大根盘的历史林地；树根整体落地并避开主要探索路线','structure')
    ground(m,3,3,38,37);path(m,18,2,24,35)
    tree(m,29,26,27,7)
    # Remnant vaulted study and a roofless old court, partially engulfed at the rear.
    shell(m,(5,2,8),(17,7,28),'mossy_stone_bricks',floor='cracked_stone_bricks')
    m.door(17,3,16,'dark_oak','east')
    for z in range(8,29):
        for x in range(5,18):
            y=8+min(x-5,17-x)//2
            if not(z>23 and x<10):m.set(x,y,z,'mossy_stone_bricks')
    window(m,(8,5,8),(12,6,8),color='glass')
    shelf(m,7,3,10,6,'dark_oak','bookshelf');station(m,'trace',9,3,18,'chiseled_stone_bricks','残存刻石记录')
    storage(m,'relic',7,3,26,6,'遗留器物收纳壁龛')
    for z in (8,35):m.box((20,2,z),(35,4,z),'mossy_stone_bricks')
    m.box((20,3,8),(24,4,8),'air')
    for x,z in ((34,11),(34,16),(24,35),(28,35)):m.box((x,3,z),(x,7,z),'mossy_stone_bricks')
    m.point('court','circulation',(21,3,23),'树根与旧庭交界',look_at=[29,7,26])
    m.room('study','旧石砌记录室',(6,3,9),(16,7,27),'拱顶残段、刻石、遗物与根外通道')
    m.room('court','根抱旧庭',(19,3,10),(35,7,34),'南东侧被根盘侵入的开放旧庭，西侧保留探索线')
    entry(m,21,3);m.meta['design_notes']=['树干、板根、残墙与拱顶共同形成历史层次，根盘不虚悬；破损拱顶之外保留完整室内可读性。'];return m


BUILDERS={m: fn for m,fn in [('FS-03-v01',seed_archive),('FS-04-v01',beast_inn),('FS-05-v01',crown_hall),('FS-09-v01',clearing_market),('FS-10-v01',nature_school),('FS-11-v01',old_nursery),('FS-12-v01',root_ruin)]}
