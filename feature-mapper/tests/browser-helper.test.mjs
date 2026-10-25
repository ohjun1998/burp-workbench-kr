import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto, createHash} from 'node:crypto';
import {originOf, pageOf, actionContext, validateConnection, permissionPattern, pickAction, requestBodySignal} from '../browser-helper/mapping.js';
const token = 'a'.repeat(64);
test('loopback pairing excludes remote endpoints and credentials', () => {
  assert.equal(validateConnection('http://127.0.0.1:1234', token), 'http://127.0.0.1:1234');
  for (const url of ['https://127.0.0.1:1234', 'http://evil.test:1234', 'http://x@127.0.0.1:1234', 'http://127.0.0.1:1234/x']) assert.throws(() => validateConnection(url, token));
  assert.throws(() => validateConnection('http://127.0.0.1:1234', 'wrong'));
});
test('page context removes query and fragment data', () => {
  assert.equal(pageOf('https://test/a?token=secret#secret'), 'https://test/a');
  assert.equal(originOf('file:///private'), '');
});
test('actions stay within their tab, frame, origin and short window', () => {
  const action = {tab: 1, frame: 0, time: 1000, page: 'https://test/write', label: '등록'};
  const request = {tabId: 1, frameId: 0, timeStamp: 1010, url: 'https://test/api/posts', type: 'xmlhttprequest'};
  assert.equal(actionContext(action, request), action);
  for (const change of [{tabId: 2}, {frameId: 1}, {timeStamp: 5000}, {type: 'image'}, {url: 'https://other/api/posts'}]) assert.equal(actionContext(action, {...request, ...change}), null);
});
test('content observer sends labels and never reads input values', () => {
  const handlers = {}, messages = [];
  const context = {document: {title: '글 작성', addEventListener: (name, f) => {handlers[name] = f;}}, location: {origin: 'https://test', pathname: '/write'}, chrome: {runtime: {sendMessage: x => {messages.push(x); return Promise.resolve();}}}};
  vm.createContext(context); vm.runInContext(fs.readFileSync(new URL('../browser-helper/content.js', import.meta.url), 'utf8'), context);
  const element = {getAttribute: n => n === 'aria-label' ? '게시글 등록' : '', get value() {throw new Error('input values must not be read');}, textContent: '등록'};
  handlers.click({isTrusted: true, target: {closest: () => element}});
  assert.equal(messages.length, 1); assert.equal(messages[0].label, '게시글 등록');
  handlers.click({isTrusted: false, target: {closest: () => element}});
  assert.equal(messages.length, 1);
  assert.deepEqual(Object.keys(messages[0]).sort(), ['kind', 'label', 'page', 'time', 'title', 'type']);
});
test('helper is an observer without request-blocking or credential permissions', () => {
  const manifest = JSON.parse(fs.readFileSync(new URL('../browser-helper/manifest.json', import.meta.url)));
  assert.equal(manifest.manifest_version, 3);
  assert(!manifest.permissions.includes('webRequestBlocking'));
  assert(!manifest.permissions.includes('cookies'));
  assert.deepEqual(manifest.host_permissions, ['http://127.0.0.1/*']);
});

test("site permission patterns omit ports while origins retain them", () => {
  assert.equal(permissionPattern("http://127.0.0.1:18888"), "http://127.0.0.1/*");
  assert.equal(originOf("http://127.0.0.1:18888"), "http://127.0.0.1:18888");
});

test('a delayed network callback selects its original action, not a newer click', () => {
  const original = {tab: 1, frame: 0, time: 1000, page: 'https://test/write', label: '등록'};
  const newer = {...original, time: 1100, label: '수정'};
  const request = {tabId: 1, frameId: 0, timeStamp: 1020, url: 'https://test/api/posts', type: 'xmlhttprequest'};
  assert.equal(pickAction([original, newer], request), original);
  assert.equal(pickAction([original, newer], {...request, timeStamp: 1110}), newer);
});

test('body evidence hashes exact POST bytes and keeps PATCH API limitations explicit', async () => {
  const bytes = new TextEncoder().encode('{"displayName":"한글 😀"}\r\n');
  const post = await requestBodySignal({method: 'POST', requestBody: {raw: [
    {bytes: bytes.slice(0, 9).buffer}, {bytes: bytes.slice(9).buffer}
  ]}}, webcrypto);
  assert.deepEqual(post, {bodyHash: createHash('sha256').update(bytes).digest('hex'), bodyHashStatus: 'raw'});
  assert.deepEqual(await requestBodySignal({method: 'PATCH'}, webcrypto),
    {bodyHash: '', bodyHashStatus: 'unsupported-method'});
  assert.deepEqual(await requestBodySignal({method: 'POST', requestBody: {formData: {a: ['b']}}}, webcrypto),
    {bodyHash: '', bodyHashStatus: 'unavailable'}, 'parsed form values cannot reconstruct wire bytes');
  assert.deepEqual(await requestBodySignal({method: 'PUT', requestBody: {raw: [{file: '/private/file'}]}}, webcrypto),
    {bodyHash: '', bodyHashStatus: 'unavailable'}, 'file paths never become body bytes');
  assert.deepEqual(await requestBodySignal({method: 'POST', requestBody: {raw: [{bytes: new ArrayBuffer(512 * 1024 + 1)}]}}, webcrypto),
    {bodyHash: '', bodyHashStatus: 'too-large'});
});

test('worker startup waits for saved settings and delivery recovers after an earlier error', async () => {
  let onMessage, onRequest, onRemoved, resolveSettings;
  const session = {deliveryCount: 7, lastError: 'earlier failure'};
  const transmitted = [];
  const lookup = (values, keys) => keys === null ? {...values}
    : typeof keys === 'string' ? {[keys]: values[keys]}
    : {...keys, ...values};
  const chrome = {
    runtime: {id: 'fixture', onMessage: {addListener: fn => {onMessage = fn;}}},
    storage: {
      local: {get: () => new Promise(resolve => {resolveSettings = resolve;})},
      session: {
        get: async keys => lookup(session, keys),
        set: async values => {Object.assign(session, values);},
        remove: async keys => {for (const key of keys) delete session[key];}
      },
      onChanged: {addListener: () => {}}
    },
    tabs: {onRemoved: {addListener: fn => {onRemoved = fn;}}},
    webRequest: {onBeforeRequest: {addListener: fn => {onRequest = fn;}}}
  };
  const context = {chrome, crypto: webcrypto, Date, setTimeout, AbortSignal,
    originOf, pageOf, pickAction, validateConnection, requestBodySignal,
    fetch: async (url, options) => {transmitted.push(JSON.parse(options.body)); return {ok: true, status: 200};}};
  vm.createContext(context);
  const source = fs.readFileSync(new URL('../browser-helper/background.js', import.meta.url), 'utf8').replace(/^import[^\n]+\n/, '');
  vm.runInContext(source, context);
  const time = Date.now();
  let responded = false;
  const answer = new Promise(resolve => {
    const asynchronous = onMessage({type: 'action', label: '등록', kind: 'click', time},
      {id: 'fixture', tab: {id: 1}, frameId: 0, url: 'https://test/write'}, value => {responded = true; resolve(value);});
    assert.equal(asynchronous, true);
  });
  await Promise.resolve();
  assert.equal(responded, false, 'the first click must not be dropped before storage initialization');
  resolveSettings({base: 'http://127.0.0.1:1234', token, origins: ['https://test']});
  assert.equal((await answer).ok, true);
  onRequest({tabId: 1, frameId: 0, timeStamp: time + 10, method: 'POST', url: 'https://test/api/posts', type: 'xmlhttprequest', requestBody: {raw: [{bytes: new TextEncoder().encode('one').buffer}]}});
  for (let attempt = 0; attempt < 30 && session.deliveryCount === 7; attempt++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(session.deliveryCount, 8, 'worker restarts preserve the delivery counter');
  assert.equal(session.lastError, '', 'a recovered delivery clears an obsolete failure');
  assert(session.lastDeliveryAt > 0);
  assert.equal(transmitted.length, 1);
  assert.equal(transmitted[0].label, '등록');
  assert.equal(transmitted[0].bodyHash, '7692c3ad3540bb803c020b3aee66cd8887123234ea0c6e7143c0add73ff431ed');
  assert.equal(transmitted[0].bodyHashStatus, 'raw');
  onRequest({tabId: 1, frameId: 0, timeStamp: time + 20, method: 'PATCH', url: 'https://test/api/users/123', type: 'xmlhttprequest'});
  for (let attempt = 0; attempt < 30 && session.deliveryCount === 8; attempt++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(session.deliveryCount, 9, 'unsupported body observation must not drop PATCH request metadata');
  assert.equal(transmitted[1].method, 'PATCH');
  assert.equal(transmitted[1].bodyHash, '');
  assert.equal(transmitted[1].bodyHashStatus, 'unsupported-method');
  onRemoved(1);
  await Promise.resolve(); await Promise.resolve();
  assert(!Object.keys(session).some(key => key.startsWith('action:1:') || key.startsWith('history:1:')),
    'closed tabs release persisted action history as well as memory');
});
