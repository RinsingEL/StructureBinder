"""Fixed seasonal food plots and fodder/animal-care variants for steppe camps."""
from functools import partial
from .steppe_common import base, ground, entry, point, cabinet, dining
from .steppe_caravans import canopy
from .steppe_crafts import fence, pen, rest
from .components import bench


def plot(m,key,x0,z0,x1,z1,crop='carrots'):
    m.box((x0-1,2,z0-1),(x1+1,2,z1+1),'coarse_dirt')
    waters={(x,z) for x in set([*range(x0+3,x1+1,7),max(x0,x1-3)]) for z in set([*range(z0+3,z1+1,7),max(z0,z1-3)])}
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            if (x,z) in waters:m.set(x,2,z,'water[level=0]')
            else:m.set(x,2,z,'farmland[moisture=7]');m.set(x,3,z,f'{crop}[age={3 if crop=="beetroots" else 7}]')
    m.point(key,'work',(x0,3,z0),'季节短畦采收',approach=(x0-1,3,z0))
    m.room(key,'短期营地种植畦',(x0,3,z0),(x1,5,z1),'四格内独立水眼与完整田埂；限足够生长季和日照')


def garden(v):
    sizes=[(31,29),(25,43),(37,35),(43,37)];w,d=sizes[v-1]
    names=['双短畦菜圃','狭长分段菜畦','围栏四格菜圃','帐边转角菜圃']
    m=base(f'SC-F02-v{v:02}',names[v-1],(w,19,d),'只用于具有耕土、可靠运水和足够生长季的固定或长驻营地；'+names[v-1]+'沿既有补给小路布置。','steppe_gardens')
    ground(m,1,1,w-2,d-2)
    specs={1:[(4,5,10,16,'carrots'),(17,5,23,16,'potatoes')],2:[(4,5,10,18,'carrots'),(4,24,10,36,'beetroots')],3:[(5,5,12,12,'carrots'),(22,5,29,12,'potatoes'),(5,18,12,25,'beetroots'),(22,18,29,25,'wheat')],4:[(4,5,11,16,'carrots'),(4,23,15,30,'potatoes'),(21,25,36,30,'beetroots')]}[v]
    for i,p in enumerate(specs):plot(m,'plot'+str(i),*p)
    if v==1:
        canopy(m,3,20,24,25,'brown',7);cabinet(m,'seed',5,21,4,label='种子与轮作记号')
        point(m,'sort',13,22,'crafting_table','采收分拣与扎束');point(m,'wash',20,22,'water_cauldron[level=3]','洗菜与器具储水')
    elif v==2:
        canopy(m,14,5,21,21,'orange',7);cabinet(m,'seed',15,7,4,label='分季种子和工具')
        point(m,'sort',17,13,'crafting_table','狭地采收包装');point(m,'wash',17,17,'water_cauldron[level=3]','运水暂存与清洗')
        bench(m,15,3,27,4,'north','dark_oak')
    elif v==3:
        fence(m,2,2,33,28);canopy(m,12,29,25,32,'brown',7)
        cabinet(m,'seed',13,30,4,label='农具及种子周转');point(m,'sort',23,30,'crafting_table','围圃交接分拣');point(m,'wash',17,15,'water_cauldron[level=3]','中央储水与灌畦')
    else:
        rest(m,23,3,'gardenkeeper');cabinet(m,'seed',15,8,4,label='种子与采收筐');point(m,'sort',16,14,'crafting_table','帐边分拣');point(m,'wash',16,19,'water_cauldron[level=3]','田间器具清洗')
    point(m,'compost',w-5,d-4,'composter','植物余料集中堆肥',approach=(w-6,3,d-4))
    entry(m,w//2,d-2)
    m.meta['agriculture']=dict(type='fixed_template',crops=sorted({p[4] for p in specs}),water='全部耕地与同高水眼四格距离核验；实际光照、生长季及供水另验',layout=names[v-1])
    m.meta['differences']=[names[v-1]+'；面积、田埂组织、工作棚与值守帐分别不同。']
    return m


def fodder(v):
    w,d=[(31,31),(39,31),(39,37),(51,35)][v-1]
    names=['开敞照护草棚','半围合饲草栏','储草与分料双棚','照护小帐兽栏']
    m=base(f'SC-F03-v{v:02}',names[v-1],(w,19,d),'依托已有畜群、水草及运水路线；'+names[v-1]+'储草架离开低洼积水，入口背风，不占商队车道。','steppe_gardens')
    ground(m,1,1,w-2,d-2)
    if v==1:
        canopy(m,4,3,25,13,'brown',8)
        m.box((5,3,5),(10,5,8),'hay_block');m.box((18,3,5),(23,4,8),'hay_block')
        point(m,'water',20,18,'water_cauldron[level=3]','开敞饮畜水');cabinet(m,'care',5,18,4,label='梳洗与照护工具')
        m.point('hay','storage',(8,4,8),'分组干饲草垛',approach=(8,3,9));m.room('open','背风照护与草料棚',(4,3,3),(25,7,24),'棚下干草、露天照护与饮水，不封闭畜群通路')
    elif v==2:
        pen(m,4,4,21,20,cover=False);canopy(m,5,4,24,11,'brown',8)
        cabinet(m,'dry',29,7,5,label='干料与备用照护器具');point(m,'mix',30,15,'crafting_table','饲料补配与交接')
    elif v==3:
        pen(m,4,4,16,25);canopy(m,23,4,34,25,'orange',8)
        m.box((25,3,6),(31,5,10),'hay_block');cabinet(m,'bags',25,14,5,label='草料袋与储运工具')
        point(m,'mix',28,20,'crafting_table','草料分切与打包');m.point('dry','storage',(28,4,10),'高位干草储备',approach=(28,3,11))
        m.room('dry','储草分料棚',(23,3,4),(34,7,25),'干草、袋料、分切打包与发放分区')
    else:
        rest(m,3,4,'carer');pen(m,24,4,22,23,water=2)
        cabinet(m,'medicine',5,25,5,label='兽用清洁与敷料');point(m,'check',15,26,'brewing_stand','照护记录与配料台')
    entry(m,w//2,d-3)
    m.meta['differences']=[names[v-1]+'；开放照护、半围合、分料储草和完整值守生活组合分别建立。']
    return m


BUILDERS={**{f'SC-F02-v{v:02}':partial(garden,v) for v in range(1,5)},**{f'SC-F03-v{v:02}':partial(fodder,v) for v in range(1,5)}}
