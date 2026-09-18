import { realpath, stat, readdir, readFile } from 'node:fs/promises';
import { resolve, relative, isAbsolute, extname } from 'node:path';
import { createHash } from 'node:crypto';

export async function artifactTools(rootPath) {
  const root = await realpath(rootPath);
  const inside = path => { const rel = relative(root, path); return rel !== '..' && !rel.startsWith('..\\') && !rel.startsWith('../') && !isAbsolute(rel); };
  async function locate(path = '.') {
    const requested = resolve(root, path);
    if (!inside(requested)) throw new Error('ARTIFACT_PATH_OUTSIDE_RUN');
    const actual = await realpath(requested);
    if (!inside(actual)) throw new Error('ARTIFACT_PATH_OUTSIDE_RUN');
    return actual;
  }
  const text = value => [{ type: 'input_text', text: JSON.stringify(value) }];
  const schemas = {
    artifact_list: { path: { type: 'string' } },
    artifact_search: { path: { type: 'string' }, query: { type: 'string' } },
    artifact_read_text: { path: { type: 'string' }, startLine: { type: 'integer', minimum: 1 }, lineCount: { type: 'integer', minimum: 1, maximum: 200 }, offset: {type:'integer',minimum:0} },
    artifact_view_image: { path: { type: 'string' } },
  };
  const descriptions = {
    artifact_list: 'List a directory in the current planning run. Paths are relative to the artifact root. Read-only.',
    artifact_search: 'Find files by case-insensitive filename/path substring in the current planning run; does not follow directory links. Read-only.',
    artifact_read_text: 'Read JSON, text or Markdown with line numbers. Use startLine/lineCount, or character offset/nextOffset for very long single-line JSON. Read-only.',
    artifact_view_image: 'Open a chosen PNG/JPEG/WebP as actual visual input. Call as needed for detail or comparison; no fixed image count. Read-only.',
  };
  return Object.entries(schemas).map(([name, properties]) => ({ name, description: descriptions[name],
    parameters: { type: 'object', properties, additionalProperties: false,
      required: name === 'artifact_search' ? ['query'] : name.startsWith('artifact_read') || name === 'artifact_view_image' ? ['path'] : [] },
    async execute(args = {}) {
      try {
        const path = await locate(args.path);
        if (name === 'artifact_list') {
          const entries = (await readdir(path, { withFileTypes: true })).sort((a,b) => a.name.localeCompare(b.name));
          return text({ root, path: relative(root,path), entries: entries.slice(0,300).map(e => ({ name:e.name, kind:e.isSymbolicLink()?'link':e.isDirectory()?'directory':'file' })), truncated:entries.length>300 });
        }
        if (name === 'artifact_search') {
          if (typeof args.query !== 'string' || !args.query.trim()) throw new Error('SEARCH_QUERY_REQUIRED');
          const queue=[path], matches=[]; let visited=0;
          while(queue.length && visited<5000 && matches.length<200) {
            const dir=await locate(queue.shift());
            for(const entry of (await readdir(dir,{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name))) {
              if (++visited>5000 || matches.length>=200) break;
              if(entry.isSymbolicLink()) continue;
              const child=resolve(dir,entry.name), rel=relative(root,child);
              if(entry.isDirectory()) queue.push(child);
              else if(rel.toLowerCase().includes(args.query.toLowerCase())) matches.push(rel);
            }
          }
          return text({matches,truncated:queue.length>0 || visited>=5000 || matches.length>=200});
        }
        const info=await stat(path);
        if(!info.isFile() || info.size>8*1024*1024) throw new Error('ARTIFACT_NOT_FILE_OR_TOO_LARGE');
        const extension=extname(path).toLowerCase();
        if(name==='artifact_read_text') {
          if(!['.json','.jsonl','.txt','.md','.csv'].includes(extension)) throw new Error('ARTIFACT_TEXT_TYPE_UNSUPPORTED');
          const raw=await readFile(path,'utf8');
          const lines=raw.split(/\r?\n/);
          if(args.offset !== undefined || lines.some(line=>line.length>100000)) {
            const offset=Math.max(0,args.offset??0), chunk=raw.slice(offset,offset+100000);
            return text({path,offset,text:chunk,nextOffset:offset+chunk.length<raw.length?offset+chunk.length:null});
          }
          const start=Math.max(1,args.startLine??1), count=Math.max(1,Math.min(200,args.lineCount??80));
          const selected=lines.slice(start-1,start-1+count).map((s,i)=>`${start+i}: ${s}`).join('\n');
          return text({path, totalLines:lines.length, startLine:start, text:selected.slice(0,100000), truncated:selected.length>100000, nextLine:start+count<=lines.length?start+count:null});
        }
        const mime={'.png':'image/png','.jpg':'image/jpeg','.jpeg':'image/jpeg','.webp':'image/webp'}[extension];
        if(!mime) throw new Error('ARTIFACT_IMAGE_TYPE_UNSUPPORTED');
        const bytes=await readFile(path);
        return [...text({path,sha256:createHash('sha256').update(bytes).digest('hex')}),
          {type:'input_image',image_url:`data:${mime};base64,${bytes.toString('base64')}`}];
      } catch(error) { return text({ok:false,error:error.message}); }
    },
  }));
}
