"""Fourteen Mediterranean garden, vineyard and service-yard reference layouts."""
from .mediterranean_gardens_base import base,house,entry,zone,finish,stores,counter,use,lean,pergola,basin,bench


def plot(m,key,x,z,w,d,crop='wheat',y=2):
    m.box((x-1,0,z-1),(x+w,y,z+d),'sandstone')
    for xx in range(x,x+w):
        for zz in range(z,z+d):
            if (xx-x)%7==min(3,w//2):
                m.set(xx,y,zz,'water[level=0]');continue
            m.set(xx,y,zz,'farmland[moisture=7]')
            m.set(xx,y+1,zz,crop+'[age='+('3' if crop=='beetroots' else '7')+']')
    use(m,key,'灌溉种植与采收',x,z,x-1,z,y+1)
    zone(m,key,'灌溉作物畦',x,z,x+w-1,z+d-1,'原版成熟作物、真实耕地与就近灌溉水源',y=y+1)


def tools(m,x,z,y=3):
    counter(m,'sort','采收分选与清洗',x,z,3,y=y)
    stores(m,'seed','种子与小农具',x+5,z,3,y=y)


def farm_strip():
    m=base('WT-F02-v01','长条双畦引水菜粮园',21,34)
    plot(m,'west',4,5,5,20);plot(m,'east',13,5,4,20,'carrots')
    lean(m,4,27,16,31);tools(m,5,29)
    return finish(m,'双条种植畦夹中央铺石步道，两条明渠供水，末端横向红瓦工具棚。')


def farm_quads():
    m=base('WT-F02-v02','十字水渠四格混种园',29,30)
    for i,(x,z,c) in enumerate(((5,5,'wheat'),(17,5,'carrots'),(5,15,'potatoes'),(17,15,'beetroots'))):plot(m,'plot'+str(i),x,z,6,6,c)
    basin(m,14,13);lean(m,7,24,22,27);tools(m,8,25)
    return finish(m,'四格粮菜环绕中央蓄水槽和十字作业路，后侧低红瓦棚存种与分选。')


def farm_corner():
    m=base('WT-F02-v03','白墙院角菜园与藤檐',29,31)
    plot(m,'grain',5,5,7,7);plot(m,'veg',17,5,7,7,'carrots');plot(m,'roots',5,17,7,6,'potatoes')
    house(m,18,19,24,27);entry(m,'house',21,19);stores(m,'seed','家庭菜园种子',19,24,3)
    pergola(m,4,25,14,28);counter(m,'sort','棚下分选',6,27,4)
    return finish(m,'三块大小不同的家庭菜畦，后角白墙蓝绿百叶小屋，侧接通透藤檐，前方保留灌渠与横向步道。')


def farm_terrace():
    m=base('WT-F02-v04','三阶石砌灌溉梯田',29,34)
    for z0,z1,y in ((13,22,4),(23,31,6)):m.box((3,0,z0),(22,y,z1),'sandstone')
    for key,z,w,c,y in (('low',5,15,'wheat',2),('mid',16,15,'potatoes',4),('high',26,11,'carrots',6)):plot(m,key,5,z,w,5,c,y)
    for z,y in ((11,3),(12,4),(21,5),(22,6)):
        m.box((23,0,z),(26,y,z),'sandstone');m.box((23,y,z),(26,y,z),'sandstone_stairs[facing=south]')
    m.box((23,0,13),(26,4,20),'sandstone');m.box((23,0,23),(26,6,31),'sandstone')
    # All three levels have real irrigation water; cascade face is a static spillway.
    for z,y in ((12,4),(22,6)):
        m.box((15,y,z),(15,y,z+3),'water[level=0]')
        m.box((15,y-2,z),(15,y,z),'water[level=1]')
        m.box((15,y-2,z-2),(15,y-2,z-1),'water[level=0]')
        m.set(15,y,z+2,'spruce_slab[type=top]')
    basin(m,20,28,y=6);counter(m,'control','高位供水维护',18,30,3,y=7)
    m.meta['floors']=[dict(name='下田',y=2,max_y=5),dict(name='中田',y=4,max_y=7),dict(name='高田',y=6,max_y=10)]
    return finish(m,'三层完整石砌梯田每级抬高两格，右侧连续实阶连通；每层畦内有原版灌溉水，高位蓄水与跌水仅表达静态外形。')


def farm_grain():
    m=base('WT-F02-v05','大片谷田与临渠收获棚',32,32)
    plot(m,'grain',5,5,20,14)
    lean(m,7,24,25,28);tools(m,9,26)
    m.box((4,2,21),(27,2,21),'water[level=0]')
    m.box((14,2,21),(17,2,21),'spruce_planks')
    return finish(m,'一整片成熟麦田以后横向明渠联系宽红瓦收获棚，木板桥贯通院路，粮种与分选分台作业。')


def farm_mix():
    m=base('WT-F02-v06','混合作物与后勤供给园',30,31)
    for i,(x,z,c) in enumerate(((5,5,'wheat'),(17,5,'carrots'),(5,15,'potatoes'),(17,15,'beetroots'))):plot(m,'plot'+str(i),x,z,7,6,c)
    lean(m,5,25,19,28);tools(m,6,26);basin(m,25,26)
    m.set(23,3,23,'composter[level=0]');use(m,'compost','植物残料堆肥',23,23,24,23)
    return finish(m,'前部四块长宽不同的混合作物畦，后部横向分选棚、独立水槽与堆肥角，作业路环通。')


def vine_single():
    m=base('WT-F03-v01','贴墙长列采收藤棚',20,32)
    pergola(m,5,5,11,24)
    m.box((3,3,4),(3,6,25),'smooth_sandstone');m.box((3,7,4),(3,7,25),'brick_slab[type=bottom]')
    house(m,12,22,16,28);entry(m,'shed',14,22);stores(m,'tools','剪枝与采收工具',13,26,2)
    counter(m,'sort','葡萄装篮分选',13,7,3);use(m,'tend','棚下绑枝与养护',8,15,8,16)
    zone(m,'vine','长列葡萄棚',5,5,11,24,'暖白墙边纵向木藤架，紫晶果串静态表达')
    return finish(m,'一条长藤棚贴红瓦压顶白墙，末端独立窄工具屋，外侧通路联系分选台和采收点。')


def vine_pair():
    m=base('WT-F03-v02','双列藤棚与后分选屋',30,32)
    pergola(m,5,5,11,21);pergola(m,19,5,25,21)
    lean(m,6,25,24,29);tools(m,8,27)
    use(m,'haul','中央采收搬运道',15,14,15,15)
    zone(m,'vine','双列葡萄与宽搬运院',5,5,25,21,'两列等长藤架与中央开阔搬运路线')
    return finish(m,'左右对称轻木葡萄架夹中央宽搬运道，后方横向低红瓦分选棚，入口完全开放。')


def vine_court():
    m=base('WT-F03-v03','折角藤廊采收庭院',30,29)
    pergola(m,5,5,11,22);pergola(m,12,17,24,22)
    house(m,16,23,24,26);entry(m,'shed',20,23)
    stores(m,'tools','庭院剪枝与清洗工具',17,25,4)
    counter(m,'sort','采收分级装篮',15,8,4);basin(m,20,13)
    bench(m,13,3,15,4,wood='spruce');use(m,'tend','折角藤廊维护',8,13,8,14)
    zone(m,'court','L形采收藤庭',5,5,24,22,'藤架围合分选桌、水槽与休息席')
    return finish(m,'两段藤架呈L形围合开放采收庭，后角低屋保管器具，中央留出清洗分选与停留空间。')


def vine_terrace():
    m=base('WT-F03-v04','双阶葡萄采收台地',31,31)
    pergola(m,7,5,25,11)
    m.box((5,0,17),(27,4,28),'sandstone')
    pergola(m,7,18,19,25,y=4)
    house(m,22,21,27,27,y=4);entry(m,'shed',24,21,y=5)
    stores(m,'tools','高台修枝工具',23,25,3,y=5)
    for z,y in ((15,3),(16,4)):
        m.box((3,0,z),(6,y,z),'sandstone');m.box((3,y,z),(6,y,z),'sandstone_stairs[facing=south]')
    m.box((3,0,17),(6,4,28),'sandstone')
    counter(m,'sort','低台采收分选',8,13,4);use(m,'tend','高台葡萄维护',10,22,11,22,y=5)
    zone(m,'upper','高台藤棚',7,18,19,25,'沿真实石台地布置的上层采收通路',y=5)
    m.meta['floors']=[dict(name='低台',y=2,max_y=7),dict(name='高台',y=4,max_y=10)]
    return finish(m,'前低后高两条横向葡萄棚，左侧实阶连通高台；高台侧端白墙小屋，低台设置采收桌。')


def toolhouse():
    m=base('WT-F04-v01','白墙干库与侧修工具棚',27,24)
    house(m,4,5,13,20);entry(m,'shed',8,5)
    lean(m,16,10,23,20)
    stores(m,'dry','干燥工具分类归还',5,17,6);counter(m,'repair','修配换柄台',17,17,4)
    basin(m,17,7);use(m,'wash','器具洗涤',17,7,17,9)
    zone(m,'dry','白墙蓝绿百叶干库',5,6,12,19,'工具分类柜与中央取用路线')
    return finish(m,'短红瓦干库与右侧低檐工作棚并置，白墙蓝绿百叶、水槽和宽门前院区分干湿作业。')


def tools_long():
    m=base('WT-F04-v02','长条开敞发料工具棚',31,18)
    lean(m,4,5,26,13)
    stores(m,'long','长柄工具与用料',5,11,5);counter(m,'repair','修配与发料',13,11,5)
    stores(m,'stock','周转零件与清洁用品',21,11,4)
    zone(m,'work','长条横向服务工位',5,6,25,12,'低瓦棚沿后墙横向组织工具、维修、发料')
    return finish(m,'长条红瓦单坡檐、米白背墙与连续细木柱，三组实用工位共享前方开放搬运路。')


def tools_elbow():
    m=base('WT-F04-v03','转角干库与清洗晒网院',29,29)
    house(m,4,5,13,24);entry(m,'shed',8,5)
    lean(m,16,17,25,24)
    stores(m,'dry','干燥器具与洁净材料',5,20,6)
    counter(m,'repair','棚下修理与换柄',17,21,5)
    basin(m,22,12);use(m,'wash','清洗与晾晒前处理',22,12,22,14)
    for x in (16,24):m.box((x,3,7),(x,5,7),'oak_fence')
    m.box((16,6,7),(24,6,7),'oak_slab[type=bottom]')
    for x in (18,20,22):m.set(x,5,7,'white_wool')
    zone(m,'yard','清洗与晾晒院',15,5,25,16,'清洗槽与独立晾晒杆保持开敞搬运通道')
    return finish(m,'封闭长干库与后侧红瓦棚组成L形，院前独立晾晒杆和水槽区分湿洗、晾干与维修。')


def tools_pair():
    m=base('WT-F04-v04','双棚夹中央运具院',33,29)
    lean(m,4,5,12,23);lean(m,21,5,28,20)
    house(m,21,21,28,25);m.box((24,3,20),(26,5,20),'air');entry(m,'storedoor',25,21)
    stores(m,'dry','封闭净料库',22,23,4)
    counter(m,'repair','西棚长具修理',5,20,5)
    stores(m,'parts','东棚周转零件',22,17,5)
    counter(m,'check','归还检查',5,9,5)
    use(m,'haul','中央手推车通路',17,14,17,15)
    zone(m,'yard','双棚中央通行院',14,4,19,25,'贯通长院连接左右维修、暂存与后部小库')
    return finish(m,'两座不等长低红瓦棚分列中央搬运院，右后加独立白墙小库，形成可穿行的服务小院。')


BUILDERS={f'WT-F02-v{i:02}':f for i,f in enumerate((farm_strip,farm_quads,farm_corner,farm_terrace,farm_grain,farm_mix),1)}
BUILDERS.update({f'WT-F03-v{i:02}':f for i,f in enumerate((vine_single,vine_pair,vine_court,vine_terrace),1)})
BUILDERS.update({f'WT-F04-v{i:02}':f for i,f in enumerate((toolhouse,tools_long,tools_elbow,tools_pair),1)})
