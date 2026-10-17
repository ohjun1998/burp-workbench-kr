// Exercise the shipped script's form/redirect lifecycle with a small DOM adapter.
// This verifies JavaScript behavior; it is not a browser layout or DOM test.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const source=readFileSync(new URL('../project/burp-development-workspace/community-security-lab/src/main/resources/static/community.js',import.meta.url),'utf8');
const settle=()=>new Promise(resolve=>setImmediate(resolve));
function screen({search='',identity=null,loginStatus=200}={}) {
 const elements=new Map(),calls=[],destinations=[],types=['USER','ADMIN'].map(value=>({value,checked:value==='USER'}));
 function element(key) {
  if(!elements.has(key))elements.set(key,{value:'',hidden:true,textContent:'',style:{},disabled:false,querySelector:()=>element(key+' button')});
  return elements.get(key);
 }
 element('#account').value='alice';element('#password').value='LabOnly!2026';
 const document={body:{dataset:{screen:'login'}},querySelector(selector){
  if(selector==='input[name="accountType"]:checked')return types.find(t=>t.checked);
  if(selector==='#who')return null;
  return element(selector);
 },querySelectorAll:()=>types};
 const fetch=async(path,options={})=>{
  calls.push({path,fields:options.body?Object.fromEntries(options.body):null});
  let status=200,data={};
  if(path==='/api/access/me'){status=identity?200:401;data=identity?{user:identity,csrf:'original'}:{error:'로그인 필요'};}
  if(path==='/api/auth/login'){status=loginStatus;data=status===200?{user:{ROLE:'ADMIN'},csrf:'next',redirect:'/?view=users'}:{error:'일반 사용자 로그인을 선택하세요.'};}
  return {ok:status===200,status,text:async()=>JSON.stringify(data)};
 };
 vm.runInNewContext(source,{document,location:{search,assign:path=>destinations.push(path)},URLSearchParams,fetch,setTimeout:()=>{},FormData:class{constructor(form){return Object.entries(form.fields);}}});
 return {element,types,calls,destinations,submit:async()=>{element('#loginform').onsubmit({preventDefault(){}});await settle();}};
}
test('admin alias selects administrator credentials and sends selected type',async()=>{
 const s=screen({search:'?type=admin'});await settle();
 assert.equal(s.types[1].checked,true);assert.equal(s.element('#account').value,'admin');
 await s.submit();assert.deepEqual(s.calls.find(c=>c.path==='/api/auth/login').fields,{username:'admin',password:'LabOnly!2026',accountType:'ADMIN'});
 assert.deepEqual(s.destinations,['/?view=users']);assert.equal(s.element('#login').disabled,false);
});
test('ordinary selector and failed login retain form with an inline error',async()=>{
 const s=screen({search:'?type=admin',loginStatus:403});await settle();s.types[0].onchange();
 assert.equal(s.types[0].checked,true);assert.equal(s.element('#account').value,'alice');await s.submit();
 assert.equal(s.calls.find(c=>c.path==='/api/auth/login').fields.accountType,'USER');assert.equal(s.destinations.length,0);
 assert.equal(s.element('#login-error').hidden,false);assert.match(s.element('#login-error').textContent,/일반 사용자/);assert.equal(s.element('#login').disabled,false);
});
test('existing session continues according to actual role regardless of selected tab',async()=>{
 const s=screen({identity:{USERNAME:'admin',ROLE:'ADMIN'}});await settle();
 assert.equal(s.element('#session-notice').hidden,false);assert.equal(s.element('#continue-link').href,'/?view=users');assert.equal(s.destinations.length,0);
 const u=screen({search:'?type=admin',identity:{USERNAME:'alice',ROLE:'USER'}});await settle();assert.equal(u.element('#continue-link').href,'/?view=feed');
});
test('registration from admin tab creates and logs in a general account',async()=>{
 const s=screen({search:'?type=admin'});await settle();
 const form={fields:{username:'new-user',password:'SomePassword2026',email:'new@lab.invalid'}};
 s.element('#registerform').onsubmit({preventDefault(){},target:form});await settle();
 assert.deepEqual(s.calls.find(c=>c.path==='/api/community/register').fields,form.fields);
 assert.deepEqual(s.calls.find(c=>c.path==='/api/auth/login').fields,{username:'new-user',password:'SomePassword2026',accountType:'USER'});
 assert.equal(s.element('#registerform button').disabled,false);
});
