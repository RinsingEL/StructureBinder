"""Package the fixed Harness composition + Node; end users need no npm or Python."""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--node', type=Path, required=True)
args = parser.parse_args()
if subprocess.check_output([str(args.node.resolve()), '--version'], text=True, timeout=10).strip() != 'v22.23.2':
    raise SystemExit('Expected official Windows x64 Node v22.23.2')
dist = root / 'geomantia_harness/dist'
version = '0.1.5-rc.2-win-x64'
output = root / 'runtime/harness' / f'harness-runtime-{version}.zip'
output.parent.mkdir(parents=True, exist_ok=True)
files = [(args.node.resolve(), 'node.exe')]
files.append((args.node.resolve().parent / 'LICENSE', 'NODE_LICENSE.txt'))
if not (dist / 'main.mjs').is_file():
    raise SystemExit('Build geomantia_harness first')
inputs = json.loads((dist / 'build-inputs.json').read_text())
for name, expected in inputs.items():
    if hashlib.sha256((root / 'geomantia_harness' / name).read_bytes()).hexdigest() != expected:
        raise SystemExit(f'Stale Harness bundle: {name}; rebuild first')
files += [(p, p.relative_to(dist).as_posix()) for p in sorted(dist.rglob('*')) if p.is_file() and p.name != 'metafile.json']
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path, name in files:
        info = zipfile.ZipInfo(f'harness-{version}/{name}', (2026, 9, 17, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, path.read_bytes())
digest = hashlib.sha256(output.read_bytes()).hexdigest().upper()
java = root / 'src/main/java/com/rinsing/geomantia/systems/provider/application/HarnessPortableRuntime.java'
text = java.read_text(encoding='utf-8')
java.write_text(re.sub(r'ARCHIVE_SHA256 = "[^"]+"', f'ARCHIVE_SHA256 = "{digest}"', text), encoding='utf-8')
(output.parent / 'manifest.json').write_text(json.dumps({
    'harnessVersion': '0.1.5-rc.2', 'nodeVersion': '22.23.2',
    'nodeSha256': hashlib.sha256(args.node.read_bytes()).hexdigest(),
    'archiveSha256': digest, 'inputs': inputs,
}, indent=2) + '\n', encoding='utf-8')
print(f'{output.name}: {output.stat().st_size} bytes; sha256={digest}')
