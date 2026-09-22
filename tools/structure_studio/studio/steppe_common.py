"""Stable portable-camp components shared by reviewed service and garden assets."""
from functools import partial
from math import sqrt
from .model import Model
from .components import bench, shelf, pendant
from .steppe_caravans import canopy


def base(key,name,size,condition,source='steppe_life'):
    m=Model(key,name,size,family=key.rsplit('-v',1)[0],civilization='游牧',role='fill',terrain={
        '选址':condition,'资源与季节':'须接已有水草与补给路线，储槽为外部运水；按当地风向使入口背风，长季定居与短期停驻明确区分。',
        '高程':'默认入口脚底Y=3；坡台住宅Y=5由实体台阶接入，见点位。',
        '落地限制':'仅整理模板营地占地，不推平外部草坡；留出畜群、货车或来客外接通路。',
        '运行边界':'原版静态空间与作者点位，未接入动物、交易、季节迁徙或运行时生成。'})
    m.meta.update(source='tools/structure_studio/studio/'+source+'.py:'+key,roof_min_y=7,
        floors=[dict(name='营地生活层',y=2,max_y=6)],preview_context=dict(kind='flat',land_surface_y=3,bed_y=-1,padding=3,surface='grass'))
    return m


def ground(m,x0,z0,x1,z1,f=2,material='grass_block'):
    m.box((x0,0,z0),(x1,f,z1),'dirt');m.box((x0,f,z0),(x1,f,z1),material)
    m.box((x0,f+1,z0),(x1,m.size[1]-1,z1),'air')


def entry(m,x,z,y=3,key='entry'):
    m.point(key,'entrance',(x,y,z),'背风营路入口',facing='south')
    m.meta['connections'].append(dict(kind='pedestrian',pos=[x,y-1,z],direction='south',clearance=[3,3],note='接已有营路；人畜与车辆另留净空'))


def point(m,key,x,z,block,label,f=2,approach=None,kind='work'):
    m.set(x,f+1,z,block);m.point(key,kind,(x,f+1,z),label,approach=approach or (x,f+1,z+1))


def cabinet(m,key,x,z,w=3,f=2,label='分类物资柜'):
    shelf(m,x,f+1,z,w,'dark_oak');m.point(key,'storage',(x+1,f+2,z),label,approach=(x+1,f+1,z+1))


def dining(m,key,x,z,w=3,f=2,label='用餐与起居桌'):
    m.box((x,f+1,z),(x+w-1,f+1,z+1),'dark_oak_slab[type=top]')
    m.set(x+w-1,f+2,z,'flower_pot')
    bench(m,x,f+1,z-2,w,'south','dark_oak');bench(m,x,f+1,z+3,w,'north','dark_oak')
    m.point(key,'work',(x,f+1,z),label,approach=(x-1,f+1,z))


def tent(m,x0,z0,w,d,f=2,color='orange'):
    cx=x0+w//2;cz=z0+d//2;rx=w//2;rz=d//2
    inside=lambda x,z: ((x-cx)/rx)**2+((z-cz)/rz)**2<=1.001
    for x in range(x0,x0+w):
        for z in range(z0,z0+d):
            if not inside(x,z):continue
            m.box((x,0,z),(x,f,z),'dirt');m.set(x,f,z,'dark_oak_planks')
            m.box((x,f+1,z),(x,f+11,z),'air')
            edge=any(not inside(x+dx,z+dz) for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)])
            if edge:
                m.box((x,f+1,z),(x,f+4,z),'white_wool');m.set(x,f+2,z,color+'_wool')
            dist=sqrt(((x-cx)/rx)**2+((z-cz)/rz)**2)
            yy=f+5+int((1-dist)*4)
            m.set(x,yy,z,color+'_wool' if x==cx or z==cz else 'white_wool')
    for x,z in [(cx-rx,cz),(cx+rx,cz),(cx,cz-rz)]:
        m.box((x,f+1,z),(x,f+5,z),'dark_oak_log[axis=y]')
    # Full transverse ridge support at head height; no interior pole competes with furniture.
    m.box((cx-rx,f+5,cz),(cx+rx,f+5,cz),'dark_oak_log[axis=x]')
    m.box((cx-1,f+1,cz+rz-1),(cx+1,f+3,cz+rz),'air')
    m.box((cx-1,f+4,cz+rz),(cx+1,f+4,cz+rz),color+'_wool')
    pendant(m,cx,f+4,cz,f+9)
    return cx,cz
