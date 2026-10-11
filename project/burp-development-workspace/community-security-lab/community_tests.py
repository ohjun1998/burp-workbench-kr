"""Community and extension regression tests on the local training application."""
from pathlib import Path
import subprocess,os,time,sys,json
import http_test_client as http
from urllib.parse import quote
R=Path(__file__).parent;BASE='http://127.0.0.1:18099';report=[];proc=None
def post(s,url,**kw):
 if isinstance(kw.get('data'),str):kw['data']=kw['data'].encode()
 return s.request('POST',url,**kw)
http.Session.post=post
def check(n,v):
 report.append({'test':n,'passed':bool(v)});print(('PASS ' if v else 'FAIL ')+n,flush=True)
 if not v:raise AssertionError(n)
def login(s,name):
 r=s.post(BASE+'/api/auth/login',data={'username':name,'password':'LabOnly!2026'},timeout=8);return r,r.json() if r.status_code==200 else {}
def path(m,s):return BASE+'/api/community/'+m+s
try:
 cp=str(R/'.build')+os.pathsep+os.pathsep.join(str(x) for x in (R/'.build-deps').glob('*.jar'));proc=subprocess.Popen(['java','-cp',cp,'lab.enterprise.DevServer'],env=dict(os.environ,PORT='18099'),stdout=open(R/'.build/community-tests.log','w'),stderr=subprocess.STDOUT)
 for _ in range(100):
  try:
   if http.get(BASE+'/health',timeout=1).status_code==200:break
  except Exception:time.sleep(.2)
 guest=http.Session();alice=http.Session();bob=http.Session();moder=http.Session();admin=http.Session()
 for s,n in [(alice,'alice'),(bob,'bob'),(moder,'moderator'),(admin,'admin')]:
  x,d=login(s,n);check(n+' authenticates',x.status_code==200);s.csrf=d['csrf']
 check('community homepage is rendered',http.get(BASE,timeout=5).status_code==200 and 'LOOP' in http.get(BASE,timeout=5).text)
 for a in ['community.css','community.js']:check('community static '+a,http.get(BASE+'/assets/'+a).status_code==200)
 check('fixed input marker absent from listing','debugQuery' not in guest.get(path('fixed','/posts?lab_hidden=LAB')).text)
 check('vulnerable hidden query is reflected','debugQuery' in alice.get(path('vuln','/posts?lab_hidden=LAB')).text)
 check('public post readable without login',guest.get(path('fixed','/posts/101')).status_code==200)
 check('guest cannot read members-only fixed post',guest.get(path('fixed','/posts/103')).status_code==404)
 check('guest cannot read private fixed post',guest.get(path('fixed','/posts/102')).status_code==404)
 check('A/B IDOR exposes private post in learning mode',alice.get(path('vuln','/posts/102')).status_code==200 and 'BOB_PRIVATE_NOTE' in alice.get(path('vuln','/posts/102')).text)
 check('A cannot read B private post in fixed mode',alice.get(path('fixed','/posts/102')).status_code==404)
 check('B reads own private post in fixed mode',bob.get(path('fixed','/posts/102')).status_code==200)
 check('member post visible after login',alice.get(path('fixed','/posts/103')).status_code==200)
 check('moderator can read security board',moder.get(path('fixed','/posts/105')).status_code==200)
 check('moderator cannot read a private note',moder.get(path('fixed','/posts/104')).status_code==404)
 check('modifier parameter spoof opens admin list in learning mode',alice.get(path('vuln','/admin/users?role=ADMIN')).status_code==200)
 check('server session role blocks spoofed admin list',alice.get(path('fixed','/admin/users?role=ADMIN')).status_code==403)
 check('actual administrator can access admin list',admin.get(path('fixed','/admin/users')).status_code==200)
 body={'title':'Alice private test','body':'ALICE_NEW_PRIVATE_TEST','visibility':'PRIVATE','category':'general'}
 new=alice.post(path('fixed','/posts'),data=body,headers={'X-CSRF-Token':alice.csrf});check('Alice creates private post',new.status_code==200);pid=new.json()['id']
 patch={'title':'B changed Alice post','body':'IDOR EDIT','visibility':'PUBLIC','category':'general'}
 check('B changes Alice post in learning mode',bob.post(path('vuln',f'/posts/{pid}/edit'),data=patch).status_code==200)
 check('B cannot change Alice post in fixed mode',bob.post(path('fixed',f'/posts/{pid}/edit'),data=patch,headers={'X-CSRF-Token':bob.csrf}).status_code==403)
 check('Alice fixed update requires her CSRF',alice.post(path('fixed',f'/posts/{pid}/edit'),data={**body,'title':'Alice updated'},headers={'X-CSRF-Token':alice.csrf}).status_code==200)
 html='<img src=x onerror="document.body.dataset.loop=1">'
 check('comment write requires login',guest.post(path('vuln','/posts/102/comments'),data={'body':html}).status_code==401)
 check('stored XSS payload accepted for A post',alice.post(path('vuln','/posts/101/comments'),data={'body':html}).status_code==200)
 detail=alice.get(path('vuln','/posts/101')).json();check('comment retained in post response',any(x['BODY']==html for x in detail['comments']))
 check('fixed comment rejects missing token',alice.post(path('fixed','/posts/101/comments'),data={'body':'hello'}).status_code==403)
 check('fixed comment accepts session token',alice.post(path('fixed','/posts/101/comments'),data={'body':'safe reply'},headers={'X-CSRF-Token':alice.csrf}).status_code==200)
 privateFile=BASE+'/api/community/vuln/files/bob-private-file';safeFile=BASE+'/api/community/fixed/files/bob-private-file'
 check('private attachment IDOR in learning mode',alice.get(privateFile).status_code==200 and 'BOB_PRIVATE_ATTACHMENT' in alice.get(privateFile).text)
 check('private attachment blocked in fixed mode',alice.get(safeFile).status_code==404)
 check('anonymous private attachment blocked in fixed mode',guest.get(safeFile).status_code==404)
 check('A reads B note in learning mode',alice.get(path('vuln','/messages/202')).status_code==200)
 check('A cannot read B note in fixed mode',alice.get(path('fixed','/messages/202')).status_code==404)
 check('B can read own note in fixed mode',bob.get(path('fixed','/messages/202')).status_code==200)
 check('role modifier spoof accepted in learning mode',alice.get(path('vuln','/admin/users?role=ADMIN')).status_code==200)
 check('role modifier rejected in fixed mode',alice.get(path('fixed','/admin/users?role=ADMIN')).status_code==403)
 check('search SQL injection exposes seeded posts in learning mode',len(alice.get(path('vuln',"/search?q=%27%20OR%201%3D1%20--%20")).json())>=5)
 check('search SQL injection treated as text in fixed mode',alice.get(path('fixed',"/search?q=%27%20OR%201%3D1%20--%20")).json()==[])
 check('initial Bob post linked to private attachment','project-notes.txt' in bob.get(path('fixed','/posts/102')).text)
 check('CSRF prevents unauthenticated fixed post create',guest.post(path('fixed','/posts'),data=body).status_code==401)
 check('Alice fixed private file upload accepted',alice.post(path('fixed',f'/posts/{pid}/files?name=notes.txt'),data='training text',headers={'Content-Type':'text/plain','X-CSRF-Token':alice.csrf}).status_code==200)
 check('fixed upload rejects HTML extension',alice.post(path('fixed',f'/posts/{pid}/files?name=bad.html'),data='<script>',headers={'Content-Type':'text/html','X-CSRF-Token':alice.csrf}).status_code==415)
 check('learning upload preserves attacker MIME',alice.post(path('vuln','/posts/101/files?name=demo.html'),data='<b>demo</b>',headers={'Content-Type':'text/html'}).status_code==200)
 reg=guest.post(BASE+'/api/community/register',data={'username':'newmember','password':'StrongPass!2026','email':'newmember@lab.invalid'});check('member signup creates ordinary user',reg.status_code==200 and reg.json()['role']=='USER')
 check('duplicate nickname blocked by signup validation',guest.post(BASE+'/api/community/register',data={'username':'newmember','password':'StrongPass!2026','email':'newmember@lab.invalid'}).status_code==409)
 check('reset protected from regular member',alice.post(BASE+'/api/community/reset',headers={'X-CSRF-Token':alice.csrf}).status_code==403)
 check('admin reset restores seeded community',admin.post(BASE+'/api/community/reset',headers={'X-CSRF-Token':admin.csrf}).status_code==200 and admin.get(path('fixed','/posts/102')).status_code==200)
finally:
 (R/'community-test-results.json').write_text(json.dumps({'tests':report,'passed':sum(x['passed'] for x in report),'total':len(report),'runtime':'Spring/Tomcat/H2'},ensure_ascii=False,indent=2))
 if proc:proc.terminate();proc.wait(timeout=15)
print(str(len(report))+' community checks',flush=True)
