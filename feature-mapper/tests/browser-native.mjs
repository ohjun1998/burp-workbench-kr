import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
const require = createRequire(import.meta.url);
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const info = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const executablePath = process.argv[3];
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const helper = path.join(root, 'browser-helper');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'mapper-browser-'));
const checks=[];
function check(ok, name) {assert(ok, name); checks.push('PASS '+name); console.log('BROWSER_PASS '+name);}
let context;
try {
  context = await chromium.launchPersistentContext(profile, {executablePath, channel:'chromium', headless:true, ignoreDefaultArgs:['--disable-extensions'],
    ...(info.proxy ? {proxy:{server:info.proxy, bypass:'http://127.0.0.1:'+new URL(info.base).port}} : {}),
    args:['--no-sandbox','--disable-dev-shm-usage', ...(info.proxy ? ['--proxy-bypass-list=<-loopback>;http://127.0.0.1:'+new URL(info.base).port] : []),'--disable-extensions-except='+helper,'--load-extension='+helper]});
  let worker=context.serviceWorkers()[0]; if(!worker)worker=await context.waitForEvent('serviceworker',{timeout:20000});
  const id=new URL(worker.url()).host;
  check(!!id,'actual Chromium loads the Manifest V3 helper');
  const popup=await context.newPage();await popup.goto('chrome-extension://'+id+'/popup.html');
  await popup.locator('#base').fill(info.base);await popup.locator('#token').fill(info.token);await popup.locator('#connect').click();
  await popup.waitForFunction(()=>document.querySelector('#status').textContent.includes('연결 완료'));
  check(true,info.proxy ? 'real helper popup pairs with the native Burp bridge' : 'real helper popup pairs with the production bridge fixture');
  await popup.waitForFunction(()=>document.querySelector('#diagnostics').textContent.includes('클릭 요청 전달 대기'));
  check(true,'helper shows pairing separately from click request delivery');
  const lab=await context.newPage();await lab.goto(info.lab+'/board/write');await lab.bringToFront();
  await popup.locator('#enable').click();
  await popup.waitForFunction(()=>document.querySelector('#status').textContent.includes('활성화 완료'));
  check(true,'site activation registers a real content script');
  await lab.reload();await lab.locator('#post').click();await lab.waitForFunction(()=>document.querySelector('#done').textContent==='등록 완료');
  check(true,info.proxy ? 'a real button performs upload, save and follow-up fetch through Burp' : 'a real button performs upload, save and follow-up fetch against the fixture server');
  const actions=await worker.evaluate(()=>chrome.storage.session.get(null));
  const action=Object.values(actions).find(x=>x&&x.label==='게시글 등록');
  check(!!action,'trusted browser click delivers its visible function label');
  await lab.locator('#profile').click();await lab.waitForFunction(()=>document.querySelector('#done').textContent==='수정 완료');
  await new Promise(resolve=>setTimeout(resolve,700));
  const state=await worker.evaluate(()=>chrome.storage.session.get(null));
  check(!state.lastError && state.deliveryCount >= 4,'browser request metadata reaches the bridge without errors');
  await popup.waitForFunction(()=>document.querySelector('#diagnostics').textContent.includes('마지막 전달'));
  check(true,'helper displays successful delivery time independently of Burp mapping');
  check(Object.values(state).some(x=>x&&x.label==='내 정보 수정'),'second screen action has its own function label');
  await lab.bringToFront();await popup.locator('#disable').click();await popup.waitForFunction(()=>document.querySelector('#status').textContent.includes('해제 완료'));
  const configured=await worker.evaluate(()=>chrome.storage.local.get('origins'));
  check(!configured.origins.includes(info.lab),'site deactivation removes the target from capture configuration');
  fs.writeFileSync(path.join(path.dirname(process.argv[2]),'browser-done.flag'),'passed\n');
  fs.writeFileSync(path.join(path.dirname(process.argv[2]),'browser-native-result.txt'),checks.join('\n')+'\nBROWSER_NATIVE_SUCCESS\n');
} catch(e) {
  fs.writeFileSync(path.join(path.dirname(process.argv[2]),'browser-native-result.txt'),checks.join('\n')+'\nFAIL '+e.stack+'\n');
  throw e;
} finally { if(context)await context.close();fs.rmSync(profile,{recursive:true,force:true}); }
