"""Mountain forge assets: load-bearing masonry, copper caps and useful interiors."""
from functools import partial
from .model import Model
from .components import shell,window,column,bench,pendant,shelf,crate_stack,hip_roof,arch_front

WALL='deepslate_bricks'
FLOOR='polished_andesite'

def base(key,name,w,d,h=18,role='key',terrain=None):
    m=Model(key,name,(w,h,d),family=key.split('-v')[0],civilization='矮人',role=role,terrain=terrain or {
        '选址':'山前矿业聚落的稳定石质台地，正面朝 -Z；须避开落石、塌陷和洪水通道。',
        '高程':'基础上边界及室内脚底 Y=2；入口街面需同高程。',
        '保留空间':'完整独立建筑及院落，保留入口和装卸面，不依赖其他模板补墙。'})
    m.box((1,0,1),(w-2,1,d-2),'stone_bricks')
    m.box((1,1,1),(w-2,1,d-2),FLOOR)
    m.box((1,2,1),(w-2,h-1,d-2),'air')
    m.meta.update(roof_min_y=8,floors=[dict(name='生活与作业层',y=1,max_y=5)],
        source='tools/structure_studio/studio/mountain_forge.py:BUILDERS',
        preview_context=dict(kind='flat',land_surface_y=2,padding=5),
        design_notes=['厚石墙、深板岩扶壁、云杉内饰和铜屋面构成山地工艺语言。'],differences=[])
    return m

def hall(m,x0,z0,x1,z1,*,y=1,height=6,roof='gable'):
    top=y+height
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],top+1)
    shell(m,(x0,y,z0),(x1,top,z1),WALL,FLOOR,ceiling='spruce_planks')
    for x in (x0,x1):
        for z in (z0,z1):column(m,x,z,y+1,top,'polished_basalt','chiseled_deepslate')
    for z in (z0,z1):m.box((x0,top,z),(x1,top,z),'waxed_cut_copper')
    if roof in ('gable','hip'):
        # Wall-line rafters sit one course above the ceiling because of the overhang.
        for z in (z0,z1):m.box((x0,top+2,z),(x1,top+2,z),'spruce_planks')
        for x in (x0,x1):m.box((x,top+2,z0),(x,top+2,z1),'spruce_planks')
    if roof=='gable':
        span=x1-x0+2
        for i in range((span+1)//2):
            xa,xb=x0-1+i,x1+1-i
            if xa>xb:break
            for z in range(z0-1,z1+2):
                m.set(xa,top+2+i,z,'deepslate_tile_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]')
                if xb!=xa:m.set(xb,top+2+i,z,'deepslate_tile_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]')
            if i>0:
                for z in (z0,z1):m.box((xa,top+1,z),(xb,top+1+i,z),'spruce_planks')
    elif roof=='hip':hip_roof(m,x0-1,x1+1,z0-1,z1+1,top+2,material='waxed_cut_copper',tiers=3)
    else:m.box((x0-1,top+2,z0-1),(x1+1,top+2,z1+1),'deepslate_tile_slab[type=bottom,waterlogged=false]')
    for z in range(z0+3,z1,5):
        window(m,(x0,y+3,z),(x0,y+4,min(z+1,z1-1)),axis='z',color='gray_stained_glass')
        window(m,(x1,y+3,z),(x1,y+4,min(z+1,z1-1)),axis='z',color='gray_stained_glass')
    for z in range(z0+3,z1-1,6):pendant(m,(x0+x1)//2,top-1,z,top+1)
    return top

def front(m,x,z=3,y=2,wide=False):
    if wide:m.box((x-1,y,z),(x+1,y+3,z),'air')
    else:m.door(x,y,z)
    m.point('entry' if not any(p['id']=='entry' for p in m.meta['points']) else f'entry_{x}_{z}','entrance',(x,y,z-1),'街面入口',facing='north')

def room(m,key,name,x0,z0,x1,z1,purpose,y=2,maxy=6):m.room(key,name,(x0,y,z0),(x1,maxy,z1),purpose)
def use(m,key,kind,x,y,z,name,ax,az,ay=None):m.point(key,kind,(x,y,z),name,approach=(ax,y if ay is None else ay,az))
def desk(m,x,y,z,key='desk',name='登记书写台'):
    m.box((x,y,z),(x+2,y,z),'spruce_planks');m.set(x+1,y,z,'lectern[facing=south,has_book=false,powered=false]')
    m.set(x+2,y+1,z,'lantern[hanging=false,waterlogged=false]');use(m,key,'work',x+1,y,z,name,x+1,z+1)
def kitchen(m,x,y,z,key='kitchen'):
    m.set(x,y,z,'smoker[facing=south,lit=false]');m.set(x+1,y,z,'crafting_table');m.set(x+2,y,z,'water_cauldron[level=3]')
    m.set(x,y+1,z,'cobblestone_wall');use(m,key,'work',x,y,z,'炊事与洗涤',x,z+1)
def bunks(m,x,y,z,n=2,key='beds'):
    for i in range(n):
        m.bed(x+3*i,y,z,'brown',facing='south');m.set(x+3*i,y,z+3,'barrel[facing=up,open=false]')
        use(m,f'{key}_{i}','bed',x+3*i,y,z,'床位与个人柜',x+3*i+1,z)
def table(m,x,y,z,length=4):
    m.box((x,y,z),(x+length-1,y,z),'spruce_planks')
    for xx in range(x,x+length,2):m.set(xx,y+1,z,'flower_pot')
    bench(m,x,y,z+2,length,'north')
def chimney(m,x,z,y,top):
    m.box((x,y,z),(x+2,top,z+2),'polished_blackstone_bricks')
    m.box((x+1,y,z+1),(x+1,top,z+1),'air')
    m.box((x,top+1,z),(x+2,top+1,z+2),'iron_bars[east=true,west=true,north=true,south=true,waterlogged=false]')

def monumental_front(m,cx,x0,x1):
    arch_front(m,cx-3,2,3,7,8,'chiseled_deepslate')
    for x in (x0+3,x1-5):window(m,(x,4,3),(x+2,6,3),color='orange_stained_glass')
    for x in (cx-4,cx+4):
        column(m,x,2,2,8,'polished_basalt','waxed_cut_copper')
        m.set(x,9,2,'lantern[hanging=false,waterlogged=false]')

def mine_station():
    m=base('MF-01-v01','岩脉转运站',37,33,22)
    hall(m,3,3,13,18,roof='hip');hall(m,23,3,33,20,roof='gable')
    front(m,8);front(m,28,wide=True)
    m.door(13,2,13,facing='east');m.box((23,2,14),(23,5,16),'air')
    desk(m,5,2,6);shelf(m,4,2,16,5,contents='bookshelf');bench(m,5,2,11,4)
    m.bed(10,2,15,'gray',facing='south');use(m,'watch','bed',10,2,15,'值守床',9,15)
    for x,z in ((25,5),(29,5),(25,17),(30,17)):crate_stack(m,x,2,z,2,2,2)
    m.set(31,2,11,'stonecutter[facing=west]');use(m,'weigh','work',31,2,11,'理货秤台',30,11)
    for z in (24,28):
        m.box((2,1,z),(34,1,z),'gravel')
        for x in range(2,35):m.set(x,2,z,'rail[shape=east_west,waterlogged=false]')
    for x in (4,12,20,28,34):
        for z in (22,30):column(m,x,z,2,7,'polished_basalt','chiseled_deepslate')
    m.box((3,8,21),(35,8,31),'deepslate_tile_slab[type=bottom,waterlogged=false]')
    for x in (8,18,30):pendant(m,x,6,26,8)
    m.box((16,2,8),(16,10,8),'stripped_spruce_log[axis=y]');m.box((16,10,8),(21,10,8),'spruce_log[axis=x]')
    m.box((21,5,8),(21,9,8),'chain[axis=y,waterlogged=false]');m.set(21,4,8,'grindstone[face=ceiling,facing=north]')
    m.set(16,2,9,'grindstone[face=floor,facing=south]')
    use(m,'crane','work',16,2,9,'手动吊架绞盘',17,9)
    m.point('platform','circulation',(18,2,26),'双线间装卸廊',look_at=[26,2,26])
    room(m,'dispatch','调度值班所',4,4,12,17,'登记、运单、值守与更班');room(m,'ore','分类理货库',24,4,32,19,'矿料分类、称量和手车周转');room(m,'tracks','矿车装卸雨棚',4,22,34,30,'两条横向矿车线与中部三格装卸通路')
    m.meta['connections']=[dict(kind='rail',pos=[2,2,z],direction='west',clearance=[3,4],note='东西向水平矿车轨；外部线路与运输逻辑另行接入。') for z in (24,28)]
    m.meta['design_notes']=['双矿车线雨棚与前场分离；西翼运单值班，东翼分类仓，中央手吊与矿料周转。']
    m.meta['differences']=['横向贯通双轨，三格中岛与前侧货场；生活值守不占装卸廊。']
    return m

def lift_tower():
    m=base('MF-02-v01','九阶提升塔',29,29,23)
    hall(m,3,3,13,13,roof='hip');front(m,8);desk(m,5,2,6)
    shelf(m,4,2,11,4);m.door(13,2,9,facing='east')
    # Bounded display shaft. Lower and upper service decks remain outside its fence.
    m.box((5,1,17),(14,1,25),'chiseled_deepslate')
    for x in (5,14):
        for z in (17,25):column(m,x,z,2,19,'polished_basalt','waxed_cut_copper')
    m.box((5,20,17),(14,20,25),'waxed_cut_copper')
    for x in (6,13):m.box((x,3,21),(x,19,21),'chain[axis=y,waterlogged=false]')
    m.box((7,3,19),(12,3,23),'spruce_planks')
    for z in (17,25):m.box((6,2,z),(13,3,z),'iron_bars[east=true,west=true,north=false,south=false,waterlogged=false]')
    for x in (5,14):m.box((x,2,18),(x,3,24),'iron_bars[east=false,west=false,north=true,south=true,waterlogged=false]')
    # Nine rise staircase to actual upper floor Y=11, with two-wide treads.
    for i in range(9):
        z=6+i;y=2+i
        m.box((21,1,z),(23,y-1,z),'stone_bricks')
        m.box((21,y,z),(23,y,z),'stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]')
        m.set(24,y+1,z,'stone_brick_wall')
    m.box((16,0,15),(25,10,25),'stone_bricks');m.box((17,2,16),(24,7,24),'air')
    m.box((21,2,15),(23,4,15),'air')
    hall(m,17,16,25,24,y=10,height=5,roof='hip');m.box((21,11,16),(23,14,16),'air')
    m.box((14,10,19),(17,10,23),'spruce_planks');m.box((14,11,19),(17,13,23),'air')
    m.box((17,11,20),(17,13,22),'air')
    for z in (19,23):m.box((14,11,z),(16,11,z),'iron_bars[east=true,west=true,north=false,south=false,waterlogged=false]')
    desk(m,19,11,22,'upper','上层候运登记');m.set(24,11,18,'grindstone[face=floor,facing=west]')
    use(m,'service','work',24,11,18,'卷扬维护',23,18)
    m.door(25,11,21,facing='east')
    m.box((26,10,20),(27,10,22),'polished_andesite')
    for z in (19,23):m.box((25,11,z),(27,11,z),'iron_bars[east=true,west=true,north=false,south=false,waterlogged=false]')
    m.point('landing','circulation',(15,11,21),'上部接驳桥',look_at=[10,13,21])
    m.point('lower','circulation',(10,2,15),'下部候运',look_at=[10,4,21])
    room(m,'register','下部登记装备室',4,4,12,12,'矿井出入登记、装备与值守');room(m,'shaft','提升井架',5,17,14,25,'围栏内吊台与导链，不能当作玩家电梯',maxy=19);room(m,'upper','上部候运检修室',18,17,24,23,'上部等候、卷扬维护和桥头登记',y=11,maxy=15)
    m.meta.update(roof_min_y=8,floors=[dict(name='下部接驳',y=1,max_y=5),dict(name='上部接驳',y=10,max_y=14)])
    m.meta['terrain']['高程']='下部脚底 Y=2，上部脚底 Y=11；九级三宽楼梯构成本体步行联系，上侧需匹配山地巷道。'
    m.meta['connections']=[dict(kind='pedestrian',pos=[27,11,21],direction='east',clearance=[3,3],note='上层东门外接平台；外部山道须与脚底 Y=11 齐平，本体已可由楼梯抵达。')]
    m.meta['design_notes']=['石台托起上层候运室，外侧九级宽阶连上下接驳；独立四柱井架围住不可步行吊台。']
    m.meta['differences']=['真实双高程步行联系；提升装置仅原版方块造型。']
    return m

def geothermal():
    m=base('MF-03-v01','赤脉地热炉厅',35,31,23)
    hall(m,3,3,31,26,height=9,roof='flat');front(m,17,wide=True)
    monumental_front(m,17,3,31)
    for z in range(6,24):
        m.box((15,11,z),(18,12,z),'gray_stained_glass')
        m.box((15,13,z),(18,13,z),'waxed_cut_copper_slab[type=bottom,waterlogged=false]')
    m.meta.update(roof_min_y=11,floors=[dict(name='炉厅作业层',y=1,max_y=6)])
    for x in (7,27):
        for z in (8,18):column(m,x,z,2,10,'polished_basalt','waxed_cut_copper')
    # Two hearth islands, open perimeter and central material lane.
    for x in (10,23):
        m.box((x-2,1,10),(x+2,1,16),'polished_blackstone_bricks')
        m.box((x-1,1,11),(x+1,1,15),'magma_block')
        for z in (10,16):m.box((x-2,2,z),(x+2,3,z),'polished_blackstone_bricks')
        m.box((x-2,2,11),(x-2,3,15),'blast_furnace[facing=west,lit=false]')
        m.box((x+2,2,11),(x+2,3,15),'blast_furnace[facing=east,lit=false]')
        chimney(m,x-1,12,5,19)
        for z in (11,15):m.box((x,4,z),(x,6,z),'chain[axis=y,waterlogged=false]')
        use(m,f'hearth{x}','work',x-2,2,12,'炉前检修与投料',x-3,12)
    for x in (5,15,28):m.set(x,2,21,'anvil[facing=north]');use(m,f'anvil{x}','work',x,2,21,'锻打工位',x,20)
    m.box((4,2,24),(11,2,24),'water_cauldron[level=3]');shelf(m,24,2,24,5)
    desk(m,4,2,5,'foreman','炉班记录');shelf(m,25,2,5,4,contents='bookshelf')
    for x in (11,22):pendant(m,x,8,7,11)
    m.box((15,2,26),(19,5,26),'air')
    m.point('rear','entrance',(17,2,27),'后部矿料卸货面')
    room(m,'hearths','双热井炉组',7,10,26,16,'两组封护热井、四侧炉门及独立检修环道',maxy=10);room(m,'forge','后部锻打与冷却',4,19,30,25,'三处铁砧、淬冷水盆、成品和工具储存');room(m,'control','炉前调度与工具带',4,4,30,8,'工序登记、工具与材料转运')
    m.meta['terrain']['前提']='仅适用于设定具有可利用地热且可隔离危险区域的场地；本体热井由岩浆块视觉表达，无熔岩流体。'
    m.meta['design_notes']=['两组高烟罩热井平行放置，中央宽材料通路和外缘检修环道相通；后排锻打冷却、前排登记工具。']
    m.meta['differences']=['宽跨工业厅、双热井、三锻台与前后分别装卸。']
    return m

def guild():
    m=base('MF-09-v01','铜砧锻造行会馆',33,31,22)
    hall(m,3,3,12,26,roof='hip');hall(m,20,3,29,26,roof='hip');hall(m,12,17,20,26,roof='gable')
    front(m,7);front(m,24);m.door(12,2,11,facing='east');m.door(20,2,11,facing='west');front(m,16,17,wide=True)
    # Interior cross doors make the council room reachable from both wings.
    m.door(12,2,21,facing='east');m.door(20,2,21,facing='west')
    desk(m,4,2,6,'clerk','会员接待与登记');shelf(m,4,2,24,6,contents='bookshelf')
    for z in (12,17):m.set(5,2,z,'smithing_table');use(m,f'teacher{z}','work',5,2,z,'教学锻台',6,z)
    m.set(9,2,17,'anvil[facing=north]');m.set(9,2,12,'grindstone[face=floor,facing=west]')
    for z,block in ((6,'gold_block'),(11,'diamond_block'),(16,'anvil'),(23,'amethyst_block')):
        m.set(27,2,z,'chiseled_deepslate');m.set(27,3,z,block)
    use(m,'display','work',27,3,11,'工艺陈列柜',26,11,ay=2)
    shelf(m,21,2,24,4,contents='bookshelf');desk(m,21,2,7,'archive','工艺档案桌')
    table(m,14,2,23,5);m.set(16,2,20,'lectern[facing=south,has_book=false,powered=false]')
    use(m,'council','work',16,2,20,'议事席',16,19)
    m.box((14,1,8),(18,1,12),'waxed_cut_copper');m.set(16,2,10,'polished_basalt');m.set(16,3,10,'anvil[facing=east]')
    room(m,'school','接待与技艺教室',4,4,11,25,'登记、双锻台示教、工具与教案');room(m,'museum','作品与工艺档案翼',21,4,28,25,'分列作品台、档案和研究桌');room(m,'council','后部议事厅',13,18,19,25,'围桌议事与行会宣讲');room(m,'court','铜砧纪念院',13,4,19,16,'进入议事厅的露天共享前院')
    m.meta['design_notes']=['双长翼夹铜砧前院，后部议事厅闭合 U 形；西教学、东陈列与档案各有独立街门。']
    m.meta['differences']=['工艺展示与实际工作台分翼，议事厅不穿过教学工位。']
    return m

def memorial():
    m=base('MF-10-v01','千锤祖先纪念厅',31,31,23)
    hall(m,3,3,27,21,height=9,roof='flat');front(m,15,wide=True)
    monumental_front(m,15,3,27)
    for x in (8,22):m.box((x,12,5),(x,13,19),'waxed_cut_copper')
    hall(m,3,21,14,27,roof='hip');hall(m,16,21,27,27,roof='hip')
    m.door(10,2,21);m.door(21,2,21)
    for x in (6,24):
        for z in (7,13,19):column(m,x,z,2,10,'polished_basalt','chiseled_deepslate')
    for x in (8,21):
        for z in (8,14):bench(m,x,2,z,2,'north')
    for x in (10,20):m.box((x,2,17),(x,4,17),'chiseled_deepslate');m.set(x,5,17,'anvil[facing=north]')
    m.box((13,2,17),(17,2,19),'waxed_cut_copper');m.set(15,3,18,'lectern[facing=north,has_book=false,powered=false]')
    use(m,'tribute','work',15,3,18,'族谱与祭仪台',15,16,ay=2)
    for x in (5,25):
        for z in (6,11,16):m.set(x,3,z,'chiseled_stone_bricks')
    shelf(m,4,2,26,8,contents='bookshelf');desk(m,5,2,23,'genealogy','族谱抄录')
    bunks(m,18,2,23,1,'keeper');kitchen(m,23,2,25);m.set(26,2,23,'barrel[facing=up,open=false]')
    for x in (10,20):pendant(m,x,7,11,11,soul=True)
    m.meta.update(roof_min_y=8,floors=[dict(name='纪念与守护层',y=1,max_y=6)])
    room(m,'memory','柱列纪念大厅',4,4,26,20,'祖先锻砧、族名碑、公共座位与仪式通道',maxy=10);room(m,'records','族谱档案室',4,22,13,26,'书册架与抄录');room(m,'keeper','守护者住所',17,22,26,26,'一人床位、衣柜、炊事与饮水')
    m.meta['terrain']['选址']='安静山前台地或山体外缘，采光面朝 -Z；本版本为独立地表纪念厅，不是地下挖掘模板。'
    m.meta['design_notes']=['六柱仪式厅正对铜色族谱台；两间后房分别存放族谱并供守护者完整生活。']
    m.meta['differences']=['安静公共纪念空间与后侧档案生活独立分区。']
    return m

def sealed_mine():
    m=base('MF-11-v01','封册旧矿口',31,29,19,role='structure')
    # Deliberately irregular independent rock fragment around a carved adit.
    for y in range(2,15):
        inset=max(0,(y-5)//3)
        m.box((13+inset,y,7+inset),(28-inset,y,26-inset),'tuff' if y%3 else 'stone')
    for x,y,z,w,h,d in ((12,2,14,3,6,5),(14,6,8,3,5,4),(24,2,6,4,7,4),(24,8,17,3,5,5),(18,12,13,5,4,6)):
        m.box((x,y,z),(min(29,x+w-1),y+h-1,min(27,z+d-1)),'andesite')
    m.box((18,2,7),(23,6,22),'air')
    for z in (7,12,17,22):
        for x in (18,23):m.box((x,2,z),(x,6,z),'stripped_spruce_log[axis=y]')
        m.box((18,6,z),(23,6,z),'spruce_log[axis=x]')
    m.box((19,2,21),(22,5,21),'iron_bars[east=true,west=true,north=false,south=false,waterlogged=false]')
    m.box((19,2,22),(22,4,25),'cobblestone');m.set(20,5,22,'coal_ore')
    for z in range(8,21):m.set(20,2,z,'rail[shape=north_south,waterlogged=false]')
    for z in (10,16):pendant(m,21,5,z,6)
    hall(m,3,3,12,19,roof='hip');front(m,7);m.door(12,2,12,facing='east')
    desk(m,5,2,6,'register','封矿登记');shelf(m,4,2,17,6);m.set(10,2,12,'smithing_table');use(m,'gear','work',10,2,12,'旧矿装备检查',9,12)
    m.point('boundary','circulation',(21,2,19),'封闭界线前检查位',look_at=[21,3,22])
    room(m,'register','登记与装备间',4,4,11,18,'旧矿巡查登记、装备、封矿记录');room(m,'adit','封闭旧巷',19,8,22,20,'保留轨道、坑木与巡视通路，末端栏栅和塌方为明确边界')
    m.meta['terrain']['选址']='仅用于山体前缘，+Z 旧巷尽端须接实山；岩体外壳是局部入口表达，不能悬放平原。'
    m.meta['terrain']['边界']='铁栅与塌方封住 Z=21 后段；本体没有可继续探索或矿车通行的出口。'
    m.meta['design_notes']=['不规则岩壳包住坑木旧轨巷；侧边铜顶登记装备房与矿口共用检修坪。']
    m.meta['differences']=['明确选用的封矿遗存，封闭边界而非重复生产矿井。']
    return m

def pump():
    m=base('MF-12-v01','岩底双泵排水站',31,29,20)
    hall(m,3,3,27,24,height=8,roof='flat');front(m,15,wide=True)
    monumental_front(m,15,3,27)
    for x in (7,22):
        m.box((x,10,7),(x+1,11,20),'gray_stained_glass')
        m.box((x,12,7),(x+1,12,20),'waxed_cut_copper_slab[type=bottom,waterlogged=false]')
    m.box((4,2,18),(11,5,18),WALL);m.door(8,2,18)
    desk(m,5,2,21,'duty','排水值守记录');shelf(m,4,2,23,5);m.bed(10,2,21,'gray',facing='south');use(m,'watch','bed',10,2,21,'应急值守床',11,21)
    for x in (8,21):
        m.box((x-2,1,9),(x+2,1,14),'polished_deepslate')
        m.box((x-1,1,10),(x+1,1,13),'water')
        for z in (9,14):m.box((x-2,2,z),(x+2,2,z),'stone_brick_wall')
        for px in (x-2,x+2):m.box((px,2,10),(px,2,13),'stone_brick_wall')
        m.box((x,2,11),(x,6,11),'waxed_cut_copper')
        m.box((x,6,11),(x,6,23),'waxed_cut_copper')
        m.box((x,6,23),(x,8,23),'waxed_cut_copper')
        m.set(x,8,24,'lightning_rod[facing=south,powered=false,waterlogged=false]')
        m.set(x-2,3,11,'lever[face=wall,facing=west,powered=false]')
        use(m,f'pump{x}','work',x-2,3,11,'泵组检修操作',x-3,11,ay=2)
    for x in (14,17):m.set(x,2,6,'water_cauldron[level=3]')
    shelf(m,22,2,22,4);m.set(23,2,18,'smithing_table');use(m,'repair','work',23,2,18,'备件维护',23,17)
    m.box((14,0,24),(17,0,27),'stone_bricks');m.box((14,1,24),(17,1,27),'water');m.box((14,2,24),(17,3,24),'iron_bars[east=true,west=true,north=false,south=false,waterlogged=false]')
    room(m,'pumps','双泵巡检厅',4,4,26,17,'两座封护集水池、铜管泵组和四边巡检通道');room(m,'duty','隔墙值班应急间',4,19,11,23,'值守床、登记与应急物资');room(m,'repair','备件与维护间',13,18,26,23,'工具台、备用件和管线后部检修')
    m.meta.update(roof_min_y=10,floors=[dict(name='泵组巡检层',y=1,max_y=5)])
    m.meta['terrain']['服务']='仅用于存在地下水或汇水需求的低位矿业设施；尾部排水口必须连接有坡降的安全受水去向。'
    m.meta['connections']=[dict(kind='drain',pos=[15,1,27],direction='south',clearance=[4,2],note='示意水槽终点；泵送、流量及外部排水均未模拟。')]
    m.meta['design_notes']=['两组铜管泵架各有独立围护池与巡检环路，后角值守卧位和应急物资隔墙，尾部低位出水槽。']
    m.meta['differences']=['低位排水服务节点，水槽和人员地坪分层。']
    return m

BUILDERS={f'MF-{i:02d}-v01':fn for i,fn in [(1,mine_station),(2,lift_tower),(3,geothermal),(9,guild),(10,memorial),(11,sealed_mine),(12,pump)]}
