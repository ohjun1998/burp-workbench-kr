import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {originOf, pageOf, actionContext, validateConnection, permissionPattern, pickAction, BodyObservations} from '../browser-helper/mapping.js';
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
  const context = {window: {postMessage() {}, addEventListener() {}}, document: {title: '글 작성', addEventListener: (name, f) => {handlers[name] = f;}}, location: {origin: 'https://test', pathname: '/write'}, chrome: {storage: {local: {get: async () => ({origins: []})}, onChanged: {addListener() {}}}, runtime: {sendMessage: x => {messages.push(x); return Promise.resolve();}}}};
  vm.createContext(context); vm.runInContext(fs.readFileSync(new URL('../browser-helper/content.js', import.meta.url), 'utf8'), context);
  const element = {getAttribute: n => n === 'aria-label' ? '게시글 등록' : '', get value() {throw new Error('input values must not be read');}, textContent: '등록'};
  handlers.click({isTrusted: true, target: {closest: () => element}});
  assert.equal(messages.length, 1); assert.equal(messages[0].label, '게시글 등록');
  handlers.click({isTrusted: false, target: {closest: () => element}});
  assert.equal(messages.length, 1);
  assert.deepEqual(Object.keys(messages[0]).sort(), ['kind', 'label', 'page', 'time', 'title', 'type']);
});
test('normal mapping is observational without blocking or credential permissions; explicit inspection is separate', () => {
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

test('worker startup waits for saved settings and delivery recovers after an earlier error', async () => {
  let onMessage, onRequest, onRemoved, onSettingsChanged, resolveSettings;
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
      onChanged: {addListener: listener => {onSettingsChanged = listener;}}
    },
    tabs: {onRemoved: {addListener: fn => {onRemoved = fn;}}},
    webRequest: {onBeforeRequest: {addListener: fn => {onRequest = fn;}}}
  };
  const context = {chrome, crypto: webcrypto, Date, setTimeout, AbortSignal,
    originOf, pageOf, pickAction, validateConnection, BodyObservations,
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
  onSettingsChanged({origins: {newValue: ['https://test']}}, 'local');
  resolveSettings({base: 'http://127.0.0.1:1234', token, origins: []});
  assert.equal((await answer).ok, true);
  onRequest({tabId: 1, frameId: 0, timeStamp: time + 10, method: 'POST', url: 'https://test/api/posts', type: 'xmlhttprequest', requestBody: {raw: [{bytes: new TextEncoder().encode('one').buffer}]}});
  for (let attempt = 0; attempt < 30 && session.deliveryCount === 7; attempt++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(session.deliveryCount, 8, 'worker restarts preserve the delivery counter');
  assert.equal(session.lastError, '', 'a recovered delivery clears an obsolete failure');
  assert(session.lastDeliveryAt > 0);
  assert.equal(transmitted.length, 1);
  assert.equal(transmitted[0].label, '등록');
  assert.equal(transmitted[0].bodyHash, '7692c3ad3540bb803c020b3aee66cd8887123234ea0c6e7143c0add73ff431ed');
  assert.equal(transmitted[0].bodyHashSource, 'web-request');
  const patchTime = Date.now();
  const patchHash = 'a'.repeat(64);
  await new Promise(resolve => onMessage({type: 'request-body', method: 'PATCH', url: 'https://test/api/profile',
    time: patchTime, bodyHash: patchHash, size: 20},
  {id: 'fixture', tab: {id: 1}, frameId: 0, url: 'https://test/write', documentId: 'doc'}, resolve));
  onRequest({tabId: 1, frameId: 0, documentId: 'doc', timeStamp: patchTime + 1, method: 'PATCH',
    url: 'https://test/api/profile', type: 'xmlhttprequest'});
  for (let attempt = 0; attempt < 40 && transmitted.length < 2; attempt++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(transmitted[1].bodyHash, patchHash, 'PATCH without Chrome raw bytes can use a unique observed digest');
  assert.equal(transmitted[1].bodyHashSource, 'page-observation');
  onRequest({tabId: 1, frameId: 0, documentId: 'doc', timeStamp: Date.now(), method: 'PATCH',
    url: 'https://test/api/unverified', type: 'xmlhttprequest'});
  for (let attempt = 0; attempt < 40 && !session.bodyUnverifiedCount; attempt++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(session.bodyUnverifiedCount, 1);
  assert.equal(transmitted.length, 2, 'unverified body requests never degrade into URL-only attribution');
  onRemoved(1);
  await Promise.resolve(); await Promise.resolve();
  assert(!Object.keys(session).some(key => key.startsWith('action:1:') || key.startsWith('history:1:')),
    'closed tabs release persisted action history as well as memory');
});

function bodyObserver(crypto = webcrypto) {
  const posted = [], calls = [], handlers = {};
  const fetchResult = Promise.resolve('native result');
  const window = {addEventListener: (name, fn) => {handlers[name] = fn;},
    postMessage: value => posted.push(value), fetch: (...args) => {calls.push(args); return fetchResult;}};
  class Xhr { open(...args) {this.openArgs = args;} send(body) {this.sentBody = body; return 'sent';} }
  const context = {window, location: {origin: 'https://test', href: 'https://test/write'},
    crypto, URL, URLSearchParams, TextEncoder, Uint8Array, ArrayBuffer, DataView, Blob, XMLHttpRequest: Xhr};
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(new URL('../browser-helper/request-body.js', import.meta.url), 'utf8'), context);
  const enable = enabled => handlers.message({source: window, origin: 'https://test', data: {source: 'feature-mapper-body-control-v1', enabled}});
  const hashes = () => posted.filter(x => x.source === 'feature-mapper-body-v1');
  return {window, Xhr, posted, calls, fetchResult, enable, hashes};
}
async function waitHashes(observer, count) {
  for (let i = 0; i < 100 && observer.hashes().length < count; i++) await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(observer.hashes().length, count);
}
async function sha(text) {
  return Array.from(new Uint8Array(await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode(text))), x => x.toString(16).padStart(2, '0')).join('');
}
test('MAIN observer preserves fetch/XHR behavior and hashes UTF-8 POST/PATCH without forwarding body bytes', async () => {
  const observer = bodyObserver(); observer.enable(true);
  const first = '{"이름":"하나"}', second = '{"이름":"둘"}';
  assert.equal(observer.window.fetch('/api/profile', {method: 'POST', body: first}), observer.fetchResult);
  const xhr = new observer.Xhr(); xhr.open('PATCH', '/api/profile');
  assert.equal(xhr.send(second), 'sent'); assert.equal(xhr.sentBody, second);
  await waitHashes(observer, 2);
  const post = observer.hashes().find(x => x.method === 'POST'), patch = observer.hashes().find(x => x.method === 'PATCH');
  assert.equal(post.bodyHash, await sha(first)); assert.equal(patch.bodyHash, await sha(second));
  assert.equal(post.size, new TextEncoder().encode(first).length);
  assert.deepEqual(Object.keys(post).sort(), ['bodyHash', 'method', 'size', 'source', 'time', 'url']);
  assert(!JSON.stringify(observer.posted).includes('하나'));
});
test('body observation is bounded, opt-in, same-origin and skips unsupported bodies/accessors', async () => {
  const observer = bodyObserver();
  observer.window.fetch('/api/profile', {method: 'PATCH', body: 'disabled'});
  observer.enable(true);
  observer.window.fetch('https://other/api/profile', {method: 'PATCH', body: 'other'});
  observer.window.fetch('/api/profile', {method: 'PATCH', body: 'x'.repeat(512 * 1024 + 1)});
  observer.window.fetch('/api/profile', {method: 'PATCH', body: {unsupported: true}});
  let getterCalls = 0;
  observer.window.fetch('/api/profile', {method: 'PATCH', get body() {getterCalls++; return 'getter';}});
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(observer.hashes().length, 0); assert.equal(getterCalls, 0);
  const data = new TextEncoder().encode('original');
  observer.window.fetch('/api/profile', {method: 'PATCH', body: data}); data.fill(0);
  await waitHashes(observer, 1);
  assert.equal(observer.hashes()[0].bodyHash, await sha('original'), 'mutable byte inputs are snapshotted');
});
test('page digest matching requires a unique real request and rejects ambiguity, replay and cross-document data', () => {
  const now = Date.now();
  const sender = {tab: {id: 1}, frameId: 0, documentId: 'one', url: 'https://test/write'};
  const body = {method: 'PATCH', url: 'https://test/api/profile', time: now, size: 7, bodyHash: 'a'.repeat(64)};
  const details = {tabId: 1, frameId: 0, documentId: 'one', method: 'PATCH', url: body.url, timeStamp: now + 10};
  const observations = new BodyObservations();
  assert(observations.observe(body, sender, now));
  const request = observations.register(details, now);
  assert.equal(observations.take(request).bodyHash, body.bodyHash);
  assert.equal(observations.take(request), null, 'one digest is never reused');
  assert.equal(observations.observe({...body, url: 'https://other/api'}, sender, now), false);
  assert.equal(observations.observe({...body, size: 512 * 1024 + 1}, sender, now), false);
  const ambiguous = new BodyObservations();
  ambiguous.observe(body, sender, now);
  const first = ambiguous.register(details, now);
  ambiguous.register({...details, timeStamp: now + 20}, now);
  assert.equal(ambiguous.take(first), null, 'concurrent equal-URL requests are not guessed by order');
  const documents = new BodyObservations(); documents.observe(body, sender, now);
  assert.equal(documents.take(documents.register({...details, documentId: 'two'}, now)), null);
  const duplicate = new BodyObservations(); duplicate.observe(body, sender, now); duplicate.observe({...body, bodyHash: 'b'.repeat(64)}, sender, now);
  assert.equal(duplicate.take(duplicate.register(details, now)), null, 'conflicting page observations remain unverified');
  observations.clearTab(1); assert.equal(observations.requests.length, 0); assert.equal(observations.observations.length, 0);
});

test('body snapshots do not execute overridden buffer, view or Blob properties', async () => {
  const observer = bodyObserver(); observer.enable(true);
  let overrides = 0;
  const bytes = new Uint8Array([65]);
  const originalBuffer = bytes.buffer;
  const mutate = () => { overrides++; bytes[0] = 66; throw new Error('custom body accessor'); };
  Object.defineProperties(bytes.buffer, {slice: {value: mutate}, constructor: {get: mutate}, byteLength: {get: mutate}});
  observer.window.fetch('/api/profile', {method: 'PATCH', body: bytes.buffer});
  Object.defineProperties(bytes, {buffer: {get: mutate}, byteOffset: {get: mutate}, byteLength: {get: mutate}});
  observer.window.fetch('/api/profile', {method: 'PATCH', body: bytes});
  const view = new DataView(new Uint8Array([65]).buffer);
  Object.defineProperties(view, {buffer: {get: mutate}, byteOffset: {get: mutate}, byteLength: {get: mutate}});
  observer.window.fetch('/api/profile', {method: 'PATCH', body: view});
  const blob = new Blob(['A']);
  Object.defineProperties(blob, {size: {get: mutate}, arrayBuffer: {value: mutate}});
  observer.window.fetch('/api/profile', {method: 'PATCH', body: blob});
  await waitHashes(observer, 4);
  assert.equal(overrides, 0); assert.equal(bytes[0], 65);
  for (const result of observer.hashes()) assert.equal(result.bodyHash, await sha('A'));
  assert.equal(observer.calls[0][1].body, originalBuffer, 'native fetch receives the original body object');
});

test('disabled generations cannot publish a digest after re-enabling', async () => {
  let finish;
  const observer = bodyObserver({subtle: {digest: () => new Promise(resolve => { finish = resolve; })}});
  observer.enable(true);
  observer.window.fetch('/api/profile', {method: 'PATCH', body: 'old'});
  observer.enable(false); observer.enable(true);
  finish(new Uint8Array(32).buffer);
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(observer.hashes().length, 0);
});

test('content settings initialization cannot undo a later disable', async () => {
  let resolveSettings, change;
  const posted = [];
  const context = {window: {postMessage: value => posted.push(value), addEventListener() {}},
    document: {addEventListener() {}}, location: {origin: 'https://test'},
    chrome: {storage: {local: {get: () => new Promise(resolve => {resolveSettings = resolve;})},
      onChanged: {addListener: listener => {change = listener;}}}, runtime: {sendMessage: async () => {}}}};
  vm.createContext(context); vm.runInContext(fs.readFileSync(new URL('../browser-helper/content.js', import.meta.url), 'utf8'), context);
  change({origins: {newValue: []}}, 'local');
  resolveSettings({origins: ['https://test']});
  await Promise.resolve(); await Promise.resolve();
  assert.equal(posted.at(-1).enabled, false);
});

test('bounded digest registries fail closed under saturation and recover after stale hashes expire', () => {
  const sender = {tab: {id: 1}, frameId: 0, documentId: 'doc', url: 'https://test/write'};
  const body = {method: 'PATCH', url: 'https://test/api/profile', time: 10000, size: 1, bodyHash: 'a'.repeat(64)};
  const details = {tabId: 1, frameId: 0, documentId: 'doc', method: 'PATCH', url: body.url, timeStamp: 10000};
  const filtered = new BodyObservations();
  const first = filtered.register(details, 10000);
  assert.equal(filtered.take(first), null);
  for (let i = 0; i < 128; i++) filtered.register({...details, tabId: 2, method: 'GET', timeStamp: 10050}, 10050);
  assert.equal(filtered.requests.length, 1, 'bodyless traffic cannot evict request tombstones');
  filtered.observe(body, sender, 10150);
  assert.equal(filtered.take(filtered.register({...details, timeStamp: 10200}, 10200)), null);
  for (const saturated of ['requests', 'observations']) {
    const registry = new BodyObservations();
    const old = registry.register(details, 10000);
    for (let i = 0; i < 129; i++) {
      if (saturated === 'requests') registry.register({...details, tabId: 2, timeStamp: 10050}, 10050);
      else registry.observe({...body, bodyHash: 'b'.repeat(64)}, sender, 10050);
    }
    assert.equal(registry.take(old), null, 'overflow invalidates in-flight tokens');
    assert.equal(registry.observe(body, sender, 10150), false);
    assert.equal(registry.take(registry.register({...details, timeStamp: 10200}, 10200)), null);
    assert.equal(registry.observe(body, sender, 13000), false, 'pre-overflow observations expire before recovery');
    assert(registry.observe({...body, time: 13000}, sender, 13000));
    assert.equal(registry.take(registry.register({...details, timeStamp: 13010}, 13010)).bodyHash, body.bodyHash);
  }
  const boundary = new BodyObservations(); boundary.overflow(10000);
  assert.equal(boundary.register({...details, timeStamp: 12400}, 12400), null);
  assert.equal(boundary.observe({...body, time: 12400}, sender, 12520), false,
    'a digest delayed across cooldown cannot resurrect a skipped request');
  assert.equal(boundary.observe({...body, time: 12504}, sender, 12520), false,
    'the backwards matching allowance cannot cross the retired-request boundary');
  assert.equal(boundary.register({...details, timeStamp: 12499}, 12520), null);
  assert.equal(boundary.take(boundary.register({...details, timeStamp: 12600}, 12600)), null);
});
