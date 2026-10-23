import {originOf, pageOf, pickAction, validateConnection, BodyObservations} from './mapping.js';
const active = new Map();
const history = new Map();
const bodies = new BodyObservations();
let delivered = 0;
let unverifiedBodies = 0;
let settings = {base: '', token: '', origins: []};
const changedSettings = {};
const ready = Promise.all([
  chrome.storage.local.get({base: '', token: '', origins: []}),
  chrome.storage.session.get({deliveryCount: 0, bodyUnverifiedCount: 0})
]).then(([saved, session]) => { settings = {...saved, ...changedSettings}; delivered = Number(session.deliveryCount) || 0; unverifiedBodies = Number(session.bodyUnverifiedCount) || 0; });
chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== 'local') return;
  for (const key of ['base', 'token', 'origins']) if (changes[key])
    settings[key] = changedSettings[key] = changes[key].newValue ?? (key === 'origins' ? [] : '');
});
async function deliver(event) {
  await ready;
  if (!settings.base || !settings.token || !settings.origins.includes(originOf(event.url))) return;
  const base = validateConnection(settings.base, settings.token);
  const response = await fetch(base + '/event', {method: 'POST', headers: {'Content-Type': 'application/json', 'X-Feature-Mapper-Token': settings.token}, body: JSON.stringify(event), signal: AbortSignal.timeout(2500)});
  if (!response.ok) throw new Error('Burp 연결 실패: ' + response.status);
  await chrome.storage.session.set({deliveryCount: ++delivered, lastDeliveredLabel: event.label, lastDeliveryAt: Date.now(), lastError: ''});
}
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (!['action', 'request-body'].includes(message?.type) || !sender.tab || sender.id !== chrome.runtime.id) return;
  const receivedAt = Date.now();
  (async () => {
    await ready;
    const page = pageOf(sender.url || message.page);
    if (!settings.origins.includes(originOf(page))) return;
    if (message.type === 'request-body') return {ok: bodies.observe(message, sender)};
    const label = String(message.label || '').replace(/[\r\n\t]/g, ' ').trim().slice(0, 80);
    if (!label) return;
    const value = {id: crypto.randomUUID(), tab: sender.tab.id, frame: sender.frameId || 0, time: Math.abs(Number(message.time) - receivedAt) < 1500 ? Number(message.time) : receivedAt, page, label, title: String(message.title || '').slice(0, 120)};
    const key = value.tab + ':' + value.frame;
    const previous = active.get(key);
    // A submit event immediately after the same click is one browser action.
    if (message.kind === 'submit' && previous && value.time - previous.time < 500) { value.id = previous.id; value.label = previous.label; }
    active.set(key, value);
    const list = [...(history.get(key) || []), value].filter(x => value.time - x.time < 10000).slice(-32); history.set(key, list);
    chrome.storage.session.set({['action:' + key]: value, ['history:' + key]: list}).catch(() => {});
    return {ok: true};
  })().then(respond, () => respond({ok: false}));
  return true;
});
chrome.tabs.onRemoved.addListener(tab => {
  bodies.clearTab(tab);
  for (const key of active.keys()) if (key.startsWith(tab + ':')) { active.delete(key); history.delete(key); }
  chrome.storage.session.get(null).then(values => chrome.storage.session.remove(
    Object.keys(values).filter(key => key.startsWith('action:' + tab + ':') || key.startsWith('history:' + tab + ':'))
  )).catch(() => {});
});
async function requestEvent(details) {
  await ready;
  if (details.tabId < 0 || !settings.origins.includes(originOf(details.url))) return;
  // Register before any per-request wait, but exclude unrelated sites and bodyless traffic.
  const observationRequest = bodies.register(details);
  const key = details.tabId + ':' + details.frameId;
  // The network callback and content-script message can arrive out of order.
  // Select by the original click time, rather than the latest action at delivery time.
  await new Promise(resolve => setTimeout(resolve, details.requestBody?.raw?.length ? 35 : 120));
  const list = history.get(key) || (await chrome.storage.session.get('history:' + key))['history:' + key] || [];
  const action = pickAction(list, details);
  if (!action) return;
  let bodyHash = '';
  let bodyHashSource = '';
  const raw = details.requestBody?.raw;
  if (raw?.length && raw.every(x => x.bytes) && raw.reduce((n, x) => n + x.bytes.byteLength, 0) <= 512 * 1024) {
    const bytes = new Uint8Array(raw.reduce((n, x) => n + x.bytes.byteLength, 0)); let offset = 0;
    for (const item of raw) { bytes.set(new Uint8Array(item.bytes), offset); offset += item.bytes.byteLength; }
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    bodyHash = Array.from(new Uint8Array(digest), x => x.toString(16).padStart(2, '0')).join('');
    bodyHashSource = 'web-request';
  }
  if (!bodyHash) {
    const observed = bodies.take(observationRequest);
    if (observed) { bodyHash = observed.bodyHash; bodyHashSource = 'page-observation'; }
  }
  // A missing/ambiguous digest must not degrade into a URL-only write attribution.
  if (!bodyHash && !['GET', 'HEAD'].includes(details.method)) {
    await chrome.storage.session.set({bodyUnverifiedCount: ++unverifiedBodies});
    return;
  }
  await deliver({type: 'network', time: details.timeStamp, method: details.method, url: details.url, bodyHash, bodyHashSource,
    actionId: action.id, label: action.label, title: action.title, page: action.page, tab: String(details.tabId), frame: String(details.frameId)});
}
// Observe only. This listener does not block, replay, or modify any target request.
chrome.webRequest.onBeforeRequest.addListener(details => {
  requestEvent(details).catch(error => chrome.storage.session.set({lastError: error.message}));
}, {urls: ['http://*/*', 'https://*/*']}, ['requestBody']);
