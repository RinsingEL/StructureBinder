"""Create an ungenerated world with existing pack/worldgen settings, never copying chunks or planning artifacts."""
import gzip, struct, sys
from pathlib import Path

raw = memoryview(gzip.decompress(Path(sys.argv[1]).read_bytes()))
offset = 0
def read(fmt):
    global offset
    size = struct.calcsize(fmt)
    value = struct.unpack_from(fmt, raw, offset)[0]
    offset += size
    return value
def blob(size):
    global offset
    value = bytes(raw[offset:offset+size]); offset += size
    return value
def string(): return blob(read('>H')).decode('utf-8')
def payload(kind):
    if kind in range(1, 7): return read({1:'>b',2:'>h',3:'>i',4:'>q',5:'>f',6:'>d'}[kind])
    if kind == 7: return blob(read('>i'))
    if kind == 8: return string()
    if kind == 9:
        child, count = read('>B'), read('>i')
        return (child, [payload(child) for _ in range(count)])
    if kind == 10:
        values = {}
        while (child := read('>B')):
            name = string(); values[name] = (child, payload(child))
        return values
    if kind in (11,12): return [read('>i' if kind == 11 else '>q') for _ in range(read('>i'))]
    raise ValueError(kind)
def text(value):
    encoded = value.encode('utf-8'); return struct.pack('>H',len(encoded)) + encoded
def encode(kind,value):
    if kind in range(1,7): return struct.pack({1:'>b',2:'>h',3:'>i',4:'>q',5:'>f',6:'>d'}[kind],value)
    if kind == 7: return struct.pack('>i',len(value)) + value
    if kind == 8: return text(value)
    if kind == 9: return struct.pack('>Bi',value[0],len(value[1])) + b''.join(encode(value[0],v) for v in value[1])
    if kind == 10: return b''.join(bytes([t])+text(k)+encode(t,v) for k,(t,v) in value.items())+b'\0'
    if kind in (11,12): return struct.pack('>i',len(value))+b''.join(struct.pack('>i' if kind==11 else '>q',v) for v in value)
    raise ValueError(kind)

kind, name = read('>B'), string()
root = payload(kind)
assert offset == len(raw)
data = root['Data'][1]
data.pop('Player',None)
for key in ('WanderingTraderId','DragonFight'): data.pop(key,None)
data.update({'LevelName':(8,sys.argv[3]), 'Time':(4,0), 'DayTime':(4,0),
             'GameType':(3,1), 'allowCommands':(1,1), 'initialized':(1,0), 'WasModded':(1,1)})
data['WorldGenSettings'][1]['seed'] = (4,int(sys.argv[4]))
target = Path(sys.argv[2])
target.mkdir(parents=True,exist_ok=False)
(target/'level.dat').write_bytes(gzip.compress(bytes([kind])+text(name)+encode(kind,root)))
print(f'Fresh ungenerated world: {target}; seed={sys.argv[4]}; no chunks/player/planning data copied')
