import {originOf, validateConnection, permissionPattern} from './mapping.js';
const byId = id => document.getElementById(id);
const saved = await chrome.storage.local.get({base: '', token: '', origins: [], inspectionEnabled: false});
byId('base').value = saved.base; byId('token').value = saved.token;
byId('inspection').checked=!!saved.inspectionEnabled;
byId('inspection').addEventListener('change',async()=>{await chrome.storage.local.set({inspectionEnabled:byId('inspection').checked});report(byId('inspection').checked?'SPA 통합 탐색 대기 · Workbench에서 시작하세요.':'SPA 통합 탐색 해제 · 실행 중인 전용 탭 정리');});
function report(text) { byId('status').textContent = text; }
async function diagnostics() {
  const state = await chrome.storage.session.get({deliveryCount: 0, bodyUnverifiedCount: 0, lastDeliveryAt: 0, lastError: '', inspectionStatus: '통합 탐색 대기', inspectionPages:0, inspectionActions:0, inspectionRequests:0, inspectionResponses:0, inspectionNetworkErrors:0, inspectionPendingRequests:0, inspectionExcluded:0});
  const {origins = []} = await chrome.storage.local.get('origins');
  byId('diagnostics').textContent = '활성 사이트 ' + origins.length + '개 · 이벤트 전달 ' + state.deliveryCount + '건\n'
    + (state.lastError ? '최근 전달 실패: ' + state.lastError
      : state.lastDeliveryAt ? '마지막 전달: ' + new Date(state.lastDeliveryAt).toLocaleTimeString()
      : '클릭 요청 전달 대기')
    + '\n본문 해시 미확인으로 연결 제외 ' + state.bodyUnverifiedCount + '건'
    + '\n전달 성공은 Burp의 SCOPE 수집·기능 연결 완료와 별개입니다.'
    + '\n'+state.inspectionStatus+' · 페이지 '+state.inspectionPages+' · 동작 '+state.inspectionActions+' · 네트워크 관측 '+state.inspectionRequests+' · 제외 '+state.inspectionExcluded
    + '\nAPI 응답 '+state.inspectionResponses+' · 실패 '+state.inspectionNetworkErrors+' · 응답 대기/미확인 '+state.inspectionPendingRequests
    + '\n브라우저 응답 관측은 선택 HTTP 계정의 본문 분석·권한 확인과 별개입니다.';
}
chrome.storage.onChanged.addListener(() => { diagnostics().catch(() => {}); });
await diagnostics();
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
    const scripts = await chrome.scripting.getRegisteredContentScripts({ids: [id, id + '-body']});
    const registrations = [
      {id, matches: [match], js: ['content.js'], runAt: 'document_start', allFrames: true, persistAcrossSessions: true},
      {id: id + '-body', matches: [match], js: ['request-body.js'], world: 'MAIN', runAt: 'document_start', allFrames: true, persistAcrossSessions: true}
    ].filter(script => !scripts.some(existing => existing.id === script.id));
    if (registrations.length) await chrome.scripting.registerContentScripts(registrations);
    await chrome.storage.local.set({origins: [...new Set([...origins, origin])]}); report(origin + '\n활성화 완료 · 페이지를 새로고침하세요.');
  } catch (e) { report(e.message); }
});
byId('disable').addEventListener('click', async () => {
  try {
    const origin = await currentOrigin();
    const id = 'fm-' + Array.from(new TextEncoder().encode(origin), n => n.toString(16).padStart(2, '0')).join('');
    const scripts = await chrome.scripting.getRegisteredContentScripts({ids: [id, id + '-body']});
    if (scripts.length) await chrome.scripting.unregisterContentScripts({ids: scripts.map(script => script.id)});
    const {origins = []} = await chrome.storage.local.get('origins');
    await chrome.storage.local.set({origins: origins.filter(x => x !== origin)}); report(origin + '\n해제 완료');
  } catch (e) { report(e.message); }
});
