const $ = id => document.getElementById(id);
const directions = {north: '北 −Z', south: '南 +Z', east: '东 +X', west: '西 −X'};

export function createFrontageEditor({focus, saved, reload}) {
  let model = null, dirty = false, busy = false;
  function controls() {
    const fixed = $('frontage-policy').value === 'FIXED_FRONT';
    $('frontage-primary').disabled = !fixed || busy;
    $('frontage-policy').disabled = !model || busy || !$('frontage-primary').options.length;
    $('frontage-save').disabled = !model || busy || !$('frontage-policy').value
      || (fixed && !$('frontage-primary').value);
    $('frontage-reload').disabled = busy;
  }
  function select(data) {
    model = data; dirty = false;
    $('frontage-entrances').replaceChildren();
    $('frontage-primary').replaceChildren(new Option('请选择主入口…', ''));
    const points = data?.author.points.filter(p => p.kind === 'entrance') ?? [];
    for (const point of points) {
      const label = `${point.id} · ${point.name} · ${directions[point.facing?.toLowerCase()] ?? '朝向未标'} · (${point.pos.join(', ')})`;
      $('frontage-primary').append(new Option(label, point.id));
      const button = document.createElement('button'); button.textContent = label;
      button.title = '在预览中定位此入口'; button.onclick = () => focus(point);
      $('frontage-entrances').append(button);
    }
    const state = data?.frontage;
    const decision = state?.status === 'ready' ? state : data?.author.frontage;
    $('frontage-policy').value = decision?.policy ?? '';
    $('frontage-primary').value = decision?.entrance_id ?? '';
    $('frontage-status').textContent = state?.message ?? '正在读取入口…';
    $('frontage-status').className = state?.status === 'ready' ? 'good' : 'warning';
    controls();
    $('frontage-policy').disabled ||= !points.length;
  }
  for (const id of ['frontage-policy', 'frontage-primary']) $(id).onchange = () => {
    dirty = true; controls(); $('frontage-status').textContent = '尚未保存';
    $('frontage-status').className = 'warning';
    if (id === 'frontage-primary') {
      const point = model?.author.points.find(p => p.kind === 'entrance' && p.id === $(id).value);
      if (point) focus(point);
    }
  };
  $('frontage-save').onclick = async () => {
    const source = model;
    if (!source || busy) return;
    busy = true; controls();
    try {
      const response = await fetch('/api/frontage', {method: 'POST',
        headers: {'Content-Type': 'application/json', 'X-Studio-Write': 'frontage'},
        body: JSON.stringify({id: source.author.id, author_sha256: source.author_sha256, nbt_sha256: source.sha256,
          policy: $('frontage-policy').value,
          entrance_id: $('frontage-policy').value === 'FIXED_FRONT' ? $('frontage-primary').value : ''})});
      const result = await response.json();
      if (!response.ok) throw new Error(result.error ?? '保存失败');
      saved(source, result);
      if (model === source) {
        Object.assign(source, result); select(source);
        $('frontage-status').textContent = '已保存。重新导出素材包后，规划时才会使用这项标注。';
      }
    } catch (error) {
      if (model === source) { $('frontage-status').textContent = error.message; $('frontage-status').className = 'warning'; }
    } finally { busy = false; controls(); }
  };
  $('frontage-reload').onclick = () => { if (model) reload(model.author.id); };
  function canLeave() { return !busy && (!dirty || window.confirm('入口标注尚未保存，放弃本次修改？')); }
  window.addEventListener('beforeunload', event => { if (dirty || busy) { event.preventDefault(); event.returnValue = ''; } });
  return {select, canLeave};
}
