#!/usr/bin/env node
// Verify a complete, saved-results HTML without requesting the bank or any asset service.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
const {chromium}=createRequire(import.meta.url)('playwright');
const repository=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
if(!process.argv[2])throw Error('Usage: node verify_grouped_html.mjs COMPLETE_HTML [OUTPUT_DIRECTORY]');
const htmlPath=path.resolve(process.argv[2]);
const output=path.resolve(process.argv[3]||path.join(path.dirname(htmlPath),'browser-check'));
const originalPath=path.join(repository,'docs/reports/2026-11-01-bank-simple-1.4.21/layout-view/Workbench_Simple_1.4.21_Results_Layout_KR.html');
const source=fs.readFileSync(htmlPath),originalSource=fs.readFileSync(originalPath);
const embedded=bytes=>{
  const match=bytes.toString().match(/<script type="application\/json" id="layout-data">([\s\S]*?)<\/script>/);
  if(!match)throw Error('Complete embedded saved-result data is missing');return match[1];
};
const dataText=embedded(source),originalText=embedded(originalSource),saved=JSON.parse(dataText);
const digest=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const assert=(value,message)=>{if(!value)throw Error(message)};
assert(dataText===originalText,'Embedded bank-result JSON changed from the preserved original');
assert(!source.toString().includes('__LAYOUT_DATA__'),'Unpopulated data placeholder remains');
fs.mkdirSync(output,{recursive:true});
const errors=[],attempts=[],requests=[],checks=[],geometry=[],screenshots=[];
const server=http.createServer((req,res)=>{
  requests.push({method:req.method,path:req.url});
  if(req.url!=='/grouped.html'){res.writeHead(404);res.end();return;}
  res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});res.end(source);
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const address='http://127.0.0.1:'+server.address().port+'/grouped.html';
const pageIds=['traffic','mapper','auth','js','environment','response','payload','bounty','data','activity','scope','dashboard','exposure'];
const expected={traffic:89,mapper:135,auth:3,js:19,environment:4,response:5,payload:5,bounty:5,data:0,activity:91,scope:525,dashboard:46,exposure:0};
const groupedPages=['traffic','mapper','auth','js','environment','response','bounty','activity','scope','dashboard'];
let browser,page,failure;
async function check(id,title,action){
  const start=Date.now();
  try{const detail=await action();checks.push({id,title,result:'passed',milliseconds:Date.now()-start,...(detail||{})});}
  catch(error){checks.push({id,title,result:'failed',message:error.message,milliseconds:Date.now()-start});throw error;}
}
async function navigate(id){await page.evaluate(id=>WorkbenchSavedResults.navigate(id),id);}
const rowLocator=key=>page.locator(`tr[data-row="${key}"]`);
async function rows(key,visible=false){return page.locator(`tr[data-row="${key}"]${visible?':visible':''}`).count();}
async function setTheme(theme){if(await page.locator('body').getAttribute('data-theme')!==theme)await page.click('#theme-toggle');}
function allButton(owner){return page.locator(`button[data-result-owner="${owner}"][data-result-group="all"]`);}
async function all(owner){const button=allButton(owner);if(await button.count())await button.click();}
async function screenshot(name){
  await page.evaluate(()=>document.fonts.ready);
  const filename=name+'.png';await page.screenshot({path:path.join(output,filename),fullPage:false});
  screenshots.push(filename);
}
async function geometryCheck(id,width,height){
  const measurement=await page.evaluate(()=>{
    const canvas=document.createElement('canvas'),context=canvas.getContext('2d');
    const headers=[...document.querySelectorAll('.grouped-result .data-table th')].filter(element=>element.getClientRects().length).map(element=>{
      const style=getComputedStyle(element);context.font=style.font;const text=element.textContent.trim();
      const minimum=Math.min(56,context.measureText(text).width+10),r=element.getBoundingClientRect();
      return {text,width:r.width,minimum};
    });
    const cards=[...document.querySelectorAll('#page .card')].filter(element=>element.getClientRects().length).map(element=>{
      const r=element.getBoundingClientRect();return {left:r.left,right:r.right,width:r.width};
    });
    const clippedControls=[...document.querySelectorAll('#page select')].filter(element=>element.getClientRects().length).map(element=>{
      const bounds=element.getBoundingClientRect(),card=element.closest('.card')?.getBoundingClientRect(),page=document.querySelector('#page').getBoundingClientRect();
      return {name:element.dataset.control||element.getAttribute('aria-label'),height:bounds.height,
        visiblePageHeight:Math.max(0,Math.min(bounds.bottom,page.bottom)-Math.max(bounds.top,page.top)),
        visibleCardHeight:card?Math.max(0,Math.min(bounds.bottom,card.bottom)-Math.max(bounds.top,card.top)):bounds.height};
    }).filter(element=>element.height<28||element.visibleCardHeight+1<element.height||(element.name==='jsSource'&&element.visiblePageHeight<28));
    return {bodyWidth:document.documentElement.scrollWidth,viewportWidth:innerWidth,
      outsideCards:cards.filter(r=>r.left < -1||r.right>innerWidth+1),cardCount:cards.length,
      headers,smallHeaders:headers.filter(h=>h.width+1<h.minimum),clippedControls,
      groupCount:document.querySelectorAll('.grouped-result').length,
      horizontalTables:[...document.querySelectorAll('.table-wrap')].filter(element=>element.scrollWidth>element.clientWidth+1).length};
  });
  geometry.push({page:id,width,height,...measurement});
  assert(measurement.bodyWidth<=width+1,`${id}/${width} page overflow ${measurement.bodyWidth}`);
  assert(!measurement.outsideCards.length,`${id}/${width} cards outside viewport: ${JSON.stringify(measurement.outsideCards)}`);
  assert(!measurement.smallHeaders.length,`${id}/${width} unreadable headers: ${JSON.stringify(measurement.smallHeaders)}`);
  assert(!measurement.clippedControls.length,`${id}/${width} source/filter controls clipped by their card: ${JSON.stringify(measurement.clippedControls)}`);
}
try{
  browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--disable-background-networking']});
  page=await browser.newPage({viewport:{width:1440,height:1000},acceptDownloads:true});
  page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/*',route=>{
    const url=route.request().url();
    if(/^https?:/.test(url)&&url!==address){attempts.push(url);return route.abort();}
    return route.continue();
  });
  await page.goto(address);await page.waitForFunction(()=>Boolean(window.WorkbenchSavedResults));
  await page.evaluate(()=>document.fonts.ready);
  await check('PROVENANCE','Original bank-result JSON and displayed source versions are explicit',async()=>{
    const text=await page.locator('body').innerText();
    assert(text.includes('1.4.21')&&text.includes('1.4.22'),'Stored execution and current display versions must both be visible');
    assert(saved.traffic.length===89&&saved.scope.findings.length===525,'Preserved source dataset counts');
    return {dataSha256:digest(dataText),originalHtmlSha256:digest(originalSource),traffic:89,scopeFindings:525};
  });
  await check('VIEWS','All13 saved-result tools retain their complete rows under both themes',async()=>{
    assert(await page.locator('[data-nav]').count()===13,'13 navigation controls');
    for(const theme of ['light','dark']){
      await setTheme(theme);
      for(const id of pageIds){
        await navigate(id);await all(id);
        assert(await rows(id)===expected[id],`${theme}/${id}: DOM rows ${await rows(id)} != ${expected[id]}`);
        assert(await rows(id,true)===expected[id],`${theme}/${id}: all-selected visible rows changed`);
        if(groupedPages.includes(id))assert(await page.locator(`.grouped-result[data-result-owner="${id}"]`).count()===1,`${id}: grouped result missing`);
        if(['payload','data'].includes(id))assert(await page.locator('.grouped-result').count()===0,`${id}: value-oriented output was grouped as a route list`);
        await geometryCheck(id,1440,1000);await screenshot(`${theme}-${id}`);
      }
    }
    return {pages:13,themes:2};
  });
  await setTheme('light');
  await check('GROUPS','Group and leaf filters preserve all original rows and expose exact original information',async()=>{
    const results=[];
    for(const owner of groupedPages){
      await navigate(owner);await all(owner);
      const total=await rows(owner),group=page.locator(`button[data-result-owner="${owner}"][data-result-group]:not([data-result-group="all"])`).first();
      assert(await group.count()>0,owner+': no nonempty group');
      const count=Number(await group.getAttribute('data-result-count'));assert(count>0&&count<=total,owner+': group count');
      const folder=group.locator('xpath=ancestor::details[@data-result-folder][1]');
      if(await folder.count()&&!await folder.evaluate(element=>element.open))await folder.locator(':scope > summary').click();
      await group.click();assert(await rows(owner,true)===count,`${owner}: group count differs from visible rows`);
      assert(await rows(owner)===total,owner+': grouping removed original DOM rows');
      const leaf=page.locator(`button[data-result-owner="${owner}"][data-result-leaf]:visible`).first();
      assert(await leaf.count()>0,owner+': no visible leaf');
      const leafCount=Number(await leaf.getAttribute('data-result-count'));await leaf.click();
      assert(await rows(owner,true)===leafCount,owner+': leaf count differs from original observation count');
      const firstVisible=page.locator(`tr[data-row="${owner}"]:visible`).first();await firstVisible.click();
      const selectedIndex=Number(await page.locator(`tr[data-row="${owner}"].selected:visible`).getAttribute('data-index'));
      assert(Number.isInteger(selectedIndex),owner+': selected original index');
      const original=page.locator(`[data-result-original="${owner}"]`);
      assert(await original.count()===1,owner+': original disclosure missing');
      if(await original.evaluate(element=>element.tagName==='DETAILS')){
        await original.locator('summary').click();
        assert(await original.getAttribute('open')!==null,owner+': original disclosure did not open');
      }else await original.click();
      assert((await original.innerText()).trim().length>10,owner+': original metadata empty');
      const jsSource=await page.evaluate(()=>WorkbenchSavedResults.state.jsSource);
      const datasets={traffic:saved.traffic,mapper:saved.mapper.metadata.packets,auth:saved.auth,
        js:saved.js[jsSource].endpoints,environment:saved.paths,response:saved.response,
        bounty:saved.business.steps,activity:saved.activity.data.endpoints,scope:saved.scope.findings,dashboard:saved.scope.pages};
      const originalUrl=datasets[owner][selectedIndex]?.url;
      assert(typeof originalUrl==='string'&&(await original.innerText()).includes(originalUrl),owner+': selected original URL is not exact');
      const leafFolder=leaf.locator('xpath=ancestor::details[@data-result-folder][1]');
      const folderKey=await leafFolder.getAttribute('data-result-folder');
      if(await leafFolder.evaluate(element=>element.open))await leafFolder.locator(':scope > summary').click();
      await page.waitForFunction(key=>WorkbenchGroupedResults.state.expanded[key]===false,folderKey);
      await page.evaluate(()=>WorkbenchSavedResults.render());
      assert(await page.evaluate(key=>[...document.querySelectorAll('[data-result-folder]')].find(element=>element.dataset.resultFolder===key)?.open===false,folderKey),owner+': refresh reopens an explicitly folded group');
      assert(await rows(owner,true)===leafCount&&Number(await page.locator(`tr[data-row="${owner}"].selected:visible`).getAttribute('data-index'))===selectedIndex,owner+': group folding changed selected original row or filter');
      await all(owner);assert(await rows(owner,true)===total,owner+': all reset lost rows');
      results.push({page:owner,total,groupRows:count,leafRows:leafCount,selectedIndex});
    }
    return {resultGroups:results};
  });
  await check('DASHBOARD','Six real projection counts and API/environment group displays remain distinct',async()=>{
    await navigate('dashboard');
    const counts=(await page.locator('[data-metric] strong').allTextContents()).map(Number);
    assert(JSON.stringify(counts)===JSON.stringify([46,183,53,7,0,126]),'Actual dashboard metrics changed');
    for(const [metric,count] of [['api',183],['environment',53],['secret',7],['access',0],['coverage',126],['paths',46]]){
      await page.locator(`[data-metric="${metric}"]`).click();await all('dashboard');
      assert(await rows('dashboard')===count&&await rows('dashboard',true)===count,metric+': exact actual rows');
      if(metric==='api'){
        for(const group of ['assets','docs']){
          const button=page.locator(`button[data-result-owner="dashboard"][data-result-group="${group}"]`);
          assert(await button.count()===1&&Number(await button.getAttribute('data-result-count'))>0,'API candidates not split into '+group);
        }
      }
    }
    await page.locator('[data-metric="api"]').click();await screenshot('dashboard-api-grouped');
    await page.locator('[data-metric="environment"]').click();await screenshot('dashboard-environment-grouped');
  });
  await check('SCOPE','Existing category filtering composes with grouped rows and resets',async()=>{
    await navigate('scope');await page.selectOption('[data-control="category"]','API');await all('scope');
    assert(await rows('scope')===173&&await rows('scope',true)===173,'Scope API173');
    await page.fill('#search','no-such-saved-path');assert(await rows('scope',true)===0,'Scope empty search');
    await page.fill('#search','');await page.selectOption('[data-control="category"]','');await all('scope');
    assert(await rows('scope')===525&&await rows('scope',true)===525,'Scope reset525');
  });
  await check('SOURCE','JS original source counts, search reset and packet results are intact',async()=>{
    await navigate('js');const openapi=saved.js.findIndex(x=>x.url.endsWith('/static/openapi.json'));
    await page.selectOption('[data-control="jsSource"]',String(openapi));await all('js');assert(await rows('js')===42,'OpenAPI42');
    await page.selectOption('[data-control="jsSource"]','0');await all('js');assert(await rows('js')===19,'Original source19');
    await page.fill('#search','transfer');
    assert(await rows('js')===saved.js[0].endpoints.filter(x=>JSON.stringify(x).toLowerCase().includes('transfer')).length,'Source search remains original-record based');
    await navigate('traffic');await all('traffic');assert(await page.inputValue('#search')===''&&await rows('traffic')===89,'Navigation search reset');
  });
  await check('ALIAS_SEARCH','Readable Korean route names can be searched without changing original URL values',async()=>{
    await navigate('traffic');await all('traffic');await page.fill('#search','잔액');
    const count=saved.traffic.filter(row=>row.url.includes('/check_balance/')).length;
    assert(count>0&&await rows('traffic',true)===count,'Korean balance alias search differs from actual balance observations');
    await page.fill('#search','');await all('traffic');assert(await rows('traffic',true)===89,'Alias search reset');
    return {alias:'잔액',matchingOriginalRows:count};
  });
  await check('AUTH_GROUP_RESET','A search for another auth result resets a vanished group instead of hiding the whole tool',async()=>{
    await navigate('auth');await all('auth');
    const group=page.locator('button[data-result-owner="auth"][data-result-group]:not([data-result-group="all"])').first();
    const folder=group.locator('xpath=ancestor::details[@data-result-folder][1]');
    if(await folder.count()&&!await folder.evaluate(element=>element.open))await folder.locator(':scope > summary').click();
    await group.click();
    const shown=await page.locator('tr[data-row="auth"]:visible').evaluateAll(elements=>elements.map(element=>Number(element.dataset.index)));
    const candidate=saved.auth.find((row,index)=>!shown.includes(index));
    assert(candidate?.url,'Auth fixture needs distinct route groups');
    await page.fill('#search',candidate.url);
    const matching=saved.auth.filter(row=>JSON.stringify(row).toLowerCase().includes(candidate.url.toLowerCase())).length;
    assert(await rows('auth',true)===matching&&await page.locator('.grouped-result[data-result-owner="auth"]').count()===1,'A stale auth group made matching results unreachable');
    assert(await allButton('auth').getAttribute('aria-pressed')==='true','Stale auth filter was not reset');
    await page.fill('#search','');await all('auth');assert(await rows('auth',true)===3,'Auth reset lost original rows');
    return {matchingResults:matching};
  });
  await check('AUTH','Original actor comparison and protected controls retain actual responses',async()=>{
    await navigate('auth');await all('auth');
    await page.locator('tr[data-row="auth"][data-index="0"]').click();
    const headers=await page.locator('#page .split .card-head strong').allTextContents();
    assert(headers[0].includes('wb_alice')&&headers[1].includes('wb_bob'),'Actual actor captions');
    const bodies=await page.locator('#page .split .raw').allTextContents();
    assert(bodies.length===2&&bodies.every(x=>x.includes('wb_alice')&&x.includes('1005')),'Actual IDOR response');
    await page.locator('tr[data-row="auth"][data-index="1"]').click();
    assert((await page.locator('#page .split .card-head').allTextContents())[1].includes('403'),'Actual protected Bob403');
    await page.selectOption('[data-control="authActor"]','anonymous');
    assert((await page.locator('#page .split .card-head').allTextContents())[1].includes('401'),'Actual protected anonymous401');
    await page.selectOption('[data-control="authActor"]','wb_bob');
  });
  await check('VALUES','Payload, negative transfer and JWT actual values are not replaced with path captions',async()=>{
    await navigate('payload');await page.locator('tr[data-row="payload"]').filter({hasText:'SQL 조건 차이 후보'}).click();
    const payload=await page.locator('#page .split .raw').allTextContents();
    assert(payload.length===2&&/^GET /m.test(payload[0])&&payload[1].includes('Account not found'),'Actual SQL candidate request and404 response');
    await navigate('bounty');await all('bounty');
    const postIndex=saved.business.steps.findIndex(row=>row.method==='POST');
    await page.locator(`tr[data-row="bounty"][data-index="${postIndex}"]`).click();
    await page.locator('[data-tab="bounty"][data-value="request"]').click();
    assert((await page.locator('#page .raw').allTextContents()).some(value=>{try{return JSON.parse(value).amount===-5;}catch{return false;}}),'Actual negative transfer-5');
    const text=await page.locator('#page').innerText();assert(text.includes('1000 → 1005')&&text.includes('1000 → 995'),'Real balance outcomes');
    await navigate('data');await page.locator('[data-tab="data"][data-value="jwt"]').click();
    const values=await page.locator('#page .split .raw').allTextContents(),header=JSON.parse(values[0]),claims=JSON.parse(values[1]);
    assert(header.alg==='HS256'&&claims.is_admin===false&&!('exp' in claims),'Actual JWT claims');
    assert((await page.locator('#page').innerText()).includes('서명 미검증'),'JWT verification state');
    await page.locator('[data-tab="data"][data-value="compare"]').click();
  });
  await check('DOWNLOAD','Saved JSON export equals the unmodified actual result values',async()=>{
    await navigate('auth');const pending=page.waitForEvent('download');await page.click('#export-data');const download=await pending;
    assert(digest(fs.readFileSync(await download.path()))===digest(JSON.stringify(saved.auth,null,2)),'Export content differs');
    return {filename:download.suggestedFilename()};
  });
  await check('RESPONSIVE','All13 tools remain readable at3sizes under both themes',async()=>{
    for(const theme of ['light','dark']){
      await setTheme(theme);
      for(const [width,height] of [[1024,768],[390,844]]){
        await page.setViewportSize({width,height});
        await page.evaluate(hidden=>document.body.classList.toggle('nav-hidden',hidden),width<760);
        for(const id of pageIds){await navigate(id);await geometryCheck(id,width,height);}
        for(const id of ['js','bounty','dashboard']){await navigate(id);await screenshot(`${theme}-${width}-${id}`);}
      }
      await page.setViewportSize({width:900,height:768});
      await page.evaluate(()=>document.body.classList.remove('nav-hidden'));await navigate('js');
      await geometryCheck('js',900,768);await screenshot(`${theme}-900-js`);
      const openapi=saved.js.findIndex(source=>source.url.endsWith('/static/openapi.json'));
      await page.selectOption('[data-control="jsSource"]',String(openapi));await all('js');
      assert(await rows('js',true)===42,'900px source selector cannot open another actual source');
      await page.selectOption('[data-control="jsSource"]','0');await all('js');
    }
    return {viewports:[[1440,1000],[1024,768],[390,844]],additionalJsBreakpoint:[900,768],themes:2,measurements:geometry.length};
  });
  await check('NATIVE_CAPTURES','Each tool has embedded real1.4.22 screenshots in both themes and can return to saved results',async()=>{
    await page.setViewportSize({width:1440,height:1000});
    await page.evaluate(()=>document.body.classList.remove('nav-hidden'));
    await page.locator('[data-display-mode="native"]').click();
    assert(await page.locator('#search').isDisabled()&&await page.locator('#export-data').isDisabled(),'Native screenshot mode pretends to filter/export bank results');
    const captures=[];
    for(const theme of ['light','dark']){
      await setTheme(theme);
      for(const id of pageIds){
        await navigate(id);
        await page.waitForFunction(()=>{const image=document.querySelector('.capture-stage img');return image?.complete&&image.naturalWidth>0;});
        const image=await page.locator('.capture-stage img').evaluate(element=>({source:element.src.slice(0,30),width:element.naturalWidth,height:element.naturalHeight,declaredWidth:Number(element.getAttribute('width')),declaredHeight:Number(element.getAttribute('height')),alt:element.alt}));
        assert(image.source.startsWith('data:image/png;base64,')&&image.width>=300&&image.height>=300&&image.width===image.declaredWidth&&image.height===image.declaredHeight,`${theme}/${id}: real embedded screenshot dimensions differ`);
        assert((await page.locator('.capture-note').innerText()).includes('합성 데이터'),'Screenshot fixture provenance missing');
        captures.push({page:id,theme,...image});
      }
    }
    await screenshot('native-dark-exposure');
    await page.locator('[data-display-mode="results"]').click();await navigate('dashboard');
    await page.locator('[data-metric="paths"]').click();await all('dashboard');
    assert(!await page.locator('#search').isDisabled()&&!await page.locator('#export-data').isDisabled(),'Saved-result controls not restored');
    assert(await rows('dashboard',true)===46,'Saved results lost after native screenshots');
    return {embeddedCaptures:captures};
  });
  await check('IMMUTABLE','UI interactions never alter saved JSON or contact any network service',async()=>{
    assert(digest(await page.evaluate(()=>JSON.stringify(WorkbenchSavedResults.data)))===digest(JSON.stringify(saved)),'Result data mutated');
    assert(errors.length===0,'JavaScript errors: '+errors.join('; '));
    assert(attempts.length===0,'Unexpected HTTP attempts: '+attempts.join('; '));
    assert(requests.length===1&&requests[0].path==='/grouped.html','Additional HTML/asset requests');
    return {savedDataSha256:digest(dataText),bankRequests:0};
  });
}catch(error){failure=error;console.error(error.stack||error.message);if(page)await page.screenshot({path:path.join(output,'failure.png'),fullPage:false}).catch(()=>{});}
finally{
  if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));
  const result={result:failure?'failed':'passed',htmlFilename:path.basename(htmlPath),htmlSha256:digest(source),originalHtmlSha256:digest(originalSource),
    embeddedDataSha256:digest(dataText),embeddedOriginalBytesUnchanged:dataText===originalText,checks,checkCount:checks.length,
    passedChecks:checks.filter(value=>value.result==='passed').length,pageErrors:errors,additionalHttpAttempts:attempts,servedRequests:requests,
    bankRequests:0,singleHtmlWithoutAssetDirectory:true,geometry,screenshots,
    scope:'HTML presentation of preserved1.4.21 execution records using1.4.22 grouped display. No new bank diagnosis and no native Burp claim.',
    fileUrlNotExercised:'Managed Chromium file URL policy; one-file loopback server used.'};
  fs.writeFileSync(path.join(output,'browser-verification.json'),JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify({result:result.result,checks:checks.length,passed:result.passedChecks,geometry:geometry.length,errors:errors.length,extraHttp:attempts.length}));
}
if(failure)process.exitCode=1;
