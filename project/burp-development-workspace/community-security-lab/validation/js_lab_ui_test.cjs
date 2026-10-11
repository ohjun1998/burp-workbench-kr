/* Headless DOM behavior checks. No browser rendering claims. */
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const root=path.resolve(__dirname,'../src/main/resources/static');
class Element {
 constructor(tag='div'){this.tag=tag;this.children=[];this.dataset={};this.className='';this.textContent='';this.value='vertical';this.disabled=false;this.classList={toggle:(name,on)=>{const names=new Set(this.className.split(' ').filter(Boolean));on?names.add(name):names.delete(name);this.className=[...names].join(' ');}};}
 append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=[...nodes];}
}
const ids=Object.fromEntries([...fs.readFileSync(path.join(root,'js-lab.html'),'utf8').matchAll(/id="([^"]+)"/g)].map(m=>[m[1],new Element()]));
const apiButtons=['echo','unauthorized','forbidden','rate-limit'].map(name=>{const b=new Element('button');b.dataset.api=name;return b;});
const packet=new Element();let requests=[],executed=0;
const document={getElementById:id=>ids[id],createElement:tag=>new Element(tag),querySelector:()=>packet,querySelectorAll:()=>apiButtons};
const fetch=async(url,options={})=>{requests.push({url,options});let text,status=200;
 if(url.startsWith('/assets/'))text=fs.readFileSync(path.join(root,url.substring(8)),'utf8');
 else {status=({unauthorized:401,forbidden:403,'rate-limit':429})[url.split('/').pop()]||200;text=JSON.stringify({trainingOnly:true,status,body:options.body?JSON.parse(options.body):null});}
 return {ok:status>=200&&status<300,status,statusText:status===200?'OK':'Test status',headers:new Map([['content-type',url.includes('/api/')?'application/json':'application/javascript']]),text:async()=>text,json:async()=>JSON.parse(text)};
};
vm.runInNewContext(fs.readFileSync(path.join(root,'js-lab-ui.js'),'utf8'),{document,fetch,location:{host:'127.0.0.1:8088'},Map,JSON,Array,Error,eval:()=>{executed++;throw Error('must not execute fixture');}});
const settle=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
 await settle();assert.equal(ids.cases.children.length,14);assert.match(ids.status.textContent,/14개/);
 await ids['collect-one'].onclick();assert(ids.source.textContent.includes("fetch('/api"));assert.match(ids.response.textContent,/HTTP 200/);
 ids.cases.children[8].onclick();await ids['collect-one'].onclick();assert.match(ids.source.textContent,/fetch\('\/api\/js-lab\/broken/);
 await ids['collect-all'].onclick();assert.match(ids.status.textContent,/14 \/ 14/);assert.equal(ids.cases.children.filter(b=>b.className.includes('loaded')).length,14);
 ids.cases.children[12].onclick();assert.match(ids.source.textContent,/<script>/);
 await ids['collect-map'].onclick();assert.match(ids.response.textContent,/"sourcesContent"/);
 for(const b of apiButtons){await b.onclick();const expected=({echo:200,unauthorized:401,forbidden:403,'rate-limit':429})[b.dataset.api];assert.match(ids.response.textContent,new RegExp('HTTP '+expected));}
 assert.match(ids.response.textContent, /\n  "trainingOnly"/);
 ids.layout.value='horizontal';ids.layout.onchange();assert.match(packet.className,/horizontal/);
 ids.layout.value='vertical';ids.layout.onchange();assert.doesNotMatch(packet.className,/horizontal/);
 assert.equal(executed,0);assert(requests.every(r=>r.url.startsWith('/assets/')||r.url.startsWith('/api/js-lab/')));assert.equal(ids['collect-all'].disabled,false);
 fs.writeFileSync(path.join(__dirname,'../js-lab-ui-results.json'),JSON.stringify({passed:true,mode:'headless DOM behavior; not browser rendering',cases:14,checks:19,requests:requests.map(x=>({url:x.url,method:x.options.method||'GET'}))},null,2));
 console.log('19 UI behavior checks passed');
})().catch(e=>{console.error(e);process.exit(1);});
