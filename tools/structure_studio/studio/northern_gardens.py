"""Nordic sheltered kitchen plots and task-specific drying shelters."""
from .northern_seafarers import base,lodge,entry,use,counter,finish,rail
from .northern_life import stores,zone
from .northern_crafts import canopy,drying


def yard(key,name,w,d):
    m=base(key,name,w,d,24,role='fill');m.meta['source']='tools/structure_studio/studio/northern_gardens.py:BUILDERS'
    return m


def plot(m,key,x,z,w,d,crop='carrots',y=3):
    m.box((x-1,y-1,z-1),(x+w,y-1,z+d),'spruce_log[axis=x]')
    for xx in range(x,x+w):
        for zz in range(z,z+d):
            m.set(xx,y-1,zz,'farmland[moisture=7]');m.set(xx,y,zz,f'{crop}[age=7]')
    # Covered in-template water source; furthest corner stays within four blocks.
    cx=x+w//2;cz=z+d//2
    m.set(cx,y-1,cz,'water[level=0]');m.set(cx,y,cz,'spruce_trapdoor[facing=north,half=bottom,open=false,powered=false,waterlogged=false]')
    use(m,key,'菜畦维护、取水和收获边道',x,z,x-1,z,y)


def garden_finish(m,note):
    m.meta['terrain']['种植条件']='固定模板菜畦；只在具备光照、土壤与可种植季节的背风地块使用。不得当作冰原全年农业。'
    m.meta['terrain']['水与保温']='水源已覆盖防止直接暴露；屋盖和风障只是构造表达，不声称提供模组温度或冬季生长加成。'
    return finish(m,note)


def short_plot():
    m=yard('NS-F02-v01','短畦背风家庭菜园',19,21)
    m.box((3,2,3),(15,2,17),'dirt')
    rail(m,3,3,15,3,3);m.box((3,4,3),(15,4,3),'spruce_planks')
    plot(m,'roots',6,6,6,7)
    counter(m,'sort','家庭收菜和洗选小台',5,16,6,block='water_cauldron[level=3]')
    entry(m,'entry',9,19,wall=False)
    zone(m,'plot','集中根菜短畦',5,5,12,13,'一块菜畦四周有维护路径，水源盖板在中部')
    zone(m,'work','南侧分拣工作边',4,15,14,17,'洗选台和放菜空间')
    return garden_finish(m,'小菜园只有单块有中央覆水源的短畦，北侧双高木风障，南侧洗选台；紧凑家庭产量不占整座大院。')


def walled_plot():
    m=yard('NS-F02-v02','三面挡风双菜畦',27,25)
    m.box((3,2,3),(23,2,21),'dirt')
    for a,b in [((3,3,3),(23,4,3)),((3,3,3),(3,4,21)),((23,3,3),(23,4,21))]:m.box(a,b,'spruce_planks')
    plot(m,'carrot',6,6,5,9);plot(m,'potato',16,6,5,9,'potatoes')
    counter(m,'sort','双畦收获称分台',6,19,6);m.set(18,3,19,'water_cauldron[level=3]');use(m,'water','维护取水盆',18,19,18,18)
    entry(m,'entry',13,22,wall=False)
    zone(m,'west','根菜畦与维护边',5,5,11,15,'覆水源和完整周边小径')
    zone(m,'east','块茎畦与维护边',15,5,21,15,'与根菜分畦便于轮作管理')
    zone(m,'work','南部收获维护场',5,17,21,20,'两畦共用称分台和取水，中央宽道')
    return garden_finish(m,'三面双高实木风障包围两块独立菜畦，中央宽道连接南部称分与取水，作物种类及维护路线清楚分开。')


def side_plot():
    m=yard('NS-F02-v03','工具屋旁折角菜园',29,27)
    lodge(m,3,3,12,17,height=4);entry(m,'entry',8,3)
    stores(m,'tools','锄具、绳索和苗盘',4,5,7);counter(m,'seed','育苗备料与换盆台',4,11,6)
    plot(m,'east',17,6,6,8);plot(m,'south',6,21,8,3,'potatoes')
    m.box((16,3,3),(25,4,3),'spruce_planks');m.box((25,3,3),(25,4,17),'spruce_planks')
    counter(m,'wash','菜园清洗与分筐台',18,21,6,block='water_cauldron[level=3]')
    zone(m,'tools','完整园务小屋',4,4,11,16,'工具干存、苗盘与育苗工作台')
    zone(m,'garden','侧院主菜畦',16,5,24,15,'北东挡风，工具屋在西侧')
    zone(m,'south','屋后短畦与洗选',5,19,24,24,'短畦和洗选台分边，中央搬运通路')
    return garden_finish(m,'园务小屋与两块大小不同的菜畦组成折角院，工具和育苗台在室内，主畦受屋体与北东风障保护，后边短畦独立维护。')


def warmbeds():
    m=yard('NS-F02-v04','玻璃盖温床育苗院',25,25)
    for x in (5,15):
        plot(m,'bed'+str(x),x,6,5,8)
        for zz in (5,14):m.box((x-1,3,zz),(x+5,4,zz),'spruce_planks')
        for xx in (x-1,x+5):m.box((xx,3,5),(xx,4,14),'spruce_planks')
        m.box((x,5,6),(x+4,5,13),'glass')
        # Explicit access panel interrupts the low wall beside each bed.
        m.set(x-1,3,9,'spruce_fence_gate[facing=east,in_wall=false,open=true,powered=false]');m.set(x-1,4,9,'air')
        m.meta['points'][-1]['approach']=[x-2,3,9]
    canopy(m,4,18,20,21);counter(m,'pot','苗盘、换土和种子分装',5,19,7)
    stores(m,'seed','种子及育苗用具',14,19,5)
    entry(m,'entry',12,23,wall=False)
    zone(m,'beds','并列玻璃盖低温床',4,5,20,14,'两组木框玻璃盖，侧门供维护，中央走道')
    zone(m,'work','有顶苗盘与种子工作边',5,18,19,21,'换土、育苗工具与种子干存')
    m.meta['roof_min_y']=5
    return garden_finish(m,'并列低木框玻璃盖温床与后部种子工作棚，透明上盖和侧维护口完整；这是固定育苗模板，保温功能需另行接入。')


def dry_standalone():
    m=yard('NS-F03-v01','独立双列晾网棚',23,27)
    canopy(m,4,4,18,22);entry(m,'entry',11,4,wall=False)
    drying(m,'front',5,8,12);drying(m,'back',5,16,12)
    counter(m,'check','晾后检查与折网工作台',5,20,6,block='loom[facing=south]')
    stores(m,'rope','备用绳和浮子',13,20,4)
    zone(m,'dry','两列长幅晾网',5,5,17,18,'宽架之间可展开转运，开放四周通风')
    zone(m,'check','后边折检工具带',5,19,17,21,'补结、折网与绳料存放')
    return finish(m,'独立晾网棚以两列长幅挂架为主，后侧完整折检台和绳料架，架间留宽通路，适合远离居室的网具周转。')


def dry_wall():
    m=yard('NS-F03-v02','封背贴墙鱼获棚',25,21)
    canopy(m,3,4,21,16);m.box((3,3,16),(21,6,16),'spruce_planks')
    entry(m,'entry',12,4,wall=False)
    counter(m,'wash','鱼获冲洗与分级',4,7,7,block='water_cauldron[level=3]')
    drying(m,'dry',4,12,8);drying(m,'dry2',14,12,7)
    stores(m,'baskets','鱼筐、粗盐与备用容器',14,7,6)
    zone(m,'front','前处理与容器带',4,5,20,9,'清洗分级和容器分开')
    zone(m,'back','封背通风挂鱼带',4,10,20,15,'并列短挂架，实木背墙挡主风')
    return finish(m,'完整自带背墙的鱼获棚可贴近院边独立使用，前清洗分级、后两组悬挂架；不依赖相邻建筑补齐背墙。')


def dry_long():
    m=yard('NS-F03-v03','长条木料风干棚',33,19)
    canopy(m,3,4,29,14);entry(m,'entry',16,4,wall=False)
    for i,x in enumerate((5,14,23)):
        m.box((x,3,7),(x+4,4,11),'stripped_spruce_log[axis=z]')
        for z in (7,11):m.box((x-1,3,z),(x+5,3,z),'dark_oak_log[axis=x]')
        use(m,'stack'+str(i),'分长分批船用木料垛',x+2,7,x+2,6,kind='storage')
    counter(m,'grade','干料检查、尺量与记录',5,13,7,block='cartography_table')
    zone(m,'dry','三组离地木料垛',4,5,28,12,'垫木托起木料、垛间检查路径、四面通风')
    zone(m,'check','后侧尺量登记带',4,13,13,14,'检查干料批次及出库尺寸')
    return finish(m,'低长棚针对木料风干，三组不同存取批次的垫木料垛之间留检查道，后边尺量台记录出库，不把网架改名当木料棚。')


def dry_toolroom():
    m=yard('NS-F03-v04','工具间连晾具棚',29,27)
    lodge(m,3,3,12,23,height=4);entry(m,'entry',8,3)
    stores(m,'tools','斧锯、梭具与绳索',4,5,7);counter(m,'repair','归岸工具清理与维修',4,12,6,block='grindstone[face=floor,facing=south]')
    stores(m,'ready','修妥工具与干燥雨具',4,20,7)
    canopy(m,16,5,25,22);entry(m,'shed',20,5,wall=False)
    drying(m,'wet',17,9,8);drying(m,'dry',17,17,8)
    m.set(17,3,21,'water_cauldron[level=3]');use(m,'wash','湿具清洗盆',17,21,18,21)
    zone(m,'tools','完整工具修存间',4,4,11,22,'前工具、中清理维修台、后修妥物品')
    zone(m,'shed','双阶段晾具棚',17,6,24,21,'前湿具、后较干装备分别挂晾，旁边冲洗盆')
    return finish(m,'封闭工具修存间与开敞双阶段晾具棚并列，湿具冲洗后挂晾再入修理和干存，雨具、网具及工具有不同工作位置。')


BUILDERS={f'NS-F02-v{i+1:02}':f for i,f in enumerate((short_plot,walled_plot,side_plot,warmbeds))}
BUILDERS.update({f'NS-F03-v{i+1:02}':f for i,f in enumerate((dry_standalone,dry_wall,dry_long,dry_toolroom))})
