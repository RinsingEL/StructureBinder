// UI save is intercepted: never mark a real author's asset during automated QA.
import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {mkdir} from 'node:fs/promises';
const base = process.env.STUDIO_URL ?? 'http://127.0.0.1:8765';
const browser = await chromium.launch({headless: true, executablePath: process.env.STUDIO_CHROMIUM,
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
try {
  const page = await browser.newPage({viewport: {width: 1440, height: 1050}});
  const errors = []; page.on('pageerror', error => errors.push(String(error)));
  const source = await (await fetch(`${base}/api/model?id=DS-F04-v03`)).json();
  // Exercise review-required editing even though unmarked assets now default to the first entrance.
  source.frontage = {status: 'stale', message: '多个入口的旧标注需复核。'};
  await page.route('**/api/catalog', async route => {
    const response = await route.fetch(); const rows = await response.json();
    const row = rows.find(row => row.id === 'DS-F04-v03'); row.frontage = source.frontage;
    rows.find(row => row.id === 'DS-02-v04').frontage = source.frontage;
    await route.fulfill({json: rows});
  });
  let saved, attempts = 0;
  await page.route('**/api/frontage', async route => {
    const input = route.request().postDataJSON();
    assert.equal(input.id, 'DS-F04-v03');
    assert.equal(input.policy, 'FIXED_FRONT'); assert.equal(input.entrance_id, 'side');
    assert.equal(input.author_sha256, source.author_sha256);
    attempts++;
    if (attempts === 1) return route.fulfill({status: 409, json: {error: '作者记录已被修改，请刷新模型后重新标注。'}});
    saved = {...source, author: {...source.author, frontage: {policy: input.policy, entrance_id: input.entrance_id}},
      author_sha256: 'ui-test-hash', frontage: {status: 'ready', policy: input.policy, entrance_id: input.entrance_id,
        manual: true, message: '正面配置已保存。'}};
    return route.fulfill({json: {author: saved.author, author_sha256: saved.author_sha256, frontage: saved.frontage}});
  });
  await page.route('**/api/model?id=DS-F04-v03', route => route.fulfill({json: saved ?? source}));
  await page.goto(`${base}/?asset=DS-F04-v03&style=${encodeURIComponent('沙漠')}&frontage=pending`);
  await page.waitForFunction(() => window.studio?.telemetry.ready);
  assert.match(await page.locator('#frontage-status').innerText(), /多个入口/);
  assert.ok(await page.locator('#frontage-save').isDisabled());
  await page.locator('#frontage-entrances button').filter({hasText: 'side'}).click();
  assert.match(await page.locator('#view-note').innerText(), /side/);
  await page.locator('#frontage-policy').selectOption('ANY_AUTHORED_ENTRANCE');
  assert.ok(await page.locator('#frontage-primary').isDisabled());
  await page.locator('#frontage-policy').selectOption('FIXED_FRONT');
  assert.ok(await page.locator('#frontage-save').isDisabled());
  await page.locator('#frontage-primary').selectOption('side');
  await page.locator('#frontage-save').click();
  await page.waitForFunction(() => document.getElementById('frontage-status').textContent.includes('已被修改'));
  assert.match(await page.locator('#frontage-status').innerText(), /刷新模型/);
  await page.locator('#frontage-save').click();
  await page.waitForFunction(() => window.studio.model.author_sha256 === 'ui-test-hash');
  assert.equal(await page.locator('#assets button').filter({hasText: 'DS-F04-v03'}).count(), 0);
  await page.locator('#frontage-reload').click();
  await page.waitForFunction(() => window.studio.telemetry.ready && window.studio.model.author_sha256 === 'ui-test-hash');
  assert.equal(await page.locator('#frontage-primary').inputValue(), 'side');
  await page.locator('#frontage-next').click();
  await page.waitForFunction(() => window.studio.telemetry.ready && window.studio.model.author.id !== 'DS-F04-v03');
  // Capture the genuine unmarked model after resetting the mocked state.
  saved = undefined;
  await page.evaluate(() => window.studio.load('DS-F04-v03'));
  await page.locator('main').evaluate(el => el.scrollTop = 0);
  await mkdir('runtime/frontage-qa', {recursive: true});
  await page.screenshot({path: 'runtime/frontage-qa/editor.png'});
  assert.deepEqual(errors, []);
  assert.deepEqual(windowSafe(await page.evaluate(() => window.studio.telemetry)), []);
  console.log('PASS: entrance focus, policy, conflict, save/reload, pending filter, next asset; no real asset writes');
} finally { await browser.close(); }
function windowSafe(telemetry) { return [...telemetry.renderErrors, ...telemetry.missingTextures]; }
