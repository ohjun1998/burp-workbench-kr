#!/usr/bin/env python3
"""Run released Workbench against an isolated, offline OWASP Juice Shop instance."""
import argparse,base64,hashlib,json,os,socket,subprocess,time,urllib.request,urllib.error,http.client,runpy
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--target',type=Path,required=True);p.add_argument('--preload',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--burp',type=Path);p.add_argument('--java21',type=Path);p.add_argument('--chromium',type=Path);p.add_argument('--probe',action='store_true');p.add_argument('--extension-version',default=runpy.run_path(str(ROOT/'project/burp-development-workspace/burp-workbench-kr/burp-workbench/release_info.py'))['VERSION']);p.add_argument('--extension-jar',type=Path);args=p.parse_args();out=args.out.resolve();out.mkdir(parents=True,exist_ok=True)
base='http://127.0.0.1:24540';op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def req(path,body=None,actor=None,method=None):
 h={'Content-Type':'application/json'}
 if actor:h.update({'Authorization':'Bearer '+actor['token'],'Cookie':'token='+actor['token']})
 q=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=h,method=method)
 try:r=op.open(q,timeout=15)
 except urllib.error.HTTPError as e:r=e
 try:b=r.read().decode()
 except http.client.IncompleteRead as e:b=e.partial.decode()
 return r.status,json.loads(b) if b[:1] in '{[' else b
server=native=None
try:
 env=dict(os.environ,PORT='24540',NODE_OPTIONS='--require='+str(args.preload.resolve()));env.pop('ALCHEMY_API_KEY',None);env.pop('LLM_API_KEY',None)
 server=subprocess.Popen(['node','build/app.js'],cwd=args.target,env=env,stdout=(out/'target.log').open('w'),stderr=subprocess.STDOUT)
 for _ in range(180):
  if server.poll() is not None:raise RuntimeError('Target startup failed; see target.log')
  try:
   if req('/rest/admin/application-version')[0]==200:break
  except OSError:pass
  time.sleep(.2)
 else:raise RuntimeError('Target startup timeout')
 print('Target ready:',req('/rest/admin/application-version')[1],flush=True)
 actors=[]
 for name in ['alice','bob']:
  email='wb.'+name+'@example.test';password='LocalWorkbench2026!'
  c,j=req('/api/Users',{'email':email,'password':password,'passwordRepeat':password,'securityQuestion':{'id':1},'securityAnswer':'workbench'})
  assert c==201,(c,j)
  c,j=req('/rest/user/login',{'email':email,'password':password});a=j['authentication'];actors.append(dict(name=name,token=a['token'],bid=a['bid'],email=email))
 c,j=req('/rest/user/login',{'email':'admin@juice-sh.op','password':'admin123'});a=j['authentication'];actors.insert(0,dict(name='admin',token=a['token'],bid=a['bid'],email='admin@juice-sh.op'))
 for a in actors:
  c,j=req('/rest/user/whoami',actor=a);a['uid']=j['user']['id'];assert c==200
 # Distinct owner-only address and basket data make access truth independent of response similarity.
 addresses=[]
 for a in actors[1:]:
  marker='WB_'+a['name'].upper()+'_LOCAL_20261018'
  c,j=req('/api/Addresss',dict(fullName=marker,mobileNum='01000000000',zipCode='00000',streetAddress='Local Test '+a['name'],city='Test',state='Test',country='Test'),a);assert c==201,(c,j)
  a['addressId']=j['data']['id'];a['marker']=marker
  c,j=req('/api/BasketItems',{'ProductId':1 if a['name']=='alice' else 2,'BasketId':a['bid'],'quantity':1},a);assert c in (200,201),(c,j)
 paths=['/api/Users','/rest/user/authentication-details','/rest/basket/'+str(actors[1]['bid']),'/api/Addresss/'+str(actors[1]['addressId']),'/api/Products/1','/rest/products/search?q=apple','/rest/products/search?q=%27','/rest/products/search?q=%27%20OR%201%3D1--','/robots.txt','/does-not-exist-wb-2026']
 truth=[]
 for path in paths:
  rows=[]
  for a in actors+[None]:
   c,j=req(path,actor=a);text=json.dumps(j,ensure_ascii=False) if not isinstance(j,str) else j
   rows.append(dict(actor=a['name'] if a else 'anonymous',status=c,bytes=len(text.encode()),ownerMarker=actors[1]['marker'] in text,bodySha256=hashlib.sha256(text.encode()).hexdigest(),excerpt=text[:180] if 'search?q=%27' in path else ''))
  truth.append(dict(path=path,accounts=rows));print('Probe',path,[(r['actor'],r['status']) for r in rows],flush=True)
 (out/'target-groundtruth.json').write_text(json.dumps(truth,ensure_ascii=False,indent=2))
 state=out/'private-state.json';state.write_text(json.dumps(dict(base=base,actors=actors,password='LocalWorkbench2026!')));state.chmod(0o600)
 if not args.probe:
  assert args.burp and args.java21 and args.chromium
  wb=ROOT/'project/burp-development-workspace/burp-workbench-kr/burp-workbench';jar=args.extension_jar.resolve() if args.extension_jar else ROOT/'downloads/extensions'/args.extension_version/('burp-workbench-kr-'+args.extension_version+'.jar');classes=out/'classes';classes.mkdir(exist_ok=True)
  cp=os.pathsep.join(map(str,[jar,wb/'deps/montoya-2026.jar',wb/'deps/legacy-api.jar']))
  subprocess.run(['java','com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(ROOT/'audit/oss-lab/NativeOssLab.java')],check=True)
  home=out/'private-home';home.mkdir(exist_ok=True);data=out/'private-burp';data.mkdir(exist_ok=True)
  config=out/'private-project.json';config.write_text(json.dumps({'proxy':{'request_listeners':[{'listener_port':24541,'listen_mode':'loopback_only','certificate_mode':'per_host','running':True,'support_invisible_proxying':False}]}}))
  command=[str(args.java21),'-Djava.awt.headless=true','-Duser.home='+str(home),'-Djava.util.prefs.userRoot='+str(home),'-Dqa.base='+base,'-Dqa.out='+str(out),'-cp',os.pathsep.join(map(str,[args.burp.resolve(),classes,jar])),'burp.StartBurp','--data-dir='+str(data),'--use-defaults','--disable-auto-update','--config-file='+str(config),'--developer-extension-class-name=kr.workbench.NativeOssLab']
  native=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=(out/'burp.log').open('w'),stderr=subprocess.STDOUT,text=True);native.stdin.write('y\n');native.stdin.close()
  deadline=time.monotonic()+180
  while not (out/'browser-ready').exists():
   if native.poll() is not None:raise RuntimeError('Burp stopped before browser; see burp.log')
   if time.monotonic()>deadline:raise RuntimeError('Burp startup timeout')
   time.sleep(.2)
  for _ in range(100):
   with socket.socket() as probe:
    if probe.connect_ex(('127.0.0.1',24541))==0:break
   time.sleep(.1)
  browser_env=dict(os.environ,WB_CHROMIUM=str(args.chromium.resolve()))
  browser=subprocess.run(['node',str(ROOT/'audit/oss-lab/browser_checks.mjs'),str(state),str(out)],env=browser_env,timeout=180,stdout=(out/'browser.log').open('w'),stderr=subprocess.STDOUT)
  if browser.returncode:print('Browser test had failures; see browser.log',flush=True)
  (out/'browser-done').write_text(str(browser.returncode))
  native.wait(timeout=600)
  if native.returncode:raise RuntimeError('Native harness stopped; see burp.log')
  print('Native feature results saved',flush=True)
finally:
 for process in [native,server]:
  if process and process.poll() is None:
   process.terminate()
   try:process.wait(timeout=15)
   except subprocess.TimeoutExpired:process.kill();process.wait()
 (out/'owned-processes-stopped.json').write_text(json.dumps({'juiceShopStopped':server is not None and server.poll() is not None,'burpStopped':native is None or native.poll() is not None}))
