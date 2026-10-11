"""Run from the Compose verifier container. No third-party Python dependencies."""
from pathlib import Path
import argparse,datetime,json,shutil,subprocess,sys,tempfile,traceback
import http_test_client as http

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
  report['oracle_http_suite_completed']=True;report['status']='PASSED';return 0
 except Exception as e:
  report['error']=str(e);report['traceback']=traceback.format_exc();print('Verification FAILED: '+str(e),file=sys.stderr);return 1
 finally:
  report['finished_at']=datetime.datetime.now(datetime.timezone.utc).isoformat();(out/'stack-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
if __name__=='__main__':sys.exit(main())
