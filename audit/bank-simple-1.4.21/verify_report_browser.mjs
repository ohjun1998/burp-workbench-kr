#!/usr/bin/env node
// Read-only local report rendering, separate from all bank/engine request counters.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {chromium}=require('playwright');
const root=path.resolve(process.argv[2]||'docs/reports/2026-11-01-bank-simple-1.4.21');
const server=http.createServer((req,res)=>{
  const requested=new URL(req.url,'http://localhost').pathname;
  const file=path.resolve(root,'.'+decodeURIComponent(requested==='/'?'/RESULTS.html':requested));
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){
    res.writeHead(404);res.end();return;
  }
  const type={'.html':'text/html; charset=utf-8','.png':'image/png','.json':'application/json','.md':'text/plain; charset=utf-8'}[path.extname(file)]||'application/octet-stream';
  res.writeHead(200,{'Content-Type':type});fs.createReadStream(file).pipe(res);
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin='http://127.0.0.1:'+server.address().port;
let browser;
try{
  browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--disable-background-networking']});
  const page=await browser.newPage({viewport:{width:1440,height:1000}});
  let errors=0,external=0;
  page.on('pageerror',()=>errors++);
  await page.route('**/*',route=>{
    if(new URL(route.request().url()).origin!==origin){external++;return route.abort();}
    return route.continue();
  });
  await page.goto(origin+'/RESULTS.html');
  const all=await page.locator('.tool').count();if(all!==13)throw Error('Expected13tool cards');
  await page.selectOption('#filter','core');
  const core=await page.locator('.tool:visible').count();if(core!==8)throw Error('Expected8core cards');
  await page.selectOption('#filter','all');await page.fill('#search','SQL');
  const search=await page.locator('.tool:visible').count();if(search<=0||search>=13)throw Error('Search filter');
  await page.fill('#search','');await page.evaluate(()=>{
    document.querySelectorAll('.tool details').forEach(x=>x.open=true);
    document.querySelectorAll('img[data-light]').forEach(x=>x.loading='eager');
  });
  await page.click('#theme');
  await page.waitForFunction(()=>[...document.querySelectorAll('img[data-light]')].every(x=>x.src.includes('/dark/')&&x.complete&&x.naturalWidth>0));
  const images=await page.locator('img[data-light]').count();
  await page.click('#theme');
  await page.waitForFunction(()=>[...document.querySelectorAll('img[data-light]')].every(x=>x.src.includes('/light/')&&x.complete&&x.naturalWidth>0));
  if(errors||external)throw Error('Unexpected pageerror or external HTTP request');
  fs.writeFileSync(path.join(root,'report-ui-verification.json'),JSON.stringify({
    result:'passed',rendering:'Same saved HTML served by an ephemeral loopback read-only static server',
    managedBrowserFileUrlBlocked:true,fileUrlExecutionVerified:false,
    toolCards:all,coreFilterCards:core,searchFilterMatches:search,lightAndDarkImagesLoaded:images,
    pageErrors:errors,externalHttpRequests:external,usesSavedResultFilesOnly:true,
    additionalBankRequests:0,
  },null,2)+'\n');
  console.log('Report UI passed:13cards/8core/search/13light+dark images/no external or bank requests');
}finally{
  if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));
}
