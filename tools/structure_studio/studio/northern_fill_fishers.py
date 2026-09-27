"""Reference-driven fisher homes, retaining proven domestic furniture layouts."""
from functools import partial
from .northern_life import (fisher_split,fisher_duplex,fisher_sidework,fisher_upper,fisher_court,partition,zone)
from .northern_seafarers import base,lodge,entry,cook,dining,sleep,counter,stores,use,steps,rail


def netrack(m,key,x,z,width=5,y=3):
    for xx in (x,x+width):m.box((xx,y,z),(xx,y+4,z),'stripped_spruce_log[axis=y]')
    m.box((x,y+4,z),(x+width,y+4,z),'spruce_log[axis=x]')
    for xx in range(x+1,x+width):m.box((xx,y+2,z),(xx,y+3,z),'iron_bars[east=true,west=true,north=false,south=false]')
    m.point(key,'work',(x+2,y+2,z),'晾晒补网架',approach=(x+2,y,z+1))


def first():
    m=base('NS-06-v01','前棚后居渔户宅',27,37,28,role='fill')
    lodge(m,5,16,23,32)
    entry(m,'front',14,16)
    partition(m,6,25,22,25,14,25)
    cook(m,'cook',6,18);dining(m,14,21,5)
    sleep(m,'beds',8,29,3);stores(m,'linen','家庭干衣与被褥',17,31,5)
    m.meta['points'][-1]['approach']=[17,3,30]
    zone(m,'living','后屋炊食厅',6,17,22,24,'厨房、洗涤与共用餐桌')
    zone(m,'sleep','后部干燥寝间',6,26,22,31,'三床与独立干衣柜')
    # Lower open work shed in front, with a solid gable and real roof-bearing posts.
    lodge(m,3,4,17,12,height=3,roof='deepslate_tile')
    for z in (4,12):m.box((4,3,z),(16,5,z),'air')
    for x in (3,17):m.box((x,3,5),(x,5,11),'air')
    for x in (3,10,17):
        for z in (4,12):m.box((x,3,z),(x,6,z),'dark_oak_log[axis=y]')
    counter(m,'repair','棚内修网台',5,8,5,block='loom[facing=south]')
    stores(m,'gear','渔具与备用绳索',12,10,4)
    netrack(m,'dry',5,4,6)
    zone(m,'work','前部开敞修网棚',4,5,16,11,'修网、挂网、工具存放，生活位于后部完整木屋')
    m.point('street','entrance',(21,3,3),'陆侧院口',facing='north')
    return m


def fisher(variant):
    fns=[first,fisher_split,fisher_duplex,fisher_sidework,fisher_upper,fisher_court]
    m=fns[variant-1]()
    notes=['低矮开放网棚在前，较高完整住宅在后；湿具作业不占家庭寝食。','低岸修网棚与后抬高住宅错层，中间石阶连接，家居避开低岸湿作业。','两户独立长屋夹晒网院；每户单独炊食寝间，网具共晒。','纵向家居折接横向侧网房，前侧空院另设晾网架。','下层石基湿作业，上层木住宅；内外双梯、岸向阳台及柱撑。','三栋松散分布：家居、修网、干网库围绕露天晾晒院。']
    if variant==2:
        # Remove the low workshop's enclosure, leaving eaves and four solid posts.
        for z in (3,12):m.box((4,3,z),(20,5,z),'air')
        for x in (3,21):m.box((x,3,4),(x,5,11),'air')
        for x in (3,21):
            for z in (3,12):m.box((x,3,z),(x,7,z),'dark_oak_log[axis=y]')
        netrack(m,'dry',5,3,5)
        for x in (10,14):m.box((x,3,15),(x,4,16),'cobblestone_wall')
    elif variant==3:
        netrack(m,'dry_front',14,6,4)
        # Open inward doors, retaining the original northern entries as alternatives.
        m.door(13,3,8,facing='east');m.door(19,3,8,facing='west')
    elif variant==4:
        netrack(m,'dry',18,6,6)
        m.door(21,3,13,facing='north')
    elif variant==5:
        # Extend the authored platform only on the east, to carry the outside stair and balcony.
        m.size=(29,27,29);m.meta['size']=list(m.size)
        m.box((18,0,1),(26,2,25),'cobblestone');m.box((18,3,1),(26,23,25),'air')
        # Lower perimeter becomes a robust stone working storey.
        for p,(name,props) in list(m.blocks.items()):
            x,y,z=p
            if 3<=y<=7 and (x in (3,17) or z in (3,23)) and name in ('minecraft:spruce_planks','minecraft:dark_oak_log'):
                m.set(x,y,z,'stone_bricks')
        m.box((18,8,10),(25,8,22),'spruce_planks');m.box((18,8,9),(25,8,10),'spruce_planks')
        for x,z in ((19,11),(25,11),(19,22),(25,22)):m.box((x,3,z),(x,7,z),'dark_oak_log[axis=y]')
        rail(m,25,10,25,22,9);rail(m,18,22,25,22,9)
        m.door(17,9,10,facing='east');steps(m,21,3,3,6)
        m.box((21,8,9),(23,8,10),'spruce_planks')
        for i in range(6):
            for x in (20,24):m.box((x,3,3+i),(x,3+i,3+i),'cobblestone_wall')
        m.point('balcony','circulation',(22,9,15),'上层岸边阳台')
        netrack(m,'dry',4,2,5)
        m.meta['points'][-1]['approach']=[6,3,1]
    elif variant==6:
        # Preserve the three complete rooms, and emphasize the shared drying court.
        netrack(m,'dry_front',16,8,7)
    m.meta.update(source=f'tools/structure_studio/studio/northern_fill_fishers.py:fisher({variant})',
        ground_plane={'y':3,'note':'北侧外部岸台为本地Y=2方块顶面，站立脚底Y=3；内部抬高台地及上层另算'},
        function_terms=['住宅','渔具修缮'],design_notes=[notes[variant-1]],differences=[notes[variant-1]])
    m.meta['planning_role']='planning_role.fill'
    m.meta['preview_context']=dict(kind='shore',land_surface_y=3,shore_z=m.size[2]-2,water_surface_y=1,bed_y=-2,padding=4,surface='snow')
    m.meta['terrain']['岸线']='海面在 +Z，展示水面Y=1，低于陆侧站立面Y=3；生产点需实际渔业水域，水面不作为落地基准。'
    # Small roof material changes follow the same roof geometry, no unsupported snow carpets.
    for (x,y,z),(name,props) in list(m.blocks.items()):
        if name=='minecraft:spruce_stairs' and y>=8 and (x+z)%7==0:
            state=','.join(f'{k}={v}' for k,v in props)
            m.set(x,y,z,'deepslate_tile_stairs['+state+']')
    return m


BUILDERS={f'NS-06-v{i:02d}':partial(fisher,i) for i in range(1,7)}
