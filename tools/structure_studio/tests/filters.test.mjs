import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {functions, describe, matches, readFilters, writeFilters, roleOf} from '../web/functions.js';

const root = fileURLToPath(new URL('../../../asset_catalogs/original_civilizations/', import.meta.url));
const rows = fs.readdirSync(root, {withFileTypes: true}).filter(d => d.isDirectory()).flatMap(style => {
  const models = path.join(root, style.name, 'models');
  if (!fs.existsSync(models)) return [];
  return fs.readdirSync(models, {withFileTypes: true}).filter(d => d.isDirectory()).flatMap(model => {
    const author = path.join(models, model.name, 'author.json');
    return fs.existsSync(author) ? [JSON.parse(fs.readFileSync(author, 'utf8'))] : [];
  });
});

test('taxonomy has unique IDs, valid parents and no loops', () => {
  const ids = new Set(functions.map(n => n.id));
  assert.equal(ids.size, functions.length);
  for (const node of functions) {
    const seen = new Set([node.id]);
    let parent = node.parent;
    while (parent) {
      assert.ok(ids.has(parent), parent); assert.ok(!seen.has(parent), parent);
      seen.add(parent); parent = functions.find(n => n.id === parent).parent;
    }
  }
});
test('a specific use matches parents, but a broad retail tag cannot imply bread or baking', () => {
  const shop = {function_terms: ['果蔬销售']};
  assert.ok(matches(shop, {functions: ['retail.food', 'commerce']}));
  assert.ok(!matches(shop, {functions: ['retail.food.bread']}));
  const generic = {name: '面包铺', function_terms: ['零售']};
  assert.ok(!matches(generic, {functions: ['retail.food.bread']}));
  assert.ok(!matches(generic, {functions: ['production.food.baking']}));
});
test('legacy runtime terms share broad categories without inventing a shop subtype', () => {
  assert.ok(matches({function_terms: ['商业']}, {functions: ['commerce']}));
  assert.ok(!matches({function_terms: ['商业']}, {functions: ['retail']}));
  assert.ok(matches({function_terms: ['住宅']}, {functions: ['housing']}));
  assert.ok(!matches({function_terms: ['住宅']}, {functions: ['housing.family']}));
  assert.deepEqual(describe({function_terms: ['教育文化']}).unmapped, ['教育文化']);
});
test('AND, OR and empty selection have different semantics', () => {
  const house = {function_terms: ['家庭居住']};
  assert.ok(!matches(house, {functions: ['housing', 'retail'], mode: 'all'}));
  assert.ok(matches(house, {functions: ['housing', 'retail'], mode: 'any'}));
  assert.ok(matches(house, {functions: []}));
});
test('planning role groups core roles and intersects every function mode', () => {
  for (const planning_role of ['planning_role.key', 'planning_role.anchor']) {
    const row = {planning_role, civilization: '北欧', function_terms: ['家庭居住']};
    assert.ok(matches(row, {role: 'core', style: '北欧', functions: ['housing']}));
    assert.ok(!matches(row, {role: 'fill', functions: ['housing', 'retail'], mode: 'any'}));
  }
  assert.ok(matches({planning_role: 'planning_role.fill'}, {role: 'fill'}));
  for (const planning_role of ['planning_role.structure', 'planning_role.self_contained', 'invalid', undefined]) {
    assert.ok(!matches({planning_role}, {role: 'core'}));
    assert.ok(!matches({planning_role}, {role: 'fill'}));
  }
  assert.equal(roleOf({planning_role: 'planning_role.self_contained'}).label, '完整组合');
  assert.ok(matches({}, {role: 'unknown'}));
});
test('style, original tag and word search remain independent intersecting filters', () => {
  const row = {id: 'ABC-01', name: '工具铺', civilization: '北欧', function_terms: ['工具维修', '零售']};
  assert.ok(matches(row, {search: 'ａｂｃ 工具', style: '北欧', tag: '工具维修'}));
  assert.ok(!matches(row, {tag: '工具维护'}));
  assert.ok(!matches(row, {style: '魔法学院'}));
  assert.ok(matches(row, {search: '商业 器具维修'}));
});
test('site text excludes optional contexts and does not infer terrain feasibility', () => {
  const row = {terrain: {选址: '稳定承载，入口同高', 推荐情境: ['海湾聚落']}};
  assert.ok(matches(row, {site: '承载 入口'}));
  assert.ok(!matches(row, {site: '海湾'}));
  assert.ok(!matches({}, {site: '承载'}));
});
test('historic use and incidental descriptions do not grant active abilities', () => {
  const ruin = {function_terms: ['育苗设施遗存'], rooms: [{name: '员工床位'}]};
  assert.ok(matches(ruin, {functions: ['culture.display']}));
  assert.ok(!matches(ruin, {functions: ['farming', 'housing'], mode: 'any'}));
  assert.ok(!matches({function_terms: ['园艺换盆']}, {functions: ['farming.nursery']}));
});
test('unknown author tags remain searchable without inventing a category', () => {
  const row = {function_terms: ['新用途']};
  assert.deepEqual(describe(row).unmapped, ['新用途']);
  assert.ok(matches(row, {tag: '新用途'})); assert.ok(matches(row, {search: '新用途'}));
  assert.ok(!matches(row, {functions: ['retail']}));
});
test('URL state survives reload and rejects invalid function IDs', () => {
  const state = {search: '烘焙 商业', style: '蒸汽朋克', role: 'fill', tag: '零售', site: '入口', functions: ['retail', 'housing.family'], mode: 'any'};
  const query = writeFilters(state, 'SR-F01-v01');
  assert.deepEqual(readFilters(query), state);
  assert.equal(new URLSearchParams(query).get('asset'), 'SR-F01-v01');
  assert.deepEqual(readFilters('?functions=retail,missing,retail').functions, ['retail']);
  assert.equal(readFilters('?role=invalid').role, '');
});
test('all current assets are covered without changing author data; real combinations stay precise', () => {
  assert.equal(rows.length, 498);
  const before = JSON.stringify(rows);
  assert.deepEqual([...new Set(rows.flatMap(row => describe(row).unmapped))], []);
  const bakery = rows.filter(row => matches(row, {functions: ['retail.food.bread', 'production.food.baking']}));
  assert.deepEqual(bakery.map(r => r.id).sort(), ['AA-F01-v01', 'CN-F01-v01', 'DS-F01-v01', 'MF-F01-v01', 'ML-F01-v01', 'SR-F01-v01']);
  assert.deepEqual(bakery.filter(row => matches(row, {functions: ['housing.family']})).map(r => r.id), ['SR-F01-v01']);
  assert.equal(rows.filter(row => matches(row, {style: '北欧', functions: ['storage.goods']})).length, 4);
  assert.equal(rows.filter(row => matches(row, {style: '空艇幻想', functions: ['housing.shared']})).length, 6);
  assert.equal(rows.filter(row => matches(row, {style: '北欧', functions: ['production.food.baking']})).length, 0);
  assert.equal(JSON.stringify(rows), before);
});
