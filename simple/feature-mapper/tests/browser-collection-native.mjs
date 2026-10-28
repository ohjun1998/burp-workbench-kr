/** Actual Chromium + production helper service, via an explicit Chrome API adapter.
 * No installed MV3 worker, native DNR, Burp GUI or extension permission claim.
 */
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {inspectionAllows,installInspectionGuard} from '../browser-helper/inspection.js';
import {createInspectionService} from '../browser-helper/inspection-background.js';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright-core');
const executablePath=process.argv[2],out=path.resolve(process.argv[3]||'build/browser-collection-native');
if(!executablePath)throw new Error('Usage: node browser-collection-native.mjs /path/to/chromium output');
fs.mkdirSync(out,{recursive:true});
const helper=path.resolve(path.dirname(new URL(import.meta.url).pathname),'../browser-helper');
const sourceSHA256=Object.fromEntries(['inspection.js','inspection-background.js','background.js','popup.js','popup.html','manifest.json'].map(name=>[name,createHash('sha256').update(fs.readFileSync(path.join(helper,name))).digest('hex')]));
const sockets=new Set(),events=[],checks=[],requests=[],session={},rules=[],tabs=new Map(),requestInfo=new WeakMap();
let browser,context,service,command,origin,serial=0;
const server=http.createServer((req,res)=>{
  if(req.url.startsWith('/helper/')){
    const name=req.url.slice('/helper/'.length).split('?')[0];
    if(!/^[a-z-]+\.(?:html|js)$/.test(name)){res.writeHead(404);res.end();return;}
    const file=path.join(helper,name);if(!fs.existsSync(file)){res.writeHead(404);res.end();return;}
    res.setHeader('content-type',name.endsWith('.js')?'text/javascript':'text/html; charset=utf-8');res.end(fs.readFileSync(file));return;
  }
  if(req.url==='/health'){res.end('Feature Mapper fixture');return;}
  requests.push({path:req.url.split('?')[0],method:req.method});
  res.setHeader('content-type','text/html; charset=utf-8');
  if(req.url==='/existing'){res.end('<!doctype html><title>Existing tab</title><p>기존 사용자 탭</p>');return;}
  if(req.url==='/app'){
    res.end(`<!doctype html><html lang="ko"><meta charset="utf-8"><title>브라우저 수집 고도화 fixture</title>
      <style>body{font:16px system-ui;margin:36px;color:#21384b;background:#f5f8fb}nav,iframe{display:block;margin:18px 0;background:white;border:1px solid #b8cbd9;padding:16px}iframe{width:600px;height:180px}</style>
      <h1>실제 Chromium · helper 서비스 어댑터</h1><p>설치된 MV3/DNR 또는 실제 Burp 검증이 아닙니다.</p>
      <nav><a href="/app/summary">요약 메뉴</a></nav><iframe title="포함 화면" src="/app/frame"></iframe>
      <script>fetch('/app/api/users?token=PRIVATE_BROWSER_QUERY').catch(()=>{});fetch('/app/api/denied').catch(()=>{});fetch('/app/api/failure').catch(()=>{});fetch('/app/api/pending').catch(()=>{});fetch('/outside-api').catch(()=>{});fetch('/app/write',{method:'POST'}).catch(()=>{});</script></html>`);return;
  }
  if(req.url==='/app/frame'){res.end('<!doctype html><title>포함 화면</title><nav><a href="/app/frame-menu">프레임 안 메뉴</a></nav>');return;}
  if(req.url.startsWith('/app/api/pending'))return; // Deliberately no response; close at teardown.
  if(req.url.startsWith('/app/api/failure')){req.socket.destroy();return;}
  if(req.url.startsWith('/app/api/')){res.setHeader('content-type','application/json');if(req.url.includes('/denied'))res.statusCode=403;res.end(JSON.stringify({ok:res.statusCode===200}));return;}
  if(['/app/summary','/app/frame-menu'].includes(req.url)){res.end('<!doctype html><title>읽기 화면</title><nav><a href="/app">홈</a></nav>');return;}
  res.writeHead(404);res.end('not found');
});
server.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));origin='http://127.0.0.1:'+server.address().port;
const check=(value,name)=>{assert(value,name);checks.push({name,passed:true});console.log('BROWSER_COLLECTION_PASS '+name);};
const scope='Actual Chromium loopback DOM and network callbacks through production createInspectionService and inspection.js, with Chrome API/session/tab/transport adapters. Not native MV3, DNR, optional permission installation, browser response-body analysis or Burp GUI.';
try{
  browser=await chromium.launch({executablePath,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});context=await browser.newContext();
  const existing=await context.newPage();await existing.goto(origin+'/existing');
  command={state:'run',runId:'browser-collection-140',origin,startUrl:origin+'/app',includes:[origin+'/app'],excludes:[],maxPages:8,maxActions:12,expiresAt:Date.now()+60000};
  const config={base:origin,token:'f'.repeat(64),origins:[origin],inspectionEnabled:true};
  const chrome={
    storage:{session:{get:async key=>typeof key==='string'?{[key]:session[key]}:{...session},set:async value=>Object.assign(session,value)}},permissions:{contains:async()=>true},
    tabs:{create:async()=>{
      const page=await context.newPage(),id=77;tabs.set(id,page);await page.setViewportSize({width:1080,height:850});
      await page.addInitScript(installInspectionGuard,command);
      // This is a test transport adapter, not Chrome's native DNR implementation.
      await page.route('**/*',route=>inspectionAllows(route.request().url(),command)&&['GET','HEAD'].includes(route.request().method())?route.continue():route.abort('blockedbyclient'));
      page.on('request',request=>{
        const resource=request.resourceType(),details={tabId:id,requestId:String(++serial),url:request.url(),documentUrl:request.frame().url(),method:request.method(),type:['fetch','xhr'].includes(resource)?'xmlhttprequest':resource,frameId:request.frame()===page.mainFrame()?0:1};
        requestInfo.set(request,details);service.request(details).catch(()=>{});
      });
      page.on('requestfinished',async request=>{const details=requestInfo.get(request),response=await request.response();if(details&&response)await service.completed({...details,statusCode:response.status(),fromCache:false});});
      page.on('requestfailed',request=>{const details=requestInfo.get(request);if(details)service.blocked({...details,error:request.failure()?.errorText||'net::ERR_FAILED'}).catch(()=>{});});
      return {id};
    },update:async(id,value)=>{await tabs.get(id).goto(value.url,{waitUntil:'domcontentloaded'});return {id};},get:async id=>({id,status:'complete',url:tabs.get(id).url()}),remove:async id=>{await tabs.get(id)?.close();}},
    scripting:{executeScript:async({target,func,args})=>[{result:await tabs.get(target.tabId).evaluate(({source,args})=>(0,eval)('('+source+')')(...args),{source:func.toString(),args})}]},
    declarativeNetRequest:{getSessionRules:async()=>rules,isRegexSupported:async()=>({isSupported:true}),updateSessionRules:async update=>{for(const id of update.removeRuleIds){const index=rules.findIndex(r=>r.id===id);if(index>=0)rules.splice(index,1);}rules.push(...update.addRules);}}
  };
  service=createInspectionService(chrome,{settings:async()=>({...config}),command:async()=>({state:'running',runId:command.runId}),event:async event=>events.push(event)});
  await service.launch(command);
  const until=Date.now()+45000;while(service.snapshot()&&Date.now()<until)await new Promise(resolve=>setTimeout(resolve,50));
  check(service.snapshot()===null,'bounded production browser service reaches a terminal state');
  check(events.some(e=>e.kind==='menu'&&e.discoveryType==='iframe'&&e.url.endsWith('/app/frame')),'visible same-Scope iframe is discovered as a separate menu candidate');
  check(events.some(e=>e.kind==='route'&&e.url.endsWith('/app/frame'))&&events.some(e=>e.kind==='menu'&&e.url.endsWith('/app/frame-menu')),'visiting the iframe URL discovers its own navigation without broad frame injection');
  const apis=events.filter(e=>e.kind==='api'),responses=apis.filter(e=>e.networkStage==='response');
  check(responses.some(e=>e.statusCode===200&&e.url.endsWith('/app/api/users')),'actual GET response200 is observed separately from request discovery');
  check(responses.some(e=>e.statusCode===403&&e.url.endsWith('/app/api/denied')),'actual denied403 remains an observed denial rather than access success');
  check(responses.every(e=>apis.some(r=>r.networkStage==='request'&&r.requestId===e.requestId)),'response callbacks preserve hashed request correlation');
  check(apis.some(e=>e.networkStage==='error'&&e.url.endsWith('/app/api/failure')&&e.statusCode===0),'transport failure remains HTTP-unconfirmed with an explicit error stage');
  check(events.some(e=>e.kind==='done'&&e.state==='partial'&&e.message.includes('응답 대기 1.5초'))&&session.inspectionPendingRequests>0,'unfinished API remains pending at the bounded final response wait');
  check(!JSON.stringify(events).includes('PRIVATE_BROWSER_QUERY')&&apis.every(e=>!('body'in e)&&!('headers'in e)),'query values and response bodies never cross browser metadata bridge');
  check(apis.every(e=>e.actor==='browser-session'&&!e.httpProfileShared),'browser actor remains separate from selected HTTP identity');
  check(!requests.some(r=>r.method==='POST'||r.path==='/outside-api'),'production MAIN guard and explicit test transport adapter keep read-only Scope');
  check(existing.url()===origin+'/existing'&&!existing.isClosed(),'existing user tab remains unchanged');
  check(rules.length===0&&session.scopeInspectionTaskV1===null,'production service cleans its dedicated tab lease and test transport rules');
  const popup=await context.newPage();await popup.setViewportSize({width:420,height:830});
  await popup.addInitScript(({config,session})=>{globalThis.chrome={storage:{local:{get:async()=>({...config}),set:async()=>{}},session:{get:async defaults=>({...defaults,...session})},onChanged:{addListener(){}}}};},{config,session});
  await popup.goto(origin+'/helper/popup.html');await popup.locator('#connect').click();await popup.waitForFunction(()=>document.querySelector('#status').textContent.includes('연결 완료'));
  check((await popup.locator('#diagnostics').innerText()).includes('응답 대기/미확인'),'production popup displays response, failure and pending counters');
  await popup.screenshot({path:path.join(out,'helper-connected.png'),fullPage:true});
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({result:'passed',passed:checks.length,checks,scope,helperVersion:JSON.parse(fs.readFileSync(path.join(helper,'manifest.json'))).version,sourceSHA256,nativeMV3Verified:false,nativeDNRVerified:false,actualChromiumDOMVerified:true,serviceAdapterVerified:true,observedResponses:responses.length,pendingRequests:session.inspectionPendingRequests,events},null,2)+'\n');
}catch(error){fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({result:'failed',scope,error:String(error.stack||error),checks,events},null,2)+'\n');throw error;}
finally{if(service?.snapshot())await service.removed(77);if(browser)await browser.close();for(const socket of sockets)socket.destroy();await new Promise(resolve=>server.close(resolve));}
