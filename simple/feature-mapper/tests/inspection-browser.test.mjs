import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import {webcrypto} from 'node:crypto';
import {inspectionCommand, inspectionAllows, inspectionRules, inspectionMetadata, inspectDocument, installInspectionGuard} from '../browser-helper/inspection.js';
import {createInspectionService} from '../browser-helper/inspection-background.js';
const task = changes => ({state:'run',runId:'test-run-01',origin:'https://scope.test',startUrl:'https://scope.test/app',includes:['https://scope.test/app'],excludes:['https://scope.test/app/private'],maxPages:6,maxActions:8,expiresAt:Date.now()+60000,...changes});

test('inspection Scope enforces origin, literal path boundary, conservative exclusions and ambiguity rejection', () => {
  const command=inspectionCommand(task());
  assert(inspectionAllows('https://scope.test/app/users#list',command));
  assert(inspectionAllows('https://scope.test/app?tab=summary',command));
  for(const url of ['https://scope.test/apple','https://other.test/app','http://scope.test/app','https://scope.test:444/app','https://scope.test/app/private-data','https://scope.test/app/%2e%2e/admin','https://scope.test/app/%252e%252e/admin','https://scope.test/app/delete','https://scope.test/app/logout','https://user:secret@scope.test/app'])assert(!inspectionAllows(url,command),url);
  for(const change of [{expiresAt:Date.now()-1},{maxPages:101},{origin:'https://other.test'},{includes:['https://other.test/app']},{excludes:['https://scope.test/app?secret=x']}])assert.throws(()=>inspectionCommand(task(change)));
});

test('DNR rules guard only the dedicated tab and allow scoped GET/HEAD without unsafe methods or redirect destinations', () => {
  const rules=inspectionRules(77,inspectionCommand(task()));
  const action=(url,method,tabId=77,type='xmlhttprequest')=>rules.filter(rule=>rule.condition.tabIds.includes(tabId)
      &&(!rule.condition.requestMethods||rule.condition.requestMethods.includes(method.toLowerCase()))
      &&(!rule.condition.resourceTypes||rule.condition.resourceTypes.includes(type))
      &&(!rule.condition.excludedResourceTypes||!rule.condition.excludedResourceTypes.includes(type))
      &&(!rule.condition.regexFilter||new RegExp(rule.condition.regexFilter,rule.condition.isUrlFilterCaseSensitive===false?'i':'').test(url)))
    .sort((a,b)=>b.priority-a.priority)[0]?.action.type;
  assert.equal(action('https://scope.test/app/api','GET'),'allow');
  assert.equal(action('https://scope.test/app/api','HEAD'),'allow');
  for(const method of ['POST','PUT','PATCH','DELETE','OPTIONS'])assert.equal(action('https://scope.test/app/api',method),'block');
  for(const url of ['https://scope.test/apple','https://other.test/app','https://scope.test/app/private-data','https://scope.test/app/logout','https://scope.test/app/%252fadmin'])assert.equal(action(url,'GET'),'block',url);
  assert.equal(action('https://scope.test/app/api','GET',77,'websocket'),'block');
  assert.equal(action('https://scope.test/app','GET',77,'main_frame'),'allow','explicit main-frame GET allowance reaches the scoped page');
  for(const method of ['POST','PUT','PATCH','DELETE'])assert.equal(action('https://scope.test/app/api',method,77,'main_frame'),'block','form/navigation method '+method);
  for(const url of ['https://other.test/app','https://scope.test/apple','https://scope.test/app/private-data','https://scope.test/app/logout','https://scope.test/app/%252fadmin'])assert.equal(action(url,'GET',77,'main_frame'),'block','main-frame redirect/navigation '+url);
  assert(rules.every(rule=>rule.condition.resourceTypes||rule.condition.excludedResourceTypes),'every transport rule explicitly defines its resource set');
  assert.equal(action('https://other.test/anything','GET',77,'future_resource_type'),'block','the complementary resource set fails closed for future resource types');
  assert.equal(action('https://other.test/anything','POST',12),undefined,'existing user tabs are untouched');
  assert.throws(()=>inspectionRules(-1,task()));
});

test('browser telemetry removes query/fragment values, separates hash routes, and never forwards credentials or bodies', async () => {
  const command=task();
  const first=await inspectionMetadata({kind:'api',url:'https://scope.test/app/api?token=PRIVATE_VALUE#/orders',page:'https://scope.test/app?session=PRIVATE_VALUE#/home',label:'token=PRIVATE_VALUE',headers:{Cookie:'PRIVATE_VALUE'},body:'PRIVATE_VALUE'},command,webcrypto);
  const second=await inspectionMetadata({kind:'route',url:'https://scope.test/app/api?token=PRIVATE_VALUE#/users'},command,webcrypto);
  assert.equal(first.url,'https://scope.test/app/api');assert.equal(first.page,'https://scope.test/app');
  assert.deepEqual(first.queryNames,['token']);assert(first.requiresQuery);assert.equal(first.actor,'browser-session');
  assert.equal(first.httpProfileShared,false);assert.notEqual(first.routeId,second.routeId);assert(first.pageRouteId);assert.equal(first.association,'tab-current-route-observation');
  assert(!JSON.stringify(first).includes('PRIVATE_VALUE'));assert(!('headers' in first)&&!('body' in first));
});

test('actual DOM explorer opens navigation details and finds hash routes without reading forms or dangerous actions', () => {
  const make=(attributes,content='')=>({tagName:'A',textContent:content,disabled:false,getAttribute:key=>attributes[key]??null,getBoundingClientRect:()=>({width:100,height:30}),closest:()=>null});
  const detail=make({},'메뉴');detail.open=false;
  const links=[make({href:'#/orders'},'주문 목록'),make({href:'#/users'},'사용자 목록'),make({href:'/app/delete'},'삭제'),make({href:'https://other.test/out'},'외부')];
  const context={location:{href:'https://scope.test/app',origin:'https://scope.test'},URL,innerHeight:600,scrollBy(){},getComputedStyle:()=>({visibility:'visible',display:'block'}),document:{title:'SPA',readyState:'complete',forms:{length:1},documentElement:{scrollHeight:600},querySelectorAll:selector=>selector.startsWith('nav details')?[detail]:selector.startsWith('nav button')?[]:links}};
  vm.createContext(context);const fn=vm.runInContext('('+inspectDocument.toString()+')',context);
  const value=fn(task(),true);
  assert(detail.open);assert(value.action.includes('펼침 메뉴'));assert.equal(value.routes.length,2);
  assert(value.routes.some(x=>x.url.endsWith('#/orders')));assert(value.routes.some(x=>x.url.endsWith('#/users')));
  assert.equal(value.excluded,2);assert.equal(value.forms,1);
});

test('MAIN guard preserves allowed fetch/XHR and blocks writes, outside requests, forms and popup escape', async () => {
  const calls=[],listeners={};const native=Promise.resolve('native');
  class Xhr{open(method,url){this.url=url;}send(){calls.push(this.url);return 'xhr-native';}}
  class Form{submit(){throw new Error('native submit must not run');}requestSubmit(){throw new Error('native submit must not run');}}
  const context={location:{href:'https://scope.test/app',origin:'https://scope.test'},URL,Request,DOMException,XMLHttpRequest:Xhr,HTMLFormElement:Form,navigator:{sendBeacon:()=>true},document:{addEventListener:(name,fn)=>{listeners[name]=fn;}},window:{fetch:(...args)=>{calls.push(args[0]);return native;},open:()=>{throw new Error('native popup must not run');}}};
  vm.createContext(context);vm.runInContext('('+installInspectionGuard.toString()+')',context)(task());
  assert.equal(context.window.fetch('/app/api'),native);assert.equal(await context.window.fetch('/app/api'),'native');
  await assert.rejects(context.window.fetch('/app/api',{method:'POST'}));
  await assert.rejects(context.window.fetch('https://other.test/api'));
  const xhr=new Xhr();xhr.open('GET','/app/api');assert.equal(xhr.send(),'xhr-native');xhr.open('PATCH','/app/api');assert.throws(()=>xhr.send());
  assert.throws(()=>new Form().submit());assert.equal(context.window.open('https://other.test'),null);assert.equal(context.navigator.sendBeacon('/app/api','body'),false);
  let prevented=0;listeners.submit({preventDefault(){prevented++;},stopImmediatePropagation(){prevented++;}});assert.equal(prevented,2);
  assert(!calls.includes('https://other.test/api'));
});

function fixture({unsupported=false,closed=false,document=null}={}) {
  const events=[],operations=[],session={},rules=[];let url='about:blank',nextTab=77;
  const config={inspectionEnabled:true,base:'http://127.0.0.1:1234',token:'a'.repeat(64),origins:['https://scope.test']};
  const chrome={
    storage:{session:{get:async key=>typeof key==='string'?{[key]:session[key]}:{...session},set:async value=>Object.assign(session,value)}},
    permissions:{contains:async()=>true},
    tabs:{create:async value=>{operations.push('create:'+value.url);url=value.url;return {id:nextTab};},update:async(id,value)=>{operations.push('navigate:'+value.url);url=value.url;return {id};},get:async id=>{if(closed)throw new Error('tab closed');return {id,status:'complete',url};},remove:async id=>{operations.push('close:'+id);}},
    scripting:{executeScript:async ({func,args})=>{operations.push('script:'+func.name);if(func.name==='installInspectionGuard')return [{result:null}];if(document)return [{result:document(url,args[1])}];return [{result:{url,title:'fixture',routes:url.endsWith('/app')?[{url:'https://scope.test/app#/orders',label:'주문'}]:[],action:'',excluded:0,forms:0}}];}},
    declarativeNetRequest:{getSessionRules:async()=>[...rules],isRegexSupported:async()=>({isSupported:!unsupported}),updateSessionRules:async value=>{operations.push(value.addRules.length?'guard':'unguard');for(const id of value.removeRuleIds){const n=rules.findIndex(x=>x.id===id);if(n>=0)rules.splice(n,1);}rules.push(...value.addRules);}}
  };
  const service=createInspectionService(chrome,{settings:async()=>({...config}),command:async()=>({state:'running',runId:'test-run-01'}),event:async event=>events.push(event)});
  return {service,events,operations,session,rules,config};
}
async function idle(service){for(let i=0;i<1200&&service.snapshot();i++)await new Promise(resolve=>setTimeout(resolve,10));assert.equal(service.snapshot(),null,'inspection should complete and clean its lease');}

test('dedicated browser service installs guards before navigation, visits SPA routes, then closes before guard removal', async () => {
  const f=fixture();await f.service.launch(task());await idle(f.service);
  assert(f.operations.indexOf('guard')<f.operations.findIndex(x=>x.startsWith('navigate:')));
  assert(f.operations.includes('navigate:https://scope.test/app#/orders'));
  assert(f.operations.indexOf('close:77')<f.operations.lastIndexOf('unguard'));
  assert.equal(f.rules.length,0);assert.equal(f.session.scopeInspectionTaskV1,null);
  assert(f.events.some(x=>x.kind==='started'));assert(f.events.some(x=>x.kind==='route'));assert(f.events.some(x=>x.kind==='done'));
  assert(f.events.every(x=>x.actor==='browser-session'&&x.httpProfileShared===false));
});

test('unsupported transport Scope rule fails closed before the first target navigation', async () => {
  const f=fixture({unsupported:true});await f.service.launch(task());await idle(f.service);
  assert(!f.operations.some(x=>x.startsWith('navigate:')));assert(f.operations.includes('close:77'));assert.equal(f.rules.length,0);
  assert(f.events.some(x=>x.kind==='error'&&x.message.includes('Scope')));
});

test('site opt-out cancels the dedicated task without touching other user tabs', async () => {
  const f=fixture();await f.service.launch(task());f.config.inspectionEnabled=false;await f.service.poll();await idle(f.service);
  assert(f.operations.filter(x=>x.startsWith('close:')).every(x=>x==='close:77'));
  assert.equal(f.rules.length,0);assert(f.events.some(x=>x.kind==='done'&&x.state==='stopped'));assert.equal(f.session.inspectionStatus,'탐색 중지');
});

test('inspection helper adds only explicit tab guards and a separate opt-in without cookie/debugger permissions', () => {
  const manifest=JSON.parse(fs.readFileSync(new URL('../browser-helper/manifest.json',import.meta.url)));
  assert(manifest.permissions.includes('declarativeNetRequest'));assert(manifest.permissions.includes('alarms'));
  assert(!manifest.permissions.includes('cookies'));assert(!manifest.permissions.includes('debugger'));assert(!manifest.permissions.includes('webRequestBlocking'));
  assert.deepEqual(manifest.host_permissions,['http://127.0.0.1/*']);assert.equal(manifest.version,'0.5.0');
  const popup=fs.readFileSync(new URL('../browser-helper/popup.html',import.meta.url),'utf8');assert(popup.includes('SPA 통합 탐색 허용'));
});


test('DOM route and remaining-menu limits are explicit without adding unsafe actions', () => {
  const link=i=>({textContent:'menu '+i,disabled:false,getAttribute:key=>key==='href'?'/app?item='+i:null,getBoundingClientRect:()=>({width:50,height:20}),closest:()=>null});
  const links=Array.from({length:201},(_,i)=>link(i));
  const context={location:{href:'https://scope.test/app',origin:'https://scope.test',pathname:'/app'},URL,innerHeight:800,scrollY:0,scrollBy(){},getComputedStyle:()=>({visibility:'visible',display:'block'}),document:{title:'SPA',readyState:'complete',forms:{length:0},documentElement:{scrollHeight:800},querySelector:()=>null,querySelectorAll:selector=>selector.startsWith('a[href]')?links:[]}};
  vm.createContext(context);const fn=vm.runInContext('('+inspectDocument.toString()+')',context);
  const value=fn(task(),false);assert.equal(value.routes.length,200);assert(value.limits.includes('DOM 메뉴 후보 200개 한도'));assert.equal(value.pendingActions,false);assert.equal(value.loading,false);
});

test('initial routes are harvested before a read-only navigation tab replaces the document links', async () => {
  let changed=false;
  const f=fixture({document:(url,action)=>{
    if(action&&!changed){changed=true;return {url,routes:[{url:'https://scope.test/app#/replacement',label:'replacement'}],action:'read-only tab',pendingActions:false,excluded:0};}
    return {url,routes:[{url:changed?'https://scope.test/app#/replacement':'https://scope.test/app#/initial',label:changed?'replacement':'initial'}],action:'',excluded:0};
  }});
  await f.service.launch(task());await idle(f.service);
  assert(f.events.some(x=>x.kind==='menu'&&x.label==='initial'));assert(f.events.some(x=>x.kind==='menu'&&x.label==='replacement'));
});

test('remaining navigation after the twelve-action document bound is reported partial', async () => {
  let actions=0;
  const f=fixture({document:(url,action)=>{if(action)actions++;return {url,routes:[],action:action?'expand':'',pendingActions:actions<13,excluded:0};}});
  await f.service.launch(task({maxActions:100}));await idle(f.service);
  const done=f.events.findLast(x=>x.kind==='done');assert.equal(actions,12);assert.equal(done.state,'partial');assert(done.message.includes('12회'));
});

test('persistent DOM loading reaches its bounded wait and reports partial rather than empty completion', async () => {
  const f=fixture({document:url=>({url,routes:[],action:'',pendingActions:false,loading:true,ready:'complete',excluded:0})});
  await f.service.launch(task());await idle(f.service);
  const done=f.events.findLast(x=>x.kind==='done');assert.equal(done.state,'partial');assert(done.message.includes('3초'));assert(f.session.inspectionStatus.includes('일부 탐색'));assert(!f.session.inspectionStatus.startsWith('탐색 완료'));
});

test('read-only navigation retries a replaced frame within the bounded DOM wait', async () => {
  let navigated=false,unloaded=false;
  const f=fixture({document:(url,action)=>{
    if(action&&!navigated){navigated=true;unloaded=true;return {url,routes:[],action:'read-only navigation',pendingActions:false,excluded:0};}
    if(!action&&unloaded){unloaded=false;throw new Error('Execution context was destroyed because of a navigation');}
    return {url,routes:navigated?[{url:'https://scope.test/app/destination',label:'destination'}]:[],pendingActions:!navigated,excluded:0};
  }});
  await f.service.launch(task());await idle(f.service);
  assert(!f.events.some(x=>x.kind==='error'));assert(f.events.some(x=>x.kind==='menu'&&x.label==='destination'));
});

test('network lifecycle metadata hashes request IDs and allowlists response status, frame and error fields', async () => {
  const input={kind:'api',url:'https://scope.test/app/api?token=PRIVATE_QUERY',page:'https://scope.test/app#/users',
    method:'GET',networkStage:'response',requestId:'PRIVATE_REQUEST_ID',statusCode:403,resourceType:'xmlhttprequest',frameId:9,
    elapsedMillis:125,fromCache:true,errorCode:'net::PRIVATE_ERROR',headers:{Cookie:'PRIVATE_COOKIE'},body:'PRIVATE_BODY',responses:2,pendingRequests:0};
  const one=await inspectionMetadata(input,task(),webcrypto),two=await inspectionMetadata({...input,networkStage:'request'},task(),webcrypto);
  assert.match(one.requestId,/^[a-f0-9]{64}$/);assert.equal(one.requestId,two.requestId);
  assert.equal(one.statusCode,403);assert.equal(two.statusCode,0);assert.equal(one.frameId,9);assert.equal(one.elapsedMillis,125);assert(one.fromCache);
  assert.equal(one.errorCode,'');assert.equal(one.resourceType,'xmlhttprequest');assert.equal(one.responses,2);assert.equal(one.pendingRequests,0);
  assert(!JSON.stringify(one).includes('PRIVATE_'));assert(!('headers' in one)&&!('body' in one));
});

test('response telemetry requires an actual integer HTTP status rather than a zero fallback', async () => {
  const input={kind:'api',url:'https://scope.test/app/api',networkStage:'response',requestId:'status-boundary'};
  for(const statusCode of [undefined,null,0,-1,99,600,200.5,NaN,Infinity,'200'])
    await assert.rejects(inspectionMetadata({...input,statusCode},task(),webcrypto),/상태 코드 미확인/);
  for(const statusCode of [100,200,403,599])assert.equal((await inspectionMetadata({...input,statusCode},task(),webcrypto)).statusCode,statusCode);
});

test('same-Scope visible iframe URLs become bounded menu candidates without reading the embedded DOM', () => {
  const frame=(src,title)=>({tagName:'IFRAME',textContent:'',getAttribute:key=>key==='src'?src:key==='title'?title:null,getBoundingClientRect:()=>({width:500,height:300}),closest:()=>null});
  const frames=[frame('/app/embedded','포함 화면'),frame('https://other.test/app','외부 화면'),frame('/app/delete','위험 경로')];
  const context={location:{href:'https://scope.test/app',origin:'https://scope.test',pathname:'/app'},URL,innerHeight:800,scrollBy(){},getComputedStyle:()=>({visibility:'visible',display:'block'}),document:{title:'SPA',readyState:'complete',forms:{length:0},documentElement:{scrollHeight:800},querySelector:()=>null,querySelectorAll:selector=>selector.startsWith('a[href]')?frames:[]}};
  vm.createContext(context);const result=vm.runInContext('('+inspectDocument.toString()+')',context)(task(),false);
  assert.equal(result.routes.length,1);assert.equal(result.routes[0].url,'https://scope.test/app/embedded');assert.equal(result.routes[0].discoveryType,'iframe');assert.equal(result.excluded,2);
});

test('actual API callbacks retain request-response-error linkage while query values, bodies and unrelated completions stay local', async () => {
  const f=fixture();await f.service.launch(task());
  const details={tabId:77,requestId:'xhr-1',url:'https://scope.test/app/api?token=PRIVATE_QUERY',documentUrl:'https://scope.test/app#/orders',method:'GET',type:'xmlhttprequest',frameId:2};
  await f.service.request(details);
  for(const statusCode of [undefined,null,0,-1,99,600,200.5,NaN,Infinity,'200'])await f.service.completed({...details,statusCode});
  assert.equal(f.service.snapshot().responses,0);assert.equal(Object.keys(f.service.snapshot().pendingRequests).length,1);
  assert(!f.events.some(e=>e.networkStage==='response'),'invalid callbacks must preserve pending rather than claim a response');
  await f.service.completed({...details,statusCode:403,fromCache:true});
  await f.service.completed({...details,requestId:'unobserved',statusCode:200});
  // Request IDs are opaque: a prototype-shaped value must remain an own key.
  await f.service.request({...details,requestId:'__proto__',url:'https://scope.test/app/slow'});
  await f.service.blocked({...details,requestId:'__proto__',url:'https://scope.test/app/slow',error:'net::ERR_CONNECTION_RESET PRIVATE_ERROR'});
  await idle(f.service);
  const stages=f.events.filter(e=>e.kind==='api');
  assert.equal(stages.length,4);assert.deepEqual(stages.map(e=>e.networkStage),['request','response','request','error']);
  assert.equal(stages[0].requestId,stages[1].requestId);assert.notEqual(stages[1].requestId,stages[2].requestId);assert.equal(stages[2].requestId,stages[3].requestId);
  assert.equal(stages[1].statusCode,403);assert.equal(stages[1].frameId,2);assert(stages[1].fromCache);assert.equal(stages[3].errorCode,'NETWORK_ERROR');
  assert(stages.every(e=>e.actor==='browser-session'&&e.httpProfileShared===false));assert(!JSON.stringify(f.events).includes('PRIVATE_'));
  assert.equal(f.session.inspectionResponses,1);assert.equal(f.session.inspectionNetworkErrors,1);assert.equal(f.session.inspectionPendingRequests,0);
  const done=f.events.findLast(e=>e.kind==='done');assert.equal(done.state,'partial');assert(done.message.includes('전송 실패'));
  const before=f.events.length;await f.service.completed({...details,statusCode:200});assert.equal(f.events.length,before);
});

test('unfinished API responses remain pending and make bounded browser completion partial', async () => {
  const f=fixture({document:url=>({url,routes:[],action:'',pendingActions:false,loading:false,ready:'complete',excluded:0})});
  await f.service.launch(task());
  await f.service.request({tabId:77,requestId:'never-completes',url:'https://scope.test/app/api',method:'GET',type:'xmlhttprequest',frameId:0});
  await idle(f.service);
  const done=f.events.findLast(e=>e.kind==='done');assert.equal(done.state,'partial');assert(done.message.includes('응답 대기 1.5초'));
  assert.equal(f.session.inspectionPendingRequests,1);assert.equal(f.session.inspectionResponses,0);
  assert(!f.events.some(e=>e.networkStage==='response'||e.networkStage==='error'),'timeout is not fabricated as a real HTTP response or callback error');
});
