"""Gothic civic institutions and historic sites, authored as independent plans."""
from .model import Model
from .components import shell,bench,pendant,shelf


def base(key,name,w,d,h=32,role='key'):
    m=Model(key,name,(w,h,d),family=key.split('-v')[0],civilization='哥特',role=role,terrain={
        '选址':'排水良好的稳定台地，连接仍有常住居民的聚落道路；纪念设施不替代活人的生活供给。',
        '高程':'基础底 Y=0，公共地面 Y=2，步行脚底 Y=3；道路接入口同高。',
        '保留空间':'独立完整模板，保留外扶壁、彩窗采光面及庭院；不可用相邻建筑遮挡。',
        '构造':'尖拱、外扶壁与内肋共同表达高挑空间；不模拟结构受力、照度或天气。'})
    m.box((1,0,1),(w-2,2,d-2),'stone_bricks');m.box((1,2,1),(w-2,2,d-2),'smooth_stone')
    m.box((1,3,1),(w-2,h-1,d-2),'air')
    m.meta.update(source='tools/structure_studio/studio/memorial_lanterns.py:BUILDERS',roof_min_y=13,
        floors=[dict(name='公共与作业层',y=2,max_y=11)],preview_context=dict(kind='flat',land_surface_y=3,padding=5,surface='grass'))
    return m


def lancet(m,c,z,y=5,axis='x',glass=True,color='blue'):
    # Five-wide pointed light: the central apex rises two blocks above shoulders.
    for u in range(-2,3):
        top=y+5-abs(u)
        for yy in range(y,top+1):
            block='polished_andesite' if abs(u)==2 or yy==top else (f'{color}_stained_glass' if glass else 'air')
            if glass and u==0 and yy<top:block='yellow_stained_glass'
            x,zz=(c+u,z) if axis=='x' else (c,z+u)
            m.set(x,yy,zz,block)


def hall(m,x0,z0,x1,z1,tall=10):
    top=2+tall
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],top+1)
    shell(m,(x0,2,z0),(x1,top,z1),'stone_bricks','dark_oak_planks')
    for x in (x0,x1):
        m.box((x,top,z0),(x,top,z1),'polished_andesite')
        for z in range(z0+3,z1-1,6):lancet(m,x,z,axis='z',color='cyan' if z%2 else 'purple')
        for z in range(z0,z1+1,6):
            outward=-1 if x==x0 else 1
            for offset,height in ((1,top-1),(2,6)):
                m.box((x+offset*outward,2,z),(x+offset*outward,height,z),'stone_bricks')
                m.set(x+offset*outward,height+1,z,'stone_brick_slab[type=bottom,waterlogged=false]')
            m.box((x,top+1,z),(x,top+3,z),'polished_andesite')
            m.set(x,top+4,z,'stone_brick_wall')
    center=(x0+x1)//2
    for i in range((x1-x0)//2+1):
        a,b=x0+i,x1-i;yy=top+1+i
        for z in range(z0-1,z1+2):
            for x,facing in ((a,'east'),(b,'west')):m.set(x,yy,z,f'deepslate_tile_stairs[facing={facing},half=bottom,shape=straight,waterlogged=false]')
        for z in (z0,z1):m.box((a,top+1,z),(b,yy,z),'stone_bricks')
        # Exposed transverse stone ribs below the dark roof shell.
        for z in range(z0+3,z1,6):
            for x in (a,b):m.set(x,yy-1,z,'polished_andesite')
    ridge=top+1+(x1-x0)//2
    m.box((center,ridge,z0-1),(center,ridge,z1+1),'deepslate_tiles')
    for z in (z0,z1):
        lancet(m,center,z,y=top-3,color='magenta')
        m.box((center,ridge+1,z),(center,ridge+3,z),'polished_andesite');m.set(center,ridge+4,z,'stone_brick_wall')
    for z in range(z0+5,z1,8):pendant(m,center,8,z,ridge)


def entry(m,key,x,z):
    lancet(m,x,z,y=3,glass=False)
    m.door(x,3,z,wood='dark_oak',facing='north')
    for xx in (x-1,x+1):m.box((xx,3,z),(xx,4,z),'stone_bricks')
    m.point(key,'entrance',(x,3,z-1),'尖拱门入口')


def use(m,key,name,x,z,ax=None,az=None,kind='work',y=3):
    m.point(key,kind,(x,y,z),name,approach=(x if ax is None else ax,y,z+1 if az is None else az),look_at=[x,y+1,z])


def desk(m,key,name,x,z,n=4,block='crafting_table'):
    m.box((x,3,z),(x+n-1,3,z),'dark_oak_planks');m.set(x,3,z,block)
    m.set(x+n-1,4,z,'lantern');use(m,key,name,x,z)


def store(m,key,name,x,z,n=4,books=False):
    shelf(m,x,3,z,n,material='dark_oak',contents='bookshelf' if books else 'barrel')
    use(m,key,name,x,z,kind='storage')


def bed(m,key,x,z,color='white'):
    m.bed(x,3,z,color);m.set(x,3,z+2,'barrel[facing=up]');use(m,key,'床位、床侧护理与个人用品',x,z,x+1,z,kind='bed')


def partition(m,x0,z,x1,door):
    m.box((x0,3,z),(x1,7,z),'stone_bricks');m.door(door,3,z,wood='dark_oak')


def zone(m,key,name,x0,z0,x1,z1,purpose):m.room(key,name,(x0,3,z0),(x1,10,z1),purpose)


def meals(m,x,z):
    desk(m,'kitchen'+str(x)+str(z),'热食、备餐与洗涤',x,z,4,'smoker[facing=south,lit=false]')
    m.set(x+2,3,z,'water_cauldron[level=3]')
    # Separate nonflammable flue, terminating above this local roof.
    yy=max(y for (xx,y,zz),b in m.blocks.items() if xx==x and zz==z and b[0]!='minecraft:air')+1
    m.box((x,4,z),(x,yy,z),'stone_bricks')
    m.box((x,3,z+4),(x+3,3,z+4),'dark_oak_planks');bench(m,x,3,z+6,4,wood='dark_oak')


def garden(m,x,z,w,d):
    m.box((x,2,z),(x+w-1,2,z+d-1),'moss_block')
    for xx in (x,x+w-1):
        for zz in (z,z+d-1):m.set(xx,3,zz,'flowering_azalea')
    m.box((x+w//2,3,z+d//2),(x+w//2,5,z+d//2),'chiseled_stone_bricks')
    m.set(x+w//2,6,z+d//2,'lantern')


def finish(m,note):
    m.meta['design_notes']=[note];m.meta['differences']=[note]
    for r in m.meta['rooms']:
        names=[p['name'] for p in m.meta['points'] if all(r['min'][i]<=p['pos'][i]<=r['max'][i] for i in range(3))]
        if names:r['purpose']+='；设施：'+'、'.join(names)
    return m


def library():
    m=base('ML-01-v01','尖拱纪念书库与抄录院',43,39)
    hall(m,6,5,22,33);hall(m,28,17,38,33,tall=8);entry(m,'entry',14,5);entry(m,'adminentry',33,17)
    partition(m,7,23,21,14)
    store(m,'westbooks','地方记忆与家系书架',7,9,5,True);store(m,'eastbooks','公众借阅与历史著录',16,9,5,True)
    desk(m,'catalogue','借阅登记及归还核对',7,6,5,'lectern[facing=south,has_book=false]')
    for z in (15,19):
        desk(m,'read'+str(z),'读者阅览抄录长桌',10,z,9,'lectern[facing=south,has_book=false]');bench(m,11,3,z+2,7,wood='dark_oak')
    store(m,'archive','封存原始档案与骨藏记录',7,30,5,True);store(m,'relics','纪念物编号保管柜',16,30,5)
    desk(m,'memorial','骨藏纪念物登记与祭奠台',9,26,9,'chiseled_stone_bricks')
    for x in (10,13,16):m.set(x,4,26,'flower_pot')
    partition(m,29,26,37,35);desk(m,'restore','档案修复和装订',29,20,7,'cartography_table');store(m,'supplies','纸墨与修复工具',29,24,5)
    bed(m,'keeper',29,30);desk(m,'keeperdesk','管理员交班与饮水',33,29,4);m.set(35,3,29,'water_cauldron[level=3]')
    garden(m,29,6,8,7);bench(m,29,3,14,6,wood='dark_oak');m.point('court','circulation',(26,3,11),'纪念庭院旁公共步道',look_at=[33,5,9])
    zone(m,'reading','公共目录与阅览厅',7,6,21,22,'前登记、分类书架和双组阅览长桌')
    zone(m,'archive','受控骨藏纪念与档案室',7,24,21,32,'祭奠和编号保管分开，骨藏使用封存柜表达而非暴露遗骨')
    zone(m,'repair','修复抄录工作室',29,18,37,25,'纸墨干存、修复装订和登记')
    zone(m,'keeper','管理员轮休间',29,27,37,32,'床柜、交班台与饮水')
    zone(m,'court','读者纪念庭院',25,5,39,15,'树丛、纪念石灯、休息长凳与独立通道')
    return finish(m,'高挑尖拱书库配低翼修复室和独立纪念庭院；前公众阅览、后受控档案纪念、侧管理员生活明确分区。浅石扶壁和彩窗强调哥特构造，骨藏是文化档案用途。')


def hospital():
    m=base('ML-10-v01','双翼彩窗医馆与康复庭',47,41)
    hall(m,6,5,18,35,tall=8);hall(m,30,5,42,35,tall=8)
    entry(m,'wardentry',12,5);entry(m,'clinicentry',36,5)
    partition(m,7,13,17,12);partition(m,7,26,17,12)
    desk(m,'admit','住院登记、家属交接',7,8,5,'lectern[facing=south,has_book=false]');bench(m,14,3,10,3,wood='dark_oak')
    for i,(x,z) in enumerate(((8,17),(14,17),(8,22),(14,22))):bed(m,'patient'+str(i),x,z)
    store(m,'linen','干净被服与护理备用品',8,24,3)
    bed(m,'staff',8,32,'light_blue');desk(m,'staffdesk','护理交班台',13,29,4);store(m,'staffgear','工作人员换衣柜',13,33,3)
    partition(m,31,14,41,36);partition(m,31,25,41,36)
    desk(m,'triage','门诊登记与初诊',31,8,6,'lectern[facing=south,has_book=false]');bench(m,32,3,11,7,wood='dark_oak')
    bed(m,'exam',32,19);desk(m,'medicine','药材分拣与配药台',36,18,5,'brewing_stand');store(m,'herbs','分类干药材与器材',35,23,6)
    meals(m,31,28);store(m,'food','工作人员与患者食物储备',37,33,3)
    garden(m,22,12,5,15);bench(m,21,3,8,7,wood='dark_oak');bench(m,21,3,31,7,wood='dark_oak')
    desk(m,'wash','院内洗手与饮水点',21,35,6,'water_cauldron[level=3]')
    m.point('rehab','circulation',(24,3,29),'康复庭环绕步道',look_at=[24,5,19])
    zone(m,'admit','家属等候与入院',7,6,17,12,'登记柜和候诊长凳，不占病区床侧')
    zone(m,'ward','四床休养病区',7,14,17,25,'四组床侧护理面、个人用品与清洁被服')
    zone(m,'staff','护理轮休生活间',7,27,17,34,'护理交班、换衣与轮休床柜')
    zone(m,'clinic','公众门诊候诊厅',31,6,41,13,'初诊登记、候诊长凳及转诊通路')
    zone(m,'treatment','诊疗配药室',31,15,41,24,'诊床与药材配制分边，药材干存')
    zone(m,'kitchen','医馆备餐间',31,26,41,34,'患者与员工热食、洗涤、食物保管及餐席')
    zone(m,'court','康复纪念庭院',20,6,28,36,'庭院环道、树丛和休息长凳，支持活人休养')
    m.meta['terrain']['卫生']='近常住区与洁净水源，院内饮水为静态容器；污物处置和医学玩法需运行时另行接入。'
    return finish(m,'医馆为双翼窄厅围合康复庭院；一翼入院、四床护理与员工轮休，另一翼门诊、诊床配药与完整备餐。尖窗、扶壁和石肋服务安静采光空间，医疗对象是正常居民。')


def carriage(m,x,z):
    for xx in (x,x+4):
        for zz in (z,z+6):m.set(xx,3,zz,'polished_blackstone')
    m.box((x+1,4,z),(x+3,4,z+6),'dark_oak_planks')
    m.box((x+1,5,z+2),(x+3,5,z+5),'polished_andesite')
    m.box((x+1,6,z+2),(x+3,6,z+5),'stone_brick_slab[type=bottom,waterlogged=false]')
    m.box((x+1,3,z-3),(x+1,3,z-1),'dark_oak_fence[east=false,west=false,north=true,south=true]')
    m.box((x+3,3,z-3),(x+3,3,z-1),'dark_oak_fence[east=false,west=false,north=true,south=true]')


def station():
    m=base('ML-02-v01','送葬车站与双湾候车院',47,45)
    hall(m,6,6,24,38);entry(m,'entry',15,6)
    partition(m,7,18,23,15);partition(m,7,29,23,15)
    desk(m,'tickets','仪程登记与车次交接',7,9,6,'lectern[facing=south,has_book=false]')
    for z in (12,15):bench(m,16,3,z,6,wood='dark_oak')
    store(m,'luggage','随行衣物与小行李寄存',7,15,5)
    desk(m,'prepare','仪式用品和纪念物整理',7,22,7);store(m,'cloth','礼布、烛台与干燥备用品',17,25,6)
    desk(m,'dispatch','站务调度和路册',7,32,5,'cartography_table');bed(m,'driver',19,34,'gray')
    m.set(12,3,35,'water_cauldron[level=3]');use(m,'drink','车夫饮水与清洁',12,35)
    # Two wide pointed carriage openings, a sheltered transfer gallery and open turning road.
    for z in (12,28):
        for x in (29,37):
            for zz in (z,z+12):m.box((x,2,zz),(x,8,zz),'stone_bricks')
        for i in range(5):
            for x in (29+i,37-i):m.box((x,8+i if i else 9,z),(x,9+i,z+12),'deepslate_tiles')
        carriage(m,31,z+4)
        use(m,'load'+str(z),'送葬车侧面移交与检修',31,z+6,29,z+6)
    m.box((40,2,2),(44,2,42),'cobblestone')
    garden(m,29,3,8,5);m.point('drive','circulation',(42,3,5),'车行连接和转向留空',look_at=[35,5,20])
    zone(m,'wait','家属候行与行李登记',7,7,23,17,'候车长凳、路册交接和行李寄存')
    zone(m,'prepare','送行仪式准备厅',7,19,23,28,'用品整理、礼布干存及安静等待')
    zone(m,'dispatch','站务轮休室',7,30,23,37,'调度、路线记录、车夫轮休与饮水')
    zone(m,'yard','双湾车棚与移交院',27,11,39,41,'车辆静态展示，车侧交接及分批候行')
    m.meta['terrain']['交通']='使用地面牵引车交通，不假定铁路。东侧 X=40～44 的5格车道需两端接通并留外部转弯场；展示车辆非可驾驶实体。'
    return finish(m,'送葬车站以候行、仪式准备、站务三段长厅配两湾尖顶车棚；东侧连续车行道和车侧移交面独立，不把车站当祭坛或假设不存在的轨道。')


def council():
    m=base('ML-05-v01','祖先议事庭与双侧公档翼',47,41)
    hall(m,15,5,31,35);hall(m,5,19,11,35,tall=8);hall(m,35,19,41,35,tall=8)
    entry(m,'entry',23,5);entry(m,'archiveentry',8,19);entry(m,'serviceentry',38,19)
    for z in (9,12):
        bench(m,17,3,z,4,wood='dark_oak');bench(m,25,3,z,4,wood='dark_oak')
    m.box((18,3,18),(18,3,26),'dark_oak_planks');m.box((28,3,18),(28,3,26),'dark_oak_planks')
    desk(m,'chair','公开议事主持与文书',19,29,9,'lectern[facing=south,has_book=false]')
    for z in (19,23,26):
        m.set(17,3,z,'dark_oak_stairs[facing=east]');m.set(29,3,z,'dark_oak_stairs[facing=west]')
    m.point('debate','circulation',(23,3,22),'中央陈述与公共讨论位',look_at=[23,4,29])
    desk(m,'memorial','祖先纪念与公共誓约石台',20,33,7,'chiseled_stone_bricks')
    store(m,'minutes','历次议事记录',6,23,4,True);desk(m,'copy','公档查阅抄录',6,29,4,'lectern[facing=south,has_book=false]')
    store(m,'documents','待公布决议与文具',36,23,4);desk(m,'service','公众申请与书记交接',36,29,4)
    garden(m,5,6,7,8);garden(m,35,6,7,8)
    m.point('publiccourt','circulation',(23,3,3),'公众集会前庭',look_at=[23,12,5])
    zone(m,'public','开放旁听席',16,6,30,15,'四组旁听长凳和中央进入通道')
    zone(m,'debate','议事与纪念主庭',16,16,30,34,'两侧代表席、中央陈述、主持记录和祖先誓约台')
    zone(m,'archive','公共档案侧翼',6,20,10,34,'议事记录与查阅抄录')
    zone(m,'clerk','书记事务侧翼',36,20,40,34,'公众申请、决议公布和文具保管')
    return finish(m,'主庭采用中央陈述、两侧议事桌、前旁听后主持的公共制度布局；双侧低翼分别保存公档与办理事务，前庭树丛围绕开放集会口。')


def mausoleum():
    m=base('ML-11-v01','下沉旧陵寝与守护前厅',39,47,role='structure')
    # Buried margins and an upper approach terrace make the underground range explicit.
    m.box((2,3,2),(36,8,11),'stone_bricks')
    for x0,x1 in ((2,9),(29,36)):m.box((x0,3,12),(x1,8,43),'stone')
    hall(m,10,20,28,41,tall=8)
    for i in range(6):
        z=11+i;y=8-i;m.box((17,0,z),(21,y-1,z),'stone_bricks')
        m.box((17,y,z),(21,y,z),'stone_brick_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]')
        m.box((16,3,z),(16,y+1,z),'stone_bricks');m.box((22,3,z),(22,y+1,z),'stone_bricks')
    # Wide lower landing to ceremonial entry. Upper terrace is the only external entrance.
    lancet(m,19,20,y=3,glass=False);m.door(19,3,20,wood='dark_oak')
    for x in (18,20):m.box((x,3,20),(x,4,20),'stone_bricks')
    m.point('entry','entrance',(19,9,7),'旧路高台入口')
    partition(m,11,28,27,19)
    desk(m,'offer','仪式清洁与祭品准备',11,23,5);store(m,'guardian','守护灯具与历史记录',22,25,5)
    for x in (12,23):
        m.box((x,3,33),(x+3,4,37),'chiseled_stone_bricks');m.box((x,5,33),(x+3,5,37),'stone_brick_slab[type=bottom,waterlogged=false]')
        use(m,'tomb'+str(x),'封闭石棺纪念位',x,35,x-1,35)
    desk(m,'inscription','碑文记录与纪念台',16,39,7,'lectern[facing=south,has_book=false]')
    m.point('landing','circulation',(19,3,18),'下沉阶梯底部转身平台',look_at=[19,7,20])
    m.point('descent','circulation',(19,9,10),'下沉阶梯上口观察与转向',look_at=[19,3,18])
    m.room('stairs','高台与连续下沉石阶',(16,3,7),(22,10,19),'六级宽石阶、两侧护边与底部转身平台')
    zone(m,'guard','仪式与守护前厅',11,21,27,27,'祭品整理、旧灯具和档案')
    zone(m,'burial','下沉双棺纪念室',11,29,27,40,'两侧封闭石棺，中央历史参观通路')
    m.meta.update(floors=[dict(name='下沉陵寝层',y=2,max_y=10),dict(name='旧路高台',y=8,max_y=12)])
    m.meta['preview_context'].update(kind='slope',land_surface_y=9,slope_origin_z=0,run=8,rise=-1)
    m.meta['terrain']['地下']='前旧路脚底 Y=9，经6级台阶下至 Y=3。地下保留范围 X=10～28、Z=20～41、Y=2～10；两侧围土不得侵入彩窗和内部通路。'
    return finish(m,'旧陵寝的旧路高台经连续实阶下降到礼仪前厅和双棺纪念室，明确地下范围；不做无出口迷宫，历史纪念、守护用品和碑文记录均有可达位置。')


def sealed_gate():
    m=base('ML-12-v01','封闭墓区旧门与值守遗存',39,37,role='structure')
    hall(m,5,5,15,25,tall=8);entry(m,'entry',10,5)
    partition(m,6,16,14,11);desk(m,'register','旧守门登记与墓籍记录',6,9,7,'lectern[facing=south,has_book=false]')
    store(m,'tools','旧灯具与维护用品',6,13,4);bed(m,'keeper',7,22,'gray');desk(m,'water','旧值守饮水与私人物品',11,20,3,'water_cauldron[level=3]')
    m.box((20,2,4),(31,2,32),'mossy_stone_bricks')
    m.box((18,3,25),(34,11,27),'stone_bricks')
    lancet(m,26,25,y=3,glass=False)
    # Sealed arch deliberately remains impassable; no required point behind this barrier.
    m.box((25,3,25),(27,6,25),'iron_bars[east=true,west=true,north=false,south=false]')
    for x in (22,30):
        m.box((x,2,24),(x,15,28),'stone_bricks');m.set(x,16,26,'stone_brick_wall')
    for i in range(5):m.box((22+i,12+i,25),(30-i,12+i,27),'deepslate_tiles')
    m.box((19,3,28),(33,4,31),'mossy_cobblestone')
    for x,z in ((21,29),(30,30),(25,31)):m.box((x,5,z),(x+1,6,z),'cracked_stone_bricks')
    garden(m,22,8,8,9);bench(m,22,3,19,7,wood='dark_oak')
    desk(m,'notice','封闭告示与历史路径说明',19,22,4,'lectern[facing=south,has_book=false]')
    m.point('barrier','circulation',(26,3,23),'封闭铁栅前止步线',look_at=[26,6,25])
    zone(m,'office','旧值守登记室',6,6,14,15,'墓籍与维护工具遗存')
    zone(m,'rest','值守轮休遗存',6,17,14,24,'旧床柜与饮水，保留完整生活痕迹')
    zone(m,'approach','可达旧径与纪念小庭',18,5,33,24,'旧路、休息长凳、关闭告示与止步位置')
    m.meta['terrain']['封闭边界']='Z=25 铁栅及后方碎石明确封闭；可达范围止于 Z=24。后墓区未制作，不声明后续通路存在或可进入。'
    return finish(m,'入口专项以旧守门生活屋和独立尖拱封闭门形成非对称组合；铁栅、后碎石和告示说明止步边界，前庭与历史登记室可正常抵达。')


BUILDERS={'ML-01-v01':library,'ML-10-v01':hospital,'ML-02-v01':station,'ML-05-v01':council,'ML-11-v01':mausoleum,'ML-12-v01':sealed_gate}
