"""Ocean fantasy dwellings: faceted shell vaults, branched plans and dry tidal decks."""
from .model import Model
from .components import bench
from .tidal_coral import deck,door,room,entry,work,store,meal,kitchen,bed,rail


def start(family,v,name,w,d,h=36):
 m=Model(f'{family}-v{v:02}',name,(w,h,d),family=family,civilization='海洋幻想',role='fill',terrain={
 '选址':'避风潮上岸台或人工桩台；水侧支柱底Y=0必须接稳定海床，不自动适应任意水深',
 '潮位':'低潮Y=3、参考水面Y=5、预期高潮Y=6；主层干地板Y=7、脚底Y=8，潮汐仅为条件记录',
 '支撑接驳':'北侧接Y=8普通步行道路；所有楼梯为实体台阶。高层与分台另有房间高程，不依赖跳跃或游泳',
 '供水生活':'厨房淡水和食物依岸上补给；海水不能视为饮用或灌溉水。居室采用干燥外壳，气候通风与实际水密另验',
 '风格':'棱面近似贝壳的弧顶、放射或分瓣舱室、青绿石质桩台；风格不是国名',
 '限制':'原版静态模型；不代表潮汐、潜水呼吸、流体、生产或运行时生成已接入'})
 m.meta.update(source='tools/structure_studio/studio/tidal_life.py',roof_min_y=14,floors=[dict(name='干式生活层',y=7,max_y=13)],preview_context=dict(kind='shore',shore_z=max(12,d//2),water_surface_y=5,land_surface_y=8,bed_y=-1,padding=4,surface='sand'))
 deck(m,2,2,w-3,d-3)
 rail(m,2,d//2,2,d-3);rail(m,w-3,d//2,w-3,d-3);rail(m,2,d-3,w-3,d-3)
 return m


def footprint(x,z,w,d):
 for zz in range(z,z+d+1):
  cut=max(0,3-min(zz-z,z+d-zz))
  for xx in range(x+cut,x+w-cut+1):yield xx,zz


def pod(m,x,z,w=18,d=18,f=7):
 cells=set(footprint(x,z,w,d))
 for xx,zz in cells:
  edge=any((xx+dx,zz+dz) not in cells for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)))
  m.set(xx,f,zz,'birch_planks')
  for y in range(f+1,f+7):m.set(xx,y,zz,'smooth_quartz' if edge else 'air')
  if edge:
   if (xx in (x,x+w) and z+5<=zz<=z+d-5) or (zz in (z,z+d) and x+5<=xx<=x+w-5):
    m.box((xx,f+2,zz),(xx,f+4,zz),'cyan_stained_glass')
   m.set(xx,f+6,zz,'dark_prismarine')
  m.set(xx,f+7,zz,'prismarine_bricks')
 # Faceted shell dome: successive closed tiers, radial pale ribs remain attached.
 for layer in range(1,5):
  margin=layer*2
  if w-2*margin<2 or d-2*margin<2:break
  for xx,zz in footprint(x+margin,z+margin,w-2*margin,d-2*margin):
   m.set(xx,f+7+layer,zz,'smooth_quartz' if xx==x+w//2 or zz==z+d//2 else 'waxed_oxidized_copper')
 # The front shell fan is attached to the wall and spans its door.
 cx=x+w//2
 for dx in range(-3,4):m.set(cx+dx,f+5+(3-abs(dx))//2,z-1,'smooth_quartz')
 m.set(cx,f+8,z+d//2,'sea_lantern')


def common(m,k,x,z,w=18,d=18,f=7):
 pod(m,x,z,w,d,f);door(m,x+w//2,z,f)
 kitchen(m,k+'_cook',x+3,z+3,f);store(m,k+'_food',x+w-7,z+3,4,f=f)
 meal(m,x+4,z+9,f)
 store(m,k+'_books',x+3,z+d-3,5,'bookshelf',f=f,side=-1)
 bench(m,x+w-7,f+1,z+8,3,'south','birch');bench(m,x+w-7,f+1,z+13,3,'north','birch')
 m.box((x+w-7,f+1,z+10),(x+w-5,f+1,z+10),'birch_slab[type=top]');m.set(x+w-6,f+2,z+10,'flower_pot')
 m.box((x+9,f+1,z+d-4),(x+11,f+1,z+d-3),'cyan_carpet')
 room(m,k,'贝舱共居起居间',x+2,z+2,w-4,d-4,'连续厨房与食品柜、六席餐桌、面对面坐读、书柜与家务地毯',f)


def sleeping(m,k,x,z,w=14,d=14,f=7,beds=2):
 pod(m,x,z,w,d,f);door(m,x+w//2,z,f)
 bed(m,k+'a',x+4,z+d-5,f)
 if beds==2:bed(m,k+'b',x+w-4,z+d-5,f)
 store(m,k+'_clothes',x+3,z+3,4,f=f)
 work(m,k+'_desk',x+w-6,z+4,3,'lectern[facing=south]','个人书写与物品',f)
 m.set(x+w-4,f+1,z+6,'birch_stairs[facing=north]');m.set(x+3,f+1,z+d-3,'water_cauldron[level=3]')
 # Low opaque screens at each bed head separate the personal corners.
 m.box((x+2,f+1,z+d-7),(x+2,f+3,z+d-4),'birch_planks')
 room(m,k,'独门休眠贝舱',x+2,z+2,w-4,d-4,'个人床头灯柜、衣物柜、书写座位、洗盆与低屏，普通木门保障私密',f)


def coral_court(m,k,x,z,w=9,d=9):
 m.box((x,5,z),(x+w,7,z+d),'dark_prismarine');m.box((x+1,6,z+1),(x+w-1,7,z+d-1),'water')
 for dx,dz,b in ((2,2,'brain_coral_block'),(w-2,d-2,'tube_coral_block'),(w//2,d//2,'bubble_coral_block')):m.set(x+dx,6,z+dz,b)
 for args in ((x,z,x+w,z),(x,z+d,x+w,z+d),(x,z,x,z+d),(x+w,z,x+w,z+d)):rail(m,*args)
 m.point(k,'work',(x+w//2,7,z+d//2),'有水与边护的珊瑚陈设庭',approach=(x+w//2,8,z-1))
 room(m,k,'低位珊瑚水庭',x,z,w,d,'水槽低于干燥台面，原版珊瑚置水中；实际生态与流体另验',f=5,h=4)


def stairs(m,x,z,rise,f=7,width=3):
 for i in range(rise):
  m.box((x,f+1,z+i),(x+width-1,f+1+i,z+i),'dark_prismarine')
  for xx in range(x,x+width):m.set(xx,f+1+i,z+i,'prismarine_brick_stairs[facing=south]')
  m.set(x-1,f+2+i,z+i,'birch_fence');m.set(x+width,f+2+i,z+i,'birch_fence')


def home(v,courtyard=False):
 if v==5 and not courtyard:return stacked()
 fam='TC-03' if courtyard else 'TC-02'
 names=['单贝前廊宅','双瓣分户宅','长廊三舱宅','坡岸阶贝宅','叠层错台宅','放射共居宅']
 if courtyard:names=['前庭珊瑚宅','双瓣侧庭宅','双户共享水庭','半环海庭宅','前后两庭宅','干式下沉观景宅']
 plans=[(45,35,[(5,5,18,20,7)],[(27,12,14,14,7)]),
 (53,49,[(5,5,18,18,7),(29,5,18,18,7)],[(7,29,14,14,7),(31,29,14,14,7)]),
 (67,35,[(5,5,20,22,7)],[(29,10,14,16,7),(47,10,14,16,7)]),
 (49,47,[(5,5,20,19,7)],[(29,22,14,16,11)]),
 (43,43,[(5,5,20,20,7)],[(9,9,14,14,19)]),
 (63,51,[(23,5,18,20,7)],[(5,29,16,16,7),(39,29,16,16,7)])]
 w,d,commons,sleeps=plans[v-1]
 if courtyard:
  # Distinct court plans rather than recolouring the raised-house arrangements.
  plans2=[(49,49,[(5,5,20,19,7)],[(29,25,14,16,7)]),
  (57,45,[(5,5,18,20,7)],[(35,18,16,18,7)]),
  (65,55,[(5,5,18,18,7),(39,5,18,18,7)],[(7,33,14,14,7),(41,33,14,14,7)]),
  (63,49,[(23,5,18,18,7)],[(5,24,16,16,7),(41,24,16,16,7)]),
  (65,49,[(23,5,18,20,7)],[(5,27,14,14,7),(45,27,14,14,7)]),
  (53,51,[(5,5,20,20,7)],[(31,29,14,16,3)])]
  w,d,commons,sleeps=plans2[v-1]
 m=start(fam,v,names[v-1],w,d,h=42)
 # Raised rooms need supported landing areas before their shell is added.
 for i,(x,z,ww,dd,f) in enumerate(sleeps):
  if f>7:
   m.box((x-2,8,z-2),(x+ww+2,f,z+dd+2),'dark_prismarine')
   if v==5 and not courtyard:
    # Upper bedroom sits over a solid, occupied lower shell; staircase climbs outside.
    m.box((x-2,8,z-2),(x+ww+2,18,z+dd+2),'air')
    m.box((x-2,18,z-2),(x+ww+2,19,z+dd+2),'prismarine_bricks')
    for xx,zz in ((x-2,z-2),(x+ww+2,z-2),(x-2,z+dd+2),(x+ww+2,z+dd+2)):m.box((xx,8,zz),(xx,18,zz),'dark_prismarine')
   stairs(m,x+ww//2-1,z-2-(f-7),f-7)
   m.box((x+ww//2-1,f,z-2),(x+ww//2+1,f,z-1),'prismarine_bricks')
  elif f<7:
   # A dry, enclosed recessed chamber: floor3, rim7, upper wall reaches above高潮6.
   m.box((x-1,3,z-1),(x+ww+1,7,z+dd+1),'dark_prismarine');m.box((x,4,z),(x+ww,7,z+dd),'air')
   m.box((x+ww//2-1,4,z-5),(x+ww//2+1,8,z-1),'air')
   for j in range(4):
    zz=z-5+j;yy=7-j
    m.box((x+ww//2-1,3,zz),(x+ww//2+1,yy-1,zz),'dark_prismarine')
    for xx in range(x+ww//2-1,x+ww//2+2):m.set(xx,yy,zz,'prismarine_brick_stairs[facing=north]')
   m.box((x+ww//2-1,4,z-1),(x+ww//2+1,7,z-1),'air')
 for i,p in enumerate(commons):common(m,'common'+str(i),*p)
 for i,p in enumerate(sleeps):sleeping(m,'bedroom'+str(i),*p)
 if courtyard:
  courts=[[(9,31,11,9)],[(25,6,7,9)],[(27,28,8,12)],[(25,30,10,10)],[(6,8,10,10),(45,8,10,10)],[(9,32,13,10)]][v-1]
  for i,p in enumerate(courts):coral_court(m,'court'+str(i),*p)
 # Upper homes use a dedicated stair landing on the north; ensure approach remains inside template.
 if v==5 and not courtyard:
  # The first geometry is reconfigured below by the dedicated builder.
  pass
 entry(m,w//2,3)
 m.meta['differences']=[names[v-1],f'{len(commons)}个起居舱与{len(sleeps)}个独门休眠舱，位置/尺度/楼面高程共同变化；' + ('低位水庭与干燥住宅分别围护' if courtyard else '贯通架空平台和独立舱门')]
 if any(p[-1]!=7 for p in sleeps):
  for p in sleeps:
   if p[-1]!=7:m.meta['floors'].append(dict(name='分台居住层',y=p[-1],max_y=p[-1]+6))
 return m

def stacked():
 m=start('TC-02',5,'叠层错台宅',43,37,h=42)
 common(m,'lower',5,5,20,20)
 for xx,zz in ((7,7),(23,7),(7,23),(23,23)):m.box((xx,8,zz),(xx,18,zz),'dark_prismarine')
 m.box((7,19,7),(25,19,25),'prismarine_bricks')
 sleeping(m,'upper',9,9,14,14,f=19)
 door(m,23,18,f=19,face='east')
 stairs(m,30,5,12)
 m.box((24,19,17),(32,19,20),'prismarine_bricks')
 rail(m,24,17,29,17,f=19);rail(m,24,20,32,20,f=19);rail(m,32,17,32,20,f=19)
 room(m,'upper_bridge','高层接驳廊',24,17,8,3,'外侧实心楼梯接带边护高廊',f=19,h=4)
 m.meta['floors'].append(dict(name='高层睡眠舱',y=19,max_y=25))
 m.meta['terrain']['分层']='下层地板7，上层19，外梯位于东侧；上层由四根实体柱及下层贝顶承托，需连续承载基础'
 m.meta['differences']=['上下贝舱错台，外侧独立长阶与高廊接独门睡眠舱，起居/睡眠分层']
 entry(m,27,3);return m

BUILDERS={**{f'TC-02-v{v:02}':(lambda v=v:home(v)) for v in range(1,7)},**{f'TC-03-v{v:02}':(lambda v=v:home(v,True)) for v in range(1,7)}}

def hostel(v):
 plans=[(57,51,(5,5,20,20),[(7,31,14,14),(35,29,14,16)],(31,5,18,18)),
 (81,49,(5,5,22,20),[(5,29,14,14),(23,29,14,14),(41,29,14,14),(59,29,14,14)],(43,5,24,18)),
 (63,55,(33,5,22,20),[(7,33,16,16),(37,33,16,16)],(5,5,22,22)),
 (67,57,(25,5,20,22),[(5,31,16,18),(25,35,14,16),(45,31,16,18)],(5,5,16,20))]
 w,d,c,beds,e=plans[v-1];m=start('TC-07',v,['双客舱潜旅舍','通廊四舱潜旅舍','装备院潜旅舍','放射三翼潜旅舍'][v-1],w,d)
 common(m,'dining',*c)
 for i,p in enumerate(beds):sleeping(m,'guest'+str(i),*p)
 x,z,ww,dd=e;pod(m,*e);door(m,x+ww//2,z)
 work(m,'reception',x+3,z+3,ww-6,'lectern[facing=south]','接待登记与行李交接')
 store(m,'gear',x+3,z+dd-3,ww-6,side=-1)
 work(m,'repair',x+3,z+9,ww-6,'smithing_table','潜水装具维修与清洗')
 bench(m,x+4,8,z+dd-6,ww-8,'north','birch')
 room(m,'equipment','接待装备服务舱',x+2,z+2,ww-4,dd-4,'接待、行李装备分柜、维修检查和等候坐席；潜水机制不由陈设实现')
 entry(m,w//2,3);m.meta['differences']=[f'{len(beds)}间独立双床客舱，独立餐饮贝舱与装备服务舱；'+['前后双客房与侧接待','四间长廊横排客房','装备院与左右双客舱','三翼扇形客房'][v-1]]
 return m

BUILDERS.update({f'TC-07-v{v:02}':(lambda v=v:hostel(v)) for v in range(1,5)})

# Private revisions: arched shell ribs and complete rooms inside connected buildings.
_old_pod=pod

def pod(m,x,z,w=18,d=18,f=7):
 _old_pod(m,x,z,w,d,f)
 # Replace the flat/stepped cap with a load-bearing faceted barrel shell.
 import math
 m.box((x,f+7,z),(x+w,f+13,z+d),'air')
 roof_cells=set(footprint(x,z,w,d))
 for xx,zz in roof_cells:
  t=(xx-(x+w/2))/(w/2)
  y=f+7+round(5*math.sqrt(max(0,1-t*t)))
  rib=(zz-z)%5==0 or zz==z+d
  m.box((xx,max(f+7,y-3),zz),(xx,y,zz),'smooth_quartz' if rib else 'waxed_oxidized_copper')
  if any((xx+dx,zz+dz) not in roof_cells for dx,dz in ((1,0),(-1,0),(0,1),(0,-1))):
   m.box((xx,f+7,zz),(xx,max(f+7,y-1),zz),'cyan_stained_glass' if abs(t)<.45 else 'prismarine_bricks')
  if xx in (x,x+w):m.box((xx,f+6,zz),(xx,y,zz),'dark_prismarine')
 for xx,zz in footprint(x,z,w,d):m.set(xx,f,zz,'spruce_planks')
 for zz in range(z+5,z+d-4,5):
  for xx in (x,x+w):m.set(xx,f+1,zz,'prismarine_brick_slab[type=top]')


def home_fit(m,k,x,z,w,d,f=7):
 kitchen(m,k+'cook',x+2,z+2,f);store(m,k+'food',x+w-6,z+2,4,f=f)
 meal(m,x+3,z+8,f);store(m,k+'books',x+2,z+d-2,5,'bookshelf',f=f,side=-1)
 bench(m,x+w-6,f+1,z+8,3,'south','birch');bench(m,x+w-6,f+1,z+12,3,'north','birch')
 m.set(x+w-5,f+1,z+10,'dark_oak_slab[type=top]');m.set(x+w-5,f+2,z+10,'flower_pot')
 room(m,k,'厨房餐读起居',x+1,z+1,w-2,d-2,'完整厨房、食品柜、共餐桌椅、相向读书座与书物柜',f)


def sleep_fit(m,k,x,z,w,d,f=7,beds=2):
 bed(m,k+'a',x+3,z+d-4,f)
 if beds==2:bed(m,k+'b',x+w-3,z+d-4,f)
 store(m,k+'cloth',x+2,z+2,4,f=f);work(m,k+'desk',x+w-5,z+3,3,'lectern[facing=south]','个人书写灯案',f)
 m.set(x+2,f+1,z+d-2,'water_cauldron[level=3]')
 room(m,k,'隔门干式卧室',x+1,z+1,w-2,d-2,'床位与个人灯柜、衣物储藏、写字和盥洗，实体隔门',f)


_saved_home=home

def home(v,courtyard=False):
 if not courtyard and v in (1,2,3):
  if v==1:
   m=start('TC-02',v,'单体长贝前后宅',35,43);pod(m,5,5,24,32)
   m.box((6,8,24),(28,13,24),'smooth_quartz');door(m,17,5);door(m,17,24)
   home_fit(m,'living',6,6,22,17);sleep_fit(m,'sleep',6,25,22,11)
  elif v==2:
   m=start('TC-02',v,'并联双户贝廊宅',57,43)
   for j,x in enumerate((5,31)):
    pod(m,x,5,20,32);door(m,x+10,5);m.box((x+1,8,24),(x+19,13,24),'smooth_quartz');door(m,x+10,24)
    home_fit(m,'living'+str(j),x+1,6,18,17);sleep_fit(m,'sleep'+str(j),x+1,25,18,11)
  else:
   m=start('TC-02',v,'长贝内廊三室宅',61,41);pod(m,5,5,50,30);door(m,30,5)
   m.box((6,8,22),(54,13,22),'smooth_quartz')
   home_fit(m,'living',6,6,23,15);work(m,'study',37,10,11,'cartography_table','共用海图书写');store(m,'study_books',37,18,11,'bookshelf',side=-1)
   room(m,'study','共用读图间',32,6,21,15,'海图长案与书柜，连通前部公共生活')
   for j,x in enumerate((6,22,38)):
    if j:m.box((x,8,23),(x,13,34),'smooth_quartz')
    door(m,x+7,22);sleep_fit(m,'room'+str(j),x,23,15,11,beds=1)
  entry(m,m.size[0]//2,3);m.meta['differences']=[m.meta['name'],'主体内部分隔共居与寝室，居住和起居以室内门相连；拱壳肋与连续支承落在桩台']
  return m
 return _saved_home(v,courtyard)

BUILDERS.update({f'TC-02-v{v:02}':(lambda v=v:home(v)) for v in range(1,7)})
BUILDERS.update({f'TC-03-v{v:02}':(lambda v=v:home(v,True)) for v in range(1,7)})



# Guard every elevated landing edge; leave only the real stair/bridge opening.
_home_before_guards=home
def home(v,courtyard=False):
 m=_home_before_guards(v,courtyard)
 if v==6 and courtyard:
  rail(m,36,24,36,28);rail(m,40,24,40,28)
 if v==4 and not courtyard:
  rail(m,27,20,34,20,f=11);rail(m,38,20,45,20,f=11)
  rail(m,27,20,27,40,f=11);rail(m,45,20,45,40,f=11);rail(m,27,40,45,40,f=11)
 if v==5 and not courtyard:
  rail(m,7,7,25,7,f=19);rail(m,7,7,7,25,f=19);rail(m,7,25,25,25,f=19)
  rail(m,25,7,25,16,f=19);rail(m,25,21,25,25,f=19)
 return m
BUILDERS.update({f'TC-02-v{v:02}':(lambda v=v:home(v)) for v in range(1,7)})
BUILDERS.update({f'TC-03-v{v:02}':(lambda v=v:home(v,True)) for v in range(1,7)})

_hostel_pods=hostel
def hostel(v):
 if v not in (1,2):return _hostel_pods(v)
 w=51 if v==1 else 83;d=59 if v==1 else 53
 m=start('TC-07',v,'连体双客房潜旅舍' if v==1 else '通廊四客房潜旅舍',w,d)
 pod(m,5,5,w-11,d-11);door(m,w//2,5)
 if v==1:
  m.box((28,8,6),(28,13,24),'smooth_quartz');door(m,28,15,face='east')
  home_fit(m,'dining',6,6,21,18)
  ex,ez,ew,ed=29,6,15,18;split=29
  for j,x in enumerate((6,26)):
   if j:m.box((25,8,30),(25,13,52),'smooth_quartz')
   sleep_fit(m,'guest'+str(j),x,30,18,22)
   door(m,x+9,split)
  m.box((6,8,split),(44,13,split),'smooth_quartz')
  for x in (15,35):door(m,x,split)
 else:
  home_fit(m,'dining',6,6,23,18);ex,ez,ew,ed=34,6,22,18;split=29
  for j,x in enumerate((6,24,42,60)):
   if j:m.box((x-1,8,30),(x-1,13,46),'smooth_quartz')
   sleep_fit(m,'guest'+str(j),x,30,16,16)
  m.box((6,8,split),(76,13,split),'smooth_quartz')
  for x in (14,32,50,68):door(m,x,split)
  bench(m,62,8,12,10,'south','birch');bench(m,62,8,18,10,'north','birch');store(m,'waiting_books',62,22,10,'bookshelf',side=-1)
  room(m,'waiting','同行交流等候间',60,6,16,18,'行前共读和访客等候，与装备区留通道')
 work(m,'reception',ex+2,ez+2,ew-4,'lectern[facing=south]','潜行登记与补给账册')
 work(m,'repair',ex+2,ez+8,ew-4,'smithing_table','装备检修与清洗');store(m,'gear',ex+2,ez+ed-2,ew-4,side=-1)
 room(m,'gearroom','接待装具整理厅',ex,ez,ew,ed,'登记接待、行李柜和装具维修，与住宿隔门分区')
 room(m,'corridor','室内客房公共走廊',6,25,w-13,3,'宽干道串联独立寝室，避开公共餐厨和维修工位')
 entry(m,w//2,3);m.meta['differences']=[m.meta['name'],'连体肋拱贝厅以真实公共走廊串联'+('两' if v==1 else '四')+'间双床客房，前部接待、装备与共餐起居分区']
 return m
BUILDERS.update({f'TC-07-v{v:02}':(lambda v=v:hostel(v)) for v in range(1,5)})
