#!/usr/bin/env node
// Capture the real locally running VulnBank UI. Never substitute target responses.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {createRequire} from 'node:module';

const require = createRequire(import.meta.url);
const {chromium} = require('playwright');
const options = new Map();
for (let i = 2; i < process.argv.length; i += 2) options.set(process.argv[i], process.argv[i + 1]);
if (!options.get('--state') || !options.get('--out')) {
  throw Error('Usage: node capture_bank.mjs --state /private/private-state.json --out /report/images/bank');
}
const state = JSON.parse(fs.readFileSync(options.get('--state'), 'utf8'));
const base = new URL(state.base).origin;
if (!['127.0.0.1', 'localhost', '[::1]'].includes(new URL(base).hostname)
    || new URL(base).protocol !== 'http:') throw Error('Only a loopback HTTP bank is supported.');
const output = path.resolve(options.get('--out'));
if (fs.existsSync(path.join(output, 'verification.json')) || fs.existsSync(path.join(output, '01-login.png'))) {
  throw Error('Preserve previous browser captures; choose a fresh output directory.');
}
fs.mkdirSync(output, {recursive: true});
const alice = state.actors.find(actor => actor.name === 'wb_alice');
const bob = state.actors.find(actor => actor.name === 'wb_bob');
if (!alice || !bob || !state.password) throw Error('The seeded Alice/Bob test accounts are required.');
const checks = [];
const captures = [];
const responses = [];
const blocked = [];
const requestFailures = [];
const dialogs = [];
let errorCount = 0;
let consoleErrorCount = 0;
let browser;
let failure;
const secretValues = state.actors.map(actor => actor.token).filter(Boolean);
const assert = (condition, message) => { if (!condition) throw Error(message); };
async function check(id, title, operation) {
  const detail = await operation();
  checks.push({id, title, result: 'passed', ...(detail === undefined ? {} : {detail})});
}

try {
  browser = await chromium.launch({
    executablePath: process.env.WB_CHROMIUM || '/usr/bin/chromium',
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-background-networking', '--disable-extensions'],
  });
  const context = await browser.newContext({
    viewport: {width: 1440, height: 1000}, deviceScaleFactor: 1,
    serviceWorkers: 'block', locale: 'en-US', colorScheme: 'light',
  });
  await context.route('**/*', async route => {
    let url;
    try { url = new URL(route.request().url()); } catch { return route.abort(); }
    if (url.origin === base) return route.continue();
    blocked.push({origin: url.origin, path: url.pathname, method: route.request().method()});
    return route.abort();
  });
  if (context.routeWebSocket) await context.routeWebSocket('**/*', socket => socket.close());
  const page = await context.newPage();
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.origin === base) responses.push({path: url.pathname, method: response.request().method(), status: response.status()});
  });
  page.on('pageerror', () => { errorCount++; });
  page.on('requestfailed', request => {
    const url = new URL(request.url());
    requestFailures.push({origin: url.origin, path: url.pathname, type: request.resourceType(),
      local: url.origin === base, error: request.failure()?.errorText || 'unreported'});
  });
  page.on('console', message => { if (message.type() === 'error') consoleErrorCount++; });
  page.on('dialog', async dialog => {
    // Dialog values can contain input. Preserve only the type, then dismiss it.
    dialogs.push({type: dialog.type()}); await dialog.dismiss();
  });
  async function acknowledgeTrainingNotice() {
    const acknowledge = page.locator('#disclaimer-acknowledge');
    if (await acknowledge.isVisible()) {
      await acknowledge.click();
      await page.locator('#vuln-disclaimer').waitFor({state: 'hidden'});
    }
  }
  async function picture(name, label) {
    await page.evaluate(() => document.fonts.ready);
    const visibleText = await page.locator('body').innerText();
    const browserToken = await page.evaluate(() => localStorage.getItem('jwt_token'));
    if (browserToken) secretValues.push(browserToken);
    assert(secretValues.every(token => !visibleText.includes(token)), 'A live token is visible; do not capture this screen.');
    assert(!visibleText.includes(state.password), 'The test password is visibly exposed.');
    // Original application rendering only: no injected CSS, fake DOM, masking or image editing.
    const destination = path.join(output, name);
    await page.screenshot({path: destination, fullPage: false, animations: 'disabled'});
    const bytes = fs.readFileSync(destination);
    captures.push({name, label, page: new URL(page.url()).pathname + new URL(page.url()).hash,
      width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20),
      bytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
      originalBrowserScreenshot: true, visibleTokenOrPassword: false});
  }

  await check('BR-01', '원본 은행 로그인 화면', async () => {
    const response = await page.goto(base + '/login', {waitUntil: 'networkidle'});
    assert(response.status() === 200, 'Login page is unavailable.');
    await page.locator('#username').waitFor({state: 'visible'});
    await acknowledgeTrainingNotice();
    assert(await page.locator('#password').getAttribute('type') === 'password', 'Password input is not masked.');
    await picture('01-login.png', '은행 로그인 화면 · 실제 원본 앱');
    return {status: response.status(), passwordInputType: 'password'};
  });
  await check('BR-02', '실제 로그인 폼으로 Alice 로그인', async () => {
    await page.locator('#username').fill('wb_alice');
    await page.locator('#password').fill(state.password);
    await page.locator('#loginForm button[type=submit]').click();
    await page.waitForURL(base + '/dashboard', {timeout: 15000});
    await page.waitForFunction(() => Boolean(localStorage.getItem('jwt_token')));
    await page.waitForLoadState('networkidle');
    await acknowledgeTrainingNotice();
    assert((await page.locator('#account-number').innerText()).trim() === String(alice.account), 'Dashboard account differs from Alice.');
    await picture('02-dashboard-light.png', '로그인한 Alice 계좌·잔액');
    return {username: 'wb_alice', accountNumber: String(alice.account), displayedBalance: (await page.locator('#balance').innerText()).trim()};
  });
  await check('BR-03', '실제 이체 양식 · 제출 전 상태', async () => {
    await page.locator('.nav-link[href="#transfers"]').click();
    await page.locator('#to_account').fill(String(bob.account));
    await page.locator('#amount').fill('1.00');
    await page.locator('#description').fill('Local Workbench bank demonstration');
    await page.locator('#transferForm').scrollIntoViewIfNeeded();
    await picture('03-transfer-form.png', 'Bob에게 이체하는 실제 양식 · 제출하지 않음');
    return {recipientAccount: String(bob.account), amount: '1.00', submitted: false};
  });
  await check('BR-04', '실제 거래 내역 화면', async () => {
    await page.locator('.nav-link[href="#transactions"]').click();
    await page.locator('#transactions').scrollIntoViewIfNeeded();
    const transactionText = await page.locator('#transaction-list').innerText();
    assert(!transactionText.includes('Loading transactions') && !transactionText.includes('Error loading transactions'),
      'The transaction request did not finish successfully.');
    await picture('04-transactions.png', '실제 거래 내역 · 사이트 응답 기준');
    return {renderedTransactionItems: await page.locator('#transaction-list .transaction-item').count(),
      displayedEmptyState: transactionText.includes('No transactions found')};
  });
  await check('BR-05', '원본 은행 테마 버튼의 다크 화면', async () => {
    await page.locator('.theme-toggle').click();
    await page.waitForFunction(() => document.documentElement.getAttribute('data-theme') === 'dark');
    await page.evaluate(() => window.scrollTo(0, 0));
    await picture('05-dashboard-dark.png', '은행 자체 다크 테마 · Alice 계좌');
    return {theme: await page.locator('html').getAttribute('data-theme')};
  });
  await check('BR-06', '실제 정적 JS/API 응답과 외부 요청 차단', async () => {
    assert(responses.some(row => row.path === '/static/dashboard.js' && row.status === 200), 'The real dashboard JavaScript was not loaded.');
    assert(responses.some(row => row.path === '/login' && row.method === 'POST' && row.status === 200), 'Successful login response is missing.');
    return {localResponses: responses.length, blockedExternalRequests: blocked.length};
  });
  await context.close();
} catch (error) {
  failure = error;
  let reason = error.message;
  for (const secret of [...secretValues, state.password].filter(Boolean)) reason = reason.split(secret).join('[REDACTED]');
  checks.push({id: 'BROWSER_CAPTURE', result: 'failed', reason});
} finally {
  const result = {
    result: failure ? 'failed' : 'passed', capturedAtUTC: new Date().toISOString(),
    browser: browser ? {name: 'Chromium', version: browser.version()} : null,
    baseURL: base, viewport: {width: 1440, height: 1000},
    source: 'Unmodified pinned VulnBank application served on loopback; actual browser form login and original DOM.',
    checks, captures, localResponses: responses, blockedExternalRequests: blocked,
    requestFailures, failedLocalHttpResponses: responses.filter(response => response.status >= 400).length,
    failedLocalResourceRequests: requestFailures.filter(request => request.local).length,
    consoleErrorCount, pageErrorCount: errorCount, dialogs,
    newIsolatedContext: true, externalRequestsBlocked: true,
    originalScreenshots: true, diagnosticVulnerabilityClaims: false,
    transferSubmittedByBrowserCapture: false,
    browserHelperExtensionLoaded: false,
    notRun: ['Native Burp browser', 'Feature Mapper MV3 browser-helper integration'],
  };
  fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify(result, null, 2) + '\n');
  if (browser) await browser.close();
  console.log(JSON.stringify({result: result.result, checks: checks.length, captures: captures.length,
    externalBlocked: blocked.length, output}));
}
if (failure) throw failure;
