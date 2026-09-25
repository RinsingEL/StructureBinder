"""Trade-specific academy repair, alchemy, crystal and daily commerce."""
from functools import partial
from .arcane_life import start,commons,bedroom,shell_room
from .arcane_academy import counter,storage,entry


TRADES={
'AA-05':[
 ('锻修器具铺','anvil','grindstone[face=floor,facing=north]','iron_block','修整工具、磨刃与备件','tool'),
 ('附魔校准铺','enchanting_table','lectern[facing=south]','bookshelf','器具附魔外形、术式校准和成品签收','enchant'),
 ('纸笔修复坊','cartography_table','lectern[facing=south]','bookshelf','纸卷修补、压平、墨水与干燥','paper'),
 ('法袍器具修补铺','loom[facing=south]','crafting_table','purple_wool','法袍与布带缝补、折叠和配件收纳','cloth')],
'AA-08':[
 ('街角配方药铺','brewing_stand','water_cauldron[level=3]','barrel','配方登记、洗涤配制和封存成品','potion'),
 ('干草浸制坊','brewing_stand','composter','hay_block','干草原料、浸制台和分装成品','herb'),
 ('临街药剂配制铺','brewing_stand','cauldron','brown_mushroom_block','分类菌材、配制与批次存放','fungus'),
 ('院内研磨药坊','grindstone[face=floor,facing=north]','brewing_stand','melon','原料研磨、配制与药材封柜','grind')],
'AA-10':[
 ('晶石初切坊','stonecutter[facing=south]','grindstone[face=floor,facing=north]','amethyst_block','原矿接收、初切、磨面与成品架','raw'),
 ('晶石分级坊','cartography_table','amethyst_cluster[facing=up]','amethyst_block','样晶比对、按级分格与登记','grade'),
 ('临街晶具加工铺','smithing_table','stonecutter[facing=south]','copper_block','晶具组装、加工、销售与返修','device'),
 ('双工位研晶坊','grindstone[face=floor,facing=north]','stonecutter[facing=south]','calcite','双加工工位、洗涤、原料成品分边','polish')],
'AA-F01':[
 ('学城面包铺','smoker[facing=south]','crafting_table','hay_block','烘烤、揉面、食品陈列与订单','bread'),
 ('窄街旧书铺','lectern[facing=south]','cartography_table','bookshelf','分类书架、书目检索与阅读试阅','book'),
 ('纸墨抄材铺','cartography_table','water_cauldron[level=3]','bookshelf','纸卷装订、墨液调配和纸墨库存','ink'),
 ('街角衣物铺','loom[facing=south]','crafting_table','purple_wool','布卷、裁制、折叠衣物与交易','clothes'),
 ('双间日用器皿铺','crafting_table','cauldron','terracotta','日用器皿分层陈列、配货与修补','pot'),
 ('菜果食品铺','composter','water_cauldron[level=3]','melon','菜果分格、清洗、配货与日常销售','fruit'),
 ('清洁用品铺','water_cauldron[level=3]','crafting_table','white_wool','洗涤用品、桶具和家庭布品','soap'),
 ('学生文具铺','lectern[facing=south]','cartography_table','barrel','课业文具、纸夹、书写试用与打包','stationery')]
}
# Main shop and living rooms change geometry and circulation together.
PLANS=[((4,5,18,22),(26,5,16,16),(26,25,12,12),'前店侧住'),
 ((4,5,22,18),(4,27,18,16),(26,27,12,12),'横厅后院'),
 ((4,5,16,28),(24,5,16,18),(24,27,14,12),'窄街长铺'),
 ((4,23,24,20),(4,5,16,14),(32,23,12,14),'前院后坊'),
 ((4,5,20,24),(28,5,18,16),(28,25,14,12),'双开间侧屋'),
 ((22,5,22,20),(4,5,16,16),(4,25,12,14),'偏角交易院'),
 ((4,5,18,18),(4,27,16,16),(24,27,12,12),'穿院配货铺'),
 ((4,23,20,22),(28,5,16,16),(28,25,12,14),'错轴前后院')]


def make(family,v):
    name,primary,secondary,stock,workflow,kind=TRADES[family][v-1]
    a,b,c,plan=PLANS[v-1];rs=[a,b,c];w=max(r[0]+r[2] for r in rs)+5;d=max(r[1]+r[3] for r in rs)+5
    m=start(family,v,name+' · '+plan,w,d,module='arcane_crafts')
    x,z,sw,sd=a
    shell_room(m,'shop',*a,'营业与专业制作厅',workflow+'；交易、原料、操作与成品分区')
    counter(m,'trade',x+2,z+2,sw-4,'lectern[facing=south]','顾客交易、订单和签收')
    storage(m,'raw',x+2,z+6,sw-6,stock)
    counter(m,'work',x+2,z+11,sw-7,primary,workflow)
    m.set(x+sw-3,4,z+11,secondary)
    m.point('secondary','work',(x+sw-3,4,z+11),'第二道操作与检验',approach=(x+sw-3,4,z+12))
    storage(m,'finished',x+2,z+sd-2,sw-4,'bookshelf' if kind in ('book','paper','enchant') else 'barrel',side=-1)
    # Trade-specific visible goods use supported counters rather than color swaps.
    for dx in range(2,sw-5,3):
        if kind in ('bread','fruit'):
            m.set(x+dx,5,z+6,'cake[bites=0]' if kind=='bread' else ('pumpkin' if dx%2 else 'melon'))
        elif kind in ('pot','herb','potion','fungus','grind'):
            m.set(x+dx,5,z+6,'flower_pot')
        elif kind in ('clothes','cloth','soap'):
            m.set(x+dx,5,z+6,'white_carpet' if dx%2 else 'purple_carpet')
        elif kind in ('raw','grade','device','polish'):
            m.set(x+dx,5,z+6,'amethyst_block' if dx%2 else 'calcite')
    if sd>=22:
        counter(m,'packing',x+2,z+16,sw-7,'crafting_table','打包、修后整理与批次复核')
    if family=='AA-08':
        m.meta['terrain']['配制条件']='独立存放的静态原料和器材；不声称药方、治疗或流体装置已运行。'
    if family=='AA-10':
        m.meta['terrain']['产业条件']='人工整平台地，保留从普通街道搬运晶石的宽院路；切割磨面仅为原版陈设。'
    commons(m,'living',*b);bedroom(m,'sleep',*c,count=1)
    entry(m,w//2,3)
    m.meta['differences']=[plan,workflow,'营业操作与后勤厨房起居、独立店主寝室明确分栋；住宅不是角落孤床']
    return m


BUILDERS={f'{f}-v{v:02}':partial(make,f,v) for f,variants in TRADES.items() for v in range(1,len(variants)+1)}

# Revised contiguous street architecture. Garden and civic modules are independent.
from .arcane_life import academy_start,academy_shell,steep_roof,stair_access,cross_door,domestic_fit,sleeping_fit
from .arcane_academy import kitchen,dining,bed,room
from .components import hip_roof


def household(m,key,x,z,w=24,d=18,f=3):
 # A small joined two-room apartment, not detached service buildings.
 split=x+w-10
 m.box((split,f+1,z+1),(split,f+6,z+d-1),'calcite');cross_door(m,split,z+8,'east',f)
 kitchen(m,key+'_cook',x+2,z+2,f);dining(m,x+2,z+8,5,f)
 storage(m,key+'_food',x+2,z+d-2,5,f=f,side=-1)
 m.set(x+9,f+1,z+11,'dark_oak_stairs[facing=west]');m.set(x+7,f+1,z+11,'dark_oak_slab[type=top]')
 storage(m,key+'_read',x+8,z+3,3,'bookshelf',f)
 bed(m,key+'_bed',split+4,z+d-4,f)
 counter(m,key+'_desk',split+2,z+2,5,'lectern[facing=south]','店主私室书写',f)
 storage(m,key+'_clothes',split+2,z+d-2,5,f=f,side=-1)
 m.set(split+7,f+1,z+5,'water_cauldron[level=3]')
 room(m,key+'_common','店主餐厨起居',x+1,z+1,split-1,z+d-1,'完整备餐、就餐、坐读和食品存放',f)
 room(m,key+'_sleep','隔门店主寝室',split+1,z+1,x+w-1,z+d-1,'个人床柜、书写、衣物和盥洗',f)


def trade_fit(m,key,x,z,w,d,trade,f=3):
 name,primary,secondary,stock,workflow,kind=trade
 counter(m,key+'_trade',x+2,z+3,min(w-6,11),'lectern[facing=south]','交易、订单与签收',f)
 storage(m,key+'_stock',x+2,z+7,min(w-8,12),stock,f)
 counter(m,key+'_work',x+2,z+12,min(w-7,12),primary,workflow,f)
 m.set(x+w-3,f+1,z+12,secondary);m.point(key+'_secondary','work',(x+w-3,f+1,z+12),'分开的第二操作与检验工位',approach=(x+w-3,f+1,z+13))
 storage(m,key+'_finished',x+2,z+d-2,min(w-5,15),'bookshelf' if kind in ('book','paper','enchant','ink') else 'barrel',f,side=-1)
 for i in range(2,min(w-8,12),3):
  material='cake[bites=0]' if kind=='bread' else 'flower_pot' if kind in ('herb','potion','fungus','grind') else 'amethyst_block' if kind in ('raw','grade','polish','device') else 'purple_carpet' if kind in ('cloth','clothes','soap') else 'melon' if kind=='fruit' else 'flower_pot'
  m.set(x+i,f+2,z+7,material)
 if kind in ('book','paper','enchant','ink','stationery'):
  storage(m,key+'_catalog',x+w-5,z+3,3,'bookshelf',f)
 if kind in ('tool','raw','polish','device'):
  m.box((x+w-5,f+1,z+8),(x+w-3,f+1,z+8),'polished_deepslate');m.set(x+w-4,f+2,z+8,primary)
  m.point(key+'_heavy','work',(x+w-4,f+2,z+8),'独立承台上的重加工工具',approach=(x+w-4,f+1,z+9))
 room(m,key,'交易陈列与专业工作间',x+1,z+1,x+w-1,z+d-1,workflow+'；接单、原料、主次操作和成品分区',f)


def revised_trade(family,v):
 trade=TRADES[family][v-1];offset={'AA-05':0,'AA-08':2,'AA-10':3,'AA-F01':0}[family]
 plan=(v-1+offset)%5
 names=['紧凑前店后住','上住下店街宅','转角折翼连铺','三翼生产内院','长铺后部夹层']
 m=academy_start(family,v,trade[0]+' · '+names[plan],60 if plan in (2,3) else 44,53)
 m.meta['source']='tools/structure_studio/studio/arcane_crafts.py'
 if plan==0:
  academy_shell(m,6,7,26,40);steep_roof(m,5,6,28,22,11);hip_roof(m,5,33,28,48,11,tiers=4)
  m.box((7,4,29),(31,9,29),'calcite');cross_door(m,15,29)
  trade_fit(m,'shop',6,7,26,22,trade);household(m,'home',6,29,26,18)
  cross_door(m,18,7);entry(m,18,3)
 elif plan==1:
  academy_shell(m,6,7,30,35);academy_shell(m,6,7,30,35,f=10)
  steep_roof(m,5,6,32,37,18);trade_fit(m,'shop',6,7,23,23,trade)
  counter(m,'packing',8,35,19,'crafting_table','后部装箱、修后整理与封存');room(m,'packing_room','底层后勤配货区',7,31,29,41,'制作后进入独立配货长案，右侧室内楼梯')
  stair_access(m,32,10)
  household(m,'home',6,23,30,19,10)
  counter(m,'upstairs_study',8,10,15,'lectern[facing=south]','店主账册与生活书写',10);storage(m,'upstairs_books',8,19,15,'bookshelf',10,side=-1)
  room(m,'upper_study','二层账房与坐读前厅',7,8,30,22,'私人账册、书柜和连接后部生活间的通路',10)
  cross_door(m,19,7);entry(m,19,3)
 elif plan==2:
  academy_shell(m,5,7,24,24);academy_shell(m,29,7,24,37)
  steep_roof(m,4,6,26,26,11);steep_roof(m,28,6,26,39,11)
  trade_fit(m,'shop',5,7,24,24,trade)
  counter(m,'side_work',31,10,16,trade[1],trade[4]+'的侧翼精修');storage(m,'side_raw',31,18,16,trade[3],side=-1)
  room(m,'side_workroom','街角侧翼操作间',30,8,52,24,'与营业厅相接的独立加工和分类原料')
  m.box((30,4,26),(52,9,26),'calcite');cross_door(m,37,26)
  household(m,'home',29,26,24,18);cross_door(m,29,19,'east')
  cross_door(m,16,7);cross_door(m,53,17,'east');entry(m,16,3)
 elif plan==3:
  academy_shell(m,5,7,48,19);academy_shell(m,5,26,20,21);academy_shell(m,29,26,24,21)
  hip_roof(m,4,54,6,27,11,tiers=4);steep_roof(m,4,25,22,23,11);steep_roof(m,28,25,26,23,11)
  trade_fit(m,'shop',5,7,25,19,trade)
  counter(m,'street_display',34,10,15,'lectern[facing=south]','沿街订单与样品复核');storage(m,'street_stock',34,21,15,trade[3],side=-1)
  counter(m,'yard_work',7,29,14,trade[1],trade[4]+'的院内操作');counter(m,'yard_pack',7,36,14,trade[2],'第二工序和批次复核');storage(m,'yard_raw',7,44,14,trade[3],side=-1)
  room(m,'yard_workroom','内院加工翼',6,27,24,46,'临院开门的加工、批次操作与原料成品柜')
  household(m,'home',29,26,24,21)
  for x in (15,27,37):cross_door(m,x,26)
  cross_door(m,27,7);entry(m,27,3);room(m,'courtyard','内院搬运通道',26,27,28,46,'作坊与住宅相连，院门允许地面人工搬运')
 else:
  academy_shell(m,6,7,28,40);academy_shell(m,6,27,28,20,f=10)
  steep_roof(m,5,26,30,22,18);hip_roof(m,5,35,6,26,11,tiers=4)
  trade_fit(m,'shop',6,7,28,21,trade)
  counter(m,'deep_work',8,32,18,trade[1],trade[4]+'的后部专业工位');storage(m,'deep_finished',8,44,18,'barrel',side=-1)
  room(m,'deep_workroom','夹层下生产作间',7,29,28,46,'后部加工长案与成品柜；东侧室内楼梯上夹层')
  stair_access(m,30,28);household(m,'home',6,27,23,20,10)
  cross_door(m,18,7);entry(m,18,3)
 if plan in (0,2,3):m.meta.update(floors=[dict(name='连通店铺主层',y=3,max_y=9)],roof_min_y=10)
 m.meta['differences']=[names[plan],trade[4],'连通主体、分室生活与行业工位；依据街面、楼层或内庭组织真实流线，不以独栋位置互换作为变体']
 return m


BUILDERS={f'{f}-v{v:02}':partial(revised_trade,f,v) for f,variants in TRADES.items() for v in range(1,len(variants)+1)}
