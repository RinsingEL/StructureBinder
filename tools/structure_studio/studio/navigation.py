"""Conservative offline walking audit using 1.20.1 collision AABBs.

0.5-block horizontal samples, 0.6-block step height, 0.6 x 1.8 player body.
Wooden doors are considered opened by the player; iron doors stay as authored.
No jumping, flying, ladders, swimming or moving entities are simulated.
"""
from collections import deque
import math

EPS=1e-6


def collision_boxes(block, registry):
    spec=registry.get(block["name"])
    if spec is None or not spec.get("collisions"):
        raise ValueError(f"No collision shape: {block['name']}")
    props={**spec["default"],**block["properties"]}
    if block["name"].endswith("_door") and block["name"]!="minecraft:iron_door":
        props["open"]="true"
    index=0
    for key in spec["state_order"]:
        values=spec["properties"][key]
        index=index*len(values)+values.index(props[key])
    shapes=spec["collisions"]
    shape=shapes[index if len(shapes)>1 else 0]
    if shape is None:raise ValueError(f"Unknown collision for {block['name']}")
    return shape


def audit(data, meta, registry):
    shape_grid={}
    try:
        palette=[collision_boxes(b,registry) for b in data["palette"]]
    except (ValueError,KeyError,IndexError) as exc:
        return dict(passed=False,unsupported=str(exc),unreachable=[],scope=__doc__)
    for block in data["blocks"]:
        x,y,z=block["pos"]
        shape_grid[(x,y,z)]=[(x+a,y+b,z+c,x+d,y+e,z+f) for a,b,c,d,e,f in palette[block["state"]]]
    width,height,depth=data["size"]

    def nearby(x,y,z):
        for bx in range(math.floor(x-.3),math.floor(x+.3)+1):
            for by in range(math.floor(y)-2,math.ceil(y+2.5)):
                for bz in range(math.floor(z-.3),math.floor(z+.3)+1):
                    yield from shape_grid.get((bx,by,bz),())

    def stance(x,y,z):
        if not .3<=x<=width-.3 or not .3<=z<=depth-.3:return None
        boxes=list(nearby(x,y,z))
        overlap=[b for b in boxes if b[0]<x+.3-EPS and b[3]>x-.3+EPS and b[2]<z+.3-EPS and b[5]>z-.3+EPS]
        tops=sorted({b[4] for b in overlap if y-.6-EPS<=b[4]<=y+.6+EPS},reverse=True)
        for top in tops:
            if any(b[1]<top+1.8-EPS and b[4]>top+EPS for b in overlap):continue
            # Minecraft supports the player's footprint at a step edge before
            # its centre passes the riser. Requiring centre support rejects stairs.
            if any(abs(b[4]-top)<EPS for b in overlap):
                return round(top,6)
        return None

    entries=[p for p in meta.get("points",[]) if p["kind"]=="entrance"]
    targets=[p for p in meta.get("points",[]) if "approach" in p or p["kind"] in ("entrance","circulation")]
    if not entries:
        return dict(passed=not targets,unreachable=[p["id"] for p in targets],nodes=0,scope=__doc__)
    visited=set();queue=deque()
    for entry in entries:
        x,y,z=entry.get("approach",entry["pos"]);x+=.5;z+=.5
        sy=stance(x,y,z)
        if sy is not None:
            key=(x,sy,z);visited.add(key);queue.append(key)
    while queue and len(visited)<100_000:
        x,y,z=queue.popleft()
        for dx,dz in ((.5,0),(-.5,0),(0,.5),(0,-.5)):
            nx,nz=x+dx,z+dz
            ny=stance(nx,y,nz)
            if ny is None or (nx,ny,nz) in visited:continue
            # Intermediate body samples reject corner clipping between valid endpoints.
            iy=stance(x+dx*.5,max(y,ny),z+dz*.5)
            if iy is None or abs(iy-y)>.6+EPS or abs(ny-iy)>.6+EPS:continue
            visited.add((nx,ny,nz));queue.append((nx,ny,nz))
    unreachable=[]
    for p in targets:
        x,y,z=p.get("approach",p["pos"])
        if not any((x+.5,round(y+dy,6),z+.5) in visited for dy in (0,.0625,-.0625)):
            unreachable.append(p["id"])
    return dict(passed=not unreachable and not queue,unreachable=unreachable,nodes=len(visited),highest_standing_y=max((p[1] for p in visited),default=None),
                truncated=bool(queue),scope=__doc__)
