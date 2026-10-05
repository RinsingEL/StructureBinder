"""Seven cultural landmarks, each with a distinct usable spatial programme.

Culture is an author design classification; planning_role remains the existing
planning schema. These are contemporary game designs, not historical replicas.
"""
from .chinese_parts import (
    base, building, doorway, gate, boundary, desk, bed, kitchen, wash,
    pave, garden, corridor, veranda, roof,
)


def cultural_base(number, name, width, depth, terms, identity):
    m = base(number, name, width, depth)
    m.meta.update(source='tools/structure_studio/studio/chinese_culture.py:BUILDERS',
                  function_terms=terms, differences=[identity],
                  design_notes=[
                      '文化核心指中式文化代表性；功能区主次与重复布置方式由具体规划决定。',
                      '独立设计的游戏建筑，不绑定特定朝代、地域或宗派。',
                      '灰瓦、浅墙、深木构延续用户参考；设施表达空间用途，不声明 NPC 行为。'])
    m.meta['terrain']['供给'] = '需维护、照明与清洁用水；驻留人员的食物和饮水由所在聚落提供。'
    boundary(m)
    gate(m, width//2)
    return m


def shelves(m, x0, x1, z, y=3):
    m.box((x0,y,z),(x1,y+1,z),'bookshelf')


def table(m, x, z, y=3, length=3):
    # Dark furniture remains legible against the lighter spruce floorboards.
    m.box((x,y,z),(x+length-1,y,z),'dark_oak_slab[type=top]')
    for xx in (x,x+length-1):
        m.set(xx,y,z-1,'dark_oak_stairs[facing=south]')
        m.set(xx,y,z+1,'dark_oak_stairs[facing=north]')
    m.set(x+length//2,y+1,z,'flower_pot')


def courtyard_lights(m, positions):
    for x,z in positions:
        m.set(x,2,z,'chiseled_stone_bricks')
        m.set(x,3,z,'stone_brick_wall')
        m.set(x,4,z,'lantern')


def pavilion(m, key, title, x0,z0,x1,z1, floor=2):
    m.box((x0,1,z0),(x1,floor,z1),'stone_bricks')
    m.box((x0+1,floor,z0+1),(x1-1,floor,z1-1),'spruce_planks')
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,floor+1,z),(x,floor+5,z),'dark_oak_log')
    for z in (z0,z1):m.box((x0,floor+5,z),(x1,floor+5,z),'spruce_log[axis=x]')
    for x in (x0,x1):m.box((x,floor+5,z0),(x,floor+5,z1),'spruce_log[axis=z]')
    roof(m,x0,z0,x1,z1,floor+6)
    cx=(x0+x1)//2
    m.set(cx,2,z0-1,'stone_brick_stairs[facing=south]')
    m.point(key+'_entry','circulation',(cx,floor+1,z0+1),'亭内通行')
    m.room(key,title,(x0+1,floor+1,z0+1),(x1-1,floor+5,z1-1),title)


def academy():
    m=cultural_base(16,'中式书院 · 讲堂书斋',53,57,['讲学','阅览','藏书'],
                    '前部讲学庭、中央讲堂、两翼书斋藏书、后部师生休息，教学和藏书空间分别设置。')
    pave(m,23,10,29,53);pave(m,14,18,38,39)
    building(m,'lecture','讲堂：授课与讨论',18,22,34,35)
    doorway(m,'lecture_front',25,22,width=3)
    doorway(m,'lecture_back',26,35,side='south')
    veranda(m,18,22,34)
    for x in (21,23,29,31):
        for z in (26,29):
            m.set(x,3,z,'dark_oak_slab[type=top]')
            m.set(x,4,z,'white_carpet')
            m.set(x,3,z-1,'dark_oak_stairs[facing=south]')
    desk(m,'teacher',26,32,name='讲席与讲义')
    m.point('student_seat','work',(21,3,26),'听讲案席',approach=(21,3,27))
    shelves(m,19,23,34);shelves(m,29,33,34)
    building(m,'study','西书斋：读写与小组研习',5,18,13,36)
    doorway(m,'study_door',13,26,side='east')
    for z in (21,28,32):desk(m,'study_'+str(z),7,z,name='书斋读写')
    building(m,'library','东藏书室：书架与借阅',39,18,47,36)
    doorway(m,'library_door',39,26,side='west')
    for z in (19,29,35):shelves(m,41,46,z)
    desk(m,'book_register',41,23,name='藏书登记')
    building(m,'teacher_room','后院讲师起居',5,44,17,52)
    doorway(m,'teacher_room_door',11,44)
    bed(m,'teacher_bed',8,50);desk(m,'teacher_notes',14,46,name='备课书案')
    building(m,'student_rest','后院学舍休息',35,44,47,52)
    doorway(m,'student_rest_door',41,44)
    for x in (38,43):bed(m,'student_bed_'+str(x),x,50)
    pavilion(m,'reading_pavilion','后园读书亭',22,44,30,51)
    table(m,24,48);garden(m,7,11,16,14);garden(m,36,11,45,14)
    courtyard_lights(m,[(18,15),(34,15),(18,40),(34,40)])
    return m


def ancestral_hall():
    m=cultural_base(17,'中式宗祠 · 前议后祭',43,55,['祖先祭祀','宗族议事','谱牒保管'],
                    '入口、议事厅、拜庭与抬高祭堂依次展开；谱牒室与祭器房分列两翼。')
    pave(m,17,10,25,49);pave(m,8,28,34,33,'polished_andesite')
    building(m,'clan_meeting','前厅：宗族议事',13,16,29,25)
    doorway(m,'meeting_front',20,16,width=3)
    doorway(m,'meeting_back',20,25,side='south',width=3)
    for x in (15,25):table(m,x,20,3,2)
    desk(m,'clan_record',15,23,name='族务记录')
    building(m,'genealogy','东谱牒室',33,16,38,26)
    doorway(m,'genealogy_door',33,21,side='west')
    shelves(m,34,37,25);desk(m,'genealogy_register',35,18,name='族谱抄录与保管')
    building(m,'vessels','西祭器房',4,16,9,26)
    doorway(m,'vessels_door',9,21,side='east')
    desk(m,'vessel_store',6,18,kind='storage',block='barrel',name='祭器与礼仪物资')
    m.set(5,3,24,'flower_pot')
    building(m,'ancestral_altar','后祭堂：祖祭与供案',10,35,32,47,floor=3,wall_height=6)
    doorway(m,'altar_entry',20,35,3,width=3)
    veranda(m,10,35,32,3)
    for x in (15,18,21,24,27):
        m.set(x,4,45,'dark_oak_planks')
        m.set(x,5,45,'dark_oak_trapdoor[facing=south,open=true]')
    m.box((14,4,46),(28,7,46),'red_terracotta')
    for x in (14,28):m.box((x,4,46),(x,7,46),'dark_oak_log')
    m.box((16,4,42),(26,4,42),'dark_oak_slab[type=top]')
    for x in (17,25):m.set(x,5,42,'lantern')
    m.point('ancestral_rite','work',(21,4,42),'供案礼仪站位',approach=(21,4,41))
    for x in (17,21,25):m.set(x,4,39,'red_carpet')
    corridor(m,5,30,7,45);corridor(m,35,30,37,45)
    garden(m,5,11,12,13);garden(m,30,11,37,13)
    courtyard_lights(m,[(11,29),(31,29)])
    return m


def temple():
    m=cultural_base(18,'中式庙宇 · 钟鼓礼庭',55,65,['祭祀供奉','礼仪集会'],
                    '钟鼓亭夹峙前庭，抬高主殿居中，偏殿与后部庙务生活区分开。')
    pave(m,23,10,31,59);pave(m,15,23,39,28,'polished_andesite')
    pavilion(m,'bell','西钟亭',7,15,13,21)
    m.box((7,7,18),(13,7,18),'spruce_log[axis=x]')
    m.set(10,6,18,'bell[attachment=ceiling,facing=north]')
    m.point('bell_use','work',(10,6,18),'钟亭礼仪设施',approach=(10,3,17))
    pavilion(m,'drum','东鼓亭',41,15,47,21)
    m.box((43,3,17),(45,3,19),'spruce_planks')
    m.set(44,4,18,'red_terracotta');m.set(44,5,18,'white_wool')
    m.point('drum_use','work',(44,4,18),'鼓台静态表达',approach=(44,3,16))
    building(m,'sanctuary','主殿：供奉与礼拜',18,31,36,46,floor=4,wall_height=6)
    doorway(m,'sanctuary_entry',26,31,4,width=3)
    veranda(m,18,31,36,4)
    m.box((23,5,42),(31,5,44),'chiseled_stone_bricks')
    m.box((26,6,43),(28,8,43),'gold_block')
    m.set(27,9,43,'chiseled_sandstone')
    m.box((24,5,39),(30,5,39),'dark_oak_slab[type=top]')
    m.point('offering','work',(27,5,39),'主殿供奉台',approach=(27,5,38))
    for x in (23,27,31):m.set(x,5,35,'yellow_carpet')
    for x in (15,39):
        m.set(x,2,25,'chiseled_stone_bricks');m.set(x,3,25,'flower_pot')
    for side,xa,xb in (('west',5,13),('east',41,49)):
        building(m,side+'_chapel','偏殿：小型供奉与静修',xa,32,xb,46)
        doorway(m,side+'_chapel_door',xb if side=='west' else xa,38,side='east' if side=='west' else 'west')
        desk(m,side+'_offering',xa+3,42,block='dark_oak_slab[type=top]',name='偏殿供案')
        m.set(xa+3,4,42,'flower_pot')
    building(m,'temple_service','后院斋厨与庙务',5,53,19,60)
    doorway(m,'temple_service_door',12,53)
    kitchen(m,'temple_stove',7,54);table(m,14,57)
    building(m,'caretaker','后院值守休息',37,53,49,60)
    doorway(m,'caretaker_door',43,53)
    for x in (40,45):bed(m,'caretaker_'+str(x),x,58)
    garden(m,23,51,31,58);courtyard_lights(m,[(19,18),(35,18)])
    m.meta['design_notes'].append('供奉对象使用抽象几何表达，未指定宗派与神祇；钟鼓为原版与静态组合。')
    return m


def yamen():
    m=cultural_base(19,'中式衙署 · 公堂文署',55,63,['政务办理','公务议事','档案保管'],
                    '影壁分流入口，前部办事与等候、公堂居中、后部文署与档案库采用侧路连接。')
    pave(m,16,10,38,44);pave(m,16,41,18,57);pave(m,36,41,38,57)
    m.box((23,2,14),(31,5,14),'stone_bricks')
    m.box((24,3,13),(30,4,13),'white_terracotta')
    m.box((22,6,14),(32,6,14),'deepslate_tile_slab[type=bottom]')
    building(m,'public_office','西办事房：递交与登记',5,18,13,31)
    doorway(m,'public_office_door',13,24,side='east')
    for z in (20,27):desk(m,'public_desk_'+str(z),7,z,name='文书受理')
    building(m,'waiting','东等候房：公告与等候',41,18,49,31)
    doorway(m,'waiting_door',41,24,side='west')
    for z in (20,27):table(m,44,z,3,3)
    desk(m,'notice_desk',43,29,name='公告与办事指引')
    building(m,'court','公堂：公务会商与正式受理',18,28,36,39,floor=3,wall_height=6)
    doorway(m,'court_door',26,28,3,width=3)
    doorway(m,'court_back',33,39,3,'south')
    veranda(m,18,28,36,3)
    m.box((23,4,36),(31,4,36),'dark_oak_slab[type=top]')
    desk(m,'official_desk',27,36,4,name='公堂案桌')
    m.box((24,4,38),(30,6,38),'blue_terracotta')
    for x in (21,33):m.set(x,4,33,'spruce_stairs[facing=south]')
    building(m,'archives','西后档案库：分类架与查阅',5,43,13,55)
    doorway(m,'archives_door',13,48,side='east')
    for z in (44,51,54):shelves(m,6,11,z)
    desk(m,'archive_register',7,47,name='档案检索登记')
    building(m,'administration','后文署：公务书写',20,47,34,57)
    doorway(m,'administration_door',27,47)
    for x in (23,30):
        for z in (49,53):desk(m,f'clerk_{x}_{z}',x,z,name='文署书案')
    building(m,'duty_room','东后值房：值守与休息',41,43,49,55)
    doorway(m,'duty_room_door',41,48,side='west')
    bed(m,'duty_bed',44,53);wash(m,'duty_wash',43,44)
    garden(m,5,10,14,13);garden(m,40,10,49,13)
    courtyard_lights(m,[(18,18),(36,18),(16,39),(38,39)])
    return m


def guildhall():
    m=cultural_base(20,'中式会馆 · 会商迎客',51,55,['同乡同行会商','商旅接待','宴集'],
                    '前部接待与会商、后部大会厅居主位，客舍、餐厨和公用物资房提供内部配套。')
    pave(m,21,10,29,30);pave(m,14,30,36,50)
    building(m,'negotiation','西会商房：商旅洽谈',5,17,15,29)
    doorway(m,'negotiation_door',15,23,side='east')
    table(m,8,23,3,5);desk(m,'trade_register',7,18,name='会务与商旅登记')
    m.set(12,3,18,'cartography_table')
    building(m,'dining','东宴集房：餐席与茶水',35,17,45,29)
    doorway(m,'dining_door',35,23,side='west')
    table(m,38,21,3,5);kitchen(m,'guild_kitchen',37,27)
    building(m,'assembly','大会厅：同乡同行议事',17,34,33,47,wall_height=6)
    doorway(m,'assembly_door',24,34,width=3)
    veranda(m,17,34,33)
    for x in (20,27):table(m,x,39,3,4)
    desk(m,'guild_chair',25,44,name='会馆主持与会务')
    shelves(m,18,22,46);shelves(m,28,32,46)
    building(m,'guesthouse','西客舍：来访代表留宿',5,36,12,48)
    doorway(m,'guesthouse_door',12,42,side='east')
    bed(m,'guest_one',7,46);bed(m,'guest_two',9,39)
    building(m,'common_store','东公用库：会务器材',38,36,45,48)
    doorway(m,'common_store_door',38,42,side='west')
    for z in (37,46):
        for x in range(39,45):m.set(x,3,z,'barrel')
    desk(m,'common_inventory',40,37,kind='storage',block='barrel',name='会务物资领用')
    garden(m,5,11,14,13);garden(m,36,11,45,13)
    courtyard_lights(m,[(18,20),(32,20),(18,27),(32,27)])
    return m


def theatre():
    m=cultural_base(21,'中式戏台院 · 临院演戏',49,57,['戏曲演出','观演','节庆集会'],
                    '抬高开敞戏台朝向观众院，中央与两侧走道保持贯通，侧房及后台承担候场和道具。')
    pave(m,10,11,38,32,'polished_andesite');pave(m,9,32,39,51)
    building(m,'stage_shell','戏台',13,35,35,47,floor=4,wall_height=6,room=False)
    m.box((14,5,35),(34,9,35),'air')
    m.box((14,5,43),(34,8,43),'red_wool')
    for x in (15,32):m.box((x,5,43),(x+1,7,43),'air')
    for x in (14,34):m.box((x,5,36),(x,8,36),'red_wool')
    doorway(m,'stage_west',13,39,4,'west',2)
    doorway(m,'stage_east',35,39,4,'east',2)
    doorway(m,'stage_back',24,47,4,'south',2)
    m.room('stage','开敞舞台',(14,5,35),(34,10,42),'表演、出入场与台口')
    m.room('backstage','后台横廊',(14,5,44),(34,9,46),'候场和两侧出入场')
    m.point('performance','work',(24,5,39),'舞台表演站位')
    m.point('backstage_route','circulation',(24,5,45),'后台候场通路')
    for z in (18,23,28):
        for xa,xb in ((13,20),(28,35)):
            for x in range(xa,xb+1):m.set(x,2,z,'spruce_stairs[facing=south]')
    m.room('audience','观众院',(11,2,14),(37,6,31),'六组座席与中央、横向通道')
    m.point('audience_access','circulation',(24,2,24),'观众院中央通道')
    corridor(m,5,16,8,30);corridor(m,40,16,43,30)
    building(m,'costumes','西候场房：更衣与衣箱',4,37,9,49)
    doorway(m,'costumes_door',9,44,side='east')
    desk(m,'costume_store',6,39,kind='storage',block='barrel',name='戏服与衣箱')
    m.set(5,3,47,'loom[facing=south]')
    building(m,'props','东道具房：乐器与器材',39,37,44,49)
    doorway(m,'props_door',39,44,side='west')
    desk(m,'prop_store',41,39,kind='storage',block='barrel',name='道具领用')
    m.set(42,3,47,'note_block')
    courtyard_lights(m,[(11,13),(37,13)])
    garden(m,5,10,12,12);garden(m,36,10,43,12)
    return m


def scholar_garden():
    m=cultural_base(22,'中式园林 · 曲水游廊',57,59,['游赏','雅集','园林会客'],
                    '非对称水池、直桥、开敞水亭、书屋、茶榭和折向游路组织移步换景；无主宅或生产园地。')
    m.meta['asset_tags']=['landscape']
    # Broad loops and a dry bridge make both shores reachable without swimming.
    pave(m,15,11,17,49);pave(m,39,11,41,49)
    pave(m,15,11,41,13);pave(m,9,46,47,49)
    for xa,za,xb,zb in ((19,20,37,42),(24,17,35,44),(17,26,39,36)):
        m.box((xa,0,za),(xb,0,zb),'stone_bricks')
        m.box((xa,1,za),(xb,1,zb),'water')
    for x,z in ((20,24),(35,23),(20,38),(34,40),(24,19)):
        m.set(x,2,z,'lily_pad')
    m.box((27,2,17),(31,2,44),'spruce_planks')
    for z in (24,36):m.box((27,0,z),(31,1,z),'stone_bricks')
    for z in (16,45):
        for x in range(27,32):m.set(x,2,z,'spruce_stairs[facing='+('south' if z==16 else 'north')+']')
    for x in (27,31):
        for z in range(17,45):m.set(x,3,z,'spruce_fence[north=true,south=true]')
    for z in (18,28,43):
        for x in (27,31):m.set(x,4,z,'lantern')
    m.point('bridge','circulation',(29,3,30),'跨水步桥')
    m.room('pond','曲水与步桥',(17,1,17),(39,5,44),'观景水面；通行经干式桥面和环池步道')
    pavilion(m,'view_pavilion','东北观景亭',44,14,51,22)
    table(m,46,19)
    building(m,'teahouse','东水榭：会客与茶席',43,30,51,42)
    doorway(m,'teahouse_door',43,36,side='west')
    table(m,45,35,3,4);desk(m,'tea_station',45,40,block='water_cauldron[level=3]',name='茶席备水')
    building(m,'garden_study','西书屋：读写与雅集',5,31,13,42)
    doorway(m,'garden_study_door',13,36,side='east')
    shelves(m,6,12,41);desk(m,'garden_desk',7,33,name='临园书案')
    table(m,7,37,3,3)
    # Low open rear gallery, with its own beam-supported narrow roof.
    pave(m,10,51,46,54)
    for x in range(10,47,6):
        for z in (51,54):m.box((x,2,z),(x,5,z),'dark_oak_log')
    for z in (51,54):m.box((10,6,z),(46,6,z),'spruce_log[axis=x]')
    roof(m,10,51,46,54,7,ridge_x=True)
    m.point('rear_gallery','circulation',(28,2,52),'后园横向游廊')
    for xa,za,xb,zb in ((5,12,12,27),(45,46,50,50),(6,45,11,49)):
        garden(m,xa,za,xb,zb)
    for x,z,h in ((8,18,4),(9,21,6),(11,19,5)):
        m.box((x,2,z),(x+1,h,z+1),'mossy_cobblestone')
    for x,z in ((9,25),(46,47),(10,46)):
        m.box((x,2,z),(x,5,z),'oak_log')
        m.box((x-2,5,z-2),(x+2,6,z+2),'oak_leaves[persistent=true]')
        m.box((x-1,7,z-1),(x+1,7,z+1),'oak_leaves[persistent=true]')
    courtyard_lights(m,[(18,13),(38,13)])
    m.meta['terrain']['供给']='园池与植物需要补水和养护；水体是模板内有底封闭浅池，不要求外接河岸。'
    return m


BUILDERS={f'CH-{number}-v01':fn for number,fn in (
    (16,academy),(17,ancestral_hall),(18,temple),(19,yamen),
    (20,guildhall),(21,theatre),(22,scholar_garden))}
