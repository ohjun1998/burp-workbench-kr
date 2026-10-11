"""Real HTTP checks of the integrated extension training package (H2 runtime)."""
from pathlib import Path
import subprocess,os,sys,time,json,urllib.request,urllib.error,http.cookiejar,re,base64,hmac,hashlib,socket,ssl
from concurrent.futures import ThreadPoolExecutor
ROOT=Path(__file__).resolve().parent;external='--url' in sys.argv
BASE=(sys.argv[sys.argv.index('--url')+1].rstrip('/') if external else 'http://127.0.0.1:18088');results=[];procs=[]
from urllib.parse import urlsplit
if urlsplit(BASE).hostname not in ('localhost','127.0.0.1'):raise SystemExit('이 검사는 로컬 실습 서버만 대상으로 합니다.')
TLS_PORT=8443 if external else 18443;CSD_VULN=8091 if external else 18091;CSD_FIXED=8092 if external else 18092
env=dict(os.environ,LAB_TLS_PORT=str(TLS_PORT),LAB_CSD_VULN_PORT=str(CSD_VULN),LAB_CSD_FIXED_PORT=str(CSD_FIXED));env['PYTHONPATH']=os.environ.get('PROTOCOL_PYTHONPATH','')
def check(name,ok):
 results.append({'test':name,'passed':bool(ok)});print(('PASS ' if ok else 'FAIL ')+name,flush=True)
 if not ok:raise AssertionError(name)
class Client:
 def __init__(self):self.jar=http.cookiejar.CookieJar();self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
 def request(self,path,method='GET',body=None,headers=None):
  hs=dict(headers or {});data=None if body is None else body.encode();hs.update({'Content-Type':'application/x-www-form-urlencoded'} if data is not None else {})
  req=urllib.request.Request(BASE+path,data=data,headers=hs,method=method)
  try:r=self.opener.open(req,timeout=10)
  except urllib.error.HTTPError as e:r=e
  return r.status,r.read().decode()
 def login(self,user):return self.request('/api/auth/login','POST','username='+user+'&password=LabOnly%212026')
def enc(value):return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).decode().rstrip('=')
def jwt(head,body,key=None):
 value=enc(head)+'.'+enc(body);return value+'.'+(base64.urlsafe_b64encode(hmac.new(key.encode(),value.encode(),hashlib.sha256).digest()).decode().rstrip('=') if key else 'invalid')
try:
 if not external:
  cp=str(ROOT/'.build')+os.pathsep+os.pathsep.join(str(x) for x in (ROOT/'.build-deps').glob('*.jar'))
  procs.append(subprocess.Popen(['java','-cp',cp,'lab.enterprise.DevServer'],env=dict(os.environ,PORT='18088'),stdout=open(ROOT/'.build/extension-server.log','w'),stderr=subprocess.STDOUT))
  procs.append(subprocess.Popen([sys.executable,'protocols/run.py'],cwd=ROOT,env=env,stdout=open(ROOT/'.build/protocols.log','w'),stderr=subprocess.STDOUT))
 anon=Client()
 for _ in range(100):
  try:
   if anon.request('/health')[0]==200:break
  except Exception:pass
  time.sleep(.2)
 for _ in range(100):
  try:
   with socket.create_connection(('127.0.0.1',CSD_VULN),timeout=.2):break
  except OSError:time.sleep(.2)
 check('hub HTML served',anon.request('/extensions')[0]==200)
 manifest=json.loads(anon.request('/api/extensions/manifest')[1]);check('all nine engines listed',len(manifest['engines'])==9)
 for name in ['extensions-hub.js','extensions-catalog.json','extensions-fixtures.js','extensions-fixtures.js.map','param-miner-wordlist.txt']:check('asset '+name,anon.request('/assets/'+name)[0]==200)
 sm=json.loads(anon.request('/assets/extensions-fixtures.js.map')[1]);check('source map includes original synthetic source',sm['version']==3 and len(sm['sourcesContent'])==len(sm['sources']))
 check('hidden baseline stable',anon.request('/api/extensions/hidden')[1]==anon.request('/api/extensions/hidden')[1])
 check('hidden query changes response','hiddenQuery' in anon.request('/api/extensions/hidden?lab_hidden=ok')[1])
 check('hidden header changes response','hiddenHeader' in anon.request('/api/extensions/hidden',headers={'X-Lab-Debug':'ok'})[1])
 check('hidden cookie changes response','hiddenCookie' in anon.request('/api/extensions/hidden',headers={'Cookie':'lab_debug=ok'})[1])
 check('marker roundtrip','LAB-XYZ' in anon.request('/api/extensions/log?marker=LAB-XYZ')[1])
 check('base64 JSON conversion',json.loads(anon.request('/api/extensions/convert?value=eyJsYWIiOiJPSyJ9')[1])['decoded']['lab']=='OK')
 check('invalid conversion rejected',anon.request('/api/extensions/convert?value=bad')[0]==400)
 check('CSRF needs login',anon.request('/api/extensions/csrf/form')[0]==401)
 a,b,admin=Client(),Client(),Client()
 for c,u in [(a,'alice'),(b,'bob'),(admin,'admin')]:check(u+' login',c.login(u)[0]==200)
 token=re.search('value=\'([^\']+)\'',a.request('/api/extensions/csrf/form')[1]).group(1)
 check('CSRF foreign session rejected',b.request('/api/extensions/csrf/write','POST','csrf='+token)[0]==403)
 check('CSRF fresh token accepted',a.request('/api/extensions/csrf/write','POST','csrf='+token)[0]==200)
 check('CSRF replay rejected',a.request('/api/extensions/csrf/write','POST','csrf='+token)[0]==403)
 old=re.search('value=\'([^\']+)\'',a.request('/api/extensions/csrf/form')[1]).group(1)
 new=re.search('value=\'([^\']+)\'',a.request('/api/extensions/csrf/form')[1]).group(1)
 check('CSRF rotates and old token denied',new!=old and a.request('/api/extensions/csrf/write','POST','csrf='+old)[0]==403)
 check('JWT issuance needs login',anon.request('/api/extensions/jwt/issue')[0]==401)
 issued=json.loads(a.request('/api/extensions/jwt/issue')[1]);key=issued['trainingKey'];t=issued['token']
 check('JWT valid ordinary user cannot admin',anon.request('/api/extensions/fixed/jwt/admin',headers={'Authorization':'Bearer '+t})[0]==403)
 claims={'sub':'1','role':'ADMIN','exp':int(time.time())+1000};forged=jwt({'alg':'HS256'},claims)
 check('JWT vulnerable accepts unsigned role change',anon.request('/api/extensions/vuln/jwt/admin',headers={'Authorization':'Bearer '+forged})[0]==200)
 check('JWT fixed rejects bad signature',anon.request('/api/extensions/fixed/jwt/admin',headers={'Authorization':'Bearer '+forged})[0]==401)
 check('JWT fixed checks account despite re-sign',anon.request('/api/extensions/fixed/jwt/admin',headers={'Authorization':'Bearer '+jwt({'alg':'HS256'},claims,key)})[0]==403)
 adminJwt=json.loads(admin.request('/api/extensions/jwt/issue')[1])['token'];check('JWT real admin accepted',anon.request('/api/extensions/fixed/jwt/admin',headers={'Authorization':'Bearer '+adminJwt})[0]==200)
 check('JWT algorithm none denied',anon.request('/api/extensions/fixed/jwt/admin',headers={'Authorization':'Bearer '+jwt({'alg':'none'},{'sub':'3','role':'ADMIN','exp':int(time.time())+100},key)})[0]==401)
 check('JWT expiration enforced',anon.request('/api/extensions/fixed/jwt/admin',headers={'Authorization':'Bearer '+jwt({'alg':'HS256'},{'sub':'3','role':'ADMIN','exp':1},key)})[0]==401)
 check('JWT malformed returns controlled status',anon.request('/api/extensions/fixed/jwt/admin',headers={'Authorization':'Bearer x.y.z'})[0]==401)
 check('counter needs login',anon.request('/api/extensions/vuln/counter','POST','')[0]==401)
 check('counter reset',a.request('/api/extensions/counter/reset','POST','')[0]==200)
 cookie='; '.join(c.name+'='+c.value for c in a.jar)
 def hit(mode):return Client().request('/api/extensions/'+mode+'/counter','POST','',{'Cookie':cookie})[0]
 with ThreadPoolExecutor(max_workers=20) as pool:
  statuses=list(pool.map(lambda _:hit('vuln'),range(40)))
 check('parallel vuln requests succeed',all(x==200 for x in statuses))
 with ThreadPoolExecutor(max_workers=20) as pool:statuses=list(pool.map(lambda _:hit('fixed'),range(40)))
 counts=json.loads(a.request('/api/extensions/counter')[1]);check('vuln lost update reproduced',counts['vuln']<40);check('fixed preserves all increments',counts['fixed']==40 and all(x==200 for x in statuses))
 check('vuln duplicate redeem accepted',all(a.request('/api/extensions/vuln/redeem','POST','key=demo')[0]==200 for _ in range(2)))
 check('fixed first redeem accepted',a.request('/api/extensions/fixed/redeem','POST','key=demo')[0]==200)
 check('fixed duplicate redeem blocked',a.request('/api/extensions/fixed/redeem','POST','key=demo')[0]==409)
 check('redeem isolated between accounts',b.request('/api/extensions/fixed/redeem','POST','key=demo')[0]==200)
 ctx=ssl._create_unverified_context()
 with ctx.wrap_socket(socket.create_connection(('127.0.0.1',TLS_PORT)),server_hostname='localhost') as conn:
  ctx.set_alpn_protocols(['http/1.1']);conn.sendall(b'GET /tls HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n');data=conn.recv(8192);check('actual TLS HTTP1 response',b'200 OK' in data)
 sys.path.insert(0,env['PYTHONPATH']);import h2.connection,h2.config,h2.events
 ctx.set_alpn_protocols(['h2'])
 with ctx.wrap_socket(socket.create_connection(('127.0.0.1',TLS_PORT)),server_hostname='localhost') as conn:
  check('TLS ALPN negotiates H2',conn.selected_alpn_protocol()=='h2');conn.settimeout(5);h=h2.connection.H2Connection(config=h2.config.H2Configuration(header_encoding='utf-8'));h.initiate_connection();h.send_headers(1,[(':method','GET'),(':scheme','https'),(':authority','localhost'),(':path','/tls')],end_stream=True);conn.sendall(h.data_to_send());done=False;code=None
  while not done:
   for e in h.receive_data(conn.recv(65535)):
    if isinstance(e,h2.events.ResponseReceived):code=dict(e.headers)[':status']
    if isinstance(e,h2.events.StreamEnded):done=True
   out=h.data_to_send()
   if out:conn.sendall(out)
  check('actual H2 request response',code=='200')
 # A local marker request is the entire body. No external targets are used.
 body=b'GET /marker HTTP/1.1\r\nHost: localhost\r\n\r\n'
 for port,vuln in [(CSD_VULN,True),(CSD_FIXED,False)]:
  with socket.create_connection(('127.0.0.1',port)) as conn:
   conn.settimeout(.7);conn.sendall(b'POST /csd HTTP/1.1\r\nHost: localhost\r\nContent-Length: '+str(len(body)).encode()+b'\r\n\r\n'+body);reply=b''
   try:
    while True:
     chunk=conn.recv(8192)
     if not chunk:break
     reply+=chunk
   except socket.timeout:pass
   check('CSD '+('vulnerable two responses' if vuln else 'fixed consumes body'),reply.count(b'HTTP/1.1 200')==(2 if vuln else 1))
finally:
 for proc in reversed(procs):
  if proc.poll() is None:
   proc.terminate()
   try:proc.wait(timeout=10)
   except subprocess.TimeoutExpired:proc.kill();proc.wait()
 (ROOT/'extension-test-results.json').write_text(json.dumps({'runtime':'Spring MVC/Tomcat + integrated protocol services','target':BASE,'tests':results,'passed':sum(x['passed'] for x in results),'total':len(results)},ensure_ascii=False,indent=2))
print(str(len(results))+' extension checks passed')
