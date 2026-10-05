"""S13 freestanding landscape: three different tree silhouettes and a flower bed."""
from .chinese_daily import daily


def foliage(m,cx,cy,cz,rx,ry,rz,block):
    for x in range(cx-rx,cx+rx+1):
        for y in range(cy-ry,cy+ry+1):
            for z in range(cz-rz,cz+rz+1):
                if ((x-cx)/max(1,rx))**2+((y-cy)/max(1,ry))**2+((z-cz)/max(1,rz))**2<=1.1:
                    m.set(x,y,z,block+'[persistent=true]')


def tree(kind):
    n,name={'pine':(26,'迎客松'),'willow':(27,'垂柳'),'blossom':(29,'花树')}[kind]
    m=daily(n,'中式景观 · '+name,23,23,h=25,role='structure',terms=['树木观赏'],tags=['landscape'])
    m.box((1,1,1),(21,1,21),'grass_block')
    m.box((10,1,0),(12,1,5),'stone_bricks')
    if kind=='pine':
        m.box((11,2,11),(11,11,11),'spruce_log')
        m.box((7,9,11),(16,9,11),'spruce_log[axis=x]')
        m.box((8,9,9),(8,12,11),'spruce_log');m.box((16,9,11),(16,11,13),'spruce_log')
        for cx,cy,cz,rx in ((8,13,9,5),(16,12,13,4),(11,17,11,4),(11,20,11,2)):
            foliage(m,cx,cy,cz,rx,1,3,'spruce_leaves')
        m.box((11,12,11),(11,20,11),'spruce_log')
    elif kind=='willow':
        m.box((10,2,11),(11,12,12),'oak_log')
        for x,z in ((6,7),(16,8),(6,16),(16,16)):
            for t in range(6):
                xx=round(11+(x-11)*t/5);zz=round(11+(z-11)*t/5)
                m.set(xx,10+t//2,zz,'oak_log')
            foliage(m,x,13,z,4,2,4,'oak_leaves')
            for dx,dz in ((-3,0),(3,0),(0,-3),(0,3)):
                m.box((x+dx,5,z+dz),(x+dx,12,z+dz),'oak_leaves[persistent=true]')
    else:
        m.box((11,2,11),(11,10,11),'cherry_log')
        for x,z,y in ((6,8,11),(16,8,12),(7,16,12),(16,16,10),(11,11,15)):
            for t in range(6):
                m.set(round(11+(x-11)*t/5),8+t//2,round(11+(z-11)*t/5),'cherry_log')
            foliage(m,x,y,z,4,2,4,'cherry_leaves')
    m.box((7,2,4),(9,2,4),'spruce_stairs[facing=north]')
    m.point('view','circulation',(11,2,4),'树前观赏站位')
    m.meta.update(roof_min_y=None,floors=[],source='tools/structure_studio/studio/chinese_landscape.py:BUILDERS',
                  design_notes=['完整实体树干和冠幅；持久树叶不依赖随机生长。树种是原版方块的造型表达，图审核对每种树的独立轮廓。'])
    return m


def flower_bed():
    m=daily(28,'中式景观 · 月牙花坛',23,21,h=12,role='structure',terms=['花草观赏','园艺养护'],tags=['landscape'])
    m.box((1,1,1),(21,1,19),'grass_block')
    for x in range(3,20):
        for z in range(5,18):
            r=(x-11)**2+(z-11)**2
            if 20<=r<=57 and z>=8:
                m.set(x,1,z,'moss_block')
                if (x+z)%3==0:m.set(x,2,z,'allium' if x%2 else 'azure_bluet')
                if r>=46:m.set(x,2,z,'stone_brick_slab')
    m.box((9,1,0),(13,1,8),'stone_bricks')
    m.box((9,2,12),(13,2,12),'spruce_stairs[facing=north]')
    m.set(5,2,6,'chiseled_stone_bricks');m.set(5,3,6,'lantern')
    m.point('garden_seat','circulation',(11,2,10),'花坛内侧休憩通道')
    m.meta.update(roof_min_y=None,floors=[],source='tools/structure_studio/studio/chinese_landscape.py:BUILDERS')
    return m


BUILDERS={'CH-26-v01':lambda:tree('pine'),'CH-27-v01':lambda:tree('willow'),
          'CH-28-v01':flower_bed,'CH-29-v01':lambda:tree('blossom')}
