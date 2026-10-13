from PIL import Image,ImageDraw
from pathlib import Path
ids=['MG-43-v01','MG-44-v01','MG-45-v01','MG-34-v02','MG-37-v02'];im=Image.new('RGB',(1500,1000),'#eeeeee');draw=ImageDraw.Draw(im)
for i,ident in enumerate(ids):
 for j,view in enumerate(('front','top')):
  p=Path('tools/structure_studio/runtime/captures')/ident/(view+'.png')
  if p.exists():
   shot=Image.open(p);shot.thumbnail((300,460));im.paste(shot,(i*300,j*500+25));draw.text((i*300+10,j*500+5),ident+' '+view,fill='black')
im.save('runtime/campus-groups-assets.png')
