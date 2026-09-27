"""Six reference-designed oasis farms, with irrigated crops and dry access."""
from .model import Model


PLANS = [
    ('长渠窄条田', (21, 13, 39), '单条长渠两侧六畦，横跨小桥及两侧连续采收道。'),
    ('十字分水田', (33, 13, 33), '十字分水渠将大田分为四片，桥梁衔接四周环路与中央作业道。'),
    ('内院菜畦', (29, 13, 29), '围墙庭园、四块短畦、中央取水庭与后侧小型工具凉棚。'),
    ('L形巷边田', (33, 13, 33), '沿折角街巷展开的L形种植带，凹角不铺地，渠边保持干燥搬运道。'),
    ('分拣棚生产田', (35, 13, 35), '大片生产田连接后部通透分拣棚，收获、称量、装筐与堆肥分开。'),
    ('三级等高渠田', (31, 15, 41), '三道等高种植台、侧边跌水渠与连续中央短阶，逐级向后抬升。'),
]


def ground(m, cells, height):
    for x, z in cells:
        f = height(z)
        m.box((x, 0, z), (x, f, z), 'sandstone')
        m.box((x, f+1, z), (x, m.size[1]-1, z), 'air')
        m.set(x, f, z, ('coarse_dirt' if (x*7+z*11)%13<3 else 'smooth_sandstone') if x%3 else 'cut_sandstone')


def bed(m, key, x0, z0, x1, z1, f, crop='wheat', axis='z'):
    """Raised coping encloses soil; the central water row hydrates every cell."""
    for x in range(x0, x1+1):
        for z in range(z0, z1+1):
            edge = x in (x0, x1) or z in (z0, z1)
            if edge:
                m.set(x, f, z, 'cut_sandstone')
                m.set(x, f+1, z, 'smooth_sandstone_slab[type=bottom]')
            elif (x == (x0+x1)//2 if axis == 'z' else z == (z0+z1)//2):
                m.set(x, f, z, 'water[level=0]')
            else:
                m.set(x, f, z, 'farmland[moisture=7]')
                age = 3 if crop == 'beetroots' else 7
                m.set(x, f+1, z, f'{crop}[age={age}]')
    m.room(key, '灌溉种植畦 '+key, (x0, f+1, z0), (x1, f+3, z1), '水渠、耕土及沿畦采收')


def lamp(m, x, z, f=1):
    m.set(x, f+1, z, 'cut_sandstone')
    m.set(x, f+2, z, 'spruce_fence')
    m.set(x, f+3, z, 'lantern')


def shed(m, x0, z0, x1, z1, f=1):
    for x in sorted({x0, x1, *range(x0+5,x1,5)}):
        for z in (z0, z1):
            m.box((x, f+1, z), (x, f+4, z), 'stripped_spruce_log[axis=y]')
    m.box((x0, f+5, z0), (x1, f+5, z1), 'spruce_slab[type=bottom]')
    for x in range(x0, x1+1, 2):
        m.box((x, f+5, z0), (x, f+5, z1), 'spruce_trapdoor[half=bottom,open=false,facing=north]')
    for x in range(x0+1, x1):
        m.set(x, f+1, z1, 'barrel[facing=north]' if (x-x0)%5==1 else 'crafting_table' if (x-x0)%5==2 else 'spruce_slab[type=top]')
    m.set(x0+1, f+1, z0+1, 'composter[level=5]')
    m.set(x1-1, f+1, z0+1, 'chest[facing=north]')
    m.set((x0+x1)//2, f+4, z1-1, 'lantern[hanging=true]')
    m.point('sorting', 'work', ((x0+x1)//2, f+1, z1), '分拣装筐台', approach=((x0+x1)//2, f+1, z1-1))
    m.room('shed', '田头工具分拣棚', (x0, f+1, z0), (x1, f+4, z1), '遮阳、工具、收获物分拣与装筐')


def headwater(m, x, z, f=1):
    m.box((x-2, f+1, z), (x+2, f+3, z+2), 'cut_sandstone')
    m.box((x-1, f+1, z), (x+1, f+2, z+1), 'air')
    m.set(x, f, z+1, 'water[level=0]')
    m.set(x-2, f+4, z+1, 'smooth_sandstone_slab[type=bottom]')
    m.set(x+2, f+4, z+1, 'smooth_sandstone_slab[type=bottom]')


def garden(variant):
    name, size, desc = PLANS[variant-1]
    w, _, d = size
    m = Model(f'DS-F02-v{variant:02d}', name, size, family='DS-F02', civilization='沙漠', role='fill', terrain={
        '选址':'有可靠灌溉供水、适宜土壤及日照的地块；模板水槽须有真实供水来源。',
        '地块':desc, '落地':'外部入口脚底 Y=2；耕地及地坪顶层方块 Y=1。三级台田内部地坪另升至 Y=2、3。',
        '适用':'独立固定田块，不替代随自然地形生成的大田。'})
    m.meta.update(source=f'tools/structure_studio/studio/desert_fill_farms.py:garden({variant})',
        design_notes=[desc, '参考用户图7大轮廓与水渠组织，使用原版作物；保留作业净空。'], differences=[desc],
        ground_plane={'y':2,'note':'外部接路站立面 Y=2；地坪顶层方块 Y=1，内部种植台和短阶不改变对地基准。'},
        floors=[dict(name='灌溉与作业层',y=0,max_y=7)],roof_min_y=6,
        preview_context=dict(kind='flat',land_surface_y=2,bed_y=-1,padding=3,surface='sand'))
    cells={(x,z) for x in range(1,w-1) for z in range(1,d-1)}
    if variant==4: cells={(x,z) for x,z in cells if x<=13 or z>=19}
    level = lambda z: 1+(z>=14)+(z>=26) if variant==6 else 1
    ground(m,cells,level)
    edge={(x,z) for x,z in cells if any((x+dx,z+dz) not in cells for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)))}
    gatex=10 if variant==1 else 7 if variant==4 else w//2
    for x,z in edge:
        if z==1 and abs(x-gatex)<=1:continue
        f=level(z)
        m.set(x,f+1,z,'sandstone')
        m.set(x,f+2,z,'smooth_sandstone_slab[type=bottom]')
    crops=('wheat','carrots','potatoes','beetroots')
    if variant==1:
        for i,z in enumerate((4,14,24)):
            for side,(a,b) in enumerate(((3,8),(12,17))):bed(m,f'b{i}_{side}',a,z,b,z+7,1,crops[(i+side)%4])
        m.box((10,1,3),(10,1,34),'water[level=0]')
        for z in (3,12,22,33):m.box((9,1,z),(11,1,z),'spruce_planks')
        headwater(m,10,35)
        m.set(3,2,34,'composter[level=5]');lamp(m,17,35)
        route=[(3,2,12),(17,2,22),(9,2,34)]
    elif variant==2:
        for i,(x,z) in enumerate(((3,4),(19,4),(3,19),(19,19))):bed(m,f'b{i}',x,z,x+10,z+9,1,crops[i])
        m.box((15,1,3),(16,1,28),'water[level=0]');m.box((3,1,15),(29,1,16),'water[level=0]')
        for z in (3,14,17,29):m.box((14,1,z),(17,1,z),'spruce_planks')
        for x in (2,14,17,30):m.box((x,1,14),(x,1,17),'spruce_planks')
        headwater(m,16,29);route=[(2,2,14),(30,2,17),(14,2,28)]
        for x,z in ((2,2),(30,2),(2,30)):lamp(m,x,z)
    elif variant==3:
        for i,(x,z) in enumerate(((3,5),(17,5),(3,15),(17,15))):bed(m,f'b{i}',x,z,x+8,z+6,1,crops[i])
        for x,z in edge:
            if z!=1:m.set(x,3,z,'sandstone')
        m.box((12,1,12),(16,1,14),'cut_sandstone');m.box((13,1,12),(15,1,14),'water[level=0]')
        shed(m,4,23,11,26);headwater(m,20,24)
        lamp(m,3,3);lamp(m,25,3);route=[(12,2,9),(16,2,18),(14,2,23)]
    elif variant==4:
        bed(m,'north',3,4,10,15,1,'carrots')
        bed(m,'elbow',3,20,10,29,1,'wheat')
        bed(m,'east',16,22,28,29,1,'potatoes',axis='x')
        m.box((12,1,3),(12,1,30),'water[level=0]');m.box((12,1,20),(29,1,20),'water[level=0]')
        for z in (3,17,30):m.box((11,1,z),(13,1,z),'spruce_planks')
        m.box((14,1,19),(15,1,21),'spruce_planks');headwater(m,7,16)
        lamp(m,2,3);lamp(m,29,30);route=[(11,2,10),(14,2,21),(29,2,21)]
    elif variant==5:
        for i,(x,z) in enumerate(((3,4),(20,4),(3,15),(20,15))):bed(m,f'b{i}',x,z,x+10,z+8,1,crops[i])
        m.box((15,1,3),(15,1,24),'water[level=0]');m.box((18,1,3),(18,1,24),'water[level=0]')
        for z in (3,13,24):
            for x in (15,18):m.set(x,1,z,'spruce_planks')
        shed(m,7,27,27,32);headwater(m,4,29)
        route=[(16,2,10),(17,2,20),(17,2,26)]
        lamp(m,3,25);lamp(m,31,25)
    else:
        for i,z in enumerate((4,16,28)):
            f=i+1
            bed(m,f'west{i}',3,z,11,z+7,f,crops[i])
            bed(m,f'east{i}',19,z,27,z+7,f,crops[(i+1)%4])
            m.box((17,f,z-1),(17,f,z+8),'water[level=0]')
            for zz in (z-1,z+8):m.set(17,f,zz,'spruce_planks')
            lamp(m,2,z+8,f)
        for z,f in ((14,2),(26,3)):
            for x in range(13,17):m.set(x,f,z,'sandstone_stairs[facing=south]')
        headwater(m,15,37,3);route=[(15,2,9),(15,3,21),(15,4,33)]
        m.meta['floors']=[dict(name='三级种植台与中央短阶',y=0,max_y=8)]
    # Harvest baskets and shaded-storage cues sit on dedicated dry margins.
    storage = {
        1: [(3,33),(17,33),(3,22),(17,12)],
        2: [(4,29),(7,29),(28,29),(30,13)],
        3: [(3,23),(24,23),(24,25)],
        4: [(3,17),(10,18),(30,21),(14,28)],
        5: [(4,25),(30,26),(30,29),(4,32)],
        6: [(4,12),(25,12),(4,24),(25,24),(5,37),(25,37)],
    }[variant]
    for i,(x,z) in enumerate(storage):
        f=level(z)
        m.set(x,f+1,z,'barrel[facing=up]' if i%2 else 'composter[level=6]')
        if i%2:m.set(x,f+2,z,'lantern')
    m.point('entry','entrance',(gatex,2,1),'田头主入口',facing='north')
    for i,p in enumerate(route):m.point(f'workway{i}','circulation',p,'第'+str(i+1)+'处采收作业道',look_at=[p[0]-2,p[1]+1,p[2]+3])
    m.meta['agriculture']=dict(crops=list(crops),layout=desc,template_mode='independent_fixed_plot',hydration='全部原版作物检查实际耕地与水平四格同层水源。')
    return m
