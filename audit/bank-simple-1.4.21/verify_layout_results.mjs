#!/usr/bin/env node
// Verify the saved-result UI using one HTML response, with no bank or asset service.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {chromium}=require('playwright');
const directory=path.resolve(process.argv[2]||'docs/reports/2026-11-01-bank-simple-1.4.21/layout-view');
const filename='Workbench_Simple_1.4.21_Results_Layout_KR.html';
const source=fs.readFileSync(path.join(directory,filename));
const original=JSON.parse(source.toString().match(/<script type="application\/json" id="layout-data">([\s\S]*?)<\/script>/)[1]);
const digest=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const originalDataDigest=digest(JSON.stringify(original));
const output=path.resolve('/workspace/tools/burp-workbench/bank-simple-1.4.21/layout-final-preview');
fs.mkdirSync(output,{recursive:true});
const assert=(condition,message)=>{if(!condition)throw Error(message);};
const requests=[],networkAttempts=[],errors=[],checks=[],geometry=[];
const server=http.createServer((req,res)=>{
  requests.push({method:req.method,path:req.url});
  if(req.url!=='/layout.html'){res.writeHead(404);res.end();return;}
  res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});res.end(source);
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin='http://127.0.0.1:'+server.address().port;
let browser,page,failure;
async function check(id,title,action){
  const start=Date.now();
  try{const value=await action();checks.push({id,title,result:'passed',milliseconds:Date.now()-start,...(value||{})});}
  catch(error){checks.push({id,title,result:'failed',message:error.message,milliseconds:Date.now()-start});throw error;}
}
async function navigate(id){await page.evaluate(id=>WorkbenchSavedResults.navigate(id),id);}
async function rows(key){return page.locator(`tr[data-row="${key}"]`).count();}
async function firstRows(){const table=page.locator('#page .data-table').first();return await table.count()?table.locator('tbody tr').count():0;}
async function setTheme(theme){if(await page.locator('body').getAttribute('data-theme')!==theme)await page.click('#theme-toggle');}
async function screenshot(name){await page.evaluate(()=>document.fonts.ready);await page.screenshot({path:path.join(output,name),fullPage:false});}
async function measure(id,width,height){
  const measured=await page.evaluate(()=>{
    const width=innerWidth;
    const cards=[...document.querySelectorAll('#page .card')].map(element=>{
      const r=element.getBoundingClientRect();return {left:r.left,right:r.right,width:r.width};
    });
    const top=document.querySelector('.topbar').getBoundingClientRect(),main=document.querySelector('.main').getBoundingClientRect();
    return {viewportWidth:width,bodyWidth:document.documentElement.scrollWidth,topbarWidth:top.width,mainWidth:main.width,
      navHidden:document.body.classList.contains('nav-hidden'),cardCount:cards.length,
      outsideCards:cards.filter(r=>r.left < -1||r.right>width+1),
      horizontalScrollTables:[...document.querySelectorAll('.table-wrap')].filter(x=>x.scrollWidth>x.clientWidth+1).length};
  });
  geometry.push({page:id,width,height,...measured});
  assert(measured.bodyWidth<=width+1,`Body overflow ${id} ${width}: ${measured.bodyWidth}`);
  assert(!measured.outsideCards.length,`Card outside viewport ${id} ${width}: ${JSON.stringify(measured.outsideCards)}`);
}
try{
  browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--disable-background-networking']});
  page=await browser.newPage({viewport:{width:1440,height:1000},acceptDownloads:true});
  page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/*',route=>{
    const url=route.request().url();
    if(/^https?:/.test(url)&&url!==origin+'/layout.html'){networkAttempts.push(url);return route.abort();}
    return route.continue();
  });
  await page.goto(origin+'/layout.html');
  await page.waitForFunction(()=>Boolean(window.WorkbenchSavedResults));
  await page.evaluate(()=>document.fonts.ready);
  const pageIds=['traffic','mapper','auth','js','environment','response','payload','bounty','data','activity','scope','dashboard','exposure'];
  const expected={traffic:89,mapper:135,auth:3,js:19,environment:4,response:5,payload:5,bounty:5,data:0,activity:91,scope:525,dashboard:46,exposure:0};
  await check('VIEWS','13 saved-result pages under both themes',async()=>{
    assert(await page.locator('[data-nav]').count()===13,'Navigation count');
    for(const theme of ['light','dark']){
      await setTheme(theme);
      for(const id of pageIds){await navigate(id);const count=await firstRows();assert(count===expected[id],`${theme}/${id} rows ${count} != ${expected[id]}`);await measure(id,1440,1000);}
    }
    return {pages:13,themes:2};
  });
  await setTheme('light');
  await check('AUTH','Actual Alice baseline, Bob comparison and protected controls',async()=>{
    await navigate('auth');
    const headers=await page.locator('#page .split .card-head strong').allTextContents();
    assert(headers[0].includes('wb_alice')&&headers[1].includes('wb_bob'),'IDOR account captions');
    const response=await page.locator('#page .split .raw').allTextContents();
    assert(response.length===2&&response.every(x=>x.includes('wb_alice')&&x.includes('1005')),'IDOR actual responses');
    await screenshot('light-auth.png');
    await page.locator('tr[data-row="auth"][data-index="1"]').click();
    assert((await page.locator('#page .split .card-head').allTextContents())[0].includes('admin'),'Protected baseline');
    assert((await page.locator('#page .split .card-head').allTextContents())[1].includes('403'),'Protected Bob403');
    await page.selectOption('[data-control="authActor"]','anonymous');
    assert((await page.locator('#page .split .card-head').allTextContents())[1].includes('401'),'Protected anonymous401');
    await page.locator('[data-tab="authDetail"][data-value="judgement"]').click();
    assert(await rows('auth-cells')===4,'Judgement rows');
    await page.selectOption('[data-control="authActor"]','wb_bob');
    await page.locator('tr[data-row="auth"][data-index="0"]').click();
    await page.locator('[data-tab="authDetail"][data-value="responses"]').click();
  });
  await check('PAYLOAD','SQL candidate selected with actual GET and404 response',async()=>{
    await navigate('payload');
    await page.locator('tr[data-row="payload"]').filter({hasText:'SQL 조건 차이 후보'}).click();
    const values=await page.locator('#page .split .raw').allTextContents();
    assert(values.length===2&&/^GET /m.test(values[0]),'Actual GET request');
    assert(values[1].includes('Account not found'),'Actual404 body');
    assert(await page.locator('tr[data-row="payload"].selected').count()===1,'Selected candidate');
    await screenshot('light-payload.png');
  });
  await check('JS','Original source counts and local search reset',async()=>{
    await navigate('js');
    const openapi=original.js.findIndex(x=>x.url.endsWith('/static/openapi.json'));
    await page.selectOption('[data-control="jsSource"]',String(openapi));
    assert(await rows('js')===original.js[openapi].endpoints.length&&await rows('js')===42,'OpenAPI42');
    await page.selectOption('[data-control="jsSource"]','0');
    assert(await rows('js')===19,'Dashboard.js19');
    await page.fill('#search','transfer');
    assert(await rows('js')===original.js[0].endpoints.filter(x=>JSON.stringify(x).toLowerCase().includes('transfer')).length,'Transfer search');
    await page.fill('#search','');assert(await rows('js')===19,'Search reset');
    await page.fill('#search','no-such-saved-path');await navigate('traffic');
    assert(await page.inputValue('#search')===''&&await rows('traffic')===89,'Navigation resets local query');
  });
  await check('MAPPER','Feature selection uses actual primary assignments',async()=>{
    await navigate('mapper');
    const feature=original.mapper.metadata.features.find(x=>original.mapper.metadata.packets.some(p=>p.primary===x.id));
    const count=original.mapper.metadata.packets.filter(p=>p.primary===feature.id).length;
    await page.locator(`[data-feature="${feature.id}"]`).click();assert(await rows('mapper')===count,'Mapper selected feature');
    await page.locator('[data-feature="all"]').click();assert(await rows('mapper')===135,'Mapper all');
    return {selectedFeatureRows:count};
  });
  await check('SCOPE','Category filtering keeps candidate-only observations',async()=>{
    await navigate('scope');await page.selectOption('[data-control="category"]','API');
    assert(await rows('scope')===original.scope.findings.filter(x=>x.category==='API').length&&await rows('scope')===173,'ScopeAPI173');
    await page.fill('#search','no-such-saved-path');assert(await rows('scope')===0,'Empty filtered scope');
    await page.fill('#search','');await page.selectOption('[data-control="category"]','');assert(await rows('scope')===525,'Scope reset');
  });
  await check('DASHBOARD','Six original projection counts and empty access group',async()=>{
    await navigate('dashboard');
    const numbers=(await page.locator('[data-metric] strong').allTextContents()).map(Number);
    assert(JSON.stringify(numbers)===JSON.stringify([46,183,53,7,0,126]),'Dashboard projected metrics');
    await page.locator('[data-metric="api"]').click();assert(await rows('dashboard')===183,'Dashboard API183');
    await page.locator('[data-metric="access"]').click();assert(await rows('dashboard')===0,'Dashboard access0');
    await page.locator('[data-metric="paths"]').click();assert(await rows('dashboard')===46,'Dashboard paths46');
    await setTheme('dark');await screenshot('dark-dashboard.png');await setTheme('light');
  });
  await check('DATA','JWT claim display is decoding without signature verification',async()=>{
    await navigate('data');await page.locator('[data-tab="data"][data-value="jwt"]').click();
    const values=await page.locator('#page .split .raw').allTextContents();const header=JSON.parse(values[0]),payload=JSON.parse(values[1]);
    assert(header.alg==='HS256'&&payload.is_admin===false&&!('exp' in payload),'JWT actual fields');
    assert((await page.locator('#page').innerText()).includes('서명 미검증'),'JWT label');
    await page.locator('[data-tab="data"][data-value="compare"]').click();
  });
  await check('BUSINESS','Selected real negative transfer request and numeric outcomes',async()=>{
    await navigate('bounty');await page.locator('[data-tab="bounty"][data-value="request"]').click();
    const selected=await page.locator('tr[data-row="bounty"].selected').innerText();assert(selected.includes('POST')&&selected.includes('/transfer'),'Business POST selection');
    const bodies=await page.locator('#page .raw').allTextContents();
    assert(bodies.some(x=>{try{return JSON.parse(x).amount===-5;}catch{return false;}}),'Actual request amount-5');
    const text=await page.locator('#page').innerText();assert(text.includes('1000 → 1005')&&text.includes('1000 → 995'),'Actual numeric outcomes');
  });
  await check('DOWNLOAD','Local JSON download matches preserved data',async()=>{
    await navigate('auth');const event=page.waitForEvent('download');await page.click('#export-data');const download=await event;
    const downloaded=fs.readFileSync(await download.path());assert(digest(downloaded)===digest(JSON.stringify(original.auth,null,2)),'Export fingerprint');
    return {filename:download.suggestedFilename(),sha256:digest(downloaded)};
  });
  await check('SIDEBAR','Two42px primary entries stay fixed while the narrow sidebar scrolls',async()=>{
    await page.setViewportSize({width:1024,height:600});
    await page.evaluate(()=>document.body.classList.remove('nav-hidden'));await navigate('traffic');
    const initial=await page.evaluate(()=>{
      const sidebar=document.querySelector('.sidebar');sidebar.scrollTop=0;
      return [...document.querySelectorAll('.nav-primary .nav-item')].map(x=>{
        const r=x.getBoundingClientRect();return {x:r.x,y:r.y,height:r.height};
      });
    });
    await page.evaluate(()=>{const sidebar=document.querySelector('.sidebar');sidebar.scrollTop=sidebar.scrollHeight;});
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    const after=await page.evaluate(()=>{
      const sidebar=document.querySelector('.sidebar');
      return {scrolled:sidebar.scrollTop,rows:[...document.querySelectorAll('.nav-primary .nav-item')].map(x=>{
        const r=x.getBoundingClientRect();return {x:r.x,y:r.y,height:r.height};
      })};
    });
    assert(initial.length===2&&after.scrolled>0,'Sidebar scroll actually exercised');
    assert(initial.every((r,i)=>r.height===42&&after.rows[i].height===42&&Math.abs(r.x-after.rows[i].x)<1&&Math.abs(r.y-after.rows[i].y)<1),'Primary rows moved while scrolling');
    await page.click('#menu-toggle');assert(await page.locator('body').evaluate(x=>x.classList.contains('nav-hidden')),'Sidebar hide');
    await page.click('#menu-toggle');assert(!await page.locator('body').evaluate(x=>x.classList.contains('nav-hidden')),'Sidebar restore');
    await page.evaluate(()=>document.querySelector('.sidebar').scrollTop=0);
    return {scrollPixels:after.scrolled,rowHeights:initial.map(x=>x.height)};
  });
  await check('RESPONSIVE','1024 and390 viewports keep cards within the page',async()=>{
    for(const [width,height] of [[1024,768],[390,844]]){
      await page.setViewportSize({width,height});
      if(width<760)await page.evaluate(()=>document.body.classList.add('nav-hidden'));
      for(const id of pageIds){await navigate(id);await measure(id,width,height);}
      if(width===390){await navigate('auth');await screenshot('mobile-auth.png');}
    }
  });
  await check('IMMUTABLE','All interactions preserve saved values and send no extra HTTP',async()=>{
    const current=await page.evaluate(()=>JSON.stringify(WorkbenchSavedResults.data));
    assert(digest(current)===originalDataDigest,'Data mutated by UI');
    assert(!errors.length,'JavaScript errors: '+errors.join(';'));
    assert(!networkAttempts.length,'Additional HTTP attempted');
    assert(requests.length===1&&requests[0].path==='/layout.html','One HTML only server');
    return {dataSha256:originalDataDigest};
  });
}catch(error){failure=error;console.error(error.stack||error.message);}
finally{
  if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));
  const result={result:failure?'failed':'passed',htmlFilename:filename,htmlSha256:digest(source),
    savedDataSha256:originalDataDigest,checks,checkCount:checks.length,passedChecks:checks.filter(x=>x.result==='passed').length,
    pageErrors:errors,additionalHttpAttempts:networkAttempts,servedRequests:requests,
    singleHtmlWithoutAssetDirectory:true,bankRequests:0,geometry,
    qaScreenshotsOutsideRepository:output,
    scope:'Saved-results HTML reconstruction checks only; no native Burp, bank service, new diagnosis or source result mutation.',
    fileUrlNotExercised:'Managed Chromium file URL policy; one-file loopback server used.'};
  fs.writeFileSync(path.join(directory,'browser-verification.json'),JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify({result:result.result,checks:result.checkCount,passed:result.passedChecks,geometry:geometry.length,pageErrors:errors.length,extraHttp:networkAttempts.length}));
}
if(failure)process.exitCode=1;
