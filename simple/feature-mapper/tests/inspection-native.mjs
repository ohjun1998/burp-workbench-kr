/** Real Chromium helper/SPA/DNR verification using an isolated temporary profile and loopback app. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright-core');
const executablePath=process.argv[2],output=path.resolve(process.argv[3]||'build/inspection-browser');
if(!executablePath)throw new Error('Usage: node inspection-native.mjs /path/to/chromium output-directory');
fs.mkdirSync(output,{recursive:true});
const profile=fs.mkdtempSync(path.join(os.tmpdir(),'scope-inspection-chromium-'));
const token='a'.repeat(64),events=[],checks=[],requests=[];
let task=null,leased=false,origin='',context;
const secret='BROWSER_FIXTURE_COOKIE_392838';
const app=`<!doctype html><html lang="ko"><meta charset="utf-8"><title>Scope SPA fixture</title>
<nav><a id="home" href="/app">홈</a><a href="/app#/orders">주문 목록</a>
<details><summary>더 보기</summary><a href="/app#/profile">프로필</a></details>
<button type="button" aria-expanded="false" onclick="this.setAttribute('aria-expanded','true');const link=document.createElement('a');link.href='/app#/users';link.textContent='사용자 목록';document.querySelector('#lazy').append(link);fetch('/app/write',{method:'POST',body:'must-not-send'}).catch(()=>{});">메뉴 펼치기</button><span id="lazy"></span>
<a href="/app/delete">삭제</a><a href="/outside">외부 범위</a></nav>
<form method="post" action="/app/write"><input name="password" type="password" value="PRIVATE_FORM_VALUE"><button>등록</button></form>
<div id="result"></div><img src="/outside-resource.png">
<script>async function route(){const route=location.hash||'#home';document.querySelector('#result').textContent=route;await fetch('/app/api/'+(route.includes('users')?'users':route.includes('orders')?'orders':'home')).catch(()=>{});}addEventListener('hashchange',route);route();fetch('/app/write',{method:'POST',body:'must-not-send'}).catch(()=>{});fetch('/outside-api').catch(()=>{});</script></html>`;
const server=http.createServer(async(req,res)=>{
  let body='';for await(const chunk of req)body+=chunk;
  if(req.url.startsWith('/inspection/command')||req.url==='/health'||req.url==='/event'){
    if(req.headers['x-feature-mapper-token']!==token){res.writeHead(401);res.end('unauthorized');return;}
    if(req.url==='/health'){res.end('Feature Mapper KR fixture');return;}
    if(req.url==='/event'){
      const event=JSON.parse(body);events.push(event);res.end('accepted');return;
    }
    res.setHeader('content-type','application/json');
    if(!task){res.end(JSON.stringify({state:'idle'}));return;}
    const done=events.some(x=>x.type==='inspection'&&x.runId===task.runId&&['done','error'].includes(x.kind));
    const value={...task,state:done?'idle':leased?'running':'run'};leased=true;
    res.end(JSON.stringify(value));return;
  }
  requests.push({url:req.url,method:req.method,cookie:req.headers.cookie||''});
  if(req.url==='/setup'){res.setHeader('content-type','text/html');res.end('<!doctype html><title>Existing user tab</title><p>이 탭은 바꾸지 않습니다.</p>');return;}
  if(req.url==='/app'){res.setHeader('content-type','text/html');res.end(app);return;}
  if(req.url.startsWith('/app/api/')){res.setHeader('content-type','application/json');res.end(JSON.stringify({ok:true,browserSession:(req.headers.cookie||'').includes(secret)}));return;}
  res.writeHead(404);res.end('not found');
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));origin='http://127.0.0.1:'+server.address().port;
const check=(value,name)=>{assert(value,name);checks.push({name,passed:true});console.log('BROWSER_PASS '+name);};
try{
  const helper=path.resolve(path.dirname(new URL(import.meta.url).pathname),'../browser-helper');
  context=await chromium.launchPersistentContext(profile,{executablePath,channel:'chromium',headless:true,ignoreDefaultArgs:['--disable-extensions'],args:['--no-sandbox','--disable-dev-shm-usage','--disable-extensions-except='+helper,'--load-extension='+helper]});
  let worker=context.serviceWorkers()[0];
  if(!worker)try{worker=await context.waitForEvent('serviceworker',{timeout:20000});}
  catch(error){
    const diagnostic={message:String(error),helper,targets:[],extensionPage:'',popup:''};
    const page=await context.newPage();
    try{const cdp=await context.newCDPSession(page);diagnostic.targets=(await cdp.send('Target.getTargets')).targetInfos.map(x=>({type:x.type,url:x.url}));await cdp.detach();}catch(e){diagnostic.targetError=String(e);}
    try{await page.goto('chrome://extensions/');diagnostic.extensionPage=await page.evaluate(()=>{const values=[];const walk=node=>{if(node.nodeType===3)values.push(node.textContent);if(node.shadowRoot)walk(node.shadowRoot);for(const child of node.childNodes||[])walk(child);};walk(document);return values.join(' ').replace(/\s+/g,' ').slice(0,40000);});await page.screenshot({path:path.join(output,'extension-load-diagnostics.png'),fullPage:true});}catch(e){diagnostic.extensionError=String(e);}
    const extensionId=createHash('sha256').update(helper).digest('hex').slice(0,32).replace(/[0-9a-f]/g,x=>String.fromCharCode(97+parseInt(x,16)));
    try{page.on('pageerror',e=>{diagnostic.popupErrors=(diagnostic.popupErrors||[]).concat(String(e));});await page.goto('chrome-extension://'+extensionId+'/popup.html');await page.waitForTimeout(500);diagnostic.popup=await page.locator('body').innerText();}catch(e){diagnostic.popupError=String(e);}
    const policyBlocked=diagnostic.extensionPage.includes('managed by your administrator')&&String(diagnostic.popupError||'').includes('ERR_BLOCKED_BY_CLIENT');
    diagnostic.managedExtensionPolicyBlocked=policyBlocked;
    fs.writeFileSync(path.join(output,'load-diagnostics.json'),JSON.stringify(diagnostic,null,2)+'\n');
    if(policyBlocked){const blocked=new Error('System Chromium managed extension policy blocks loading the unpacked helper; MV3/DNR verification was not executed.');blocked.code='EXTENSION_POLICY_BLOCKED';throw blocked;}
    throw error;
  }
  check(!!worker,'actual Chromium loads the configured helper and MV3 service worker');
  const id=new URL(worker.url()).host;
  const popup=await context.newPage();await popup.setViewportSize({width:420,height:760});await popup.goto('chrome-extension://'+id+'/popup.html');await popup.evaluate(async()=>{await import(chrome.runtime.getURL('popup.js'));});
  await popup.locator('#base').fill(origin);await popup.locator('#token').fill(token);await popup.locator('#connect').click();await popup.waitForFunction(()=>document.querySelector('#status').textContent.includes('연결 완료'));
  check(true,'real helper pairs over authenticated loopback protocol');
  const existing=await context.newPage();await existing.goto(origin+'/setup');await existing.bringToFront();await popup.locator('#enable').click();await popup.waitForFunction(()=>document.querySelector('#status').textContent.includes('활성화 완료'));
  await popup.locator('#inspection').check();
  await popup.screenshot({path:path.join(output,'helper-connected.png'),fullPage:true});
  await context.addCookies([{name:'sid',value:secret,url:origin,httpOnly:true,sameSite:'Lax'}]);
  task={state:'run',runId:'native-spa-inspection',origin,startUrl:origin+'/app',includes:[origin+'/app'],excludes:[origin+'/app/private'],maxPages:8,maxActions:12,expiresAt:Date.now()+60000};
  const end=Date.now()+65000;
  while(Date.now()<end&&!events.some(x=>x.type==='inspection'&&x.runId===task.runId&&['done','error'].includes(x.kind)))await new Promise(resolve=>setTimeout(resolve,100));
  const inspection=events.filter(x=>x.type==='inspection');
  const failure=inspection.find(x=>x.kind==='error');assert(!failure,failure?.message);
  check(inspection.some(x=>x.kind==='started'),'Workbench command starts an isolated browser inspection tab');
  check(inspection.some(x=>x.kind==='done'),'bounded menu crawl reaches a terminal state');
  check(inspection.filter(x=>x.kind==='route').length>=3,'real DOM navigation visits separate hash routes');
  check(inspection.some(x=>x.kind==='menu'&&x.label.includes('사용자')),'aria-expanded menu is clicked and lazy submenu is discovered');
  check(inspection.some(x=>x.kind==='menu'&&x.label.includes('프로필')),'native details menu is expanded and its hidden route discovered');
  check(inspection.some(x=>x.kind==='api'&&x.url.endsWith('/app/api/users')),'SPA hash route causes a real API request observed by helper');
  check(requests.some(x=>x.url.startsWith('/app/api/')&&x.cookie.includes(secret)),'exploration uses the browser login session without exporting its cookie');
  check(!requests.some(x=>x.method==='POST'&&x.url.startsWith('/app/write')),'DNR blocks page-load and menu-triggered writes before target transport');
  check(!requests.some(x=>x.url.startsWith('/outside')),'DNR blocks out-of-Scope resources and API calls before target transport');
  check(!requests.some(x=>x.url.startsWith('/app/delete')),'destructive navigation is not visited');
  check(!JSON.stringify(inspection).includes(secret)&&!JSON.stringify(inspection).includes('PRIVATE_FORM_VALUE'),'metadata never forwards cookie or form values');
  check(inspection.every(x=>x.actor==='browser-session'&&x.httpProfileShared===false),'browser observations remain separate from selected HTTP comparison identity');
  check(existing.url()===origin+'/setup'&&!existing.isClosed(),'existing user tab remains open and unchanged');
  const remaining=await worker.evaluate(()=>chrome.declarativeNetRequest.getSessionRules());check(remaining.length===0,'finished task releases tab-limited transport rules');
  const state=await worker.evaluate(()=>chrome.storage.session.get('scopeInspectionTaskV1'));check(state.scopeInspectionTaskV1===null,'finished task releases persisted browser execution state');
  fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({result:'passed',checks,passed:checks.length,scope:'Actual Chromium MV3 helper, isolated profile, loopback SPA/protocol fixture; not native Burp GUI.'},null,2)+'\n');
}catch(error){fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({result:error.code==='EXTENSION_POLICY_BLOCKED'?'blocked':'failed',checks,reason:error.code||'',error:String(error.stack||error),events,requests:requests.map(x=>({...x,cookie:x.cookie?'[REDACTED]':''}))},null,2)+'\n');throw error;}
finally{if(context)await context.close();await new Promise(resolve=>server.close(resolve));fs.rmSync(profile,{recursive:true,force:true});}
