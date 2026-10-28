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

/** Page hashes are observations, not trusted request IDs. Match only a unique real request. */
export class BodyObservations {
  constructor() { this.observations = []; this.requests = []; this.generation = 0; this.blockedUntil = 0; }
  overflow(now) {
    // Eviction must not turn an ambiguous match into a seemingly unique one. Retire every
    // in-flight token and wait beyond accepted hash age + matching window before restarting.
    this.observations = []; this.requests = []; this.generation++; this.blockedUntil = now + 2500;
  }
  prune(now) {
    this.observations = this.observations.filter(x => now - x.time < 2500).slice(-128);
    this.requests = this.requests.filter(x => now - x.timeStamp < 2500).slice(-128);
  }
  observe(message, sender, now = Date.now()) {
    this.prune(now);
    if (now < this.blockedUntil) return false;
    if (!sender.tab || typeof message.url !== 'string' || message.url.length > 8192
        || !originOf(sender.url) || originOf(message.url) !== originOf(sender.url)
        || !/^[A-Z]{1,32}$/.test(message.method || '') || ['GET', 'HEAD'].includes(message.method)
        || !/^[a-f0-9]{64}$/.test(message.bodyHash || '')
        || !Number.isInteger(message.size) || message.size < 0 || message.size > 512 * 1024
        || !Number.isFinite(message.time) || Math.abs(now - message.time) > 1500
        // Requests skipped during cooldown must stay retired after the wall-clock deadline.
        // Include matches()'s 5ms backwards allowance at the observation boundary.
        || message.time < this.blockedUntil + 5) return false;
    if (this.observations.length >= 128) { this.overflow(now); return false; }
    this.observations.push({url: message.url, method: message.method, bodyHash: message.bodyHash,
      size: message.size, time: message.time, tabId: sender.tab.id, frameId: sender.frameId || 0,
      documentId: sender.documentId || '', used: false});
    return true;
  }
  register(details, now = Date.now()) {
    this.prune(now);
    if (now < this.blockedUntil || typeof details.url !== 'string' || details.url.length > 8192
        || details.tabId < 0 || ['GET', 'HEAD'].includes(details.method)
        || details.timeStamp < this.blockedUntil) return null;
    if (this.requests.length >= 128) { this.overflow(now); return null; }
    const request = {url: details.url, method: details.method, timeStamp: details.timeStamp,
      tabId: details.tabId, frameId: details.frameId, documentId: details.documentId || '', generation: this.generation};
    this.requests.push(request);
    return request;
  }
  matches(observation, request) {
    if (!request) return false;
    const gap = request.timeStamp - observation.time;
    return observation.tabId === request.tabId && observation.frameId === request.frameId
      && observation.documentId === request.documentId && observation.method === request.method
      && observation.url === request.url && gap >= -5 && gap <= 250;
  }
  take(request) {
    if (!request || request.generation !== this.generation) return null;
    const matches = this.observations.filter(x => !x.used && this.matches(x, request));
    if (matches.length !== 1) return null;
    const observation = matches[0];
    // Keep completed requests as tombstones: a late hash must not attach to the next request.
    if (this.requests.filter(x => this.matches(observation, x)).length !== 1) return null;
    observation.used = true;
    return observation;
  }
  clearTab(tab) {
    this.observations = this.observations.filter(x => x.tabId !== tab);
    this.requests = this.requests.filter(x => x.tabId !== tab);
  }
}

export {createInspectionService} from './inspection-background.js';
