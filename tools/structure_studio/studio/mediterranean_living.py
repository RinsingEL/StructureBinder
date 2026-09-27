"""Reference-led Mediterranean homes, tea houses and inns; original layouts."""
from functools import partial
from .model import Model
from .components import shell, bench


def base(key,name,w=39,d=39):
    m=Model(key,name,(w,31,d),family=key.split('-v')[0],civilization='地中海',role='fill',terrain={'选址':'普通稳定平地；无需水岸。台地版由模板内石基形成高差。','边界':'完整独立建筑与庭院，保留入口通路和屋檐。'})
    m.box((1,0,1),(w-2,2,d-2),'sandstone');m.box((1,2,1),(w-2,2,d-2),'smooth_sandstone')
    m.box((1,3,1),(w-2,30,d-2),'air')
    m.meta.update(source='tools/structure_studio/studio/mediterranean_living.py:BUILDERS',ground_plane={'y':3,'note':'门外路面方块Y=2，外部站立接地线Y=3；上层与台地由实阶连接。'},floors=[dict(name='庭院与首层',y=2,max_y=7)],roof_min_y=8,preview_context=dict(kind='flat',land_surface_y=3,padding=3,surface='grass'))
    return m


def roof(m,x0,z0,x1,z1,y):
    sideways=x1-x0>z1-z0
    lo,hi=(z0,z1) if sideways else (x0,x1)
    start,end=(x0,x1) if sideways else (z0,z1)
    def put(u,v,yy,block):m.set(v,yy,u,block) if sideways else m.set(u,yy,v,block)
    for i in range((hi-lo+4)//2):
        a,b=lo-1+i,hi+1-i
        if a>b:break
        yy=y+i//2
        for v in range(start-1,end+2):
            for u,face in ((a,'south' if sideways else 'east'),(b,'north' if sideways else 'west')):
                put(u,v,yy,'brick_stairs[facing='+face+']' if i%2==0 else 'bricks')
        for v in (start,end):
            for u in range(a+1,b):
                for fy in range(y,yy):put(u,v,fy,'smooth_sandstone')
    for v in range(start-1,end+2):put((lo+hi)//2,v,y+(hi-lo+2)//4,'brick_slab')


def window(m,x,z,y,axis='x'):
    for dy in (1,2):
        m.set(x,y+dy,z,'light_blue_stained_glass')
        for offset in (-1,1):
            xx,zz=(x+offset,z) if axis=='x' else (x,z+offset)
            m.set(xx,y+dy,zz,'warped_trapdoor[facing='+('north' if axis=='x' else 'west')+',open=true]')
    m.set(x,y+3,z,'sandstone_slab')


def entry(m,key,x,z,y):
    m.door(x,y,z,wood='warped',facing='north')
    m.set(x,y+2,z,'sandstone_stairs[facing=north,half=top]')
    m.set(x-2,y+2,z-1,'lantern[hanging=true]');m.set(x+2,y+2,z-1,'lantern[hanging=true]')
    m.point(key,'entrance',(x,y,z-1),'入户或营业入口',facing='north')


def room(m,key,x0,z0,x1,z1,y=2,kind='home',roofed=True,doorx=None):
    if y>2:m.box((x0,2,z0),(x1,y,z1),'sandstone')
    shell(m,(x0,y,z0),(x1,y+5,z1),'smooth_sandstone','birch_planks',ceiling='birch_planks')
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,y+1,z),(x,y+5,z),'sandstone')
    for z in (z0,z1):
        for x in range(x0+3,x1-1,5):window(m,x,z,y+1)
    for x in (x0,x1):
        for z in range(z0+3,z1-1,5):window(m,x,z,y+1,axis='z')
    if roofed:roof(m,x0,z0,x1,z1,y+6)
    dx=doorx if doorx is not None else (x0+x1)//2
    entry(m,key+'entry',dx,z0,y+1)
    m.room(key,key,(x0+1,y+1,z0+1),(x1-1,y+4,z1-1),{'home':'家庭餐厨、双床与衣物','bed':'客房床位、行李与休息','tea':'茶饮备制、客桌与器皿','work':'作业台、工具与备料','store':'接待、行李与后勤'}[kind])
    furnish(m,key,x0,z0,x1,z1,y+1,kind)
    return dx


def point(m,key,x,y,z,name,kind='work'):
    m.point(key,kind,(x,y,z),name,approach=(x,y,z+1),look_at=[x,y+1,z])


def furnish(m,key,x0,z0,x1,z1,y,kind):
    x,z=x0+2,z0+3
    if kind in ('home','tea','work','store'):
        block={'home':'smoker[facing=south]','tea':'water_cauldron[level=3]','work':'crafting_table','store':'lectern[facing=south]'}[kind]
        m.set(x,y,z,block);m.set(x+1,y,z,'barrel');m.set(x+2,y,z,'crafting_table');point(m,key+'work',x,y,z,'餐厨备制' if kind=='home' else '接待或作业台')
        m.set(x+2,y+1,z,'lantern')
    if kind in ('home','bed'):
        for i,bx in enumerate((x0+2,x1-2)):
            bz=z1-3;m.bed(bx,y,bz,'cyan');m.set(bx,y,z1-1,'barrel')
            m.point(key+'bed'+str(i),'bed',(bx,y,bz),'床位与行李',approach=(bx+1 if i==0 else bx-1,y,bz))
    elif z1-z0>=10:
        table(m,x0+3,z1-3,y)
    m.set(x1-1,y,z0+2,'barrel');point(m,key+'store',x1-1,y,z0+2,'衣物食粮或营业库存','storage')


def table(m,x,z,y=3):
    m.set(x,y,z,'oak_fence');m.set(x,y+1,z,'oak_pressure_plate')
    m.set(x-1,y,z,'oak_stairs[facing=east]');m.set(x+1,y,z,'oak_stairs[facing=west]')


def stairs(m,x,z,y=3,rise=6):
    for i in range(rise):
        m.box((x,y-1,z+i),(x+1,y+i-1,z+i),'sandstone')
        m.box((x,y+i,z+i),(x+1,y+i,z+i),'sandstone_stairs[facing=south]')
    m.box((x,y-1,z+rise),(x+1,y+rise-1,z+rise),'sandstone')


def porch(m,x0,z0,x1,z1,y=3):
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,y,z),(x,y+3,z),'stripped_oak_log[axis=y]')
    m.box((x0,y+4,z0),(x1,y+4,z1),'oak_slab')
    for x in range(x0+2,x1,4):m.set(x,y+3,z0,'lantern[hanging=true]')


def raised(m,key,x0,z0,x1,z1,kind='home',lower=False):
    cx=(x0+x1)//2
    if lower:
        room(m,key+'ground',x0,z0,x1,z1,kind='tea' if kind=='bed' else 'store',roofed=False,doorx=x0+2)
        # Upper shell must not fill its lower usable storey.
        saved={p:b for p,b in m.blocks.items() if x0<=p[0]<=x1 and z0<=p[2]<=z1 and p[1]<8}
        room(m,key,x0,z0,x1,z1,y=8,kind=kind,doorx=cx)
        m.blocks.update(saved)
    else:room(m,key,x0,z0,x1,z1,y=8,kind=kind,doorx=cx)
    stairs(m,cx,z0-7)
    m.meta['floors']=[dict(name='庭院与下层',y=2,max_y=7),dict(name='上层或台地',y=8,max_y=13)]


def garden(m,x,z):
    m.set(x,3,z,'barrel');m.set(x,4,z,'flower_pot')
    m.set(x+1,3,z,'sandstone_wall')


def finish(m,note):
    w,_,d=m.size
    for x in range(3,w-3):
        if w//2-3<=x<=w//2+3:continue
        m.set(x,3,2,'sandstone_wall')
    for x in (2,w-3):
        for z in range(3,d-3):m.set(x,3,z,'sandstone_wall')
    for x in (3,w-4):
        m.box((x,3,2),(x,4,2),'sandstone');m.set(x,5,2,'lantern')
    # Only the open gate in the front boundary is an external road connection.
    # House doors inside the courtyard retain their real positions as circulation.
    for point in m.meta['points']:
        if point['kind']=='entrance':
            point['kind']='circulation';point['name']='院内房门：'+point['name']
    m.point('courtyard_street_entry','entrance',(w//2,3,2),'前院墙中央实际开口接路',facing='north')
    family=m.meta['family']
    m.meta['function_terms']= {'WT-06':['住宅','家庭居住'],'WT-07':['餐饮','茶饮'],'WT-08':['旅客住宿','餐饮']}[family]
    m.meta.update(design_notes=[note,'依2026-09-27用户参考图：暖白浅石、缓坡红瓦、木廊与蓝绿百叶，完整生活和营业尺度。'],differences=[note])
    return m


HOME_NAMES=['独户廊前住宅','三屋围院住宅','外阶双层窄宅','折角家庭庭院宅','台地家庭与低库','前作坊后家庭宅']
def home(v):
    m=base(f'WT-06-v{v:02}',HOME_NAMES[v-1])
    if v==1:
        room(m,'家宅',6,12,22,31);room(m,'侧库',26,20,34,31,kind='work');porch(m,6,7,22,10);table(m,11,9);garden(m,29,14)
    elif v==2:
        room(m,'西居',5,13,14,31);room(m,'东居',24,17,33,31);room(m,'后厅',16,25,22,34,kind='tea');table(m,19,18);porch(m,15,20,23,23)
    elif v==3:
        raised(m,'上层家居',10,14,24,32,lower=True);porch(m,7,4,16,6);garden(m,28,24)
    elif v==4:
        room(m,'西寝翼',5,10,15,31,kind='bed');raised(m,'后家庭',20,18,33,32,lower=True);table(m,20,9);porch(m,16,27,19,31)
    elif v==5:
        raised(m,'高台家宅',7,20,30,33);room(m,'低前库',6,5,14,12,kind='store');porch(m,6,15,13,18);garden(m,32,26)
    else:
        room(m,'前工坊',5,6,19,15,kind='work');raised(m,'后家宅',19,23,33,34);porch(m,5,18,16,25);table(m,10,21);garden(m,31,9)
    return finish(m,HOME_NAMES[v-1]+'：独立入口、生活内饰和不同屋体/庭院组合。')


TEA_NAMES=['街角双层茶馆','临水长廊茶馆','三翼庭院茶馆','台地双层休闲茶馆']
def tea(v):
    m=base(f'WT-07-v{v:02}',TEA_NAMES[v-1],43,41)
    if v==1:
        raised(m,'后茶厅',8,18,27,34,kind='tea',lower=True);room(m,'侧营业翼',30,13,38,33,kind='tea');porch(m,7,5,22,10);table(m,12,8);table(m,19,8)
    elif v==2:
        room(m,'长茶厅',5,13,36,25,kind='tea');porch(m,5,6,36,11)
        for x in (10,18,26,33):table(m,x,8)
        room(m,'后厨房',12,29,29,36,kind='tea')
        m.meta['terrain']['选址']='真实临水茶馆：模板前缘需接稳定岸墙或码头平台，水面在模板外，不把水写入室内地坪。'
    elif v==3:
        room(m,'西茶厅',5,14,14,33,kind='tea');room(m,'东茶厅',29,17,37,33,kind='tea');room(m,'后茶房',17,27,26,36,kind='tea');porch(m,16,14,27,20);table(m,20,17);table(m,24,23)
    else:
        room(m,'低茶厅',5,6,19,17,kind='tea');raised(m,'上台茶厅',8,26,34,36,kind='tea');porch(m,23,8,36,14);table(m,28,10);table(m,32,12)
    return finish(m,TEA_NAMES[v-1]+'：各翼独立备茶器皿与客桌，露天座席和高差形成各自轮廓。')


INN_NAMES=['廊院双客房小旅舍','街角双层旅店','围院多人旅店','坡台层叠旅店']
def inn(v):
    m=base(f'WT-08-v{v:02}',INN_NAMES[v-1],45,43)
    if v==1:
        room(m,'前接待餐厅',5,9,20,20,kind='tea');room(m,'后双床客房',6,26,19,37,kind='bed');room(m,'侧双床客房',25,22,37,37,kind='bed');porch(m,5,4,20,7);table(m,26,12)
    elif v==2:
        raised(m,'西上客房',6,15,19,33,kind='bed',lower=True);raised(m,'东上客房',26,20,38,36,kind='bed',lower=True);porch(m,21,29,24,36);table(m,24,9)
    elif v==3:
        room(m,'西客翼',5,12,15,35,kind='bed');room(m,'东客翼',30,12,40,35,kind='bed');room(m,'后接待餐厅',18,29,27,38,kind='tea');porch(m,17,17,28,23);table(m,21,20);table(m,25,25)
        for x in (18,25):garden(m,x,10)
    else:
        raised(m,'西楼客房',5,15,18,32,kind='bed',lower=True);raised(m,'高台客舍',25,27,39,38,kind='bed');room(m,'低院餐厅',25,7,39,17,kind='tea');porch(m,21,4,39,5);table(m,22,15)
    return finish(m,INN_NAMES[v-1]+'：接待餐饮与独立双床客房分区，行李柜和可达楼层齐备。')


BUILDERS={**{f'WT-06-v{i:02}':partial(home,i) for i in range(1,7)},**{f'WT-07-v{i:02}':partial(tea,i) for i in range(1,5)},**{f'WT-08-v{i:02}':partial(inn,i) for i in range(1,5)}}
