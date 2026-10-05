/** Standing annotations may deliberately use the same pos and approach. */
export function walkPose(point, size) {
  const p=point?.approach??point?.pos??[size[0]/2,2,size[2]-2];
  const eye=[p[0]+.5,p[1]+1.62,p[2]+.5];
  let yaw={north:0,south:Math.PI,east:-Math.PI/2,west:Math.PI/2}[point?.facing]??0;
  let pitch=0;
  const focus=point?.look_at??(point?.approach?point.pos:undefined);
  if(focus) {
    const dx=focus[0]+.5-eye[0],dy=focus[1]+.5-eye[1],dz=focus[2]+.5-eye[2];
    const horizontal=Math.hypot(dx,dz);
    // Looking at one's own feet makes lookAt parallel to its up vector.
    // Keep the declared facing unless an explicit look target was requested.
    if(horizontal>1e-6||point?.look_at) {
      if(horizontal>1e-6)yaw=Math.atan2(dx,dz);
      pitch=Math.max(-1.5,Math.min(1.5,Math.atan2(dy,horizontal)));
    }
  }
  return {eye,yaw,pitch};
}
