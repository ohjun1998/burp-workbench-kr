import {originOf, validateConnection, permissionPattern} from './mapping.js';
const byId = id => document.getElementById(id);
const saved = await chrome.storage.local.get({base: '', token: '', origins: []});
byId('base').value = saved.base; byId('token').value = saved.token;
function report(text) { byId('status').textContent = text; }
byId('connect').addEventListener('click', async () => {
  try {
    const base = validateConnection(byId('base').value.trim(), byId('token').value.trim());
    const token = byId('token').value.trim();
    const response = await fetch(base + '/health', {headers: {'X-Feature-Mapper-Token': token}, signal: AbortSignal.timeout(2500)});
    if (!response.ok) throw new Error('연결 실패: ' + response.status);
    await chrome.storage.local.set({base, token}); report('Burp 연결 완료');
  } catch (e) { report(e.message); }
});
async function currentOrigin() {
  const [tab] = await chrome.tabs.query({active: true, currentWindow: true});
  const origin = originOf(tab?.url || ''); if (!origin) throw new Error('분석할 HTTP/HTTPS 사이트를 먼저 여세요.'); return origin;
}
byId('enable').addEventListener('click', async () => {
  try {
    const origin = await currentOrigin(), match = permissionPattern(origin);
    if (!await chrome.permissions.request({origins: [match]})) throw new Error('현재 사이트 접근 권한이 필요합니다.');
    const {origins = []} = await chrome.storage.local.get('origins');
    const id = 'fm-' + Array.from(new TextEncoder().encode(origin), n => n.toString(16).padStart(2, '0')).join('');
    const scripts = await chrome.scripting.getRegisteredContentScripts({ids: [id]});
    if (!scripts.length) await chrome.scripting.registerContentScripts([{id, matches: [match], js: ['content.js'], runAt: 'document_start', allFrames: true, persistAcrossSessions: true}]);
    await chrome.storage.local.set({origins: [...new Set([...origins, origin])]}); report(origin + '\n활성화 완료 · 페이지를 새로고침하세요.');
  } catch (e) { report(e.message); }
});
byId('disable').addEventListener('click', async () => {
  try {
    const origin = await currentOrigin();
    const id = 'fm-' + Array.from(new TextEncoder().encode(origin), n => n.toString(16).padStart(2, '0')).join('');
    await chrome.scripting.unregisterContentScripts({ids: [id]});
    const {origins = []} = await chrome.storage.local.get('origins');
    await chrome.storage.local.set({origins: origins.filter(x => x !== origin)}); report(origin + '\n해제 완료');
  } catch (e) { report(e.message); }
});
