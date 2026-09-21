"""Small architectural components, not whole-building reskins."""
from .samples import pane, railing


def shell(m,a,b,wall,floor="oak_planks",ceiling=None):
    x0,y0,z0=a;x1,y1,z1=b
    m.box(a,b,wall)
    m.box((x0+1,y0+1,z0+1),(x1-1,y1,z1-1),"air")
    m.box((x0,y0,z0),(x1,y0,z1),floor)
    if ceiling:m.box((x0,y1+1,z0),(x1,y1+1,z1),ceiling)


def window(m,a,b,axis="x",color="glass"):
    for x in range(a[0],b[0]+1):
        for y in range(a[1],b[1]+1):
            for z in range(a[2],b[2]+1):pane(m,x,y,z,axis,color)


def column(m,x,z,y0,y1,shaft="stone_bricks",cap="polished_andesite"):
    m.box((x,y0,z),(x,y1,z),shaft)
    m.set(x,y0,z,cap);m.set(x,y1,z,cap)


def bench(m,x,y,z,length=3,facing="south",wood="spruce"):
    for i in range(length):
        m.set(x+i,y,z,f"{wood}_stairs[facing={facing},half=bottom,shape=straight,waterlogged=false]")
    for px in (x-1,x+length):
        m.set(px,y,z,f"{wood}_trapdoor[facing={'east' if px<x else 'west'},half=bottom,open=true,powered=false,waterlogged=false]")


def pendant(m,x,y,z,support_y,soul=False):
    support_y=next((yy for yy in range(y+1,m.size[1])
                   if m.blocks.get((x,yy,z),("minecraft:air",()))[0] not in
                   {"minecraft:air","minecraft:cave_air","minecraft:void_air"}),support_y)
    m.set(x,y,z,f"{'soul_' if soul else ''}lantern[hanging=true,waterlogged=false]")
    if support_y>y+1:m.box((x,y+1,z),(x,support_y-1,z),"chain[axis=y,waterlogged=false]")


def hip_roof(m,x0,x1,z0,z1,y,material="waxed_cut_copper",tiers=None):
    tiers=tiers or min((x1-x0)//2,(z1-z0)//2)+1
    for i in range(tiers):
        xa,xb,za,zb=x0+i,x1-i,z0+i,z1-i
        if xa>xb or za>zb:break
        for x in range(xa,xb+1):
            m.set(x,y+i,za,f"{material}_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
            m.set(x,y+i,zb,f"{material}_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
        for z in range(za+1,zb):
            m.set(xa,y+i,z,f"{material}_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
            m.set(xb,y+i,z,f"{material}_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]")
        if i==tiers-1 and xa+1<=xb-1 and za+1<=zb-1:
            block={"brick":"bricks","stone_brick":"stone_bricks","polished_blackstone_brick":"polished_blackstone_bricks"}.get(material,material)
            m.box((xa+1,y+i,za+1),(xb-1,y+i,zb-1),block)


def arch_front(m,x,y,z,width,height,material="smooth_sandstone",opening=True):
    """Stepped three-centred arch; bounds include the piers."""
    for px in range(x,x+width):
        edge=min(px-x,x+width-1-px)
        rise=min(edge,2)
        if edge==0:m.box((px,y,z),(px,y+height-1,z),material)
        else:
            if opening:m.box((px,y,z),(px,y+height-4+rise,z),"air")
            m.set(px,y+height-3+rise,z,material)


def shelf(m,x,y,z,width=3,material="spruce",contents="barrel"):
    m.box((x,y,z),(x+width-1,y,z),f"{material}_planks")
    for px in range(x,x+width):
        m.set(px,y+1,z,"barrel[facing=south,open=false]" if contents=="barrel" else contents)
    m.box((x,y+2,z),(x+width-1,y+2,z),f"{material}_slab[type=top,waterlogged=false]")


def crate_stack(m,x,y,z,w=2,d=2,h=2):
    for xx in range(x,x+w):
        for zz in range(z,z+d):
            for yy in range(y,y+h):
                if yy==y+h-1 and (xx-x+zz-z)%3==1:continue
                m.set(xx,yy,zz,"barrel[facing=up,open=false]" if (xx+zz+yy)%2 else "spruce_planks")
