from pathlib import Path
import subprocess,os,sys,time,json,urllib.request
sys.stdout.reconfigure(encoding='utf-8')
base=Path('work/burp-gui').resolve();root=base/'whole-audit-0210';root.mkdir(exist_ok=True)
for folder in ['home','preferences','tmp','data']:(root/folder).mkdir(exist_ok=True)
java='C:/Users/kwonohjun/AppData/Local/BurpSuiteCommunity/jre/bin/java.exe';javac='C:/Program Files/Java/jdk-24/bin/javac.exe';burp='C:/Users/kwonohjun/AppData/Local/BurpSuiteCommunity/burpsuite.jar';jar=Path('work/source/burp-development-workspace/burp-workbench-kr/burp-workbench/dist/burp-workbench-kr-0.21.0.jar').resolve()
subprocess.run([javac,'--release','17','-encoding','UTF-8','-cp',burp,'-d',str(base/'classes')]+[str(p) for p in (base/'src/check').glob('*.java')],check=True)
audit=Path('work/burp-user-audit/classes').resolve();audit.mkdir(parents=True,exist_ok=True)
subprocess.run([javac,'--release','17','-encoding','UTF-8','-cp',str(jar)+os.pathsep+burp,'-d',str(audit)]+[str(p) for p in Path('work/burp-user-audit/src/kr/workbench').glob('*.java')],check=True)
user={'user_options':{'extender':{'settings':{'automatically_update_bapps_on_startup':False},'extensions':[{'extension_file':str(jar),'extension_type':'java','loaded':True,'name':'Workbench loading check','output':'file','output_file':str(root/'extension-output.log'),'errors':'file','error_file':str(root/'extension-errors.log')}]}}}
(root/'load-user.json').write_text(json.dumps(user,indent=2),encoding='utf-8')
(root/'load-project.json').write_text(json.dumps({'proxy':{'request_listeners':[{'certificate_mode':'per_host','custom_tls_protocols':[],'enable_http2':False,'listen_mode':'loopback_only','listener_port':18288,'running':True,'use_custom_tls_protocols':False}],'intercept_client_requests':{'do_intercept':False},'intercept_server_responses':{'do_intercept':False}}}),encoding='utf-8')
command=[java,'-Xmx768m','-Djava.awt.headless=true','-Djava.util.prefs.PreferencesFactory=check.WorkspacePreferencesFactory','-Dtest.preferences.root='+str(root/'preferences'),'-Duser.home='+str(root/'home'),'-Djava.io.tmpdir='+str(root/'tmp'),'-Dtest.run.root='+str(root),'-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8']+['--add-opens='+x+'=ALL-UNNAMED' for x in ['java.base/java.lang','java.desktop/javax.swing','java.desktop/java.awt','java.desktop/java.awt.color','java.base/javax.crypto','jdk.crypto.cryptoki/sun.security.pkcs11']]+['-cp',str(base/'classes')+os.pathsep+burp,'burp.StartBurp','--data-dir='+str(root/'data'),'--config-file='+str(root/'load-project.json'),'--user-config-file='+str(root/'load-user.json'),'--developer-extension-class-name=check.AssessmentAuditObserver']
command.insert(1,'-Dtest.native.http=true')
command.insert(1,'-Dtest.audit.classes='+str(audit))
command.insert(1,'-Dtest.release.jar='+str(jar))
for name in ['account-task-audit.json','whole-audit.json','user-audit.json','native-ready.txt','native-user-options.json','native-project-options.json','native-http-results.json','native-bounty-results.json','extension-output.log','extension-errors.log']:(root/name).unlink(missing_ok=True)
proc=None;server=None
with (root/'load.log').open('w',encoding='utf-8') as log:
 try:
  task=Path('work/lab-audit').resolve();server=subprocess.Popen([java,'-cp',os.pathsep.join([str(task/'.build')]+[str(p) for p in (task/'.build-deps').glob('*.jar')]),'lab.enterprise.DevServer'],env=dict(os.environ,PORT='18299'),stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
  opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
  for _ in range(100):
   if server.poll() is not None:raise RuntimeError('test server startup failed')
   try:
    with opener.open('http://127.0.0.1:18299/health',timeout=1) as response:
     if response.status==200:break
   except Exception:time.sleep(.2)
  else:raise RuntimeError('test server startup timeout')
  proc=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,stdin=subprocess.PIPE,creationflags=subprocess.CREATE_NO_WINDOW);proc.stdin.write(b'y\n');proc.stdin.flush();proc.stdin.close()
  (root/'pid.txt').write_text(str(proc.pid))
  for _ in range(600):
   if (root/'native-ready.txt').exists() or proc.poll() is not None:break
   time.sleep(.25)
  print('Ready:',(root/'native-ready.txt').exists(),'exit:',proc.poll())
 finally:
  if proc and proc.poll() is None:
   proc.terminate()
   try:proc.wait(timeout=10)
   except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=5)
  if server and server.poll() is None:
   server.terminate()
   try:server.wait(timeout=10)
   except subprocess.TimeoutExpired:server.kill();server.wait(timeout=5)
if (root/'native-ready.txt').exists():
 print((root/'native-ready.txt').read_text(encoding='utf-8'))
 doc=json.loads((root/'native-user-options.json').read_text(encoding='utf-8'));print(json.dumps(doc.get('user_options',{}).get('extender',{}),ensure_ascii=False,indent=2))
 if (root/'native-http-results.json').exists():print((root/'native-http-results.json').read_text(encoding='utf-8'))
else:print((root/'load.log').read_text(encoding='utf-8',errors='replace')[-3500:]);raise SystemExit(1)
