"""Build and view isolated city-subject experiments using the existing workbench."""
import argparse
import json
import shutil
import sys
from pathlib import Path
from http.server import ThreadingHTTPServer

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import workbench


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('case', choices=['market', 'craft', 'woodland'])
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--serve', action='store_true')
    parser.add_argument('--port', type=int, default=8771)
    args = parser.parse_args()
    workbench.OUTPUT = workbench.ROOT / 'runtime/city-subjects-20261010' / args.case
    workbench.SCENE = HERE / (args.case + '.json')
    if args.build:
        source = workbench.ROOT / 'runtime/campus-workbench/terrain'
        target = workbench.OUTPUT / 'terrain'
        target.mkdir(parents=True, exist_ok=True)
        for name in ('dem-step1.json', 'manifest.json'):
            if (source / name).exists():
                shutil.copy2(source / name, target / name)
        result = workbench.build(workbench.read(workbench.SCENE))
        print(json.dumps({'case': args.case, 'summary': result['summary'],
                          'evidence': result['evidenceDirectory']}, ensure_ascii=False), flush=True)
    if args.serve:
        if not (workbench.OUTPUT / 'state.json').exists():
            parser.error('Build this case before serving it.')
        class Handler(workbench.Handler):
            def do_GET(self):
                if self.path.split('?')[0] == '/api/campus/examples':
                    return self.json([{'id': args.case, 'name': workbench.read(HERE/'subjects.json')[args.case]['title'],
                                       'scene': workbench.read(workbench.SCENE)}])
                return super().do_GET()
        server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
        print(f'{args.case}: http://127.0.0.1:{args.port}', flush=True)
        server.serve_forever()


if __name__ == '__main__':
    main()
