"""Additional actual-browser and actual-Burp TLS/H2 checks; isolated processes."""
from pathlib import Path
import argparse,json,os,socket,subprocess,time,urllib.request,sys
p=argparse.ArgumentParser();p.add_argument('--runtime',type=Path,required=True);p.add_argument('--chrome',type=Path,required=True);p.add_argument('--protocol-pythonpath',type=Path,required=True);p.add_argument('--gui',action='store_true');p.add_argument('--native-only',action='store_true');a=p.parse_args()
root=Path(__file__).resolve().parent.parent;out=root/'validation-local/additional-results';out.mkdir(exist_ok=True)
wb=root/'project/burp-development-workspace/burp-workbench-kr/burp-workbench';lab=root/'project/burp-development-workspace/community-security-lab';runtime=a.runtime.resolve();java=runtime/'root/usr/lib/jvm/java-21-openjdk-amd64/bin/java';jar=wb/'dist/burp-workbench-kr-0.21.0.jar'
ports_to_check=[18599,18443,18091,18092]+([6096] if a.gui else [])
for port in ports_to_check:
 with socket.socket() as s:s.bind(('127.0.0.1',port))
for f in ['protocol-ready.txt','protocol-failure.txt','burp-protocol-checks.json','ui-tree.txt','click.txt']:(out/f).unlink(missing_ok=True)
for f in ['classes','prefs','home','tmp','data','protocol']:(out/f).mkdir(exist_ok=True)
env=dict(os.environ,TEST_OUTPUT=str(out),LAB_URL='http://127.0.0.1:18599',CHROME_PATH=str(a.chrome.resolve()),PYTHONPATH=str(a.protocol_pythonpath.resolve()),NODE_PATH=os.environ.get('CODEX_PRIMARY_RUNTIME_NODE_MODULES',os.environ.get('NODE_PATH','')),LAB_TLS_PORT='18443',LAB_CSD_VULN_PORT='18091',LAB_CSD_FIXED_PORT='18092',LAB_PROTOCOL_RUNTIME=str(out/'protocol'))
procs=[];logs=[]
def spawn(cmd,name,procenv=env):
 log=(out/name).open('w');logs.append(log);proc=subprocess.Popen(list(map(str,cmd)),stdout=log,stderr=subprocess.STDOUT,env=procenv,stdin=subprocess.PIPE);procs.append(proc);return proc
def waitport(port,proc):
 for _ in range(150):
  if proc.poll() is not None:raise RuntimeError('Process exited on port '+str(port))
  try:
   with socket.create_connection(('127.0.0.1',port),.2):return
  except OSError:time.sleep(.2)
 raise RuntimeError('Port startup timeout: '+str(port))
try:
 task=wb/'validation/all-features/.run';server=spawn([java,'-cp',str(task/'site')+os.pathsep+str(task/'deps/*'),'lab.enterprise.DevServer'],'lab.log',dict(env,PORT='18599',JAVA_TOOL_OPTIONS='-Djava.io.tmpdir='+str(out/'tmp')));waitport(18599,server)
 for _ in range(150):
  try:
   with urllib.request.urlopen(env['LAB_URL']+'/health',timeout=1) as r:
    if r.status==200:break
  except Exception:time.sleep(.2)
 else:raise RuntimeError('Lab health timeout')
 protocols=spawn([sys.executable,lab/'protocols/run.py'],'protocol-services.log');waitport(18443,protocols);waitport(18091,protocols);waitport(18092,protocols)
 if not a.native_only:
  browser=spawn([os.environ.get('CODEX_PRIMARY_RUNTIME_NODE','node'),root/'validation-local/browser-checks.cjs'],'browser.log');code=browser.wait(timeout=180);print('BROWSER_EXIT',code,flush=True)
  if code:raise RuntimeError('Browser verification failed; see browser.log')
  probe=spawn([sys.executable,root/'validation-local/protocol-checks.py'],'protocol-checks.log');code=probe.wait(timeout=60);print('PROTOCOL_PROBE_EXIT',code,flush=True)
  if code:raise RuntimeError('Protocol probe failed')
 sources=[root/'validation-local/ProtocolAuditObserver.java',root/'reproduction/native-observer/WorkspacePreferencesFactory.java']
 if a.gui:
  sources.append(root/'validation-local/GuiLauncher.java');env.update(DISPLAY='127.0.0.1:96',LD_LIBRARY_PATH=str(runtime/'root/usr/lib/x86_64-linux-gnu'))
  xv=spawn([runtime/'root/usr/bin/Xvfb',':96','-screen','0','1600x1000x24','-nolisten','unix','-nolisten','local','-listen','tcp','-ac','-noreset','-nolock','-fp','built-ins','-xkbdir',runtime/'root/usr/share/X11/xkb'],'xvfb.log');waitport(6096,xv)
 subprocess.run(['java','com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',str(wb/'deps/montoya-2026.jar'),'-d',str(out/'classes'),*map(str,sources)],check=True)
 user={'user_options':{'extender':{'settings':{'automatically_update_bapps_on_startup':False},'extensions':[{'extension_file':str(jar),'extension_type':'java','loaded':True,'name':'Workbench KR protocol check','output':'file','output_file':str(out/'extension-output.log'),'errors':'file','error_file':str(out/'extension-errors.log')}]}}}
 (out/'user.json').write_text(json.dumps(user));(out/'project.json').write_text(json.dumps({'proxy':{'request_listeners':[],'intercept_client_requests':{'do_intercept':False},'intercept_server_responses':{'do_intercept':False}}}))
 options=[java,'-Xmx768m','-Djava.awt.headless='+str(not a.gui).lower(),'-Dsun.java2d.xrender=false','-Djava.util.prefs.PreferencesFactory=check.WorkspacePreferencesFactory','-Dtest.preferences.root='+str(out/'prefs'),'-Duser.home='+str(out/'home'),'-Djava.io.tmpdir='+str(out/'tmp'),'-Dtest.run.root='+str(out),'-Dfile.encoding=UTF-8']
 options+=['--add-opens='+x+'=ALL-UNNAMED' for x in ['java.base/java.lang','java.desktop/javax.swing','java.desktop/java.awt','java.desktop/java.awt.color','java.base/javax.crypto','jdk.crypto.cryptoki/sun.security.pkcs11']]
 burp=spawn(options+['-cp',str(out/'classes')+os.pathsep+str(runtime/'burp.jar'),'check.GuiLauncher' if a.gui else 'burp.StartBurp','--use-defaults','--disable-auto-update','--data-dir='+str(out/'data'),'--config-file='+str(out/'project.json'),'--user-config-file='+str(out/'user.json'),'--developer-extension-class-name=check.ProtocolAuditObserver'],'burp.log');burp.stdin.write(b'y\n\n');burp.stdin.flush();burp.stdin.close()
 for _ in range(180):
  if (out/'protocol-failure.txt').exists():raise RuntimeError((out/'protocol-failure.txt').read_text())
  if (out/'protocol-ready.txt').exists():print('ACTUAL_BURP_PROTOCOL_PASSED',flush=True);break
  if burp.poll() is not None:raise RuntimeError('Burp exited '+str(burp.returncode))
  time.sleep(1)
 else:raise RuntimeError('Burp protocol audit timeout')
finally:
 for proc in reversed(procs):
  if proc.poll() is None:proc.terminate()
 for proc in reversed(procs):
  try:proc.wait(timeout=5)
  except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=5)
 for log in logs:log.close()
 ports={}
 for port in ports_to_check:
  with socket.socket() as s:ports[str(port)]=s.connect_ex(('127.0.0.1',port))!=0
 (out/'cleanup.json').write_text(json.dumps({'ports_closed':ports},indent=2));print('ISOLATED_PROCESSES_STOPPED',ports,flush=True)
