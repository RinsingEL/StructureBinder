from pathlib import Path
import json,subprocess,hashlib,re,os
H=Path(__file__).resolve().parent;FF=os.environ.get('FFMPEG', 'ffmpeg')
acts=['开场','第二段','第三段','第四段','第五段']
files=[H/f'{i:02}_{a}_定稿.mp4' for i,a in enumerate(acts,1)]
assert all(p.is_file() and p.stat().st_size>10000 for p in files)
listing=H/'render/concat.txt';listing.write_text('\n'.join("file '"+p.as_posix()+"'" for p in files)+'\n',encoding='utf8')
report=json.loads((H/'渲染核验.json').read_text(encoding='utf8'))
chapters=[';FFMETADATA1'];offset=0;srt=[];counter=0
def stamp(t):
 ms=round(t*1000);hh,ms=divmod(ms,3600000);mm,ms=divmod(ms,60000);ss,ms=divmod(ms,1000);return f'{hh:02}:{mm:02}:{ss:02},{ms:03}'
for r in report:
 chapters+=['[CHAPTER]','TIMEBASE=1/1000',f'START={round(offset*1000)}',f'END={round((offset+r["video_duration"])*1000)}','title='+r['act']]
 for c in sorted(json.loads((H/'render'/f'{r["act"]}_cards.json').read_text(encoding='utf8')),key=lambda c:c['start']):
  counter+=1;srt.append(f"{counter}\n{stamp(offset+c['start'])} --> {stamp(offset+c['end'])}\n{c['text']}")
 offset+=r['video_duration']
meta=H/'render/chapters.ffmeta';meta.write_text('\n'.join(chapters)+'\n',encoding='utf8')
(H/'社团开放日_全片定稿_20261122.srt').write_text('\n\n'.join(srt)+'\n',encoding='utf8')
dest=H/'社团开放日_全片定稿_20261122.mp4'
subprocess.run([FF,'-y','-v','error','-f','concat','-safe','0','-i',str(listing),'-i',str(meta),'-map','0:v:0','-map','0:a:0','-map_metadata','1','-map_chapters','1','-c','copy','-movflags','+faststart',str(dest)],check=True)
print('CONCAT_COMPLETE',dest,flush=True)
subprocess.run([FF,'-v','error','-i',str(dest),'-f','null','-'],check=True)
result={'full_video':dest.name,'nominal_duration':offset,'full_decode':'passed','size_bytes':dest.stat().st_size,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest(),'acts':[{'file':p.name,'bytes':p.stat().st_size} for p in files],'subtitle_cards':counter,'draft':'最终剪辑_配乐气泡均为负10dB.json'}
(H/'交付核验.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(result,ensure_ascii=False),flush=True)
