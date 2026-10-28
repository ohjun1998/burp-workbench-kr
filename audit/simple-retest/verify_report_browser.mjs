#!/usr/bin/env node
// Local report UI only. No application or external target traffic is sent.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const directory = path.resolve(process.argv[2] || '');
if (!fs.existsSync(path.join(directory, 'report.html'))) throw new Error('Usage: verify_report_browser.mjs local-report-directory');
const report = JSON.parse(fs.readFileSync(path.join(directory, 'report.json'), 'utf8'));
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
const page = await context.newPage();
const pageErrors = [], external = [], checks = [];
page.on('pageerror', error => pageErrors.push(error.message));
await page.route('**/*', route => {
  const url = route.request().url();
  if (/^https?:/i.test(url)) { external.push(url); return route.abort(); }
  return route.continue();
});
function check(name, passed) { checks.push({ name, passed: Boolean(passed) }); if (!passed) throw new Error(name); }
try {
  // Render the explicitly read report string. No browser filesystem navigation
  // or server is necessary for this self-contained HTML artifact.
  await page.setContent(fs.readFileSync(path.join(directory, 'report.html'), 'utf8'));
  await page.waitForSelector('#values tbody tr');
  check('Initial table contains up to 50 exact-value rows', await page.locator('#values tbody tr').count() === Math.min(50, report.values.length));
  check('Initial count equals complete JSON value count', (await page.locator('#count').textContent()).includes(report.values.length.toLocaleString('en-US')));
  await page.locator('#search').fill('a-query-that-cannot-match-714cf12a');
  await page.waitForTimeout(180);
  check('No-match search has an empty table', await page.locator('#values tbody tr').count() === 0);
  await page.locator('#search').fill('');
  await page.waitForTimeout(180);
  const category = report.values.find(value => value.category === 'token')?.category || report.values[0].category;
  await page.locator('#category').selectOption(category);
  const expected = report.values.filter(value => value.category === category);
  check('Category filter preserves requested category', await page.locator('#values tbody tr').count() === Math.min(50, expected.length));
  const downloadPromise = page.waitForEvent('download');
  await page.locator('#download').click();
  const download = await downloadPromise;
  const downloaded = JSON.parse(fs.readFileSync(await download.path(), 'utf8'));
  check('Filtered JSON download preserves every exact value', JSON.stringify(downloaded) === JSON.stringify(expected));
  await page.locator('#category').selectOption('');
  if (report.values.some(value => value.sourceOrigin === 'testLoginAuth')) {
    await page.locator('#origin').selectOption('testLoginAuth');
    check('Test-login credentials have a separate filter', await page.locator('#values tbody tr').count() === Math.min(50, report.values.filter(value => value.sourceOrigin === 'testLoginAuth').length));
    await page.locator('#origin').selectOption('');
  }
  const first = report.values[0];
  check('Displayed value is character-for-character identical to JSON', await page.locator('#values tbody tr').first().locator('.value').textContent() === first.valueExact);
  check('Page has no horizontal overflow outside the table', await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  check('Report executes without browser script errors', pageErrors.length === 0);
  check('Report has zero external HTTP requests', external.length === 0);
  await page.screenshot({ path: path.join(directory, 'html-report-preview.png'), fullPage: false });
  fs.writeFileSync(path.join(directory, 'browser-validation.json'), JSON.stringify({ passed: true, browser: await browser.version(), checks, pageErrors, externalRequestCount: external.length, limitation: 'Local report HTML only; not an application browser or native Burp GUI test.' }, null, 2) + '\n');
  process.stdout.write(JSON.stringify({ passed: true, checks: checks.length, exactValues: report.values.length }) + '\n');
} finally { await browser.close(); }
