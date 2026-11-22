"""Local-only preview server. Root is devlog so linked artwork is available."""
from pathlib import Path
from http.server import ThreadingHTTPServer,SimpleHTTPRequestHandler
from functools import partial
import argparse,re,json,io,wave,hashlib,os
from urllib.parse import urlsplit
ap=argparse.ArgumentParser();ap.add_argument('--port',type=int,default=8790);args=ap.parse_args()
root=Path(__file__).resolve().parents[3]
allowed={ (root/p).resolve() for p in json.loads(Path(__file__).with_name('access_manifest.json').read_text(encoding='utf8')) }
imports=Path(__file__).resolve().parent/'imports'
if imports.exists():
    allowed.update(p.resolve() for p in imports.glob('*.wav') if re.fullmatch(r'[0-9a-f]{64}\.wav',p.name))
class PreviewHandler(SimpleHTTPRequestHandler):
    def do_POST(self):
        # Local editor file selection only; arbitrary paths and cross-origin writes are rejected.
        if urlsplit(self.path).path!='/api/import-audio':self.send_error(404);return
        host=self.headers.get('Host','');origin=self.headers.get('Origin','')
        if host not in [f'127.0.0.1:{args.port}',f'localhost:{args.port}'] or origin!=f'http://{host}':self.send_error(403);return
        try:
            size=int(self.headers.get('Content-Length','0'))
            if not 44<size<=64*1024*1024:raise ValueError('音频过大或为空')
            body=self.rfile.read(size)
            if len(body)!=size:raise ValueError('上传不完整')
            with wave.open(io.BytesIO(body),'rb') as w:
                channels=w.getnchannels();rate=w.getframerate();frames=w.getnframes();width=w.getsampwidth();pcm=w.readframes(frames)
                if channels not in (1,2) or width!=2 or not 8000<=rate<=192000 or len(pcm)!=frames*channels*width:raise ValueError('无效PCM音频')
                duration=frames/rate
                if not .01<=duration<=1200:raise ValueError('音频时长超出范围')
            digest=hashlib.sha256(body).hexdigest();imports.mkdir(exist_ok=True)
            dest=imports/(digest+'.wav')
            try:
                with dest.open('xb') as f:f.write(body)
            except FileExistsError:pass
            allowed.add(dest.resolve())
            result=json.dumps({'id':'import-'+digest,'url':'imports/'+dest.name,'duration':duration,'gain_db':0}).encode('utf8')
            self.send_response(201);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(result)));self.end_headers();self.wfile.write(result)
        except (ValueError,wave.Error,EOFError):self.send_error(400,'Invalid audio upload')
    def send_head(self):
        self.remaining=None
        p=Path(self.translate_path(self.path)).resolve()
        if p not in allowed or not p.is_file():
            self.send_error(403);return None
        request=self.headers.get('Range')
        if not request or not p.is_file():return super().send_head()
        size=p.stat().st_size;m=re.fullmatch(r'bytes=(\d*)-(\d*)',request)
        if not m or not any(m.groups()):self.send_error(416);return None
        start=int(m[1]) if m[1] else max(0,size-int(m[2]))
        end=min(size-1,int(m[2])) if m[1] and m[2] else size-1
        if start>end or start>=size:self.send_error(416);return None
        f=p.open('rb');f.seek(start);self.remaining=end-start+1
        self.send_response(206);self.send_header('Content-Type',self.guess_type(str(p)))
        self.send_header('Content-Length',str(self.remaining));self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
        self.send_header('Accept-Ranges','bytes');self.end_headers();return f
    def end_headers(self):
        self.send_header('Accept-Ranges','bytes');super().end_headers()
    def copyfile(self,source,outputfile):
        try:
            if self.remaining is None:return super().copyfile(source,outputfile)
            while self.remaining:
                buf=source.read(min(65536,self.remaining))
                if not buf:break
                outputfile.write(buf);self.remaining-=len(buf)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
ThreadingHTTPServer(('127.0.0.1',args.port),partial(PreviewHandler,directory=str(root))).serve_forever()
