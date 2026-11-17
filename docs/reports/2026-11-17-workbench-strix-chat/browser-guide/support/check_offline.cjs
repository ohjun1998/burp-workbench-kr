#!/usr/bin/env node
// Opens the standalone guide as an actual offline file in Chromium.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const http=require('node:http');
const {chromium}=require('playwright');
const input=path.resolve(process.argv[2]||'/workspace/burp-workbench-kr/downloads/guides/strix-1.1.0/Workbench-Strix-1.1.0-사용가이드.html');
const manifestPath=path.resolve(process.argv[3]||'/workspace/burp-workbench-kr/docs/reports/2026-11-17-workbench-strix-chat/native/guide-captures.json');
const output=path.resolve(process.argv[4]||'/workspace/burp-workbench-kr/docs/reports/2026-11-17-workbench-strix-chat/browser-guide');
const html=fs.readFileSync(input), manifest=JSON.parse(fs.readFileSync(manifestPath));
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const checks=[],errors=[],external=[],layouts=[];
let browser,server,failure;const requests=[];
const assert=(condition,message)=>{if(!condition)throw Error(message);};
const check=async(name,fn)=>{await fn();checks.push({name,result:'passed'});};
(async()=>{
  fs.mkdirSync(output,{recursive:true});
  browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--disable-background-networking']});
  server=http.createServer((req,res)=>{requests.push({method:req.method,path:req.url});if(req.url!=='/guide.html'){res.writeHead(404);res.end();return;}res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});res.end(html);});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const address='http://127.0.0.1:'+server.address().port+'/guide.html';
  const context=await browser.newContext({viewport:{width:1280,height:900},reducedMotion:'reduce'});
  const page=await context.newPage();
  page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/*',route=>{const url=route.request().url();if(/^https?:/.test(url)&&url!==address){external.push(url);return route.abort();}return route.continue();});
  await page.goto(address);
  await context.setOffline(true);
  await page.evaluate(()=>document.fonts.ready);
  await page.locator('img').evaluateAll(images=>Promise.all(images.map(img=>img.decode())));
  await check('product-version-and-current-session-guidance',async()=>{
    const title=await page.title(),text=await page.locator('body').innerText();
    assert(title.includes('Workbench Strix')&&title.includes('1.1.0'),'Wrong product/version title');
    for(const word of ['원본·테스트 정책','같은 세션','초기 턴 예산','초기화되지 않습니다','합성 API/세션','실제 사용자 Burp'])assert(text.includes(word),'Missing accurate guidance: '+word);
  });
  await check('all-capture-bytes-match-final-jar-manifest',async()=>{
    const images=await page.locator('img').evaluateAll(elements=>elements.map(e=>({src:e.src,complete:e.complete,width:e.naturalWidth,height:e.naturalHeight})));
    assert(images.length===manifest.captures.length,'Capture count differs');
    images.forEach((image,index)=>{
      const source=manifest.captures[index],original=fs.readFileSync(path.resolve(path.dirname(manifestPath),source.path));
      assert(image.src.startsWith('data:image/png;base64,')&&image.complete&&image.width>0&&image.height>0,'Capture did not decode '+source.id);
      assert(sha(Buffer.from(image.src.split(',')[1],'base64'))===source.sha256&&sha(original)===source.sha256,'Embedded PNG differs '+source.id);
    });
    assert((await page.locator('body').innerText()).includes(manifest.releaseJarSha256),'Final JAR checksum missing');
  });
  await check('actual-size-image-toggle',async()=>{
    const toggles=page.locator('input.zoom');assert(await toggles.count()===manifest.captures.length,'Image size controls missing');
    for(let i=0;i<manifest.captures.length;i++){
      await toggles.nth(i).check();
      const actual=await page.locator('figure img').nth(i).evaluate(e=>({shown:e.getBoundingClientRect().width,natural:e.naturalWidth}));
      assert(Math.abs(actual.shown-actual.natural)<1,'100% capture size differs');
      await toggles.nth(i).uncheck();
    }
  });
  await check('no-external-assets-or-scripts',async()=>{
    const resources=await page.evaluate(()=>[...document.querySelectorAll('script,link,iframe,video,audio,source')].length);
    assert(resources===0,'Unexpected runtime resource');
    assert(external.length===0,'Attempted external requests');
  });
  await check('anchors-and-download-destinations',async()=>{
    const data=await page.evaluate(()=>({ids:[...document.querySelectorAll('[id]')].map(e=>e.id),anchors:[...document.querySelectorAll('a[href^="#"]')].map(e=>e.hash.slice(1)),links:[...document.querySelectorAll('a[href]:not([href^="#"])')].map(e=>e.href)}));
    assert(new Set(data.ids).size===data.ids.length,'Duplicate anchor IDs');
    assert(data.anchors.every(id=>data.ids.includes(id)),'Missing anchor destination');
    assert(data.links.length===2&&data.links.every(u=>u.startsWith('https://github.com/ohjun1998/burp-workbench-kr/')),'Unexpected download links');
    assert(data.links.some(u=>u.endsWith('/burp-workbench-strix-1.1.0.jar'))&&data.links.some(u=>u.endsWith('/burp-workbench-strix-1.1.0-source.zip')),'Release filenames differ');
    await page.locator('.actions a[href="#flow"]').click();
    assert(await page.evaluate(()=>location.hash)==='#flow','Flow anchor failed');
  });
  for(const width of [390,768,1280])await check('responsive-offline-'+width,async()=>{
    await page.setViewportSize({width,height:900});await page.evaluate(()=>scrollTo(0,0));
    const measure=await page.evaluate(()=>({viewport:innerWidth,documentWidth:document.documentElement.scrollWidth,images:[...document.querySelectorAll('img')].map(e=>({width:e.getBoundingClientRect().width,complete:e.complete})),badLinks:[...document.querySelectorAll('.actions a')].map(e=>({text:e.textContent,box:e.getBoundingClientRect().toJSON()})).filter(e=>e.box.left<0||e.box.right>innerWidth+1)}));
    layouts.push({width,...measure});
    assert(measure.documentWidth<=width,'Document overflows '+width);
    assert(measure.badLinks.length===0,'Action link clipped '+width);
    assert(measure.images.every(i=>i.width>0&&i.complete),'Collapsed/unreadable image '+width);
    await page.screenshot({path:path.join(output,'guide-'+width+'.png'),fullPage:false});
    await page.locator('.actions a[href="#screens"]').click();assert(await page.evaluate(()=>location.hash)==='#screens','Screens anchor failed '+width);
  });
  await check('no-page-errors-or-network',async()=>{assert(errors.length===0,'Page errors '+errors.join(';'));assert(external.length===0,'Network requests '+external.join(';'));});
})().catch(e=>{failure=e;console.error(e.stack||e);}).finally(async()=>{
  if(browser)await browser.close();
  if(server)await new Promise(resolve=>server.close(resolve));
  const result={product:'Workbench Strix',version:'1.1.0',input,htmlSha256:sha(html),releaseJarSha256:manifest.releaseJarSha256,result:failure?'failed':'passed',checks,errors,externalRuntimeRequests:external,loopbackRequests:requests,offlineAfterDocumentLoad:true,offlineFile:false,layouts,nativeBurpValidation:false,syntheticConversation:true,notRun:['Actual user Burp','Actual Strix/model/target requests','OS download/save UI'],...(failure?{failure:failure.message}:{})};
  fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify({result:result.result,checks:checks.length,errors,externalRuntimeRequests:external,output},null,2));if(failure)process.exitCode=1;
});
