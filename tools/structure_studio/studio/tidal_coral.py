"""Six tidal coral civic structures; water levels are static siting references."""
from .model import Model
from .components import shell, hip_roof, shelf, bench, pendant
from .samples import railing
from functools import partial
from .tidal_civic_details import refine


def base(code,name,w,d,h=34,role='key',f=7,water=5,shore=22):
 m=Model(code+'-v01',name,(w,h,d),family=code,civilization='海洋幻想',role=role,terrain={
 '选址':'避风浅海、潮间带与稳定岸台；模板柱脚Y=0须接连续承载海床，不能悬空生成',
 '潮位':f'本模型参考静水面Y={water}，低潮Y={water-2}，预期高潮Y={water+1}；不是潮汐模拟，实际浪高及风暴增水另核对',
 '干湿区':f'常用干地板Y={f}、步行脚底Y={f+1}；指定水槽与历史淹没区例外，入口以标记为准',
 '接驳':'北侧为陆路或干式栈桥入口；水侧作业接口不替代普通步行路线，船舶吃水与上下船机制另接',
 '供给':'淡水由岸上补给或收集后处理；水盆与食品柜表达储备，不假定海水可饮用；驻留与生产均需岸上补给',
 '边界':'原版静态珊瑚色建材与海洋设备陈设；不证明潮门、潜水、呼吸、流体或机器已运行'})
 m.meta.update(source='tools/structure_studio/studio/tidal_coral.py',roof_min_y=f+7,floors=[dict(name='干式主层',y=f,max_y=f+6)],preview_context=dict(kind='shore',shore_z=shore,water_surface_y=water,land_surface_y=f+1,bed_y=-1,padding=4,surface='sand'))
 return m


def deck(m,x0,z0,x1,z1,f=7):
 m.box((x0,f-1,z0),(x1,f,z1),'prismarine_bricks');m.box((x0,f+1,z0),(x1,m.size[1]-1,z1),'air')
 for x in range(x0,x1+1,5):
  for z in range(z0,z1+1,5):m.box((x,0,z),(x,f-2,z),'dark_prismarine')
 for x,z in ((x1,z0),(x1,z1),(x0,z1)):m.box((x,0,z),(x,f-2,z),'dark_prismarine')


def hall(m,x,z,w,d,f=7,h=6,glass=False):
 shell(m,(x,f,z),(x+w,f+h,z+d),'smooth_quartz',floor='birch_planks',ceiling='prismarine_bricks')
 for xx in (x,x+w):
  for zz in (z,z+d):m.box((xx,f+1,zz),(xx,f+h+1,zz),'dark_prismarine')
 for xx in range(x+2,x+w-1,4):
  for zz in (z,z+d):m.box((xx,f+2,zz),(xx+1,f+4,zz),'cyan_stained_glass')
 for zz in range(z+2,z+d-1,4):
  for xx in (x,x+w):m.box((xx,f+2,zz),(xx,f+4,zz+1),'cyan_stained_glass')
 if glass:
  m.box((x,f+h+1,z),(x+w,f+h+1,z+d),'cyan_stained_glass')
  for xx in range(x,x+w+1,4):m.box((xx,f+h+1,z),(xx,f+h+1,z+d),'dark_prismarine')
 else:hip_roof(m,x-1,x+w+1,z-1,z+d+1,f+h+2,material='waxed_oxidized_cut_copper',tiers=3)
 pendant(m,x+w//2,f+h-1,z+d//2,f+h+1)


def door(m,x,z,f=7,face='north'):m.door(x,f+1,z,'birch',face)
def room(m,key,name,x,z,w,d,purpose,f=7,h=6):m.room(key,name,(x,f+1,z),(x+w,f+h,z+d),purpose)
def entry(m,x,z,f=7):
 m.point('entry','entrance',(x,f+1,z),'陆侧普通步行入口',facing='north');m.meta['connections'].append(dict(kind='pedestrian',pos=[x,f,z],direction='north',clearance=[3,4],note='接同高程干燥道路或栈桥'))
def work(m,k,x,z,w=5,b='cartography_table',name='记录操作台',f=7,side=1):
 m.box((x,f+1,z),(x+w-1,f+1,z),'birch_planks');m.set(x+1,f+1,z,b);m.set(x+w-1,f+2,z,'lantern[hanging=false]');m.point(k,'work',(x+1,f+1,z),name,approach=(x+1,f+1,z+side))
def store(m,k,x,z,w=5,b='barrel',f=7,side=1):
 shelf(m,x,f+1,z,w,'birch',b);m.point(k,'storage',(x+1,f+2,z),'分类储藏与取放',approach=(x+1,f+1,z+side))
def meal(m,x,z,f=7):
 m.box((x,f+1,z),(x+4,f+1,z),'birch_slab[type=top]')
 for xx in (x,x+2,x+4):
  m.set(xx,f+1,z-2,'birch_stairs[facing=south]');m.set(xx,f+1,z+2,'birch_stairs[facing=north]')
 m.set(x+2,f+2,z,'lantern[hanging=false]')
def kitchen(m,k,x,z,f=7):
 for i,b in enumerate(('smoker[facing=south]','crafting_table','water_cauldron[level=3]','birch_planks','barrel')):m.set(x+i,f+1,z,b)
 m.point(k,'work',(x+1,f+1,z),'储备淡水与连续备餐',approach=(x+1,f+1,z+1))
def bed(m,k,x,z,f=7):
 m.bed(x,f+1,z,'cyan','north');m.set(x-1,f+1,z-1,'barrel');m.set(x-1,f+2,z-1,'lantern[hanging=false]');m.point(k,'sleep',(x,f+1,z),'值守床位与个人柜',approach=(x+1,f+1,z))
def rail(m,x0,z0,x1,z1,f=7):
 if x0==x1:railing(m,(x0,f+1,z0),(x1,f+1,z1),'birch','z')
 else:railing(m,(x0,f+1,z0),(x1,f+1,z1),'birch','x')
def finish(m,note):m.meta['differences']=[note];m.meta['design_notes']=[note,'内饰与标记随功能制作，普通步行导航不验证游泳或潜水。'];return m


def port():
 m=base('TC-01','双齿泊湾 · 潮门港',55,51)
 deck(m,2,2,52,24);deck(m,2,25,20,47);deck(m,33,25,52,47)
 hall(m,5,5,15,16);door(m,12,5);door(m,20,17,face='east')
 work(m,'ticket',7,8,8,name='候船登记');store(m,'luggage',7,19,9,side=-1)
 for z in (12,16):bench(m,8,8,z,7,'north','birch')
 room(m,'waiting','候船行李厅',6,6,13,14,'登记、两排候船坐席、行李寄存与侧门登船路线')
 hall(m,32,5,17,16);door(m,40,5);work(m,'port_control',34,8,10,name='泊位、水位与停航台账');store(m,'port_archive',34,19,8,'bookshelf',side=-1)
 meal(m,34,14);kitchen(m,'staff_water',43,16)
 room(m,'office','港务与补给厅',33,6,15,14,'港务图表、档案、工作人员共餐和岸上淡水储备')
 for x in (5,36):
  for z in (29,37):store(m,f'cargo{x}_{z}',x,z,9)
 work(m,'loading',36,43,10,'crafting_table','核货与封装');bench(m,6,8,44,7,'north','birch')
 for x in (2,20,33,52):rail(m,x,25,x,47)
 rail(m,2,47,20,47);rail(m,33,47,52,47);rail(m,21,24,32,24)
 for x in (20,33):
  m.box((x,0,32),(x,14,32),'dark_prismarine')
  for y,b in ((3,'white_concrete'),(5,'yellow_concrete'),(6,'red_concrete')):m.set(x, y,31,b)
 m.box((20,14,32),(33,14,32),'waxed_oxidized_copper');m.point('tide','work',(20,6,31),'低潮、参考、高潮色标与静态潮门梁',approach=(18,8,31))
 m.meta['connections'].append(dict(kind='water',pos=[26,5,47],direction='south',note='双栈桥间净水湾，船舶吃水/登船需另行接入'))
 room(m,'cargo','两侧装卸栈桥',3,25,48,21,'货堆分区、贯通步道、安全栏杆与中央泊湾')
 entry(m,26,3);return finish(m,'两幢岸端小厅与两条长货栈桥围出中央泊湾，候船和货运分侧，水位标尺从干燥栈桥读取。')


def underwater():
 m=base('TC-08','玻璃双舱 · 水下考察站',53,46,f=3,water=14,shore=5,h=28)
 m.meta['terrain']['干湿区']='海床舱地板Y=3，舱顶Y=11；参考水面Y=14，低潮12/高潮15。密闭外壳内为干式空间，北端Y=17平台高于高潮，经封闭楼梯降至舱内；水密和呼吸玩法另验证。'
 m.meta['preview_context']['land_surface_y']=18
 m.meta['roof_min_y']=10;m.meta['floors']=[dict(name='干燥海床舱',y=3,max_y=9),dict(name='地面入站口',y=16,max_y=21)]
 deck(m,2,18,50,43,f=3)
 hall(m,4,20,17,20,f=3,glass=True);hall(m,31,20,17,20,f=3,glass=True)
 hall(m,21,28,10,12,f=3,glass=True);door(m,21,32,f=3,face='east');door(m,31,32,f=3,face='west')
 # A solid watertight stair casing, glazed sidewalls, dry descending steps.
 shell(m,(23,3,2),(29,21,28),'dark_prismarine',floor='prismarine_bricks',ceiling='prismarine_bricks')
 m.box((24,4,3),(28,20,27),'air')
 for x in (23,29):m.box((x,6,5),(x,19,26),'cyan_stained_glass')
 m.box((24,3,3),(28,16,6),'prismarine_bricks')
 for i in range(13):
  z=7+i;y=16-i
  m.box((24,3,z),(28,max(3,y-1),z),'prismarine_bricks')
  for x in range(24,29):m.set(x,y,z,'prismarine_brick_stairs[facing=north]')
 # Last step is Y=3; descend onto the same lower deck.
 m.box((24,4,28),(28,6,28),'air');door(m,26,2,f=16)
 work(m,'intake',24,25,4,'water_cauldron[level=3]','潜水装备交接、外壳不替代气闸',f=3)
 work(m,'samples',6,23,10,'brewing_stand','海水样本分拣',f=3);store(m,'sample_store',6,38,11,'barrel',f=3,side=-1)
 for z in (28,33):work(m,'lab'+str(z),6,z,10,'cartography_table','分析与记录',f=3)
 room(m,'lab','干式样本研究舱',5,21,15,18,'分样清洗、分析双台与分类样本柜',f=3)
 kitchen(m,'galley',33,23,f=3);meal(m,34,29,f=3)
 store(m,'food',41,23,5,f=3);store(m,'reading_books',42,31,4,'bookshelf',f=3,side=-1)
 bench(m,42,4,27,3,'south','birch');m.set(43,4,29,'birch_slab[type=top]')
 store(m,'diving_gear',23,38,6,f=3,side=-1);work(m,'equipment_log',23,34,5,'smithing_table','装备检查与交接记录',f=3)
 room(m,'equipment','中央装备交接舱',22,29,8,10,'潜水装具柜、检查长案与两侧舱门，保留绕台通道',f=3)
 m.box((32,4,33),(47,8,33),'smooth_quartz');door(m,40,33,f=3)
 bed(m,'bunk1',35,37,f=3);bed(m,'bunk2',43,37,f=3);store(m,'linen',39,39,5,f=3,side=-1)
 room(m,'stay','驻留生活舱',32,21,15,18,'储备淡水、成套备餐餐席、隔门双床与个人柜',f=3)
 room(m,'entry_shaft','封闭干式下行通道',24,3,4,24,'从高潮以上平台下降至水下舱的实体楼梯；玻璃外壳水密待运行时核对',f=3,h=17)
 entry(m,26,4,f=16);return finish(m,'海床双低矮玻璃舱分隔研究和生活，独立高出高潮的入站井以实体楼梯接驳；水下功能点都在干燥舱内。')


def school():
 m=base('TC-09','折翼观海院 · 海流学馆',55,49)
 deck(m,2,2,52,45)
 hall(m,5,6,21,14);hall(m,5,25,13,16);hall(m,32,6,16,25,h=8)
 door(m,15,6);door(m,15,20,face='south');door(m,11,25);door(m,32,19,face='west')
 work(m,'teach',8,8,13,'lectern[facing=south]','海流课程讲台')
 for z in (13,17):
  for x in (9,16,22):m.set(x,8,z,'birch_stairs[facing=north]');m.set(x,8,z-1,'birch_slab[type=top]')
 store(m,'maps',7,19,5,'bookshelf',side=-1);room(m,'class','海流课堂',6,7,19,12,'讲台、分列课桌座椅与课本柜')
 work(m,'sample_sort',7,28,8,'brewing_stand','海域样本对照');store(m,'samples',7,38,8,'barrel',side=-1)
 work(m,'specimen',7,33,8,'water_cauldron[level=3]','清洗与珊瑚骨架标本台')
 for x in (11,14):m.set(x,9,33,'dead_brain_coral_block')
 room(m,'samples','独立样本翼',6,26,11,14,'洗样台、分区储藏与记录，远离公共课桌')
 store(m,'atlas',34,8,10,'bookshelf');work(m,'plot',34,15,10,'cartography_table','航线与海流图');meal(m,35,23)
 store(m,'voyage_logs',34,29,10,'bookshelf',side=-1)
 for x in (43,45):m.set(x,8,22,'birch_stairs[facing=south]');m.set(x,8,24,'birch_slab[type=top]')
 room(m,'reading','高窗图志厅',33,7,14,23,'成排海图、地图长案与共读坐席',h=8)
 # Wide rear lookout deck with paired instruments and benches.
 for x in (24,38):
  m.set(x,8,38,'chiseled_quartz_block');m.set(x,9,38,'lightning_rod[facing=up]');m.point('observe'+str(x),'work',(x,9,38),'海面方位观察标',approach=(x,8,36))
 bench(m,22,8,32,7,'south','birch');bench(m,36,8,33,7,'south','birch')
 rail(m,2,45,52,45);rail(m,2,22,2,45);rail(m,52,22,52,45)
 work(m,'field_notes',23,41,11,'cartography_table','露天观测记录')
 room(m,'lookout','海侧露天观测台',20,32,29,12,'两组方位柱、海图案、坐席与连续临水栏杆')
 entry(m,26,3);return finish(m,'前教学横厅、后样本短翼和高窗图志长翼错位围出露天观测台；课堂、藏图、样本与航行记录有独立组织。')


def council():
 m=base('TC-10','珊环公庭 · 潮汐议事庭',55,49)
 deck(m,2,2,52,45)
 # Stepped octagonal central open court; columns carry separate arc beams.
 for z in range(9,35):
  inset=max(0,5-min(z-9,34-z));m.box((14+inset,7,z),(40-inset,7,z),'smooth_quartz')
 for x,z in ((19,10),(35,10),(14,16),(40,16),(14,28),(40,28),(19,34),(35,34)):
  m.box((x,8,z),(x,14,z),'prismarine_bricks');m.set(x,15,z,'sea_lantern')
 for x0,z0,x1,z1 in ((19,10,35,10),(14,16,14,28),(40,16,40,28),(19,34,35,34)):
  m.box((x0,14,z0),(x1,14,z1),'waxed_oxidized_copper')
 work(m,'chair',22,29,10,'lectern[facing=north]','面向代表席的议事记录席',side=-1)
 for z in (15,20,25):
  bench(m,18,8,z,4,'south','birch');bench(m,32,8,z,4,'south','birch')
 m.box((25,7,17),(29,7,24),'dark_prismarine');m.box((26,7,18),(28,7,23),'water')
 rail(m,25,17,29,17);rail(m,25,24,29,24);rail(m,25,18,25,23);rail(m,29,18,29,23)
 m.point('ritual','work',(27,7,21),'有边护的浅水仪式盆',approach=(24,8,21))
 hall(m,4,7,8,27);door(m,12,19,face='east');store(m,'records',6,9,4,'bookshelf');store(m,'old_records',6,30,4,'bookshelf',side=-1)
 work(m,'scribe',6,17,4,'lectern[facing=south]','议案登记');bench(m,6,8,24,3,'north','birch')
 hall(m,42,7,8,27);door(m,42,19,face='west');kitchen(m,'public_water',44,10);meal(m,44,20);store(m,'public_goods',44,30,4,side=-1)
 room(m,'court','露天八角议事庭',15,10,24,23,'两侧多排代表席、书记席和护栏浅水仪式盆')
 room(m,'archives','狭长议案档案翼',5,8,6,25,'新旧档案分柜、书写和等候')
 room(m,'rest','公共饮水休息翼',43,8,6,25,'岸运淡水、备餐、公用餐席和用品柜')
 rail(m,2,45,52,45);rail(m,2,22,2,45);rail(m,52,22,52,45);bench(m,10,8,40,8,'south','birch');bench(m,35,8,40,8,'south','birch')
 entry(m,27,3);return finish(m,'露天八角柱庭包围浅水仪式盆，代表席与书记席互相面向；两条窄翼承担档案和公共休息，保持开放陆海轴线。')


def drowned():
 m=base('TC-11','回水残藏 · 淹没档案馆',47,47,role='structure',water=5,shore=16)
 deck(m,2,2,44,15)
 hall(m,5,5,14,9);door(m,12,5);work(m,'salvage',7,8,8,'cartography_table','抢救藏品登记');store(m,'dry_records',7,12,8,'bookshelf',side=-1)
 room(m,'office','增建干燥登记室',6,6,12,7,'灾后办公室、抢救图志与清点长案')
 # Old lower archive floor is actually flooded; newer high walkways bridge it.
 m.box((4,0,18),(42,3,43),'stone_bricks');m.box((4,4,18),(42,10,43),'air');m.box((5,4,19),(41,4,42),'water')
 m.box((4,4,18),(42,6,18),'prismarine_bricks')
 for x in (4,42):m.box((x,4,18),(x,12,43),'prismarine_bricks')
 m.box((4,4,43),(42,10,43),'prismarine_bricks')
 for x in range(6,41,7):
  m.box((x,4,39),(x+3,7,40),'bookshelf');m.box((x,4,22),(x+2,5,23),'bookshelf')
 for x in (8,36):
  m.box((x,0,17),(x+3,6,43),'dark_prismarine');m.box((x,7,16),(x+3,7,43),'birch_planks')
  rail(m,x,17,x,42);rail(m,x+3,17,x+3,42)
 m.box((8,7,31),(39,7,34),'birch_planks');rail(m,12,31,35,31);rail(m,12,34,35,34)
 # Remove rail crossings for dry walk connections.
 for x in (11,36):m.box((x,8,32),(x,10,33),'air')
 for x in (8,39):rail(m,x,43,x+0,43)
 work(m,'recovery',19,32,7,'crafting_table','中桥干燥转运与样本编号')
 m.point('old_shelf','work',(20,5,39),'从新桥观察低处泡水旧书架',approach=(20,8,33))
 room(m,'old_archive','淹没旧藏区',5,19,36,23,'旧低地板与浸水书柜；只从抬高木桥观察，不把涉水当普通通路',f=3,h=8)
 room(m,'recovery_bridge','灾后高架抢救桥',8,17,31,25,'双纵桥与横向转运桥，连续侧栏防落入旧层')
 for xa,xb in ((2,7),(12,35),(40,44)):rail(m,xa,15,xb,15)
 entry(m,26,3);return finish(m,'前部增建干燥办公室接双纵高桥，旧档案层实际保留浅水与低书柜；断墙与缺屋盖揭示淹没历史，抢救路线不依赖游泳。')


def tower():
 m=base('TC-12','青阶旧标 · 旧潮标塔',37,45,h=38,role='structure',shore=29)
 deck(m,2,2,34,41)
 hall(m,5,5,12,13);door(m,11,5);kitchen(m,'keeper_cook',7,8);meal(m,7,13)
 store(m,'keeper_books',14,15,2,'bookshelf',side=-1);m.set(14,8,11,'birch_stairs[facing=south]');m.set(14,8,13,'birch_slab[type=top]')
 room(m,'keeper','前部值守生活屋',6,6,10,11,'淡水储备、连续备餐与六人餐席')
 hall(m,5,23,12,14);door(m,11,23);bed(m,'keeper_bed',9,32);work(m,'log',12,26,3,'lectern[facing=south]','潮况值班日志');store(m,'clothes',11,35,4,side=-1)
 room(m,'sleep','独立值守寝室',6,24,10,12,'独门床柜、日志和衣物')
 # Thin tower on eastern side; long stair climbs behind the lower workshop.
 shell(m,(20,7,7),(31,25,36),'prismarine_bricks',floor='birch_planks')
 for x in (20,31):
  for z in (11,18,26,32):m.box((x,10,z),(x,13,z+1),'cyan_stained_glass')
 door(m,20,12,face='west');work(m,'repair',22,10,6,'smithing_table','旧潮标零件维护')
 for i in range(11):
  z=15+i;y=8+i
  m.box((26,8,z),(29,y,z),'dark_prismarine')
  for x in range(26,30):m.set(x,y,z,'prismarine_brick_stairs[facing=south]')
  m.set(25,y+1,z,'birch_fence');m.set(30,y+1,z,'birch_fence')
 m.box((21,19,26),(30,19,35),'prismarine_bricks')
 # The landing shares the last step's upper face at Y=19.
 m.box((26,19,26),(29,19,26),'air');m.box((26,18,26),(29,18,26),'prismarine_bricks')
 for x in range(26,30):m.set(x,19,27,'prismarine_brick_stairs[facing=south]')
 for x in (20,31):m.box((x,21,28),(x,24,34),'cyan_stained_glass')
 rail(m,21,26,25,26,f=19);bench(m,22,20,29,3,'south','birch')
 store(m,'spares',22,34,3,side=-1);work(m,'assembly',22,29,3,'crafting_table','潮标维护小件装配')
 work(m,'observation',22,32,7,'cartography_table','高处海面方位与旧航路记录',f=19)
 # Stair enclosure rises with the actual staircase; only the rear lookout is tall.
 for z in range(7,26):
  top=14 if z<15 else 14+z-15
  m.box((20,top+1,z),(31,29,z),'air');m.box((20,top,z),(31,top,z),'waxed_oxidized_copper')
 m.box((20,26,26),(31,26,36),'dark_prismarine');hip_roof(m,19,32,25,37,27,material='waxed_oxidized_cut_copper',tiers=3)
 m.box((25,30,30),(26,32,31),'sea_lantern');m.box((24,33,29),(27,33,32),'waxed_oxidized_copper')
 # Historic shore marker is read from the dry apron, no descent into tide zone.
 m.box((32,0,39),(32,16,39),'dark_prismarine')
 for y,b in ((3,'white_concrete'),(5,'yellow_concrete'),(6,'red_concrete')):m.set(32,y,38,b)
 m.point('historic_gauge','work',(32,5,38),'旧低高潮标记柱',approach=(30,8,38))
 rail(m,2,41,34,41);rail(m,34,29,34,41);rail(m,2,29,2,41)
 room(m,'tower','旧塔楼梯与维护',21,8,9,27,'下层零件维修、侧向连续实心梯级与两侧护栏',h=17)
 room(m,'lookout','塔上有窗观测间',21,28,9,7,'完整登陆平台、海图长案和朝海玻璃窗',f=19,h=5)
 m.meta['floors'].append(dict(name='塔上观测',y=19,max_y=25));m.meta['roof_min_y']=26
 entry(m,19,3);return finish(m,'双间生活小屋与狭长高塔分离，侧向实体阶梯抵达封闭观测层，旧色标留在海侧；值守、寝室、维护和航路观测分层组织。')

BUILDERS={'TC-01-v01':port,'TC-08-v01':underwater,'TC-09-v01':school,'TC-10-v01':council,'TC-11-v01':drowned,'TC-12-v01':tower}
BUILDERS={key:partial(refine,builder) for key,builder in BUILDERS.items()}
