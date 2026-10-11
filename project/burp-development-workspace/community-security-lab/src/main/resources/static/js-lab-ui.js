/* This UI only fetches fixture bodies. It never evals/imports sample code. */
(() => {
 'use strict';
 const $=id=>document.getElementById(id);
 const results=new Map();let cases=[],selected=null,busy=false;
 function pretty(text){try{return JSON.stringify(JSON.parse(text),null,2);}catch{return text;}}
 function packet(result){
  $('request').textContent=result.request;
  $('response').textContent=result.response;
 }
 function render(){
  $('cases').replaceChildren();
  cases.forEach((c,i)=>{const b=document.createElement('button');b.className='case'+(c===selected?' selected':'')+(results.has(c.file)?' loaded':'');
   const title=document.createElement('strong');title.textContent=String(i+1).padStart(2,'0')+' · '+c.title;
   const sub=document.createElement('span');sub.textContent=c.group+' · '+c.file;
   b.append(title,sub);b.onclick=()=>choose(c);$('cases').append(b);
  });
 }
 function choose(c){selected=c;render();$('case-title').textContent=c.title;$('description').textContent=c.description;$('group').textContent=c.group;
  $('expected').replaceChildren();c.expected.forEach(x=>{const li=document.createElement('li');li.textContent=x;$('expected').append(li);});
  $('source-link').href='/assets/js-lab/'+c.file;
  const r=results.get(c.file);$('source').textContent=r?r.text:'단일 수집을 누르면 원본 코드와 요청/응답이 표시됩니다.';
  if(r)packet(r);else {$('request').textContent='GET /assets/js-lab/'+c.file+' HTTP/1.1\nHost: '+location.host;$('response').textContent='미수집 · 단일 수집을 눌러주세요.';}
 }
 function setBusy(value){busy=value;$('collect-all').disabled=value;$('collect-one').disabled=value;$('collect-map').disabled=value;document.querySelectorAll('[data-api]').forEach(b=>b.disabled=value);}
 async function fetchPacket(path,options={}){
  const method=options.method||'GET',headers=options.headers||{};
  const request=method+' '+path+' HTTP/1.1\nHost: '+location.host+'\n'+Object.entries(headers).map(([k,v])=>k+': '+v).join('\n')+(options.body?'\n\n'+pretty(options.body):'');
  try {const r=await fetch(path,{...options,cache:'no-store'});const text=await r.text();return {text,request,response:'HTTP '+r.status+' '+r.statusText+'\n'+Array.from(r.headers).map(([k,v])=>k+': '+v).join('\n')+'\n\n'+pretty(text),ok:r.ok};}
  catch(e){return {text:'',request,response:'요청 실패: '+e.message,ok:false};}
 }
 async function collect(c){const r=await fetchPacket('/assets/js-lab/'+c.file);if(r.ok)results.set(c.file,r);if(selected===c){$('source').textContent=r.text;packet(r);}render();return r.ok;}
 $('collect-one').onclick=async()=>{if(!selected||busy)return;setBusy(true);try{const ok=await collect(selected);$('status').textContent=ok?'단일 수집 완료 · JS 추출 결과를 확인하세요.':'수집 실패 · 응답 창을 확인하세요.';}finally{setBusy(false);}};
 $('collect-all').onclick=async()=>{if(busy)return;setBusy(true);let successes=0;try{for(let i=0;i<cases.length;i++){$('status').textContent='수집 중 '+(i+1)+' / '+cases.length;if(await collect(cases[i]))successes++;}$('status').textContent='수집 완료 '+successes+' / '+cases.length+' · JS 추출에서 확인하세요.';}finally{setBusy(false);}};
 $('collect-map').onclick=async()=>{if(busy)return;setBusy(true);try{packet(await fetchPacket('/assets/js-lab/11-sourcemap.js.map'));$('status').textContent='외부 map 응답 수집 완료 · 원본 복원 결과를 확인하세요.';}finally{setBusy(false);}};
 document.querySelectorAll('[data-api]').forEach(b=>b.onclick=async()=>{if(busy)return;setBusy(true);try{const echo=b.dataset.api==='echo';packet(await fetchPacket('/api/js-lab/'+b.dataset.api,echo?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({postId:101,text:'JS lab packet sample'})}:{}));$('status').textContent='HTTP 응답 샘플 수신 · 패킷 창에서 확인하세요.';}finally{setBusy(false);}});
 $('layout').onchange=()=>document.querySelector('.packet').classList.toggle('horizontal',$('layout').value==='horizontal');
 fetch('/assets/js-lab/manifest.json',{cache:'no-store'}).then(r=>{if(!r.ok)throw new Error('manifest HTTP '+r.status);return r.json();}).then(m=>{cases=m.cases;choose(cases[0]);$('status').textContent=cases.length+'개 샘플 준비 완료 · 전체 수집을 누르세요.';}).catch(e=>{$('status').textContent='목록 로드 실패: '+e.message;setBusy(true);});
})();
