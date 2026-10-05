"""Geometry primitives for the second design pass; no civilization house template."""
import math
from .model import Model


def base(code, name, size, civilization, *, role='key', tags=(), terms=()):
    m = Model(code+'-v01', name, size, family=code, civilization=civilization, role=role)
    w,h,d=size
    m.box((0,0,0),(w-1,0,d-1),'stone')
    m.box((0,1,0),(w-1,1,d-1),'grass_block')
    m.box((0,2,0),(w-1,h-1,d-1),'air')
    m.meta.update(function_terms=list(terms), asset_tags=list(tags),
        source='tools/structure_studio/studio/*_revision2.py',
        ground_plane={'y':2,'note':'外围道路脚底 Y=2；高层须有模型内实体楼梯。'},
        preview_context={'kind':'flat','land_surface_y':2,'padding':3,'surface':'grass'},
        terrain={'选址':'连续稳定承载基面；模型自带台基、高差与支承构件，保留完整外轮廓净空。'},
        floors=[{'name':'首层','y':1,'max_y':8}],roof_min_y=15,
        design_notes=['2026-10-05 第二轮结构重做；按结构轮廓、构件连接、立面进深、室内空间与文明差异核对。'],
        design_revision=2)
    return m


def line(m,a,b,material,r=0):
    n=max(abs(b[i]-a[i]) for i in range(3))*3+1
    for i in range(n+1):
        p=tuple(round(a[k]+(b[k]-a[k])*i/n) for k in range(3))
        m.box(tuple(v-r for v in p),tuple(v+r for v in p),material)


def curve(m,points,material,r=0):
    """Quadratic Bezier with connected voxel segments."""
    a,b,c=points;last=a
    for i in range(1,61):
        t=i/60;p=tuple(round((1-t)**2*a[k]+2*t*(1-t)*b[k]+t*t*c[k]) for k in range(3))
        line(m,last,p,material,r);last=p


def arch_profile(u,r,rise,kind='pointed'):
    t=abs(u)/max(1,r)
    if kind=='round':return round(rise*math.sqrt(max(0,1-t*t)))
    if kind=='angular':return round(rise*(1-t)*0.65+rise*0.35) if t<.7 else round(rise*(1-t))
    return round(rise*(1-t**1.35))


def arch(m,c,wall,bottom,width,spring,rise,mat,*,axis='x',depth=1,fill='air',kind='pointed',rib=1):
    """Pier-to-pier arch with carved opening and proud extrados."""
    r=width//2
    def put(u,y,v,b):m.set(u,y,v,b) if axis=='x' else m.set(v,y,u,b)
    for v in range(wall,wall+depth):
        for u in range(-r-rib,r+rib+1):
            inner=bottom+spring+arch_profile(min(r,abs(u)),r,rise,kind)
            if abs(u)<=r:
                if fill is not None:
                    for y in range(bottom,inner):put(c+u,y,v,fill)
                # Join steep adjacent courses vertically: a thin arch must not
                # become disconnected floating voxels near its haunches.
                adjacent=max(arch_profile(min(r,abs(u+du)),r,rise,kind) for du in (-1,0,1))
                outer=bottom+spring+adjacent+rib
                for y in range(inner,outer+1):put(c+u,y,v,mat)
            else:
                for y in range(bottom,bottom+spring+rib+1):put(c+u,y,v,mat)


def ring(m,cx,cz,y,rx,rz,mat,thickness=1):
    for x in range(cx-rx,cx+rx+1):
        for z in range(cz-rz,cz+rz+1):
            q=((x-cx)/rx)**2+((z-cz)/rz)**2
            if q<=1 and (((x-cx)/max(1,rx-thickness))**2+((z-cz)/max(1,rz-thickness))**2>=1):m.set(x,y,z,mat)


def disk(m,cx,cz,y,r,mat):
    for x in range(cx-r,cx+r+1):
        for z in range(cz-r,cz+r+1):
            if (x-cx)**2+(z-cz)**2<=r*r:m.set(x,y,z,mat)


def steps(m,x0,x1,z0,start_floor,end_floor,mat='stone_brick',south=True):
    direction=1 if south else -1
    for i in range(end_floor-start_floor):
        z=z0+direction*i;y=start_floor+1+i
        solid={'stone_brick':'stone_bricks','quartz':'quartz_block','deepslate_brick':'deepslate_bricks'}.get(mat,mat)
        if y>start_floor+1:m.box((x0,start_floor+1,z),(x1,y-1,z),solid)
        m.box((x0,y,z),(x1,y,z),mat+'_stairs[facing='+('south' if south else 'north')+']')
        m.box((x0,y+1,z),(x1,y+3,z),'air')


def work(m,key,x,y,z,name):m.point(key,'work',(x,y,z),name,approach=(x,y,z))


def arrival(m,x):m.point('street','entrance',(x,2,0),'临街步行入口',facing='north')


def window_rose(m,cx,cy,z,r,glass='purple_stained_glass',stone='stone_bricks'):
    for dx in range(-r-1,r+2):
        for dy in range(-r-1,r+2):
            d=math.hypot(dx,dy)
            if d<=r+1:
                block=stone if d>r-0.5 or (abs(dx)==abs(dy) and d>1) or dx==0 or dy==0 else glass
                m.set(cx+dx,cy+dy,z,block)


def shelf(m,x,y,z,width=5,wood='spruce',block='bookshelf'):
    m.box((x,y,z),(x+width-1,y+2,z),block)
    for xx in (x-1,x+width):m.box((xx,y,z),(xx,y+3,z),wood+'_log')
    m.box((x-1,y+3,z),(x+width,y+3,z),wood+'_slab')


def table(m,x,y,z,width=5,wood='spruce'):
    for xx in (x,x+width-1):m.set(xx,y,z,wood+'_fence')
    m.box((x,y+1,z),(x+width-1,y+1,z),wood+'_slab[type=top]')
    for xx in range(x,x+width,2):
        m.set(xx,y,z-1,wood+'_stairs[facing=south]');m.set(xx,y,z+1,wood+'_stairs[facing=north]')


def bed(m,key,x,y,z,color='white'):
    m.bed(x,y,z,color,'north');m.point(key,'bed',(x,y,z),'床位',approach=(x+1,y,z))
    m.set(x-1,y,z,'barrel');m.set(x-1,y+1,z,'lantern')


def tree(m,x,z,h=17,r=5,wood='oak',leaves='oak_leaves'):
    line(m,(x,2,z),(x+1,h,z),wood+'_log',1 if h>20 else 0)
    for dx,dz,dy in ((-r,0,-1),(r,1,1),(0,-r,0),(1,r,-2),(-1,1,3)):
        a=(x+dx,h+dy,z+dz);curve(m,((x,h-7,z),(x+dx//3,h+dy,z+dz//3),a),wood+'_log')
        for xx in range(-4,5):
            for yy in range(-2,3):
                for zz in range(-4,5):
                    p=(a[0]+xx,a[1]+yy,a[2]+zz)
                    if xx*xx+zz*zz+2*yy*yy<=17 and m.inside(p) and m.blocks.get(p,('minecraft:air',))[0]=='minecraft:air':m.set(*p,leaves+'[persistent=true]')
