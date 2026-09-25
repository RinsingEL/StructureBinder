"""Ocean fantasy workshops, dry warehouses and everyday trade."""
from .tidal_life import start,pod,home_fit,sleep_fit,stairs
from .tidal_coral import door,room,entry,work,store,rail,meal,kitchen,bed
from .components import bench

TRADES=[('海盐食铺','smoker[facing=south]','barrel'),('潜具修补铺','smithing_table','barrel'),('潮图书铺','cartography_table','bookshelf'),('渔网织坊','loom[facing=south]','barrel'),('贝器杂货铺','crafting_table','barrel'),('船灯零件铺','grindstone[face=floor,facing=south]','barrel'),('沿岸茶食铺','smoker[facing=south]','barrel'),('海路行李铺','crafting_table','chest[facing=south]')]

def fitting(m,k,x,z,w,d,kind=0):
 title,b,stock=TRADES[kind%8]
 if kind>=8:title,b,stock='珍珠鉴定加工',['grindstone[face=floor,facing=south]','cartography_table','stonecutter[facing=south]','crafting_table'][(kind-9)%4],'barrel'
 work(m,k+'trade',x+2,z+3,min(w-5,10),'lectern[facing=south]',title+'收银交接')
 store(m,k+'raw',x+2,z+d-3,w-4,stock,side=-1)
 work(m,k+'bench',x+2,z+10,max(5,w-5),b,title+'行业操作')
 # Perpendicular stands break up continuous counters and leave a through route.
 for zz in range(z+5,z+d-4,4):
  m.set(x+w-2,8,zz,'dark_prismarine');m.set(x+w-2,9,zz,['sea_lantern','flower_pot','amethyst_cluster'][kind%3])
 room(m,k,title+'交易与加工',x+1,z+1,w-2,d-2,'交易柜台、分类原料和成品，行业工具与侧墙陈列；中部留贯通搬运通道')

_fitting_basic=fitting
def fitting(m,k,x,z,w,d,kind=0):
 _fitting_basic(m,k,x,z,w,d,kind)
 # Distinct freestanding industry stations, with access from the cross aisle.
 xx=x+w//2;zz=z+6
 if kind>=8:
  m.box((xx,8,zz),(xx+4,8,zz+1),'dark_prismarine')
  for i in (0,2,4):m.set(xx+i,9,zz,'amethyst_cluster')
  m.set(xx+4,9,zz+1,'water_cauldron[level=3]')
  title='贝样清洗与分级展示'
 elif kind in (0,6):
  for i,b in enumerate(('smoker[facing=north]','barrel','water_cauldron[level=3]','smoker[facing=north]')):m.set(xx+i,8,zz,b)
  meal(m,xx,z+14) if d>23 else None
  title='盐食备料与热加工'
 elif kind==1:
  m.set(xx,8,zz,'anvil[facing=north]');m.set(xx+3,8,zz,'water_cauldron[level=3]')
  m.box((xx,8,zz+2),(xx+4,8,zz+2),'dark_prismarine');m.box((xx,9,zz+2),(xx+4,10,zz+2),'iron_bars')
  title='潜具浸洗与金属修整'
 elif kind==2:
  m.box((xx,8,zz),(xx+4,9,zz),'bookshelf');m.set(xx+2,10,zz,'lantern[hanging=false]')
  m.set(xx+1,8,zz+2,'lectern[facing=north]');m.set(xx+3,8,zz+2,'cartography_table')
  title='图志翻阅与海图校对'
 elif kind==3:
  m.box((xx,8,zz),(xx,11,zz),'stripped_spruce_log');m.box((xx+4,8,zz),(xx+4,11,zz),'stripped_spruce_log')
  m.box((xx,11,zz),(xx+4,11,zz),'spruce_fence');m.box((xx+1,9,zz),(xx+3,10,zz),'iron_bars');m.set(xx+2,8,zz+2,'loom[facing=north]')
  title='渔网张力框与织补'
 elif kind==4:
  m.box((xx,8,zz),(xx+4,8,zz+2),'dark_prismarine')
  for i,b in enumerate(('flower_pot','sea_pickle[pickles=4,waterlogged=false]','amethyst_cluster')):m.set(xx+i*2,9,zz,b)
  title='贝器陈列与小件装配'
 elif kind==5:
  m.box((xx,8,zz),(xx,11,zz),'stripped_spruce_log');m.box((xx+4,8,zz),(xx+4,11,zz),'stripped_spruce_log');m.box((xx,11,zz),(xx+4,11,zz),'spruce_fence')
  for i in (1,3):m.set(xx+i,10,zz,'lantern[hanging=true]')
  m.set(xx+2,8,zz+2,'grindstone[face=floor,facing=north]');title='船灯悬挂检验与零件修整'
 else:
  m.box((xx,8,zz),(xx+4,8,zz+2),'spruce_planks');m.box((xx,9,zz),(xx+4,9,zz+1),'barrel');title='行李成件装箱交接'
 m.point(k+'industry','work',(xx,8,zz),title,approach=(xx-1,8,zz))


def dwelling(m,k,x,z,w,d,f=7):
 mid=z+d-12
 m.box((x, f+1,mid),(x+w,f+6,mid),'smooth_quartz');door(m,x+w//2,mid,f)
 home_fit(m,k+'live',x,z,w,d-13,f);sleep_fit(m,k+'bed',x,mid+1,w,11,f,beds=1)


def shop(v,pearl=False):
 fam='TC-05' if pearl else 'TC-F01';kind=8+v if pearl else v-1
 name=(['临街鉴珠铺','上居分级坊','转角贝作院','内庭珍珠交易坊'][v-1] if pearl else TRADES[v-1][0])
 shape=(v-1)%4
 if shape==0:
  m=start(fam,v,name+' · 前后贝厅',39,65);pod(m,5,5,28,52);door(m,19,5)
  m.box((6,8,26),(32,13,26),'smooth_quartz');door(m,19,26)
  fitting(m,'shop',6,6,26,19,kind);dwelling(m,'owner',6,27,26,29)
 elif shape==1:
  m=start(fam,v,name+' · 上住下作',43,47,h=42);pod(m,5,5,28,35);door(m,19,5)
  # Low lower shell is replaced by a flat supported upper floor, then roofed upper living.
  m.box((5,14,5),(33,21,40),'air');m.box((5,14,5),(33,14,40),'prismarine_bricks')
  pod(m,5,5,28,35,f=14);door(m,33,16,f=14,face='east')
  stairs(m,36,7,7);m.box((33,14,14),(38,14,17),'prismarine_bricks');rail(m,33,17,38,17,f=14);rail(m,38,14,38,17,f=14)
  fitting(m,'shop',6,6,26,32,kind);dwelling(m,'owner',6,6,26,33,f=14)
  m.meta['floors'].append(dict(name='干式上层住宅',y=14,max_y=20));room(m,'stair','外侧上层接廊',33,14,5,3,'连续实体楼梯和护栏通往楼上居所',f=14)
 elif shape==2:
  m=start(fam,v,name+' · 转角长翼',63,49);pod(m,5,5,24,25);pod(m,29,5,27,36);door(m,17,5);door(m,29,17,face='east')
  fitting(m,'shop',6,6,22,22,kind);dwelling(m,'owner',30,6,25,34)
  work(m,'packing',8,36,13,'crafting_table','侧院包装与货物交接');room(m,'yard','岸端工作小院',5,32,22,10,'带边护的干式露天包装院')
 else:
  m=start(fam,v,name+' · 三面内院',65,59);pod(m,5,5,52,20);pod(m,5,25,22,27);pod(m,35,25,22,27)
  door(m,31,5);door(m,16,25);door(m,46,25)
  fitting(m,'shop',6,6,50,17,kind);dwelling(m,'owner',36,26,20,25)
  work(m,'craft',8,29,16,'stonecutter[facing=south]' if pearl else TRADES[kind%8][1],'后院精作与工具维修');store(m,'materials',8,46,15,side=-1)
  meal(m,10,37);room(m,'workwing','独立加工翼',6,26,20,25,'精作长案、材料柜及值班坐席，独门面向内庭')
 if pearl:
  m.meta['terrain']['原料']='珍珠原料由外部合规水产或贸易供给；紫晶球与贝壳意象仅为分级陈列，不声明原版珍珠养殖。'
  for p in m.meta['points']:
   if p['kind']=='work' and p['id'] in ('shopbench','craft'):p['name']='珍珠清理、鉴定或分级工位'
 entry(m,m.size[0]//2,3);m.meta['source']='tools/structure_studio/studio/tidal_crafts.py';m.meta['differences']=[name,['一体长贝前店后居，内部隔门','上住下作双层贝厅，实心外梯','L形转角店面与居住长翼','三面内庭，交易、精作与生活分翼'][shape]]
 return m


def warehouse(v):
 m=start('TC-06',v,['贯通双门潮仓','中央分货高贝仓','岸端转运侧仓','双翼看管货院'][v-1],65 if v==4 else 51,59 if v==4 else 51)
 if v<4:
  x,z,w,d=5,5,40,38;pod(m,x,z,w,d);door(m,25,5);door(m,25,43,face='south')
  if v==1:
   for j,xx in enumerate((8,32)):
    for zz in (10,20,30):store(m,f'cargo{j}_{zz}',xx,zz,10)
   work(m,'check',8,38,10,'cartography_table','贯通路线侧验货');work(m,'care',32,38,10,'crafting_table','包装防潮检查')
  elif v==2:
   for j,zz in enumerate((9,19,29)):
    store(m,f'left{j}',8,zz,13);store(m,f'right{j}',29,zz,13)
   work(m,'sort',22,14,6,'crafting_table','中央分类台');work(m,'log',22,35,6,'lectern[facing=south]','中央清点台')
  else:
   m.box((6,8,28),(20,13,28),'smooth_quartz');m.box((21,8,28),(21,13,42),'smooth_quartz');door(m,14,28)
   kitchen(m,'kitchen',8,31);bed(m,'watch',16,38);store(m,'personal',8,39,4,side=-1);room(m,'watchroom','干式看管小室',6,29,14,12,'备餐、床位与私人用品，货区隔门')
   for j,zz in enumerate((10,20,33)):store(m,'cargo'+str(j),29,zz,13)
   work(m,'weigh',8,15,12,'cartography_table','陆水转运登记');store(m,'waiting',8,23,11,side=-1)
  room(m,'warehouse','高于高潮的干货主仓',6,6,38,36,'桩台主仓，分类货架和宽通道，普通门仅步行，货运机制另验')
 else:
  pod(m,5,5,22,45);pod(m,35,5,22,45);door(m,16,5);door(m,46,5);door(m,27,22,face='east');door(m,35,22,face='west')
  for i,xx in enumerate((8,38)):
   for zz in (10,20,30):store(m,f'cargo{i}_{zz}',xx,zz,16)
   work(m,f'log{i}',xx,42,16,'cartography_table','分翼验货与装箱')
   room(m,'wing'+str(i),'分货长仓',xx-2,6,20,43,'两翼干货与分别装卸，中间露天货运庭')
  work(m,'yard',29,35,4,'crafting_table','中庭临时交接');room(m,'yardroom','贯通分货院',28,6,6,43,'双翼间陆水贯通通路，后缘连续护栏')
 entry(m,m.size[0]//2,3);m.meta['source']='tools/structure_studio/studio/tidal_crafts.py';m.meta['differences']=[m.meta['name'],'货物离参考水面和高潮线存放；真实干式步行通路，桩脚接连续承载海床']
 m.meta['connections'].append(dict(kind='water',pos=[m.size[0]//2,5,m.size[2]-1],direction='south',note='水侧货物由港区转运，登船和升降装卸另接'))
 return m

_warehouse_before_pallets=warehouse
def warehouse(v):
 m=_warehouse_before_pallets(v)
 # Bundled cargo has actual bulk; keep shelf approaches and the central lane clear.
 positions=([(11,z) for z in (14,24,34)]+[(34,z) for z in (14,24,34)] if v==1 else
            [(11,z) for z in (13,23,33)]+[(32,z) for z in (13,23,33)] if v==2 else
            [(33,z) for z in (14,24,37)]+[(11,8)] if v==3 else
            [(x,z) for x in (11,41) for z in (14,24,34)])
 for i,(x,z) in enumerate(positions):
  m.box((x,8,z),(x+5,8,z+2),'spruce_slab[type=bottom]')
  m.box((x,9,z),(x+4,10,z+2),'barrel[facing=up]')
  m.box((x+1,11,z),(x+3,11,z+1),'spruce_planks')
  m.point('pallet'+str(i),'storage',(x+1,10,z),'离地捆装防潮货垛',approach=(x+1,8,z-1))
 return m

BUILDERS={**{f'TC-05-v{v:02}':(lambda v=v:shop(v,True)) for v in range(1,5)},**{f'TC-06-v{v:02}':(lambda v=v:warehouse(v)) for v in range(1,5)},**{f'TC-F01-v{v:02}':(lambda v=v:shop(v)) for v in range(1,9)}}
