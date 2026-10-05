// Shared author-term meanings; parent tags never imply child functions.
import taxonomy from '../../../src/main/resources/geomantia/catalog/structure_functions.json' with {type: 'json'};
import catalogRules from '../catalog.json' with {type: 'json'};
export const functions = taxonomy.functions;
const byId = new Map(functions.map(n => [n.id, n]));
const byTerm = new Map();
for (const n of functions) for (const t of n.terms) byTerm.set(t, [...(byTerm.get(t) ?? []), n.id]);
const meanings = new WeakMap();

// One author-side rule table is shared by the API and detail/list rendering.
export const categories = catalogRules.categories;
const specialtyFamilies = new Set(catalogRules.specialty_families);
export function categoryOf(row) {
  const explicit = categories.find(c => c.id === row.category);
  if (explicit) return explicit;
  const specialty = specialtyFamilies.has(row.family) || specialtyFamilies.has((row.id ?? '').split('-v')[0]);
  return categories.find(c => c.id === (specialty ? 'specialty' : 'common'));
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
  // Old four-category links become supporting-tag filters; retired role filters are ignored.
  if (assetTags.some(tag => tag.id === p.get('category')) && !assetTags.some(tag => tag.id === p.get('assetTag')))
    p.set('assetTag', p.get('category'));
  return {search: p.get('q') ?? '', style: p.get('style') ?? '', tag: p.get('tag') ?? '', site: p.get('site') ?? '',
    category: categories.some(cat => cat.id === p.get('category')) ? p.get('category') : '',
    assetTag: assetTags.some(tag => tag.id === p.get('assetTag')) ? p.get('assetTag') : '',
    frontage: ['pending', 'ready'].includes(p.get('frontage')) ? p.get('frontage') : '',
    functions: [...new Set((p.get('functions') ?? '').split(',').filter(id => byId.has(id)))], mode: p.get('mode') === 'any' ? 'any' : 'all'};
}
export function writeFilters(state, asset) {
  const p = new URLSearchParams();
  if (asset) p.set('asset', asset);
  for (const [key, value] of Object.entries({q: state.search, style: state.style, category: state.category, assetTag: state.assetTag, frontage: state.frontage, tag: state.tag, site: state.site,
    functions: state.functions?.join(','), mode: state.mode === 'any' ? 'any' : ''})) if (value) p.set(key, value);
  return `?${p}`;
}
