"""Four reference-led inns, with independent massing and usable circulation."""
from .model import Model
from .components import shell, bench, pendant


def _room(m,key,name,x0,z0,x1,z1,f=1,wall='smooth_sandstone'):
    shell(m,(x0,f,z0),(x1,f+6,z1),wall,'smooth_sandstone',ceiling='smooth_sandstone')
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,f+1,z),(x,f+7,z),'cut_sandstone')
    for z in (z0,z1):
        for x in range(x0+2,x1-1,4):m.box((x,f+3,z),(x+1,f+4,z),'oak_fence')
    for x in (x0,x1):
        for z in range(z0+3,z1-2,5):m.box((x,f+3,z),(x,f+4,z),'oak_fence')
    for x in (x0,x1):
        for z in (z0,z1):m.set(x,f+8,z,'smooth_sandstone_slab[type=bottom]')
    m.room(key,name,(x0+1,f+1,z0+1),(x1-1,f+6,z1-1),name)
    pendant(m,(x0+x1)//2,f+5,(z0+z1)//2,f+7)


def _door(m,x,z,f=1,face='north'):
    m.box((x,f+1,z),(x,f+3,z),'air');m.door(x,f+1,z,wood='oak',facing=face)
    m.set(x,f+4,z,'chiseled_sandstone')


def _deck(m,x0,z0,x1,z1,f):
    m.box((x0,0,z0),(x1,f,z1),'sandstone')
    m.box((x0,f,z0),(x1,f,z1),'smooth_sandstone')


def _rail(m,x0,z0,x1,z1,f):
    for x in range(x0,x1+1):
        for z in (z0,z1):m.set(x,f+1,z,'oak_fence')
    for z in range(z0,z1+1):
        for x in (x0,x1):m.set(x,f+1,z,'oak_fence')


def _shade(m,x0,z0,x1,z1,f=1,color=None):
    y=f+5
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,f+1,z),(x,y,z),'stripped_oak_log[axis=y]')
    for z in (z0,z1):m.box((x0,y,z),(x1,y,z),'stripped_oak_log[axis=x]')
    for x in range(x0,x1+1):
        if color:m.box((x,y+1,z0),(x,y+1,z1),f'{color}_carpet')
        else:m.box((x,y+1,z0),(x,y+1,z1),'oak_slab[type=bottom]')
    if color:m.box((x0,y,z0),(x1,y,z1),'oak_slab[type=top]')
    pendant(m,x0+1,y-1,z0+1,y)


def _stairs(m,x,z,f,rise):
    for i in range(rise):
        y=f+1+i
        for xx in range(x,x+3):
            m.box((xx,0,z+i),(xx,y,z+i),'sandstone')
            m.set(xx,y,z+i,'sandstone_stairs[facing=south]')
            m.box((xx,y+1,z+i),(xx,min(m.size[1]-1,y+4),z+i),'air')
        for xx in (x-1,x+3):
            m.box((xx,0,z+i),(xx,y-1,z+i),'sandstone')
            m.set(xx,y,z+i,'oak_fence')
    m.box((x,f+rise,z+rise),(x+2,f+rise,z+rise+1),'smooth_sandstone')
    m.box((x,f+rise+1,z+rise),(x+2,f+rise+3,z+rise+1),'air')


def _bedroom(m,key,x0,z0,x1,z1,f=1,two=True,wall='smooth_sandstone'):
    _room(m,key,'独立客房 '+key,x0,z0,x1,z1,f,wall)
    for j,x in enumerate((x0+2,x1-2) if two else (x0+2,)):
        m.bed(x,f+1,z1-2,color='orange' if j else 'white',facing='north')
        approach=x+1 if j==0 else x-1
        m.point(key+f'_bed{j}','bed',(x,f+1,z1-2),'旅人客床',approach=(approach,f+1,z1-2))
    m.set(x0+2,f+1,z0+3,'oak_slab[type=top]');m.set(x0+2,f+2,z0+3,'flower_pot')
    bench(m,x0+3,f+1,z0+4,2,'north','oak')
    m.box((x1-1,f+1,z0+3),(x1-1,f+2,z0+4),'bookshelf')
    m.set(x0+1,f+1,z0+1,'barrel[facing=east]');m.set(x1-1,f+1,z0+1,'water_cauldron[level=3]')
    m.point(key+'_wash','work',(x1-1,f+1,z0+1),'客房洗漱',approach=(x1-2,f+1,z0+1))


def _hall(m,x0,z0,x1,z1,f=1,key='hall'):
    _room(m,key,'接待餐饮与后勤',x0,z0,x1,z1,f)
    m.set(x0+2,f+1,z0+2,'lectern[facing=south]')
    m.point('register','work',(x0+2,f+1,z0+2),'入住登记',approach=(x0+2,f+1,z0+3))
    for x,block in ((x0+1,'smoker[facing=north]'),(x0+3,'water_cauldron[level=3]'),(x0+5,'barrel[facing=north]')):m.set(x,f+1,z1-1,block)
    m.point('cook','work',(x0+1,f+1,z1-1),'备餐厨房',approach=(x0+1,f+1,z1-2))
    m.box((x0+1,f+2,z1-1),(x0+1,f+10,z1-1),'sandstone');m.set(x0+1,f+11,z1-1,'cobblestone_wall')
    m.set(x1-1,f+1,z1-2,'barrel[facing=west]');m.point('luggage','storage',(x1-1,f+1,z1-2),'行李寄存',approach=(x1-2,f+1,z1-2))
    _table(m,'meal',x0+3,z0+6,f)
    m.box((x1-1,f+1,z0+2),(x1-1,f+2,z0+4),'bookshelf')
    m.set(x1-1,f+1,z0+8,'crafting_table')


def _table(m,key,x,z,f):
    m.box((x,f+1,z),(x+2,f+1,z),'oak_slab[type=top]')
    bench(m,x,f+1,z+2,3,'north','oak')
    m.point(key,'work',(x+1,f+1,z),'旅人餐桌',approach=(x+1,f+1,z-1))


def inn(variant):
    if variant not in range(1,5):raise ValueError(variant)
    names=['路边露廊小客栈','双层围院旅舍','窄街楼廊客栈','阶庭错台旅栈']
    sizes=[(33,24,35),(41,25,39),(24,24,35),(39,26,40)]
    notes=['一低一高的路边客栈；前餐廊和左侧露台衬托侧边客房塔。','双层西客翼围住开放内院；后连廊通向东侧餐厅屋顶，独立上楼路线。','狭长街屋叠置接待食堂和双客房，内梯接侧廊，临街上层阳台。','低接待厅、中台客房与露天餐台、高台静养房，经两段外阶逐级连接。']
    m=Model(f'DS-08-v{variant:02d}',names[variant-1],sizes[variant-1],family='DS-08',civilization='沙漠',role='fill',terrain={
        '选址':'有可靠生活用水、餐食补给及旅人需求的绿洲街道或商路停驻点',
        '地块':notes[variant-1],'外部地面':'北侧街面脚底Y=2；台地为模型内人工支撑台基，不要求天然高坡',
        '配套':'客房床位、登记、餐食炉灶、盥洗、行李寄存均为可达原版陈设，服务机制另接入'})
    m.meta.update(source=f'tools/structure_studio/studio/desert_fill_inns.py:inn({variant})',ground_plane={'y':2,'note':'北侧外部街面为Y=1方块顶面，玩家脚底Y=2；内部楼层及台基不改变此基准'},
        roof_min_y=15,floors=[dict(name='街面接待',y=1,max_y=7),dict(name='客房与露台',y=8,max_y=14)],design_notes=[notes[variant-1]],differences=[notes[variant-1]],
        preview_context=dict(kind='flat',land_surface_y=2,bed_y=-1,padding=3,surface='sand'))
    w,h,d=m.size;_deck(m,2,2,w-3,d-3,1);m.box((2,2,2),(w-3,h-1,d-3),'air')
    if variant==1:
        _hall(m,4,11,17,29);_bedroom(m,'lower',18,15,29,29);_bedroom(m,'upper',18,15,29,29,8,wall='terracotta')
        for x,z,f,face in ((11,11,1,'north'),(17,22,1,'east'),(18,22,1,'west'),(18,22,8,'west'),(26,15,8,'north')):_door(m,x,z,f,face)
        _shade(m,4,4,16,9);_table(m,'outdoor_meal',7,6,1)
        # Hall roof is a supported terrace, with an upper-room door into it.
        _rail(m,4,11,17,29,8);m.box((17,9,21),(17,11,23),'air')
        m.box((18,8,13),(29,8,14),'smooth_sandstone')
        m.box((18,9,13),(24,9,13),'oak_fence');m.box((29,9,13),(29,9,14),'oak_fence')
        _stairs(m,25,6,1,7)
        m.point('terrace','circulation',(11,9,15),'低翼屋顶露台')
        _shade(m,5,20,11,27,8);_table(m,'roof_meal',7,22,8)
        entry=(20,2,4)
    elif variant==2:
        _hall(m,4,9,14,33);_door(m,14,15,1,'east')
        _bedroom(m,'west_front',4,9,14,20,8);_bedroom(m,'west_rear',4,22,14,33,8)
        for z in (15,27):_door(m,14,z,8,'east')
        m.box((15,8,9),(17,8,33),'smooth_sandstone');_rail(m,15,9,17,33,8)
        m.box((15,9,10),(15,11,32),'air')
        _bedroom(m,'east',28,11,37,31);_door(m,28,17,1,'west');_rail(m,28,11,37,31,8)
        _room(m,'gate','入院门厅',18,5,25,10);_door(m,21,5);_door(m,21,10)
        m.box((15,8,30),(29,8,34),'smooth_sandstone');_rail(m,15,30,29,34,8)
        m.box((16,9,31),(28,11,33),'air');m.box((15,9,30),(17,11,30),'air');m.box((28,9,26),(29,11,32),'air')
        _stairs(m,25,19,1,7)
        # Landing turns right onto the east roof then follows the rear gallery.
        m.box((25,8,26),(30,8,28),'smooth_sandstone');m.box((25,9,26),(30,11,28),'air')
        for x,z in ((16,31),(28,32)):m.box((x,2,z),(x,7,z),'cut_sandstone')
        _shade(m,18,15,23,24,1,'orange');_table(m,'court_meal',19,18,1)
        m.point('gallery','circulation',(16,9,24),'西翼客房廊');m.point('east_terrace','circulation',(33,9,25),'东翼休憩露台')
        _shade(m,30,13,36,21,8);_table(m,'terrace_meal',31,16,8)
        entry=(21,2,4)
    elif variant==3:
        _hall(m,4,7,20,30);_door(m,11,7)
        # Upper rooms are narrower than the lower hall, leaving a continuous side gallery.
        _bedroom(m,'street',4,7,14,17,8,wall='terracotta');_bedroom(m,'rear',4,19,14,30,8)
        for z in (13,24):_door(m,14,z,8,'east')
        m.box((15,8,7),(20,8,30),'smooth_sandstone');m.box((20,9,7),(20,14,30),'smooth_sandstone')
        for z in (10,17,24):m.box((20,11,z),(20,12,z+1),'oak_fence')
        m.box((15,15,7),(20,15,30),'smooth_sandstone')
        _stairs(m,17,21,1,7)
        m.box((16,9,21),(16,9,27),'oak_fence');m.box((15,9,28),(19,11,29),'air')
        m.box((4,8,4),(20,8,6),'smooth_sandstone');_rail(m,4,4,20,6,8);_door(m,10,7,8)
        m.box((9,9,6),(11,11,6),'air');_shade(m,5,4,19,6,8)
        for x in (5,19):m.box((x,2,5),(x,7,5),'stripped_oak_log[axis=y]')
        _shade(m,4,3,18,5,1,'red')
        m.point('balcony','circulation',(12,9,5),'街上遮阴阳台');m.point('upper','circulation',(15,9,20),'内梯上层侧廊')
        entry=(11,2,3)
    else:
        _hall(m,4,6,16,18);_door(m,11,6);_door(m,16,14,1,'east')
        _deck(m,18,11,35,25,4);_deck(m,4,20,16,31,4);_deck(m,16,18,23,21,4)
        _bedroom(m,'middle',24,12,35,24,4,wall='terracotta');_door(m,24,17,4,'west')
        _deck(m,13,24,26,36,8);_bedroom(m,'high',13,27,26,36,8);_door(m,19,27,8)
        _rail(m,4,20,16,31,4);m.box((16,5,20),(16,7,21),'air')
        _shade(m,5,23,15,30,4,'red');_table(m,'mid_meal',8,25,4)
        _rail(m,13,24,26,26,8);m.box((18,9,24),(22,11,26),'air')
        m.box((18,5,11),(35,5,11),'oak_fence');m.box((19,5,11),(21,7,11),'air')
        m.box((18,5,12),(18,5,17),'oak_fence');m.box((27,5,25),(35,5,25),'oak_fence')
        _stairs(m,19,8,1,3);_stairs(m,19,20,4,4)
        m.point('mid_terrace','circulation',(19,5,15),'中台客房通路');m.point('high_terrace','circulation',(20,9,25),'高台客房平台')
        _shade(m,5,3,15,5,1,'orange')
        entry=(19,2,4)
        m.meta['floors']=[dict(name='街面接待',y=1,max_y=6),dict(name='中台餐廊与客房',y=4,max_y=10),dict(name='高台客房',y=8,max_y=14)]
    m.point('front','entrance',entry,'街面主入口',facing='north')
    m.meta['connections'].append(dict(kind='pedestrian',pos=list(entry),direction='north',clearance=[3,3],note='外部街面脚底Y=2'))
    # Fence states must explicitly connect; Studio has no neighbour update ticks.
    for (x,y,z),(name,props) in list(m.blocks.items()):
        if name!='minecraft:oak_fence':continue
        connections=[]
        for face,dx,dz in [('north',0,-1),('south',0,1),('east',1,0),('west',-1,0)]:
            other=m.blocks.get((x+dx,y,z+dz),('minecraft:air',()))[0]
            joins=other=='minecraft:oak_fence' or other in {'minecraft:smooth_sandstone','minecraft:cut_sandstone','minecraft:sandstone','minecraft:terracotta','minecraft:stripped_oak_log'}
            connections.append(f'{face}={str(joins).lower()}')
        m.set(x,y,z,'oak_fence['+','.join(connections)+',waterlogged=false]')
    return m
