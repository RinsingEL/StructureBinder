import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const base = process.env.STUDIO_URL ?? 'http://127.0.0.1:8765';
const browser = await chromium.launch({headless: true, executablePath: process.env.STUDIO_CHROMIUM,
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
fs.mkdirSync('runtime', {recursive: true});
try {
  const rows = await (await fetch(`${base}/api/catalog`)).json();
  assert.equal(rows.length, 171);
  assert.equal(new Set(rows.map(row => row.civilization)).size, 6);
  assert.ok(!rows.some(row => row.id.startsWith('FIXTURE')));
  assert.equal((await fetch(`${base}/api/model?id=FIXTURE-01`)).status, 404);
  const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
  page.setDefaultTimeout(15000);
  const errors = []; page.on('pageerror', err => errors.push(String(err)));
  const ready = () => page.waitForFunction(() => window.studio?.telemetry.ready, null, {timeout: 30000});
  const count = () => page.locator('#assets button').count();
  const reset = () => page.locator('#clear-filters').click();
  async function checkRender() {
    assert.deepEqual(await page.evaluate(() => window.studio.telemetry.renderErrors), []);
    assert.deepEqual(await page.evaluate(() => window.studio.telemetry.missingTextures), []);
  }
  // Migrate an old link, including a retired role filter, without an invisible restriction.
  await page.goto(`${base}/?asset=CH-22-v01&style=${encodeURIComponent('中式木构')}&category=landscape&role=fill`);
  await ready();
  assert.equal(await page.locator('#structure-category').inputValue(), '');
  assert.equal(await page.locator('#asset-tag').inputValue(), 'landscape');
  assert.equal(new URL(page.url()).searchParams.has('role'), false);
  assert.equal(await page.locator('#planning-role').count(), 0);
  assert.doesNotMatch(await page.locator('#subtitle').innerText(), /规划角色|核心|填充/);
  await page.locator('#structure-category').selectOption('specialty');
  assert.equal(await count(), 1);
  assert.match(await page.locator('#assets').innerText(), /CH-22-v01/);
  assert.ok(await page.locator('#selection-note').isHidden());
  await page.reload(); await ready();
  assert.equal(await count(), 1);
  assert.equal(await page.locator('#structure-category').inputValue(), 'specialty');
  assert.equal(await page.locator('#asset-tag').inputValue(), 'landscape');
  await checkRender();
  await page.screenshot({path: 'runtime/filter-review-desktop.png'});
  // Detail chips retain both identities.
  await reset();
  await page.locator('#category-tags button').click();
  await page.locator('#asset-tags button').filter({hasText: '景观'}).click();
  assert.match(await page.locator('#assets').innerText(), /CH-22-v01/);
  await page.locator('#asset-tag').selectOption('infrastructure');
  assert.equal(await count(), 0);
  assert.ok(await page.locator('#selection-note').isVisible());
  await reset(); assert.equal(await count(), rows.length);
  // Common identity is compatible with an old author key; it does not choose a district core.
  await page.evaluate(() => window.studio.load('CH-19-v01'));
  await page.locator('#civilization').selectOption('中式木构');
  await page.locator('#structure-category').selectOption('common');
  assert.match(await page.locator('#assets').innerText(), /CH-19-v01/);
  assert.doesNotMatch(await page.locator('#assets').innerText(), /核心|填充/);
  assert.doesNotMatch(await page.locator('#subtitle').innerText(), /规划角色/);
  await checkRender();
  async function addUse(path) {
    for (const [level, id] of path.entries()) await page.locator(`#function-level-${level}`).selectOption(id);
    await page.locator('#add-function').click();
  }
  await addUse(['commerce', 'retail']);
  await addUse(['storage', 'storage.goods']);
  assert.match(await page.locator('#assets').innerText(), /CH-04-v01/);
  await addUse(['housing', 'housing.family']);
  assert.equal(await count(), 1);
  assert.match(await page.locator('#assets').innerText(), /CH-14-v01/);
  await page.locator('#match-mode').selectOption('any');
  assert.ok(await count() > 1);
  await reset();
  await page.locator('#search').fill('CH-19 政务');
  assert.equal(await count(), 1);
  await reset();
  await page.locator('.extra-filters summary').click();
  await page.locator('#raw-tag').selectOption('政务办理');
  assert.match(await page.locator('#assets').innerText(), /CH-19-v01/);
  await page.locator('#site-search').fill('这个说明不存在');
  assert.equal(await count(), 0);
  await reset();
  // Existing stale evidence remains pending, never promoted by the catalog change.
  await page.evaluate(() => window.studio.load('DS-10-v01'));
  assert.match(await page.locator('#checks').innerText(), /视觉验收待完成/);
  await checkRender();
  await page.locator('main').evaluate(el => { el.scrollTop = el.scrollHeight; });
  await page.screenshot({path: 'runtime/filter-review-details.png'});
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.setViewportSize({width: 760, height: 900});
  await page.screenshot({path: 'runtime/filter-review-narrow.png'});
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  assert.deepEqual(errors, []);
  console.log('PASS: six pools, fixture isolation, independent identity/tags, legacy URL migration, role retirement, AND/OR, search, empty/reset, pending review, responsive layout, NBT preview');
} finally { await browser.close(); }
