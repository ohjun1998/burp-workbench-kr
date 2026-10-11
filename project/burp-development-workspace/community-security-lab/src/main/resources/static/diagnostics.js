(()=>{'use strict';
 const $=id=>document.getElementById(id);let cases=[],busy=false;
 async function request(url,method='GET',value){
  if(busy)return;busy=true;$('filter').disabled=true;document.querySelectorAll('button').forEach(b=>b.disabled=true);$('request').textContent=method+' '+url;
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),10000);try{const options={signal:controller.signal,method,credentials:'same-origin',redirect:'manual'};if(value!==undefined){options.headers={'Content-Type':'application/x-www-form-urlencoded'};options.body=new URLSearchParams({value});}const response=await fetch(url,options);$('response').textContent='HTTP '+response.status+'\n'+await response.text();}
  catch(error){$('response').textContent=error.message;}finally{clearTimeout(timer);busy=false;$('filter').disabled=false;document.querySelectorAll('button').forEach(b=>b.disabled=false);}
 }
 function render(){
  const shown=cases.filter(c=>!$('filter').value||c.feature===$('filter').value);$('cases').replaceChildren();$('cases').className='cases';$('count').textContent=shown.length+'개 사례';
  for(const c of shown){const card=document.createElement('article');card.className='case';for(const [tag,text]of[['small',c.feature+' · '+c.kind],['h2',c.id],['code',c.method+' '+c.url],['p','기대 결과: '+c.expected]]){const node=document.createElement(tag);node.textContent=text;card.append(node);}
   if(c.method==='GET'){const button=document.createElement('button');button.textContent='이 사례 조회';button.onclick=()=>request(c.url);card.append(button);}else{const note=document.createElement('small');note.textContent='아래 쓰기·조회 연결 또는 실제 트래픽을 사용하세요.';card.append(note);}$('cases').append(card);
  }
 }
 $('filter').onchange=render;
 for(const mode of['vuln','fixed']){$('store-'+mode).onclick=()=>request('/api/diagnostics/'+mode+'/stored','POST',$('value').value);$('read-'+mode).onclick=()=>request('/api/diagnostics/'+mode+'/stored');}
 fetch('/api/diagnostics/manifest',{credentials:'same-origin'}).then(r=>{if(!r.ok)throw Error('사례 목록 HTTP '+r.status);return r.json();}).then(data=>{cases=data.cases;for(const c of data.payloadCases){const card=document.createElement('article');card.className='case';for(const[tag,text]of[['small',c.kind],['h3',c.profile],['code',c.method+' '+c.template],['p',c.expected],['small',c.note]]){const node=document.createElement(tag);node.textContent=text;card.append(node);}$('payloads').append(card);}for(const feature of data.features){const node=document.createElement('span');node.className='chip';node.textContent=feature;$('features').append(node);}for(const feature of [...new Set(cases.map(c=>c.feature))]){const option=document.createElement('option');option.value=option.textContent=feature;$('filter').append(option);}render();}).catch(error=>{$('count').textContent=error.message;});
})();
