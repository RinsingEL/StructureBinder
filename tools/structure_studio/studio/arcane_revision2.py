"""Academy revision: buttressed bays, ribbed halls and occupied gallery levels."""
import math
from .revision_geometry import base,arch,line,curve,steps,arrival,work,window_rose,shelf,table,bed,tree,ring,disk

STONE='stone_bricks'
PALE='smooth_quartz'
DARK='polished_deepslate'
ROOF='purple_terracotta'


def academy(n,name,w,h,d,terms,role='key',tags=()):
    m=base(f'MG-{n:02d}',name,(w,h,d),'魔法学院新制',role=role,tags=tags,terms=terms)
    m.meta['source']='tools/structure_studio/studio/arcane_revision2.py:BUILDERS'
    m.box((3,1,3),(w-4,1,d-4),'stone_bricks')
    for x in range(4,w-4,6):m.box((x,1,3),(x,1,d-4),'polished_andesite')
    return m


def pier(m,x,z,bottom,top,r=1):
    m.box((x-r-1,bottom,z-r-1),(x+r+1,bottom+1,z+r+1),STONE)
    m.box((x-r,bottom+2,z-r),(x+r,top,z+r),STONE)
    for dx,dz in ((-r,0),(r,0),(0,-r),(0,r)):
        m.box((x+dx,bottom+2,z+dz),(x+dx,top,z+dz),'quartz_pillar')
    m.box((x-r-1,top,z-r-1),(x+r+1,top+1,z+r+1),'stone_brick_slab[type=top]')


def lancet(m,c,v,bottom,width,height,axis='x'):
    rise=max(2,width//2+1);spring=height-rise
    arch(m,c,v,bottom,width,spring,rise,STONE,axis=axis,fill='purple_stained_glass',rib=1)
    r=width//2
    for u in range(c-r+1,c+r,2):
        for yy in range(bottom,bottom+spring+rise-abs(u-c)-1):
            m.set(u,yy,v,'quartz_pillar') if axis=='x' else m.set(v,yy,u,'quartz_pillar')
    if width>=5:
        y=bottom+spring-1
        if axis=='x':m.box((c-r,y,v),(c+r,y,v),'quartz_slab')
        else:m.box((v,y,c-r),(v,y,c+r),'quartz_slab')


def nave(m,x0,z0,x1,z1,floor=1,top=25,bays=10):
    m.box((x0,floor,z0),(x1,floor,z1),'dark_oak_planks')
    for x in (x0,x1):m.box((x,floor+1,z0),(x,top,z1),'calcite')
    for z in (z0,z1):m.box((x0,floor+1,z),(x1,top,z),'calcite')
    for y in (floor+2,top-1):
        for x in (x0,x1):m.box((x,y,z0),(x,y,z1),STONE)
        for z in (z0,z1):m.box((x0,y,z),(x1,y,z),STONE)
    for z in range(z0+5,z1-3,bays):
        for x in (x0,x1):lancet(m,z,x,floor+4,5,min(14,top-floor-5),axis='z')
    for z in sorted({z0,z1,*range(z0+bays,z1,bays)}):
        for x in (x0,x1):pier(m,x,z,floor+1,top,0)
        # The complete cross rib springs from the engaged wall piers.
        arch(m,(x0+x1)//2,z,top-6,x1-x0-2,0,(x1-x0)//2+2,PALE,fill=None,rib=1)
    roof(m,x0-1,z0-1,x1+1,z1+1,top+2)
    if x1-x0>=14:
        for z in (z0,z1):
            window_rose(m,(x0+x1)//2,top+5,z,2)
            if top-floor>=12:
                for x in (x0+4,x1-4):lancet(m,x,z,floor+4,3,min(7,top-floor-5))


def roof(m,x0,z0,x1,z1,eave):
    for x in range(x0,x1+1):
        rise=min(x-x0,x1-x);y=eave+rise
        for z in range(z0,z1+1):
            block='polished_deepslate' if z in (z0,z1) or z%10==0 else 'purple_terracotta'
            m.set(x,y,z,block)
        if x0<x<x1:
            for z in (z0+1,z1-1):m.box((x,eave-2,z),(x,y-1,z),'calcite')
    for x in (x0+1,x1-1):m.box((x,eave-2,z0+1),(x,eave,z1-1),STONE)
    for z in range(z0,z1+1,5):m.set((x0+x1)//2,eave+(x1-x0)//2+1,z,'stone_brick_wall')


def tower(m,cx,cz,r,top,roof_h=13,bottom=2):
    cells={(x,z) for x in range(cx-r,cx+r+1) for z in range(cz-r,cz+r+1) if abs(x-cx)+abs(z-cz)<=2*r-2}
    edge={p for p in cells if any((p[0]+dx,p[1]+dz) not in cells for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)))}
    for x,z in cells:
        if bottom==2:m.set(x,1,z,STONE)
        if (x,z) in edge:m.box((x,bottom,z),(x,top,z),'calcite')
    for y in range(bottom+1,top,8):
        for x,z in edge:m.set(x,y,z,STONE)
    for x,z in edge:
        if abs(x-cx)==r-2 and abs(z-cz)==r:
            m.box((x,bottom,z),(x,top+2,z),STONE)
    for y in range(bottom+4,top-6,9):
        lancet(m,cx,cz-r,y,3,6);lancet(m,cx,cz+r,y,3,6)
        lancet(m,cz,cx-r,y,3,6,axis='z');lancet(m,cz,cx+r,y,3,6,axis='z')
    for yy in range(roof_h+1):
        rr=max(0,round((r+1)*(1-yy/roof_h)**1.3))
        for dx in range(-rr,rr+1):
            for dz in range(-rr,rr+1):
                if abs(dx)+abs(dz)<=rr+rr//2:
                    if abs(dx)==rr or abs(dz)==rr or abs(dx)+abs(dz)>=rr+rr//2-1 or yy==roof_h:m.set(cx+dx,top+1+yy,cz+dz,DARK if dx==0 or dz==0 else ROOF)
    m.set(cx,top+roof_h+2,cz,'gold_block');m.set(cx,top+roof_h+3,cz,'end_rod')
    for dx,dz in ((-r+1,-r+1),(r-1,-r+1),(-r+1,r-1),(r-1,r-1)):
        m.box((cx+dx,top-2,cz+dz),(cx+dx,top+4,cz+dz),STONE)
        m.set(cx+dx,top+5,cz+dz,'stone_brick_wall')


def buttress(m,wallx,outx,z,spring,landing):
    pier(m,outx,z,2,landing,1)
    mid=(wallx+outx)//2
    curve(m,((outx,landing,z),(mid,spring+3,z),(wallx,spring,z)),STONE,1)
    # The arc leaves a real opening above the lower aisle, with a second slender rib.
    curve(m,((outx,landing+3,z),(mid,spring+7,z),(wallx,spring+3,z)),'quartz_pillar')
    m.box((outx,landing+2,z),(outx,landing+7,z),'stone_brick_wall')
    m.set(outx,landing+8,z,'end_rod')


def great_hall():
    m=academy(1,'星穹礼堂 · 飞扶壁与肋拱中殿',71,72,83,('教学','礼仪','集会'))
    arrival(m,35)
    nave(m,24,26,46,73,top=28,bays=12)
    # Low aisles are visibly subordinate to the high clerestory nave.
    nave(m,15,31,23,70,top=13,bays=12);nave(m,47,31,55,70,top=13,bays=12)
    for z in (37,49,61):
        for x in (24,46):arch(m,z,x,2,7,5,5,STONE,axis='z',depth=1)
        buttress(m,24,9,z,26,13);buttress(m,46,61,z,26,13)
    # Deep three-layer central portal and a rose window, not a flat cutout.
    for z,width in ((23,13),(24,11),(25,9),(26,7)):
        arch(m,35,z,2,width,7,6,STONE,depth=1,rib=1)
    window_rose(m,35,23,25,5)
    for x in (25,45):pier(m,x,24,2,30,0)
    tower(m,16,19,5,35,14);tower(m,54,19,5,44,17)
    for cx in (16,54):arch(m,cx,14,2,3,5,3,STONE)
    # A real first-floor cross gallery reached from the left stair, overlooking the nave.
    m.box((19,10,27),(51,10,31),'dark_oak_planks')
    for x in range(19,52):m.set(x,11,31,'stone_brick_wall')
    m.box((19,11,27),(51,13,30),'air')
    steps(m,19,21,18,1,10)
    for x in (22,48):pier(m,x,29,2,9,0)
    work(m,'gallery',35,11,29,'礼堂上层旁听廊')
    for z in (39,45,51,57):
        table(m,27,2,z,5,'birch');table(m,39,2,z,5,'birch')
    m.box((28,2,66),(42,2,70),'polished_andesite')
    m.box((32,3,67),(38,3,69),'quartz_slab')
    m.set(35,4,68,'lectern');work(m,'lecturer',35,2,64,'讲台前演示空间')
    for x,z in ((18,43),(18,59),(50,43),(50,59)):shelf(m,x,2,z,3,'dark_oak')
    for z in (38,50,62):
        m.box((35,19,z),(35,24,z),'chain');m.set(35,18,z,'lantern[hanging=true]')
    m.room('nave','高挑礼堂',(26,2,33),(44,26,71),'分列坐席、中轴通行、讲台和肋拱空间')
    m.room('gallery','二层旁听廊',(22,11,27),(48,14,30),'实体楼梯连接的跨厅旁听平台')
    m.meta.update(floors=[{'name':'礼堂与侧廊','y':1,'max_y':9},{'name':'旁听廊与高窗','y':10,'max_y':17}],roof_min_y=30)
    m.meta['design_notes']+=['高窗中殿、低侧廊、三跨外飞扶壁与完整横肋构成骨架；门廊进深四格，玫瑰窗有石质窗棂。','前塔上部是封闭装饰与采光构架，不声明可用楼层。']
    return m


BUILDERS={'MG-01-v01':great_hall}


def oct_cells(cx,cz,r,cut):
    return {(x,z) for x in range(cx-r,cx+r+1) for z in range(cz-r,cz+r+1) if abs(x-cx)+abs(z-cz)<=2*r-cut}


def rotunda(m,cx,cz,r,floor,top,cut=5):
    cells=oct_cells(cx,cz,r,cut)
    edge={p for p in cells if any((p[0]+dx,p[1]+dz) not in cells for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)))}
    for x,z in cells:
        m.set(x,floor,z,'dark_oak_planks')
        if (x,z) in edge:m.box((x,floor+1,z),(x,top,z),'calcite')
    for x,z in edge:
        for y in (floor+2,top-1):m.set(x,y,z,STONE)
    for offset in (-r+cut,r-cut):
        for x,z in ((cx+offset,cz-r),(cx+offset,cz+r),(cx-r,cz+offset),(cx+r,cz+offset)):
            pier(m,x,z,floor+1,top,0)
    return cells,edge


def library():
    m=academy(2,'八瓣藏书馆 · 双层环廊与采光穹顶',61,61,65,('书籍借阅','文献保管','阅读'))
    arrival(m,30)
    cells,edge=rotunda(m,30,34,21,1,22,9)
    # Two-storey annular reading gallery around a nine-block-radius light well.
    for x,z in cells-edge:
        if (x-30)**2+(z-34)**2>=9**2:m.set(x,11,z,'dark_oak_planks')
        if 9**2<=(x-30)**2+(z-34)**2<11**2:m.set(x,12,z,'stone_brick_wall')
    for x,z in ((19,25),(41,25),(19,43),(41,43)):
        pier(m,x,z,2,10,0)
    steps(m,44,46,24,1,11)
    m.box((44,12,34),(46,15,36),'air')
    for yy in (4,14):
        for cx in (21,30,39):lancet(m,cx,13,yy,5,7);lancet(m,cx,55,yy,5,7)
        for cz in (25,34,43):lancet(m,cz,9,yy,5,7,axis='z');lancet(m,cz,51,yy,5,7,axis='z')
    for dx,dz in ((-15,-15),(15,-15),(-15,15),(15,15)):
        tower(m,30+dx,34+dz,3,27,9)
    # Eight raised ribs spring from the octagonal wall into a tall glazed lantern.
    for dy in range(13):
        rr=21-round(dy*.9);skin=oct_cells(30,34,rr,max(2,round(9*rr/21)))
        inner=oct_cells(30,34,rr-2,max(1,round(9*(rr-2)/21)))
        for x,z in skin-inner:m.set(x,23+dy,z,ROOF)
    for dx,dz in ((0,-21),(0,21),(-21,0),(21,0),(-15,-15),(15,-15),(-15,15),(15,15)):
        curve(m,((30+dx,23,34+dz),(30+dx//2,38,34+dz//2),(30+dx//3,36,34+dz//3)),STONE)
    for x,z in oct_cells(30,34,10,3)-oct_cells(30,34,6,2):m.set(x,35,z,STONE)
    tower(m,30,34,7,41,13,bottom=35)
    # Only the lantern wall is present above the atrium, so the light well remains open.
    m.box((25,2,29),(35,34,39),'air')
    for zz,width in ((10,9),(11,7),(12,5),(13,5)):arch(m,30,zz,2,width,5,4,STONE)
    for yy in (2,12):
        for x,z in ((15,23),(38,23),(15,46),(38,46)):shelf(m,x,yy,z,7,'dark_oak')
    for x in (19,37):table(m,x,2,37,5,'birch')
    m.set(30,2,20,'lectern');work(m,'catalog',30,2,22,'一层书目检索')
    work(m,'upper',44,12,39,'上层环廊书架')
    m.room('reading','环形阅览厅',(17,2,21),(43,9,47),'中央采光井与周边阅览座位')
    m.room('gallery','双层藏书环廊',(13,12,19),(47,20,49),'围绕挑空中庭的连续藏书层')
    m.meta.update(roof_min_y=23,floors=[{'name':'中央阅览厅','y':1,'max_y':10},{'name':'环形藏书廊','y':11,'max_y':21}])
    return m


def observatory():
    m=academy(3,'子午观星台 · 阶塔与开瓣星穹',51,64,55,('天象观测','研究'))
    arrival(m,25)
    cells,edge=rotunda(m,25,29,15,1,27,5)
    for y in (10,19,28):
        for x,z in cells:m.set(x,y,z,STONE if y==28 or (x,z) in edge else 'dark_oak_planks')
    steps(m,14,16,17,1,10);steps(m,34,36,38,10,19,south=False);steps(m,14,16,17,19,28)
    for y in (4,13,22):
        for x in (18,25,32):lancet(m,x,14,y,3,6);lancet(m,x,44,y,3,6)
        for z in (22,29,36):lancet(m,z,10,y,3,6,axis='z');lancet(m,z,40,y,3,6,axis='z')
    for floor,x0,x1,z0,z1 in ((10,14,16,17,25),(19,34,36,30,38),(28,14,16,17,25)):
        for xx in (x0-1,x1+1):m.box((xx,floor+1,z0),(xx,floor+1,z1),'stone_brick_wall')
        end=z1+1 if x0==34 else z0-1
        m.box((x0,floor+1,end),(x1,floor+1,end),'stone_brick_wall')
    for x,z in ((10,22),(10,36),(40,22),(40,36)):
        pier(m,x,z,2,24,1)
    for z,width in ((11,9),(12,7),(13,5),(14,5)):arch(m,25,z,2,width,5,4,STONE)
    # Open meridian ribs spring from the roof terrace; their curves form a broken dome.
    for y in range(29,47):
        rr=max(1,round(13*math.sqrt(max(0,1-((y-29)/18)**2))))
        for x,z in ((25-rr,29),(25+rr,29),(25,29-rr),(25,29+rr)):
            m.set(x,y,z,'waxed_cut_copper')
    for x,z in edge:m.set(x,29,z,'stone_brick_wall')
    m.box((21,29,25),(29,30,33),'polished_andesite')
    m.box((24,31,28),(26,34,30),'quartz_pillar');m.set(25,35,29,'amethyst_block')
    for k in range(80):
        a=2*math.pi*k/80;m.set(25+round(8*math.cos(a)),38+round(6*math.sin(a)),29,'gold_block')
    for x,z in ((18,20),(31,39)):shelf(m,x,2,z,4,'dark_oak')
    table(m,21,11,36,7,'birch');table(m,21,20,36,7,'birch')
    work(m,'chart',25,11,33,'历法与星图层');work(m,'study',25,20,33,'观测准备层');work(m,'sky',25,29,22,'星仪观测露台')
    m.room('archive','星图档案',(17,2,20),(33,9,38),'星图存放与接待')
    m.meta.update(roof_min_y=30,floors=[{'name':'档案','y':1,'max_y':9},{'name':'星图工作层','y':10,'max_y':18},{'name':'准备层','y':19,'max_y':27},{'name':'星仪露台','y':28,'max_y':48}])
    return m


BUILDERS.update({'MG-02-v01':library,'MG-03-v01':observatory})


def glazed_vault(m,bounds,eave,rise):
    x0,z0,x1,z1=bounds;cx=(x0+x1)//2;r=(x1-x0)//2
    for x in range(x0,x1+1):
        y=eave+round(rise*(1-abs(x-cx)/r)**.7)
        for z in range(z0,z1+1):
            block=STONE if z in (z0,z1) or (z-z0)%6==0 else 'light_blue_stained_glass'
            m.set(x,y,z,block)
            if x>cx:
                py=eave+round(rise*(1-abs(x-1-cx)/r)**.7)
                for yy in range(y,py):m.set(x,yy,z,block)
            elif x<cx:
                py=eave+round(rise*(1-abs(x+1-cx)/r)**.7)
                for yy in range(y,py):m.set(x,yy,z,block)
    for z in range(z0,z1+1,6):
        for x in (x0,x1):pier(m,x,z,2,eave,0)


def laboratory():
    m=academy(4,'三院炼金实验所 · 晶核中庭与玻璃长翼',73,64,73,('实验研究','药剂制作'))
    arrival(m,36)
    rotunda(m,36,43,12,1,22,4)
    for bounds,top in [((7,26,23,62),12),((49,26,65,62),15)]:
        x0,z0,x1,z1=bounds;nave(m,*bounds,top=top,bays=10)
        m.box((x0-1,top+1,z0-1),(x1+1,top+18,z1+1),'air')
        glazed_vault(m,(x0,z0,x1,z1),top+1,7)
        for z in (33,45,56):
            table(m,x0+4,2,z,7,'birch');m.set(x0+6,4,z,'brewing_stand')
        arch(m,(x0+x1)//2,z0,2,5,4,4,STONE)
        work(m,f'lab_{x0}',x0+8,2,29,'独立实验长翼')
    for x in (24,48):arch(m,43,x,2,5,4,4,STONE,axis='z')
    arch(m,36,31,2,5,5,4,STONE)
    for dx,dz in ((-9,-9),(9,-9),(-9,9),(9,9)):pier(m,36+dx,43+dz,2,28,1)
    for x in (27,45):line(m,(x,28,34),(36,37,43),STONE,1);line(m,(x,28,52),(36,37,43),STONE,1)
    for y in range(8,29):
        r=max(1,5-abs(y-18)//3)
        for dx in range(-r,r+1):
            for dz in range(-r,r+1):
                if abs(dx)+abs(dz)<=r:m.set(36+dx,y,43+dz,'amethyst_block')
    m.box((33,2,40),(39,5,46),'polished_deepslate')
    work(m,'central',36,2,36,'晶核实验观察环道')
    for x,z in ((15,13),(57,13)):tower(m,x,z,3,23,11)
    m.room('crystal','四柱晶核实验庭',(26,2,33),(46,29,53),'封护实验核心及周边观察通路')
    m.meta['roof_min_y']=30
    return m


def dormitory():
    m=academy(5,'回廊学生宿舍 · 双翼三层寝室',69,61,67,('学生住宿','自习'),role='self_contained')
    arrival(m,34)
    for x0,x1 in ((9,25),(43,59)):
        nave(m,x0,16,x1,55,top=24,bays=12)
        arch(m,(x0+x1)//2,16,2,5,4,4,STONE)
        for y in (8,15):m.box((x0+1,y,17),(x1-1,y,54),'dark_oak_planks')
        steps(m,x0+2,x0+4,23,1,8);steps(m,x1-4,x1-2,39,8,15,south=False)
        for y in (2,9,16):
            for z in (43,51):
                bed(m,f'bed_{x0}_{y}_{z}',x0+7,y,z,'purple');bed(m,f'bed_b_{x0}_{y}_{z}',x0+12,y,z,'purple')
            table(m,x0+6,y,20,5,'birch')
            # Rear partition leaves the stair-side corridor open.
            m.box((x0+6,y,46),(x1-1,y+4,46),'dark_oak_planks');m.box((x0+9,y,46),(x0+10,y+2,46),'air')
        work(m,f'floor3_{x0}',x0+8,16,37,'三层自习与寝居通道')
    m.box((25,8,48),(43,8,52),STONE)
    for x in (25,43):m.box((x,9,49),(x,12,51),'air')
    for z in (48,52):m.box((26,9,z),(42,9,z),'stone_brick_wall')
    for x in (28,40):pier(m,x,50,2,7,0)
    for x in (29,39):
        m.box((x,1,27),(x+2,1,40),'grass_block')
        for z in (29,34,39):m.set(x+1,2,z,'allium')
    m.room('court','双翼学习庭',(27,2,19),(41,7,45),'宿舍围成的安静步行庭院')
    m.meta.update(roof_min_y=26,floors=[{'name':'宿舍首层','y':1,'max_y':7},{'name':'寝居与桥廊','y':8,'max_y':14},{'name':'上层寝室','y':15,'max_y':23}])
    return m


def shop():
    m=academy(6,'学院器材店 · 八角陈列窗与后部货间',43,43,49,('商品零售','货物暂存'),role='fill')
    arrival(m,21);rotunda(m,21,19,12,1,12,4)
    for x in (15,21,27):lancet(m,x,7,3,3,7)
    arch(m,21,7,2,5,4,3,STONE)
    nave(m,13,29,29,42,top=10,bays=6)
    arch(m,21,29,2,5,4,3,STONE);arch(m,21,31,2,5,4,3,STONE)
    for yy in range(8):
        rr=13-yy
        cur=oct_cells(21,19,rr,max(2,4-yy//3));nxt=oct_cells(21,19,rr-1,max(2,4-(yy+1)//3))
        for x,z in cur-nxt:m.set(x,13+yy,z,ROOF)
        for x,z in cur:
            if any((x+dx,z+dz) not in cur for dx,dz in ((1,0),(-1,0),(0,1),(0,-1))):m.set(x,13+yy,z,ROOF)
    for x,z in oct_cells(21,19,5,2):m.set(x,21,z,'light_blue_stained_glass')
    for x,z in oct_cells(21,19,6,2)-oct_cells(21,19,5,2):m.set(x,21,z,STONE)
    for x in (12,30):pier(m,x,11,2,13,0)
    for z in (16,23):table(m,15,2,z,5,'birch');table(m,24,2,z,3,'birch')
    shelf(m,16,2,40,10,'dark_oak','barrel');m.set(17,4,16,'brewing_stand');m.set(25,4,23,'amethyst_cluster')
    work(m,'retail',21,2,20,'中央选购通道');work(m,'stock',21,2,36,'后部补货间')
    m.room('sales','八角器材陈列厅',(13,2,12),(29,10,26),'环列陈设与居中的选购通道')
    return m


def refectory():
    m=academy(7,'锤梁长桌食堂 · 高窗长厅与备餐院',57,56,69,('餐饮','集体用餐'),role='self_contained')
    arrival(m,26);nave(m,13,14,39,57,top=20,bays=10)
    for z in (24,34,44,54):
        for sign in (-1,1):
            x=26+sign*12
            line(m,(x,14,z),(26+sign*7,18,z),'dark_oak_log',0)
            line(m,(26+sign*7,18,z),(26,28,z),'dark_oak_log',0)
            m.box((min(x,26+sign*7),18,z),(max(x,26+sign*7),18,z),'dark_oak_log[axis=x]')
    for x in (18,29):
        for z in (25,35,45):table(m,x,2,z,6,'birch')
    arch(m,26,14,2,7,6,5,STONE)
    nave(m,40,33,50,57,top=11,bays=8);arch(m,45,33,2,3,4,3,STONE)
    arch(m,44,39,2,5,4,3,STONE,axis='z')
    for z in (40,48,53):m.set(48,2,z,'smoker');m.set(47,2,z,'barrel')
    m.box((47,12,51),(49,28,53),STONE)
    window_rose(m,26,17,13,4)
    work(m,'dining',26,2,32,'长桌间主通道');work(m,'kitchen',45,2,45,'备餐工间')
    m.room('dining','锤梁食堂',(15,2,16),(37,19,55),'长桌、锤梁与高窗共同形成集体用餐空间')
    m.meta['roof_min_y']=22
    return m


BUILDERS.update({f'MG-{i:02d}-v01':f for i,f in ((4,laboratory),(5,dormitory),(6,shop),(7,refectory))})


def rector_tower():
    m=academy(13,'层台院务塔 · 四层议事与学籍廊',55,68,59,('公共办事','议事','文献保管'))
    arrival(m,27);rotunda(m,27,31,15,1,34,6)
    for y in (9,17,25):
        for x,z in oct_cells(27,31,14,5):m.set(x,y,z,'dark_oak_planks')
    steps(m,16,18,20,1,9);steps(m,36,38,40,9,17,south=False);steps(m,16,18,20,17,25)
    for y in (4,12,20,28):
        for x in (20,27,34):lancet(m,x,16,y,3,5);lancet(m,x,46,y,3,5)
    for x in (12,42):
        for z in (25,37):buttress(m,x,x-5 if x<27 else x+5,z,27,17)
    tower(m,27,31,11,42,16,bottom=35)
    for x,z in oct_cells(27,31,15,6)-oct_cells(27,31,9,3):m.set(x,35,z,STONE)
    for z,width in ((13,9),(14,7),(15,5),(16,5)):arch(m,27,z,2,width,5,4,STONE)
    for y in (2,10,18,26):
        table(m,22,y,37,9,'birch');shelf(m,21,y,43,10,'dark_oak')
        work(m,f'floor_{y}',27,y,34,'院务议事与学籍工作层')
    m.meta.update(roof_min_y=35,floors=[{'name':n,'y':y,'max_y':y+7} for n,y in [('接待',1),('学籍',9),('会商',17),('院长议事',25)]])
    m.room('administration','院务塔',(17,2,21),(37,33,41),'四个实体楼层经交替楼梯连通')
    return m


def portal_hall():
    m=academy(14,'三环传送厅 · 三进尖拱与校准侧廊',61,57,69,('出行登记','传送设施维护'))
    arrival(m,30)
    for z,w,spring,rise in ((19,23,12,14),(34,19,10,12),(49,15,8,10)):
        for zz in (z,z+2):arch(m,30,zz,2,w,spring,rise,STONE,depth=1,rib=2)
        for x in (30-w//2-2,30+w//2+2):
            pier(m,x,z+1,2,spring+5,1)
            m.box((x,spring+5,z+1),(x,spring+10,z+1),'stone_brick_wall')
            m.set(x,spring+11,z+1,'amethyst_block')
    m.box((24,1,8),(36,1,58),'polished_deepslate')
    for z in (20,35,50):
        for x in range(26,35):m.set(x,1,z,'amethyst_block')
    for x0,x1 in ((5,15),(45,55)):
        nave(m,x0,26,x1,57,top=13,bays=10);arch(m,(x0+x1)//2,26,2,3,4,3,STONE)
        shelf(m,x0+3,2,51,4,'dark_oak');m.set(x0+5,2,39,'enchanting_table')
        work(m,f'service_{x0}',x0+5,2,37,'目的地校准与出行登记')
    work(m,'portal',30,2,43,'三环校准通道')
    m.room('rings','三进传送环庭',(19,2,15),(41,25,56),'有普通步行通路的静态传送环序列')
    m.meta['roof_min_y']=35
    return m


def infirmary():
    m=academy(15,'愈疗圣堂 · 十字病房与日照庭',65,55,71,('医疗','照料','休养'))
    arrival(m,32);nave(m,23,14,41,59,top=21,bays=12)
    for x0,x1 in ((6,22),(42,58)):
        nave(m,x0,31,x1,50,top=12,bays=9)
        arch(m,(x0+x1)//2,31,2,5,4,4,STONE)
        for x in (x0+5,x0+11):bed(m,f'patient_{x}',x,2,44,'white')
        work(m,f'nurse_{x0}',x0+8,2,37,'病房巡视与照护')
    for x in (22,23,41,42):arch(m,39,x,2,7,4,5,STONE,axis='z')
    arch(m,32,14,2,7,5,5,STONE);window_rose(m,32,18,13,4)
    for x,z in ((12,17),(50,17),(12,58),(50,58)):
        m.box((x,1,z),(x+3,1,z+5),'grass_block')
        for zz in range(z,z+6,2):m.set(x+1,2,zz,'lily_of_the_valley')
    table(m,28,2,52,7,'birch');m.set(32,4,52,'brewing_stand')
    work(m,'care',32,2,49,'中央照护与配药台')
    m.room('care','采光照护中厅',(25,2,17),(39,18,57),'两侧病房围绕高窗中厅展开')
    m.meta['roof_min_y']=23
    return m


def crystal_works():
    m=academy(16,'四柱晶核工坊 · 张拱主架与检修庭',65,65,65,('魔法器材制作','能源设施维护'))
    arrival(m,32)
    for x,z in ((18,18),(46,18),(18,46),(46,46)):
        pier(m,x,z,2,27,2)
        curve(m,((x,28,z),((x+32)//2,47,(z+32)//2),(32,48,32)),STONE,1)
        m.set(x,29,z,'amethyst_block')
    for z in (18,46):arch(m,32,z,17,25,0,17,STONE,fill=None,rib=2)
    m.box((27,2,27),(37,5,37),'polished_deepslate')
    for y in range(6,39):
        r=max(1,6-abs(y-22)//4)
        for dx in range(-r,r+1):
            for dz in range(-r,r+1):
                if abs(dx)+abs(dz)<=r:m.set(32+dx,y,32+dz,'amethyst_block')
    for z in (9,52):
        nave(m,23,z,41,z+8,top=9,bays=8);arch(m,32,z,2,5,3,3,STONE)
        m.set(27,2,z+4,'smithing_table');m.set(37,2,z+4,'enchanting_table')
    for x in (11,53):
        m.box((x,1,17),(x,1,47),'waxed_cut_copper')
        for z in (23,32,41):m.set(x,2,z,'lightning_rod')
    work(m,'repair',21,2,32,'晶核外围检修通道');work(m,'register',32,2,13,'前部物资与停机登记')
    m.room('core','张拱晶核庭',(19,2,19),(45,45,45),'四角主架与巨型晶核形成独立设备空间')
    m.meta['roof_min_y']=50
    return m


def professor_home():
    m=academy(17,'导师小院 · 阶梯书斋与玻璃冬园',47,45,53,('家庭居住','研究'),role='self_contained')
    arrival(m,23);nave(m,8,16,28,42,top=17,bays=9)
    m.box((9,9,17),(27,9,41),'dark_oak_planks');steps(m,11,13,22,1,9)
    arch(m,18,16,2,5,4,4,STONE)
    for x in (18,23):bed(m,f'bed_{x}',x,10,37,'purple')
    shelf(m,16,2,38,8,'dark_oak');table(m,16,2,28,7,'birch')
    glazed_vault(m,(29,23,39,42),9,7)
    for x in (29,39):m.box((x,2,23),(x,9,42),'light_blue_stained_glass')
    m.box((30,1,24),(38,1,41),'dark_oak_planks')
    for z in (27,34,39):m.set(35,2,z,'flower_pot')
    work(m,'study',20,2,26,'导师书斋');work(m,'upper',19,10,32,'上层寝居')
    m.room('study','书斋与会面厅',(10,2,18),(26,8,40),'导师家庭居住与独立书斋')
    m.meta.update(roof_min_y=19,floors=[{'name':'书斋与冬园','y':1,'max_y':8},{'name':'寝居','y':9,'max_y':16}])
    return m


def potion_shop():
    m=academy(18,'药剂作坊 · 错顶工间与冷却烟塔',49,46,51,('药剂制作','商品零售'),role='fill')
    arrival(m,24)
    nave(m,7,10,25,39,top=13,bays=10);nave(m,26,23,41,44,top=18,bays=10)
    arch(m,16,10,2,5,4,4,STONE);arch(m,32,25,2,5,4,4,STONE,axis='z');arch(m,32,26,2,5,4,4,STONE,axis='z')
    for x,z in ((12,20),(12,30),(30,31),(30,39)):
        table(m,x,2,z,5,'birch');m.set(x+2,4,z,'brewing_stand')
    m.box((36,2,37),(39,29,40),STONE);m.box((37,3,38),(38,29,39),'air')
    m.set(36,2,36,'smoker');work(m,'mix',17,2,25,'药剂配比与配售');work(m,'cool',33,2,35,'后场冷却工间')
    m.room('workshop','前后分开的药剂工间',(9,2,12),(23,12,37),'前部配售与后部蒸馏工序分离')
    return m


def scriptorium():
    m=academy(19,'卷轴抄写所 · 连拱采光书廊',57,39,45,('文献抄写','书籍制作'),role='fill')
    arrival(m,28)
    for x0,x1 in ((7,22),(34,49)):
        nave(m,x0,10,x1,35,top=14,bays=8);arch(m,(x0+x1)//2,10,2,5,4,4,STONE)
        for z in (17,25):
            table(m,x0+4,2,z,6,'birch');m.set(x0+6,4,z,'lectern')
        shelf(m,x0+3,2,32,8,'dark_oak')
        work(m,f'copy_{x0}',x0+8,2,21,'沿高窗排列的抄写桌')
    for z in (15,23,31):arch(m,28,z,2,9,3,5,STONE,fill=None)
    m.box((23,1,10),(33,1,35),'polished_andesite')
    m.room('cloister','抄写中廊',(24,2,12),(32,10,34),'双工作翼之间的连续拱廊')
    return m


def sealed_store():
    m=academy(20,'封印材料库 · 厚壁券室与卸货前庭',45,38,55,('货物仓储','材料保管'),role='fill')
    arrival(m,22);nave(m,9,18,35,46,top=14,bays=8)
    for x in (7,37):
        for z in (24,36,44):pier(m,x,z,2,13,1)
    for z,width in ((14,11),(15,9),(16,7),(17,5),(18,5)):arch(m,22,z,2,width,5,4,STONE)
    for x in (13,26):
        for z in (24,32,40):shelf(m,x,2,z,4,'dark_oak','barrel')
    work(m,'inventory',22,2,34,'材料清点主通道')
    for x in (12,30):m.set(x,2,10,'barrel');m.set(x+1,2,11,'crafting_table')
    m.room('stock','封印材料券室',(11,2,20),(33,12,44),'左右成组货架与居中搬运通路')
    return m


def greenhouse():
    m=academy(21,'教学玻璃温室 · 三跨肋架与试植床',59,34,51,('植物教学','园艺养护'),role='fill')
    arrival(m,29)
    for xa,xb in ((8,20),(23,35),(38,50)):
        glazed_vault(m,(xa,14,xb,41),11,8)
        for x in (xa,xb):m.box((x,2,14),(x,10,41),'glass')
        m.box((xa+1,1,15),(xb-1,1,40),'moss_block')
        for x in (xa+3,xb-3):
            for z in range(18,39,4):m.set(x,2,z,'flowering_azalea')
        m.box(((xa+xb)//2,1,14),((xa+xb)//2,1,41),'stone_bricks')
        work(m,f'growing_{xa}',(xa+xb)//2,2,27,'温室试植与通行带')
    m.room('greenhouse','三跨试植温室',(9,2,15),(49,18,40),'三种采光试植床与独立维护通道')
    m.meta['roof_min_y']=12
    return m


BUILDERS.update({f'MG-{i:02d}-v01':f for i,f in ((13,rector_tower),(14,portal_hall),(15,infirmary),(16,crystal_works),(17,professor_home),(18,potion_shop),(19,scriptorium),(20,sealed_store),(21,greenhouse))})


def bridge():
    m=academy(8,'尖拱步廊桥 · 高券桥腹与玻璃廊顶',29,39,59,('步行过桥',),role='structure',tags=('infrastructure',))
    arrival(m,14);m.box((0,1,17),(28,1,41),'water')
    for x in (9,19):arch(m,29,x,2,33,0,7,STONE,axis='z',fill=None,rib=2)
    m.box((9,9,12),(19,9,46),STONE)
    steps(m,11,17,4,1,9);steps(m,11,17,54,1,9,south=False)
    for x in (9,19):m.box((x,10,12),(x,10,46),'stone_brick_wall')
    for z in (17,29,41):
        for x in (9,19):pier(m,x,z,10,20,0)
        arch(m,14,z,17,9,0,7,STONE,fill=None)
    glazed_vault(m,(9,17,19,41),21,6)
    work(m,'bridge',14,10,29,'跨水步廊');m.meta.update(roof_min_y=21,floors=[{'name':'过桥廊面','y':9,'max_y':19}])
    return m


def lamp():
    m=academy(9,'环晶路灯 · 四瓣悬灯与仪环',19,27,19,('道路照明',),role='structure',tags=('infrastructure',))
    arrival(m,9);pier(m,9,9,2,11,0)
    for dx,dz in ((-5,0),(5,0),(0,-5),(0,5)):
        curve(m,((9,10,9),(9+dx,17,9+dz),(9+dx,15,9+dz)),STONE)
        m.set(9+dx,14,9+dz,'sea_lantern');m.set(9+dx,13,9+dz,'end_rod[facing=down]')
    ring(m,9,9,17,5,5,'waxed_cut_copper')
    m.box((9,12,9),(9,19,9),'quartz_pillar');m.set(9,20,9,'amethyst_block');m.set(9,21,9,'amethyst_cluster')
    m.meta['roof_min_y']=23
    return m


def fountain():
    m=academy(10,'三级星泉 · 放射石肋与水盆',35,31,35,('水景观赏','休憩'),role='structure',tags=('landscape',))
    arrival(m,17)
    for y,r in ((2,12),(7,7),(12,3)):
        disk(m,17,17,y,r,STONE);disk(m,17,17,y+1,r,STONE);disk(m,17,17,y+1,r-1,'water')
    m.box((16,2,16),(18,15,18),'quartz_pillar')
    for dx,dz in ((-8,0),(8,0),(0,-8),(0,8)):
        curve(m,((17+dx,2,17+dz),(17+dx,8,17+dz),(17,12,17)),STONE)
    m.set(17,16,17,'sea_lantern');m.set(17,17,17,'amethyst_cluster')
    m.meta['roof_min_y']=22
    return m


def silver_birch():
    m=academy(11,'学院银桦 · 三干庭树与弧形读书席',39,39,39,('树木观赏','休憩'),role='structure',tags=('landscape',))
    arrival(m,19);m.box((6,1,6),(32,1,32),'grass_block')
    tree(m,18,20,24,7,'birch','birch_leaves');tree(m,22,23,20,5,'birch','birch_leaves')
    for x,z in ((9,13),(11,10),(26,10),(29,13)):
        m.set(x,2,z,'stone_brick_stairs[facing=south]');m.set(x+1,2,z,'stone_brick_stairs[facing=south]')
    m.meta['roof_min_y']=35
    return m


def herb_court():
    m=academy(12,'药草温室庭 · 十字花径与双玻璃花房',43,33,47,('花草观赏','园艺养护'),role='structure',tags=('landscape',))
    arrival(m,21);m.box((6,1,6),(36,1,40),'grass_block')
    for x0,x1 in ((7,17),(25,35)):
        glazed_vault(m,(x0,24,x1,39),9,6)
        for x in range(x0+3,x1-1,3):
            for z in (28,34):m.set(x,2,z,'flowering_azalea')
    m.box((20,1,0),(22,1,42),'polished_andesite');m.box((6,1,20),(36,1,22),'polished_andesite')
    for x in (10,29):
        for z in (8,14):
            m.box((x,2,z),(x+3,2,z+3),'moss_block')
            m.set(x+1,3,z+1,'allium');m.set(x+2,3,z+2,'blue_orchid')
    arch(m,21,8,2,9,4,5,STONE,fill=None);m.meta['roof_min_y']=18
    return m


def gate():
    m=academy(22,'学院双塔门楼 · 尖券通道与值守桥廊',57,57,37,('出入通行','驻守'),role='structure',tags=('infrastructure',))
    arrival(m,28)
    tower(m,13,20,5,32,13);tower(m,43,20,5,37,13)
    for z in (17,23):arch(m,28,z,2,19,5,9,STONE,rib=2)
    m.box((17,18,17),(39,18,23),STONE)
    for z in (17,23):m.box((18,19,z),(38,19,z),'stone_brick_wall')
    # External dogleg-free stair climbs directly to the gate gallery.
    steps(m,20,22,1,1,18)
    m.box((20,19,18),(22,22,20),'air')
    work(m,'gate_gallery',28,19,20,'可达的值守门廊')
    m.meta.update(roof_min_y=34,floors=[{'name':'门道','y':1,'max_y':12},{'name':'值守桥廊','y':18,'max_y':25}])
    return m


def quay():
    m=academy(23,'星灯河埠 · 券廊候船与双臂栈桥',53,38,59,('水运接驳','候船'),role='structure',tags=('infrastructure',))
    arrival(m,26);m.box((0,1,30),(52,1,58),'water')
    m.box((8,2,16),(44,2,32),STONE)
    steps(m,23,29,15,1,2)
    for x in (10,40):
        m.box((x,2,31),(x+2,2,52),'dark_oak_planks')
        for z in (33,43,52):m.box((x,0,z-1),(x+2,1,z+1),STONE)
    for x in (12,26,40):pier(m,x,24,3,13,0)
    for x in (19,33):arch(m,x,24,7,11,0,7,STONE,fill=None)
    for x in (14,33):table(m,x,3,20,5,'birch')
    for x in (10,41):m.box((x,3,47),(x,8,47),'stone_brick_wall');m.set(x,9,47,'sea_lantern')
    work(m,'waiting',26,3,28,'候船券廊');work(m,'berth_left',11,3,49,'左侧登船栈桥');work(m,'berth_right',41,3,49,'右侧登船栈桥');m.meta['roof_min_y']=18
    return m


def purple_tree():
    m=academy(24,'旋枝紫荫树 · 扭转枝冠与星石基座',39,43,39,('树木观赏',),role='structure',tags=('landscape',))
    arrival(m,19);m.box((7,1,7),(31,1,31),'grass_block')
    tree(m,19,20,27,7,'dark_oak','flowering_azalea_leaves')
    for y in range(3,25):
        a=y*.42;m.set(19+round(2*math.cos(a)),y,20+round(2*math.sin(a)),'stripped_dark_oak_log')
    ring(m,19,20,2,8,8,STONE)
    m.meta['roof_min_y']=37
    return m


BUILDERS.update({f'MG-{i:02d}-v01':f for i,f in ((8,bridge),(9,lamp),(10,fountain),(11,silver_birch),(12,herb_court),(22,gate),(23,quay),(24,purple_tree))})
