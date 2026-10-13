from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
p=Path('runtime/java-d4-20261011'); src=Image.open(p/'24-homes.2.png').convert('RGB')
font='C:/Windows/Fonts/msyh.ttc'
f=lambda n:ImageFont.truetype(font,n)
im=Image.new('RGB',(1600,1060),'#f6f4ed'); d=ImageDraw.Draw(im)
d.text((35,20),'镜潮学院岛城 · D4 整城方案',font=f(34),fill='#243a3c')
d.text((36,70),'4 个功能区   /   8 个组团   /   21 栋计划全部保留',font=f(22),fill='#526561')
crop=src.crop((240,145,805,580)).resize((1130,870));im.paste(crop,(24,120));d=ImageDraw.Draw(im)
points=[(1,504,450),(2,545,195),(3,504,282),(4,500,370),(5,427,446),(6,300,240),(7,681,416),(8,391,375)]
for n,x,y in points:
 x=24+(x-240)*2;y=120+(y-145)*2
 d.ellipse((x-17,y-17,x+17,y+17),fill='#203c44',outline='white',width=2)
 d.text((x-7,y-13),str(n),font=f(20),fill='white')
items=[('1  教学与实验','主教学楼、实验研究楼'),('2  植物公共庭园','星泉、植物教学、花草庭园'),('3  魔法工坊','器材制作 · 衔接花园与藏书区'),('4  藏书与观测','图书馆、抄写室、观测塔'),('5  港务与办事','水运接驳、公共办事'),('6  访客服务街','出行登记、校园零售'),('7  学生日常生活','餐厅、宿舍、居住、药剂'),('','仓储、医疗'),('8  教职工街坊','住宅、邻里药剂配套')]
y=140
for title,detail in items:
 if title:d.text((1180,y),title,font=f(23),fill='#243a3c');y+=35
 d.text((1180,y),detail,font=f(16),fill='#526561');y+=51 if title else 28
for y,t in [(997,'图源：Java 正式 D4 预览；裁切放大并添加中文索引，未重绘建筑或地形。'),(1026,'D4 已接受。绿化配置已保存，实际种植、道路通行和港口施工尚未验证。')]:d.text((30,y),t,font=f(18),fill='#526561')
im.save(p/'city-d4-annotated.png')
