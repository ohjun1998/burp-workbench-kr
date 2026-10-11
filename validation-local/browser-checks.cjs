// Actual browser checks against the owned loopback training application.
const {chromium}=require('playwright');
const fs=require('fs'),path=require('path');
const base=process.env.LAB_URL||'http://127.0.0.1:18599';
if(!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('Loopback lab required');
const out=process.env.TEST_OUTPUT;
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH,args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:1360,height:1000}}),page=await context.newPage();
 const tests=[],errors=[];let failure;
 page.on('pageerror',e=>errors.push(e.message));
 function check(test,passed){tests.push({test,passed:!!passed});console.log((passed?'PASS ':'FAIL ')+test);if(!passed)throw Error(test);}
 try{
  await page.goto(base);await page.getByRole('heading',{name:/작은 질문이/}).waitFor();
  check('community homepage renders in Chromium',true);
  await page.locator('#account').selectOption('alice');await page.locator('#login').click();await page.locator('#who').getByText('alice · USER').waitFor();
  check('real login updates active account',true);
  await page.locator('#view [data-post]').first().click();await page.locator('#commentform').waitFor();
  const payload='<img src=x onerror="document.body.dataset.loopXss=\'yes\'">';
  await page.locator('#commentform textarea').fill(payload);await page.locator('#commentform button').click();
  await page.waitForFunction(()=>document.body.dataset.loopXss==='yes');check('stored XSS actually executes in vulnerable comment view',true);
  await page.locator('#mode').selectOption('fixed');
  await page.waitForFunction(()=>document.querySelectorAll('#view .comment').length>0&&document.querySelectorAll('#view .comment img').length===0);
  check('fixed comment view displays payload text', (await page.locator('#view .comment').allTextContents()).join('\n').includes('<img src=x'));
  await page.evaluate(()=>delete document.body.dataset.loopXss);
  await page.reload();await page.locator('#who').getByText('alice · USER').waitFor();
  check('session restores after reload',true);
  await page.locator('#mode').selectOption('fixed');await page.locator('#view [data-post]').first().click();await page.locator('#commentform').waitFor();
  check('fixed view contains no injected image',await page.locator('#view .comment img').count()===0);
  check('fixed view did not execute stored payload',await page.evaluate(()=>document.body.dataset.loopXss===undefined));
  await page.locator('#account').selectOption('bob');await page.locator('#login').click();await page.locator('#who').getByText('bob · USER').waitFor();
  check('account switching updates active account',true);
  await page.goto(base);await page.locator('#view .post').first().waitFor();
  check('desktop layout avoids horizontal overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  await page.setViewportSize({width:390,height:844});
  const mobile=await page.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,overflow:[...document.querySelectorAll('body *')].map(e=>({tag:e.tagName,id:e.id,className:e.className,right:e.getBoundingClientRect().right,width:e.getBoundingClientRect().width})).filter(e=>e.right>innerWidth+1)}));
  fs.writeFileSync(path.join(out,'mobile-layout.json'),JSON.stringify(mobile,null,2));
  check('mobile layout avoids horizontal overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  await page.setViewportSize({width:1360,height:1000});await page.goto(base+'/diagnostics');await page.locator('#cases .case').last().waitFor();
  const manifest=await (await context.request.get(base+'/api/diagnostics/manifest')).json();
  check('diagnostics catalogue renders all current cases',await page.locator('#cases .case').count()===manifest.cases.length);
  check('diagnostics renders nine feature chips',await page.locator('#features .chip').count()===9);
  check('diagnostics renders sixteen payload profiles',await page.locator('#payloads .case').count()===16);
  await page.locator('#filter').selectOption({label:'트래픽/SCOPE'});check('diagnostics feature filter',await page.locator('#cases .case').count()===2);
  await page.locator('#cases button').first().click();await page.waitForFunction(()=>document.querySelector('#response').textContent.startsWith('HTTP '));
  check('diagnostics button sends actual HTTP', (await page.locator('#response').textContent()).includes('HTTP 200'));
  await page.locator('#value').fill(payload);await page.locator('#store-vuln').click();await page.waitForFunction(()=>document.querySelector('#response').textContent.includes('"stored":true'));
  await page.locator('#read-vuln').click();await page.waitForFunction(()=>document.querySelector('#response').textContent.includes('<img src=x'));
  check('diagnostics response preview treats HTML as text',await page.locator('#response img').count()===0);
  check('diagnostics controls recover after HTTP completion',await page.locator('#filter').isEnabled()&&await page.locator('#read-vuln').isEnabled());
  const rendered=await context.newPage();await rendered.goto(base+'/api/diagnostics/vuln/stored');await rendered.waitForFunction(()=>document.body.dataset.loopXss==='yes');
  check('stored HTML executes when vulnerable endpoint is rendered',true);await rendered.close();
  await page.locator('#store-fixed').click();await page.waitForFunction(()=>document.querySelector('#response').textContent.includes('"stored":true'));
  const fixed=await context.newPage();await fixed.goto(base+'/api/diagnostics/fixed/stored');
  check('fixed stored endpoint renders escaped payload',await fixed.locator('body').textContent().then(t=>t.includes(payload))&&await fixed.locator('img').count()===0);
  check('fixed stored endpoint does not execute payload',await fixed.evaluate(()=>document.body.dataset.loopXss===undefined));await fixed.close();
  await page.goto(base+'/js-lab');await page.locator('#cases button').last().waitFor();
  check('JS lab renders fourteen syntax fixtures',await page.locator('#cases button').count()===14);
  await page.locator('#collect-all').click();await page.waitForFunction(()=>document.querySelector('#status').textContent.includes('14 / 14'));
  check('JS lab fetches all syntax fixtures in actual browser',await page.locator('#cases .loaded').count()===14);
  check('no unhandled page JavaScript errors',errors.length===0);
 }catch(e){failure=String(e);throw e;}
 finally{fs.writeFileSync(path.join(out,'browser-checks.json'),JSON.stringify({browser:browser.version(),target:base,method:'actual Chromium DOM/JavaScript execution',tests,errors,passed:tests.filter(x=>x.passed).length,failed:tests.filter(x=>!x.passed).length,failure},null,2));await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
