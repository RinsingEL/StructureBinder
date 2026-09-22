"""Oasis domestic and agricultural plans; each variant has its own footprint."""
from .model import Model
from .desert import room_shell,windtower,front,table,bedroom
from .components import bench,shelf,pendant,crate_stack,window
from .samples import railing


HOME_PLANS=[
    ("单户凉廊宅",(23,23,25),"前院凉廊接后排起居与卧室，小家庭独立生活，沿北侧街巷进入。"),
    ("双户共水宅",(33,23,28),"两户各有炊事和卧室，共享中央储水庭与遮阳入口，不通过另一户进入。"),
    ("曲折内庭宅",(31,23,31),"厚墙围合内庭，凉室、起居、卧室与后储藏翼分开，通路绕院曲折展开。"),
    ("窄巷叠居宅",(17,31,31),"窄长占地，底层炊事与凉室，上层睡眠、阅读和晾晒；内部楼梯连接两层。"),
    ("转角遮阳宅",(29,23,27),"L 形住宅有北侧和东侧两个入口，前凹角留给外部街巷，后翼兼作客宿与储藏。"),
    ("分台风庭宅",(33,25,33),"前低后高两台住宅，低院炊事、上台睡眠与家务，用内院短阶衔接一格高差。"),
]


def base(key,name,size,description,terrain):
    m=Model(key,name,size,family=key.rsplit('-v',1)[0],civilization="星仪王国",role="fill",terrain=terrain)
    m.meta.update(design_notes=[description],differences=[description],floors=[dict(name="起居与内庭",y=1,max_y=6)],roof_min_y=8,
                  preview_context=dict(kind="flat",land_surface_y=2,bed_y=-1,padding=3,surface="sand"))
    return m


def paved(m,cells,height=lambda x,z:1):
    for x,z in sorted(cells):
        f=height(x,z)
        m.box((x,0,z),(x,f,z),"cut_sandstone")
        m.box((x,f+1,z),(x,m.size[1]-1,z),"air")
        m.set(x,f,z,"smooth_sandstone" if (x+z)%8 else "sandstone")


def canopy(m,x0,z0,x1,z1,f=1):
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,f+1,z),(x,f+4,z),"stripped_acacia_log[axis=y]")
    for x in range(x0,x1+1):
        m.box((x,f+5,z0),(x,f+5,z1),"white_wool" if x%4<2 else "orange_wool")


def ventilation(m,x,z,y=9):
    windtower(m,x,z,y)
    m.meta.setdefault('ventilation',[]).append(dict(roof_y=y-1,min=[x+1,y-1,z+1],max=[x+2,y+4,z+2],note='连续通风孔的几何表达，不模拟气流或温度'))


def living(m,key,name,a,b,*,f=1):
    x0,z0=a;x1,z1=b
    room_shell(m,x0,z0,x1,z1,f=f)
    m.set(x0+1,f+1,z1-1,"smoker[facing=north]")
    m.box((x0+1,f+2,z1-1),(x0+1,f+10,z1-1),"sandstone")
    m.set(x0+3,f+1,z1-1,"water_cauldron[level=3]")
    m.set(x1-1,f+1,z1-1,"barrel[facing=north]")
    table(m,x0+4,f+1,z0+3,3,"orange")
    bench(m,x0+4,f+1,z0+5,3,"north","birch")
    shelf(m,x1-3,f+1,z0+1,2,material="birch",contents="flower_pot")
    m.point(key+"_cook","work",(x0+1,f+1,z1-1),name+"炉灶",approach=(x0+1,f+1,z1-2))
    m.room(key,name,(x0+1,f+1,z0+1),(x1-1,f+5,z1-1),"炊事、备水、进餐与用品")
    pendant(m,x0+5,f+5,z0+3,f+7)


def sleeping(m,key,name,a,b,*,f=1,two=False):
    x0,z0=a;x1,z1=b
    room_shell(m,x0,z0,x1,z1,f=f)
    bedroom(m,key,x0,z0,x1,z1,f=f,color="cyan",two=two)
    for index,p in enumerate([p for p in m.meta['points'] if p['id'].startswith(key+'_bed')],1):p['name']=name+f"床位 {index}"
    m.room(key,name,(x0+1,f+1,z0+1),(x1-1,f+5,z1-1),"睡眠、衣物与室内盥洗")
    pendant(m,(x0+x1)//2,f+5,z0+3,f+7)


def home(variant):
    name,size,description=HOME_PLANS[variant-1]
    m=base(f"DS-04-v{variant:02d}",name,size,description,{
        "选址":"有稳定生活用水的干热聚落，通风方向与遮阳不被邻屋完全堵塞",
        "地块":description,"落地":"独立住宅模板，庭院及其硬地属于实际占地；未写入格保留原环境",
        "高程":"主入口脚底 Y=2；上台脚底 Y=3" if variant==6 else "主入口与底层脚底 Y=2；叠居版二层脚底 Y=9" if variant==4 else "主入口与生活空间脚底 Y=2"})
    m.meta['source']=f"tools/structure_studio/studio/oasis_life.py:home({variant})"
    w,_,d=size
    cells={(x,z) for x in range(2,w-2) for z in range(3,d-2)}
    if variant==5:cells={(x,z) for x,z in cells if x<=15 or z>=15}
    paved(m,cells,lambda x,z:2 if variant==6 and z>=16 else 1)
    if variant==1:
        living(m,'living','炊事与起居',(3,11),(11,21))
        sleeping(m,'bedroom','单户卧室',(11,11),(19,21))
        m.door(7,2,11,facing='north');m.door(11,2,18,facing='east')
        canopy(m,3,4,10,9)
        bench(m,4,2,7,4,'south','birch');m.set(17,2,6,'water_cauldron[level=3]')
        m.point('cool','circulation',(7,2,9),'凉廊活动位',look_at=[6,3,7])
        m.room('court','前院凉廊',(3,2,4),(19,6,10),'歇凉、取水与家务')
        window(m,(13,4,11),(16,5,11),color='cyan_stained_glass')
        ventilation(m,15,17);front(m,13,4,width=3)
    elif variant==2:
        for side,x0,x1,doorx in [('west',3,13,13),('east',19,29,19)]:
            label='西户' if side=='west' else '东户'
            living(m,side+'_living',label+'起居',(x0,5),(x1,13))
            sleeping(m,side+'_bedroom',label+'卧室',(x0,13),(x1,23))
            for z in (8,17):m.door(doorx,2,z,facing='east' if side=='west' else 'west')
        m.set(16,2,22,'water_cauldron[level=3]');m.set(16,2,23,'barrel[facing=north]')
        m.point('well','work',(16,2,22),'两户共用取水点',approach=(16,2,21))
        canopy(m,14,5,18,11)
        m.room('common','共水窄庭',(14,2,4),(18,6,24),'两户独立入口与共享储水')
        for x in (5,21):window(m,(x,3,5),(x+3,4,5),color='cyan_stained_glass')
        ventilation(m,4,6);ventilation(m,25,17);front(m,16,5,width=3)
    elif variant==3:
        room_shell(m,3,5,12,15)
        living(m,'living','家庭起居',(3,15),(12,27))
        sleeping(m,'bedroom','内庭卧室',(20,5),(27,19))
        room_shell(m,13,22,27,27)
        for x,z,facing in [(12,9,'east'),(12,20,'east'),(20,12,'west'),(17,22,'north')]:m.door(x,2,z,facing=facing)
        bench(m,5,2,7,5,'south','birch');table(m,5,2,11,4,'cyan')
        m.set(4,2,13,'water_cauldron[level=3]')
        m.box((14,1,12),(18,1,16),'blue_terracotta');m.box((15,1,13),(17,1,15),'water[level=0]')
        crate_stack(m,23,2,25,3,2,2);m.set(15,2,25,'lectern[facing=north]')
        m.point('stores','work',(15,2,25),'家用物资记录',approach=(15,2,24))
        m.point('cool','circulation',(8,2,9),'凉室候坐',look_at=[7,3,7])
        m.room('coolroom','前翼凉室',(4,2,6),(11,6,14),'凉室、候坐与家人会客')
        m.room('stores','后翼储物间',(14,2,23),(26,6,26),'家用储藏和账记')
        m.room('court','曲折水庭',(13,2,5),(19,6,21),'水盆与连接各翼的内庭')
        for x in (6,22):window(m,(x,3,5),(x+3,4,5),color='cyan_stained_glass')
        ventilation(m,4,6);front(m,16,5,width=3)
    elif variant==4:
        room_shell(m,3,5,13,26);room_shell(m,3,5,13,26,f=8)
        m.door(7,2,5,facing='north');front(m,7,5,width=3)
        m.set(4,2,24,'smoker[facing=north]');m.set(6,2,25,'water_cauldron[level=3]');m.set(8,2,25,'barrel[facing=north]')
        m.box((4,3,24),(4,18,24),'sandstone')
        table(m,5,2,9,4,'orange');bench(m,5,2,12,4,'north','birch');bench(m,4,2,18,4,'south','birch')
        m.box((11,8,14),(12,8,23),'air')
        for i in range(7):
            for x in (11,12):
                m.box((x,2,22-i),(x,2+i,22-i),'sandstone');m.set(x,2+i,22-i,'sandstone_stairs[facing=north]')
        m.box((11,8,14),(12,8,15),'birch_planks')
        railing(m,(10,9,16),(10,9,23),wood='birch',axis='z')
        m.box((4,9,19),(9,12,19),'white_terracotta');m.door(7,9,19,facing='south')
        m.bed(5,9,24,color='cyan',facing='north');m.point('bed','bed',(5,9,24),'上层卧床',approach=(6,9,24))
        m.box((4,9,20),(6,10,20),'birch_planks');m.set(8,9,25,'water_cauldron[level=3]')
        table(m,4,9,8,4,'cyan');m.set(5,10,8,'potted_dead_bush');m.box((4,9,12),(4,11,15),'bookshelf')
        m.set(8,9,6,'barrel[facing=south]')
        for z in (11,15):m.box((8,9,z),(8,11,z),'stripped_acacia_log[axis=y]')
        m.box((8,12,11),(8,12,15),'stripped_acacia_log[axis=z]')
        for z in (12,14):m.set(8,11,z,'white_wool')
        m.point('linen','work',(8,11,12),'凉廊晾晒架',approach=(9,9,12))
        m.point('cook','work',(4,2,24),'底层炉灶',approach=(4,2,23))
        m.point('landing','circulation',(11,9,14),'楼上凉廊',look_at=[6,10,10])
        for key,label,a,b,usage in [('living','底层炊事与凉室',(4,2,6),(12,6,25),'餐桌、炊事与避暑'),('bedroom','上层卧室',(4,9,20),(9,13,25),'睡眠与盥洗'),('upper','上层阅读凉廊',(4,9,6),(12,13,18),'阅读、储物与楼梯连接')]:m.room(key,label,a,b,usage)
        window(m,(5,4,5),(8,5,5),color='cyan_stained_glass')
        window(m,(5,10,5),(8,12,5),color='cyan_stained_glass')
        m.box((4,13,4),(12,13,4),'smooth_sandstone_slab[type=bottom]')
        ventilation(m,4,6,y=16)
        m.meta.update(roof_min_y=15,floors=[dict(name='底层起居',y=1,max_y=6),dict(name='上层睡眠与凉廊',y=8,max_y=13)])
        pendant(m,7,6,10,8);pendant(m,7,13,15,15)
    elif variant==5:
        living(m,'living','临街起居',(3,5),(14,14))
        sleeping(m,'bedroom','后排卧室',(3,14),(14,23))
        room_shell(m,14,16,25,23)
        m.door(7,2,5,facing='north');m.door(10,2,14,facing='south');m.door(14,2,20,facing='east');m.door(25,2,20,facing='east')
        m.bed(21,2,21,color='orange',facing='north');m.point('guest_bed','bed',(21,2,21),'客宿床',approach=(22,2,21))
        crate_stack(m,16,2,21,2,2,2);table(m,18,2,17,3,'white')
        m.point('side','entrance',(26,2,20),'东侧院巷入口',facing='east')
        m.room('guest','转角客宿与储藏',(15,2,17),(24,6,22),'客宿、家用存货与东侧出入')
        m.meta['connections'].append(dict(kind='pedestrian',pos=[26,1,20],direction='east',clearance=[2,3],note='东侧巷面脚底需接 Y=2'))
        window(m,(5,4,5),(9,5,5),color='cyan_stained_glass')
        window(m,(25,4,18),(25,5,19),'z','cyan_stained_glass')
        ventilation(m,4,17);front(m,7,5,width=3)
    else:
        living(m,'living','下台家庭起居',(3,5),(15,14))
        sleeping(m,'bedroom','上台双床房',(3,18),(15,29),f=2,two=True)
        room_shell(m,18,18,29,29,f=2)
        for x,z,f,facing in [(15,10,1,'east'),(15,22,2,'east'),(18,23,2,'west')]:m.door(x,f+1,z,facing=facing)
        for x in range(16,19):m.set(x,2,16,'sandstone_stairs[facing=south]')
        canopy(m,20,5,28,12);bench(m,22,2,8,4,'south','birch')
        table(m,22,3,22,4,'cyan');m.set(27,3,26,'crafting_table');m.set(27,3,28,'barrel[facing=north]')
        shelf(m,20,3,28,4,material='birch',contents='flower_pot')
        m.point('housework','work',(27,3,26),'上台家务桌',approach=(26,3,26))
        m.point('upper','circulation',(17,3,17),'上台连廊',look_at=[17,4,25])
        m.room('housework','上台家务凉室',(19,3,19),(28,7,28),'家务、阅读与储藏')
        m.room('court','两台风庭',(16,2,4),(29,7,17),'低院歇凉与上台短阶')
        window(m,(6,3,5),(9,4,5),color='cyan_stained_glass')
        window(m,(29,4,21),(29,5,24),'z','cyan_stained_glass')
        ventilation(m,5,19,y=10);front(m,23,5,width=3)
        m.meta['floors']=[dict(name='两台起居',y=1,max_y=7)]
        m.meta['preview_context'].update(kind='slope',run=16,rise=1,slope_origin_z=0)
    return m


GARDEN_PLANS=[
    ('渠旁窄条菜田',18,31,'狭长渠旁地块，单条纵渠、两侧种植与边沿维护道。'),
    ('四岛分水菜园',27,27,'四块种植岛由十字作业道分隔，交叉水渠贴近每块菜畦。'),
    ('围墙内院菜畦',25,23,'小型住宅内院菜畦，围墙、四区短畦与田头用品形成完整庭园。'),
    ('斜巷阶边田',25,30,'随道路边角逐段展开的非矩形田，纵渠随宽度增减，凹边不清地。'),
    ('分拣棚双渠田',31,29,'两侧长畦与宽中道相接，田头有遮阳分拣棚，供小规模市场种植。'),
    ('两台等高渠田',33,29,'前低后高一格的两台菜田，沿等高方向布渠，中央短阶贯通作业。'),
]


def garden(variant):
    name,w,d,description=GARDEN_PLANS[variant-1]
    m=base(f'DS-F02-v{variant:02d}',name,(w,12,d),description,{
        '选址':'有可靠引水与适宜土壤的绿洲；不在无水沙海仅凭文明标签放置',
        '形状':description,'地面':'下台脚底 Y=2，上台 Y=3，沿 +Z 方向抬升' if variant==6 else '作业道脚底 Y=2，有限地块内备土与灌溉',
        '落地方式':'独立固定模板田块，包含封边与作业道；不替代 Landscape 自然生成大田'})
    m.meta.update(source=f'tools/structure_studio/studio/oasis_life.py:garden({variant})',roof_min_y=None,floors=[dict(name='灌渠与田埂',y=0,max_y=5)])
    if variant==1:
        cells={(x,z) for x in range(2,16) for z in range(3,29)}
        paths={(x,z) for x,z in cells if x in (2,3,4,13,14,15) or z in (3,4,16,27,28)}
        water={(8,z) for z in range(5,27)};gate=(3,3);tool=(14,25);plants=['carrots','potatoes']
    elif variant==2:
        cells={(x,z) for x in range(2,25) for z in range(3,25)}
        paths={(x,z) for x,z in cells if x in (2,12,13,14,24) or z in (3,12,13,14,24)}
        water={(x,z) for x,z in cells if x in (7,19) or z in (7,19)};gate=(13,3);tool=(13,22);plants=['carrots','beetroots','potatoes','wheat']
    elif variant==3:
        cells={(x,z) for x in range(3,22) for z in range(3,20)}
        paths={(x,z) for x,z in cells if x in (3,11,12,13,21) or z in (3,10,11,19) or (x<=8 and z<=5)}
        water={(7,7),(17,7),(7,15),(17,15)};gate=(12,3);tool=(5,4);plants=['beetroots','carrots','potatoes']
    elif variant==4:
        cells={(x,z) for z in range(3,27) for x in range(2,8+(z-3)//2)}
        paths={(x,z) for x,z in cells if x in (2,3) or z in (3,14,26)}
        water={(x,z) for x,z in cells if x in (6,13,20) and (x+2,z) in cells}|{(10,12)};gate=(3,3);tool=(3,24);plants=['wheat','carrots']
    elif variant==5:
        cells={(x,z) for x in range(2,29) for z in range(3,27)}
        paths={(x,z) for x,z in cells if x in (2,3,13,14,15,16,27,28) or z in (3,14,15,26) or (x<=12 and z<=8)}
        water={(x,z) for x,z in cells if x in (8,21,22)};gate=(14,3);tool=(4,6);plants=['carrots','potatoes','beetroots']
    else:
        cells={(x,z) for x in range(2,30) for z in range(3,26) if x<=22 or z>=10}
        paths={(x,z) for x,z in cells if x in (2,3,16,17,18,28,29) or z in (3,4,13,14,15,24,25)}
        water={(x,z) for x,z in cells if z in (8,20)}|{(25,11)};gate=(17,3);tool=(28,22);plants=['carrots','wheat','beetroots']
    edge={(x,z) for x,z in cells if any((x+dx,z+dz) not in cells for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)])}
    paths|=edge;water-=paths;crop=cells-paths-water
    level=lambda z:2 if variant==6 and z>=14 else 1
    for x,z in sorted(cells):
        f=level(z);m.box((x,0,z),(x,f,z),'sandstone' if (x,z) in edge else 'dirt')
        m.box((x,f+1,z),(x,f+7,z),'air')
        if (x,z) in water:m.set(x,f,z,'water[level=0]')
        elif (x,z) in paths:m.set(x,f,z,'cut_sandstone' if (x,z) in edge else 'smooth_sandstone')
        else:
            plant=plants[(x//9+z//9)%len(plants)]
            m.set(x,f,z,'farmland[moisture=7]');m.set(x,f+1,z,f"{plant}[age={3 if plant=='beetroots' else 7 if z%4 else 5}]")
    tx,tz=tool;ty=level(tz)+1
    m.set(tx,ty,tz,'composter[level=5]')
    adjacent=next(((x,z) for x,z in [(tx-1,tz),(tx+1,tz),(tx,tz-1),(tx,tz+1)] if (x,z) in paths),None)
    if adjacent is None:raise ValueError('No dry compost approach')
    ax,az=adjacent;m.point('compost','work',(tx,ty,tz),'堆肥与田务',approach=(ax,level(az)+1,az))
    if variant==1:
        m.set(14,2,23,'barrel[facing=west]');m.set(14,3,23,'lantern')
    elif variant==2:
        m.set(13,2,13,'water_cauldron[level=3]')
        m.point('water','work',(13,2,13),'分水庭取水',approach=(12,2,13))
    elif variant==3:
        for x,z in edge:
            if z==3 and 11<=x<=13:continue
            m.set(x,2,z,'sandstone');m.set(x,3,z,'smooth_sandstone_slab[type=bottom]')
        canopy(m,4,4,8,5);m.set(7,2,4,'barrel[facing=south]')
        next(p for p in m.meta['points'] if p['id']=='compost')['approach']=[5,2,5]
    elif variant==4:
        m.set(3,2,21,'barrel[facing=east]');m.set(3,3,21,'lantern')
    elif variant==5:
        canopy(m,3,4,11,8);table(m,6,2,5,4,'orange');m.set(10,2,7,'barrel[facing=west]')
        m.point('sort','work',(7,2,5),'遮阳分拣台',approach=(7,2,6))
        m.room('sorting','田头分拣棚',(3,2,4),(11,6,8),'遮阳、分拣与存放')
    else:
        for x in (16,17,18):m.set(x,2,14,'sandstone_stairs[facing=south]')
        for i,z in enumerate((11,22),1):m.point(f'terrace_{i}','circulation',(17,level(z)+1,z),f'第 {i} 台田埂',look_at=[10,level(z)+2,z])
        m.meta['preview_context'].update(kind='slope',run=14,rise=1,slope_origin_z=0)
    gx,gz=gate
    m.point('entry','entrance',(gx,2,gz),'田头入口',facing='north')
    m.room('field','灌渠种植与维护',(min(x for x,z in cells),2,min(z for x,z in cells)),(max(x for x,z in cells),6,max(z for x,z in cells)),description)
    m.meta['agriculture']=dict(crops=plants,layout=description,planted_cells=len(crop),irrigation_sources=len(water),template_mode='independent_fixed_plot',hydration='以最终 NBT 校核支撑与同高程或高一格、水平 4 格水源')
    return m
