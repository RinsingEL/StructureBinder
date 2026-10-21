"""Contact sheets from real Studio captures; never generates review approval."""
from pathlib import Path
import json
from PIL import Image,ImageDraw

root=Path(__file__).resolve().parents[1]/'runtime'
ids=json.loads((root/'european-ids.json').read_text())
out=root/'european-review';out.mkdir(exist_ok=True)
for batch in range((len(ids)+3)//4):
    canvas=Image.new('RGB',(1600,1100),'#dddddd');draw=ImageDraw.Draw(canvas)
    for row,key in enumerate(ids[batch*4:batch*4+4]):
        for col,view in enumerate(['front','back','roof-off','floor-1']):
            p=root/'captures'/key/(view+'.png')
            if not p.exists():continue
            im=Image.open(p).convert('RGB')
            # Exclude UI footer and enough empty margins to make compact assets readable.
            im=im.crop((int(im.width*.15),int(im.height*.12),int(im.width*.85),int(im.height*.9)))
            im.thumbnail((390,240));x=col*400+(400-im.width)//2;y=row*275+30
            canvas.paste(im,(x,y));draw.text((col*400+8,row*275+8),key+' '+view,fill='black')
    canvas.save(out/f'page-{batch+1:02d}.jpg')
print('Contact pages:',batch+1)
