"""HTTP regression suite. Default: test running local Oracle stack. With --embedded / --war: developer tests.
With --url: test the running Docker/Oracle stack and require database=Oracle Free.
Destructive only to the synthetic lab database: creates notes/uploads/changes alice email.
"""
from pathlib import Path
import http_test_client as requests
import subprocess,time,sys,json,os
R=Path(__file__).parent; report=[]; proc=None
if '--url' in sys.argv or ('--embedded' not in sys.argv and '--war' not in sys.argv):
 base=(sys.argv[sys.argv.index('--url')+1] if '--url' in sys.argv else 'http://127.0.0.1:8088').rstrip('/'); expected_db='Oracle Free'
else:
 base='http://127.0.0.1:8088';expected_db='H2 Oracle mode (테스트용)'
 cp=str(R/'.build')+os.pathsep+os.pathsep.join(str(x) for x in (R/'.build-deps').glob('*.jar'))
 log=open(R/'.build/server.log','w')
 if '--war' in sys.argv:
  cp=str(R/'.build')+os.pathsep+os.pathsep.join(str(x) for x in (R/'.build-deps').glob('*.jar') if x.name.startswith(('tomcat-embed','jakarta.annotation')))
  cmd=['java','-cp',cp,'lab.enterprise.WarServer',str(R/'dist/ROOT.war')]
 else: cmd=['java','-cp',cp,'lab.enterprise.DevServer']
 proc=subprocess.Popen(cmd,stdout=log,stderr=log)
def check(label,condition):
 report.append({'test':label,'passed':bool(condition)})
 print(('PASS' if condition else 'FAIL')+' '+label,flush=True)
 if not condition: raise AssertionError(label)
def req(s,m,path,**kw):return s.request(m,base+path,timeout=15,**kw)
def login(s,name):
 r=req(s,'POST','/api/auth/login',data={'username':name,'password':'LabOnly!2026'});check(name+' login',r.status_code==200);return r.json()['csrf']
try:
 for i in range(90):
  try:
   if requests.get(base+'/health',timeout=1).status_code==200:break
  except requests.RequestException:pass
  if proc and proc.poll() is not None:raise RuntimeError((R/'.build/server.log').read_text())
  time.sleep(.25)
 h=requests.get(base+'/health',timeout=5);check('actual DB health',h.status_code==200 and h.json()['database']==expected_db)
 check('real HTML page',requests.get(base,timeout=5).status_code==200 and ('LOOP' in requests.get(base,timeout=5).text and 'community.css' in requests.get(base,timeout=5).text))
 for f in ['community.css','community.js','lab-endpoints.js','safe-endpoints.js']:check('static '+f,requests.get(base+'/assets/'+f,timeout=5).status_code==200)
 anonymous=requests.Session();check('orders need auth',req(anonymous,'GET','/api/vuln/orders/101').status_code==401)
 a=requests.Session();b=requests.Session();admin=requests.Session();token=login(a,'alice');bt=login(b,'bob');at=login(admin,'admin')
 check('invalid password denied',req(anonymous,'POST','/api/auth/login',data={'username':'alice','password':'wrong'}).status_code==401)
 check('auth SQL injection denied',req(anonymous,'POST','/api/auth/login',data={'username':"alice' OR '1'='1",'password':'wrong'}).status_code==401)
 check('current user identity',req(a,'GET','/api/auth/me').json()['user']['USERNAME']=='alice')
 check('own orders only',len(req(a,'GET','/api/vuln/orders').json())==2)
 check('vuln IDOR exposed',req(a,'GET','/api/vuln/orders/102').status_code==200)
 check('fixed IDOR denied',req(a,'GET','/api/fixed/orders/102').status_code==404)
 check('fixed own order allowed',req(a,'GET','/api/fixed/orders/101').status_code==200)
 check('vuln role spoof exposed',req(a,'GET','/api/vuln/admin/users?role=ADMIN').status_code==200)
 check('fixed role spoof denied',req(a,'GET','/api/fixed/admin/users?role=ADMIN').status_code==403)
 check('fixed real admin allowed',req(admin,'GET','/api/fixed/admin/users').status_code==200)
 check('vuln SQLi reproduced',len(req(a,'GET','/api/vuln/search',params={'q':"' OR 1=1 -- "}).json())==3)
 check('fixed SQLi treated as data',req(a,'GET','/api/fixed/search',params={'q':"' OR 1=1 -- "}).json()==[])
 err=req(a,'GET','/api/vuln/search',params={'q':"'"});check('vuln SQL error disclosed',err.status_code==500 and 'SQL' in err.text)
 check('fixed quote search safe',req(a,'GET','/api/fixed/search',params={'q':"'"}).status_code==200)
 check('vuln CSRF token absent accepted',req(a,'POST','/api/vuln/profile',data={'email':'changed@lab.invalid'}).status_code==200)
 check('fixed CSRF missing rejected',req(a,'POST','/api/fixed/profile',data={'email':'blocked@lab.invalid'}).status_code==403)
 check('fixed CSRF wrong rejected',req(a,'POST','/api/fixed/profile',data={'email':'blocked@lab.invalid'},headers={'X-CSRF-Token':'wrong'}).status_code==403)
 check('fixed CSRF valid accepted',req(a,'POST','/api/fixed/profile',data={'email':'alice@lab.invalid'},headers={'X-CSRF-Token':token}).status_code==200)
 html='<img src=x onerror="document.body.dataset.lab=1">'
 check('vuln reflected HTML raw',html in req(a,'GET','/vuln/preview',params={'text':html}).text)
 check('fixed reflected HTML escaped','&lt;img' in req(a,'GET','/fixed/preview',params={'text':html}).text and html not in req(a,'GET','/fixed/preview',params={'text':html}).text)
 check('vuln stored note accepted',req(a,'POST','/api/vuln/notes',data={'body':html}).status_code==200)
 check('fixed note missing token rejected',req(a,'POST','/api/fixed/notes',data={'body':html}).status_code==403)
 check('fixed note accepted with token',req(a,'POST','/api/fixed/notes',data={'body':html},headers={'X-CSRF-Token':token}).status_code==200)
 check('notes persisted DB',any(n['BODY']==html for n in req(a,'GET','/api/vuln/notes').json()))
 check('unknown mode rejected',req(a,'GET','/api/other/orders').status_code==404)
 v=req(a,'POST','/api/vuln/uploads?name=demo.html',data=(R/'fixtures/demo.html').read_bytes(),headers={'Content-Type':'text/html'});check('vuln HTML upload accepted',v.status_code==200)
 vd=req(anonymous,'GET',v.json()['url']);check('vuln public inline upload',vd.status_code==200 and vd.headers['Content-Disposition']=='inline' and '<script>' in vd.text)
 check('fixed HTML upload rejected',req(a,'POST','/api/fixed/uploads?name=demo.html',data=(R/'fixtures/demo.html').read_bytes(),headers={'Content-Type':'text/html','X-CSRF-Token':token}).status_code==415)
 check('fixed false PNG MIME rejected',req(a,'POST','/api/fixed/uploads?name=demo.png',data=b'not an image',headers={'Content-Type':'image/png','X-CSRF-Token':token}).status_code==415)
 good=req(a,'POST','/api/fixed/uploads?name=pixel.png',data=(R/'fixtures/pixel.png').read_bytes(),headers={'Content-Type':'image/png','X-CSRF-Token':token});check('fixed real PNG accepted',good.status_code==200)
 url=good.json()['url'];gd=req(a,'GET',url);check('fixed attachment and nosniff',gd.status_code==200 and gd.headers['Content-Disposition'].startswith('attachment') and gd.headers['X-Content-Type-Options']=='nosniff')
 check('fixed download wrong owner denied',req(b,'GET',url).status_code==404)
 check('fixed download anonymous denied',req(anonymous,'GET',url).status_code==401)
 check('upload 1MiB cap',req(a,'POST','/api/vuln/uploads?name=big.bin',data=b'x'*1048577).status_code==400)
 check('vuln config public',req(anonymous,'GET','/api/vuln/config').status_code==200)
 check('fixed config anonymous denied',req(anonymous,'GET','/api/fixed/config').status_code==401)
 check('fixed config user denied',req(a,'GET','/api/fixed/config').status_code==403)
 check('fixed config admin allowed',req(admin,'GET','/api/fixed/config').status_code==200)
 check('JS fixture fake keys', 'AKIAIOSFODNN7EXAMPLE' in requests.get(base+'/assets/lab-endpoints.js',timeout=5).text)
 check('safe JS contains no fixture key','AKIA' not in requests.get(base+'/assets/safe-endpoints.js',timeout=5).text)
 check('OpenAPI schema',req(anonymous,'GET','/v3/api-docs').json()['openapi']=='3.0.3')
 check('logout csrf checked',req(a,'POST','/api/auth/logout').status_code==403)
 check('logout succeeds',req(a,'POST','/api/auth/logout',headers={'X-CSRF-Token':token}).status_code==200)
 check('session invalidated',req(a,'GET','/api/auth/me').status_code==401)
finally:
 out={'date':'2026-10-08','runtime':'embedded Tomcat 10.1 / Spring MVC 6.2 / '+expected_db,'tests':report,'passed':sum(x['passed'] for x in report),'total':len(report),'oracle_http_test_executed':expected_db=='Oracle Free','packaging':'deployed WAR' if '--war' in sys.argv else 'application'}
 (R/'test-results.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
 if proc:proc.terminate();proc.wait(timeout=15)
print(f'{len(report)} checks passed',flush=True)
