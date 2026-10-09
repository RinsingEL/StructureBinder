"""Real voxel captures, with no generated concept imagery."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

tool=Path(__file__).resolve().parents[1]
out=tool.parents[1]/'runtime/campus-workbench/terrace-features'
out.mkdir(parents=True,exist_ok=True)
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22)
small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
canvas=Image.new('RGB',(1600,1020),'#17222d');draw=ImageDraw.Draw(canvas)
draw.text((24,15),'通用台地设施 · 两套素材配置 / 真实 NBT',font=font,fill='white')
assets=[('MG-41-v01','石砌角塔'),('MG-42-v01','晶簇庭院标志'),('EU-25-v01','木构角亭'),('EU-26-v01','石井遮棚')]
for col,(ident,label) in enumerate(assets):
    for row,view in enumerate(['front','back','top']):
        source=Image.open(tool/'runtime/captures'/ident/f'{view}.png').convert('RGB')
        source=ImageOps.contain(source,(390,300))
        x=5+col*400;y=55+row*320
        canvas.paste(source,(x+(390-source.width)//2,y))
        draw.text((x+12,y+301),f'{ident} · {label} · {view}',font=small,fill='#d9e3ef')
canvas.save(out/'素材预览.png')
print(out/'素材预览.png')
