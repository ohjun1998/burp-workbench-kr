"""Parse actual Compose configuration and inspect release inputs without starting containers."""
from pathlib import Path
from zipfile import ZipFile
import argparse,datetime,json,subprocess,shutil
p=argparse.ArgumentParser();p.add_argument('--compose',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parent.parent;lab=root/'project/burp-development-workspace/community-security-lab';checks=[]
def check(test,passed):
 checks.append({'test':test,'passed':bool(passed)});print(('PASS ' if passed else 'FAIL ')+test)
 if not passed:raise AssertionError(test)
report={'date_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Compose parsing and packaged input inspection; no container execution','containers_executed':False,'checks':checks}
try:
 cmd=[str(a.compose.resolve())] if a.compose else ['docker','compose']
 report['compose_version']=subprocess.check_output(cmd+['version'],cwd=lab,text=True).strip()
 conf=json.loads(subprocess.check_output(cmd+['-f','compose.yaml','-f','compose.verify.yaml','--profile','verification','config','--format','json'],cwd=lab,text=True));services=conf['services']
 check('actual Compose parses both configuration files',len(services)==5)
 check('all published ports bind only to loopback',all(port.get('host_ip')=='127.0.0.1' for s in services.values() for port in s.get('ports',[])))
 check('training network is internal',conf['networks']['lab'].get('internal') is True)
 check('no privileged or host-network service',all(not s.get('privileged') and s.get('network_mode')!='host' for s in services.values()))
 mounts=[v for s in services.values() for v in s.get('volumes',[]) if v['type']=='bind']
 check('no Docker socket mounted',all('docker.sock' not in v['source'] for v in mounts))
 check('read-only inputs exist',all(Path(v['source']).exists() for v in mounts if v.get('read_only')))
 check('Oracle service gates application startup',services['was']['depends_on']['db']['condition']=='service_healthy')
 check('application health gates Nginx startup',services['web']['depends_on']['was']['condition']=='service_healthy')
 check('database connection targets isolated Oracle service',services['was']['environment']['DB_URL']=='jdbc:oracle:thin:@//db:1521/FREEPDB1')
 check('Oracle app credentials match WAS configuration',services['was']['environment']['DB_USER']==services['db']['environment']['APP_USER'] and services['was']['environment']['DB_PASSWORD']==services['db']['environment']['APP_USER_PASSWORD'])
 check('verification service has explicit profile and output mount','verification' in services['verify']['profiles'] and any(v['target']=='/results' and not v.get('read_only') for v in services['verify']['volumes']))
 with ZipFile(lab/'dist/ROOT.war') as z:
  check('release WAR has valid ZIP CRC',z.testzip() is None)
  check('release WAR includes current diagnostics and Oracle driver','WEB-INF/classes/lab/enterprise/DiagnosticsController.class' in z.namelist() and any(n.startswith('WEB-INF/lib/ojdbc11-') for n in z.namelist()))
  check('release WAR includes current browser CSS',z.read('WEB-INF/classes/static/community.css')==(lab/'src/main/resources/static/community.css').read_bytes())
 report['status']='CONFIG_PASSED_RUNTIME_REQUIRED';report['passed']=len(checks);report['failed']=0
 report['docker_cli_available']=bool(shutil.which('docker'))
 if report['docker_cli_available']:
  runtime=subprocess.run(['docker','info','--format','{{.ServerVersion}}'],capture_output=True,text=True,timeout=20)
  report['daemon_accessible']=runtime.returncode==0
 else:report['daemon_accessible']=False
finally:a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
