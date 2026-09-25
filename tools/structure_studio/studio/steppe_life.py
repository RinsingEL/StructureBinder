"""Complete portable households and guest camps, independent of frozen core assets."""
from functools import partial
from math import sqrt
from .model import Model
from .components import bench, shelf, pendant
from .steppe_caravans import canopy


def base(key,name,size,condition,source='steppe_life'):
    m=Model(key,name,size,family=key.rsplit('-v',1)[0],civilization='游牧',role='fill',terrain={
        '选址':condition,'资源与季节':'须接已有水草与补给路线，储槽为外部运水；按当地风向使入口背风，长季定居与短期停驻明确区分。',
        '高程':'默认入口脚底Y=3；坡台住宅Y=5由实体台阶接入，见点位。',
        '落地限制':'仅整理模板营地占地，不推平外部草坡；留出畜群、货车或来客外接通路。',
        '运行边界':'原版静态空间与作者点位，未接入动物、交易、季节迁徙或运行时生成。'})
    m.meta.update(source='tools/structure_studio/studio/'+source+'.py:'+key,roof_min_y=7,
        floors=[dict(name='营地生活层',y=2,max_y=6)],preview_context=dict(kind='flat',land_surface_y=3,bed_y=-1,padding=3,surface='grass'))
    return m


def ground(m,x0,z0,x1,z1,f=2,material='grass_block'):
    m.box((x0,0,z0),(x1,f,z1),'dirt');m.box((x0,f,z0),(x1,f,z1),material)
    m.box((x0,f+1,z0),(x1,m.size[1]-1,z1),'air')


def entry(m,x,z,y=3,key='entry'):
    m.point(key,'entrance',(x,y,z),'背风营路入口',facing='south')
    m.meta['connections'].append(dict(kind='pedestrian',pos=[x,y-1,z],direction='south',clearance=[3,3],note='接已有营路；人畜与车辆另留净空'))


def point(m,key,x,z,block,label,f=2,approach=None,kind='work'):
    m.set(x,f+1,z,block);m.point(key,kind,(x,f+1,z),label,approach=approach or (x,f+1,z+1))


def cabinet(m,key,x,z,w=3,f=2,label='分类物资柜'):
    shelf(m,x,f+1,z,w,'dark_oak');m.point(key,'storage',(x+1,f+2,z),label,approach=(x+1,f+1,z+1))


def dining(m,key,x,z,w=3,f=2,label='用餐与起居桌'):
    m.box((x,f+1,z),(x+w-1,f+1,z+1),'dark_oak_slab[type=top]')
    m.set(x+w-1,f+2,z,'flower_pot')
    bench(m,x,f+1,z-2,w,'south','dark_oak');bench(m,x,f+1,z+3,w,'north','dark_oak')
    m.point(key,'work',(x,f+1,z),label,approach=(x-1,f+1,z))


def tent(m,x0,z0,w,d,f=2,color='orange'):
    cx=x0+w//2;cz=z0+d//2;rx=w//2;rz=d//2
    inside=lambda x,z: ((x-cx)/rx)**2+((z-cz)/rz)**2<=1.001
    for x in range(x0,x0+w):
        for z in range(z0,z0+d):
            if not inside(x,z):continue
            m.box((x,0,z),(x,f,z),'dirt');m.set(x,f,z,'dark_oak_planks')
            m.box((x,f+1,z),(x,f+11,z),'air')
            edge=any(not inside(x+dx,z+dz) for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)])
            if edge:
                m.box((x,f+1,z),(x,f+4,z),'white_wool');m.set(x,f+2,z,color+'_wool')
            dist=sqrt(((x-cx)/rx)**2+((z-cz)/rz)**2)
            yy=f+5+int((1-dist)*4)
            m.set(x,yy,z,color+'_wool' if x==cx or z==cz else 'white_wool')
    for x,z in [(cx-rx,cz),(cx+rx,cz),(cx,cz-rz)]:
        m.box((x,f+1,z),(x,f+5,z),'dark_oak_log[axis=y]')
    # Full transverse ridge support at head height; no interior pole competes with furniture.
    m.box((cx-rx,f+5,cz),(cx+rx,f+5,cz),'dark_oak_log[axis=x]')
    m.box((cx-1,f+1,cz+rz-1),(cx+1,f+3,cz+rz),'air')
    m.box((cx-1,f+4,cz+rz),(cx+1,f+4,cz+rz),color+'_wool')
    pendant(m,cx,f+4,cz,f+9)
    return cx,cz


def rug(m,x,z,w,d,f=2,color='orange'):
    m.box((x,f,z),(x+w-1,f,z+d-1),color+'_wool')
    for xx in range(x,x+w,3):m.box((xx,f,z),(xx,f,z+d-1),'white_wool')


def locker(m,key,x,z,f=2,label='个人衣物与毡被'):
    shelf(m,x,f+1,z,3,'birch');m.point(key,'storage',(x+1,f+2,z),label,approach=(x+1,f+1,z+1))


def berth(m,key,x,z,f=2,face='north',screen=True):
    m.bed(x,f+1,z,'red',face);m.point(key,'sleep',(x,f+1,z),'独立床榻',approach=(x+1,f+1,z))
    if screen:m.box((x-1,f+1,z-2),(x-1,f+3,z+1),'orange_wool')
    m.set(x,f+1,z+2,'barrel[facing=up]')


def meal(m,key,x,z,w=4,f=2):
    m.box((x,f+1,z),(x+w-1,f+1,z+1),'birch_slab[type=top]')
    bench(m,x,f+1,z-2,w,'south','birch');bench(m,x,f+1,z+3,w,'north','birch')
    m.point(key,'work',(x,f+1,z),'浅木餐桌与双侧席',approach=(x-1,f+1,z))


def kitchen(m,key,x,z,f=2):
    m.box((x,f+1,z),(x+4,f+1,z),'birch_planks')
    point(m,key+'cook',x,z,'smoker[facing=south]','炊煮与备餐',f)
    point(m,key+'water',x+4,z,'water_cauldron[level=3]','运水清洗',f)
    m.box((x,f+2,z),(x,f+9,z),'cobblestone_wall')
    locker(m,key+'food',x,z-2,f,'干粮与炊具')


def hearth(m,key,x,z,f=2):
    m.box((x-1,f,z-1),(x+1,f,z+1),'stone_bricks')
    point(m,key,x,z,'campfire[lit=false]','中心可收拢火塘',f,(x+2,f+1,z))


def note(m,key,a,b,name,purpose):m.room(key,name,a,b,purpose)


def home(v):
    sizes={1:(27,19,33),2:(33,19,37),3:(29,19,43),4:(53,19,45),5:(39,19,43),6:(33,21,39)}
    names=['独居中心火塘帐','周边床榻家庭圆帐','纵向家务长帐','双帐共享炊庭','前廊作业风障帐','坡台冬居回廊帐']
    m=base(f'SC-04-v{v:02}',names[v-1],sizes[v],'背风稳定营地，按具体帐群占地接已有营路；'+names[v-1]);ground(m,1,1,m.size[0]-2,m.size[2]-2)
    if v==1:
        tent(m,4,3,19,21);rug(m,10,11,6,8);hearth(m,'fire',13,13)
        berth(m,'bed',8,10);locker(m,'clothes',10,6);kitchen(m,'k',15,9)
        meal(m,'meal',9,18,3);point(m,'read',18,17,'lectern[facing=west]','独居读写',approach=(17,3,17))
        note(m,'one',(5,3,5),(21,6,23),'围火独居空间','入口绕火塘抵达西侧屏后床榻，北侧衣柜与东侧厨房分开')
    elif v==2:
        tent(m,4,3,25,25);rug(m,12,12,10,10, color='red')
        berth(m,'westbed',9,12);berth(m,'eastbed',23,12);berth(m,'rearbed',16,7,screen=False)
        locker(m,'westcloth',8,17);locker(m,'eastcloth',21,17);meal(m,'familymeal',14,16,4)
        kitchen(m,'familyk',13,23);point(m,'sew',9,21,'loom[facing=east]','家庭缝补',approach=(10,3,21))
        note(m,'family',(6,3,5),(27,6,27),'周边床榻与中心餐叙','三处床榻沿弧墙分布，以短侧屏遮视线；中心不设横墙，厨房在入口侧')
    elif v==3:
        tent(m,4,3,21,31);rug(m,12,9,5,21,color='brown')
        for i,z in enumerate((10,18)):berth(m,'bed'+str(i),9,z);locker(m,'box'+str(i),17,z-2)
        m.box((11,3,7),(11,5,20),'white_wool')
        kitchen(m,'longk',15,24);meal(m,'longmeal',10,28,3)
        point(m,'loom',8,23,'loom[facing=east]','前部织补作业',approach=(9,3,23));point(m,'record',19,19,'lectern[facing=west]','家务分类记录',approach=(18,3,19))
        note(m,'long',(6,3,5),(23,6,32),'纵向生活通廊','左侧连续私密床区、右侧衣物备餐，入口大段用于织补与餐席')
    elif v==4:
        for key,x in [('a',4),('b',30)]:
            tent(m,x,3,19,21, color='orange' if key=='a' else 'brown');rug(m,x+5,10,8,8)
            berth(m,key+'bed',x+5,10);locker(m,key+'cloth',x+9,7);meal(m,key+'sit',x+9,15,3)
            note(m,key,(x+2,3,5),(x+16,6,22),'独立寝居帐','寝居与家户用品独立，炊事集中院内公共棚')
        canopy(m,13,28,38,37,'brown',7);kitchen(m,'sharedk',16,30);meal(m,'sharedmeal',27,32,5)
        locker(m,'tools',5,31,label='共同迁徙工具');note(m,'yard',(5,3,24),(47,6,39),'双帐共享炊庭','两帐之间及前庭形成环行交往空间，公共厨房不复制进每个帐')
    elif v==5:
        tent(m,8,3,23,23);rug(m,15,11,8,9);berth(m,'bed',13,10);locker(m,'linen',21,7)
        meal(m,'indoor',18,16,4);point(m,'read',13,18,'lectern[facing=east]','帐内读写',approach=(14,3,18))
        canopy(m,5,27,33,36,'orange',7);kitchen(m,'porchk',8,30);point(m,'weave',25,31,'loom[facing=south]','前廊织补');locker(m,'workstock',26,28,label='作业材料')
        m.box((5,3,27),(5,5,36),'brown_wool');m.box((33,3,27),(33,5,36),'brown_wool')
        note(m,'porch',(6,3,27),(32,6,36),'横向作业前廊','两端风障保护炊事织补，访客先经工作廊后进睡居帐')
        note(m,'sleep',(10,3,5),(29,6,24),'安静寝居帐','日间作业移至前廊，内部用于休息用餐和衣物')
    else:
        tent(m,5,3,23,25,4);rug(m,12,11,10,12,4,'red');hearth(m,'winterfire',16,16,4)
        berth(m,'leftbed',10,12,4);berth(m,'rightbed',22,11,4);locker(m,'wintercloth',14,7,4)
        kitchen(m,'winterk',18,22,4);meal(m,'wintermeal',9,21,3,4)
        # Raised terrace with offset approach and low windbreak, distinct from a flat round tent.
        m.box((4,0,2),(28,3,28),'dirt')
        for zz,yy in [(30,3),(29,4)]:
            m.box((15,0,zz),(17,yy-1,zz),'cobblestone');m.box((15,yy,zz),(17,yy,zz),'cobblestone_stairs[facing=north]')
        m.box((15,0,28),(17,4,28),'dark_oak_planks')
        m.box((6,3,32),(21,5,32),'brown_wool');m.box((6,3,29),(6,5,32),'brown_wool')
        note(m,'winter',(7,5,5),(26,8,26),'冬居围火与边床','中心围火、两侧床榻、后部衣物；入口低风障需绕东端再上台阶')
        m.meta['floors']=[dict(name='坡台冬居层',y=4,max_y=8)];m.meta['roof_min_y']=9
    entry(m,m.size[0]//2,m.size[2]-3);m.meta['differences']=[names[v-1]+'；以火塘、床榻、厨房和公共空间关系决定平面，浅木家具与织毯区分功能。']
    return m


def sleep_pod(m,key,x,z,w=17,d=19,beds=1):
    tent(m,x,z,w,d,color='brown');rug(m,x+5,z+6,w-10,d-9,color='red')
    berth(m,key+'bed',x+5,z+7)
    if beds>1:berth(m,key+'bed2',x+w-5,z+7)
    locker(m,key+'bag',x+w//2-1,z+3,label='旅人独立行李与毡被')
    meal(m,key+'packing',x+w//2-1,z+d-8,3)
    point(m,key+'read',x+w//2+2,z+d-5,'lectern[facing=south]','旅途阅读与便笺')
    note(m,key,(x+2,3,z+2),(x+w-3,6,z+d-2),'独立旅人寝帐','只含休息、衣物、读写，避免将家庭厨房复制进每个客帐')


def guest(v):
    sizes={1:(47,19,35),2:(31,19,47),3:(61,19,53),4:(55,19,48)}
    names=['双独立旅人小帐','中央通廊六床长旅帐','独立客帐与公共餐帐','客宿长帐与接待小帐']
    m=base(f'SC-09-v{v:02}',names[v-1],sizes[v],'路线补给节点，入口避开牲畜与货车主路；'+names[v-1]);ground(m,1,1,m.size[0]-2,m.size[2]-2)
    if v==1:
        sleep_pod(m,'left',3,3);sleep_pod(m,'right',27,3)
        canopy(m,13,24,34,30,'brown',7);meal(m,'meal',16,27,4);point(m,'host',29,27,'lectern[facing=south]','双帐登记');point(m,'water',33,27,'water_cauldron[level=3]','公共饮水')
    elif v==2:
        tent(m,4,3,23,35,color='brown');rug(m,13,7,5,27,color='red')
        for i,z in enumerate((10,17,24)):
            berth(m,'west'+str(i),9,z);berth(m,'east'+str(i),20,z)
            m.box((7,3,z+3),(11,5,z+3),'white_wool');m.box((20,3,z+3),(24,5,z+3),'white_wool')
        locker(m,'linen',14,6,label='集中备用毡被');meal(m,'meal',12,32,4)
        point(m,'host',10,31,'lectern[facing=east]','共宿值班登记',approach=(11,3,31));point(m,'water',21,31,'water_cauldron[level=3]','客人饮水')
        note(m,'dorm',(6,3,5),(25,6,36),'中央通廊共宿','六床沿两侧成三个屏隔舱；入口公共餐叙与长直行李通路')
    elif v==3:
        sleep_pod(m,'west',4,3,19,21,2);sleep_pod(m,'east',38,3,19,21,2)
        tent(m,20,27,23,23,color='orange');rug(m,27,34,9,9);kitchen(m,'publick',27,32)
        meal(m,'publicmeal',26,40,6);locker(m,'food',35,37,label='公共餐帐备粮');point(m,'host',24,37,'lectern[facing=east]','集中接待',approach=(25,3,37))
        note(m,'dining',(22,3,29),(41,6,48),'独立公共餐帐','两寝帐共享第三餐帐，安静庭院与集中接待分离')
    else:
        sleep_pod(m,'dorm',4,3,23,29,2)
        berth(m,'rearleft',10,21);berth(m,'rearright',22,21)
        m.box((8,3,17),(12,5,17),'white_wool');m.box((20,3,17),(24,5,17),'white_wool')
        canopy(m,31,5,49,20,'brown',7);kitchen(m,'servicek',34,8);meal(m,'servicemeal',36,15,5)
        tent(m,34,27,17,17,color='orange');locker(m,'hoststock',39,30,label='客帐备用床品');point(m,'host',40,35,'lectern[facing=south]','接待与行李核对')
        m.box((37,3,37),(43,3,38),'birch_slab[type=top]');m.point('luggage','storage',(40,3,37),'短时行李分拣台',approach=(40,3,39))
        note(m,'service',(32,3,6),(48,6,19),'公共餐饮棚','多床客帐的食物处理与餐席外置，后侧接待小帐处理行李')
        note(m,'reception',(36,3,29),(49,6,42),'独立接待小帐','集中登记、床品与行李整理，不与睡眠流线混用')
    entry(m,m.size[0]//2,m.size[2]-3);m.meta['differences']=[names[v-1]+'；独立客帐、共宿舱、餐帐与接待帐按服务组织分别组合。']
    return m


BUILDERS={**{f'SC-04-v{v:02}':partial(home,v) for v in range(1,7)},**{f'SC-09-v{v:02}':partial(guest,v) for v in range(1,5)}}
