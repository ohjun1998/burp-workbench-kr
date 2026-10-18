import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';

const require=createRequire(process.env.PLAYWRIGHT_MODULE_ROOT?path.join(process.env.PLAYWRIGHT_MODULE_ROOT,'package.json'):import.meta.url);
const {chromium}=require('playwright');
const [base,out]=process.argv.slice(2),checks=[],screenshots=[],errors=[];
const profiles=['session','token','jwt','session-token','session-jwt','token-jwt','all'];
const check=(name,passed)=>{checks.push({name,passed:!!passed});assert.ok(passed,name);};
let browser,page;
async function ready(){await page.locator('body[data-ready="true"]').waitFor();await page.evaluate(()=>document.fonts.ready);}
async function visit(profile){await page.goto(base+'/auth-lab/'+profile);await ready();}
async function login(actor='alice',lifetime='normal'){
 await page.locator('#auth-account').selectOption(actor);await page.locator('#auth-lifetime').selectOption(lifetime);
 const done=page.waitForResponse(r=>r.url()===base+'/api/auth-lab/login'&&r.request().method()==='POST');await page.locator('#auth-login').click();const response=await done;
 await page.locator('#auth-identity[data-valid="true"]').waitFor();await page.waitForFunction(()=>!document.querySelector('#auth-login').disabled);
 check('browser login '+new URL(page.url()).pathname+' '+actor,response.status()===200);
}
async function route(name,expected){
 const done=page.waitForResponse(r=>r.url().startsWith(base+'/api/auth-lab/')&&r.request().method()==='GET');
 await page.locator('[data-auth-route="'+name+'"]').click();const response=await done;await page.waitForFunction(code=>document.querySelector('#auth-http-status').textContent==='HTTP '+code,expected);
 check('visible URL request '+new URL(page.url()).pathname+' '+name+' '+expected,response.status()===expected);return response;
}
async function capture(name){await page.screenshot({path:path.join(out,name+'.png'),fullPage:true});screenshots.push(name+'.png');}
try{
 await mkdir(out,{recursive:true});browser=await chromium.launch({headless:true,...(process.env.COMMUNITY_BROWSER_EXECUTABLE?{executablePath:process.env.COMMUNITY_BROWSER_EXECUTABLE,args:JSON.parse(process.env.COMMUNITY_BROWSER_ARGS||'[]')}:{})});
 page=await browser.newPage({viewport:{width:1440,height:1000},locale:'ko-KR',reducedMotion:'reduce'});page.on('pageerror',error=>errors.push(error.message));
 await page.goto(base+'/');await page.locator('#view[aria-busy="false"]').waitFor();check('community sidebar links to authentication lab',await page.locator('a[href="/auth-lab/session"]').count()===1);
 await page.locator('a[href="/auth-lab/session"]').click();await ready();check('all seven authentication pages in sidebar',await page.locator('[data-profile]').count()===7);
 for(const profile of profiles){
  await visit(profile);check('selected authentication page '+profile,await page.locator('[data-profile="'+profile+'"][aria-current="page"]').count()===1);
  await login();await route('member',200);await route('admin/users',403);await route('requests/901',200);await route('requests/902',404);
  if(profile==='session')await capture('auth-lab-session-desktop');
  if(profile==='all'){
   await route('me',200);await capture('auth-lab-all-desktop');
   for(const part of ['session','token','jwt']){await page.locator('#omit-'+part).check();await route('member',401);await page.locator('#omit-'+part).uncheck();await route('member',200);}
  }
  if(profile.includes('jwt')||profile==='all'){await page.locator('#tamper-jwt').click();await route('member',401);await login();}
  if(profile.includes('-')||profile==='all'){
   const mixed=page.waitForResponse(r=>r.url()===base+'/api/auth-lab/login');await page.locator('#mix-auth').click();await mixed;
   await page.waitForFunction(()=>document.querySelector('#auth-identity').textContent.includes('혼합한 상태'));await route('member',401);await login();
  }
  const loggedOut=page.waitForResponse(r=>r.url().endsWith('/logout'));await page.locator('#auth-logout').click();check('browser logout '+profile,(await loggedOut).status()===200);await page.locator('#auth-identity').filter({hasText:'로그아웃 완료'}).waitFor();await route('member',401);
 }
 await visit('all');await login();await page.locator('#auth-mode').selectOption('vuln');await route('admin/users',200);check('vulnerable control visibly marked',await page.locator('#auth-mode-note[data-vulnerable="true"]').count()===1);await capture('auth-lab-vulnerable-control');
 await page.locator('#auth-mode').selectOption('fixed');await page.locator('#auth-note').fill('BROWSER_AUTH_WRITE_CONFIRMED');
 let write=page.waitForResponse(r=>r.url().endsWith('/requests/901/note'));await page.locator('#auth-write-form button').click();check('browser owner write succeeds',(await write).status()===200);await page.waitForFunction(()=>document.querySelector('#auth-http-status').textContent==='HTTP 200');
 await login('bob');write=page.waitForResponse(r=>r.url().endsWith('/requests/901/note'));await page.locator('#auth-note').fill('BROWSER_AUTH_MUST_NOT_WRITE');await page.locator('#auth-write-form button').click();check('browser non-owner write denied',(await write).status()===404);await page.waitForFunction(()=>document.querySelector('#auth-http-status').textContent==='HTTP 404');
 await login();const observed=await route('requests/901',200);check('browser denied write preserved stored value',(await observed.json()).data.note==='BROWSER_AUTH_WRITE_CONFIRMED');
 await login('admin');await route('admin/users',200);await capture('auth-lab-admin-access');
 await visit('jwt');await login('alice','short');await page.waitForTimeout(2300);await route('me',401);check('expired browser JWT is rejected',await page.locator('#auth-identity[data-valid="false"]').count()===1);
 for(const [width,height]of [[1024,768],[390,844]]){
  await page.setViewportSize({width,height});await visit('all');await login();await route('member',200);
  check('no page horizontal overflow '+width,await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await capture('auth-lab-all-'+width);
  if(width===1024){await page.locator('.auth-login').screenshot({path:path.join(out,'auth-lab-login-panel.png')});screenshots.push('auth-lab-login-panel.png');}
  if(width===390){await page.locator('#auth-menu').click();await page.locator('[data-profile="jwt"]').click();check('mobile navigation selects authentication page',new URL(page.url()).pathname==='/auth-lab/jwt');check('mobile navigation closes',!(await page.locator('body').getAttribute('class')||'').includes('nav-open'));}
 }
 check('no browser JavaScript errors',errors.length===0);
 await writeFile(path.join(out,'ui-results.json'),JSON.stringify({result:'passed',browser:browser.version(),checks:checks.length,cases:checks,screenshots,errors},null,2)+'\n');
 console.log('AUTH_LAB_UI_CHECKS',checks.length,'passed; Chromium',browser.version());
}catch(error){
 if(page)await page.screenshot({path:path.join(out,'auth-lab-ui-failure.png'),fullPage:true}).catch(()=>{});
 await writeFile(path.join(out,'ui-results.json'),JSON.stringify({result:'failed',checks:checks.length,cases:checks,screenshots,errors,error:String(error)},null,2)+'\n');throw error;
}finally{if(browser)await browser.close();}
