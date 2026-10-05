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

// Structure categories: specialty, common, infrastructure, landscape
export const categories = [
  { id: 'specialty', label: '文化特色' },
  { id: 'common', label: '通用功能' },
  { id: 'infrastructure', label: '基础设施' },
  { id: 'landscape', label: '自然景观' }
];
const specialtyPrefixes = [
  'CH-13', 'CH-16', 'CH-17', 'CH-18', 'CH-21', 'CH-22',
  'EU-01', 'EU-02', 'EU-13', 'EU-16',
  'EL-01', 'EL-02', 'EL-03', 'EL-04', 'EL-13', 'EL-14', 'EL-16',
  'DV-01', 'DV-02', 'DV-04', 'DV-13', 'DV-15', 'DV-16',
  'MG-01', 'MG-02', 'MG-03', 'MG-04', 'MG-14', 'MG-15', 'MG-16',
  'DS-03', 'DS-10', 'DS-11', 'DS-12'
];
export function categoryOf(row) {
  if (row.category) return categories.find(c => c.id === row.category) ?? categories[1];
  if (Array.isArray(row.asset_tags)) {
    if (row.asset_tags.includes('infrastructure')) return categories.find(c => c.id === 'infrastructure');
    if (row.asset_tags.includes('landscape')) return categories.find(c => c.id === 'landscape');
  }
  const id = row.id ?? '', family = row.family ?? '';
  if (specialtyPrefixes.some(p => id.startsWith(p) || family.startsWith(p))) return categories.find(c => c.id === 'specialty');
  return categories.find(c => c.id === 'common');
}

// Asset tags are explicit and independent of both cultural role and function.
export const assetTags = [{id: 'infrastructure', label: '基础设施'}, {id: 'landscape', label: '景观'}];
export function tagsOf(row) { return assetTags.filter(tag => Array.isArray(row.asset_tags) && row.asset_tags.includes(tag.id)); }

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
  if (state.category && categoryOf(row).id !== state.category) return false;
  if (state.role && roleOf(row).id !== state.role) return false;
  if (state.assetTag && !tagsOf(row).some(tag => tag.id === state.assetTag)) return false;
  if (state.frontage === 'pending' && row.frontage?.status === 'ready') return false;
  if (state.frontage === 'ready' && row.frontage?.status !== 'ready') return false;
  if (state.tag && !(row.function_terms ?? []).includes(state.tag)) return false;
  if (state.site && !containsWords(siteText(row), state.site)) return false;
  const text = [row.id, row.name, row.civilization, categoryOf(row).label, ...tagsOf(row).map(tag => tag.label), ...(row.function_terms ?? []), ...meaning.leaves.map(pathLabel)].join(' ');
  if (state.search && !containsWords(text, state.search)) return false;
  const selected = state.functions ?? [];
  return !selected.length || (state.mode === 'any'
    ? selected.some(id => meaning.expanded.has(id)) : selected.every(id => meaning.expanded.has(id)));
}
export function readFilters(search) {
  const p = new URLSearchParams(search);
  return {search: p.get('q') ?? '', style: p.get('style') ?? '', tag: p.get('tag') ?? '', site: p.get('site') ?? '',
    category: categories.some(cat => cat.id === p.get('category')) ? p.get('category') : '',
    role: roles.some(role => role.id === p.get('role')) ? p.get('role') : '',
    assetTag: assetTags.some(tag => tag.id === p.get('assetTag')) ? p.get('assetTag') : '',
    frontage: ['pending', 'ready'].includes(p.get('frontage')) ? p.get('frontage') : '',
    functions: [...new Set((p.get('functions') ?? '').split(',').filter(id => byId.has(id)))], mode: p.get('mode') === 'any' ? 'any' : 'all'};
}
export function writeFilters(state, asset) {
  const p = new URLSearchParams();
  if (asset) p.set('asset', asset);
  for (const [key, value] of Object.entries({q: state.search, style: state.style, category: state.category, role: state.role, assetTag: state.assetTag, frontage: state.frontage, tag: state.tag, site: state.site,
    functions: state.functions?.join(','), mode: state.mode === 'any' ? 'any' : ''})) if (value) p.set(key, value);
  return `?${p}`;
}
