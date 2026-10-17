import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';

const require=createRequire(process.env.PLAYWRIGHT_MODULE_ROOT?path.join(process.env.PLAYWRIGHT_MODULE_ROOT,'package.json'):import.meta.url);
const {chromium}=require('playwright');
const [base,out]=process.argv.slice(2),checks=[],screenshots=[],errors=[],contexts=[];
let browser,current;
const check=(name,passed)=>{checks.push({name,passed:!!passed});assert.ok(passed,name);};
async function ready(page){await page.locator('#view[aria-busy="false"]').waitFor();await page.evaluate(()=>document.fonts.ready);}
async function visit(page,view='feed'){await page.goto(base+'/?view='+encodeURIComponent(view));await ready(page);}
async function capture(page,name){const file=name+'.png';await page.screenshot({path:path.join(out,file),fullPage:true});screenshots.push(file);}
async function identity(name,type='USER'){
 const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'ko-KR',reducedMotion:'reduce'});contexts.push(context);
 const page=await context.newPage();current=page;page.on('pageerror',error=>errors.push(error.message));
 await page.goto(base+'/login?type='+(type==='ADMIN'?'admin':'user'));
 await page.locator('#account').fill(name);await page.locator('#login').click();await page.waitForURL(base+'/?view=*');await ready(page);
 check('actual login role menu '+name,(await page.locator('#admin-menu').isVisible())===(type==='ADMIN'));
 return page;
}
try{
 await mkdir(out,{recursive:true});browser=await chromium.launch({headless:true});
 const guestContext=await browser.newContext({viewport:{width:1440,height:1000},locale:'ko-KR',reducedMotion:'reduce'});contexts.push(guestContext);
 const guest=await guestContext.newPage();current=guest;guest.on('pageerror',error=>errors.push(error.message));
 await guest.goto(base+'/login');await guest.evaluate(()=>document.fonts.ready);await capture(guest,'login-desktop');
 await guest.locator('#password-toggle').click();check('password visibility control',await guest.locator('#password').getAttribute('type')==='text');
 await guest.locator('#password-toggle').click();
 await guest.locator('[data-demo-account="admin"]').click();await guest.locator('#account').fill('alice');await guest.locator('#login').click();
 await guest.locator('#login-error:not([hidden])').waitFor();check('wrong selected role stays on login',new URL(guest.url()).pathname==='/login');
 await guest.locator('[data-demo-account="alice"]').click();await guest.locator('#login').click();await guest.waitForURL(base+'/?view=feed');await ready(guest);
 const alice=guest;check('general login has no administration menu',!(await alice.locator('#admin-menu').isVisible()));await capture(alice,'community-desktop');
 await alice.locator('#global-search').fill('처음 오신');await alice.locator('#global-searchform button').click();await ready(alice);
 check('header search returns real matching posts',await alice.locator('#post-list .post').count()===1);
 await alice.locator('[data-clear-search]').click();await ready(alice);
 await alice.locator('.feed-filters [data-category="security"]').click();await ready(alice);
 check('category filter only renders security posts',(await alice.locator('#post-list .tag').allTextContents()).every(t=>t==='보안 게시판'));
 await visit(alice,'write');const marker='UI_PRIVATE_POST_380';
 await alice.locator('#postform [name="title"]').fill(marker);await alice.locator('#postform [name="body"]').fill('실제 브라우저에서 작성한 비공개 게시글입니다.');
 await alice.locator('#postform [name="visibility"]').selectOption('PRIVATE');await alice.locator('#postform button').click();
 await alice.locator('#view h2').filter({hasText:marker}).waitFor();await ready(alice);
 const privateView=new URL(alice.url()).searchParams.get('view');check('author sees server-authorized edit',await alice.locator('[data-edit]').isVisible());
 await alice.locator('#commentform textarea').fill('BROWSER_COMMENT_380');await alice.locator('#commentform button').click();await ready(alice);
 check('browser comment workflow',await alice.locator('.comment .body').filter({hasText:'BROWSER_COMMENT_380'}).isVisible());
 const bob=await identity('bob');await visit(bob,privateView);check('other member cannot read private post',!(await bob.locator('#view').textContent()).includes(marker));
 await visit(bob,'users');check('forced management navigation is rejected',(await bob.locator('#view').textContent()).includes('권한이 없습니다'));
 const denied=await bob.request.get(base+'/api/workspace/fixed/admin/users?secret=DO_NOT_LOG_UI_380');check('direct API remains protected',denied.status()===403);
 const admin=await identity('admin','ADMIN');await capture(admin,'admin-users-desktop');
 await visit(admin,'permissions');check('role policy matrix renders',await admin.locator('.messages tbody tr').count()>=15);await capture(admin,'admin-permissions-desktop');
 await visit(admin,'audit');check('access audit shows denial',(await admin.locator('.messages').textContent()).includes('거부'));
 check('access audit does not expose query values',!(await admin.locator('.messages').textContent()).includes('DO_NOT_LOG_UI_380'));await capture(admin,'admin-audit-desktop');
 await visit(admin,privateView);check('authorized manager can read private post',(await admin.locator('#view').textContent()).includes(marker));
 await visit(admin,'course-admin');await admin.locator('#courseform [name="title"]').fill('BROWSER_COURSE_380');await admin.locator('#courseform [name="description"]').fill('브라우저 수강 신청·승인 흐름');
 await admin.locator('#courseform button').click();await ready(admin);
 const courses=await (await admin.request.get(base+'/api/workspace/fixed/courses')).json();const course=courses.find(c=>c.TITLE==='BROWSER_COURSE_380');check('manager creates course through UI',!!course);
 await visit(alice,'course:'+course.ID);await alice.locator('#applyform textarea').fill('BROWSER_APPLICATION_380');await alice.locator('#applyform button').click();await ready(alice);
 check('member course application appears in activity',(await alice.locator('#view').textContent()).includes('BROWSER_APPLICATION_380'));
 await visit(admin,'course-admin');const application=admin.locator('.messages tbody tr').filter({hasText:'BROWSER_APPLICATION_380'});
 await application.locator('[data-decision="APPROVED"]').click();await ready(admin);check('manager approval updates state',(await admin.locator('.messages tbody tr').filter({hasText:'BROWSER_APPLICATION_380'}).textContent()).includes('수락'));
 await visit(alice,'activity');check('member sees approved course',(await alice.locator('.messages tbody tr').filter({hasText:'BROWSER_APPLICATION_380'}).textContent()).includes('수락'));
 const moderator=await identity('moderator');check('moderator has scoped navigation',await moderator.locator('#moderator-menu').isVisible());
 await visit(moderator,'moderation');check('moderation list excludes private content',(await moderator.locator('.post .tag').allTextContents()).every(t=>t==='보안 게시판'));await capture(moderator,'moderation-desktop');
 await visit(moderator,'post:105');check('assigned board exposes allowed edit',await moderator.locator('[data-edit]').isVisible());
 await visit(moderator,'post:101');check('other board does not expose edit',await moderator.locator('[data-edit]').count()===0);
 for(const [name,page,view] of [['community',alice,'feed'],['admin-users',admin,'users'],['permissions',admin,'permissions'],['moderation',moderator,'moderation']]){
  for(const [width,height] of [[1024,768],[390,844]]){
   current=page;await page.setViewportSize({width,height});await visit(page,view);
   check('no page overflow '+name+'/'+width,await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
   check('mobile navigation starts closed '+name+'/'+width,await page.locator('#menu-toggle').getAttribute('aria-expanded')==='false');
   await page.locator('#menu-toggle').click();check('mobile drawer opens '+name+'/'+width,await page.locator('#nav-backdrop').isVisible());
   await page.keyboard.press('Escape');check('escape closes mobile drawer '+name+'/'+width,!(await page.locator('#nav-backdrop').isVisible()));
   await capture(page,name+'-'+width);
  }
 }
 await admin.setViewportSize({width:1440,height:1000});await visit(admin,'users');await admin.locator('#menu-toggle').click();
 check('desktop navigation can collapse',await admin.locator('body').evaluate(e=>e.classList.contains('nav-hidden')));await admin.locator('#menu-toggle').click();
 await alice.setViewportSize({width:390,height:844});await visit(alice,'feed');await alice.locator('#menu-toggle').click();
 await alice.locator('#main-navigation [data-page="courses"]').click();await ready(alice);check('mobile navigation selects page and closes',!(await alice.locator('#nav-backdrop').isVisible())&&(await alice.locator('#current-title').textContent())==='강의 둘러보기');
 await alice.locator('#logout').click();await alice.waitForURL(base+'/login');await alice.evaluate(()=>document.fonts.ready);await capture(alice,'login-mobile');
 check('logout returns to standalone login',new URL(alice.url()).pathname==='/login');
 check('login fits mobile viewport',await alice.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
 check('no browser JavaScript exceptions',errors.length===0);
}catch(error){
 checks.push({name:'Browser flow completed',passed:false,error:String(error.stack||error)});
 if(current)await current.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});
 process.exitCode=1;console.error(error);
}finally{
 await writeFile(path.join(out,'results.json'),JSON.stringify({siteVersion:'3.8.0',passed:checks.filter(c=>c.passed).length,failed:checks.filter(c=>!c.passed).length,method:'Actual isolated Chromium against current compiled Spring MVC/Tomcat sources and fresh H2 database',checks,screenshots,browserErrors:errors,nativeBurp:false},null,2)+'\n');
 for(const context of contexts)await context.close();if(browser)await browser.close();
 console.log('Community Chromium checks:',checks.filter(c=>c.passed).length,'passed;',checks.filter(c=>!c.passed).length,'failed;',screenshots.length,'screenshots');
}
