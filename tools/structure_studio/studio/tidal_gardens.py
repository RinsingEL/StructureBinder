"""Tidal cultivation, freshwater shore beds and working shades."""
from .tidal_life import start,pod,home_fit,sleep_fit
from .tidal_coral import door,room,entry,work,store,rail,meal,kitchen,bed
from .components import bench


def tank(m,k,x,z,w,d):
 m.box((x,3,z),(x+w,7,z+d),'dark_prismarine');m.box((x+1,4,z+1),(x+w-1,7,z+d-1),'water')
 for xx in range(x+2,x+w-1,3):
  for zz in range(z+2,z+d-1,3):
   m.set(xx,3,zz,'sand');m.set(xx,4,zz,'kelp_plant');m.set(xx,5,zz,'kelp[age=25]')
 for args in ((x,z,x+w,z),(x,z+d,x+w,z+d),(x,z,x,z+d),(x+w,z,x+w,z+d)):rail(m,*args)
 m.point(k,'work',(x+1,6,z+2),'受护栏围护的海带育苗水槽',approach=(x-1,8,z+2))
 room(m,k,'海带育苗槽',x,z,w,d,'沙底水槽种植原版海带；干燥围护通道人工维护，不模拟流体与自动收获',f=3,h=5)


def nursery(v):
 plans=[(61,51,[(32,7,10,35),(47,7,8,35)]),(69,53,[(32,7,12,15),(49,7,12,15),(32,28,12,15),(49,28,12,15)]),(63,61,[(7,36,45,8),(7,49,45,7)]),(65,57,[(33,7,9,40),(48,7,9,40)])]
 w,d,tanks=plans[v-1];m=start('TC-04',v,['长槽管理贝屋','四池分批育场','横池前棚育场','双线采收管理院'][v-1],w,d)
 if v==3:
  pod(m,5,5,48,25);door(m,29,5);m.box((30,8,6),(30,13,29),'smooth_quartz');door(m,30,16,face='east')
  home_fit(m,'stay',6,6,23,22);sleep_fit(m,'bed',31,6,21,22,beds=1)
 else:
  pod(m,5,5,22,38);door(m,16,5);m.box((6,8,29),(26,13,29),'smooth_quartz');door(m,16,29)
  home_fit(m,'stay',6,6,20,22);sleep_fit(m,'bed',6,30,20,12,beds=1)
 for j,t in enumerate(tanks):tank(m,'nursery'+str(j),*t)
 sort_z=33 if v==3 else d-6
 work(m,'sort',8,sort_z,14,'crafting_table','收获分拣与周转装筐',side=-1);store(m,'tools',24,sort_z,5,side=-1)
 m.meta['source']='tools/structure_studio/studio/tidal_gardens.py';m.meta['terrain']['水产条件']='育苗池水体为静态原版海带示例；池底有沙与连续水柱，人工分批维护。投放时需核对实际水温、水交换、污染和运行时邻居更新。'
 entry(m,w//2,3);m.meta['differences']=[m.meta['name'],['两条纵长槽共用岸侧生活房','四个短池按批次分开，十字维护通路','管理长屋在前，两道横槽位于后场','双长槽之间宽作业廊，岸侧居住与收获储存'][v-1]]
 return m


def plot(m,k,x,z,w,d,crop):
 m.box((x,7,z),(x+w,7,z+d),'dark_prismarine')
 for xx in range(x+1,x+w):
  for zz in range(z+1,z+d):
   if (xx-x)%5==0:m.set(xx,7,zz,'water')
   else:m.set(xx,7,zz,'farmland[moisture=7]');m.set(xx,8,zz,crop)
 m.point(k,'work',(x+1,8,z+1),'淡水灌溉作物维护',approach=(x-1,8,z+1));room(m,k,'高于高潮的淡水菜床',x,z,w,d,'连续土层、四格以内淡水和开放光照；种植与走道分离',h=3)


def shade(m,x,z,w,d):
 m.meta["roof_min_y"]=13;m.meta["floors"][0]["max_y"]=12
 for xx in (x,x+w):
  for zz in (z,z+d):m.box((xx,8,zz),(xx,13,zz),'dark_prismarine')
 m.box((x,13,z),(x+w,13,z+d),'prismarine_bricks')
 for xx in range(x,x+w+1):
  y=14+min(xx-x,x+w-xx)//3
  m.box((xx,y,z),(xx,y,z+d),'smooth_quartz' if (xx-x)%4==0 else 'waxed_oxidized_cut_copper')


def farm(v):
 w,d=[(41,37),(35,59),(49,43),(53,39)][v-1];m=start('TC-F02',v,['四畦潮上菜园','沿岸窄长淡水田','架高中央维护菜床','院边折角种植槽'][v-1],w,d)
 layouts=[[(7,8,10,9),(23,8,10,9),(7,23,10,8),(23,23,10,8)],[(7,8,9,34),(22,8,7,34)],[(7,8,12,12),(29,8,12,12),(7,26,12,10),(29,26,12,10)],[(7,8,34,8),(7,22,12,10),(25,22,16,10)]][v-1]
 for j,t in enumerate(layouts):plot(m,'bed'+str(j),*t,['wheat[age=7]','carrots[age=7]','potatoes[age=7]','beetroots[age=3]'][j%4])
 work(m,'water',w-7,d-7,3,'water_cauldron[level=3]','淡水储备与取水',side=-1)
 if v==2:
  shade(m,6,47,23,7);work(m,'tools',8,51,8,'crafting_table','田间工具整理');store(m,'seed',20,51,5)
 else:store(m,'seed',w//2-2,4,4)
 entry(m,w//2,3);m.meta['source']='tools/structure_studio/studio/tidal_gardens.py';m.meta['terrain']['农业前提']='仅部署于高潮和盐雾浸泡线以上且有持续淡水补给的稳定岸台；人工架床有实体底板与土层，不直接移入海水。开放天光，运行时光照另验。'
 m.meta['differences']=[m.meta['name'],'种植床间有独立干道，淡水槽与岸外海水不连通；原版作物支撑及四格灌溉经数据检查']
 return m


def shed(v):
 w,d=[(35,29),(43,35),(49,27),(39,45)][v-1];m=start('TC-F03',v,['岸上共坐贝棚','架空分货遮棚','贴墙工具长棚','贯通装货双棚'][v-1],w,d)
 if v==1:
  shade(m,6,6,22,17);meal(m,10,12);bench(m,9,8,20,14,'north','birch');store(m,'personal',23,9,4);room(m,'shelter','面海休憩棚',6,6,22,17,'共餐桌椅与长座、寄存柜，开敞通风')
 elif v==2:
  shade(m,6,6,30,23);store(m,'inbound',8,10,10);store(m,'outbound',24,10,10);work(m,'packing',9,24,22,'crafting_table','分货包装与查验',side=-1);room(m,'shelter','两侧分货棚',6,6,30,23,'来货成品两组，中间搬运通道和后部长工作案')
 elif v==3:
  shade(m,5,6,37,14);m.box((5,8,6),(42,12,6),'prismarine_bricks');store(m,'tools',8,9,12);work(m,'repair',26,9,13,'smithing_table','靠墙工具检修');bench(m,8,8,17,29,'north','birch');room(m,'shelter','背墙维护棚',5,6,37,14,'背墙工具柜和维修，前方坐席及侧通路')
 else:
  shade(m,5,5,11,33);shade(m,23,5,10,33)
  for j,x in enumerate((7,25)):
   store(m,'cargo'+str(j),x,9,7);work(m,'check'+str(j),x,29,7,'crafting_table','货物封装和交接');room(m,'shelter'+str(j),'侧列货棚',x-2,5,10,33,'货物靠两侧，中央贯通搬运线不受屋柱阻挡')
 entry(m,w//2,3);m.meta['source']='tools/structure_studio/studio/tidal_gardens.py';m.meta['differences']=[m.meta['name'],'开放遮棚提供明确休憩、维修或货运用途，连续柱梁承载贝肋屋盖，临水边护独立连续']
 return m

BUILDERS={**{f'TC-04-v{v:02}':(lambda v=v:nursery(v)) for v in range(1,5)},**{f'TC-F02-v{v:02}':(lambda v=v:farm(v)) for v in range(1,5)},**{f'TC-F03-v{v:02}':(lambda v=v:shed(v)) for v in range(1,5)}}
