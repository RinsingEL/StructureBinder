"""Arrange existing screenshots for actual visual comparison; never approves them."""
import argparse
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

parser=argparse.ArgumentParser()
parser.add_argument("output",type=Path)
parser.add_argument("images",type=Path,nargs="+")
parser.add_argument("--columns",type=int,default=3)
args=parser.parse_args()
if args.columns<1:parser.error("columns must be positive")
w,h=600,400
sheet=Image.new("RGB",(w*args.columns,(h+32)*math.ceil(len(args.images)/args.columns)),"#10171d")
draw=ImageDraw.Draw(sheet)
font_path=Path("C:/Windows/Fonts/msyh.ttc")
font=ImageFont.truetype(str(font_path),17) if font_path.exists() else ImageFont.load_default()
for i,p in enumerate(args.images):
    img=Image.open(p).convert("RGB")
    thumb=ImageOps.contain(img,(w-8,h-8))
    x=(i%args.columns)*w;y=(i//args.columns)*(h+32)
    sheet.paste(thumb,(x+(w-thumb.width)//2,y+(h-thumb.height)//2))
    draw.text((x+12,y+h+2),f"{p.parent.name} / {p.stem}",font=font,fill="#d5e2e5")
args.output.parent.mkdir(parents=True,exist_ok=True)
sheet.save(args.output)
print(args.output)
