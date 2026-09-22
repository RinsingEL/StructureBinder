"""Mountain living variants with deliberately different occupancy and plans."""
from functools import partial
from .mountain_forge import base,hall,front,room,use,desk,kitchen,bunks,table,WALL,FLOOR
from .components import shell,window,column,bench,pendant,shelf,crate_stack

def identity(m,notes,site='紧凑平整生活台地，室内与街面脚底 Y=2。'):
    m.meta['design_notes']=[notes]
    m.meta['differences']=[notes]
    m.meta['source']='tools/structure_studio/studio/mountain_life.py:BUILDERS'
    m.meta['terrain']['选址']=site
    return m

def partition(m,x0,z0,x1,z1,door,y=2,top=6):
    m.box((x0,y,z0),(x1,top,z1),WALL)
    m.door(door[0],y,door[1],facing='east' if x0==x1 else 'south')

def short_home(m,x0,z0,x1,z1,key,y=2,roof='hip',beds=2):
    hall(m,x0,z0,x1,z1,y=y-1,height=5,roof=roof)
    front(m,(x0+x1)//2,z0,y)
    kitchen(m,x0+1,y,z0+2,key+'_cook')
    table(m,x1-5,y,z0+3,3)
    split=z1-7
    partition(m,x0+1,split,x1-1,split,((x0+x1)//2,split),y,top=y+4)
    bunks(m,x0+2,y,split+2,beds,key+'_bed')
    m.set(x1-1,y,z1-1,'water_cauldron[level=3]')
    room(m,key+'_living','起居餐厨',x0+1,z0+1,x1-1,split-1,'厨房、共用餐桌与进门缓冲',y,y+4)
    room(m,key+'_sleep','就寝与装备',x0+1,split+1,x1-1,z1-1,'床位、个人储物和饮水',y,y+4)

def stair(m,x,z,y,rise=6,width=2):
    # Landing floor top is y+rise. Ceiling over the entire flight is explicitly cut.
    m.box((x,y,z),(x+width-1,y+rise+2,z+rise),'air')
    for i in range(rise):
        if i:m.box((x,y,z+i),(x+width-1,y+i-1,z+i),'stone_bricks')
        m.box((x,y+i,z+i),(x+width-1,y+i,z+i),'stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]')
    m.box((x,y+rise-1,z+rise),(x+width-1,y+rise-1,z+rise+1),FLOOR)
    for xx in (x-1,x+width):
        m.box((xx,y+rise,z),(xx,y+rise,z+rise-1),'spruce_fence[east=false,west=false,north=true,south=true,waterlogged=false]')
    m.box((x-1,y+rise,z-1),(x+width,y+rise,z-1),'spruce_fence[east=true,west=true,north=false,south=false,waterlogged=false]')
    m.set(x-1,y+rise,z-1,'spruce_fence[east=true,west=false,north=false,south=true,waterlogged=false]')
    m.set(x+width,y+rise,z-1,'spruce_fence[east=false,west=true,north=false,south=true,waterlogged=false]')
    return y+rise

def longhouse(v):
    names=['四床短长屋','双户连脊长屋','中廊分寝宿舍','折角共餐长屋','双翼更班院舍','窄街楼上宿舍']
    dims=[(23,24),(33,24),(23,35),(27,30),(33,32),(23,29)]
    w,d=dims[v-1];m=base(f'MF-04-v{v:02}',names[v-1],w,d,25,role='fill')
    if v==1:
        short_home(m,3,3,19,20,'short',beds=4,roof='gable')
        shelf(m,4,2,11,4);use(m,'gear','storage',4,3,11,'共用矿具架',5,10,ay=2)
        note='单脊短长屋，前部公共餐厨，隔门后四床与个人柜；适合一班矿工共享。'
    elif v==2:
        for x,key in ((3,'west'),(18,'east')):short_home(m,x,3,x+11,20,key,beds=2)
        m.box((15,1,4),(17,1,20),'gravel');m.set(16,2,19,'water_cauldron[level=3]')
        note='两户各有街门、餐厨和双床后寝，三格巷道分开作息；双铜顶与共享院侧水盆。'
    elif v==3:
        hall(m,3,3,19,31,roof='gable');front(m,11)
        for x in (10,13):m.box((x,2,4),(x,6,30),WALL)
        for z in (12,21):
            m.box((4,2,z),(9,6,z),WALL);m.box((14,2,z),(18,6,z),WALL)
        for z in (8,17,26):m.door(10,2,z,facing='east');m.door(13,2,z,facing='east')
        kitchen(m,14,2,5);table(m,5,2,5,3);shelf(m,4,2,10,4)
        for x in (5,15):
            for z in (14,23):bunks(m,x,2,z,1,f'bed{x}_{z}')
        for key,a,b in [('west',(4,4),(9,30)),('east',(14,4),(18,30))]:room(m,key,'分隔寝室与公用前室',*a,*b,'前部公共餐厨，后部两间一人寝室与个人装备')
        room(m,'corridor','贯通中廊',11,4,12,30,'二格净宽通廊联系六个门口');m.point('hallway','circulation',(11,2,28),'后部疏通廊',look_at=[11,2,8])
        note='二格贯通中廊联系两侧六门；四间单寝与前部独立餐室、厨房分开。'
    elif v==4:
        hall(m,3,3,23,13,roof='hip');hall(m,3,13,12,26,roof='gable');front(m,16)
        m.door(8,2,13);kitchen(m,4,2,5);table(m,14,2,6,5);shelf(m,15,2,11,6)
        for z in (15,21):bunks(m,5,2,z,2,f'wing{z}')
        m.set(20,2,18,'smithing_table');m.set(23,2,22,'water_cauldron[level=3]')
        use(m,'repair','work',20,2,18,'露天衣具修补',20,17)
        room(m,'living','宽面共餐厅',4,4,22,12,'公共厨房、大餐桌与储粮');room(m,'beds','折角四床寝翼',4,14,11,25,'两组双床、个人柜与巷侧窗');room(m,'yard','院侧修补坪',13,15,24,26,'工具修补、清洗与晾晒缓冲')
        note='L形共餐厅与四床窄寝翼围出维修小院，餐厨与就寝有转折门厅。'
    elif v==5:
        for x,key in ((3,'west'),(20,'east')):
            hall(m,x,3,x+9,27,roof='hip');front(m,x+4);m.door(x+9 if x==3 else x,2,16,facing='east')
            for z in (7,17):bunks(m,x+2,2,z,2,key+str(z))
            shelf(m,x+1,2,25,6)
            room(m,key,'四床更班寝翼',x+1,4,x+8,26,'每翼四床、个人柜和共用装备架')
        hall(m,12,20,20,27,roof='flat');front(m,16,20);m.door(12,2,24,facing='east');m.door(20,2,24,facing='east')
        kitchen(m,13,2,22);table(m,15,2,24,3)
        m.set(16,2,9,'water_cauldron[level=3]');bench(m,14,2,15,4)
        room(m,'common','后部公共餐厨',13,21,19,26,'共用厨房和短餐台');room(m,'court','换班院',13,4,19,19,'清洗、歇脚、进出两翼与餐厨')
        note='八床分两条寝翼，各有街门与院门；后部共厨围出可换班洗漱的中央院。'
    else:
        hall(m,3,3,19,25,height=5,roof='flat');hall(m,3,3,19,25,y=7,height=5,roof='gable');front(m,11)
        kitchen(m,4,2,5);table(m,12,2,6,5);shelf(m,4,2,23,7)
        stair(m,5,10,2,6,3)
        for z in (5,19):bunks(m,11,8,z,2,f'upper{z}')
        m.set(17,8,13,'water_cauldron[level=3]');shelf(m,11,8,23,6)
        room(m,'common','楼下餐厨装备厅',4,4,18,24,'共餐、厨房、全班装备与内楼梯');room(m,'sleep','楼上四床宿舍',4,4,18,24,'四床与储物、饮水、护栏梯井',8,12)
        m.meta.update(roof_min_y=7,floors=[dict(name='餐厨与装备',y=1,max_y=5),dict(name='上层宿舍',y=7,max_y=11)])
        note='狭长街坊用六级三宽内梯联系两层，下层餐厨装备，上层四床与护栏梯井。'
    return identity(m,note)

def artisan(v):
    names=['顺坡前工后居宅','窄面双层匠宅','高院折角工匠宅','双门错层宅','工场上居连廊宅','双庭阶台家宅']
    sizes=[(25,29),(21,27),(31,29),(29,27),(29,30),(31,31)]
    w,d=sizes[v-1];m=base(f'MF-05-v{v:02}',names[v-1],w,d,23,role='fill')
    if v==2:
        hall(m,3,3,17,23,height=5,roof='flat');hall(m,3,3,17,23,y=7,height=5,roof='gable');front(m,10)
        desk(m,4,2,5,'trade','接单书写');m.set(14,2,12,'smithing_table');use(m,'work','work',14,2,12,'小型修造台',13,12)
        shelf(m,10,2,21,6);stair(m,5,10,2,6,2)
        kitchen(m,11,8,5);bunks(m,10,8,17,2,'home');table(m,11,8,11,3)
        room(m,'work','接单与小工作室',4,4,16,22,'工作台、书写和材料，上层生活经侧内梯进入');room(m,'home','楼上家庭起居',4,4,16,22,'餐厨、二床、衣柜和梯井',8,12)
        m.meta.update(roof_min_y=7,floors=[dict(name='工作层',y=1,max_y=5),dict(name='家庭层',y=7,max_y=11)])
        return identity(m,'窄面两层：下层接单工作与材料，上层二床餐厨；六级双宽内梯把工作与生活分开。','稳定狭长台地；本款靠楼层节约地块，双层本体街门脚底 Y=2。')
    # Every other variant has a +Z rising one-step court, independently supported.
    boundary={1:14,3:13,4:12,5:17,6:15}[v]
    m.box((2,0,boundary),(w-3,2,d-3),'stone_bricks')
    for x in range(12,15):m.set(x,2,boundary,'stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]')
    if v==1:
        hall(m,3,3,21,12,roof='hip');front(m,12);m.box((11,2,12),(14,4,12),'air')
        m.set(5,2,6,'smithing_table');use(m,'work','work',5,2,6,'匠作台',6,6);desk(m,15,2,6,'write','接单与绘图');shelf(m,4,2,10,6)
        hall(m,3,16,21,25,y=2,height=5,roof='hip');front(m,12,16,3)
        bunks(m,5,3,20,2,'backbed');kitchen(m,16,3,19,'cook2');table(m,13,3,22,3)
        room(m,'home','高台家庭屋',4,17,20,24,'二床、个人柜、厨房和家庭餐桌',3,7)
        room(m,'work','前部工房',4,4,20,11,'接单、书写、小工作间与材料')
        note='前低工房与后高家庭屋隔一阶院，正面接单不穿过居室；高屋二床并设餐厨。'
    elif v==3:
        hall(m,3,3,11,23,roof='gable');front(m,7);m.door(11,2,9,facing='east')
        desk(m,4,2,6,'write','匠人记账');m.set(5,2,16,'smithing_table');use(m,'work','work',5,2,16,'纵向小工间',6,16);shelf(m,4,2,21,5)
        hall(m,17,15,27,25,y=2,height=5,roof='hip');front(m,22,15,3)
        bunks(m,18,3,20,2,'backbed');kitchen(m,23,3,17,'cook2');table(m,23,3,22,2)
        room(m,'home','东后高宅',18,16,26,24,'二床、个人柜、餐厨',3,7)
        room(m,'work','长条工房',4,4,10,22,'前接单、后修造与材料');room(m,'court','折角高院',12,14,16,26,'一阶高院联系独立家庭翼',3,7)
        note='西侧窄工房与东后高宅形成折角双屋，高院分流工作门与家庭门。'
    elif v==4:
        hall(m,3,3,24,10,roof='hip');front(m,10);desk(m,5,2,5,'write','前厅接单');m.set(21,2,6,'loom');use(m,'work','work',21,2,6,'织修台',20,6)
        hall(m,3,14,24,23,y=2,height=5,roof='hip');front(m,13,14,3)
        bunks(m,5,3,17,2,'home');kitchen(m,19,3,16);table(m,14,3,19,3)
        m.door(24,3,19,facing='east');m.point('upper_entry','entrance',(25,3,19),'高街家门',facing='east')
        room(m,'work','低街接单厅',4,4,23,9,'客户接待与纺织修补');room(m,'home','高街家庭厅',4,15,23,22,'二床、餐厨、独立高街门',3,7)
        note='低街接单与高街家庭厅分成两条横向屋，中央一阶内院连通，东西高街另开家门。'
    elif v==5:
        hall(m,3,3,13,14,roof='gable');front(m,8);m.set(5,2,6,'smithing_table');use(m,'work','work',5,2,6,'前工场',6,6);desk(m,8,2,11,'write','书写台')
        hall(m,3,19,24,26,y=2,height=5,roof='hip');front(m,13,19,3);bunks(m,5,3,21,2,'home');kitchen(m,19,3,21);table(m,13,3,23,3)
        for x,z in ((17,5),(24,5),(17,14),(24,14)):column(m,x,z,2,6,'stripped_spruce_log','waxed_cut_copper')
        m.box((16,7,4),(25,7,15),'spruce_slab[type=bottom,waterlogged=false]');crate_stack(m,19,2,6,4,2,2)
        room(m,'work','低位工场与记账',4,4,12,13,'小工场、书写');room(m,'store','低位材料连棚',17,5,24,14,'材料暂放与从街到高宅的周转');room(m,'home','后部高台家庭屋',4,20,23,25,'二床、公共餐厨与前阶院',3,7)
        note='前低工场并置开放材料连棚，后高家庭屋横向收尾；搬料路线和生活入口分开。'
    else:
        hall(m,3,3,12,12,roof='hip');front(m,8);desk(m,4,2,5,'write','绘样室');m.set(10,2,9,'cartography_table');use(m,'design','work',10,2,9,'绘图工作台',9,9)
        hall(m,19,3,27,12,roof='flat');front(m,23);m.set(21,2,6,'stonecutter[facing=east]');use(m,'work','work',21,2,6,'石料修造',22,6);shelf(m,20,2,10,5)
        hall(m,3,18,27,27,y=2,height=5,roof='hip');front(m,14,18,3);bunks(m,5,3,21,2,'home');kitchen(m,22,3,20);table(m,14,3,23,5)
        m.set(16,2,6,'water_cauldron[level=3]');bench(m,16,3,17,4)
        room(m,'design','独立绘样室',4,4,11,11,'安静书写绘图');room(m,'work','独立石作间',20,4,26,11,'石料切磨与储存');room(m,'home','宽高台家屋',4,19,26,26,'两床、宽餐桌、厨房与家庭储物',3,7)
        note='低台两间独立绘样/石作小屋留中央前院，后方一阶宽家庭屋把双工种与生活分开。'
    m.meta['terrain']['高程']=f'低位工作与街门 Y=2，高台家庭脚底 Y=3；+Z 从 Z={boundary} 起抬高一格，三宽短阶位置固定。'
    m.meta.update(roof_min_y=7 if v==5 else 8,floors=[dict(name='低位工房与高台家庭',y=1,max_y=6)],preview_context=dict(kind='slope',land_surface_y=2,padding=5,run=14,rise=1,slope_origin_z=boundary))
    return identity(m,note,'仅用于明确向 +Z 升高一格的山地地块；宽阶与后方高台均须承托，不可任意镜像后沿用高程。')

BUILDERS={**{f'MF-04-v{i:02}':partial(longhouse,i) for i in range(1,7)},**{f'MF-05-v{i:02}':partial(artisan,i) for i in range(1,7)}}
