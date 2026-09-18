import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,symlink,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import sharp from 'sharp';
import {artifactTools} from '../src/artifact-tools.mjs';

test('artifact tools browse, search, read actual pixels and reject escapes', async()=>{
 const home=await mkdtemp(join(tmpdir(),'geomantia-artifacts-'));
 try {
  const root=join(home,'run');await mkdir(root);await mkdir(join(home,'outside'));
  await writeFile(join(home,'outside','secret.txt'),'outside');
  await writeFile(join(root,'state.json'),'line1\nline2\nline3');
  const png=await sharp({create:{width:10,height:10,channels:3,background:'red'}}).png().toBuffer();
  await writeFile(join(root,'overview.png'),png);
  await symlink(join(home,'outside'),join(root,'escape'),'junction');
  const tools=await artifactTools(root);
  const run=(name,args)=>tools.find(t=>t.name===name).execute(args);
  const payload=r=>JSON.parse(r[0].text);
  assert.ok(payload(await run('artifact_list',{})).entries.some(e=>e.name==='overview.png'));
  assert.deepEqual(payload(await run('artifact_search',{query:'overview'})).matches,['overview.png']);
  assert.match(payload(await run('artifact_read_text',{path:'state.json',startLine:2,lineCount:1})).text,/2: line2/);
  for(const path of ['../outside/secret.txt',join(home,'outside','secret.txt'),'escape/secret.txt'])
   assert.equal(payload(await run('artifact_read_text',{path})).error,'ARTIFACT_PATH_OUTSIDE_RUN');
  assert.deepEqual(payload(await run('artifact_search',{query:'secret'})).matches,[]);
  const image=await run('artifact_view_image',{path:'overview.png'});
  assert.deepEqual(Buffer.from(image[1].image_url.split(',')[1],'base64'),png);
 } finally {await rm(home,{recursive:true,force:true});}
});
