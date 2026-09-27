// Author's explicit exterior standing level. Missing records stay unmarked.
export function groundPlane(model) {
  const author=model.author??{};
  if(!Object.hasOwn(author,'ground_plane'))return {status:'unmarked',y:null,note:'',message:'外部地面未标'};
  const p=author.ground_plane;
  if(!p||typeof p!=='object'||Array.isArray(p)||!Number.isInteger(p.y)||p.y<0||p.y>=model.size[1]||typeof p.note!=='string'||!p.note.trim())
    return {status:'invalid',y:null,note:'',message:'外部地面标记无效：需模板高度内的整数 Y 与非空说明'};
  return {status:'marked',y:p.y,note:p.note,message:`外部地面 Y=${p.y}`};
}
export function previewContext(model) {
  const plane=groundPlane(model),spec=model.author?.preview_context;
  if(plane.status==='invalid')throw new Error(plane.message);
  if(plane.status!=='marked')return spec;
  return {...(spec??{kind:'flat'}),land_surface_y:plane.y};
}
