import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {originOf, pageOf, actionContext, validateConnection, permissionPattern, pickAction} from '../browser-helper/mapping.js';
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
