import {inspectionCommand, inspectionAllows, inspectionMetadata, inspectionRules, installInspectionGuard, inspectDocument, inspectionOrigin} from './inspection.js';

/** A dedicated, guarded browser tab. Target cookies remain inside the browser profile. */
export function createInspectionService(chrome, hooks) {
  const storageKey='scopeInspectionTaskV1', firstRule=870000, lastRule=870999;
  let task=null, loop=null, polling=false, finishing=false, stopped=false;
  const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const settings=async()=>await hooks.settings();
  const persist=async()=>chrome.storage.session.set({[storageKey]:task});
  const emit=async(kind,value={})=>{
    if(!task)return;
    const owner=task;
    const event=await inspectionMetadata({...value,kind,pages:owner.pages,actions:owner.actions,requests:owner.requests,
      excluded:owner.excluded,tab:String(owner.tabId??''),state:value.state||'running'},owner.command);
    await hooks.event(event);
    if(task!==owner)return;
    await chrome.storage.session.set({inspectionStatus:kind==='error'?'탐색 오류':kind==='done'?(owner.excluded?'일부 탐색 · 범위 밖 리소스 제외':'탐색 완료'):'브라우저 로그인 세션으로 탐색 중',inspectionPages:owner.pages,inspectionActions:owner.actions,
      inspectionRequests:owner.requests,inspectionExcluded:owner.excluded,inspectionRunId:owner.command.runId});
  };
  const ownedRules=async()=>((await chrome.declarativeNetRequest.getSessionRules())||[]).filter(rule=>rule.id>=firstRule&&rule.id<=lastRule).map(rule=>rule.id);
  const removeRules=async()=>chrome.declarativeNetRequest.updateSessionRules({removeRuleIds:await ownedRules(),addRules:[]});
  async function finish(state,message,kind='done') {
    if(!task||finishing)return;finishing=true;
    const previous=task;previous.cancelled=true;
    try {
      // Keep transport guards until the dedicated tab has closed, avoiding a cleanup race.
      if(Number.isInteger(previous.tabId))try{await chrome.tabs.remove(previous.tabId);}catch{}
      await removeRules();
      await emit(kind,{url:previous.current||previous.command.startUrl,page:previous.current||previous.command.startUrl,state,message});
    } finally {
      task=null;await persist();finishing=false;
    }
  }
  async function validTask() {
    if(!task||task.cancelled||stopped)return false;
    const saved=await settings();
    if(!saved.inspectionEnabled||!saved.origins?.includes(task.command.origin)) {await finish('stopped','SPA 통합 탐색 허용 또는 사이트 활성화 해제');return false;}
    if(Date.now()>=task.command.expiresAt){await finish('partial','브라우저 시간 한도 · 남은 메뉴는 미탐색');return false;}
    return true;
  }
  async function waitLoaded(tabId, timeout=12000) {
    const end=Date.now()+timeout;
    while(Date.now()<end&&await validTask()){
      const tab=await chrome.tabs.get(tabId);
      if(tab.status==='complete')return tab;
      await delay(100);
    }
    throw new Error('페이지 로드 시간 한도');
  }
  async function script(func,args,world='ISOLATED') {
    const result=await chrome.scripting.executeScript({target:{tabId:task.tabId,frameIds:[0]},world,func,args});
    return result?.[0]?.result;
  }
  async function run() {
    if(loop)return loop;
    loop=(async()=>{
      try {
        while(await validTask()) {
          const current=task;
          if(!current.queue.length||current.pages>=current.command.maxPages||current.actions>=current.command.maxActions){
            const limited=current.queue.length>0;
            await finish(limited?'partial':'completed',limited?'브라우저 페이지/동작 한도 · 남은 메뉴는 미탐색':'발견한 메뉴 큐 처리 완료');break;
          }
          const next=current.queue.shift();current.current=next.url;await persist();
          if(!inspectionAllows(next.url,current.command)){current.excluded++;await emit('excluded',{url:next.url,label:next.label,message:'Scope 또는 변경 경로 제외'});continue;}
          if(current.visited.includes(next.url))continue;
          await chrome.tabs.update(current.tabId,{url:next.url,active:false});
          const tab=await waitLoaded(current.tabId);
          if(task!==current||current.cancelled)break;
          if(!inspectionAllows(tab.url,current.command)){current.excluded++;await emit('excluded',{url:tab.url,message:'이동 결과가 Scope 밖이거나 브라우저 차단 화면'});continue;}
          await script(installInspectionGuard,[current.command],'MAIN');
          await delay(300);
          let page=await script(inspectDocument,[current.command,false]);
          if(!page||!inspectionAllows(page.url,current.command)){current.excluded++;await emit('excluded',{url:next.url,message:'문서 또는 실제 이동 Scope 확인 실패'});continue;}
          if(page.login){await finish('partial','로그인 화면 관측 · 브라우저 세션 확인 필요');break;}
          current.pages++;current.visited.push(next.url);
          await emit('route',{url:page.url,page:page.url,label:next.label||'화면',message:'브라우저 세션으로 방문한 메뉴 · HTTP 재조회 계정과 별도'});
          for(let step=0;step<12&&current.actions<current.command.maxActions&&await validTask();step++) {
            page=await script(inspectDocument,[current.command,true]);
            if(task!==current||current.cancelled)break;
            if(!page||!inspectionAllows(page.url,current.command))break;
            if(page.login){await finish('partial','로그인 화면 관측 · 브라우저 세션 확인 필요');break;}
            if(page.url!==current.current){
              if(!current.visited.includes(page.url)){if(current.pages>=current.command.maxPages){await finish('partial','브라우저 페이지 한도 · 남은 메뉴는 미탐색');break;}current.pages++;current.visited.push(page.url);}
              current.current=page.url;await emit('route',{url:page.url,page:page.url,label:page.action||'SPA 메뉴 이동',message:'메뉴 동작 후 실제 라우트 관측'});
            }
            current.excluded+=Math.max(0,Number(page.excluded)||0);
            for(const route of page.routes||[]) {
              if(!inspectionAllows(route.url,current.command)){current.excluded++;continue;}
              if(current.discovered.includes(route.url))continue;
              if(current.discovered.length>=500){current.excluded++;continue;}
              current.discovered.push(route.url);
              await emit('menu',{url:route.url,page:page.url,label:route.label,message:'실제 DOM 메뉴에서 발견 · 메뉴 접근 권한/업무 완료는 별도 확인'});
              // Unknown query values are kept inside the browser; do not replay guessed parameters.
              if(new URL(route.url).search){current.excluded++;continue;}
              if(!current.visited.includes(route.url)&&!current.queue.some(x=>x.url===route.url)&&current.queue.length<100)current.queue.push(route);
            }
            if(!page.action)break;
            current.actions++;
            await emit('progress',{url:page.url,page:page.url,label:page.action,message:'메뉴 확장·지연 로딩 확인'});
            await persist();await delay(350);
          }
          await persist();
        }
      } catch(error) {if(task&&!task.cancelled)await finish('failed',String(error.message||error).slice(0,200),'error');}
      finally{loop=null;}
    })();
    return loop;
  }
  async function launch(value) {
    const command=inspectionCommand(value);
    if(task&&task.command.runId===command.runId)return;
    if(task)await finish('stopped','새 통합 점검으로 전환');
    if(loop)await loop;
    const saved=await settings();
    if(!saved.inspectionEnabled||!saved.origins.includes(command.origin))return;
    const match=new URL(command.origin).protocol+'//'+new URL(command.origin).hostname+'/*';
    task={command,tabId:null,current:command.startUrl,queue:[{url:command.startUrl,label:'시작 화면'}],visited:[],discovered:[command.startUrl],pages:0,actions:0,requests:0,excluded:0,cancelled:false};
    await persist();
    try {
      if(chrome.permissions?.contains&&!await chrome.permissions.contains({origins:[match]}))throw new Error('브라우저 사이트 권한을 다시 활성화하세요.');
      const tab=await chrome.tabs.create({url:'about:blank',active:false});task.tabId=tab.id;
      const rules=inspectionRules(tab.id,command,firstRule);
      for(const rule of rules)if(rule.condition.regexFilter&&chrome.declarativeNetRequest.isRegexSupported){
        const result=await chrome.declarativeNetRequest.isRegexSupported({regex:rule.condition.regexFilter,isCaseSensitive:rule.condition.isUrlFilterCaseSensitive});
        if(!result.isSupported)throw new Error('Scope 경로 규칙을 브라우저에 적용할 수 없습니다.');
      }
      await chrome.declarativeNetRequest.updateSessionRules({removeRuleIds:await ownedRules(),addRules:rules});
      await persist();await emit('started',{url:command.startUrl,message:'전용 탭과 Scope GET/HEAD 전송 제한 적용'});
      run();
    } catch(error){await finish('failed',String(error.message||error),'error');}
  }
  async function poll() {
    if(stopped||polling)return;polling=true;
    try {
      const saved=await settings();
      if(!saved.inspectionEnabled||!saved.base||!saved.token){if(task)await finish('stopped','SPA 탐색 연결 해제');return;}
      if(task&&!saved.origins.includes(task.command.origin)){await finish('stopped','사이트 활성화 해제');return;}
      const sites=task?[task.command.origin]:saved.origins.slice(0,50);
      for(const origin of sites) {
        const response=await hooks.command(origin);
        if(response.state==='run')await launch(response);
        else if(task&&response.runId===task.command.runId&&['cancel','idle'].includes(response.state))await finish('stopped','Workbench에서 브라우저 점검을 중지했습니다.');
      }
    } catch(error) {
      await chrome.storage.session.set({inspectionStatus:'브라우저 연결 확인 필요',inspectionError:String(error.message||error).slice(0,160)});
      // A failed heartbeat does not extend the lease. Existing DNR guards remain in place.
      if(task&&Date.now()>=task.command.expiresAt)await finish('partial','연결 실패와 시간 한도');
    } finally{polling=false;}
  }
  async function request(details) {
    const current=task;if(!current||details.tabId!==current.tabId||current.cancelled)return;
    current.requests++;
    if(!inspectionAllows(details.url,current.command)||!['GET','HEAD'].includes(details.method)){
      current.excluded++;await emit('excluded',{url:details.url,method:details.method,message:'브라우저 Scope/조회 전용 정책에서 제외'});
    } else if(['xmlhttprequest','other'].includes(details.type)){
      let page=current.current;try{const tab=await chrome.tabs.get(current.tabId);if(inspectionAllows(tab.url,current.command))page=tab.url;}catch{}
      if(task===current&&!current.cancelled)await emit('api',{url:details.url,page,method:details.method,label:'브라우저 관측 API',message:'탭·현재 라우트 관측 문맥 · JavaScript 인과/계정 신원 확정 아님. HTTP 검증은 별도 선택 계정으로 수행'});
    }
    if(task===current&&current.requests>=2000)await finish('partial','브라우저 네트워크 관측 한도 · HTTP 검사 예산과 별도');
  }
  async function blocked(details) {
    if(task&&details.tabId===task.tabId&&String(details.error||'').includes('BLOCKED')){
      task.excluded++;await emit('excluded',{url:details.url,method:details.method,message:'범위 밖 리소스 또는 조회 전용 정책에 따라 브라우저 전송 차단'});
    }
  }
  async function recover() {
    const saved=await chrome.storage.session.get(storageKey), previous=saved[storageKey];
    if(!previous){await removeRules();return;}
    try{
      inspectionCommand({...previous.command,state:'run'});
      const tab=await chrome.tabs.get(previous.tabId);
      task=previous;task.cancelled=false;
      if(task.current&&!task.visited.includes(task.current)&&!task.queue.some(x=>x.url===task.current))task.queue.unshift({url:task.current,label:'중단된 화면 이어서 확인'});
      const rules=inspectionRules(tab.id,task.command,firstRule);
      await chrome.declarativeNetRequest.updateSessionRules({removeRuleIds:await ownedRules(),addRules:rules});
      run();
    }catch{task=previous;await finish('partial','브라우저 재시작 후 남은 탐색을 복구하지 못했습니다.');}
  }
  async function removed(tabId){if(task&&task.tabId===tabId&&!finishing)await finish('stopped','전용 탐색 탭이 닫혔습니다.');}
  function start(){
    recover().then(poll).catch(error=>chrome.storage.session.set({inspectionError:String(error.message||error).slice(0,160)}));
    const timer=setInterval(()=>poll(),2500);timer.unref?.();
    if(chrome.alarms){chrome.alarms.create('scope-inspection-poll',{periodInMinutes:0.5});chrome.alarms.onAlarm.addListener(alarm=>{if(alarm.name==='scope-inspection-poll')poll();});}
    return ()=>{stopped=true;clearInterval(timer);return finish('stopped','브라우저 도우미 종료');};
  }
  return {start,poll,request,blocked,removed,launch,snapshot:()=>task};
}
