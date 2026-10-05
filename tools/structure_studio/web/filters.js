import {children, describe, matches, pathLabel, readFilters, writeFilters, roles, roleOf, assetTags, tagsOf} from './functions.js';

const $ = id => document.getElementById(id);
function textElement(tag, text, className = '') {
  const el = document.createElement(tag); el.textContent = text; el.className = className; return el;
}

export function createFilters(rows, onChange) {
  const state = readFilters(location.search);
  let currentId = new URLSearchParams(location.search).get('asset'), selection = [];
  const styles = [...new Set(rows.map(row => row.civilization).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'zh'));
  if (!styles.includes(state.style)) state.style = '';
  $('civilization').replaceChildren(new Option('全部风格', ''), ...styles.map(style => new Option(style, style)));
  for (const [id, key] of [['search', 'search'], ['civilization', 'style'], ['planning-role', 'role'], ['asset-tag', 'assetTag'], ['frontage-filter', 'frontage'], ['raw-tag', 'tag'], ['site-search', 'site'], ['match-mode', 'mode']]) {
    const el = $(id);
    el.value = state[key];
    el.addEventListener(el.tagName === 'INPUT' ? 'input' : 'change', () => {
      state[key] = el.value; update();
    });
  }
  $('clear-filters').onclick = () => {
    Object.assign(state, {search: '', style: '', role: '', assetTag: '', frontage: '', tag: '', site: '', functions: [], mode: 'all'});
    selection = [];
    for (const [id, value] of [['search', ''], ['civilization', ''], ['frontage-filter', ''], ['site-search', ''], ['match-mode', 'all']]) $(id).value = value;
    update();
  };
  $('add-function').onclick = () => addFunction(selection.at(-1));

  function addFunction(id) {
    if (!id || state.functions.includes(id)) return;
    state.functions.push(id); update();
  }
  function syncURL() { history.replaceState(null, '', writeFilters(state, currentId)); }
  function renderChoices(counts) {
    $('function-path').replaceChildren();
    let parent = '', depth = 0;
    while (children(parent).length) {
      const select = document.createElement('select');
      select.id = `function-level-${depth}`;
      select.setAttribute('aria-label', depth === 0 ? '功能大类' : `第 ${depth + 1} 级功能`);
      select.append(new Option(depth === 0 ? '选择功能大类…' : '停在当前类别 / 继续细分…', ''));
      for (const node of children(parent)) select.append(new Option(`${node.label} · ${counts.get(node.id) ?? 0}`, node.id));
      select.value = selection[depth] ?? '';
      const level = depth;
      select.onchange = () => {
        selection = selection.slice(0, level);
        if (select.value) selection.push(select.value);
        renderChoices(counts);
        $(`function-level-${level}`)?.focus();
      };
      $('function-path').append(select);
      if (!selection[depth]) break;
      parent = selection[depth++];
    }
    const selected = selection.at(-1);
    $('add-function').disabled = !selected || state.functions.includes(selected);
    $('function-choice').textContent = selected ? pathLabel(selected) : '可选大类，也可继续选到具体用途';
  }
  function refresh() {
    const roleCounts = new Map();
    for (const row of rows.filter(row => matches(row, {...state, role: ''}))) {
      const id = roleOf(row).id;
      roleCounts.set(id, (roleCounts.get(id) ?? 0) + 1);
    }
    $('planning-role').replaceChildren(new Option('全部规划角色', ''), ...roles.map(role => new Option(`${role.label} · ${roleCounts.get(role.id) ?? 0}`, role.id)));
    $('planning-role').value = state.role;
    const assetCounts = new Map();
    for (const row of rows.filter(row => matches(row, {...state, assetTag: ''})))
      for (const tag of tagsOf(row)) assetCounts.set(tag.id, (assetCounts.get(tag.id) ?? 0) + 1);
    $('asset-tag').replaceChildren(new Option('全部结构标签', ''), ...assetTags.map(tag => new Option(`${tag.label} · ${assetCounts.get(tag.id) ?? 0}`, tag.id)));
    $('asset-tag').value = state.assetTag;
    const counts = new Map(), tags = new Map();
    for (const row of rows.filter(row => matches(row, {...state, functions: [], tag: ''}))) {
      for (const id of describe(row).expanded) counts.set(id, (counts.get(id) ?? 0) + 1);
      for (const tag of row.function_terms ?? []) tags.set(tag, (tags.get(tag) ?? 0) + 1);
    }
    if (state.tag && !tags.has(state.tag)) tags.set(state.tag, 0);
    $('raw-tag').replaceChildren(new Option('全部原始标签', ''), ...[...tags].sort(([a], [b]) => a.localeCompare(b, 'zh')).map(([tag, count]) => new Option(`${tag} · ${count}`, tag)));
    $('raw-tag').value = state.tag;
    renderChoices(counts);
    $('selected-functions').replaceChildren();
    for (const id of state.functions) {
      const chip = textElement('button', `${pathLabel(id)} ×`, 'filter-chip');
      chip.title = '移除此用途'; chip.setAttribute('aria-label', `移除 ${pathLabel(id)}`);
      chip.onclick = () => { state.functions = state.functions.filter(value => value !== id); update(); };
      $('selected-functions').append(chip);
    }
    $('match-mode').disabled = state.functions.length < 2;
    const active = state.search || state.style || state.role || state.assetTag || state.frontage || state.tag || state.site || state.functions.length;
    $('clear-filters').disabled = !active;
    $('filter-status').textContent = active
      ? `${state.functions.length ? `${state.functions.length} 项用途 · ${state.mode === 'any' ? '任一具备' : '同时具备'}` : '用途不限'}${state.role ? ` · ${roles.find(role => role.id === state.role).label}` : ''}${state.assetTag ? ` · ${assetTags.find(tag => tag.id === state.assetTag).label}` : ''}${state.tag ? ` · 原始标签：${state.tag}` : ''}${state.site ? ' · 检索选址文字' : ''}`
      : '全部结构 · 可按用途逐层缩小范围';
  }
  function update() { refresh(); syncURL(); onChange(); }
  function renderDetails(row) {
    $('asset-tags').replaceChildren();
    for (const tag of tagsOf(row)) {
      const button = textElement('button', tag.label, 'path-tag');
      button.onclick = () => { state.assetTag = tag.id; update(); };
      $('asset-tags').append(button);
    }
    if (!tagsOf(row).length) $('asset-tags').append(textElement('span', '未标注结构标签'));
    $('function-tags').replaceChildren();
    const meanings = describe(row);
    for (const id of meanings.leaves) {
      const button = textElement('button', pathLabel(id), 'path-tag');
      button.title = '按此用途筛选列表，不修改标签'; button.onclick = () => addFunction(id); $('function-tags').append(button);
    }
    if (!meanings.leaves.length) $('function-tags').append(textElement('p', '尚无已归类的功能'));
    $('original-tags').replaceChildren();
    for (const term of row.function_terms ?? []) {
      const button = textElement('button', term, 'raw-tag');
      button.title = '按此原始标签精确筛选';
      button.onclick = () => { state.tag = term; update(); };
      $('original-tags').append(button);
    }
    if (!(row.function_terms ?? []).length) $('original-tags').append(textElement('span', '未标注'));
    $('tag-note').textContent = meanings.unmapped.length
      ? `尚未归类：${meanings.unmapped.join('、')}；仍可按原始标签查找。`
      : '分类依据作者用途标记；点击功能路径或原始标签可加入筛选。';
    $('terrain').replaceChildren(); $('recommended').replaceChildren();
    for (const [key, value] of Object.entries(row.terrain ?? {})) {
      const display = Array.isArray(value) ? value.join('；') : String(value);
      if (key === '推荐情境') $('recommended').append(textElement('p', display));
      else {
        const entry = document.createElement('div');
        entry.append(textElement('strong', key), textElement('span', display)); $('terrain').append(entry);
      }
    }
    if (!$('terrain').children.length) $('terrain').append(textElement('p', '暂无选址说明'));
    if (!$('recommended').children.length) $('recommended').append(textElement('p', '未指定推荐情境'));
  }
  refresh();
  return {
    refresh,
    get state() { return state; },
    results() { return rows.filter(row => matches(row, state)); },
    select(row) { currentId = row.id; syncURL(); renderDetails(row); },
  };
}
