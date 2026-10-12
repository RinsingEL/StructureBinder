import {Mesh,Quad,Vector} from 'deepslate';

// Full D3 surface uses its native grid. The Java construction window remains
// a hole so this background cannot cover excavated terraces or authored blocks.
export function createTerrainContext(gl,context,origin){
 const {bounds,step,detailedBounds:detail}=context;
 const cells=new Map(context.samples.map(s=>[`${s[0]},${s[1]}`,s]));
 const cell=(x,z)=>cells.get(`${Math.min(bounds.maxX-step+1,Math.max(bounds.minX,Math.floor(x/step)*step))},${Math.min(bounds.maxZ-step+1,Math.max(bounds.minZ,Math.floor(z/step)*step))}`);
 const height=(x,z)=>{
  const a=cell(x,z),fx=Math.max(0,Math.min(1,(x-a[0])/step)),fz=Math.max(0,Math.min(1,(z-a[1])/step));
  const b=cell(a[0]+step,a[1]),c=cell(a[0],a[1]+step),d=cell(a[0]+step,a[1]+step);
  return (a[2]*(1-fx)+b[2]*fx)*(1-fz)+(c[2]*(1-fx)+d[2]*fx)*fz+1;
 };
 const xs=[...new Set([bounds.minX,bounds.maxX+1,detail.minX,detail.maxX+1,...context.samples.map(s=>s[0])])].filter(v=>v>=bounds.minX&&v<=bounds.maxX+1).sort((a,b)=>a-b);
 const zs=[...new Set([bounds.minZ,bounds.maxZ+1,detail.minZ,detail.maxZ+1,...context.samples.map(s=>s[1])])].filter(v=>v>=bounds.minZ&&v<=bounds.maxZ+1).sort((a,b)=>a-b);
 const mesh=new Mesh(),stats={waterQuads:0,landQuads:0,shoreQuads:0,bounds,step,waterSampleCount:context.waterSampleCount};
 const point=(x,y,z)=>new Vector(x-origin[0],y-origin[1],z-origin[2]);
 const quad=(coords,color)=>mesh.quads.push(Quad.fromPoints(...coords.map(p=>point(...p))).setColor(color));
 for(let zi=0;zi<zs.length-1;zi++)for(let xi=0;xi<xs.length-1;xi++){
  const x=xs[xi],z=zs[zi],xx=xs[xi+1],zz=zs[zi+1];
  if(context.detailedRegions?context.detailedRegions.some(b=>x>=b.minX&&xx<=b.maxX+1&&z>=b.minZ&&zz<=b.maxZ+1):(x>=detail.minX&&xx<=detail.maxX+1&&z>=detail.minZ&&zz<=detail.maxZ+1))continue;
  const sample=cell((x+xx)/2,(z+zz)/2),water=sample[3];
  const coords=[[x,height(x,z),z],[x,height(x,zz),zz],[xx,height(xx,zz),zz],[xx,height(xx,z),z]];
  if(water){
   coords.forEach(p=>p[1]=context.waterDisplayY);
   const depth=Math.max(0,context.waterDisplayY-sample[2]),blue=[.24-depth*.007,.49-depth*.006,.57-depth*.004];
   quad(coords,blue);stats.waterQuads++;
  }else{
   const shade=(sample[2]-62)*.003;
   quad(coords,[.34+shade,.47+shade,.24+shade]);stats.landQuads++;
   // Close only classified shoreline edges down to the illustrative water plane.
   for(const [a,b,nx,nz] of [[0,1,x-.01,(z+zz)/2],[1,2,(x+xx)/2,zz+.01],[2,3,xx+.01,(z+zz)/2],[3,0,(x+xx)/2,z-.01]]){
    if(nx<bounds.minX||nx>bounds.maxX||nz<bounds.minZ||nz>bounds.maxZ||!cell(nx,nz)[3])continue;
    const p=coords[a],q=coords[b];quad([q,p,[p[0],context.waterDisplayY,p[2]],[q[0],context.waterDisplayY,q[2]]],[.54,.51,.36]);stats.shoreQuads++;
   }
  }
 }
 mesh.rebuild(gl,{pos:true,color:true});
 return {mesh,stats,dispose(){for(const buffer of [mesh.posBuffer,mesh.colorBuffer,mesh.indexBuffer])if(buffer)gl.deleteBuffer(buffer);}};
}
