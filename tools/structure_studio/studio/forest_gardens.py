"""Six field plans, four supported vine gardens and four working/drying sheds."""
from functools import partial
from .forest_symbiosis import base,ground,path,tree,lodge,pergola,entry,station,storage
from .components import bench,crate_stack,pendant
from .samples import railing


def crop_plot(m,key,x0,z0,x1,z1,crop='wheat',f=2):
    path(m,x0-1,z0-1,x1+1,z1+1,f)
    waters={(x,z) for x in set([*range(x0+3,x1+1,7),max(x0,x1-3)]) for z in set([*range(z0+3,z1+1,7),max(z0,z1-3)])}
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            if (x,z) in waters:m.set(x,f,z,'water[level=0]')
            else:
                m.set(x,f,z,'farmland[moisture=7]');m.set(x,f+1,z,f'{crop}[age={3 if crop=="beetroots" else 7}]')
    m.point(key,'work',(x0,f+1,z0),crop+'种植与采收畦',approach=(x0-1,f+1,z0))
    m.room(key,'林隙作物畦',(x0,f+1,z0),(x1,f+3,z1),'实体耕地、水眼、边缘木质作业路；需实际足够日照')


FARMS={
 1:((29,17,41),[(4,8,10,35,'wheat',2),(18,12,24,30,'carrots',2)],'双长畦','狭长林隙中的两条不同长度田畦，中间保留贯通林路'),
 2:((41,26,33),[(4,7,13,27,'potatoes',2),(18,20,35,27,'carrots',2)],'曲角边田','L形田地绕开东北树根，分别种植地下块茎和蔬菜'),
 3:((37,18,37),[(4,8,13,16,'wheat',2),(23,8,32,16,'carrots',2),(4,23,13,31,'potatoes',2),(23,23,32,31,'beetroots',2)],'四季分畦','四个独立短畦由十字作业路分开，适合开阔林缘日照地'),
 4:((39,20,42),[(4,7,13,17,'carrots',2),(25,7,34,17,'beetroots',2),(5,26,14,36,'potatoes',4),(25,26,34,36,'wheat',4)],'缓坡阶田','南侧高两格的实心保土台，中央宽阶连接上下田间道路'),
 5:((43,21,34),[(4,9,14,27,'wheat',2),(19,9,29,27,'potatoes',2)],'收获棚田','两个田块连接东侧分拣棚，区分采收、短存与通行'),
 6:((39,23,35),[(4,9,14,16,'carrots',2),(4,24,14,30,'beetroots',2)],'药花育圃','西侧菜畦与东侧示范药花格院分开；原版花卉只作药材种植外形表达'),
}


def farm(v):
    size,plots,title,note=FARMS[v]
    m=base(f'FS-F02-v{v:02d}',title+' · 林缘农田与药圃',size,note)
    m.meta['source']='tools/structure_studio/studio/forest_gardens.py:farm'
    ground(m,2,2,size[0]-3,size[2]-3)
    if v==4:
        ground(m,2,23,36,39,4)
        for z,y in ((21,3),(22,4)):
            m.box((17,2,z),(21,y,z),'mossy_cobblestone')
            for x in range(17,22):m.set(x,y,z,'spruce_stairs[facing=south]')
        m.meta['preview_context'].update(kind='slope',rise=2,run=22,slope_origin_z=21)
        m.meta['floors']=[dict(name='下台与上台作物',y=2,max_y=7)]
    for i,args in enumerate(plots):crop_plot(m,'plot'+str(i),*args)
    ex=size[0]//2
    if v==1:ex=14
    if v==2:
        tree(m,31,10,21,4);ex=16
    if v==5:
        pergola(m,33,9,39,27)
        storage(m,'harvest',34,3,25,4,'临时收获与包装物')
        station(m,'sort',35,3,12,'crafting_table','棚下收获分拣')
        m.room('sort','采收分拣棚',(34,3,10),(38,7,26),'避雨分拣、周转果蔬和工具')
        ex=17
    if v==6:
        for x,z in ((22,10),(29,10),(22,23),(29,23)):
            path(m,x-1,z-1,x+4,z+4)
            for xx in range(x,x+4):
                for zz in range(z,z+4):
                    m.set(xx,2,zz,'moss_block');m.set(xx,3,zz,'cornflower' if x==22 else 'allium')
        m.point('herb','work',(22,3,10),'示范药花种植格',approach=(21,3,10))
        ex=18
    station(m,'tools',4,3,4,'composter','整地与植物残余收集',approach=(4,3,5))
    entry(m,ex,2)
    m.meta['agriculture']=dict(type='fixed_template',crops=sorted({p[4] for p in plots}),layout=note,
        water='每个田块独立水眼，与最终NBT四格水源核对；实际生长、光照与季节另验')
    m.meta['design_notes']=['完整封边的独立田块，可直接读出田埂、种植、水眼与工作区；不代表沿自然地形自动延展的大田。']
    m.meta['differences']=[note]
    return m


def vine_strip(m,key,x0,z0,x1,z1):
    path(m,x0,z0,x1,z1)
    for x in (x0,x1):
        for z in range(z0,z1+1,4):
            m.box((x,3,z),(x,7,z),'stripped_dark_oak_log[axis=y]')
            vx=x+1 if x==x0 else x-1
            m.set(vx,2,z,'rooted_dirt')
            m.box((vx,3,z),(vx,6,z),'vine[west=true]' if x==x0 else 'vine[east=true]')
    for z in range(z0,z1+1):
        for x in (x0,x1):m.set(x,7,z,'stripped_dark_oak_log[axis=z]')
    for z in range(z0,z1+1,4):
        m.box((x0,7,z),(x1,7,z),'stripped_dark_oak_log[axis=x]')
        for x in range(x0+1,x1):
            m.set(x,8,z,'oak_leaves[persistent=true,distance=1]')
            if x%2:m.set(x,6,z,'small_amethyst_bud[facing=down]')
    m.point(key,'work',(x0+1,6,z0+4),'藤果采收',approach=((x0+x1)//2,3,z0+3))
    m.room(key,'完整独立藤架',(x0,3,z0),(x1,7,z1),'落地棚柱、纵横梁、叶蔓与采收走道')


def packing_table(m,key,x,z,w=4,d=2,label='分拣与扎包工作台'):
    """A supported work surface with labelled standing space on its north side."""
    m.box((x,3,z),(x+w-1,3,z+d-1),'spruce_slab[type=top]')
    for xx,zz in ((x,z),(x+w-1,z),(x,z+d-1),(x+w-1,z+d-1)):
        m.set(xx,3,zz,'stripped_spruce_log[axis=y]')
    m.set(x,4,z,'flower_pot');m.set(x+w-1,4,z,'lantern[hanging=false]')
    m.point(key,'work',(x+1,4,z),label,approach=(x+1,3,z-1))


def basket_group(m,key,x,z,label):
    crate_stack(m,x,3,z,w=3,d=2,h=2)
    m.point(key,'storage',(x,3,z),label,approach=(x,3,z-1))


def vineyard(v):
    sizes={1:(25,18,35),2:(37,18,34),3:(39,19,37),4:(37,22,34)}
    notes={1:'单列长棚适合狭长林缘，前后开口连接采收道',2:'双列棚之间留宽转运路，适合有充分日照的开敞林缘',3:'L形棚架围绕分拣小院，保留转角处转身和工具作业区',4:'靠独立背墙的棚架与封闭工具间组合，适合院边暖向地块'}
    m=base(f'FS-F03-v{v:02d}',['长叶单棚','双列果廊','曲廊果院','背墙暖棚'][v-1],sizes[v],notes[v])
    m.meta['source']='tools/structure_studio/studio/forest_gardens.py:vineyard'
    sx,sy,sz=sizes[v];ground(m,2,2,sx-3,sz-3)
    if v==1:vine_strip(m,'vine',7,7,17,29)
    if v==2:
        vine_strip(m,'west',5,7,13,27);vine_strip(m,'east',23,7,31,27)
    if v==3:
        vine_strip(m,'long',5,7,13,30);vine_strip(m,'short',19,23,33,31)
    if v==4:
        vine_strip(m,'wall',5,8,13,28)
        m.box((4,2,8),(4,7,28),'mud_bricks')
        lodge(m,20,10,31,26,kind='gable')
        storage(m,'toolstore',22,3,24,7,'采收剪具、绳索与包装')
        station(m,'wash',23,3,14,'water_cauldron[level=3]','院内清洗面')
        m.box((21,3,13),(22,3,15),'spruce_planks')
        m.set(22,4,14,'tripwire_hook[facing=east]')
        packing_table(m,'pack',26,16,4,2,'清洗后的藤果挑选与包装台')
        basket_group(m,'baskets',22,20,'采收空篮与待发果箱')
        m.set(29,3,21,'barrel[facing=up]');m.set(29,4,21,'flower_pot')
        m.box((30,3,12),(30,5,14),'spruce_planks')
        m.set(29,4,13,'tripwire_hook[facing=west]')
        m.room('store','采收工具间',(21,3,11),(30,7,25),'清洗台、分拣包装、采收剪具、空篮与果箱；从前门沿中央通道进入')
    station(m,'sort',sx-7,3,5,'composter','采收整理',approach=(sx-8,3,5))
    entry(m,sx//2,2)
    m.meta.update(roof_min_y=8,floors=[dict(name='采收与工具使用层',y=2,max_y=6)])
    m.meta['design_notes']=['叶蔓依托落地梁架；果串采用原版紫晶芽外观，仅作藤果示意。实际葡萄品种、温度、季节和生产按整合包配置。']
    m.meta['differences']=[notes[v]]
    return m


def shed(v):
    size={1:(27,19,27),2:(37,27,32),3:(37,21,30),4:(39,24,35)}[v]
    notes={1:'独立四面开敞工具棚，适合生产点旁的平坦林隙',2:'树旁棚架避开完整根盘，前侧采伐工具与后侧晾材分开',3:'一侧挡风背墙的长棚，适合院边晾药、编织物和短时货物周转',4:'开放维修棚与封闭干燥储物屋组成L形生产院，适合较大林务组团'}
    m=base(f'FS-F04-v{v:02d}',['轻枝工具棚','根旁晾材棚','院边晾药棚','修枝储物院'][v-1],size,notes[v])
    m.meta['source']='tools/structure_studio/studio/forest_gardens.py:shed'
    sx,sy,sz=size;ground(m,2,2,sx-3,sz-3)
    b={1:(5,7,21,21),2:(4,7,21,25),3:(5,7,31,23),4:(5,6,21,28)}[v]
    x0,z0,x1,z1=b;pergola(m,*b)
    if v==2:tree(m,29,21,22,4)
    if v==3:m.box((x0,3,z1),(x1,6,z1),'spruce_planks')
    station(m,'tools',x0+3,3,z0+3,'crafting_table','工具修整与配件整理')
    m.set(x0+6,3,z0+3,'grindstone[face=floor,facing=south]')
    storage(m,'stock',x0+2,3,z1-1,x1-x0-3,'工具、绳索与干燥物资')
    for x in range(x0+2,x1-1,3):
        m.set(x,5,z0+7,'spruce_trapdoor[half=top,open=false]')
        m.box((x,6,z0+7),(x,7,z0+7),'chain[axis=y]')
    m.point('dry','work',(x0+3,5,z0+7),'悬挂晾晒与检视',approach=(x0+3,3,z0+6))
    m.room('shed','开放操作与晾晒棚',(x0+1,3,z0+1),(x1-1,7,z1-1),'维修台、磨具、悬挂晾架、干料架与独立柱脚')
    if v==4:
        lodge(m,25,13,34,29)
        storage(m,'drygoods',27,3,27,5,'干燥敏感物料')
        m.box((26,3,18),(27,5,23),'spruce_planks')
        for z in (18,20,22):m.set(27,4,z,'barrel[facing=east]')
        m.point('sidegoods','storage',(27,4,20),'绳索、金属配件与防潮分格柜',approach=(28,3,20))
        packing_table(m,'issue',30,17,3,2,'室内配料发放与维修登记台')
        m.set(30,3,23,'barrel[facing=up]');m.set(31,3,23,'barrel[facing=up]')
        m.room('room','封闭干料间',(26,3,14),(33,7,28),'侧柜和后架分别保存配件与干料，前段登记发料，中央通道联系门口与取料位')
    # Each shed has a different production arrangement rather than only a few
    # symbolic blocks at the edges of an otherwise empty platform.
    if v==1:
        packing_table(m,'assembly',14,10,5,2,'小型工具组装与绳结检修台')
        basket_group(m,'parts',7,17,'待修木柄、配件箱与周转材料')
        bench(m,15,3,17,3,'north')
        m.set(18,3,16,'loom[facing=north]')
        m.point('weave','work',(18,3,16),'绑带和背篓编补',approach=(19,3,16))
        m.room('assembly','前段组装与后段编补',(13,3,9),(19,6,18),'工作台、临时坐席和编补角，两侧绕行到后架')
    elif v==2:
        for x,z in ((6,19),(8,19),(10,22)):
            m.box((x,3,z),(x+1,4,z+1),'stripped_spruce_log[axis=z]')
        m.point('timber','storage',(6,3,19),'分批架空的短木料',approach=(6,3,18))
        packing_table(m,'measure',14,10,5,2,'林材量尺与端部记录台')
        m.box((14,3,19),(18,3,21),'spruce_planks')
        m.set(15,4,20,'stonecutter[facing=north]')
        m.point('cut','work',(15,4,20),'固定切割台外形与落料整理',approach=(15,3,18))
        m.room('timberyard','后段晾材和整料',(5,3,17),(19,6,23),'短料分堆、固定切割台与侧向搬运道；设备只作静态表达')
    elif v==3:
        packing_table(m,'select',8,17,5,2,'药材挑选与纸包编号台')
        packing_table(m,'bundle',23,10,5,2,'编织物扎包与晾前整理')
        for x,flower in ((17,'potted_fern'),(20,'potted_dandelion'),(23,'potted_allium')):
            m.set(x,3,18,'barrel[facing=up]');m.set(x,4,18,flower)
        m.point('samples','storage',(20,3,18),'分类干料与药花样本',approach=(20,3,17))
        m.room('sorting','双端整理与干料分类',(7,3,9),(29,6,20),'西侧选药、东侧扎包，中部晾晒观察与分类容器，不混用湿料和包装')
    else:
        packing_table(m,'repair',14,10,5,3,'较长枝具与背架维修台')
        basket_group(m,'incoming',7,22,'院内待修工具与回收配件')
        m.box((15,3,22),(18,3,24),'stripped_spruce_log[axis=z]')
        m.point('longstock','storage',(15,3,22),'长柄与架材待用区',approach=(15,3,21))
        m.room('repairbay','前段维修与后段备料',(6,3,9),(19,6,25),'长台维修、晾架与后端回收料分区，院内步道通向独立干料屋')
    pendant(m,(x0+x1)//2,6,z0+1,8)
    entry(m,13,2)
    m.meta['differences']=[notes[v]]
    m.meta['design_notes']=['悬挂晾架由链条接屋顶；工具、晾晒与材料存取均有可达站位。',
        '按工具组装、晾材切整、晾药扎包、维修发料四种用途配置工作台、材料和周转设施；保持从入口到各工作面的连续通路。']
    return m


BUILDERS={f'FS-F02-v{i:02d}':partial(farm,i) for i in range(1,7)}
BUILDERS.update({f'FS-F03-v{i:02d}':partial(vineyard,i) for i in range(1,5)})
BUILDERS.update({f'FS-F04-v{i:02d}':partial(shed,i) for i in range(1,5)})
