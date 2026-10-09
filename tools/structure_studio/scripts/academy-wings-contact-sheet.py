"""Arrange current real-NBT captures into the first academy assembly-parts atlas."""
from pathlib import Path
import json
from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageOps

TOOL=Path(__file__).resolve().parents[1]
ROOT=TOOL.parents[1]/'asset_catalogs/original_civilizations/R02_arcane_reborn'
font_path='C:/Windows/Fonts/msyh.ttc'
title=ImageFont.truetype(font_path,32)
body=ImageFont.truetype(font_path,20)
small=ImageFont.truetype(font_path,16)
poster=Image.new('RGB',(1410,1310),'#111e29');draw=ImageDraw.Draw(poster)
draw.text((30,20),'魔法学院 · 第一批组团素材',font=title,fill='#eef0f7')
draw.text((30,66),'5 件多层建筑 + 2 件程序连接部件  /  真实 Minecraft 方块模型',font=body,fill='#bfc9da')
cards=[(34,'front','双层直翼教学楼'),(35,'back','L 形折角研讨楼'),(36,'front','U 形三翼教学庭院'),
       (37,'front','双翼宿舍院 · 16 床'),(38,'front','三层研究书楼 · 退台露台'),
       (39,'front','地面尖拱连廊'),(40,'front','高架教学连廊'),
       (36,'floor-2','教学庭院 · 二层平面'),(38,'floor-3','研究书楼 · 三层平面')]
for index,(number,view,label) in enumerate(cards):
    x=25+(index%3)*460;y=108+(index//3)*385
    draw.rounded_rectangle((x,y,x+440,y+370),radius=12,fill='#1b2832',outline='#354452')
    folder=ROOT/'models'/f'MG-{number:02d}-v01'
    author=json.loads((folder/'author.json').read_text(encoding='utf-8'))
    capture=json.loads((folder/'previews/capture.json').read_text(encoding='utf-8'))
    assert capture['nbt_sha256']==author['nbt_sha256']
    source=Image.open(folder/'previews'/f'{view}.png').convert('RGB')
    source=source.crop((3,3,source.width-3,source.height-32))
    diff=ImageChops.difference(source,Image.new('RGB',source.size,source.getpixel((7,7))))
    bbox=diff.convert('L').point(lambda n:255 if n>12 else 0).getbbox()
    if bbox:source=source.crop((max(0,bbox[0]-10),max(0,bbox[1]-10),min(source.width,bbox[2]+10),min(source.height,bbox[3]+10)))
    source=ImageOps.contain(source,(415,276))
    poster.paste(source,(x+(440-source.width)//2,y+12+(276-source.height)//2))
    draw.text((x+15,y+295),label,font=body,fill='#eff2f7')
    w,h,d=author['size'];kind='程序消费' if number>=39 else f"{len(author['floors'])} 层空间"
    draw.text((x+15,y+332),f'MG-{number:02d}  ·  {w}×{d} / 高 {h}  ·  {kind}',font=small,fill='#b5c6d5')
draw.text((30,1280),'二层接口脚底 Y=12 · 接缝与步行通路离线检查通过 · 自动组团拼装待接入',font=small,fill='#acbccd')
output=ROOT/'组团素材第一批预览.png';poster.save(output)
print(output)
