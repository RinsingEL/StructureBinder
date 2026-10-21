"""New versions only: compact housing/barracks and authored public employment."""
from functools import partial
from .european_village import base,room_shell,windows,entry,roof,stair,bed,light,station
from .european_revision2 import BUILDERS as OLD


def compact_street():
    m=base(5,2,'挑楼街屋 · 紧凑三层三户',13,17,29)
    b=(1,2,11,15)
    for floor in (1,6,11):
        room_shell(m,b,floor,name=f'{1+(floor-1)//5}层住户');windows(m,b,floor+2)
        for x in (3,5,7):bed(m,x,floor+1,13,f'bed_{floor}_{x}')
        m.set(2,floor+1,6,'barrel[facing=east]')
        m.set(2,floor+1,4,'smoker[facing=east]')
        # Private flat behind a partition; stair circulation remains shared.
        m.box((8,floor+1,3),(8,floor+3,14),'spruce_planks')
        m.box((8,floor+1,10),(8,floor+2,10),'air')
        light(m,5,floor+4,9);light(m,10,floor+4,10)
    roof(m,b,16)
    entry(m,9)
    stair(m,10,4,1);stair(m,10,4,6)
    m.meta.update(floors=[{'name':f'{i+1}层住户','y':1+5*i,'max_y':5+5*i} for i in range(3)],roof_min_y=16,
                  differences=['旧35×47改为13×17；三户各三床，公共楼梯与户内空间通过隔墙区分，旧版保留。'])
    return m


def barracks():
    m=base(21,2,'守卫营房 · 双层二十四床',19,23,29,('驻守','集体居住'))
    b=(1,2,17,21)
    for floor in (1,6):
        room_shell(m,b,floor,name='守卫寝室');windows(m,b,floor+2)
        for z in (8,13,18):
            for x in (3,5,9,11):bed(m,x,floor+1,z,f'bed_{floor}_{x}_{z}')
        for z in (5,11,17):light(m,7,floor+4,z)
        light(m,15,floor+4,12)
    roof(m,b,11,'stone_brick');entry(m,9);stair(m,16,4)
    station(m,2,2,4,'lectern[facing=east]','营房值班')
    m.meta.update(floors=[{'name':'下层寝室','y':1,'max_y':5},{'name':'上层寝室','y':6,'max_y':10}],roof_min_y=11,
                  differences=['24张真实住床、实体楼梯和中央通路，操练场独立于模型。'])
    return m


def public_revision(number):
    m=OLD[f'EU-{number:02d}-v01']()
    m.meta.update(id=f'EU-{number:02d}-v02',workplace_policy='authored',
        source='tools/structure_studio/studio/european_revisions.py:BUILDERS')
    # Existing work markers were also used for generic circulation. Relabel them
    # explicitly rather than counting every decorative vanilla work block.
    roles={2:['神父礼仪主持'],3:['书记接待','官员议事'],15:['过秤员','行会职员'],16:['守钟人'],18:['面包师','店员']}
    points=[p for p in m.meta['points'] if p['kind']=='work']
    for p,name in zip(points,roles[number]):p.update(name=name,use='staff')
    extras={2:('执事',(35,4,64)),3:('档案书记',(32,9,30))}
    if number in extras:
        name,pos=extras[number];m.point('extra_staff','work',pos,name,approach=pos,use='staff')
    m.meta['differences'].append('2026-10-21 按实际岗位命名并启用 authored 工位策略，装饰工作方块不自动增加容量；旧版保留。')
    return m


BUILDERS={'EU-05-v02':compact_street,'EU-21-v02':barracks}
BUILDERS.update({f'EU-{n:02d}-v02':partial(public_revision,n) for n in (2,3,15,16,18)})
