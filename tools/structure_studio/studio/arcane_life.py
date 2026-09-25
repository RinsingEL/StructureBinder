"""Arcane domestic life: twelve independent ground-level arrangements."""
from functools import partial
from .arcane_academy import base as civic_base,hall,door,kitchen,dining,storage,counter,bed,entry,room
from .components import bench


def start(family,v,name,w,d,module='arcane_life'):
    m=civic_base(family,name,w,d,h=26,role='fill',site='稳定干燥的学院生活街区；整块人工石基，外部道路接普通步行入口')
    m.meta.update(id=f'{family}-v{v:02}',source=f'tools/structure_studio/studio/{module}.py',lifecycle='authored')
    m.meta['terrain']['模板落地']='基础Y=0..2落在连续承载层；不是自然坡地自适应，不用于未经整平的岸边与水面。'
    return m


def sitting(m,key,x,z):
    m.set(x,4,z,'dark_oak_stairs[facing=west]');m.set(x,4,z+2,'dark_oak_stairs[facing=west]')
    m.set(x-2,4,z+1,'dark_oak_slab[type=top]');m.set(x-2,5,z+1,'lantern')
    m.box((x+1,4,z),(x+1,5,z+2),'bookshelf')
    m.box((x-1,4,z),(x-1,4,z+2),'purple_carpet')
    m.point(key,'work',(x+1,5,z+1),'窗边共读、茶几与生活书柜',approach=(x,4,z+1))


def shell_room(m,key,x,z,w,d,label,purpose,side='north'):
    hall(m,x,z,x+w,z+d)
    dx,dz,face=(x+w//2,z,'north') if side=='north' else (x+w,z+d//2,'east') if side=='east' else (x,z+d//2,'west') if side=='west' else (x+w//2,z+d,'south')
    door(m,dx,dz,face)
    room(m,key,label,x+1,z+1,x+w-1,z+d-1,purpose)


def commons(m,key,x,z,w=16,d=16,side='north'):
    shell_room(m,key,x,z,w,d,'共用厨房起居厅','连续厨房、成套餐桌、坐读书柜、餐具衣物存放',side)
    kitchen(m,key+'_cook',x+2,z+2);storage(m,key+'_food',x+9,z+2,5)
    dining(m,x+2,z+7,6);sitting(m,key+'_read',x+w-3,z+d-5)
    storage(m,key+'_linen',x+2,z+d-2,5,side=-1)
    m.point(key+'_meal','work',(x+4,4,z+7),'完整共餐桌和对向座椅',approach=(x+4,4,z+6))


def bedroom(m,key,x,z,w=12,d=12,count=2,side='north'):
    shell_room(m,key,x,z,w,d,'独立寝室','隔门寝室；每床独立床头灯、个人柜和短隐私屏，前部书写与盥洗',side)
    for i in range(count):bed(m,f'{key}_{i+1}',x+3+i*4,z+d-3)
    counter(m,key+'_desk',x+2,z+2,5,'lectern[facing=south]','寝室个人书写与整理')
    m.set(x+w-2,4,z+2,'water_cauldron[level=3]');storage(m,key+'_wardrobe',x+w-4,z+5,3,side=-1)
    m.set(x+3,4,z+4,'dark_oak_stairs[facing=north]')


def research(m,key,x,z,w=12,d=13,side='north',communal=False):
    shell_room(m,key,x,z,w,d,'公共自习书室' if communal else '隔门独立研究室','前部书写、分类书物、样本与操作台，区别于家庭起居',side)
    counter(m,key+'_write',x+2,z+2,w-4,'lectern[facing=south]','术式抄写和独立研究')
    for dx in range(2,w-2,3):m.set(x+dx,4,z+4,'dark_oak_stairs[facing=north]')
    counter(m,key+'_work',x+2,z+7,w-4,'cartography_table' if communal else 'brewing_stand','资料展开与研究操作')
    storage(m,key+'_books',x+2,z+d-2,w-4,'bookshelf',side=-1)


# Separate exterior masses, entrances and courtyards; not rotations or recolors.
PLANS=[
 ((4,5,16,16),(24,5,12,12),(24,21,12,13),'前后错置小独栋'),
 ((4,5,18,16),(4,25,14,12),(24,9,12,15),'折翼书庭住宅'),
 ((4,5,16,18),(24,5,12,14),(40,5,14,11),'三间窄长街宅'),
 ((4,23,18,18),(4,5,14,12),(24,5,14,15),'北庭研究宅'),
 ((20,5,18,18),(4,25,12,14),(20,25,16,13),'偏轴前厅后院宅'),
 ((4,5,16,18),(24,24,14,14),(4,25,14,15),'双院错落法师宅')]


def home(v):
    a,b,c,name=PLANS[v-1];w=max(r[0]+r[2] for r in (a,b,c))+5;d=max(r[1]+r[3] for r in (a,b,c))+5
    m=start('AA-07',v,name,w,d)
    commons(m,'home',*a);bedroom(m,'sleep',*b);research(m,'research',*c)
    entry(m,a[0]+a[2]//2,3)
    m.meta['differences']=[name,'三个独立有门的家庭公共厅、寝室和研究翼；院路从外部普通步行串联']
    return m


DORMS=[
 ((4,5,16,16),[(24,5,12,12)],(24,21,12,13),'双人书院舍'),
 ((4,5,16,16),[(24,5,14,12),(24,21,14,14)],(4,25,12,13),'四人折翼舍'),
 ((4,5,16,16),[(24,5,12,14),(40,5,14,12)],(24,21,12,13),'通街四人舍'),
 ((20,5,16,16),[(4,25,12,12),(20,25,12,12),(36,25,12,12)],(40,5,12,13),'六人南庭舍'),
 ((4,23,18,18),[(4,5,12,14),(20,5,14,12)],(26,23,14,15),'双院四人舍'),
 ((20,21,18,18),[(4,5,12,12),(20,5,12,12),(36,5,14,12)],(4,21,12,15),'六人后厅舍')]


def dorm(v):
    a,b,c,name=DORMS[v-1];rs=[a,*b,c];w=max(r[0]+r[2] for r in rs)+5;d=max(r[1]+r[3] for r in rs)+5
    m=start('AA-06',v,name,w,d);commons(m,'common',*a)
    for i,r in enumerate(b):bedroom(m,f'sleep{i+1}',*r)
    research(m,'study',*c,communal=True);entry(m,w//2,3)
    m.meta['differences']=[name,f'{len(b)*2}人，{len(b)}间隔门双床寝室；每人床头灯和个人储物，共用厨房自习另置']
    return m


BUILDERS={**{f'AA-07-v{v:02}':partial(home,v) for v in range(1,7)},**{f'AA-06-v{v:02}':partial(dorm,v) for v in range(1,7)}}

# Revised academy domestic architecture; legacy garden helpers above retain behaviour.
from .components import shell,window,hip_roof


def academy_start(family,v,name,w,d):
 m=civic_base(family,name,w,d,h=42,role='fill',site='干燥稳定的学院街区；石基落连续承载层，北侧接普通步行街道')
 m.meta.update(id=f'{family}-v{v:02}',source='tools/structure_studio/studio/arcane_life.py',roof_min_y=17,floors=[dict(name='公共生活层',y=3,max_y=9),dict(name='私密上层',y=10,max_y=16)])
 return m


def academy_shell(m,x,z,w,d,f=3,h=6):
 shell(m,(x,f,z),(x+w,f+h,z+d),'calcite',floor='spruce_planks',ceiling='polished_diorite')
 for xx in (x,x+w):
  for zz in (z,z+d):m.box((xx,f+1,zz),(xx,f+h+1,zz),'polished_deepslate')
 for zz in (z,z+d):
  for xx in range(x+3,x+w-1,5):window(m,(xx,f+2,zz),(min(xx+1,x+w-1),f+4,zz),color='purple_stained_glass')
 for xx in (x,x+w):
  for zz in range(z+3,z+d-1,5):window(m,(xx,f+2,zz),(xx,f+4,zz+1),axis='z',color='purple_stained_glass')
 for zz in (z,z+d):
  for xx in range(x+3,x+w-1,5):m.box((xx,f+1,zz),(min(xx+1,x+w-1),f+1,zz),'polished_blackstone_brick_slab[type=top]')
 for xx in (x,x+w):
  for zz in range(z+3,z+d-1,5):m.box((xx,f+1,zz),(xx,f+1,zz+1),'polished_blackstone_brick_slab[type=top]')


def steep_roof(m,x,z,w,d,y):
 # Continuous triangular gable with masonry ends and overhanging stair eaves.
 for dx in range(w//2+1):
  for xx,face in ((x+dx,'east'),(x+w-dx,'west')):
   for zz in range(z-1,z+d+2):m.set(xx,y+dx,zz,f'dark_oak_stairs[facing={face}]')
  if x+dx+1<=x+w-dx-1:
   for zz in (z,z+d):m.box((x+dx+1,y+dx,zz),(x+w-dx-1,y+dx,zz),'purple_terracotta')
 for zz in (z,z+d):
  m.box((x+1,y,zz),(x+w-1,y,zz),'dark_oak_log[axis=x]')
  m.box((x+w//2,y,zz),(x+w//2,y+w//2-1,zz),'dark_oak_log[axis=y]')
  for i in range(1,w//2):
   for xx in (x+i,x+w-i):m.set(xx,y+i-1,zz,'dark_oak_wood')
  if w>=14:
   for xx in (x+w//2-3,x+w//2+2):m.box((xx,y+2,zz),(xx+1,y+4,zz),'purple_stained_glass')


def indoor_stairs(m,x,z,rise=7,f=3,width=3):
 for i in range(rise):
  m.box((x,f+1,z+i),(x+width-1,f+1+i,z+i),'polished_deepslate')
  for xx in range(x,x+width):m.set(xx,f+1+i,z+i,'dark_oak_stairs[facing=south]')
  m.set(x-1,f+2+i,z+i,'dark_oak_fence');m.set(x+width,f+2+i,z+i,'dark_oak_fence')


def tower_home():
 m=academy_start('AA-07',1,'双层书阁与研究尖塔宅',39,37)
 academy_shell(m,5,7,19,22);academy_shell(m,5,7,19,22,f=10)
 steep_roof(m,4,6,21,24,18)
 # Small attached research tower, supported from foundation to study floor.
 m.box((25,0,18),(34,10,30),'polished_deepslate');academy_shell(m,25,18,9,12,f=10,h=10)
 for i in range(6):
  m.box((24+i,22+2*i,17+i),(35-i,23+2*i,31-i),'polished_blackstone_bricks')
 m.box((29,34,23),(30,35,25),'amethyst_block');m.set(29,36,24,'end_rod')
 door(m,14,7)
 m.box((13,7,6),(16,7,6),'polished_blackstone_brick_slab[type=top]')
 # Interior stair opening and complete balustrade around its upper void.
 m.box((20,10,10),(22,10,16),'air');indoor_stairs(m,20,10)
 m.box((19,11,9),(23,11,9),'dark_oak_fence')
 for z in range(10,17):m.set(19,11,z,'dark_oak_fence');m.set(23,11,z,'dark_oak_fence')
 kitchen(m,'cook',7,9);storage(m,'food',7,12,5);dining(m,7,18,6)
 sitting(m,'read',15,24);storage(m,'household',18,26,4,side=-1)
 room(m,'living','连续厨房起居与室内楼梯',6,8,23,28,'前厨储粮、共餐、书架坐读和后勤用品；东侧室内楼梯连接楼上')
 # Private bedroom behind an actual full-height partition.
 m.box((6,11,20),(18,16,20),'calcite');door(m,12,20,f=10)
 bed(m,'bed',9,26,f=10);storage(m,'wardrobe',13,27,4,f=10,side=-1)
 counter(m,'private_desk',12,23,5,'lectern[facing=south]','床边个人书写',f=10);m.set(15,11,23,'water_cauldron[level=3]')
 counter(m,'upstairs_read',7,11,8,'cartography_table','楼上课程资料与家庭书阁',f=10);storage(m,'upper_books',7,17,8,'bookshelf',f=10,side=-1)
 room(m,'bedroom','楼上隔门卧室',6,21,18,28,'单床个人灯柜、衣柜、书写与盥洗',f=10)
 room(m,'book_gallery','二层书阁与走廊',6,8,23,19,'书目长案、书柜与受护栏围护的楼梯口',f=10)
 # Adjacent tower doorway crosses both wall skins at the same level.
 for xx in (24,25):m.box((xx,11,23),(xx,13,24),'air')
 counter(m,'research',27,21,5,'brewing_stand','独立研究塔的配制与实验',f=10)
 storage(m,'tower_books',27,28,5,'bookshelf',f=10,side=-1)
 m.set(32,11,24,'enchanting_table');m.point('instrument','work',(32,11,24),'塔内符文器具观察',approach=(31,11,24))
 room(m,'tower','独立高窗研究尖塔',26,19,33,29,'高窗研究、术式分类与静态器具，直接接二层书廊',f=10,h=10)
 entry(m,14,3);m.meta['differences']=['紧凑双层家庭主体附一座窄研究尖塔；室内楼梯、楼上隔门卧室与家庭书阁，生活空间不拆成三栋']
 return m


def corridor_dorm():
 m=academy_start('AA-06',1,'拱廊双寝学院宿舍',47,41)
 academy_shell(m,5,7,36,28,h=6)
 # Rear two sleeping rooms and front common rooms share the same enclosed corridor.
 m.box((6,4,21),(40,9,21),'calcite');m.box((23,4,22),(23,9,34),'calcite')
 m.box((6,4,16),(40,9,16),'calcite')
 for x,z in ((14,7),(14,16),(32,16),(14,21),(32,21)):door(m,x,z)
 m.box((23,4,8),(23,9,15),'calcite')
 kitchen(m,'common_cook',7,9);storage(m,'food',15,9,5);dining(m,8,13,6)
 counter(m,'study',26,9,11,'lectern[facing=south]','公共课程自习长案')
 for x in (27,31,35):m.set(x,4,12,'dark_oak_stairs[facing=north]')
 storage(m,'coursebooks',26,14,10,'bookshelf',side=-1)
 for n,x in enumerate((6,24),1):
  bed(m,f'bed{n}a',x+4,30);bed(m,f'bed{n}b',x+11,30)
  storage(m,f'lockers{n}',x+3,33,10,side=-1)
  counter(m,f'desk{n}',x+3,24,9,'lectern[facing=south]','寝室双人书写与整理')
  m.set(x+13,4,26,'water_cauldron[level=3]')
  room(m,'sleep'+str(n),f'独立双人寝室{n}',x+1,22,x+16,34,'每人床头个人柜、双人书桌、衣柜及盥洗；公共走廊独立入门')
 room(m,'kitchen','公共厨房餐厅',6,8,22,15,'连续备餐、食品存放和成套餐席')
 room(m,'study','公共课程自习室',24,8,40,15,'多人自习席和课程书柜')
 room(m,'corridor','贯通公共走廊',6,17,40,20,'前部公共厅到两间独立寝室，不穿越他人床位')
 # Distinct high rear sleeping roof and lower front porch/classroom roof.
 for dz in range(9):
  for zz,face in ((20+dz,'south'),(36-dz,'north')):
   for xx in range(4,43):m.set(xx,11+dz,zz,f'dark_oak_stairs[facing={face}]')
  if 21+dz<=35-dz:
   for xx in (5,41):m.box((xx,11+dz,21+dz),(xx,11+dz,35-dz),'purple_terracotta')
 hip_roof(m,4,42,6,16,11,material='polished_blackstone_brick',tiers=4)
 for xx in (5,41):
  m.box((xx,11,21),(xx,11,35),'dark_oak_log[axis=z]')
  m.box((xx,11,28),(xx,18,28),'dark_oak_log[axis=y]')
  for i in range(1,8):
   for zz in (20+i,36-i):m.set(xx,10+i,zz,'dark_oak_wood')
  for zz in (25,30):m.box((xx,13,zz),(xx,15,zz+1),'purple_stained_glass')
 # Public entrance is reached through a five-bay stone arcade.
 for x in (6,12,18,24,30,36,40):m.box((x,4,4),(x,9,4),'polished_deepslate')
 m.box((6,9,4),(40,9,4),'polished_deepslate');m.box((6,10,4),(40,10,6),'dark_oak_slab[type=bottom]')
 for x in (8,14,20,26,32,38):m.set(x,8,4,'polished_blackstone_brick_stairs[facing=east]')
 room(m,'arcade','低檐公共入口拱廊',7,4,39,6,'实体柱与拱端承托低入口廊，通往公共厨房和贯通走廊',h=6)
 m.meta['floors']=[dict(name='单层分室宿舍',y=3,max_y=9)];m.meta['roof_min_y']=10
 entry(m,14,3);m.meta['differences']=['单栋四人宿舍以贯通公共走廊连接两间独立双人寝室；公共厨房、自习室和前部低拱廊，寝室翼高陡坡屋顶']
 return m

BUILDERS['AA-07-v01']=tower_home
BUILDERS['AA-06-v01']=corridor_dorm


def domestic_fit(m,key,x,z,w,d,f=3,study=True):
 kitchen(m,key+'_cook',x+2,z+2,f);storage(m,key+'_food',x+9,z+2,5,f=f)
 dining(m,x+2,z+8,6,f)
 counter(m,key+'_study',x+2,z+d-3,min(w-5,10),'lectern[facing=south]','课程书写与共读',f)
 storage(m,key+'_books',x+w-5,z+6,3,'bookshelf',f)
 for zz in (z+10,z+12):m.set(x+w-3,f+1,zz,'dark_oak_stairs[facing=west]')
 m.set(x+w-5,f+1,z+11,'dark_oak_slab[type=top]')
 room(m,key,'厨房餐厅与坐读书室',x+1,z+1,x+w-1,z+d-1,'连续厨房、共餐座椅、书物衣物与课程书写',f)


def sleeping_fit(m,key,x,z,w,d,f=3,count=2):
 for i in range(count):bed(m,key+str(i),x+4+i*5,z+d-4,f)
 counter(m,key+'_desk',x+2,z+2,min(9,w-5),'lectern[facing=south]','寝室书写与个人整理',f)
 storage(m,key+'_clothes',x+2,z+d-2,min(11,w-4),f=f,side=-1)
 m.set(x+w-2,f+1,z+4,'water_cauldron[level=3]')
 room(m,key,'独立寝室',x+1,z+1,x+w-1,z+d-1,'隔门床位、床头个人柜、衣柜、书桌和盥洗',f)


def cross_door(m,x,z,face='north',f=3):
 door(m,x,z,face,f)
 if face in ('north','south'):m.box((x-1,f+4,z),(x+2,f+4,z),'polished_blackstone_brick_slab[type=top]')


def stair_access(m,x,z,f=3):
 m.box((x,f+7,z),(x+2,f+7,z+6),'air');indoor_stairs(m,x,z,f=f)
 m.box((x-1,f+8,z-1),(x+3,f+8,z-1),'dark_oak_fence')
 for zz in range(z,z+7):
  for xx in (x-1,x+3):m.set(xx,f+8,zz,'dark_oak_fence')


def revised_home(v):
 names={2:'窄街联排书阁宅',3:'转角折翼小院宅',4:'高窗书室与低檐寝宅',5:'长屋夹层阁楼宅',6:'内庭回廊家庭宅'}
 m=academy_start('AA-07',v,names[v],52 if v in (3,6) else 43,49)
 if v==2:
  # Narrow frontage, deep ground floor and smaller upper rear bedroom.
  academy_shell(m,6,7,22,33);academy_shell(m,6,19,22,21,f=10)
  steep_roof(m,5,18,24,23,18);hip_roof(m,5,29,6,18,11,tiers=3)
  domestic_fit(m,'common',6,7,17,18);m.box((7,4,27),(23,9,27),'calcite');cross_door(m,14,27)
  counter(m,'research',8,30,12,'brewing_stand','后部隔门研究桌');storage(m,'samples',8,37,11,'bookshelf',side=-1)
  room(m,'research_room','底层后部研究间',7,28,27,39,'研究与样本隔门组织')
  stair_access(m,24,21);sleeping_fit(m,'sleep',6,19,17,21,10,1)
  cross_door(m,15,7);entry(m,15,3)
 elif v==3:
  academy_shell(m,5,7,21,20);academy_shell(m,26,7,20,34)
  steep_roof(m,4,6,23,22,11);steep_roof(m,25,6,22,36,11)
  domestic_fit(m,'common',5,7,21,20)
  m.box((27,4,24),(45,9,24),'calcite');cross_door(m,35,24)
  counter(m,'research',29,10,12,'brewing_stand','街角研究与课程准备');storage(m,'research_books',29,20,12,'bookshelf',side=-1)
  room(m,'research_room','转角研究厅',27,8,45,23,'接起居厅与后部私密卧室')
  sleeping_fit(m,'sleep',26,24,20,17,count=1);cross_door(m,26,16,'east')
  cross_door(m,15,7);entry(m,15,3)
  for x in (7,13,19):m.box((x,4,30),(x,8,30),'dark_oak_log');m.box((x,8,28),(x,8,38),'dark_oak_log[axis=z]')
  m.box((6,8,30),(24,8,30),'dark_oak_log[axis=x]');room(m,'yard','折翼外侧阅读小院',6,28,24,39,'木架遮阴、院路与回望高山墙')
 elif v==4:
  academy_shell(m,6,7,27,19,h=10);academy_shell(m,6,26,27,16)
  steep_roof(m,5,6,29,21,15);hip_roof(m,5,34,25,43,11,tiers=4)
  domestic_fit(m,'common',6,7,20,19);counter(m,'high_study',28,10,3,'brewing_stand','高窗书室的独立研究台')
  sleeping_fit(m,'sleep',6,26,27,16,count=1);cross_door(m,20,26)
  storage(m,'private_books',25,35,5,'bookshelf');cross_door(m,18,7);entry(m,18,3)
 elif v==5:
  academy_shell(m,6,7,27,34);academy_shell(m,6,24,27,17,f=10)
  steep_roof(m,5,23,29,19,18);hip_roof(m,5,34,6,23,11,tiers=4)
  domestic_fit(m,'common',6,7,21,18)
  counter(m,'rear_work',8,29,14,'brewing_stand','夹层下独立实验与样本整理');storage(m,'samples',8,38,14,'bookshelf',side=-1)
  room(m,'workroom','阁楼下研究作间',7,26,27,40,'后部研究长案和样本柜；东侧楼梯独立通往阁楼')
  stair_access(m,29,25);sleeping_fit(m,'sleep',6,24,22,17,10,1)
  cross_door(m,18,7);entry(m,18,3)
 else:
  academy_shell(m,5,7,40,17);academy_shell(m,5,24,17,18);academy_shell(m,28,24,17,18)
  steep_roof(m,4,23,19,20,11);steep_roof(m,27,23,19,20,11);hip_roof(m,4,46,6,24,11,tiers=4)
  domestic_fit(m,'common',5,7,24,17);counter(m,'front_study',32,10,10,'lectern[facing=south]','家庭课程与起居阅读');storage(m,'front_books',32,20,10,'bookshelf',side=-1)
  sleeping_fit(m,'sleep',5,24,17,18,count=1);cross_door(m,13,24)
  counter(m,'research',30,27,11,'brewing_stand','内庭侧实验书写');storage(m,'research_books',30,38,11,'bookshelf',side=-1)
  room(m,'research_room','内庭研究翼',29,25,44,41,'独立研究、分类样本和内庭采光');cross_door(m,36,24)
  cross_door(m,25,24);cross_door(m,25,7);entry(m,25,3)
  room(m,'court','两翼围合内庭',23,25,27,40,'朝后开敞的狭长内庭，三翼实体连续相接')
 if v in (3,4,6):m.meta.update(floors=[dict(name='连通生活主层',y=3,max_y=9)],roof_min_y=10)
 m.meta['differences']=[names[v],'共用生活、研究和私密床位按相连主体组织；山墙木构框与窗台，完整个人储物和真实通路']
 return m


def revised_dorm(v):
 names={2:'双层通廊三寝宿舍',3:'转角三寝学院宿舍',4:'三翼小院四人宿舍',5:'街面联排双寝宿舍',6:'高低翼六人学院宿舍'}
 m=academy_start('AA-06',v,names[v],56,53)
 if v==2:
  academy_shell(m,6,7,40,37);academy_shell(m,6,7,40,37,f=10)
  # Upper four bedrooms flank a central corridor; ground floor carries services.
  hip_roof(m,5,47,6,45,18,tiers=5)
  domestic_fit(m,'common',6,7,24,20);counter(m,'seminar',8,33,20,'lectern[facing=south]','共用课程研讨长桌');storage(m,'library',8,41,20,'bookshelf',side=-1)
  for xx in (9,17,25):
   for zz in (31,35):m.set(xx,4,zz,'dark_oak_stairs[facing=south]' if zz==31 else 'dark_oak_stairs[facing=north]')
  m.box((32,4,25),(45,9,25),'calcite');cross_door(m,37,25)
  kitchen(m,'laundry',34,29);storage(m,'linen',34,39,9,side=-1)
  room(m,'utility','公共盥洗与布品间',33,26,45,43,'共用水盆、整理台和分格布品柜')
  room(m,'seminar_room','公共研讨与洗衣层',7,29,31,43,'课程讨论、书物和楼梯前公共活动')
  stair_access(m,40,10)
  for n,(x,z,d) in enumerate(((6,7,15),(6,28,16),(28,28,16)),1):
   academy_shell(m,x,z,18,d,f=10);sleeping_fit(m,'sleep'+str(n),x,z,18,d,10,1)
   cross_door(m,x+9,z+d,'south',10) if z==7 else cross_door(m,x+9,z,'north',10)
  counter(m,'upper_read',28,20,11,'lectern[facing=south]','楼梯前公共阅读',10)
  room(m,'upper_landing','二层公共通廊',7,23,45,27,'楼梯上端通往三间独立寝室',f=10)
  cross_door(m,25,7);entry(m,25,3)
 elif v==3:
  academy_shell(m,5,7,24,20);academy_shell(m,29,7,19,38);academy_shell(m,5,27,16,18)
  hip_roof(m,4,30,6,28,11,tiers=4);steep_roof(m,28,6,21,40,11);steep_roof(m,4,26,18,20,11)
  domestic_fit(m,'common',5,7,24,20)
  m.box((30,4,25),(47,9,25),'calcite')
  for n,(x,z,w,d) in enumerate(((29,7,19,18),(29,25,19,20),(5,27,16,18)),1):
   sleeping_fit(m,'sleep'+str(n),x,z,w,d,count=1)
  cross_door(m,29,17,'east');cross_door(m,29,35,'west');cross_door(m,13,27)
  cross_door(m,25,27);cross_door(m,16,7);entry(m,16,3)
  for zz in (29,35,42):m.box((25,4,zz),(25,8,zz),'polished_deepslate')
  m.box((25,9,27),(28,9,44),'dark_oak_slab[type=bottom]')
  room(m,'corridor','转角院内公共廊',22,28,28,44,'从主厅经带顶院廊独立进入后侧寝室，无需穿越前寝室')
 elif v==4:
  academy_shell(m,5,7,44,17);academy_shell(m,5,24,18,21);academy_shell(m,31,24,18,21)
  hip_roof(m,4,50,6,24,11,tiers=4);steep_roof(m,4,23,20,23,11);steep_roof(m,30,23,20,23,11)
  domestic_fit(m,'common',5,7,26,17);counter(m,'study',35,10,11,'lectern[facing=south]','共用自习');storage(m,'books',35,20,11,'bookshelf',side=-1)
  for n,x in enumerate((5,31),1):sleeping_fit(m,'sleep'+str(n),x,24,18,21);cross_door(m,x+9,24)
  cross_door(m,27,24);cross_door(m,27,7);entry(m,27,3)
  room(m,'court','宿舍内庭',24,25,30,44,'围合内庭连向主厅，寝室不相互穿行')
 elif v==5:
  academy_shell(m,7,7,24,36);academy_shell(m,7,7,24,36,f=10)
  steep_roof(m,6,6,26,38,18);domestic_fit(m,'common',7,7,18,20)
  counter(m,'rear_study',9,32,13,'lectern[facing=south]','后部公共自习');storage(m,'books',9,40,13,'bookshelf',side=-1)
  stair_access(m,27,10)
  for n,z in enumerate((7,25),1):
   m.box((8,11,z+17),(25,16,z+17),'calcite');sleeping_fit(m,'sleep'+str(n),7,z,19,17,10,2)
   m.box((26,11,z+1),(26,16,z+16),'calcite');cross_door(m,26,z+9,'east',10)
  room(m,'hall','上层纵向公共走廊',27,8,30,42,'两间双人寝室通过共用走廊到楼梯',f=10)
  cross_door(m,18,7);entry(m,18,3)
 else:
  academy_shell(m,5,7,44,19,h=9);academy_shell(m,5,26,44,20)
  hip_roof(m,4,50,6,27,14,tiers=5)
  domestic_fit(m,'common',5,7,27,19);counter(m,'study',35,10,11,'lectern[facing=south]','高窗公共自习');storage(m,'books',35,22,11,'bookshelf',side=-1)
  for n,x in enumerate((5,20,35),1):
   academy_shell(m,x,29,14,17);sleeping_fit(m,'sleep'+str(n),x,29,14,17,count=2);cross_door(m,x+7,29)
   steep_roof(m,x-1,28,16,19,11)
  room(m,'corridor','后翼分寝公共走廊',6,27,48,28,'三间双人寝室的独立入门通路');cross_door(m,25,26);cross_door(m,25,7);entry(m,25,3)
 if v in (3,4,6):m.meta.update(floors=[dict(name='共用生活和分寝主层',y=3,max_y=9)],roof_min_y=10)
 m.meta['differences']=[names[v],'公共生活与各寝室通过门和连续走廊组织；每床个人灯柜，共用餐厨、自习与洗漱完整']
 return m


BUILDERS.update({f'AA-07-v{v:02}':partial(revised_home,v) for v in range(2,7)})
BUILDERS.update({f'AA-06-v{v:02}':partial(revised_dorm,v) for v in range(2,7)})
