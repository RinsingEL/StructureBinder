"""Self-contained Gothic food gardens, vine courts and maintenance shelters."""
from .memorial_life import base,house,entry,zone,finish,stores,counter,use,pointed
from .components import bench


def garden(key,name,w,d):
    m=base(key,name,w,d,24);m.meta['source']='tools/structure_studio/studio/memorial_gardens.py:BUILDERS'
    m.box((2,2,2),(w-3,2,d-3),'grass_block')
    m.meta['terrain'].update(选址='日照充分、排水稳定且有灌溉供水的聚落园地；不因哥特风格限定生物群系。',落地='固定小园模板与完整封边；不冒充按自然地形延伸的 Landscape 大田。',生产='原版粮菜与花材为静态成熟或展示状态；药材和葡萄不声称已接入专用生产机制。')
    m.point('entry','entrance',(w//2,3,2),'园地前入口')
    for x in (2,w-3):
        for z in (3,d-4):
            m.box((x,3,z),(x,5,z),'stone_bricks');m.set(x,6,z,'stone_brick_wall');m.set(x,7,z,'deepslate_tile_wall')
    return m


def plot(m,key,x,z,w,d,crop='wheat',y=2,herb=False):
    m.box((x-1,0,z-1),(x+w,y,z+d),'stone_bricks')
    m.box((x-1,y,z-1),(x+w,y,z+d),'dirt_path')
    water=x+w//2
    for xx in range(x,x+w):
        for zz in range(z,z+d):
            if xx==water and not herb:
                m.set(xx,y,zz,'water[level=0]');continue
            m.set(xx,y,zz,'dirt' if herb else 'farmland[moisture=7]')
            b=('allium' if (xx+zz)%2 else 'azure_bluet') if herb else crop+'[age='+('3' if crop=='beetroots' else '7')+']'
            m.set(xx,y+1,zz,b)
    use(m,key,'药用花材分格种植' if herb else '粮菜灌溉及采收',x,z,x-1,z,y+1,kind='work')
    zone(m,key,'药材花圃' if herb else '灌溉粮菜畦',x,z,x+w-1,z+d-1,'花材静态种植；具体药效未接入' if herb else '原版耕地与中央灌渠，边埂作业',y=y+1)


def work(m,x,z):
    counter(m,'sort','采收、分选与装篮',x,z,4);m.set(x+2,3,z,'water_cauldron[level=3]')
    stores(m,'seed','种料、标牌与手工具',x,z+4,4)
    zone(m,'work','园地作业与种料角',x,z,x+4,z+5,'采后分选、清洗、种料及工具存放')


def farm_strip():
    m=garden('ML-F02-v01','窄长双条供粮田',21,35)
    plot(m,'wheat',4,6,5,22);plot(m,'carrot',13,6,4,16,'carrots');work(m,13,25)
    m.box((10,2,3),(11,2,31),'gravel')
    return finish(m,'窄地块用一长一短双条田与中央通路，长麦畦和短根菜畦各自灌溉，短畦后接分选种料角。')


def farm_quads():
    m=garden('ML-F02-v02','四格药材花圃与标本屋',29,33)
    for i,(x,z) in enumerate(((5,6),(17,6),(5,16),(17,16))):plot(m,'herb'+str(i),x,z,6,6,herb=True)
    house(m,5,25,23,29,height=4);entry(m,'shed',14,25)
    stores(m,'specimen','干燥花材与标本签',6,27,6);counter(m,'sorting','分类、称量和养护记录',16,27,6,block='lectern[facing=south]')
    zone(m,'shed','后部药材标本小屋',6,26,22,28,'花材整理和干燥保管；展示不等于药物生产已接入')
    return finish(m,'四格花圃以十字养护道隔开，后部窄标本屋专门储存花材与记录；药材用途标注为静态表达。')


def farm_elbow():
    m=garden('ML-F02-v03','院角折线蔬菜园',31,31)
    plot(m,'west',5,6,5,18,'carrots');plot(m,'north',16,6,9,5,'potatoes');plot(m,'corner',16,16,5,6,'beetroots')
    work(m,16,25);m.box((23,2,16),(26,2,22),'moss_block');bench(m,22,3,20,4,wood='spruce')
    return finish(m,'三块不同长宽菜畦折成院角L形，空出的内角安排休息绿角与分选角，根菜各有明渠。')


def farm_terrace():
    m=garden('ML-F02-v04','三阶台地药菜园',31,36)
    plot(m,'low',5,5,8,6,'carrots');plot(m,'middle',5,16,8,6,'potatoes',y=3);plot(m,'high',5,27,8,5,herb=True,y=4)
    m.box((15,0,14),(20,3,23),'stone_bricks');m.box((15,0,25),(20,4,33),'stone_bricks')
    m.box((16,3,13),(18,3,13),'stone_brick_stairs[facing=south]');m.box((16,4,24),(18,4,24),'stone_brick_stairs[facing=south]')
    m.box((14,0,14),(14,3,23),'stone_bricks');m.box((14,0,25),(14,4,33),'stone_bricks')
    # Both ends of each level connect the left plot-edge marker to the broad east landing.
    m.box((4,0,14),(14,3,15),'stone_bricks');m.box((4,0,25),(14,4,26),'stone_bricks')
    work(m,23,6)
    m.meta['floors']=[dict(name='下菜园',y=2,max_y=7),dict(name='中菜园',y=3,max_y=8),dict(name='上药圃',y=4,max_y=9)]
    return finish(m,'三段小台地逐级抬高，东侧实阶与平台连接每一级田埂；下中层根菜灌溉，上层独立药用花圃。')


def farm_barn():
    m=garden('ML-F02-v05','谷田与独立备种屋',34,31)
    house(m,4,4,14,25);entry(m,'seedentry',9,4)
    stores(m,'seed','封存谷种与周转粮袋',5,7,6);counter(m,'sort','去杂、称量与分选',5,13,6);stores(m,'tools','锄具和收割器具',5,21,6)
    plot(m,'fielda',20,5,8,8);plot(m,'fieldb',20,19,8,7,'beetroots')
    zone(m,'shed','窄长备种工作屋',5,5,13,24,'粮种、手工具、去杂和称量分段布置')
    return finish(m,'西侧为完整窄备种工作屋，东侧两块方畦分别种粮和根菜，横向中通路可以搬运收获到分选台。')


def farm_court():
    m=garden('ML-F02-v06','环路菜药混合供给园',35,35)
    plot(m,'fronta',5,6,7,6,'potatoes');plot(m,'frontb',22,6,7,6,'carrots')
    plot(m,'rear',5,25,7,5,'wheat');plot(m,'herb',22,24,7,6,herb=True)
    work(m,15,16);m.box((12,2,15),(23,2,23),'gravel')
    bench(m,5,3,19,5,wood='spruce');m.set(28,3,19,'composter[level=0]');use(m,'compost','植物残料归集',28,19)
    return finish(m,'四角菜药畦围绕中央分选与种料工作岛，环路同时连接两种根菜、谷物、药材及残料归集点。')


def pergola(m,x0,z0,x1,z1,grape=True):
    for x in (x0,x1):
        for z in range(z0,z1+1,5):
            m.box((x,3,z),(x,6,z),'dark_oak_log[axis=y]');m.set(x,7,z,'stone_brick_wall')
        m.box((x,6,z0),(x,6,z1),'dark_oak_log[axis=z]')
    for z in range(z0,z1+1,3):
        m.box((x0,7,z),(x1,7,z),'spruce_slab[type=bottom]')
        if grape:
            for x in range(x0+1,x1):m.set(x,8,z,'oak_leaves[persistent=true,distance=1]')
            m.set(x0+1,6,z,'purple_stained_glass')
    # Pointed stone arch at the open front, retaining a three-block clear passage.
    for x in (x0,x1):m.box((x,3,z0),(x,5,z0),'stone_bricks')
    center=(x0+x1)//2
    for x in range(x0+1,x1):m.set(x,6+min(x-x0,x1-x),z0,'stone_brick_slab[type=bottom]')
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],7)
    if grape:m.meta['grape_expression']='木架、叶幕与紫色小簇表示葡萄棚；未接入实际葡萄作物、采收掉落或产量。'


def vine_single():
    m=garden('ML-F03-v01','单列采收葡萄棚',19,30);pergola(m,5,5,11,23)
    counter(m,'sort','葡萄静态采收与分选台',5,26,5);stores(m,'tools','绑枝、剪枝与采收篮',13,14,3)
    use(m,'tend','棚下绑枝维护通道',8,12,8,13)
    zone(m,'vine','单列生产用途藤棚',5,5,11,23,'棚架与葡萄外观表达，保留棚下维护通道')
    return finish(m,'单列纵向葡萄棚下贯通维护，末端设采收分选台，工具柜在棚外；生产用途与静态表达边界明确。')


def vine_pair():
    m=garden('ML-F03-v02','双列葡萄与中间搬运道',31,29);pergola(m,5,5,11,23);pergola(m,19,5,25,18)
    counter(m,'sort','采收分级与装篮',19,23,6);stores(m,'tools','两列共用工具及绑绳',13,6,4)
    use(m,'tend','双列之间采收搬运道',15,16,15,17)
    zone(m,'long','长列葡萄架',5,5,11,23,'连续维护通道及葡萄静态叶幕')
    zone(m,'short','短列与采后作业',19,5,25,24,'短列末端接分选台，保留中间主搬运道')
    return finish(m,'不等长双列架夹出宽搬运道，短列尾部接分选装篮，长列专用于连续攀援展示。')


def vine_memorial():
    m=garden('ML-F03-v03','折角纪念休憩藤廊',31,31);pergola(m,5,5,11,23,False);pergola(m,11,17,25,23,False)
    bench(m,6,3,10,4,wood='spruce');bench(m,16,3,21,6,wood='spruce')
    m.box((15,2,7),(24,2,12),'moss_block')
    for x in (16,20,23):m.set(x,3,9,'flowering_azalea')
    m.box((6,3,21),(8,4,21),'chiseled_stone_bricks');m.set(7,5,21,'white_candle[candles=3]');use(m,'memorial','安静纪念与献花',7,21,8,20)
    stores(m,'care','庭院清扫与花器',21,26,4)
    zone(m,'rest','折角休憩廊与长凳',5,5,25,23,'纪念休憩用途，不标记葡萄产出')
    return finish(m,'L形带尖拱端头的休憩廊围出花木小庭，长凳、低纪念台和清扫柜成组布置；明确属于纪念休息而非葡萄生产。')


def vine_wall():
    m=garden('ML-F03-v04','彩窗院墙前休憩棚',29,24)
    m.box((4,3,18),(24,8,18),'stone_bricks')
    for x in (7,14,21):pointed(m,x,18,4,color='purple')
    pergola(m,5,8,23,16,False);bench(m,7,3,14,5,wood='spruce');bench(m,16,3,14,5,wood='spruce')
    counter(m,'flower','花器整理与纪念小物',5,20,5);stores(m,'tools','花剪与清扫器具',18,20,5)
    m.box((9,2,4),(20,2,6),'moss_block');m.set(11,3,5,'flowering_azalea');m.set(17,3,5,'flowering_azalea')
    use(m,'rest','彩窗墙前安静停留',14,12,14,11)
    zone(m,'rest','彩窗墙前双长凳',5,8,23,17,'完整独立院墙与休憩棚，不依赖外部建筑补墙')
    return finish(m,'彩窗石院墙完整纳入模板，前部宽休憩棚含成对长凳和花木角，墙后侧作业台存放花器清扫物资。')


def toolhouse():
    m=garden('ML-F04-v01','尖顶独立看护工具屋',21,23);house(m,4,4,16,19);entry(m,'shed',10,4)
    stores(m,'tools','长短养护工具分柜',5,7,5);counter(m,'repair','磨修换柄工作台',5,13,5,block='grindstone[face=floor]')
    stores(m,'clean','干净清洁材料',11,16,4);m.set(13,3,10,'water_cauldron[level=3]');use(m,'wash','工具洗涤',13,10)
    zone(m,'shed','闭合干燥工具屋',5,5,15,18,'工具归还、磨修、洗涤及干料分别布置')
    return finish(m,'完整独立尖顶工具屋，长短工具柜、磨修工作台、洗涤盆及干净清洁材料分区，入口和中央通路通畅。')


def lean(m,x0,z0,x1,z1):
    m.box((x0,3,z1),(x1,6,z1),'stone_bricks')
    for x in (x0,x1):m.box((x,3,z0),(x,5,z0),'dark_oak_log[axis=y]')
    for z in range(z0-1,z1+2):m.box((x0-1,6+(z-z0+1)//3,z),(x1+1,6+(z-z0+1)//3,z),'deepslate_tile_slab')
    m.box((x0,5,z0),(x1,5,z0),'dark_oak_log[axis=x]')
    m.meta['roof_min_y']=min(m.meta['roof_min_y'],6)


def tools_wall():
    m=garden('ML-F04-v02','自带院墙的长工具檐',31,19);lean(m,4,5,26,12)
    stores(m,'tools','长柄与短柄工具',5,10,6);counter(m,'repair','换柄及小件维修',15,10,5)
    m.set(23,3,10,'water_cauldron[level=3]');use(m,'wash','工具清洗',23,10)
    m.box((5,3,7),(8,3,7),'barrel');use(m,'clean','清洁桶及消耗材料',5,7)
    zone(m,'shed','长檐开放工具作业面',5,6,25,11,'自带后墙，长工具、维修及洗涤横向分工')
    return finish(m,'长条背墙檐棚完整保留后墙与柱梁，前方大开口便于长柄工具进出；工具、修配、洗涤沿墙横向组织。')


def tools_elbow():
    m=garden('ML-F04-v03','干库与转角清洗棚',29,30);house(m,4,4,15,24);entry(m,'shed',10,4);lean(m,18,15,24,24)
    stores(m,'tools','干燥工具归还柜',5,7,6);stores(m,'clean','干净布料与清洁材料',5,13,6);counter(m,'record','领用与维修登记',5,20,6,block='lectern[facing=south]')
    counter(m,'repair','棚下换柄和整修',19,21,4);m.set(22,3,17,'water_cauldron[level=3]');use(m,'wash','沾泥工具清洗',22,17)
    zone(m,'dry','封闭干燥工具库',5,5,14,23,'干净物资及登记独立保管')
    zone(m,'wet','开敞转角湿作业棚',19,16,23,23,'沾泥器具先清洗换柄，干后转入主库')
    return finish(m,'闭合长库与侧后开敞清洗棚形成L形工作关系，工具先湿洗整修再入干库，净料和登记保持室内。')


def tools_bays():
    m=garden('ML-F04-v04','双棚与中间运具院',33,28);lean(m,4,5,13,20);lean(m,20,5,29,15)
    stores(m,'tools','园地长柄器具',5,18,7);counter(m,'repair','西棚修配工作台',5,8,6,block='grindstone[face=floor]')
    stores(m,'clean','东棚清洁物资',21,13,6);m.set(23,3,8,'water_cauldron[level=3]');use(m,'wash','清洁器具洗涤',23,8)
    counter(m,'check','归还检查与分流',19,22,6);m.point('yard','circulation',(16,3,16),'中院搬运及转向')
    zone(m,'west','长棚维修与长柄工具',5,6,12,19,'较长器具由中院转入西棚')
    zone(m,'east','短棚净料与洗涤',21,6,28,14,'清洁耗材与水盆分放')
    zone(m,'yard','中院检查分流',14,16,27,24,'回收器具经检查后分送对应棚区')
    return finish(m,'不等长双棚与宽中院承担工具分流，长棚磨修、短棚清洁、后院检查各有独立使用面，无遮雨死角假库。')


BUILDERS={f'ML-F02-v{i:02}':f for i,f in enumerate((farm_strip,farm_quads,farm_elbow,farm_terrace,farm_barn,farm_court),1)}
BUILDERS.update({f'ML-F03-v{i:02}':f for i,f in enumerate((vine_single,vine_pair,vine_memorial,vine_wall),1)})
BUILDERS.update({f'ML-F04-v{i:02}':f for i,f in enumerate((toolhouse,tools_wall,tools_elbow,tools_bays),1)})
