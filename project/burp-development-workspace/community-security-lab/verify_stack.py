"""Run from the Compose verifier container. No third-party Python dependencies."""
from pathlib import Path
import argparse,datetime,json,shutil,subprocess,sys,tempfile,traceback
import http_test_client as http

def authentication_checks(base,check):
 profiles=['session','token','jwt','session-token','session-jwt','token-jwt','all'];count=0
 def verify(name,ok,details):
  nonlocal count
  count+=1;check(name,ok,details)
 for profile in profiles:
  page=http.get(base+'/auth-lab/'+profile)
  verify('auth page '+profile,page.status_code==200 and 'auth-login-form' in page.text,{'status':page.status_code})
  actors={}
  for actor in ['admin','alice','bob']:
   client=http.Session();login=client.request('POST',base+'/api/auth-lab/login',data={'profile':profile,'username':actor,'password':'LabOnly!2026'})
   verify('auth login '+profile+' '+actor,login.status_code==200,{'status':login.status_code})
   actors[actor]=(client,login.json()['headers'])
  actors['anonymous']=(http.Session(),{})
  for actor,(client,headers) in actors.items():
   for mode,path,expected in [('fixed','member',401 if actor=='anonymous' else 200),('fixed','admin/users',401 if actor=='anonymous' else 200 if actor=='admin' else 403),('fixed','requests/901',401 if actor=='anonymous' else 200 if actor in ['admin','alice'] else 404),('fixed','requests/902',401 if actor=='anonymous' else 200 if actor in ['admin','bob'] else 404),('vuln','admin/users',401 if actor=='anonymous' else 200),('vuln','requests/901',401 if actor=='anonymous' else 200)]:
    response=client.get(base+'/api/auth-lab/'+profile+'/'+mode+'/'+path,headers=headers)
    okay=response.status_code==expected
    if expected>=400:okay=okay and 'AUTHLAB_PRIVATE_' not in response.text and 'AUTHLAB_ADMIN_DIRECTORY' not in response.text
    if expected==200 and path.startswith('requests/'):okay=okay and response.json()['data']['ownerId']==(1 if path.endswith('901') else 2)
    verify('auth boundary '+profile+' '+actor+' '+mode+'/'+path,okay,{'status':response.status_code,'expected':expected})
 # Simulate the externally published port through Nginx. Host must retain its port.
 response=http.Session().request('POST',base+'/api/auth-lab/login',headers={'Host':'127.0.0.1:8088','Origin':'http://127.0.0.1:8088'},data={'profile':'session','username':'alice','password':'LabOnly!2026'})
 verify('auth origin preserves published host port',response.status_code==200,{'status':response.status_code})
 return count

def main():
 p=argparse.ArgumentParser();p.add_argument('--url',default='http://web');p.add_argument('--output',default='/results');a=p.parse_args();base=a.url.rstrip('/');out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
 report={'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'target':base,'status':'FAILED','checks':[],'oracle_http_suite_completed':False}
 def check(name,ok,details):
  report['checks'].append({'test':name,'passed':bool(ok),'details':details})
  print(('PASS ' if ok else 'FAIL ')+name,flush=True)
  if not ok:raise AssertionError(name)
 try:
  health=http.get(base+'/health',timeout=20);h=health.json()
  check('Oracle-backed application health',health.status_code==200 and h.get('status')=='UP' and h.get('database')=='Oracle Free',h)
  server=health.headers.get('Server','')
  check('Nginx reverse proxy',server.lower().startswith('nginx'),{'server_header':server})
  status=http.get(base+'/nginx_status',timeout=10)
  check('Nginx stub_status',status.status_code==200 and 'Active connections:' in status.text,{'status_code':status.status_code,'body':status.text})
  with tempfile.TemporaryDirectory(prefix='enterprise-verify-') as tmp:
   root=Path(__file__).parent;t=Path(tmp)
   for file in ['tests.py','http_test_client.py']:shutil.copy2(root/file,t/file)
   shutil.copytree(root/'fixtures',t/'fixtures')
   result=subprocess.run([sys.executable,str(t/'tests.py'),'--url',base],capture_output=True,text=True)
   (out/'http-tests.log').write_text(result.stdout+'\n'+result.stderr,encoding='utf-8');print(result.stdout,flush=True)
   details=t/'test-results.json'
   if details.exists():shutil.copy2(details,out/'oracle-http-tests.json');suite=json.loads(details.read_text(encoding='utf-8'))
   else:suite={}
   check('Oracle HTTP regression suite (54 checks)',result.returncode==0 and suite.get('passed')==54 and suite.get('total')==54 and suite.get('oracle_http_test_executed') is True,{'exit_code':result.returncode,'passed':suite.get('passed'),'total':suite.get('total')})
  manifest=http.get(base+'/api/extensions/manifest',timeout=10)
  check('Extension training manifest',manifest.status_code==200 and len(manifest.json().get('engines',[]))==9,{'status_code':manifest.status_code})
  sm=http.get(base+'/assets/extensions-fixtures.js.map',timeout=10)
  check('JS source map training fixture',sm.status_code==200 and sm.json().get('version')==3,{'status_code':sm.status_code})
  report['authentication_lab_checks']=authentication_checks(base,check)
  report['oracle_http_suite_completed']=True;report['status']='PASSED';return 0
 except Exception as e:
  report['error']=str(e);report['traceback']=traceback.format_exc();print('Verification FAILED: '+str(e),file=sys.stderr);return 1
 finally:
  report['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();(out/'stack-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
if __name__=='__main__':sys.exit(main())
