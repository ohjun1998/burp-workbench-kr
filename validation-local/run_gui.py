"""Launch actual Burp GUI/native audits in an isolated profile and virtual display.
Runtime files are supplied with --runtime; they are not included in project releases.
"""
from pathlib import Path
import argparse,os,subprocess,socket,time,json,urllib.request,sys,shutil
p=argparse.ArgumentParser();p.add_argument('--runtime',type=Path,required=True);a=p.parse_args()
project=Path(__file__).resolve().parent.parent;runtime=a.runtime.resolve();out=project/'validation-local/results';out.mkdir(exist_ok=True)
wb=project/'project/burp-development-workspace/burp-workbench-kr/burp-workbench';jar=wb/'dist/burp-workbench-kr-0.21.0.jar';classes=out/'classes';audit=out/'native-classes'
for folder in [classes,audit]:shutil.rmtree(folder,ignore_errors=True);folder.mkdir()
for name in ['home','prefs','tmp','data']:(out/name).mkdir(exist_ok=True)
for name in ['native-ready.txt','gui-ready.txt','gui-failure.txt','click.txt','click-result.txt']:(out/name).unlink(missing_ok=True)
for port in [6098,18299,18288,19112]:
 with socket.socket() as s:s.bind(('127.0.0.1',port))
api=wb/'deps/montoya-2026.jar';cp=os.pathsep.join(map(str,[jar,api,wb/'deps/legacy-api.jar']))
sources=list((project/'validation-local').glob('*.java'))+[project/'reproduction/native-observer/WorkspacePreferencesFactory.java']
subprocess.run(['java','com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),*map(str,sources)],check=True)
subprocess.run(['java','com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(audit),*map(str,(wb/'validation/native').glob('*.java'))],check=True)
java=runtime/'root/usr/lib/jvm/java-21-openjdk-amd64/bin/java';env=dict(os.environ,DISPLAY='127.0.0.1:98',LD_LIBRARY_PATH=str(runtime/'root/usr/lib/x86_64-linux-gnu'))
processes=[];logs=[]
def spawn(command,name,process_env=env):
 log=(out/name).open('w');logs.append(log);proc=subprocess.Popen(list(map(str,command)),stdout=log,stderr=subprocess.STDOUT,env=process_env,stdin=subprocess.PIPE);processes.append(proc);return proc
def waitport(port,proc):
 for _ in range(100):
  if proc.poll() is not None:raise RuntimeError('Startup failed on '+str(port))
  try:
   with socket.create_connection(('127.0.0.1',port),.3):return
  except OSError:time.sleep(.2)
 raise RuntimeError('Startup timeout on '+str(port))
try:
 xv=spawn([runtime/'root/usr/bin/Xvfb',':98','-screen','0','1600x1000x24','-nolisten','unix','-nolisten','local','-listen','tcp','-ac','-noreset','-nolock','-fp','built-ins','-xkbdir',runtime/'root/usr/share/X11/xkb'],'xvfb.log');waitport(6098,xv)
 task=wb/'validation/all-features/.run';labcp=os.pathsep.join([str(task/'site'),str(task/'deps/*')]);server=spawn([java,'-cp',labcp,'lab.enterprise.DevServer'],'lab.log',dict(env,PORT='18299'));waitport(18299,server)
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 for _ in range(100):
  try:
   with opener.open('http://127.0.0.1:18299/health',timeout=2) as response:
    if response.status==200:break
  except Exception:time.sleep(.2)
 else:raise RuntimeError('Lab health timeout')
 user={'user_options':{'extender':{'settings':{'automatically_update_bapps_on_startup':False},'extensions':[{'extension_file':str(jar),'extension_type':'java','loaded':True,'name':'Workbench KR GUI load check','output':'file','output_file':str(out/'extension-output.log'),'errors':'file','error_file':str(out/'extension-errors.log')}]}}}
 (out/'user.json').write_text(json.dumps(user))
 config={'proxy':{'request_listeners':[{'certificate_mode':'per_host','custom_tls_protocols':[],'enable_http2':False,'listen_mode':'loopback_only','listener_port':18288,'running':True,'use_custom_tls_protocols':False}],'intercept_client_requests':{'do_intercept':False},'intercept_server_responses':{'do_intercept':False}}}
 (out/'project.json').write_text(json.dumps(config))
 options=[java,'-Xmx1200m','-Dsun.java2d.xrender=false','-Djava.awt.headless=false','-Djava.util.prefs.PreferencesFactory=check.WorkspacePreferencesFactory','-Dtest.preferences.root='+str(out/'prefs'),'-Duser.home='+str(out/'home'),'-Djava.io.tmpdir='+str(out/'tmp'),'-Dtest.run.root='+str(out),'-Dtest.release.jar='+str(jar),'-Dtest.audit.classes='+str(audit),'-Dfile.encoding=UTF-8']
 options+=['--add-opens='+x+'=ALL-UNNAMED' for x in ['java.base/java.lang','java.desktop/javax.swing','java.desktop/java.awt','java.desktop/java.awt.color','java.base/javax.crypto','jdk.crypto.cryptoki/sun.security.pkcs11']]
 command=options+['-cp',str(classes)+os.pathsep+str(runtime/'burp.jar'),'check.GuiLauncher','--use-defaults','--disable-auto-update','--data-dir='+str(out/'data'),'--config-file='+str(out/'project.json'),'--user-config-file='+str(out/'user.json'),'--developer-extension-class-name=check.GuiAuditObserver']
 burp=spawn(command,'burp.log');print('BURP_STARTED',burp.pid,'results:',out,flush=True)
 deadline=time.monotonic()+600
 while time.monotonic()<deadline:
  if burp.poll() is not None:raise RuntimeError('Burp exited: '+str(burp.returncode))
  if (out/'gui-failure.txt').exists():raise RuntimeError((out/'gui-failure.txt').read_text())
  if (out/'native-ready.txt').exists():print('ACTUAL_GUI_NATIVE_FINISHED',flush=True);break
  time.sleep(1)
 else:raise RuntimeError('Actual GUI/native audit timed out')
finally:
 for proc in reversed(processes):
  if proc.poll() is None:proc.terminate()
 for proc in reversed(processes):
  try:proc.wait(timeout=5)
  except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=5)
 for log in logs:log.close()
 print('ISOLATED_PROCESSES_STOPPED',flush=True)
