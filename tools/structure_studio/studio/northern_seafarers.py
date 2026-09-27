"""Cold-coast civic and specialist structures, each with a distinct working plan."""
from .model import Model
from .components import shell, window, bench, pendant, shelf, crate_stack


def base(key,name,w,d,h=27,role='key',shore=None):
    m=Model(key,name,(w,h,d),family=key.split('-v')[0],civilization='北欧',role=role,terrain={
        '选址':'稳定的寒地海湾背风岸台；避开潮涌、雪崩、海冰推挤和行洪通道。',
        '高程':'干燥主层脚底 Y=3，建筑基础底 Y=0；外部步行地面需接主层。',
        '保留空间':'完整独立模板，连同檐口、通路和本体配套保留；不可贴邻堵住工作面。',
        '风雪':'厚木围护、短风斗和陡坡屋面表达避风防雪；不模拟风雪、温度或雪荷载。'})
    m.box((1,0,1),(w-2,2,d-2),'cobblestone')
    m.box((1,2,1),(w-2,2,d-2),'gravel')
    m.box((1,3,1),(w-2,h-1,d-2),'air')
    m.meta.update(source='tools/structure_studio/studio/northern_seafarers.py:BUILDERS',
        roof_min_y=9,floors=[dict(name='岸台生活作业层',y=2,max_y=6)],
        preview_context=dict(kind='flat',land_surface_y=3,padding=5,surface='snow'))
    if shore is not None:
        m.meta['preview_context'].update(kind='shore',shore_z=shore,water_surface_y=2,bed_y=-2)
        m.meta['terrain']['岸线']=f'海在 +Z，示意岸线 Z={shore}、水面 Y=2；需校对潮差与实际船舶吃水。'
    return m


def lodge(m,x0,z0,x1,z1,y=2,height=5,roof='spruce'):
    """Closed timber hall with supported overhanging gable and ridge."""
    top=y+height
    shell(m,(x0,y,z0),(x1,top,z1),'spruce_planks','spruce_planks',ceiling='spruce_planks')
    for x in (x0,x1):
        for z in range(z0,z1+1,4):m.box((x,y+1,z),(x,top,z),'dark_oak_log[axis=y]')
        m.box((x,top+2,z0),(x,top+2,z1),'spruce_planks')
    for z in (z0,z1):m.box((x0,top+2,z),(x1,top+2,z),'spruce_planks')
    for i in range((x1-x0+4)//2):
        a,b=x0-1+i,x1+1-i
        if a>b:break
        for z in range(z0-1,z1+2):
            if a==b:m.set(a,top+2+i,z,f'{roof}_planks' if roof=='spruce' else 'deepslate_tiles')
            else:
                m.set(a,top+2+i,z,f'{roof}_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]')
                m.set(b,top+2+i,z,f'{roof}_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]')
        if i:
            for z in (z0,z1):m.box((a,top+1,z),(b,top+1+i,z),'spruce_planks')
    # Exposed end trusses and eaves break up large gables and read as real framing.
    center=(x0+x1)//2
    ridge=top+2+(x1-x0+2)//2
    for z in (z0,z1):
        m.box((x0,top,z),(x1,top,z),'dark_oak_log[axis=x]')
        m.box((center,top+1,z),(center,ridge-1,z),'dark_oak_log[axis=y]')
        for i in range(1,(x1-x0)//2+1):
            for x in (x0+i,x1-i):m.set(x,top+i,z,'dark_oak_log[axis=x]')
        if x1-x0>=8:
            for x in (center-2,center+2):m.set(x,top+2,z,'light_blue_stained_glass')
    for x in (x0,x1):m.box((x,top,z0),(x,top,z1),'dark_oak_log[axis=z]')
    for z in range(z0+3,z1-1,5):
        for x in (x0,x1):window(m,(x,y+3,z),(x,y+3,z+1),axis='z',color='light_blue_stained_glass')
    for z in range(z0+3,z1-1,7):pendant(m,(x0+x1)//2,top-1,z,top+1)
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],top+1)


def entry(m,key,x,z,y=3,wall=True):
    if wall:m.door(x,y,z,facing='north')
    m.point(key,'entrance',(x,y,z-1),'背风步行入口',facing='north')


def room(m,key,name,a,b,purpose):m.room(key,name,a,b,purpose)
def use(m,key,name,x,z,ax,az,y=3,kind='work'):
    m.point(key,kind,(x,y,z),name,approach=(ax,y,az),look_at=[x,y+1,z])


def counter(m,key,name,x,z,n=4,y=3,block='crafting_table'):
    m.box((x,y,z),(x+n-1,y,z),'spruce_planks')
    m.set(x,y,z,block);m.set(x+n-1,y+1,z,'lantern[hanging=false,waterlogged=false]')
    use(m,key,name,x,z,x,z+1,y)


def stores(m,key,name,x,z,n=4,y=3):
    shelf(m,x,y,z,n);use(m,key,name,x,z,x,z+1,y,kind='storage')


def sleep(m,key,x,z,n=2,y=3):
    for i in range(n):
        xx=x+i*3;m.bed(xx,y,z,'cyan',facing='north');m.set(xx,y,z+2,'barrel[facing=up,open=false]')
        use(m,f'{key}{i}','床铺与个人防寒用品',xx,z,xx+1,z,y,kind='bed')


def cook(m,key,x,z,y=3):
    counter(m,key,'备餐、热食与洗涤',x,z,4,y,'smoker[facing=south,lit=false]')
    m.set(x+2,y,z,'water_cauldron[level=3]')
    # Real continuous flue through ceiling and roof, capped above the ridge.
    top=max(yy for (xx,yy,zz),b in m.blocks.items() if xx==x and zz==z and b[0]!='minecraft:air')+2
    m.box((x,y+1,z),(x,top,z),'cobblestone')
    m.set(x,top+1,z,'cobblestone_slab[type=bottom,waterlogged=false]')


def dining(m,x,z,n=5,y=3):
    m.box((x,y,z),(x+n-1,y,z),'spruce_planks')
    for xx in range(x,x+n,2):m.set(xx,y+1,z,'flower_pot')
    bench(m,x,y,z-2,n,'south');bench(m,x,y,z+2,n,'north')


def steps(m,x,z,width,rise,y=3):
    for i in range(rise):
        m.box((x,0,z+i),(x+width-1,y+i-1,z+i),'cobblestone')
        m.box((x,y+i,z+i),(x+width-1,y+i,z+i),'stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]')


def rail(m,x0,z0,x1,z1,y):
    # Solid low parapets require no neighbor updates or floating connection arms.
    m.box((x0,y,z0),(x1,y,z1),'stripped_spruce_log[axis=x]' if z0==z1 else 'stripped_spruce_log[axis=z]')


def finish(m,note):
    m.meta['design_notes']=[note]
    m.meta['differences']=[note]
    for r in m.meta['rooms']:
        names=[p['name'] for p in m.meta['points'] if all(r['min'][i]<=p['pos'][i]<=r['max'][i] for i in range(3))]
        if names:r['purpose']+='；具体设施：'+'、'.join(names)
    return m


def boathouse():
    m=base('NS-01-v01','双舷作业长船屋',39,39,28,shore=33)
    lodge(m,4,3,23,29,height=7,roof='deepslate_tile')
    lodge(m,27,4,35,18);lodge(m,27,22,35,31)
    entry(m,'entry',8,3);entry(m,'tools_entry',31,4);entry(m,'watch_entry',31,22)
    # Wide seaward doors/slip; two walkways remain beside the cradle.
    m.box((11,3,29),(17,8,29),'air')
    m.box((11,2,30),(17,2,35),'spruce_planks')
    m.box((1,0,34),(37,2,37),'water[level=0]')
    m.box((1,0,34),(37,0,37),'gravel')
    for z in (32,35):
        for x in (10,18):m.box((x,0,z),(x,3,z),'spruce_log[axis=y]')
    # Longship hull: keel, narrowing bow/stern, ribs, cross-thwarts and repair gaps.
    m.box((14,3,8),(14,3,27),'dark_oak_log[axis=z]')
    for z in range(7,29):
        half=1 if z in (7,8,27,28) else 2
        m.box((14-half,4,z),(14+half,4,z),'dark_oak_planks')
        for x in (14-half,14+half):m.set(x,5,z,'dark_oak_stairs[facing='+('east' if x<14 else 'west')+',half=bottom,shape=straight,waterlogged=false]')
        if z%4==0:m.box((12,5,z),(16,5,z),'spruce_slab[type=bottom,waterlogged=false]')
    for z in (6,29):m.box((14,4,z),(14,7,z),'stripped_dark_oak_log[axis=y]')
    for z in (9,17,25):
        m.box((10,3,z),(18,3,z),'stripped_spruce_log[axis=x]')
        m.box((5,3,z),(5,9,z),'spruce_log[axis=y]');m.box((22,3,z),(22,9,z),'spruce_log[axis=y]')
        m.box((5,9,z),(22,9,z),'spruce_log[axis=x]')
    counter(m,'caulk','捻缝、刨修与树脂备料',6,5,5)
    counter(m,'fasten','紧固件与铆接工台',18,5,4,block='anvil[facing=north]')
    stores(m,'sail','折帆、索具与干燥备用件',5,26,5)
    m.box((20,3,13),(21,4,23),'stripped_spruce_log[axis=z]')
    use(m,'timber','分长存放船板',20,17,19,17,kind='storage')
    use(m,'hull','左舷检修走道与托架',12,17,10,16)
    m.point('right_aisle','circulation',(18,3,21),'右舷搬运通道',look_at=[14,5,21])
    counter(m,'joiner','独立木工备件工作台',28,6,6)
    stores(m,'parts','工具、楔块与小五金',28,16,5)
    counter(m,'assembly','船肋样板和小件拼装长台',28,11,3)
    m.set(33,3,11,'grindstone[face=floor,facing=west]');use(m,'sharpen','刃具维护',33,11,32,11)
    sleep(m,'watch',28,27);cook(m,'watch_food',28,29)
    m.box((28,3,24),(30,3,24),'spruce_planks');bench(m,28,3,23,3)
    room(m,'slip','长船托架及双舷修船廊',(5,3,4),(22,8,28),'船体静态修造展示，四周工作通道，前段捻缝铆接，后段帆索保管')
    room(m,'joinery','干燥木工作坊',(28,3,5),(34,7,17),'木工下料、刃具维护与零件保管')
    room(m,'watch','两人值守小屋',(28,3,23),(34,7,30),'值守轮休、炊事和防寒衣物')
    m.meta['terrain']['船舶']='船屋后端开口 7 格宽、6 格高，滑道朝 +Z；本体展示长船不对应可驾驶实体，实际船宽和潮汐拖曳另行核对。'
    return finish(m,'长船托架占据高跨主厅，双舷保留连续检修廊；陆侧捻缝与铆接台、独立干燥木工间和两人值守屋围成侧院，海侧为开敞滑道。')


def bathhouse():
    m=base('NS-04-v01','风斗围院公共浴屋',35,29,24)
    lodge(m,3,3,14,24);lodge(m,18,3,30,24)
    # Narrow sheltered link forms a dry entry court and a separate rear service yard.
    lodge(m,14,15,18,24,height=4)
    entry(m,'entry',8,3);m.door(14,3,19,facing='east');m.door(18,3,19,facing='east')
    m.box((4,3,10),(13,7,10),'spruce_planks');m.door(8,3,10)
    counter(m,'attendant','浴票、毛巾与更衣登记',4,5,4,block='lectern[facing=south,has_book=false,powered=false]')
    stores(m,'linen','干净毛巾与更换衣物',10,5,3)
    bench(m,5,3,8,6);m.set(12,3,8,'barrel[facing=up,open=false]')
    for z in (13,18):
        stores(m,f'locker{z}','分组更衣柜',4,z,3)
        bench(m,9,3,z+1,3)
    counter(m,'laundry','脏布收拣与清洗',4,22,4,block='water_cauldron[level=3]')
    # Wet hall split into a front heated soak and rear wash / boiler zone.
    m.box((20,2,6),(27,2,12),'stone_bricks');m.box((21,2,7),(26,2,11),'water[level=0]')
    rail(m,20,6,27,6,3);rail(m,20,12,27,12,3)
    m.set(20,3,9,'stone_brick_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]')
    use(m,'bath','浴池边清洁维护位',20,9,19,9)
    for z in (15,18):m.set(28,3,z,'water_cauldron[level=3]')
    use(m,'wash','入浴前冲洗水盆',28,15,27,15)
    bench(m,20,3,16,5);bench(m,20,3,20,4)
    use(m,'rest','热浴后的坐休与饮水',22,20,22,19)
    cook(m,'heat',25,22)
    stores(m,'fuel','炉间干柴与清洁工具',19,22,4)
    m.door(30,3,21,facing='east');m.point('fuel_entry','entrance',(31,3,21),'侧院供热维护入口')
    m.box((15,2,5),(17,2,11),'mossy_cobblestone');m.set(16,3,8,'water_cauldron[level=3]')
    room(m,'reception','风斗接待与布品发放',(4,3,4),(13,7,9),'短入口、登记、等候及干净布品')
    room(m,'change','干燥更衣与洗衣间',(4,3,11),(13,7,23),'两组更衣柜、长凳及分开收拣脏布的水台')
    room(m,'bath','供热浴池与休息厅',(19,3,4),(29,7,23),'前端下沉浴池，侧边冲洗，后端坐休、饮水、炉火与燃料')
    room(m,'link','避风干湿转换廊',(15,3,16),(17,6,23),'更衣后经有顶窄廊进入浴厅，不穿越服务水台')
    m.meta['terrain']['供水供热']='浴池与水盆仅静态清水；须有可用淡水与燃料，炉火烟道独立，排水处理和水温机制另行接入。'
    return finish(m,'干燥更衣长翼与湿浴长翼分开，后部窄廊连通；前部小院避风。入场登记、更衣、冲洗、泡浴、坐休与布品清洗各有实际家具及独立维护侧门。')


def epic_hall():
    m=base('NS-10-v01','三翼炉席史诗会堂',39,37,28)
    lodge(m,4,10,23,32,height=7);lodge(m,10,3,17,10,height=4)
    lodge(m,27,10,35,19);lodge(m,27,23,35,32)
    # Covered short transverse connectors sit between the main volume and service wings.
    for z in (14,27):
        m.box((23,2,z),(27,2,z+2),'spruce_planks')
        m.box((23,3,z),(27,5,z+2),'air')
        m.box((24,6,z),(26,6,z+2),'spruce_planks')
        for x in (24,26):m.box((x,3,z),(x,5,z),'spruce_log[axis=y]')
        for x in (23,27):m.door(x,3,z+1,facing='east')
    entry(m,'entry',13,3);m.door(13,3,10)
    bench(m,11,3,7,4);m.set(16,3,5,'barrel[facing=up,open=false]')
    for z in (15,23):
        dining(m,6,z,5);dining(m,17,z,5)
    # Central aisle leads directly to the storyteller; restrained enclosed hearths flank it.
    for x in (11,16):
        m.set(x,3,19,'campfire[facing=north,lit=false,signal_fire=false,waterlogged=false]')
        for xx in (x-1,x+1):m.set(xx,3,19,'cobblestone')
    m.box((9,2,29),(18,2,31),'stripped_spruce_log[axis=x]')
    counter(m,'speaker','史诗演述与卷册讲席',11,30,5,block='lectern[facing=north,has_book=false,powered=false]')
    use(m,'banquet','公共宴席及主通道',9,23,12,23)
    cook(m,'kitchen',28,12)
    counter(m,'prepare','切配与出餐台',28,17,6)
    stores(m,'food','冬季干粮与餐具',33,12,2)
    counter(m,'archive','航海谱系和史诗誊录',28,25,5,block='lectern[facing=south,has_book=false,powered=false]')
    for x in (28,32):shelf(m,x,3,30,3,contents='bookshelf')
    use(m,'books','分柜保管谱系与誊本',29,30,29,29,kind='storage')
    for z in (13,20,28):
        for x in (5,22):m.box((x,3,z),(x,9,z),'stripped_spruce_log[axis=y]')
        m.box((5,9,z),(22,9,z),'spruce_log[axis=x]')
    room(m,'vestibule','雪靴与披风风斗',(11,3,4),(16,6,9),'短门廊、换靴凳和个人物品暂存')
    room(m,'feast','四组宴席与演述长厅',(5,3,11),(22,9,31),'分组餐桌、中央听众通道、双封护炭盆与后端演述席')
    room(m,'kitchen','独立炊事与出餐翼',(28,3,11),(34,7,18),'炊事、清洗、切配、餐具与干粮，侧廊供餐')
    room(m,'archive','干燥谱系档案翼',(28,3,24),(34,7,31),'誊写工作台、分柜档案，远离炊事和火盆')
    return finish(m,'高跨宴席长厅以四组对坐桌和中央演述通路组织；前风斗收纳雪靴，东侧分开的炊事翼与档案翼通过短廊接入，避免餐饮与文献混杂。')


def supply_station():
    m=base('NS-02-v01','背风阶院冰原补给站',37,35,26,role='structure')
    lodge(m,3,4,14,21);entry(m,'entry',8,4)
    lodge(m,20,4,32,11,height=4);entry(m,'equip_entry',26,4)
    m.box((18,0,18),(33,5,31),'cobblestone');lodge(m,19,19,32,30,y=5,height=5)
    steps(m,24,15,3,3);m.box((24,5,18),(26,5,19),'spruce_planks');m.door(25,6,19)
    rail(m,18,31,33,31,6)
    for x in (18,33):rail(m,x,18,x,30,6)
    rail(m,18,18,23,18,6);rail(m,27,18,33,18,6)
    # Rear windbreak stops below openings; inner court stays clear for loaded sledges.
    m.box((2,3,32),(34,5,32),'cobblestone');m.box((2,3,23),(2,5,31),'cobblestone')
    sleep(m,'traveler',4,9,3);cook(m,'meal',4,18)
    dining(m,5,14,6);stores(m,'dryclothes','干燥披风、毛毯与医疗包',9,19,4)
    counter(m,'equip','雪鞋、滑橇与防寒装备维修',21,6,7)
    stores(m,'rope','绳索、灯油与备用设备',21,9,5)
    for x in (28,30):m.box((x,3,8),(x,3,10),'spruce_slab[type=bottom,waterlogged=false]')
    counter(m,'dispatch','高台物资登记与发放',20,21,5,y=6,block='lectern[facing=south,has_book=false,powered=false]')
    stores(m,'rations','分组食物储备',20,27,5,y=6)
    stores(m,'fuel','干燃料储备',27,27,4,y=6)
    crate_stack(m,22,6,24,3,1,2)
    use(m,'packed','装橇前已打包物资',23,24,23,23,y=6,kind='storage')
    m.box((29,6,21),(30,7,24),'coal_block');use(m,'bulkfuel','独立封存煤料',29,23,28,23,y=6,kind='storage')
    m.point('court','circulation',(18,3,14),'雪橇卸货与转向院',look_at=[25,6,20])
    room(m,'sleep','三人避寒与热食长屋',(4,3,5),(13,7,20),'睡眠、共同热食、干衣及应急用品')
    room(m,'equipment','低檐装备检修屋',(21,3,5),(31,6,10),'修补雪鞋滑橇、绳索、灯油与器材')
    room(m,'stores','后高台冬季物资库',(20,6,20),(31,10,29),'高台隔潮，粮食与燃料分侧，前台登记')
    m.meta['floors']=[dict(name='避风院与生活层',y=2,max_y=6),dict(name='后高台储备层',y=5,max_y=9)]
    m.meta['preview_context']=dict(kind='slope',land_surface_y=3,slope_origin_z=14,run=7,rise=1,padding=4,surface='snow')
    m.meta['terrain']['高程']='前院与生活脚底 Y=3，后库脚底 Y=6；三宽三级实体台阶相连。坡地需匹配前低后高并保留转向院。'
    m.meta['terrain']['选用']='仅选用于有实际寒地路线的背风停歇点；不作为普通住宅重复投放。'
    return finish(m,'三翼阶院面向来路，低处三人热食寝屋与装备维修屋夹雪橇转向院，后高台独立保存粮食燃料；三级宽阶连接，后墙挡风而不堵运输。')


def salvage_camp():
    m=base('NS-11-v01','浅湾残舟回收营',39,38,24,role='structure',shore=24)
    # Occupied huts stay landward; the back third is a bounded static shallows sample.
    lodge(m,3,3,14,15,height=4);lodge(m,23,3,34,13,height=4)
    entry(m,'entry',8,3);entry(m,'store_entry',28,3)
    sleep(m,'crew',4,8,3);cook(m,'cook',4,12)
    stores(m,'recovered','干燥回收物和工具柜',24,5,8)
    counter(m,'record','归属登记、回收记录与贵重物封存',24,10,7,block='lectern[facing=south,has_book=false,powered=false]')
    counter(m,'detail','小件干燥后清理与拼接台',26,8,5)
    bench(m,9,3,12,3)
    m.box((1,0,25),(37,2,36),'water[level=0]');m.box((1,0,25),(37,0,36),'gravel')
    # Pile-supported sorting quay and access finger; actual pier joins the dry yard.
    m.box((16,2,19),(34,2,29),'spruce_planks')
    m.box((10,2,27),(16,2,29),'spruce_planks')
    for x in (10,16,25,34):
        for z in (27,29):m.box((x,0,z),(x,2,z),'spruce_log[axis=y]')
    for x in (16,25,34):m.box((x,0,19),(x,2,19),'spruce_log[axis=y]')
    rail(m,17,29,34,29,3);rail(m,34,19,34,28,3)
    counter(m,'sort','漂洗后分拣与修复台',19,20,7)
    m.set(27,3,20,'water_cauldron[level=3]');use(m,'rinse','盐水物件淡水冲洗',27,20,27,21)
    for x,b in ((18,'stripped_spruce_log[axis=z]'),(23,'copper_block'),(28,'barrel[facing=up,open=false]')):
        m.box((x,3,25),(x+2,3,27),b)
    use(m,'timber','回收船板分组晾干',19,25,19,24,kind='storage')
    use(m,'metal','金属件分类存放',24,25,24,24,kind='storage')
    m.box((31,3,23),(31,9,23),'spruce_log[axis=y]');m.box((25,9,23),(31,9,23),'spruce_log[axis=x]')
    m.box((25,5,23),(25,8,23),'chain[axis=y,waterlogged=false]')
    m.set(31,3,22,'grindstone[face=floor,facing=west]');use(m,'winch','手动起吊和绞盘操作',31,22,30,22)
    # Incomplete wreck ribs lie in shallows; static historic feature, no pretend room.
    for z in range(22,35):
        m.set(8,1,z,'dark_oak_log[axis=z]')
        if z%3==1:
            m.box((5,2,z),(11,2,z),'dark_oak_planks')
            m.set(5,3,z,'dark_oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]')
            if z<31:m.set(11,3,z,'dark_oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]')
    m.box((8,2,22),(8,5,22),'dark_oak_log[axis=y]')
    m.box((5,2,23),(5,2,29),'dark_oak_planks')
    m.box((11,2,25),(11,2,31),'dark_oak_planks')
    m.point('wreck_access','circulation',(12,3,28),'浅湾残舟观察和轻件转运栈桥',look_at=[8,2,30])
    room(m,'camp','三人轮班生活屋',(4,3,4),(13,6,14),'轮休床、个人用品、热食和淡水')
    room(m,'secure','登记与干燥物保管屋',(24,3,4),(33,6,12),'回收物登记、归属文书与封存工具')
    room(m,'sort','桩基分拣起吊甲板',(17,3,19),(33,9,28),'冲洗、分拣、木料晾干、金属分类和手动起吊，中央搬运线相通')
    m.meta['terrain']['选用']='明确选于有船骸的避风浅湾；自带残舟片段及栈桥，不能与另一船骸重复叠放。'
    m.meta['terrain']['水深']='本体浅水 Y=1～2，岸台甲板脚底 Y=3；水中船骸不可当通路，全部工作点由干燥岸台及桩桥到达。'
    return finish(m,'陆侧临时轮休屋与干燥保管屋分开，后方桩基甲板排列冲洗、分拣、晾板、金属堆和手吊；侧指栈桥面对残缺舟肋，保留浅水船骸作业前提。')


def abandoned_watch():
    m=base('NS-12-v01','岬角旧海防哨',35,35,25,role='structure',shore=31)
    # A low former guard lodge and raised open signal bastion occupy different terraces.
    lodge(m,3,5,14,22,height=4,roof='deepslate_tile');entry(m,'entry',8,5)
    sleep(m,'oldbunk',4,11,2)
    counter(m,'log','旧值守日志和航路记录台',4,7,5,block='lectern[facing=south,has_book=false,powered=false]')
    stores(m,'gear','封存海防工具与旧灯具',4,19,5)
    cook(m,'oldstove',9,19)
    # Deliberate bounded weathered wall section, not random missing support.
    for z in (13,14,15):m.set(3,4,z,'mossy_cobblestone')
    for x in range(5,8):
        for z in (19,20,21):
            m.set(x,8+x-2,z,'air')
            m.set(x,7,z,'air')
    m.set(5,9,21,'cobweb')
    m.box((6,3,15),(10,3,15),'spruce_planks');bench(m,6,3,17,4)
    use(m,'mess','旧值班公共餐桌',8,15,8,14)
    m.box((17,0,18),(30,5,30),'stone_bricks')
    for x,z in ((17,18),(30,18),(17,30),(30,30)):
        m.box((x,6,z),(x,10,z),'cracked_stone_bricks')
    m.box((18,5,19),(29,5,29),'stone_bricks')
    steps(m,22,15,3,3);m.box((22,5,18),(24,5,18),'stone_bricks')
    for a,b in (((17,6,19),(17,6,29)),((30,6,19),(30,6,29)),((18,6,30),(29,6,30)),((18,6,18),(21,6,18)),((25,6,18),(29,6,18))):m.box(a,b,'mossy_stone_bricks')
    # Old signal furnace and chart desk, readable before the parapet ruins.
    m.box((20,6,25),(22,6,27),'cobblestone');m.set(21,7,26,'campfire[facing=north,lit=false,signal_fire=true,waterlogged=false]')
    use(m,'signal','停用信号火盆检视',20,25,19,25,y=6)
    counter(m,'chart','避雨残桌与海图观测位',25,21,4,y=6,block='lectern[facing=south,has_book=false,powered=false]')
    m.point('watch','circulation',(27,6,28),'朝海观测平台',look_at=[27,5,34])
    # Wind-broken old screen, grounded piers and intentionally absent roof above it.
    m.box((18,6,20),(18,8,22),'cracked_stone_bricks');m.box((18,9,21),(18,10,22),'mossy_stone_bricks')
    m.box((2,3,25),(12,4,26),'mossy_cobblestone');m.box((4,3,27),(6,3,28),'gravel')
    bench(m,7,3,25,4)
    room(m,'guard','旧值守与两人歇宿屋',(4,3,6),(13,6,21),'旧日志、歇宿、封存装备与停用炉灶；厚木屋面保留可遮蔽部分')
    room(m,'bastion','开放海向信号台',(18,6,19),(29,10,29),'停用火盆、海图工作台与观测通路，低矮连续垛墙围护高台边缘')
    m.meta['floors']=[dict(name='旧营房及下院',y=2,max_y=6),dict(name='高台观测层',y=5,max_y=10)]
    m.meta['terrain']['高程']='旧营房脚底 Y=3，岬角高台脚底 Y=6；三级三宽石阶连接，入口与海向必须匹配真实岬地。'
    m.meta['terrain']['历史']='选择有旧航路/边防历史的位置；为停用遗存，设备未燃烧、床位仅遗留，不承诺现役防御或自动补给。'
    return finish(m,'旧木营房与石质海向信号台分占两级岬角；保留日志、两床、旧炉与封存工具，抬高观测层有连续矮垛墙和停用火盆，残损石屏表现弃置而不破坏可走路线。')


from .northern_reference import BUILDERS
