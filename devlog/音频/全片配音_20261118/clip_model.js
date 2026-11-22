/* Non-destructive source ranges: at is timeline time; in/out are source time. */
(function(root){
 const length=c=>c.out-c.in;
 const split=(c,relative,id)=>{
  if(relative<.01||relative>length(c)-.01)return null;
  return [{...c,out:c.in+relative},{...c,id,at:c.at+relative,in:c.in+relative}];
 };
 const remove=(c,left,right,closeGap,id)=>{
  if(left<0||right>length(c)+1e-6||right-left<.01)return null;
  const pieces=[];
  if(left>=.01)pieces.push({...c,out:c.in+left});
  if(length(c)-right>=.01)pieces.push({...c,id:pieces.length?id:c.id,at:c.at+(closeGap?left:right),in:c.in+right});
  return pieces;
 };
 const move=(c,at,total)=>({...c,at:Math.max(0,Math.min(total-length(c),at))});
 const trim=(c,side,delta,sourceDuration,total)=>{
  if(side==='left'){delta=Math.max(-Math.min(c.in,c.at),Math.min(length(c)-.01,delta));return {...c,at:c.at+delta,in:c.in+delta};}
  delta=Math.max(.01-length(c),Math.min(sourceDuration-c.out,total-c.at-length(c),delta));return {...c,out:c.out+delta};
 };
 const api={length,split,remove,move,trim};if(typeof module!=='undefined')module.exports=api;else root.ClipOps=api;
})(globalThis);
