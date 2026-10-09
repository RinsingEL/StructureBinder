"""Court-forming academy buildings and authored connector parts (1.20.1 NBT).

Ports are author annotations, not a claim that the city planner joins them yet.
Sparse foundations follow the buildings and paths, not their bounding rectangles.
"""
from .model import Model
from .arcane_reborn_parts import gable, arch, tower, straight_stair, work, bed

CATALOG_DIR = 'R02_arcane_reborn'
WALL, TRIM = 'calcite', 'polished_andesite'


def model(number, name, size, terms, *, connector=False, floors=(1, 11)):
    m = Model(f'MG-{number:02d}-v01', name, size, family=f'MG-{number:02d}',
              civilization='魔法学院新制', role='structure' if connector else 'fill')
    m.meta.update(source='tools/structure_studio/studio/academy_wings.py:BUILDERS',
        function_terms=list(terms), asset_tags=['infrastructure'] if connector else [],
        campus_consumer='infrastructure' if connector else 'array',
        ground_plane={'y': 2, 'note': '街面脚底 Y=2；教学组团二层接口脚底 Y=12。'},
        terrain={'选址': '核心台地的稳定承载面；连接件由连接系统确定位置与支撑。'},
        preview_context={'kind': 'flat', 'land_surface_y': 2, 'padding': 3, 'surface': 'grass'},
        floors=[{'name': f'第{i+1}层', 'y': y, 'max_y': y+8} for i,y in enumerate(floors)],
        roof_min_y=23, design_revision=1,
        design_notes=['补足大核心周围的多层翼楼、折角与围院，基础只覆盖实际楼体及通路。',
            '连接端口为作者标记，尚未接入自动拼装；以局部坐标、方向、脚底高度和净空配对。',
            '紫晶、仪器与符文均为原版静态方块，不声明魔法功能已经运行。'])
    return m


def path(m, x0, z0, x1, z1, y=1):
    m.box((x0, 0, z0), (x1, 0, z1), 'stone_bricks')
    m.box((x0, y, z0), (x1, y, z1), 'polished_andesite')


def mass(m, rectangles, *, floors=(1,11), top=22):
    """Union before drawing walls: overlapping wings have no sealed internal seams."""
    cells={(x,z) for x0,z0,x1,z1 in rectangles for x in range(x0,x1+1) for z in range(z0,z1+1)}
    boundary={(x,z) for x,z in cells if any((x+dx,z+dz) not in cells for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)))}
    for x,z in cells:
        m.box((x,0,z),(x,1,z),'stone_bricks')
        m.box((x,2,z),(x,top,z),WALL if (x,z) in boundary else 'air')
        for y in floors:
            m.set(x,y,z,TRIM if (x,z) in boundary else 'dark_oak_planks')
        if (x,z) in boundary:
            for y in (10,21,top):
                if y<=top:m.set(x,y,z,'smooth_stone')
    # Narrow lancets on straight exterior walls; leave enough stone at corners.
    for x,z in sorted(boundary):
        for axis,normal in (('x',(0,-1)),('x',(0,1)),('z',(-1,0)),('z',(1,0))):
            along=(1,0) if axis=='x' else (0,1)
            if (x+normal[0],z+normal[1]) in cells:continue
            coordinate=x if axis=='x' else z
            if coordinate%7!=0:continue
            if not all((x+along[0]*i,z+along[1]*i) in boundary for i in range(-2,3)):continue
            for y in floors:
                arch(m,x if axis=='x' else z,z if axis=='x' else x,y+2,3,6,axis=axis)
    return cells


def ribs(m, x0,z0,x1,z1, *, axis='x'):
    # Shallow buttresses and purple banners give the long walls rhythm.
    if axis=='x':
        for x in range(x0+3,x1-2,7):
            for z in (z0-1,z1+1):
                m.box((x,2,z),(x,19,z),TRIM)
                m.set(x,20,z,'stone_brick_slab')
                m.box((x,15,z),(x,17,z),'purple_terracotta')
    else:
        for z in range(z0+3,z1-2,7):
            for x in (x0-1,x1+1):
                m.box((x,2,z),(x,19,z),TRIM)
                m.set(x,20,z,'stone_brick_slab')
                m.box((x,15,z),(x,17,z),'purple_terracotta')


def opening(m, c, plane, *, axis='x', floor=1):
    arch(m,c,plane,floor+1,5,6,axis=axis,glass=False)


def port(m, key, *, direction, center, wall, upper=True):
    """Five-wide supported threshold, central 3x4 clear, from wall to tile edge."""
    w,_,d=m.size
    floors=(1,11) if upper else (1,)
    for y in floors:
        if direction in ('west','east'):
            edge=0 if direction=='west' else w-1
            lo,hi=sorted((edge,wall))
            m.box((lo,y,center-2),(hi,y,center+2),'polished_andesite')
            m.box((lo,y+1,center-1),(hi,y+4,center+1),'air')
            opening(m,center,wall,axis='z',floor=y)
            p=(edge,y+1,center)
            approach=(edge+1 if direction=='west' else edge-1,y+1,center)
        else:
            edge=0 if direction=='north' else d-1
            lo,hi=sorted((edge,wall))
            m.box((center-2,y,lo),(center+2,y,hi),'polished_andesite')
            m.box((center-1,y+1,lo),(center+1,y+4,hi),'air')
            opening(m,center,wall,floor=y)
            p=(center,y+1,edge)
            approach=(center,y+1,edge+1 if direction=='north' else edge-1)
        if y==1:
            if direction in ('west','east'):m.box((lo,0,center-2),(hi,0,center+2),'stone_bricks')
            else:m.box((center-2,0,lo),(center+2,0,hi),'stone_bricks')
        else:
            # Leave the centre 3-wide lane open and guard both exposed edges.
            if direction in ('west','east'):
                for zz in (center-2,center+2):
                    for xx in range(lo,hi+1):
                        if xx!=wall:m.set(xx,y+1,zz,'iron_bars[east=true,west=true]')
            else:
                for xx in (center-2,center+2):
                    for zz in range(lo,hi+1):
                        if zz!=wall:m.set(xx,y+1,zz,'iron_bars[north=true,south=true]')
        ident=f'{key}-'+('upper' if y>1 else 'ground')
        m.point(ident,'circulation',p,'连接廊端口',approach=approach,direction=direction)
        m.meta['connections'].append({'id':ident,'point_id':ident,'kind':'academy_gallery',
            'pos':list(p),'direction':direction,'standing_y':y+1,'width':3,'height':4,
            'note':'中心线处 3 格宽、4 格高净空；原点为边缘地板上方脚底；需由程序旋转对接。'})


def front(m, x, wall_z):
    path(m,x-2,0,x+2,wall_z)
    m.box((x-1,2,0),(x+1,6,wall_z),'air')
    opening(m,x,wall_z)
    m.point('front','entrance',(x,2,0),'临街主入口',approach=(x,2,2),direction='north')


def stairs(m, key, x,z, *, start=1,end=11,facing='south',width=3):
    straight_stair(m,x,z,start,end,width,facing=facing)
    dx,dz={'south':(0,1),'north':(0,-1),'east':(1,0),'west':(-1,0)}[facing]
    # A flat upper landing makes the functional point's standing height exact.
    xx=x+dx*(end-start);zz=z+dz*(end-start)
    m.box((xx,end,zz),(xx+(width-1 if dx==0 else 0),end,zz+(width-1 if dz==0 else 0)),'dark_oak_planks')
    m.box((xx,end+1,zz),(xx+(width-1 if dx==0 else 0),end+3,zz+(width-1 if dz==0 else 0)),'air')
    m.point(key,'circulation',(xx+(width//2 if dx==0 else 0),end+1,zz+(width//2 if dz==0 else 0)),'楼梯上层平台')


def classroom(m, key, a,b, y, *, library=False):
    x0,z0=a;x1,z1=b
    m.room(f'{key}-{y}',('研读室' if library else '课堂'),(x0,y+1,z0),(x1,y+7,z1),
           '阅读与小组研究' if library else '课堂教学与研讨')
    m.box((x0,y+1,z1),(x1,y+3,z1),'bookshelf')
    if library:
        for x in (x0,x1):m.box((x,y+1,z0+2),(x,y+3,z1-2),'bookshelf')
    for x in range(x0+2,x1-1,5):
        for z in range(z0+3,z1-2,5):
            m.set(x,y+1,z,'oak_fence');m.set(x,y+2,z,'dark_oak_pressure_plate')
            m.set(x,y+1,z-1,'dark_oak_stairs[facing=south]')
    work(m,f'{key}-desk-{y}',(x0+x1)//2,y+1,z1-2,'教学与研读讲台')


def garden(m, x0,z0,x1,z1):
    path(m,x0,z0,x1,z1)
    inset=1 if x1-x0<10 else 2
    for x,z in ((x0+inset,z0+2),(x1-inset,z0+2),(x0+inset,z1-2),(x1-inset,z1-2)):
        m.box((x-1,1,z-1),(x+1,1,z+1),'moss_block')
        m.set(x,2,z,'flowering_azalea')
    cx=(x0+x1)//2;cz=(z0+z1)//2
    m.box((cx-1,1,cz-1),(cx+1,1,cz+1),'polished_blackstone_bricks')
    m.set(cx,2,cz,'amethyst_block');m.set(cx,3,cz,'amethyst_cluster')
    for z in (z0+1,z1-1):
        for x in range(cx-2,cx+3):m.set(x,2,z,'dark_oak_stairs[facing='+('south' if z==z0+1 else 'north')+']')


def teaching_wing():
    m=model(34,'双层教学翼楼 · 十字陡顶与端塔',(47,49,29),['教学','研讨','阅读'])
    mass(m,[(4,7,42,23)])
    gable(m,4,7,42,23,23,axis='x');ribs(m,4,7,42,23)
    # Cross gable and compact entrance tower break the long roof into volumes.
    gable(m,19,4,27,24,25,axis='z')
    tower(m,8,7,3,30,roof_height=12)
    for y in (1,11):
        m.box((6,y,5),(10,y,9),'dark_oak_planks')
        opening(m,8,10,floor=y)
    front(m,23,7)
    stairs(m,'upper-landing',6,12)
    for y in (1,11):
        classroom(m,'west',(13,9),(21,21),y)
        classroom(m,'east',(28,9),(39,21),y)
    port(m,'west',direction='west',center=16,wall=4)
    port(m,'east',direction='east',center=16,wall=42)
    return m


def corner_wing():
    m=model(35,'折角研讨楼 · L 形两翼与转角尖塔',(43,51,43),['研讨','研究','阅读'])
    mass(m,[(5,7,35,21),(21,7,35,37)])
    gable(m,5,7,35,21,23,axis='x');gable(m,21,7,35,37,23,axis='z')
    ribs(m,5,7,20,21);ribs(m,21,22,35,37,axis='z')
    tower(m,32,10,3,33,roof_height=12)
    for y in (1,11):
        m.box((30,y,8),(34,y,12),'dark_oak_planks');opening(m,32,13,floor=y)
    front(m,14,7);stairs(m,'upper-landing',23,23)
    for y in (1,11):
        classroom(m,'seminar',(7,9),(19,19),y,library=True)
        classroom(m,'research',(28,23),(33,35),y)
    garden(m,6,24,18,36)
    path(m,12,21,16,24)
    opening(m,14,21)
    port(m,'west',direction='west',center=14,wall=5)
    port(m,'south',direction='south',center=28,wall=37)
    return m


def teaching_court():
    m=model(36,'三翼教学庭院 · U 形围院与双门塔',(57,51,49),['教学','研讨','研究','庭院休憩'])
    mass(m,[(5,8,19,42),(37,8,51,42),(5,28,51,42)])
    gable(m,5,8,19,42,23,axis='z');gable(m,37,8,51,42,23,axis='z')
    gable(m,5,28,51,42,24,axis='x')
    ribs(m,5,8,19,25,axis='z');ribs(m,37,8,51,25,axis='z')
    for cx in (12,44):
        tower(m,cx,10,3,33,roof_height=12)
        for y in (1,11):
            m.box((cx-2,y,8),(cx+2,y,12),'dark_oak_planks');opening(m,cx,13,floor=y)
    garden(m,20,9,36,26)
    path(m,26,0,30,29)
    # Arcaded inner faces frame the court; clear doors reach both teaching wings.
    for z in (17,24):
        opening(m,z,19,axis='z');opening(m,z,37,axis='z')
    front(m,28,28)
    stairs(m,'upper-landing',8,29)
    for y in (1,11):
        classroom(m,'west',(7,15),(16,26),y)
        classroom(m,'east',(40,15),(49,26),y)
        classroom(m,'rear',(20,31),(35,40),y,library=True)
    port(m,'west',direction='west',center=21,wall=5)
    port(m,'east',direction='east',center=21,wall=51)
    return m


def dorm_court():
    m=model(37,'双翼宿舍院 · 两层床位与共享书廊',(45,43,39),['学生住宿','自习','庭院休憩'])
    mass(m,[(5,7,17,33),(27,7,39,33),(5,23,39,33)],top=21)
    gable(m,5,7,17,33,22,axis='z');gable(m,27,7,39,33,22,axis='z')
    gable(m,5,23,39,33,22,axis='x')
    ribs(m,5,7,17,21,axis='z');ribs(m,27,7,39,21,axis='z')
    garden(m,18,8,26,21)
    path(m,20,0,24,24);front(m,22,23)
    opening(m,15,17,axis='z');opening(m,15,27,axis='z')
    stairs(m,'upper-landing',7,20)
    for y in (1,11):
        for side,x in (('west',8),('east',30)):
            for i,z in enumerate((12,17)):
                bed(m,f'{side}-bed-{y}-{i}',x,y+1,z)
                bed(m,f'{side}-bed-{y}-{i}-b',x+6,y+1,z)
            m.room(f'{side}-{y}','学生寝室',(x-1,y+1,9),(x+7,y+7,21),'床位、储物与自习')
        work(m,f'reading-{y}',24,y+1,30,'共享书廊研读位',side='north')
        m.box((19,y+1,32),(25,y+3,32),'bookshelf')
    port(m,'west',direction='west',center=14,wall=5)
    port(m,'east',direction='east',center=14,wall=39)
    m.meta['roof_min_y']=22
    return m


def research_library():
    m=model(38,'阶顶研究书楼 · 三层书室与观景露台',(31,49,31),['阅读','研究','研讨'],floors=(1,11,21))
    mass(m,[(5,6,25,25)],floors=(1,11),top=20)
    # The third storey steps back instead of repeating the footprint.
    m.box((5,21,6),(25,21,25),'dark_oak_planks')
    mass(m,[(9,10,21,22)],floors=(1,11,21),top=30)
    # Reopen the nested body's ground and second-storey seam into the lower hall.
    for y in (1,11):
        for x,z,axis in ((15,10,'x'),(15,22,'x'),(16,9,'z'),(16,21,'z')):
            opening(m,x,z,axis=axis,floor=y)
    gable(m,9,10,21,22,31,axis='z')
    # Low terrace parapet, with crenels around the set-back roof.
    for x in range(5,26):
        for z in (6,25):
            m.set(x,22,z,'polished_blackstone_bricks')
            if x%3==0:m.set(x,23,z,TRIM)
    for z in range(7,25):
        for x in (5,25):
            m.set(x,22,z,'polished_blackstone_bricks')
            if z%3==0:m.set(x,23,z,TRIM)
    tower(m,24,8,2,33,roof_height=10)
    front(m,15,6)
    stairs(m,'second-landing',6,10)
    opening(m,11,10,floor=11)
    stairs(m,'third-landing',10,11,start=11,end=21)
    opening(m,16,21,axis='z',floor=21)
    for y in (1,11):
        work(m,f'catalogue-{y}',23,y+1,23,'书目查询与研究桌',side='north')
        m.box((23,y+1,12),(23,y+4,20),'bookshelf')
        m.room(f'lower-{y}','藏书研读大厅',(6,y+1,7),(24,y+7,24),'阅读、书目查询与研究')
    work(m,'research-21',18,22,18,'上层研究讲台',side='east')
    m.room('upper','上层研究书室',(10,22,11),(20,28,21),'研究与研讨')
    m.point('terrace','circulation',(23,22,16),'高层观景露台')
    port(m,'west',direction='west',center=23,wall=5)
    port(m,'east',direction='east',center=16,wall=25)
    m.meta['roof_min_y']=31
    return m


def gallery(*, upper=False):
    number=40 if upper else 39;y=11 if upper else 1
    m=model(number,'高架教学连廊 · 双层通路与尖拱支墩' if upper else '地面尖拱连廊 · 五开间连接件',
        (11,35 if upper else 25,27),['楼间连接','步行通行'],connector=True,floors=(1,11) if upper else (1,))
    path(m,3,0,7,26)
    if upper:m.box((2,11,0),(8,11,26),'dark_oak_planks')
    for z in (2,7,12,17,22,24):
        for x in (2,8):
            m.box((x,2,z),(x,y+9,z),TRIM)
            m.set(x,y+10,z,'smooth_stone')
    # Side arcades are true openings under a supported longitudinal beam.
    for x in (2,8):
        m.box((x,y+7,2),(x,y+9,24),WALL)
        for z in (4,9,14,19,23):arch(m,z,x,y+1,3,6,axis='z',glass=False)
        if upper:
            m.box((x,8,2),(x,10,24),WALL)
            for z in (4,9,14,19,23):arch(m,z,x,2,3,6,axis='z',glass=False)
    gable(m,2,2,8,24,y+10,axis='z')
    if upper:
        for x in (2,8):
            for z in range(27):
                if z not in (2,7,12,17,22,24):m.set(x,12,z,'iron_bars[north=true,south=true]')
        stairs(m,'upper-landing',3,3,width=2)
    # Open, edge-aligned thresholds; no end wall or glass seals the gallery.
    for yy in ((1,11) if upper else (1,)):
        for key,z,direction in (('north',0,'north'),('south',26,'south')):
            m.box((4,yy+1,z),(6,yy+4,z),'air')
            m.point(f'{key}-{yy}','circulation',(5,yy+1,z),'连廊接口',direction=direction)
            m.meta['connections'].append({'id':f'{key}-{yy}','point_id':f'{key}-{yy}',
                'kind':'academy_gallery','pos':[5,yy+1,z],'direction':direction,
                'standing_y':yy+1,'width':3,'height':4,
                'note':'端点脚底与翼楼同层端口对齐；高架支墩落地适配由程序负责。'})
    m.point('front','entrance',(5,2,0),'连接件地面入口',approach=(5,2,1),direction='north')
    m.meta['roof_min_y']=y+10
    m.meta['design_notes'].append('独立预览可从地面走通；连接件不能作为学院阵列建筑成员。')
    return m


BUILDERS={
    'MG-34-v01':teaching_wing,
    'MG-35-v01':corner_wing,
    'MG-36-v01':teaching_court,
    'MG-37-v01':dorm_court,
    'MG-38-v01':research_library,
    'MG-39-v01':gallery,
    'MG-40-v01':lambda:gallery(upper=True),
}
