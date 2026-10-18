import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
const require=createRequire(path.join(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES,'package.json'));
const {chromium}=require('playwright');
const [statePath,out]=process.argv.slice(2);const state=JSON.parse(fs.readFileSync(statePath,'utf8'));const base=state.base;
const browser=await chromium.launch({executablePath:process.env.WB_CHROMIUM,headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--proxy-bypass-list=<-loopback>','--disable-background-networking'],proxy:{server:'http://127.0.0.1:24541'}});
const context=await browser.newContext({viewport:{width:1440,height:980},ignoreHTTPSErrors:true});
await context.route('**/*',route=>{const u=new URL(route.request().url());return u.origin===base?route.continue():route.abort();});
const page=await context.newPage();const responses=[];const checks=[];const errors=[];let domMarker=false;
page.on('console',m=>{if(m.text().includes('WB_BROWSER_20261018'))domMarker=true;});
page.on('pageerror',e=>errors.push(e.message.slice(0,200)));
page.on('response',r=>{const u=new URL(r.url());if(u.origin===base)responses.push({method:r.request().method(),path:u.pathname,status:r.status()});});
async function check(id,title,fn){try{const detail=await fn();checks.push({id,title,status:'PASS',detail:detail??''});}catch(e){checks.push({id,title,status:'FAIL',reason:String(e).slice(0,700)});}}
try{
 await check('BR-01','상품 목록 화면 로드',async()=>{await page.goto(base+'/#/search',{waitUntil:'networkidle',timeout:60000});for(const label of ['Dismiss','Me want it!']){const button=page.getByRole('button',{name:label,exact:true});if(await button.count())await button.click({timeout:3000}).catch(()=>{});}await page.getByText('Apple Juice (1000ml)',{exact:true}).first().waitFor({timeout:15000});return 'Real Chromium DOM; public products rendered';});
 await check('BR-02','Alice 로그인 화면으로 로그인',async()=>{await page.goto(base+'/#/login',{waitUntil:'networkidle'});await page.locator('#email').fill(state.actors[1].email);await page.locator('#password').fill(state.password);await page.locator('#loginButton').click();await page.waitForFunction(()=>Boolean(localStorage.getItem('token')),{timeout:15000});const valid=await page.evaluate(()=>localStorage.getItem('token')?.split('.').length===3);if(!valid)throw new Error('Login token not present');return 'Login submitted by browser UI; token excluded from evidence';});
 await check('BR-03','검색 결과 화면과 실제 API 호출',async()=>{await page.goto(base+'/#/search?q=apple',{waitUntil:'networkidle'});await page.getByText('Apple Juice (1000ml)',{exact:true}).first().waitFor({timeout:15000});if(!responses.some(r=>r.path==='/rest/products/search'&&r.status===200))throw new Error('No search API response');});
 await check('BR-04','본인 장바구니 화면 표시',async()=>{await page.goto(base+'/#/basket',{waitUntil:'networkidle'});await page.getByText('Apple Juice (1000ml)',{exact:true}).first().waitFor({timeout:15000});if(!responses.some(r=>r.path==='/rest/basket/'+state.actors[1].bid&&r.status===200))throw new Error('Own basket API not seen');});
 await check('BR-05','DOM XSS 브라우저 실행 대조',async()=>{const payload='<iframe src="javascript:console.log(`WB_BROWSER_20261018`)">';await page.goto(base+'/#/search?q='+encodeURIComponent(payload),{waitUntil:'networkidle'});await page.waitForTimeout(1800);if(!domMarker)throw new Error('DOM marker not executed with selected non-destructive probe');return 'Only a local console marker; no network or data exfiltration';});
}finally{
 const result={engine:'real Chromium via private Burp proxy',version:browser.version(),checks,requests:responses.length,responses,scriptErrors:errors,domXssConsoleMarkerExecuted:domMarker,credentialsIncluded:false};fs.writeFileSync(path.join(out,'browser-results.json'),JSON.stringify(result,null,2));await browser.close();
}
// DOM exploratory check does not block the remaining native feature tests.
const core=checks.filter(x=>x.id!=='BR-05');if(core.some(x=>x.status==='FAIL'))process.exitCode=1;
