#!/usr/bin/env node
// Read-only guide integration checks. No target service or extension is loaded.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import {createRequire} from 'node:module';
import {fileURLToPath, pathToFileURL} from 'node:url';

const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const here=path.dirname(fileURLToPath(import.meta.url));
const html=path.resolve(process.argv[2]||path.join(here,'Workbench_Simple_1.4.0_Visual_Guide_20261029_KR.html'));
const out=path.resolve(process.argv[3]||'/workspace/simple-guide-conversation-20261029/browser-qa');
const executable=process.env.CHROMIUM_EXECUTABLE||[chromium.executablePath(),'/usr/bin/chromium','/usr/bin/chromium-browser'].find(p=>fs.existsSync(p));
const htmlBytes=fs.readFileSync(html);
fs.mkdirSync(out,{recursive:true});
const results={scope:'Actual Chromium reading the standalone guide in a fresh context. file:// is attempted first; a managed-policy block is recorded separately and the identical bytes are served over isolated loopback HTTP with non-loopback requests denied. No policy modification, Burp GUI, native extension, Katana, or target-site test.',htmlSha256:crypto.createHash('sha256').update(htmlBytes).digest('hex'),checks:[],screenshots:[],consoleErrors:[],pageErrors:[],externalRequests:[],viewports:[]};
let browser,server,loopbackOrigin=null,guideUrl=pathToFileURL(html).href;
async function guard(context){
  context.on('request',r=>{if(/^https?:/i.test(r.url())&&(!loopbackOrigin||new URL(r.url()).origin!==loopbackOrigin))results.externalRequests.push({url:r.url(),method:r.method()})});
  await context.route(/^https?:/,route=>loopbackOrigin&&new URL(route.request().url()).origin===loopbackOrigin?route.continue():route.abort('blockedbyclient'));
}
function check(name,condition,details={}){
  results.checks.push({name,result:condition?'passed':'failed',...details});
  if(!condition)console.error('FAIL',name,JSON.stringify(details));
}
async function screenshot(page,name,options={}){
  const file=path.join(out,name+'.png');
  await page.screenshot({path:file,...options});results.screenshots.push(file);
}
async function ready(page){
  await page.waitForLoadState('load');await page.evaluate(()=>document.fonts.ready);
}
async function viewportCheck(page,width,height){
  await page.setViewportSize({width,height});await page.evaluate(()=>scrollTo(0,0));
  const d=await page.evaluate(()=>({innerWidth,scrollWidth:document.documentElement.scrollWidth,bodyWidth:document.body.scrollWidth,brokenImages:[...document.querySelectorAll('svg image')].filter(x=>!x.getAttribute('href')?.startsWith('data:image/png;base64,')).length,zeroCaptures:[...document.querySelectorAll('figure svg')].filter(x=>{const r=x.getBoundingClientRect();return !r.width||!r.height}).length,fontReady:document.fonts.check('16px GuideFont'),cards:[...document.querySelectorAll('.step-card')].length}));
  check(`viewport-${width}-no-horizontal-overflow`,d.scrollWidth<=width+1&&d.bodyWidth<=width+1,d);
  check(`viewport-${width}-all-captures-and-font`,d.cards===33&&!d.brokenImages&&!d.zeroCaptures&&d.fontReady,d);
  results.viewports.push({width,height,...d});
}

try{
  if(!executable)throw new Error('Set CHROMIUM_EXECUTABLE to an installed Chromium executable.');
  results.browserExecutable=executable;
  browser=await chromium.launch({executablePath:executable,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
  const context=await browser.newContext({viewport:{width:1440,height:1000},offline:true,reducedMotion:'reduce'});
  const page=await context.newPage();
  page.on('console',m=>{if(m.type()==='error')results.consoleErrors.push(m.text())});
  page.on('pageerror',e=>results.pageErrors.push(e.message));
  await guard(context);
  try{await page.goto(guideUrl);results.fileProtocol={result:'passed'};}
  catch(error){
    if(!String(error.message).includes('ERR_BLOCKED_BY_ADMINISTRATOR'))throw error;
    results.fileProtocol={result:'blocked',reason:'ERR_BLOCKED_BY_ADMINISTRATOR',policyChanged:false};
    server=http.createServer((req,res)=>{if(req.url==='/favicon.ico'){res.writeHead(204);res.end();return}if(req.url!=='/guide.html'){res.writeHead(404);res.end();return}res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});res.end(htmlBytes)});
    await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve)});
    loopbackOrigin='http://127.0.0.1:'+server.address().port;guideUrl=loopbackOrigin+'/guide.html';results.transport='same-byte-loopback-http-external-requests-denied';
    await context.setOffline(false);results.consoleErrors=[];results.pageErrors=[];await page.goto(guideUrl);
  }
  await ready(page);
  const initial=await page.evaluate(()=>({cards:document.querySelectorAll('.step-card').length,markers:document.querySelectorAll('.step-card figure svg circle').length,actionRows:document.querySelectorAll('.step-card .actions li').length,nav:document.querySelectorAll('[data-step-nav]').length,lang:document.documentElement.lang,ids:[...document.querySelectorAll('[id]')].map(x=>x.id),unlabelledButtons:[...document.querySelectorAll('button')].filter(x=>!(x.getAttribute('aria-label')||x.textContent.trim())).length,unlabelledInputs:[...document.querySelectorAll('input')].filter(x=>!x.labels?.length&&!x.getAttribute('aria-label')).length}));
  check('33-screen-steps-and-nav',initial.cards===33&&initial.nav===33,initial);
  check('95-markers-match-95-action-rows',initial.markers===95&&initial.actionRows===95,{markers:initial.markers,actions:initial.actionRows});
  check('korean-language-and-unique-ids',initial.lang==='ko'&&new Set(initial.ids).size===initial.ids.length,{lang:initial.lang,idCount:initial.ids.length});
  check('buttons-and-inputs-have-accessible-labels',!initial.unlabelledButtons&&!initial.unlabelledInputs,{buttons:initial.unlabelledButtons,inputs:initial.unlabelledInputs});
  const decoded=await page.evaluate(async()=>Promise.all([...document.querySelectorAll('.step-card figure svg')].map(svg=>new Promise(resolve=>{const image=new Image(),source=svg.querySelector('image'),view=svg.viewBox.baseVal;image.onload=()=>resolve({source:source.getAttribute('href').slice(0,30),valid:image.naturalWidth===view.width&&image.naturalHeight===view.height});image.onerror=()=>resolve({valid:false});image.src=source.getAttribute('href')}))));
  check('all-33-embedded-screenshots-decode-at-source-size',decoded.length===33&&decoded.every(x=>x.valid),{count:decoded.length,invalid:decoded.filter(x=>!x.valid)});
  await viewportCheck(page,1440,1000);await screenshot(page,'desktop-overview');

  await page.locator('#search').fill('Katana');
  const filtered=await page.evaluate(()=>({visible:[...document.querySelectorAll('.step-card')].filter(x=>!x.hidden).map(x=>x.id),nav:[...document.querySelectorAll('[data-step-nav]')].filter(x=>!x.hidden).map(x=>x.dataset.stepNav)}));
  check('search-filters-body-and-navigation',filtered.visible.length>0&&filtered.visible.length<33&&JSON.stringify(filtered.visible)===JSON.stringify(filtered.nav),filtered);
  const first=filtered.visible[0],second=filtered.visible[1];
  if(second){await page.locator(`#${first} [data-direction="next"]`).click();check('filtered-next-follows-visible-step',new URL(page.url()).hash===`#${second}`,{hash:new URL(page.url()).hash,expected:second});await page.locator(`#${second} [data-direction="previous"]`).click();check('filtered-previous-follows-visible-step',new URL(page.url()).hash===`#${first}`,{hash:new URL(page.url()).hash,expected:first});}
  const last=filtered.visible.at(-1);await page.evaluate(id=>location.hash=id,last);
  await page.locator(`#${last} [data-direction="next"]`).click();check('filtered-last-next-does-not-enter-hidden-step',[`#${last}`,'#troubleshooting'].includes(new URL(page.url()).hash),{hash:new URL(page.url()).hash,allowed:[last,'troubleshooting']});
  await page.evaluate(id=>location.hash=id,first);await page.locator(`#${first} [data-direction="previous"]`).click();check('filtered-first-previous-does-not-enter-hidden-step',[`#${first}`,'#overview'].includes(new URL(page.url()).hash),{hash:new URL(page.url()).hash,allowed:[first,'overview']});
  await page.locator('#search').fill('no-such-guide-step-9da2b6');
  check('zero-results-state-visible',await page.locator('#empty').isVisible());
  await page.locator('#reset-search').click();
  check('reset-search-restores-all-steps',await page.locator('.step-card:visible').count()===33&&await page.locator('#search').inputValue()==='');

  await page.locator('#theme').click();
  check('dark-mode-applied',await page.locator('html').getAttribute('data-theme')==='dark'&&await page.locator('#theme').getAttribute('aria-pressed')==='true');
  await page.reload();await ready(page);
  check('dark-mode-persists-after-reload',await page.locator('html').getAttribute('data-theme')==='dark');
  await page.locator('[data-done="step-01"]').check();
  check('checklist-progress-and-limits',await page.locator('#progress').textContent()==='1 / 33단계 확인'&&(await page.locator('#overview').textContent()).includes('실제 점검 완료 판정이 아닙니다'));
  await page.reload();await ready(page);
  check('checklist-persists-after-reload',await page.locator('[data-done="step-01"]').isChecked()&&await page.locator('#progress').textContent()==='1 / 33단계 확인');
  await page.locator('#step-13').scrollIntoViewIfNeeded();await screenshot(page,'desktop-dark-filter-step');

  const zoomButton=page.locator('#step-01 .figure-tools button');
  await zoomButton.click();await page.locator('#zoom-dialog').waitFor({state:'visible'});
  let z=await page.evaluate(()=>{const svg=document.querySelector('#zoom-content svg'),r=svg.getBoundingClientRect(),v=svg.viewBox.baseVal,ids=[...document.querySelectorAll('[id]')].map(x=>x.id);return {width:r.width,height:r.height,ratio:r.width/r.height,sourceRatio:v.width/v.height,focus:document.activeElement.id,uniqueIds:new Set(ids).size===ids.length}});
  check('zoom-modal-source-ratio-and-focus',Math.abs(z.ratio-z.sourceRatio)<.01&&z.focus==='zoom-close'&&z.uniqueIds,z);
  const widthBefore=z.width;await page.locator('#zoom-plus').click();
  z=await page.locator('#zoom-content svg').boundingBox();check('zoom-plus-increases-size',z.width>widthBefore,{before:widthBefore,after:z.width});
  await page.locator('#zoom-reset').click();z=await page.locator('#zoom-content svg').boundingBox();check('zoom-reset-restores-native-width',Math.abs(z.width-widthBefore)<1,{before:widthBefore,after:z.width});
  await screenshot(page,'desktop-zoom-modal');
  await page.keyboard.press('Escape');
  check('escape-closes-and-restores-focus',!(await page.locator('#zoom-dialog').isVisible())&&await zoomButton.evaluate(el=>document.activeElement===el)&&!(await page.locator('body').getAttribute('class')||'').includes('modal-open'));

  await page.locator('#theme').click();
  await viewportCheck(page,768,1024);await screenshot(page,'tablet-overview');
  await viewportCheck(page,390,844);await screenshot(page,'mobile-overview');
  await page.locator('#step-16').scrollIntoViewIfNeeded();await screenshot(page,'mobile-recheck-step');
  await page.locator('#step-16 .zoom-detail summary').click();
  check('important-region-expands',await page.locator('#step-16 .zoom-detail').getAttribute('open')!==null);
  const mobileAfter=await page.evaluate(()=>({scrollWidth:document.documentElement.scrollWidth,innerWidth}));
  check('mobile-detail-has-no-document-overflow',mobileAfter.scrollWidth<=mobileAfter.innerWidth+1,mobileAfter);

  const targets=await page.evaluate(()=>[...document.querySelectorAll('a[href^="#"]')].map(a=>a.getAttribute('href').slice(1)).filter(id=>id&&!document.getElementById(id)));
  check('all-internal-links-resolve',targets.length===0,{missing:targets});
  const plain=await browser.newContext({viewport:{width:1280,height:800},javaScriptEnabled:false,offline:!loopbackOrigin});await guard(plain);
  const nojs=await plain.newPage();await nojs.goto(guideUrl);
  check('javascript-disabled-body-still-readable',await nojs.locator('.step-card:visible').count()===33&&await nojs.locator('.actions li').count()===95&&(await nojs.locator('#report').textContent()).includes('rawText'));
  await plain.close();
  check('no-external-network-attempts',results.externalRequests.length===0,{count:results.externalRequests.length});
  check('no-console-or-script-errors',results.consoleErrors.length===0&&results.pageErrors.length===0,{console:results.consoleErrors,page:results.pageErrors});
  check('checked-html-unchanged-during-run',crypto.createHash('sha256').update(fs.readFileSync(html)).digest('hex')===results.htmlSha256);
  await context.close();
}catch(error){results.fatal={name:error.name,message:error.message,stack:error.stack};console.error(error)}
finally{
  if(browser)await browser.close();
  if(server)await new Promise(resolve=>server.close(resolve));
  results.passed=results.checks.filter(x=>x.result==='passed').length;results.failed=results.checks.filter(x=>x.result==='failed').length;
  results.result=results.failed||results.fatal?'failed':'passed';
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2)+'\n');
  console.log(JSON.stringify({result:results.result,passed:results.passed,failed:results.failed,fatal:results.fatal?.message,output:path.join(out,'results.json')}));
  process.exitCode=results.result==='passed'?0:1;
}
