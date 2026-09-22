"""Highland glasshouses, retained growing beds and waiting shelters."""
from functools import partial
from .cloud_navigation import base,platform,lodge,enter,desk,table,stairs_z
from .components import column,hip_roof,shelf,bench,crate_stack,pendant
from .samples import railing


def start(family,v,name,size,f=7):
    m=base(f'{family}-v{v:02d}',name,size,f=f,role='fill')
    m.meta['source']=f'tools/structure_studio/studio/cloud_food.py:{family}({v})'
    return m


def bedplot(m,key,x0,z0,x1,z1,*,f=7,crop='wheat',water_x=None):
    wx=(x0+x1)//2 if water_x is None else water_x
    m.box((x0,f-2,z0),(x1,f-1,z1),'dirt')
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            if x==wx:m.set(x,f,z,'water[level=0]')
            else:
                m.set(x,f,z,'farmland[moisture=7]');m.set(x,f+1,z,crop+'[age='+('3' if crop=='beetroots' else '7')+']')
    m.point(key,'work',(x0,f+1,z0),'种植维护与采收',approach=(x0,f+1,z0-1))
    m.room(key,'有支撑灌溉种植床',(x0,f,z0),(x1,f+2,z1),'土层、原版作物、同高水源与外侧维护路径')


def glasshouse(m,key,x0,z0,x1,z1,*,f=7,crop='wheat',door=None):
    m.box((x0,f+1,z0),(x1,f+14,z1),'air')
    m.box((x0,f,z0),(x1,f,z1),'stone_bricks')
    for x in range(x0,x1+1):
        for z in (z0,z1):
            m.set(x,f+1,z,'stone_bricks');m.box((x,f+2,z),(x,f+5,z),'glass')
    for x in (x0,x1):
        m.box((x,f+1,z0),(x,f+1,z1),'stone_bricks');m.box((x,f+2,z0),(x,f+5,z1),'glass')
    for z in range(z0,z1+1,5):
        for x in (x0,x1):column(m,x,z,f+1,f+5,'stripped_dark_oak_log','dark_oak_planks')
    cx=(x0+x1)//2
    for x in range(x0,x1+1):
        yy=f+6+min(x-x0,x1-x)//2
        for z in range(z0,z1+1):m.set(x,yy,z,'waxed_weathered_cut_copper' if (z-z0)%5==0 or x==cx else 'glass')
        for z in (z0,z1):
            if yy>f+6:m.box((x,f+6,z),(x,yy-1,z),'glass')
    if door is None:door=(cx,z0,'north')
    m.door(door[0],f+1,door[1],'birch',door[2])
    # The three-wide central aisle and cross aisle remain paved.
    for side,(xa,xb) in enumerate(((x0+2,cx-2),(cx+2,x1-2))):
        if xb>=xa:bedplot(m,f'{key}_{side}',xa,z0+4,xb,z1-3,f=f,crop=crop)
    m.set(x0+1,f+1,z1-1,'composter[level=0]');m.set(x1-1,f+1,z1-1,'barrel[facing=south]')
    m.point(key+'_seed','storage',(x1-1,f+1,z1-1),'种子与工具箱',approach=(x1-2,f+1,z1-1))
    m.room(key,'高原玻璃种植室',(x0+1,f+1,z0+1),(x1-1,f+5,z1-1),'完整玻璃屋盖、铜木框架、中间通道、种植床及种子工具')
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],f+6)
    if len(m.meta['floors'])==1 and m.meta['floors'][0]['y']==f:m.meta['floors'][0]['max_y']=min(m.meta['floors'][0]['max_y'],f+5)


def steps(m,x,z,f,rise=4):
    stairs_z(m,x,z,f+1,3,rise)
    for xx in (x-1,x+3):
        for i in range(rise):
            m.box((xx,f,z+i),(xx,f+1+i,z+i),'dark_oak_planks')
            m.box((xx,f+2+i,z+i),(xx,f+3+i,z+i),'dark_oak_fence[north=true,south=true,east=false,west=false]')


def greenhouse(v):
    names=['短脊麦苗室','长条根蔬室','折庭育种院','分台双温室']
    sizes=[(33,31,37),(31,32,49),(49,33,45),(51,37,45)]
    m=start('CN-07',v,names[v-1]+' · 高原温室',sizes[v-1]);w,_,d=m.size;platform(m,3,2,w-4,d-4)
    if v==1:
        glasshouse(m,'glass',7,8,25,29,crop='wheat')
        desk(m,'packing',8,8,31,'育苗与装筐',9,block='crafting_table');note='短宽温室采用双侧麦苗床与三格中央通道，后部在支撑台上整理种苗。'
    elif v==2:
        glasshouse(m,'glass',7,7,23,39,crop='carrots')
        desk(m,'packing',8,8,42,'根蔬清点与留种',10,block='crafting_table');note='窄长地块上的长脊胡萝卜温室，双长床和连续中轴通路；后端独立分拣面。'
    elif v==3:
        glasshouse(m,'west',6,7,22,33,crop='potatoes');glasshouse(m,'east',28,21,44,37,crop='beetroots')
        lodge(m,28,7,43,16,h=5);m.door(35,8,7,'birch','north');desk(m,'seeds',30,8,11,'育种登记与工具',9);shelf(m,29,8,15,9,'birch')
        m.room('office','温室管理与储种间',(29,8,8),(42,12,15),'留种、记录、工具与清理水盆');note='折形双种植翼围绕小型管理庭，马铃薯与甜菜分开，东北管理房储种。'
    else:
        glasshouse(m,'low',6,7,22,35,crop='carrots')
        platform(m,31,9,46,39,11);glasshouse(m,'high',33,12,44,35,f=11,crop='wheat',door=(33,24,'west'))
        steps(m,26,18,7);m.box((26,11,22),(32,11,27),'birch_planks')
        railing(m,(26,12,27),(32,12,27),'dark_oak','x');m.box((31,12,23),(31,14,26),'air')
        m.meta['floors']=[dict(name='低台根蔬温室',y=7,max_y=12),dict(name='高台麦苗温室',y=11,max_y=16)]
        note='两个真实岩台高差四格，根蔬低室和麦苗高室用外阶及短廊相连，每座温室独立落墩。'
    enter(m,(w-1)//2,3);m.meta['agriculture']=dict(crops=['wheat','carrots','potatoes','beetroots'],irrigation='种植床同高水渠，最终NBT另核对4格水源范围',climate='玻璃屋盖表达防风保温；未模拟实际温度、结冰和生长，寒冷地图须另做环境适用性核对')
    m.meta['design_notes']=[note,'温室种植床的土层由梁板与石墩承接；水体封在床槽内，真实灌溉/防冻/生产不属于离线验收。'];m.meta['differences']=[note]
    return m


def farm(v):
    names=['窄岩沿麦田','双台胡萝卜畦','九宫混植槽','折边甜菜圃','种粮小庭院','阶带轮作田']
    sizes=[(25,23,41),(43,28,39),(39,24,39),(39,25,39),(43,29,43),(43,31,47)]
    m=start('CN-F02',v,names[v-1]+' · 高原田槽',sizes[v-1],f=4);w,_,d=m.size;platform(m,3,2,w-4,d-4,4)
    m.meta['preview_context']['surface']='grass';m.meta['terrain']['气候']='仅在无持续霜冻、有适宜光照与灌溉的高原生长季使用；严寒条件应改选温室。'
    if v==1:
        bedplot(m,'west',6,8,11,32,f=4,crop='wheat',water_x=8);bedplot(m,'east',15,8,19,32,f=4,crop='wheat',water_x=17)
        note='狭长岩沿采用两条麦田，中间三格通路，前后横向端路与连续边栏封边。'
    elif v==2:
        bedplot(m,'low',6,8,14,30,f=4,crop='carrots',water_x=10)
        platform(m,24,8,38,33,8);bedplot(m,'high',27,12,35,29,f=8,crop='carrots',water_x=31)
        steps(m,19,13,4);m.box((19,8,17),(26,8,21),'birch_planks');m.box((24,9,18),(24,11,20),'air')
        railing(m,(19,9,21),(26,9,21),'dark_oak','x');note='两块胡萝卜畦分别落在高差四格的实体台地，床边水渠独立、维护廊通过四级外阶连接。'
        m.meta['floors']=[dict(name='低台菜畦',y=4,max_y=7),dict(name='高台菜畦',y=8,max_y=11)]
    elif v==3:
        for iz,z in enumerate((8,18,28)):
            for ix,x in enumerate((6,17,28)):bedplot(m,f'plot_{ix}_{iz}',x,z,x+4,z+4,f=4,crop=('wheat','carrots','potatoes')[(ix+iz)%3],water_x=x+2)
        note='九个独立小型槽床构成网格通路，各有封闭水源；适合多户共用菜槽，作物交错而非单一大田。'
    elif v==4:
        bedplot(m,'north',7,8,31,12,f=4,crop='beetroots',water_x=19)
        # Long narrow north bed receives a full water stripe rather than a distant point source.
        for x in range(7,32):m.set(x,4,10,'water[level=0]');m.set(x,5,10,'air')
        bedplot(m,'west',7,17,12,31,f=4,crop='beetroots',water_x=9)
        for x,z in ((22,20),(28,26)):m.set(x,5,z,'composter[level=0]')
        bench(m,21,5,31,8,'north','birch');note='L形甜菜床沿两边围合维护小庭，内角设堆肥和歇脚位置；长边使用横向水渠覆盖整条。'
    elif v==5:
        bedplot(m,'west',6,9,14,31,f=4,crop='potatoes',water_x=10);bedplot(m,'east',26,9,34,31,f=4,crop='wheat',water_x=30)
        lodge(m,15,27,25,37,f=4,h=5);m.door(20,5,27,'birch','north');desk(m,'seeds',17,5,31,'种粮留样和农具',6,block='crafting_table')
        shelf(m,16,5,36,6,'birch');m.room('tool','田边工具与留种间',(16,5,28),(24,9,36),'小型农具、留种记录与防雨收纳');note='中央通路把马铃薯和麦田分开，后端带一间独立农具留种房，形成可独立使用的小庭院。'
    else:
        for idx,(z,f,crop) in enumerate(((8,4,'wheat'),(21,6,'carrots'),(34,8,'beetroots'))):
            m.box((6,0,z-2),(32,f,z+7),'stone_bricks')
            bedplot(m,'tier'+str(idx),8,z,30,z+5,f=f,crop=crop,water_x=19)
            for x in range(8,31):m.set(x,f,z+2,'water[level=0]');m.set(x,f+1,z+2,'air')
            if idx:
                steps(m,35,z-6,f-2,rise=2);m.box((33,f,z-4),(37,f,z+5),'birch_planks')
                m.box((31,f,z-1),(36,f,z-1),'birch_planks')
        m.box((35,4,3),(37,4,14),'birch_planks');m.box((35,4,14),(37,4,14),'birch_planks')
        m.box((35,6,17),(37,6,27),'birch_planks')
        m.meta['floors']=[dict(name='下层麦田',y=4,max_y=7),dict(name='中上层轮作',y=6,max_y=11)]
        note='三道横向石砌阶带按两格级差上升，作物轮作与横向水渠独立；东侧维护阶道串联各级。'
    m.set(w-6,5,5,'composter[level=0]');m.point('compost','work',(w-6,5,5),'堆肥与维护工具',approach=(w-7,5,5));enter(m,(w-1)//2,3,f=4)
    m.meta.update(roof_min_y=min(m.meta['roof_min_y'],12),agriculture=dict(crops=['wheat','carrots','potatoes','beetroots'],irrigation='每个独立槽有同高封闭水渠；最终NBT重新核对支撑和四格范围水源'))
    m.meta['design_notes']=[note,'固定模板田槽，农业区土层、挡土和水渠均在模板中；不代替Landscape自然大田。露天田只用于适宜生长季。'];m.meta['differences']=[note]
    return m


def shelter(v):
    names=['背风单侧候棚','贯通行李长棚','折角换乘棚','附仓寄物棚']
    sizes=[(29,28,29),(29,30,43),(43,29,37),(39,30,37)]
    m=start('CN-F03',v,names[v-1]+' · 候行货棚',sizes[v-1]);w,_,d=m.size;platform(m,3,2,w-4,d-4)
    def cover(x0,z0,x1,z1,key):
        for x in (x0,x1):
            for z in (z0,z1):column(m,x,z,8,17,'stripped_dark_oak_log','dark_oak_planks')
        hip_roof(m,x0-1,x1+1,z0-1,z1+1,16,material='waxed_cut_copper',tiers=min(4,(x1-x0)//2,(z1-z0)//2))
        for z in (z0,z1):m.box((x0,14,z),(x1,14,z),'dark_oak_log[axis=x]')
        for x in (x0,x1):m.box((x,14,z0),(x,14,z1),'dark_oak_log[axis=z]')
        pendant(m,(x0+x1)//2,13,(z0+z1)//2,16)
        m.room(key,'候行与遮雨空间',(x0+1,8,z0+1),(x1-1,14,z1-1),'有顶步行、歇坐、旅具和寄存交接')
    if v==1:
        cover(7,8,21,22,'wait');m.box((8,8,22),(20,11,22),'white_terracotta')
        bench(m,10,8,20,9,'north','birch');shelf(m,8,8,10,6,'birch');m.point('wait','circulation',(16,8,17),'背风候行席',look_at=[14,9,20])
        note='南侧半高挡风墙与朝北单侧候行区，长凳和行李架分列两端，适合背风的岩台小路口。'
    elif v==2:
        cover(7,7,21,35,'wait');bench(m,9,8,12,4,'south','birch');bench(m,15,8,29,4,'north','birch')
        crate_stack(m,9,8,28,3,4,2);m.point('bags','storage',(10,8,28),'寄存小货',approach=(10,8,27));m.point('through','circulation',(14,8,21),'贯通换乘通道',look_at=[14,9,35])
        note='窄长贯通棚保持中央三格行进通道，候行座位和寄存货物错位放置，南北均可接续步道。'
        m.box((13,8,39),(15,11,39),'air');enter(m,14,39,key='south_exit',name='南侧贯通步道接口',face='south')
    elif v==3:
        cover(7,7,19,29,'west');cover(23,17,35,29,'east')
        bench(m,9,8,25,7,'north','birch');desk(m,'dispatch',25,8,22,'转运点名与寄物',7);crate_stack(m,25,8,27,4,2,2)
        m.point('turn','circulation',(21,8,19),'折角换乘中庭',look_at=[27,9,23]);note='长候行翼与短寄货翼折向围合转角中庭，候船与小货交接分开，适合两条步道汇合点。'
    else:
        cover(6,7,21,29,'wait');lodge(m,25,12,33,28,h=6);m.door(25,8,20,'birch','west')
        crate_stack(m,27,8,23,4,3,3);shelf(m,26,8,13,5,'birch');m.point('store','storage',(28,8,23),'封闭寄物仓',approach=(28,8,22))
        m.room('store','附属封闭寄物室',(26,8,13),(32,13,27),'分层行李箱、较大寄件和独立房门')
        bench(m,8,8,24,9,'north','birch');desk(m,'counter',9,8,13,'寄存领取台',8)
        note='开放候行棚旁带封闭小寄物间，室外办理与室内存放独立，保留宽敞中间连接廊。'
    enter(m,(w-1)//2,3);m.meta['design_notes']=[note,'屋面与梁柱完整落在带边栏的支撑平台；不能放进飞空艇接驳净空，具体风向和货物流量另核对。'];m.meta['differences']=[note]
    return m


BUILDERS={**{f'CN-07-v{v:02d}':partial(greenhouse,v) for v in range(1,5)},**{f'CN-F02-v{v:02d}':partial(farm,v) for v in range(1,7)},**{f'CN-F03-v{v:02d}':partial(shelter,v) for v in range(1,5)}}
