// Shared author-term meanings; parent tags never imply child functions.
import taxonomy from '../../../src/main/resources/geomantia/catalog/structure_functions.json' with {type: 'json'};
export const functions = taxonomy.functions;
const byId = new Map(functions.map(n => [n.id, n]));
const byTerm = new Map();
for (const n of functions) for (const t of n.terms) byTerm.set(t, [...(byTerm.get(t) ?? []), n.id]);
const meanings = new WeakMap();

// Planning role is independent of function and style; key and anchor are core.
export const roles = taxonomy.roles;
export function roleOf(row) { return roles.find(role => role.values.includes(row.planning_role)) ?? roles.at(-1); }

export function ancestors(id) {
  const result = [];
  for (let node = byId.get(id); node; node = byId.get(node.parent)) result.unshift(node);
  return result;
}
export function pathLabel(id) { return ancestors(id).map(n => n.label).join(' › '); }
export function children(parent = '') { return functions.filter(n => n.parent === parent); }
export function describe(row) {
  if (meanings.has(row)) return meanings.get(row);
  const terms = row.function_terms ?? [];
  const direct = new Set(terms.flatMap(t => byTerm.get(t) ?? []));
  for (const rule of taxonomy.assetRules) if (rule.ids.includes(row.id) && rule.allTerms.every(t => terms.includes(t)))
    rule.functionIds.forEach(id => direct.add(id));
  const expanded = new Set([...direct].flatMap(id => ancestors(id).map(n => n.id)));
  // Display the most specific confirmed paths; retain parents in expanded matches.
  const leaves = [...direct].filter(id => ![...direct].some(other => other !== id && ancestors(other).some(n => n.id === id)));
  const result = {direct: [...direct], leaves, expanded, unmapped: terms.filter(t => !byTerm.has(t))};
  meanings.set(row, result);
  return result;
}
export function siteText(row) {
  return Object.entries(row.terrain ?? {}).filter(([key]) => key !== '推荐情境')
    .map(([key, value]) => `${key} ${Array.isArray(value) ? value.join(' ') : value}`).join(' ');
}
const normalize = value => String(value ?? '').normalize('NFKC').toLocaleLowerCase();
function containsWords(text, query) { return normalize(query).trim().split(/\s+/).every(word => normalize(text).includes(word)); }
export function matches(row, state = {}) {
  const meaning = describe(row);
  if (state.style && row.civilization !== state.style) return false;
  if (state.role && roleOf(row).id !== state.role) return false;
  if (state.tag && !(row.function_terms ?? []).includes(state.tag)) return false;
  if (state.site && !containsWords(siteText(row), state.site)) return false;
  const text = [row.id, row.name, row.civilization, ...(row.function_terms ?? []), ...meaning.leaves.map(pathLabel)].join(' ');
  if (state.search && !containsWords(text, state.search)) return false;
  const selected = state.functions ?? [];
  return !selected.length || (state.mode === 'any'
    ? selected.some(id => meaning.expanded.has(id)) : selected.every(id => meaning.expanded.has(id)));
}
export function readFilters(search) {
  const p = new URLSearchParams(search);
  return {search: p.get('q') ?? '', style: p.get('style') ?? '', tag: p.get('tag') ?? '', site: p.get('site') ?? '',
    role: roles.some(role => role.id === p.get('role')) ? p.get('role') : '',
    functions: [...new Set((p.get('functions') ?? '').split(',').filter(id => byId.has(id)))], mode: p.get('mode') === 'any' ? 'any' : 'all'};
}
export function writeFilters(state, asset) {
  const p = new URLSearchParams();
  if (asset) p.set('asset', asset);
  for (const [key, value] of Object.entries({q: state.search, style: state.style, role: state.role, tag: state.tag, site: state.site,
    functions: state.functions?.join(','), mode: state.mode === 'any' ? 'any' : ''})) if (value) p.set(key, value);
  return `?${p}`;
}
