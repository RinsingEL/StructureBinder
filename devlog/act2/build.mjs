import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const dir=path.dirname(fileURLToPath(import.meta.url));
const data=JSON.parse(fs.readFileSync(path.join(dir,'content.json'),'utf8'));
const keys=new Set([...data.scenes.flatMap(s=>s.frames.filter(f=>f.image).map(f=>f.image)),'cities','design','d3-landform']);
const pictures=Object.fromEntries([...keys].map(name=>[name,'data:image/webp;base64,'+fs.readFileSync(path.join(dir,'assets',name+'.webp')).toString('base64')]));
const safe=value=>JSON.stringify(value).replaceAll('<','\\u003c');
const html=fs.readFileSync(path.join(dir,'template.html'),'utf8').replace('__DECK_DATA__',safe(data)).replace('__PICTURE_DATA__',safe(pictures)).replace('__EXTRA_VISUALS__',fs.readFileSync(path.join(dir,'visuals.js'),'utf8'));
fs.writeFileSync(path.join(dir,'index.html'),html);
const intro=`# ${data.title}\n\n第二幕动态演示与台词，2026-10-24。\n\n按 W 世界、T 国度、D 城市组成十一段，普通段落四步，D4 调用回放九步。截图展示产物，动效解释变化，实机呈现体验。前五段介绍总体主线与 W/T，后六段走进 D。预计约 12～15 分钟，按现场聊天伸缩。\n\n前三段是 10 月 24 日茶话会的现场记录；后八段保留 Claude 预写台词，并补入 Astra 连续讲解稿，新增内容尚未录制。Astra 可按画面连续讲完本段，其他伙伴按现场节奏插话，无需逐句交棒。Rinsing 讲动机、玩家体验和转场，Claude 串联设计意图，Astra 补充技术事实。历史失败、手册修订与旧方案对比留到第三幕，未来玩法留到第四幕。\n\n`;
const body=data.scenes.map(s=>`## ${s.title}\n\n阶段：${s.tag}\n\n画面建议：${s.visual}\n\n核心看点：${s.focus}\n\n动效节奏：${s.beats.join(' → ')}。\n\n${s.frames.map((f,i)=>`第 ${i+1} 步画面：${f.note}`).join('\n\n')}\n\n${s.lines.map(l=>`【${l.name}｜${l.action}】\n「${l.text}」`).join('\n\n')}\n\n接话位置：${s.transition}\n\n画面口径：${s.sourceLabel}。\n`).join('\n');
fs.writeFileSync(path.join(dir,'第二幕_分页与串词.md'),intro+body+'\n## 素材与流程依据\n\n见 [素材来源](素材来源.md)。Claude 的上一版单城主讲建议保留在 [Claude 主讲方案](Claude主讲方案.md)，本版按用户最新确认的全流程框架调整。\n');
console.log(`Built ${data.scenes.length} scenes and ${data.scenes.reduce((n,s)=>n+s.lines.length,0)} speaking turns.`);

const astraIntro='# Astra · 第二幕连续讲解稿\n\n前两段保留茶话会已说过的内容；从「国度在山水间落脚」起为本轮新增待录制讲稿。其他伙伴的台词保留在合并讲稿中，本文件只收录 Astra。每页可连续讲述，停顿随画面推进，不必逐句交棒。\n\n';
const astraBody=data.scenes.filter(s=>s.lines.some(l=>l.name==='Astra')).map(s=>`## ${s.title}\n\n${s.id==='world'||s.id==='culture'?'现场记录':'新增讲稿 · 待录制'}\n\n画面顺序：${s.beats.join(' → ')}。\n\n${s.lines.filter(l=>l.name==='Astra').map(l=>`【${l.name}｜${l.action}】\n「${l.text}」`).join('\n\n')}\n`).join('\n');
fs.writeFileSync(path.join(dir,'Astra_第二幕连讲稿.md'),astraIntro+astraBody);
