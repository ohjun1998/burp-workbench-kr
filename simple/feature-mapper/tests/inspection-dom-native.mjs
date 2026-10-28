/** Real Chromium DOM verification. Does not install the helper or verify MV3/DNR. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {webcrypto} from 'node:crypto';
import {inspectionCommand,inspectionAllows,inspectionMetadata,installInspectionGuard,inspectDocument} from '../browser-helper/inspection.js';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright-core');
const executablePath=process.argv[2],output=path.resolve(process.argv[3]||'build/inspection-dom-native');
if(!executablePath)throw new Error('Usage: node inspection-dom-native.mjs /path/to/chromium output-directory');
fs.mkdirSync(output,{recursive:true});
const profile=fs.mkdtempSync(path.join(os.tmpdir(),'scope-inspection-dom-chromium-'));
const checks=[],requests=[],observations=[];
const fixtureCookie='DOM_BROWSER_FIXTURE_412983';
let context,origin='';
const app=`<!doctype html><html lang="ko"><meta charset="utf-8"><title>Scope 메뉴 탐색 · 로컬 DOM 검증</title>
<style>body{font:16px system-ui;max-width:860px;margin:40px auto;color:#1d2a38;background:#f4f7fb}h1{font-size:25px}nav,#result{padding:20px;background:white;border:1px solid #cfdae7;border-radius:10px;margin:20px 0}nav a,nav button,details{display:block;margin:10px 0}button{padding:8px}small{color:#47566a}</style>
<h1>Scope 메뉴 탐색 · 로컬 합성 화면</h1><p>실제 Chromium에서 production DOM 탐색 함수와 보조 요청 가드를 검증합니다.</p>
<small>이 화면은 확장 설치·MV3·DNR 전송 제한 검증 결과가 아닙니다.</small>
<nav><a href="/app#/orders">주문 목록</a>
<details><summary>더 보기</summary><a href="/app#/profile">프로필</a></details>
<button type="button" aria-expanded="false" onclick="this.setAttribute('aria-expanded','true');const a=document.createElement('a');a.href='/app#/users';a.textContent='사용자 목록';document.querySelector('#lazy').append(a);fetch('/app/write',{method:'POST',body:'must-not-send'}).catch(()=>{});">메뉴 펼치기</button><span id="lazy"></span>
<a href="/app/delete">삭제</a><a href="/outside-page">범위 밖 화면</a></nav>
<div id="result">시작 화면</div><form method="post" action="/app/write"><input name="password" type="password" value="PRIVATE_FORM_FIXTURE"><button>등록</button></form><img alt="" src="/outside-asset.png">
<script>function route(){document.querySelector('#result').textContent='현재 라우트: '+(location.hash||'#home');return fetch('/app/api/'+(location.hash.includes('users')?'users':location.hash.includes('orders')?'orders':location.hash.includes('profile')?'profile':'home')).catch(()=>{});}addEventListener('hashchange',route);route();fetch('/app/write',{method:'POST',body:'must-not-send'}).catch(()=>{});fetch('/outside-api').catch(()=>{});</script></html>`;
const server=http.createServer((req,res)=>{
  requests.push({url:req.url,method:req.method,browserSession:(req.headers.cookie||'').includes(fixtureCookie)});
  if(req.url==='/setup'){res.setHeader('content-type','text/html');res.end('<!doctype html><title>Existing user tab</title><p>기존 탭 유지</p>');return;}
  if(req.url==='/app'){res.setHeader('content-type','text/html; charset=utf-8');res.end(app);return;}
  if(req.url.startsWith('/app/api/')){res.setHeader('content-type','application/json');res.end(JSON.stringify({ok:true}));return;}
  res.writeHead(404);res.end('fixture resource');
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
origin='http://127.0.0.1:'+server.address().port;
const check=(value,name)=>{assert(value,name);checks.push({name,passed:true});console.log('DOM_BROWSER_PASS '+name);};
const scope='Actual Chromium page DOM and production inspection.js functions with an isolated profile and loopback fixture; no helper installation, MV3 service worker, DNR, Burp GUI or manual permission verification.';
try{
  context=await chromium.launchPersistentContext(profile,{executablePath,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
  check(true,'actual managed Chromium starts with an isolated temporary profile');
  const existing=await context.newPage();await existing.goto(origin+'/setup');
  await context.addCookies([{name:'sid',value:fixtureCookie,url:origin,httpOnly:true,sameSite:'Lax'}]);
  const command=inspectionCommand({state:'run',runId:'actual-dom-fixture',origin,startUrl:origin+'/app',includes:[origin+'/app'],excludes:[origin+'/app/private'],maxPages:8,maxActions:12,expiresAt:Date.now()+60000});
  const page=await context.newPage();await page.setViewportSize({width:1100,height:1000});
  await page.addInitScript(installInspectionGuard,command);
  page.on('request',request=>{if(request.url().startsWith(origin+'/app/api/'))observations.push({kind:'api',url:request.url(),page:page.url(),method:request.method()});});
  const queue=[{url:command.startUrl,label:'시작 화면'}],seen=new Set(),menus=[],actions=[],routes=[];
  while(queue.length&&seen.size<command.maxPages&&actions.length<command.maxActions){
    const next=queue.shift();if(seen.has(next.url)||!inspectionAllows(next.url,command))continue;
    await page.goto(next.url);await page.waitForTimeout(120);
    const initial=await page.evaluate(({source,command})=>(0,eval)('('+source+')')(command,false),{source:inspectDocument.toString(),command});
    assert(inspectionAllows(initial.url,command));seen.add(initial.url);routes.push(initial.url);
    for(let step=0;step<12&&actions.length<command.maxActions;step++){
      const value=await page.evaluate(({source,command})=>(0,eval)('('+source+')')(command,true),{source:inspectDocument.toString(),command});
      assert(inspectionAllows(value.url,command));
      for(const route of value.routes){menus.push(route);if(!seen.has(route.url)&&!queue.some(x=>x.url===route.url))queue.push(route);}
      if(!value.action)break;actions.push(value.action);await page.waitForTimeout(120);
    }
  }
  check(routes.length>=4,'real DOM navigation reaches home and three distinct hash routes');
  check(routes.some(x=>x.endsWith('#/orders'))&&routes.some(x=>x.endsWith('#/users'))&&routes.some(x=>x.endsWith('#/profile')),'hash routes remain separate in the bounded browser traversal');
  check(actions.some(x=>x.includes('펼침 메뉴'))&&menus.some(x=>x.label==='프로필'),'production DOM function expands native details and discovers its hidden route');
  check(actions.some(x=>x.includes('메뉴 확장'))&&menus.some(x=>x.label==='사용자 목록'),'production DOM function clicks aria-expanded navigation and discovers a lazy submenu');
  check(observations.some(x=>x.url.endsWith('/app/api/users')),'real hash-route navigation produces an API request');
  check(requests.some(x=>x.url.startsWith('/app/api/')&&x.browserSession),'browser API calls use the isolated browser session');
  check(!requests.some(x=>x.method==='POST'),'production MAIN fetch guard blocks page-load and menu-triggered POST calls');
  check(!requests.some(x=>x.url==='/outside-api'),'production MAIN fetch guard blocks outside-Scope fetch calls');
  const xhr=await page.evaluate(async()=>{const result={get:false,writeBlocked:false};await new Promise(resolve=>{const request=new XMLHttpRequest();request.open('GET','/app/api/xhr');request.onload=()=>{result.get=true;resolve();};request.onerror=resolve;request.send();});try{const request=new XMLHttpRequest();request.open('PATCH','/app/write');request.send('private');}catch{result.writeBlocked=true;}return result;});
  check(xhr.get&&xhr.writeBlocked&&!requests.some(x=>x.method==='PATCH'),'production MAIN XHR guard permits scoped GET and blocks writes');
  const tabsBefore=context.pages().length;
  const avoided=await page.evaluate(()=>{let formBlocked=false;try{document.querySelector('form').requestSubmit();}catch{formBlocked=true;}return {formBlocked,popupBlocked:window.open('/outside-popup')===null};});
  check(avoided.formBlocked&&avoided.popupBlocked&&context.pages().length===tabsBefore,'production MAIN guard prevents form submission and popup creation');
  check(!menus.some(x=>x.url.includes('/delete')||x.url.includes('/outside-page'))&&!requests.some(x=>x.url.includes('/app/delete')),'destructive and outside-Scope menu actions are not selected');
  const telemetry=await Promise.all(observations.map(value=>inspectionMetadata(value,command,webcrypto)));
  const routeTelemetry=await Promise.all(routes.map(url=>inspectionMetadata({kind:'route',url,page:url},command,webcrypto)));
  check(new Set(routeTelemetry.map(x=>x.routeId)).size===routes.length,'actual browser hash routes receive distinct redacted route identities');
  const userRoute=routeTelemetry[routes.indexOf(origin+'/app#/users')];
  check(userRoute&&telemetry.some(x=>x.url.endsWith('/app/api/users')&&x.pageRouteId===userRoute.routeId),'API observation preserves the actual current hash-route context');
  check(!JSON.stringify(telemetry).includes(fixtureCookie)&&!JSON.stringify(telemetry).includes('PRIVATE_FORM_FIXTURE')&&telemetry.every(x=>x.actor==='browser-session'&&!x.httpProfileShared),'metadata excludes cookies/form values and keeps the browser actor distinct');
  check(existing.url()===origin+'/setup'&&!existing.isClosed(),'existing user tab remains unchanged');
  check(requests.some(x=>x.url==='/outside-asset.png'),'supplemental MAIN guard alone does not claim DNR containment of resource requests');
  await page.screenshot({path:path.join(output,'scope-dom-exploration.png'),fullPage:true});
  fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({result:'passed',passed:checks.length,checks,scope,verified:['real DOM menus and hash navigation','production supplemental MAIN guard','browser session and redacted route metadata'],notVerified:['helper installation','MV3 service worker','DNR tab transport restrictions','manual optional permission flow','native Burp GUI']},null,2)+'\n');
}catch(error){fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({result:'failed',checks,scope,error:String(error.stack||error),requests},null,2)+'\n');throw error;}
finally{if(context)await context.close();await new Promise(resolve=>server.close(resolve));fs.rmSync(profile,{recursive:true,force:true});}
