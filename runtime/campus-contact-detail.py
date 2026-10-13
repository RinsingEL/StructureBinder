from PIL import Image,ImageDraw
from pathlib import Path
ids=['MG-43-v01','MG-44-v01','MG-45-v01','MG-34-v02','MG-37-v02'];im=Image.new('RGB',(1600,1100),'#eee');draw=ImageDraw.Draw(im)
for i,ident in enumerate(ids):
 for j,view in enumerate(('front','back','floor-1','floor-2')):
  p=Path('tools/structure_studio/runtime/captures')/ident/(view+'.png')
  if not p.exists():continue
  shot=Image.open(p);box=(shot.width//4,shot.height//4,shot.width*3//4,shot.height*3//4);shot=shot.crop(box);shot.thumbnail((390,195));x=j*400;y=i*220+20;im.paste(shot,(x,y));draw.text((x+10,y-15),ident+' '+view,fill='black')
im.save('runtime/campus-groups-asset-detail.png')
