"""Quick reference-led Nordic fish and net workshops, eight independent plans."""
from functools import partial
from .model import Model
from .northern_seafarers import lodge, counter, stores, steps, rail


def base(family,v,size,name):
    m=Model(f'{family}-v{v:02d}',name,size,family=family,civilization='北欧',role='fill',terrain={
        '选址':'具备干燥稳定地基的寒地渔业街区；原料和饮水需外部供给',
        '地基':'外部街面/入口脚底局部Y=3；保留场坪支撑及全套晾晒、修网和装卸工作面',
        '用途边界':'加工器具与晾架为原版方块静态表达，不声明自动生产或渔获机制'})
    w,h,d=size
    m.box((1,0,1),(w-2,2,d-2),'cobblestone')
    m.box((1,2,1),(w-2,2,d-2),'gravel')
    m.box((1,3,1),(w-2,h-1,d-2),'air')
    m.meta.update(source=f'tools/structure_studio/studio/northern_fill_workshops.py:{"fish" if family=="NS-03" else "nets"}({v})',
        roof_min_y=9,floors=[dict(name='工作地坪',y=3,max_y=8)],
        ground_plane=dict(y=3,note='外部街道与场坪上边界/入口脚底Y=3；内部错台由楼梯连接'),
        preview_context=dict(kind='flat',land_surface_y=3,padding=4,surface='grass'),
        design_notes=['参考图3，以陡坡木屋、附棚、开放工作架和不同生产动线区分八款；不复制图片非原版装饰。'],
        differences=[name],function_terms=['盐鱼加工','鱼类晾晒'] if family=='NS-03' else ['织网','修网'])
    return m


def hall(m,key,x0,z0,x1,z1,f=3,height=5,open_shed=False):
    lodge(m,x0,z0,x1,z1,y=f-1,height=height,roof='spruce')
    if open_shed:
        m.box((x0,f,z0),(x1,f+height-2,z1),'air')
        for x in (x0,x1):
            for z in (z0,(z0+z1)//2,z1):
                m.box((x,f,z),(x,f+height-1,z),'dark_oak_log[axis=y]')
    else:
        m.box(((x0+x1)//2-1,f,z0),((x0+x1)//2+1,f+2,z0),'air')
    m.room(key,'遮风加工厅' if not open_shed else '开放修网跨',(x0+1,f,z0+1),(x1-1,f+height-1,z1-1),'行业设备、原料与成品之间保留通道')


def shade(m,x0,z0,x1,z1,f=3,cloth=False):
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,f,z),(x,f+4,z),'spruce_log[axis=y]')
    for z in (z0,z1):m.box((x0,f+4,z),(x1,f+4,z),'spruce_log[axis=x]')
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            m.set(x,f+5,z,('white_wool' if (x-x0)%4 else 'light_gray_wool') if cloth else 'spruce_slab[type=bottom]')
    m.set(x0+1,f+3,z0,'lantern[hanging=true]')


def frame(m,key,x,z,w=8,f=3,net=False):
    for xx in (x,x+w):m.box((xx,f,z),(xx,f+4,z),'stripped_spruce_log[axis=y]')
    m.box((x,f+4,z),(x+w,f+4,z),'stripped_spruce_log[axis=x]')
    for xx in range(x+1,x+w):
        m.set(xx,f+3,z,'chain[axis=y]')
        m.set(xx,f+2,z,'cobweb' if net else ('white_candle[candles=4,lit=false]' if xx%2 else 'orange_candle[candles=3,lit=false]'))
        if net:m.set(xx,f+1,z,'cobweb')
        else:m.set(xx,f+1,z,'spruce_slab[type=top]')
    m.point(key,'work',(x+w//2,f+2,z),'立式张网检修架' if net else '悬挂盐鱼风干架（静态形象）',approach=(x+w//2,f,z-1))


def fish_tools(m,x,z,f=3):
    counter(m,'cut','去鳞分切与盐渍台',x,z,5,f)
    m.set(x+5,f,z,'water_cauldron[level=3]')
    m.set(x,f,z+4,'smoker[facing=south,lit=true]')
    m.box((x,f+1,z+4),(x,f+14,z+4),'cobblestone')
    m.set(x,f+15,z+4,'cobblestone_slab[type=bottom]')
    m.point('smoke','work',(x,f,z+4),'熏鱼火箱',approach=(x,f,z+5))
    stores(m,'salt','盐料与包材储柜',x+4,z+5,3,f)


def net_tools(m,x,z,f=3):
    counter(m,'weave','编织与梭线工作台',x,z,5,f,'loom[facing=south]')
    m.set(x+3,f,z,'loom[facing=south]')
    counter(m,'repair','修补与裁绳台',x,z+5,5,f,'crafting_table')
    stores(m,'twine','线团、浮子与配件储柜',x,z+9,5,f)


def rest(m,x,z,f=3):
    m.box((x,f,z),(x+2,f,z),'spruce_stairs[facing=north]')
    m.set(x+4,f,z,'water_cauldron[level=3]')
    m.point('water','work',(x+4,f,z),'工人饮水歇脚',approach=(x+4,f,z-1))


def finish(m,x,z):
    m.point('front','entrance',(x,3,z),'街面装卸与人员入口',facing='north')
    m.meta['connections']=[dict(kind='pedestrian',pos=[x,3,z],direction='north',clearance=[3,3],note='与外部Y=3街面平接')]
    return m


def fish(v):
    names=['小型盐鱼作坊','围晒院加工坊','临街售鱼铺','错台风干场']
    sizes=[(33,28,35),(43,29,42),(35,29,39),(39,31,43)]
    m=base('NS-03',v,sizes[v-1],names[v-1])
    if v==1:
        hall(m,'processing',4,12,16,29)
        fish_tools(m,6,16)
        shade(m,19,14,29,29)
        frame(m,'drying',19,22,w=9)
        frame(m,'front_rack',5,7,w=10)
        counter(m,'pack','包装交货台',20,28,5)
        rest(m,21,11)
        return finish(m,17,3)
    if v==2:
        hall(m,'west',4,16,14,36)
        fish_tools(m,6,21)
        hall(m,'rear',18,27,36,36)
        counter(m,'pack','成品分级包装',21,30,8)
        stores(m,'finished','盐鱼成品储柜',21,34,8)
        for i,z in enumerate((10,17,23)):frame(m,f'dry_{i}',18,z,w=15)
        rail(m,3,5,15,5,3);rail(m,26,5,38,5,3)
        rest(m,31,38)
        return finish(m,21,3)
    if v==3:
        hall(m,'shop',4,8,22,17,height=4)
        hall(m,'work',8,20,22,34,height=6)
        fish_tools(m,10,23)
        shade(m,4,3,22,6,cloth=True)
        counter(m,'sales','临街售鱼与称量',6,11,6)
        stores(m,'finished','成品与收银柜',15,13,4)
        for i,z in enumerate((17,26)):frame(m,f'dry_{i}',25,z,w=6)
        rest(m,25,33)
        return finish(m,15,2)
    m.box((3,3,24),(35,5,39),'cobblestone')
    m.box((3,5,24),(35,5,39),'gravel')
    hall(m,'processing',8,25,22,37,f=6)
    fish_tools(m,10,28,f=6)
    steps(m,18,21,5,3,y=3)
    for i,z in enumerate((9,16)):frame(m,f'west_{i}',4,z,w=10)
    for i,z in enumerate((10,18)):frame(m,f'east_{i}',26,z,w=8)
    frame(m,'upper_rack',25,33,w=8,f=6)
    rail(m,3,24,12,24,6);rail(m,24,24,35,24,6)
    rail(m,3,24,3,39,6);rail(m,35,24,35,39,6)
    rest(m,5,20)
    m.point('upper','circulation',(20,6,24),'错台装卸上口')
    m.meta['floors']=[dict(name='下台晾晒',y=3,max_y=7),dict(name='上台加工',y=6,max_y=11)]
    return finish(m,20,3)


def nets(v):
    names=['狭长织网坊','双跨开放修网棚','前店后坊网具铺','L形修网院']
    sizes=[(33,28,39),(35,28,39),(37,29,39),(41,29,41)]
    m=base('NS-07',v,sizes[v-1],names[v-1])
    if v==1:
        hall(m,'long_hall',4,7,15,33)
        net_tools(m,6,13)
        for i,z in enumerate((12,23)):frame(m,f'net_{i}',19,z,w=9,net=True)
        stores(m,'finished','卷网装箱位',21,30,6)
        rest(m,5,30)
        return finish(m,17,3)
    if v==2:
        hall(m,'west',4,10,14,31,open_shed=True)
        hall(m,'east',19,10,29,31,open_shed=True)
        net_tools(m,6,14)
        for i,z in enumerate((15,25)):frame(m,f'net_{i}',20,z,w=8,net=True)
        stores(m,'finished','已修渔网与浮子',20,33,6)
        rest(m,5,34)
        rail(m,3,5,13,5,3);rail(m,23,5,31,5,3)
        return finish(m,17,3)
    if v==3:
        hall(m,'shop',4,7,20,16,height=4)
        hall(m,'rear_work',8,19,24,34,height=7)
        net_tools(m,10,22)
        shade(m,4,3,20,5,cloth=True)
        counter(m,'sales','网具零售与接修登记',6,11,6)
        stores(m,'goods','绳具成品陈列',15,13,4)
        frame(m,'net',27,25,w=6,net=True)
        rest(m,28,33)
        return finish(m,13,2)
    hall(m,'long_wing',4,7,15,34)
    net_tools(m,6,12)
    hall(m,'rear_wing',18,27,34,35,height=5)
    counter(m,'parts','配件组装与交付台',21,30,7)
    frame(m,'back_net',19,22,w=15,net=True)
    frame(m,'front_net',21,11,w=13,net=True)
    counter(m,'repair_out','露院大网修补桌',22,17,7)
    rail(m,3,5,15,5,3);rail(m,26,5,36,5,3)
    rest(m,7,30)
    return finish(m,20,3)


BUILDERS={**{f'NS-03-v{i:02d}':partial(fish,i) for i in range(1,5)},
          **{f'NS-07-v{i:02d}':partial(nets,i) for i in range(1,5)}}
