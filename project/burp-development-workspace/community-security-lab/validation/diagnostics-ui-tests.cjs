const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(process.argv[2],'utf8'),manifest=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));let checks=[];
function check(name,ok){assert(ok,name);checks.push({test:name,passed:true});}
class Element{constructor(tag){this.tag=tag;this.children=[];this.textContent='';this.value='';this.disabled=false;}append(...n){this.children.push(...n);}replaceChildren(...n){this.children=n;}}
const ids=['filter','count','cases','payloads','features','request','response','value','store-vuln','read-vuln','store-fixed','read-fixed'],elements=Object.fromEntries(ids.map(id=>[id,new Element(id.startsWith('store-')||id.startsWith('read-')?'button':'div')]));let calls=[],pending,error=false;
function descendants(node){return[node,...node.children.flatMap(descendants)];}
const document={getElementById:id=>elements[id],createElement:tag=>new Element(tag),querySelectorAll:tag=>Object.values(elements).flatMap(descendants).filter(e=>e.tag===tag)};
const fetch=async(url,options)=>{calls.push({url,options});if(url.endsWith('manifest'))return{ok:true,json:async()=>manifest};if(error)throw Error('controlled failure');return new Promise(resolve=>pending=()=>resolve({status:200,text:async()=>'<img src=x onerror=training()> returned as text'}));};
const tick=()=>new Promise(r=>setImmediate(r));
(async()=>{vm.runInNewContext(source,{document,fetch,AbortController,setTimeout,clearTimeout,URLSearchParams,Error});await tick();await tick();
 check('all catalogue cards render',elements.cases.children.length===manifest.cases.length);
 check('all nine feature chips render',elements.features.children.length===9);
 check('all sixteen payload profiles render',elements.payloads.children.length===16);
 check('page load only requests the manifest',calls.length===1);
 elements.filter.value='트래픽/SCOPE';elements.filter.onchange();check('filter shows both scope boundaries',elements.cases.children.length===2);
 elements.cases.children[0].children.find(e=>e.tag==='button').onclick();await tick();check('GET uses same-origin credentials and manual redirect',calls.at(-1).options.credentials==='same-origin'&&calls.at(-1).options.redirect==='manual');
 check('filter and buttons disabled while request pending',elements.filter.disabled&&document.querySelectorAll('button').every(b=>b.disabled));
 const count=calls.length;elements['read-vuln'].onclick();await tick();check('concurrent request is ignored',calls.length===count);pending();await tick();await tick();
 check('response markup is displayed as text',elements.response.textContent.includes('<img')&&elements.response.children.length===0);
 check('controls recover after success',!elements.filter.disabled&&document.querySelectorAll('button').every(b=>!b.disabled));
 elements.value.value='<b>WB_TEST_한글01</b>';elements['store-fixed'].onclick();await tick();check('write is explicit POST with form encoded Unicode',calls.at(-1).options.method==='POST'&&calls.at(-1).options.body.get('value')===elements.value.value);pending();await tick();await tick();
 error=true;elements['read-fixed'].onclick();await tick();await tick();check('failure shown and controls recover',elements.response.textContent==='controlled failure'&&!elements.filter.disabled&&document.querySelectorAll('button').every(b=>!b.disabled));
 fs.writeFileSync(process.argv[4],JSON.stringify({version:'3.4.0',method:'DOM API adapter; no actual browser execution',passed:checks.length,failed:0,checks},null,2));console.log(checks.length+' diagnostics DOM checks passed');
})().catch(e=>{console.error(e);process.exitCode=1;});
