"""Fixed research gardens and small support shelters; vanilla planting only."""
from functools import partial
from .arcane_academy import base as civic_base,hall,door,counter,storage,entry,room
from .components import bench


def start(family,v,name,w,d,module='arcane_gardens'):
    m=civic_base(family,name,w,d,h=26,role='fill',site='稳定干燥的学院生活街区；整块人工石基，外部道路接普通步行入口')
    m.meta.update(id=f'{family}-v{v:02}',source=f'tools/structure_studio/studio/{module}.py',lifecycle='authored')
    m.meta['terrain']['模板落地']='基础Y=0..2落在连续承载层；不是自然坡地自适应，不用于未经整平的岸边与水面。'
    return m


def shell_room(m,key,x,z,w,d,label,purpose,side='north'):
    hall(m,x,z,x+w,z+d)
    dx,dz,face=(x+w//2,z,'north') if side=='north' else (x+w,z+d//2,'east') if side=='east' else (x,z+d//2,'west') if side=='west' else (x+w//2,z+d,'south')
    door(m,dx,dz,face)
    room(m,key,label,x+1,z+1,x+w-1,z+d-1,purpose)


def garden_start(family,v,name,w,d):
    m=start(family,v,name,w,d,module='arcane_gardens')
    m.box((3,3,3),(w-4,3,d-4),'dirt_path')
    m.meta['terrain']['农业落地']='固定模板内人工土床与边框，需稳定平地、采光、可用灌溉水和常规气候；自然连片田仍交Landscape，不宣称药效。'
    m.meta['terrain']['作物边界']='仅使用原版作物或观赏花草；不是葡萄种植或整合包药材机制。实际生长条件与整合包种类另行接入。'
    return m


def canopy(m,x,z,w,d,leaf=False):
    for xx in (x,x+w):
        for zz in (z,z+d):m.box((xx,4,zz),(xx,8,zz),'dark_oak_log')
    for zz in (z,z+d):m.box((x,8,zz),(x+w,8,zz),'dark_oak_log[axis=x]')
    for xx in range(x,x+w+1,2):
        m.box((xx,9,z),(xx,9,z+d),'dark_oak_slab[type=bottom]')
        if leaf:m.box((xx,10,z),(xx,10,z+d),'azalea_leaves[persistent=true]')
    m.box((x,8,z),(x,8,z+d),'dark_oak_log[axis=z]');m.box((x+w,8,z),(x+w,8,z+d),'dark_oak_log[axis=z]')


def plot(m,key,x,z,w,d,crop):
    m.box((x,3,z),(x+w,3,z+d),'stone_bricks')
    for xx in range(x+1,x+w):
        for zz in range(z+1,z+d):
            if xx==x+w//2:m.set(xx,3,zz,'water[level=0]')
            else:
                m.set(xx,3,zz,'farmland[moisture=7]');m.set(xx,4,zz,crop)
    m.point(key,'work',(x+1,4,z+1),'从田边观察和采收，土床中央灌溉槽',approach=(x+1,4,z))
    room(m,key,'独立灌溉试验畦',x,z,x+w,z+d,'原版作物、土层与水槽，保留完整田边通道',h=3)


FIELDS=[
 ('四格根菜试验田',[(4,5,8,8),(15,5,8,8),(4,16,8,8),(15,16,8,8)],(27,5),'carrots[age=7]'),
 ('窄条谷物试验田',[(4,5,8,19),(15,5,8,19)],(27,10),'wheat[age=7]'),
 ('折角甜菜药圃',[(4,5,8,8),(15,5,8,8),(4,16,8,8)],(15,18),'beetroots[age=3]'),
 ('双翼马铃薯菜园',[(4,5,8,12),(27,5,8,12)],(14,21),'potatoes[age=7]'),
 ('三段对照菜畦',[(4,5,8,8),(15,13,8,8),(26,21,8,8)],(4,24),'carrots[age=5]'),
 ('长院轮作记录田',[(4,5,8,8),(4,16,8,8),(4,27,8,8)],(16,5),'wheat[age=4]')]


def field(v):
    name,plots,sh,crop=FIELDS[v-1];sx,sz=sh;w=max(max(x+pw for x,z,pw,pd in plots),sx+12)+5;d=max(max(z+pd for x,z,pw,pd in plots),sz+12)+5
    m=garden_start('AA-F02',v,name,w,d)
    for i,(x,z,pw,pd) in enumerate(plots):plot(m,f'plot{i+1}',x,z,pw,pd,(['carrots[age=7]','potatoes[age=7]','beetroots[age=3]'][i%3] if v==5 else crop))
    shell_room(m,'records',sx,sz,12,12,'工具与试验记录室','种子工具分类柜、记录桌、清洗桶与短歇凳')
    counter(m,'record',sx+2,sz+2,7,'lectern[facing=south]','试验批次、种植和观察记录');storage(m,'tools',sx+2,sz+10,8,side=-1)
    m.set(sx+10,4,sz+4,'water_cauldron[level=3]');bench(m,sx+2,4,sz+6,5,'north','dark_oak')
    entry(m,w//2,3);m.meta['differences']=[name,'田块形状、分段和工具间位置共同变化；有灌溉水槽和完整边路，不依赖拼接补齐']
    return m


PERGOLAS=[('直列共读藤架',[(4,6,18,8)],False),('折翼采收架',[(4,5,16,7),(4,16,7,12)],True),('双列交流藤庭',[(4,5,18,7),(4,18,18,7)],False),('靠墙观察花架',[(5,6,22,9)],True)]

def pergola(v):
    name,rs,planted=PERGOLAS[v-1];w=max(x+pw for x,z,pw,pd in rs)+6;d=max(z+pd for x,z,pw,pd in rs)+8
    m=garden_start('AA-F03',v,name,w,d);m.meta['roof_min_y']=9
    for i,(x,z,pw,pd) in enumerate(rs):
        canopy(m,x,z,pw,pd,leaf=True)
        bench(m,x+2,4,z+2,min(5,pw-3),'north','dark_oak')
        counter(m,f'table{i}',x+2,z+pd-2,min(5,pw-3),'crafting_table' if planted else 'lectern[facing=south]','花草照料与采集整理' if planted else '共读交流桌')
        room(m,f'shade{i}','开放架下休憩',x+1,z+1,x+pw-1,z+pd-1,'端部坐席和桌台，中间连续通行',h=5)
    if planted:
        z=d-5;m.box((4,3,z),(w-5,3,z+1),'dirt')
        for x in range(4,w-4,2):m.set(x,4,z,'allium');m.set(x,4,z+1,'azure_bluet')
        m.point('plants','work',(5,4,z),'原版观赏花草，不宣称药效或葡萄产出',approach=(5,4,z-1))
    if v==4:m.box((4,4,6),(4,8,15),'calcite')
    entry(m,w//2,3);m.meta['differences']=[name,'种植照料型：原版观赏花床与整理桌' if planted else '纯遮蔽休憩型：叶冠仅为固定装饰，坐读交流', '架形和组数不同；无葡萄或自动生长声明']
    return m


def shed(v):
    names=['密封分类储物棚','双架药草晾晒棚','开侧仪器检校棚','离地样本取样亭'];w,d=[(22,21),(31,24),(29,23),(25,28)][v-1]
    m=garden_start('AA-F04',v,names[v-1],w,d)
    if v==1:
        shell_room(m,'shed',4,5,14,12,'封闭材料棚','双组分类原料架、成品封箱和交接桌')
        storage(m,'raw',6,7,10);storage(m,'finished',6,15,10,side=-1);counter(m,'handover',6,11,7,label='材料交接和归档')
    elif v==2:
        for x in (4,17):
            canopy(m,x,5,9,12)
            storage(m,f'dry{x}',x+2,8,5,'hay_block');storage(m,f'tray{x}',x+2,15,5,'barrel',side=-1)
        counter(m,'record',6,20,9,'lectern[facing=south]','双架晾晒批次记录')
        room(m,'dry','通风双晾晒架',4,5,26,17,'双架分类晾晒、周围开放通风和中央搬运路',h=5)
    elif v==3:
        canopy(m,4,5,20,13)
        counter(m,'instrument',6,8,12,'cartography_table','仪器检校与测绘展开');counter(m,'repair',6,14,12,'smithing_table','备件检修')
        storage(m,'tools',6,17,13,side=-1);m.box((4,4,5),(24,7,5),'calcite')
        room(m,'instruments','开侧检校棚',5,6,23,17,'实体背墙、双工位、分类仪器与备件，三侧采光通风',h=5)
    else:
        canopy(m,4,5,16,17)
        counter(m,'sample',6,8,10,'brewing_stand','取样、清洗与分装记录');m.set(18,4,8,'water_cauldron[level=3]')
        storage(m,'sealed',6,20,10,side=-1)
        for x in (7,12,17):
            m.set(x,4,15,'polished_deepslate');m.set(x,5,15,'tinted_glass');m.set(x,6,15,'amethyst_cluster[facing=up]')
        m.point('specimens','work',(12,5,15),'台座上隔离的静态样本',approach=(12,4,16))
        room(m,'sample','独立取样亭',5,6,19,21,'记录台、清洗桶、三座离地样本和封箱架',h=5)
    entry(m,w//2,3);m.meta['differences']=[names[v-1],'封闭储存、通风晾晒、开侧检校与独立取样各有不同结构和工位，不含住宿']
    return m


BUILDERS={**{f'AA-F02-v{v:02}':partial(field,v) for v in range(1,7)},**{f'AA-F03-v{v:02}':partial(pergola,v) for v in range(1,5)},**{f'AA-F04-v{v:02}':partial(shed,v) for v in range(1,5)}}
