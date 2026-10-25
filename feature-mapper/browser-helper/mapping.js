/** Pure helpers shared by the service worker and executable Node tests. */
export function originOf(url) {
  try { const u = new URL(url); return ['http:', 'https:'].includes(u.protocol) ? u.origin : ''; } catch { return ''; }
}
export function pageOf(url) {
  try { const u = new URL(url); return originOf(url) ? u.origin + u.pathname : ''; } catch { return ''; }
}
export function actionContext(action, request) {
  if (!action || action.tab !== request.tabId || action.frame !== request.frameId) return null;
  const gap = request.timeStamp - action.time;
  // A short window is contextual evidence, never proof of JavaScript causality.
  if (gap < -150 || gap > 2200) return null;
  if (!['xmlhttprequest', 'main_frame', 'sub_frame', 'other'].includes(request.type)) return null;
  if (originOf(action.page) !== originOf(request.url)) return null;
  return action;
}
export function validateConnection(base, token) {
  const u = new URL(base);
  if (u.protocol !== 'http:' || u.hostname !== '127.0.0.1' || !u.port || u.pathname !== '/' || u.search || u.hash || u.username || u.password) throw new Error('Burp에 표시된 http://127.0.0.1:포트 주소를 입력하세요.');
  if (!/^[a-f0-9]{64}$/.test(token)) throw new Error('Burp에 표시된 연결 토큰을 입력하세요.');
  return u.origin;
}

export function permissionPattern(url) {
  const u = new URL(url);
  if (!originOf(url)) throw new Error("HTTP/HTTPS 사이트가 필요합니다.");
  return u.protocol + "//" + u.hostname + "/*";
}

export function pickAction(actions, request) {
  return [...actions].filter(a => a.time <= request.timeStamp + 5 && actionContext(a, request)).sort((a, b) => b.time - a.time)[0] || null;
}

/** Chrome exposes raw request bodies only for POST/PUT; never reconstruct a missing body. */
export async function requestBodySignal(details, cryptoApi = globalThis.crypto) {
  if (!['POST', 'PUT'].includes(details.method)) return {bodyHash: '', bodyHashStatus: 'unsupported-method'};
  const raw = details.requestBody?.raw;
  if (!raw?.length || !raw.every(x => x.bytes && typeof x.bytes.byteLength === 'number'))
    return {bodyHash: '', bodyHashStatus: 'unavailable'};
  const size = raw.reduce((n, x) => n + x.bytes.byteLength, 0);
  if (size > 512 * 1024) return {bodyHash: '', bodyHashStatus: 'too-large'};
  const bytes = new Uint8Array(size); let offset = 0;
  for (const item of raw) { bytes.set(new Uint8Array(item.bytes), offset); offset += item.bytes.byteLength; }
  const digest = await cryptoApi.subtle.digest('SHA-256', bytes);
  return {bodyHash: Array.from(new Uint8Array(digest), x => x.toString(16).padStart(2, '0')).join(''), bodyHashStatus: 'raw'};
}
