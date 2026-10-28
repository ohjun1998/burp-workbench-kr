/** Data-only scope policy and serializable DOM helpers for explicit browser inspections. */
const forbidden = /(?:^|\/)(?:logout|signout|delete|remove|shutdown|restart|reset|save|update|create|submit|purchase|checkout|refund|transfer|revoke|disable|enable)(?:\/|$)/i;
export function inspectionOrigin(value) {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.origin : ''; } catch { return ''; }
}
export function inspectionUrl(value, base) {
  try {
    const raw = String(value); const path = raw.split(/[?#]/, 1)[0];
    if (raw.length > 8192 || /[\\\r\n\t]/.test(raw) || /%2e|%2f|%5c|%25|%00|;|(?:^|\/)\.{1,2}(?:\/|$)/i.test(path)) return null;
    const url = new URL(raw, base); return inspectionOrigin(url.href) ? url : null;
  } catch { return null; }
}
export function inspectionAllows(value, command) {
  const url = inspectionUrl(value, command.startUrl);
  if (!url || url.origin !== command.origin || forbidden.test(url.pathname)) return false;
  const target = url.origin + url.pathname;
  if (command.excludes.some(rule => target.startsWith(rule))) return false;
  return command.includes.some(rule => target === rule || target.startsWith(rule.endsWith('/') ? rule : rule + '/'));
}
export function inspectionCommand(value, now = Date.now()) {
  if (!value || value.state !== 'run' || !/^[A-Za-z0-9_.:-]{1,100}$/.test(value.runId || '')
      || !Number.isFinite(value.expiresAt) || value.expiresAt <= now || value.expiresAt - now > 15 * 60 * 1000
      || !Number.isInteger(value.maxPages) || value.maxPages < 1 || value.maxPages > 100
      || !Number.isInteger(value.maxActions) || value.maxActions < 1 || value.maxActions > 300
      || !Array.isArray(value.includes) || !value.includes.length || value.includes.length > 100
      || !Array.isArray(value.excludes) || value.excludes.length > 100) throw new Error('브라우저 점검 명령 또는 한도 오류');
  const origin = inspectionOrigin(value.origin);
  if (!origin || origin !== value.origin) throw new Error('브라우저 점검 Origin 오류');
  const rules = entries => entries.map(item => {
    const url = inspectionUrl(item);
    if (!url || url.origin !== origin || url.search || url.hash) throw new Error('브라우저 Scope 경로 오류');
    return url.origin + (url.pathname.length > 1 ? url.pathname.replace(/\/+$/, '') : '/');
  });
  const task = {...value, origin, includes: rules(value.includes), excludes: rules(value.excludes)};
  if (!inspectionAllows(task.startUrl, task)) throw new Error('브라우저 시작 URL이 Scope 밖이거나 변경 경로입니다.');
  return task;
}
const escapeRegex = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
export function inspectionRules(tabId, command, firstId = 870000) {
  if (!Number.isInteger(tabId) || tabId < 0) throw new Error('전용 탐색 탭 필요');
  let id = firstId;
  const condition = regex => ({tabIds: [tabId], regexFilter: regex, isUrlFilterCaseSensitive: true});
  const rules = [];
  // DNR's implicit resource set may omit main_frame. Cover it explicitly, while
  // the complementary exclusion set also covers future non-frame resource types.
  const add = (priority, type, value) => {
    rules.push({id: id++, priority, action: {type}, condition: {...value, excludedResourceTypes: ['main_frame']}});
    rules.push({id: id++, priority, action: {type}, condition: {...value, resourceTypes: ['main_frame']}});
  };
  add(1, 'block', {tabIds: [tabId], urlFilter: '*'});
  for (const prefix of command.includes) {
    const path = new URL(prefix).pathname;
    const regex = '^' + escapeRegex(prefix) + (path === '/' ? '[^#]*$' : '(?:/[^?#]*)?(?:\\?[^#]*)?$');
    add(10, 'allow', {...condition(regex), requestMethods: ['get', 'head']});
  }
  for (const prefix of command.excludes) add(20, 'block', condition('^' + escapeRegex(prefix)));
  add(30, 'block', {...condition('^' + escapeRegex(command.origin) + '/(?:[^?#]*/)?(?:logout|signout|delete|remove|shutdown|restart|reset|save|update|create|submit|purchase|checkout|refund|transfer|revoke|disable|enable)(?:[/?#]|$)'), isUrlFilterCaseSensitive: false});
  add(35, 'block', {...condition('^' + escapeRegex(command.origin) + '/[^?#]*(?:%2[eEfF]|%5[cC]|%25|%00|;)'), isUrlFilterCaseSensitive: false});
  rules.push({id: id++, priority: 40, action: {type: 'block'}, condition: {tabIds: [tabId], resourceTypes: ['websocket']}});
  return rules;
}
export function inspectionSafeUrl(value) {
  const url = inspectionUrl(value);
  if (!url) return '';
  return url.origin + url.pathname.replace(/((?:token|secret|password|api[_-]?key)\/)[^/]+/gi, '$1[REDACTED]')
    .replace(/(?:AKIA|ASIA)[A-Z0-9]{16}|AIza[0-9A-Za-z_-]{35}|gh[pousr]_[A-Za-z0-9]{36,255}|sk_(?:live|test)_[A-Za-z0-9]{16,200}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g, '[REDACTED]');
}
export async function inspectionRouteId(value, cryptoImpl = globalThis.crypto) {
  const bytes = new TextEncoder().encode(String(value));
  return Array.from(new Uint8Array(await cryptoImpl.subtle.digest('SHA-256', bytes)), x => x.toString(16).padStart(2, '0')).join('');
}
export async function inspectionMetadata(value, command, cryptoImpl = globalThis.crypto) {
  const url = inspectionUrl(value.url || value.page || command.startUrl, command.startUrl);
  const safe = url ? inspectionSafeUrl(url.href) : '';
  const text = (value, limit) => String(value || '').replace(/[\r\n\t]/g, ' ')
    .replace(/https?:\/\/[^\s<>"']+/g, candidate => inspectionSafeUrl(candidate))
    .replace(/(?:AKIA|ASIA)[A-Z0-9]{16}|AIza[0-9A-Za-z_-]{35}|gh[pousr]_[A-Za-z0-9]{36,255}|sk_(?:live|test)_[A-Za-z0-9]{16,200}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g,'[REDACTED]')
    .replace(/((?:api[_-]?key|token|secret|password|authorization|cookie)\s*[:=]\s*)[^ ,;]+/gi, '$1[REDACTED]').slice(0, limit);
  const integer=(value,max)=>Number.isInteger(value)&&value>=0?Math.min(value,max):0;
  const stage=['request','response','error'].includes(value.networkStage)?value.networkStage:'';
  const status=Number.isInteger(value.statusCode)&&value.statusCode>=100&&value.statusCode<=599?value.statusCode:0;
  if(stage==='response'&&!status)throw new Error('브라우저 응답 HTTP 상태 코드 미확인');
  return {type: 'inspection', runId: command.runId, actor: 'browser-session',
    kind:text(value.kind,32),state:text(value.state,40),message:text(value.message,300),method:text(value.method,16),
    tab:text(value.tab,32),pages:Number(value.pages)||0,actions:Number(value.actions)||0,requests:Number(value.requests)||0,excluded:Number(value.excluded)||0,
    url: safe, page: inspectionSafeUrl(value.page || command.startUrl), label:text(value.label,120),
    routeId: await inspectionRouteId(url?.href || command.startUrl, cryptoImpl),
    pageRouteId: await inspectionRouteId(value.page || command.startUrl, cryptoImpl), association: 'tab-current-route-observation',
    queryNames: url ? [...new Set(url.searchParams.keys())].slice(0,32).map(x => text(x,80)) : [],
    requiresQuery: !!url?.search || (!!url && safe !== url.origin + url.pathname), httpProfileShared: false,
    networkStage:stage,statusCode:stage==='response'?status:0,
    requestId:value.requestId?await inspectionRouteId(command.runId+'|'+String(value.requestId).slice(0,256),cryptoImpl):'',
    resourceType:['xmlhttprequest','other','main_frame','sub_frame'].includes(value.resourceType)?value.resourceType:'',
    frameId:integer(value.frameId,100000),elapsedMillis:integer(value.elapsedMillis,15*60*1000),fromCache:value.fromCache===true,
    errorCode:['BLOCKED','ABORTED','TIMED_OUT','NETWORK_ERROR'].includes(value.errorCode)?value.errorCode:'',
    discoveryType:['anchor','iframe','network'].includes(value.discoveryType)?value.discoveryType:'',
    responses:integer(value.responses,2000),networkErrors:integer(value.networkErrors,2000),pendingRequests:integer(value.pendingRequests,200)};
}

/** Runs only inside the dedicated tab in MAIN world. DNR remains the transport boundary. */
export function installInspectionGuard(command) {
  if (globalThis.__workbenchInspectionGuard?.runId === command.runId) return;
  const guard = {runId: command.runId, attempted: 0, blocked: 0};
  globalThis.__workbenchInspectionGuard = guard;
  const allowed = (value, method = 'GET') => {
    guard.attempted++;
    try {
      const raw = String(value), path = raw.split(/[?#]/,1)[0];
      const url = new URL(raw, location.href), target = url.origin + url.pathname;
      return ['GET','HEAD'].includes(String(method).toUpperCase()) && Date.now() < command.expiresAt
        && !url.username && !url.password && !/[\\\r\n\t]|%2e|%2f|%5c|%25|%00|;/i.test(path)
        && url.origin === command.origin && !/(?:^|\/)(?:logout|signout|delete|remove|shutdown|restart|reset|save|update|create|submit|purchase|checkout|refund|transfer|revoke|disable|enable)(?:\/|$)/i.test(url.pathname)
        && !command.excludes.some(rule => target.startsWith(rule))
        && command.includes.some(rule => target === rule || target.startsWith(rule.endsWith('/') ? rule : rule + '/'));
    } catch { return false; }
  };
  const originalFetch = window.fetch;
  window.fetch = function(input, options) {
    const method = options?.method || (typeof Request !== 'undefined' && input instanceof Request ? input.method : 'GET');
    if (!allowed(typeof input === 'string' || input instanceof URL ? input : input?.url, method)) {guard.blocked++; return Promise.reject(new TypeError('Workbench Scope inspection blocked this request'));}
    return originalFetch.apply(this, arguments);
  };
  const originalOpen = XMLHttpRequest.prototype.open, originalSend = XMLHttpRequest.prototype.send;
  const requests = new WeakMap();
  XMLHttpRequest.prototype.open = function(method,url) { requests.set(this,{method,url}); return originalOpen.apply(this,arguments); };
  XMLHttpRequest.prototype.send = function() { const request=requests.get(this);if(!request||!allowed(request.url,request.method)){guard.blocked++;throw new DOMException('Workbench Scope inspection blocked this request','SecurityError');}return originalSend.apply(this,arguments); };
  if (navigator.sendBeacon) try { navigator.sendBeacon = () => {guard.blocked++;return false;}; } catch {}
  document.addEventListener('submit', event => {event.preventDefault();event.stopImmediatePropagation();guard.blocked++;}, true);
  for (const name of ['submit','requestSubmit']) if (typeof HTMLFormElement !== 'undefined' && HTMLFormElement.prototype[name])
    HTMLFormElement.prototype[name] = function() {guard.blocked++;throw new DOMException('Forms are disabled during Scope inspection','SecurityError');};
  window.open = () => {guard.blocked++;return null;};
  document.addEventListener('click', event => {
    const link=event.target.closest?.('a[href]');
    if(link&&!allowed(link.href,'GET')){event.preventDefault();event.stopImmediatePropagation();guard.blocked++;}
  },true);
}

/** Executes a single bounded DOM action and discovers navigation without reading form values. */
export function inspectDocument(command, performAction = true) {
  const dangerous = /(?:logout|sign.?out|delete|remove|shutdown|restart|reset|save|update|create|submit|purchase|checkout|refund|transfer|revoke|disable|enable|로그아웃|삭제|탈퇴|저장|등록|수정|결제|송금|구매|취소)/i;
  const allowed = value => {
    try { const raw=String(value),p=raw.split(/[?#]/,1)[0],u=new URL(raw,location.href),target=u.origin+u.pathname;
      return u.origin===command.origin&&!u.username&&!u.password&&!/[\\\r\n\t]|%2e|%2f|%5c|%25|%00|;/i.test(p)
        && !/(?:^|\/)(?:logout|signout|delete|remove|shutdown|restart|reset|save|update|create|submit|purchase|checkout|refund|transfer|revoke|disable|enable)(?:\/|$)/i.test(u.pathname)
        && !command.excludes.some(rule=>target.startsWith(rule))&&command.includes.some(rule=>target===rule||target.startsWith(rule.endsWith('/')?rule:rule+'/')); } catch{return false;}
  };
  const visible = element => {const box=element.getBoundingClientRect();const style=getComputedStyle(element);return box.width>0&&box.height>0&&style.visibility!=='hidden'&&style.display!=='none';};
  const text = element => (element.getAttribute('aria-label') || element.textContent || element.getAttribute('title') || '').replace(/\s+/g,' ').trim().slice(0,100);
  const state=globalThis.__workbenchInspectionDom?.runId===command.runId?globalThis.__workbenchInspectionDom:{runId:command.runId,expanded:new WeakSet(),scrolled:0};
  globalThis.__workbenchInspectionDom=state;
  let action='';
  if(performAction) {
    const detail=[...document.querySelectorAll('nav details:not([open]),[role="navigation"] details:not([open]),[role="menu"] details:not([open])')].find(x=>visible(x)&&!state.expanded.has(x)&&!dangerous.test(text(x)));
    if(detail){state.expanded.add(detail);detail.open=true;action='펼침 메뉴: '+text(detail).slice(0,80);}
    else {
      const button=[...document.querySelectorAll('nav button[aria-expanded="false"],[role="navigation"] button[aria-expanded="false"],[role="menubar"] [aria-expanded="false"],nav [role="tab"][aria-selected="false"]')]
        .find(x=>visible(x)&&!state.expanded.has(x)&&!x.closest('form')&&!x.disabled&&x.getAttribute('type')!=='submit'&&!dangerous.test(text(x)));
      if(button){state.expanded.add(button);button.click();action='메뉴 확장: '+text(button).slice(0,80);}
      else if(state.scrolled<2&&document.documentElement.scrollHeight>innerHeight+20){state.scrolled++;scrollBy(0,Math.max(200,innerHeight*0.7));action='지연 로딩 영역 확인';}
    }
  }
  const routes=[];let excluded=0,routeLimit=false;
  for(const element of document.querySelectorAll('a[href],[role="link"][href],[data-route],[routerlink],iframe[src]')){
    if(routes.length>=200){routeLimit=true;break;}if(!visible(element))continue;
    const frame=String(element.tagName||'').toLowerCase()==='iframe';
    const label=text(element);const raw=frame?element.getAttribute('src'):element.getAttribute('href')||element.getAttribute('data-route')||element.getAttribute('routerlink');
    if(!raw||dangerous.test(label)){excluded++;continue;}
    try{const url=new URL(raw,location.href);if(!allowed(url.href)||url.href===location.href||url.protocol==='javascript:'){excluded++;continue;}routes.push({url:url.href,label:label||(frame?'포함 화면':'메뉴 경로'),discoveryType:frame?'iframe':'anchor'});}catch{excluded++;}
  }
  const pendingDetails=[...document.querySelectorAll('nav details:not([open]),[role="navigation"] details:not([open]),[role="menu"] details:not([open])')]
    .some(x=>visible(x)&&!state.expanded.has(x)&&!dangerous.test(text(x)));
  const pendingButtons=[...document.querySelectorAll('nav button[aria-expanded="false"],[role="navigation"] button[aria-expanded="false"],[role="menubar"] [aria-expanded="false"],nav [role="tab"][aria-selected="false"]')]
    .some(x=>visible(x)&&!state.expanded.has(x)&&!x.closest('form')&&!x.disabled&&x.getAttribute('type')!=='submit'&&!dangerous.test(text(x)));
  const canScroll=document.documentElement.scrollHeight>innerHeight+20;
  const pendingScroll=canScroll&&state.scrolled<2;
  const scrollLimit=canScroll&&state.scrolled>=2&&((globalThis.scrollY||0)+innerHeight+20<document.documentElement.scrollHeight);
  const loading=document.readyState!=='complete'||[...document.querySelectorAll('[aria-busy="true"]')].some(visible);
  const password=document.querySelector?.('input[type="password"]');
  const login=!!password&&(/(?:^|\/)(?:login|signin|sign-in)(?:\/|$)/i.test(location.pathname)||/(?:로그인|sign.?in|log.?in)/i.test(document.title));
  return {url:location.href,title:document.title,routes,action,excluded,forms:document.forms.length,login,ready:document.readyState,loading,pendingActions:pendingDetails||pendingButtons||pendingScroll,limits:[...(routeLimit?['DOM 메뉴 후보 200개 한도']:[]),...(scrollLimit?['문서별 지연 스크롤 2회 한도']:[])]};
}
