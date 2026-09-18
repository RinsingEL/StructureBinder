// Use logged surface replacements, not hidden request-body mutations. Persisted
// sessions remain replayable; original attachments stay in the audit log.
export function pruneImageHistory(session, newHostTurn = false) {
  const seen=new Set();
  for(const seq of [...session.surface.nodes].reverse()) {
    const event=session.eventAt(seq);
    if(!['user/message','tool/result'].includes(event.type)) continue;
    const data=structuredClone(event.data); let changed=false;
    function visit(value) {
      if(Array.isArray(value)) return value.map(visit);
      if(!value || typeof value!=='object') return value;
      if(value.type==='image') {
        const key=value.attachment?.attachmentId ?? JSON.stringify(value);
        if(newHostTurn || seen.has(key)) {
          changed=true;
          return {type:'text',text:`[Earlier image omitted from this request: ${key}. Its path and observations remain in history. Use artifact_view_image to inspect it again when needed.]`};
        }
        seen.add(key); return value;
      }
      for(const key of Object.keys(value)) value[key]=visit(value[key]);
      return value;
    }
    visit(data);
    if(changed) session.append(event.type,data,{surfaceOp:{op:'replace',startSeq:seq,endSeq:seq},sourceEventSeqs:[seq]});
  }
}
