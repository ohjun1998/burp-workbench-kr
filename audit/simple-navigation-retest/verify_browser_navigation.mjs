/** Actual Chromium DOM + production browser service through tab/storage adapters.
 * MV3/DNR transport itself is not emulated or claimed. Uses an isolated loopback app. */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
const require=createRequire(import.meta.url);
const {chromium}=require('playwright');
const [helperArg,outputArg,expected='after',onlyCase]=process.argv.slice(2);
if(!helperArg||!outputArg||!['before','after'].includes(expected))throw Error('Usage: node verify_browser_navigation.mjs helper-directory output-directory before|after');
const helper=path.resolve(helperArg),output=path.resolve(outputArg);
const {createInspectionService}=await import(pathToFileURL(path.join(helper,'inspection-background.js')));
const {installInspectionGuard}=await import(pathToFileURL(path.join(helper,'inspection.js')));
fs.mkdirSync(output,{recursive:true});
const requests=[],checks=[],results=[];let context;
const wrap=content=>'<!doctype html><meta charset="utf-8"><title>Navigation regression fixture</title><style>body{margin:10px;font:14px system-ui}a,summary,button{display:inline-block;margin:2px}details{display:block}nav{max-width:1100px}</style>'+content+`<script>fetch('/outside-api').catch(()=>{});fetch('/app/write',{method:'POST',body:'must-not-send'}).catch(()=>{});</script>`;
const bodies={
  late:wrap(`<nav id="menu"></nav><script>setTimeout(()=>{document.querySelector('#menu').innerHTML='<a href="/app/late#/delayed">늦게 열린 메뉴</a>';},1800);function route(){if(location.hash)fetch('/app/api/late').catch(()=>{});}addEventListener('hashchange',route);route();</script>`),
  replace:wrap(`<nav><a href="/app/replace#/initial">초기 메뉴</a><button type="button" role="tab" aria-selected="false" onclick="this.setAttribute('aria-selected','true');this.parentNode.querySelector('a').remove();const a=document.createElement('a');a.href='/app/replace#/replacement';a.textContent='변경 후 메뉴';this.parentNode.append(a);">읽기 탭</button></nav>`),
  capped:wrap('<nav>'+Array.from({length:13},(_,i)=>`<details><summary>메뉴 ${i+1}</summary><span>보기</span></details>`).join('')+'</nav>'),
  navigate:wrap(`<nav><button type="button" role="tab" aria-selected="false" onclick="location.href='/app/destination'">읽기 화면 이동</button></nav>`),
  destination:wrap('<nav><a href="/app/destination#/content">이동한 화면 메뉴</a></nav>'),
  busy:wrap('<nav aria-busy="true">불러오는 중</nav>'),
  overflow:wrap('<nav>'+Array.from({length:201},(_,i)=>`<a href="/app/overflow?entry=${i}">항목 ${i}</a>`).join('')+'</nav>')
};
const server=http.createServer((req,res)=>{
  requests.push({url:req.url,method:req.method});
  const pathname=new URL(req.url,'http://fixture').pathname;
  const key=pathname.split('/')[2];
  if(pathname==='/setup'){res.end('<!doctype html><title>Existing tab</title>preserved');return;}
  if(bodies[key]){res.setHeader('content-type','text/html; charset=utf-8');res.end(bodies[key]);return;}
  if(pathname.startsWith('/app/api/')){res.setHeader('content-type','application/json');res.end('{"ok":true}');return;}
  res.writeHead(404);res.end('not found');
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin='http://127.0.0.1:'+server.address().port;
const check=(value,name)=>{assert(value,name);checks.push({name,passed:true});};
try{
  const browser=await chromium.launch({executablePath:process.env.CHROMIUM||'/usr/bin/chromium',headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
  context=await browser.newContext({viewport:{width:1280,height:1200}});
  const existing=await context.newPage();await existing.goto(origin+'/setup');
  for(const name of Object.keys(bodies).filter(x=>x!=='destination'&&(!onlyCase||x===onlyCase))){
    const command={state:'run',runId:'navigation-'+name,origin,startUrl:origin+'/app/'+name,includes:[origin+'/app'],excludes:[origin+'/app/private'],maxPages:8,maxActions:100,expiresAt:Date.now()+60000};
    const page=await context.newPage();await page.addInitScript(installInspectionGuard,command);
    let pageUrl='about:blank',closed=false;const events=[],session={},rules=[];
    const chrome={storage:{session:{set:async value=>Object.assign(session,value),get:async key=>({[key]:session[key]})}},permissions:{contains:async()=>true},
      tabs:{create:async()=>({id:77}),update:async(id,{url})=>{await page.goto(url);pageUrl=page.url();return {id};},get:async id=>({id,url:page.url(),status:'complete'}),remove:async()=>{closed=true;}},
      scripting:{executeScript:async({func,args})=>[{result:await page.evaluate(({source,args})=>(0,eval)('('+source+')')(...args),{source:func.toString(),args})}]},
      // Rule construction/lifetime only. Native extension policy blocks installation here.
      declarativeNetRequest:{getSessionRules:async()=>rules,isRegexSupported:async()=>({isSupported:true}),updateSessionRules:async({removeRuleIds,addRules})=>{for(const id of removeRuleIds){const i=rules.findIndex(x=>x.id===id);if(i>=0)rules.splice(i,1);}rules.push(...addRules);}}
    };
    const settings={inspectionEnabled:true,base:origin,token:'a'.repeat(64),origins:[origin]};
    const service=createInspectionService(chrome,{settings:async()=>settings,command:async()=>({state:'running',runId:command.runId}),event:async event=>events.push(event)});
    const started=Date.now();await service.launch(command);
    while(service.snapshot()&&Date.now()-started<30000)await new Promise(resolve=>setTimeout(resolve,50));
    check(!service.snapshot(),name+': bounded service reaches terminal and releases lease');
    const done=events.findLast(x=>['done','error'].includes(x.kind));
    check(done?.kind==='done',name+': no browser service exception '+(done?.kind==='error'?done.message:''));
    const menuNames=events.filter(x=>x.kind==='menu').map(x=>x.label);
    const result={name,state:done.state,message:done.message,pages:done.pages,actions:done.actions,menuCount:menuNames.length,elapsedMillis:Date.now()-started,
      delayedMenu:menuNames.includes('늦게 열린 메뉴'),initialMenu:menuNames.includes('초기 메뉴'),replacementMenu:menuNames.includes('변경 후 메뉴'),unopenedDetails:await page.locator('details:not([open])').count()};
    if(name==='navigate')check(menuNames.includes('이동한 화면 메뉴'),'navigate: read-only full navigation settles and discovers destination links');
    if(name==='late')check(result.delayedMenu===(expected==='after'),'late: delayed hydration menu '+(expected==='after'?'is retained':'was missed before fix'));
    if(name==='replace')check(result.initialMenu===(expected==='after'),'replace: initial links '+(expected==='after'?'survive action mutation':'were lost before fix'));
    if(['capped','busy','overflow'].includes(name))check(result.state===(expected==='after'?'partial':'completed'),name+': limits '+(expected==='after'?'remain explicitly partial':'were incorrectly complete before fix'));
    if(name==='capped')check(result.unopenedDetails===1,'capped: 12-action document bound remains unchanged');
    check(closed&&rules.length===0&&session.scopeInspectionTaskV1===null,name+': owned tab and guards/state released');
    await page.screenshot({path:path.join(output,name+'.png'),fullPage:false});
    results.push(result);await page.close();
  }
  check(!requests.some(x=>x.method==='POST'),'actual MAIN guard blocks page-load POST across all fixtures');
  check(!requests.some(x=>x.url.startsWith('/outside-api')),'actual MAIN guard blocks outside-Scope fetch across all fixtures');
  check(existing.url()===origin+'/setup'&&!existing.isClosed(),'existing user tab remains unchanged');
  const summary={passed:true,result:'passed',expected,checks:checks.length,details:checks,cases:results,chromium:await browser.version(),
    scope:'Actual Chromium DOM and production inspection service through Playwright tab/storage adapters; loopback synthetic fixtures, same conditions before/after. Native MV3 service-worker/DNR transport and Burp GUI are not verified.'};
  fs.writeFileSync(path.join(output,'results.json'),JSON.stringify(summary,null,2)+'\n');
  console.log(JSON.stringify(summary));
  await browser.close();context=null;
}catch(error){fs.writeFileSync(path.join(output,'failure.json'),JSON.stringify({error:String(error.stack||error),checks,results},null,2)+'\n');throw error;}
finally{if(context)await context.close();await new Promise(resolve=>server.close(resolve));}
