from pathlib import Path
import os,subprocess,time,socket,json,threading,urllib.parse,http.server,traceback
root=Path(os.environ['WORKBENCH_QA_ROOT']).resolve();runtime=root/'ui-runtime';out=runtime/'actual-ui';out.mkdir(exist_ok=True);procs=[]
for old in ('runtime-tests.txt','ui-state.txt'): (out/old).unlink(missing_ok=True)
class Fixture(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  url=urllib.parse.urlsplit(self.path);q=urllib.parse.parse_qs(url.query).get('q',[''])[0]
  print('FIXTURE',self.path,flush=True)
  if url.path=='/slow':time.sleep(1)
  body=q.encode();self.send_response(200);self.send_header('Content-Type','text/plain; charset=UTF-8');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
 def log_message(self,*args):pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',19089),Fixture);threading.Thread(target=server.serve_forever,daemon=True).start()
def spawn(args,name,env=None):
 f=(out/(name+'.log')).open('w');p=subprocess.Popen(args,stdout=f,stderr=subprocess.STDOUT,env=env,cwd=root);procs.append(p);return p
def waitport(port,p):
 for i in range(100):
  try:
   with socket.create_connection(('127.0.0.1',port),.3):return
  except OSError:
   if p.poll() is not None:raise RuntimeError(f'Process exited before port {port}: {p.returncode}')
   time.sleep(.2)
 raise RuntimeError(f'port {port} not ready')
try:
 env=os.environ.copy();env['LD_LIBRARY_PATH']=str(runtime/'root/usr/lib/x86_64-linux-gnu');env['DISPLAY']='127.0.0.1:97'
 fonts=out/'fonts.conf';fonts.write_text(f'<fontconfig><include>/etc/fonts/fonts.conf</include><dir>{runtime}/root/usr/share/fonts/opentype/noto</dir><cachedir>{out}/font-cache</cachedir></fontconfig>');env['FONTCONFIG_FILE']=str(fonts)
 xv=spawn([str(runtime/'root/usr/bin/Xvfb'),':97','-screen','0','1600x1000x24','-nolisten','unix','-nolisten','local','-listen','tcp','-ac','-noreset','-nolock','-fp','built-ins','-xkbdir',str(runtime/'root/usr/share/X11/xkb')],'xvfb',env);waitport(6097,xv)
 labenv=env.copy();labenv['PORT']='19088';lab=spawn(['java','-cp',str(runtime/'community/WEB-INF/classes')+':'+str(runtime/'community/WEB-INF/lib/*')+':'+str(runtime/'deps/*'),'lab.enterprise.DevServer'],'community',labenv);waitport(19088,lab)
 config=out/'user.json';config.write_text(json.dumps({'user_options':{'extender':{'extensions':[{'extension_file':str(root/'ui-project/burp-workbench/dist/burp-workbench-kr-0.8.0.jar'),'extension_type':'java','loaded':True,'name':'Burp Workbench KR 0.8.0'}]}}}))
 jre=str(runtime/'root/usr/lib/jvm/java-21-openjdk-amd64/bin/java');home=out/'home';home.mkdir(exist_ok=True)
 p=spawn([jre,'-Xmx1600m','-Dsun.java2d.xrender=false','-Djava.util.prefs.userRoot='+str(out/'prefs'),'-Duser.home='+str(home),'-Dharness.autoStart=true','-Dharness.dir='+str(out),'-cp',str(runtime/'burp.jar')+':'+str(runtime/'harness'),'BurpHarness','--use-defaults','--developer-extension-class-name=validation.UiNative','--disable-auto-update','--data-dir='+str(out/'burp-data'),'--user-config-file='+str(config)],'burp',env)
 end=time.monotonic()+400;previous=''
 while time.monotonic()<end:
  report=out/'runtime-tests.txt';content=report.read_text() if report.exists() else ''
  if content!=previous:print(content[len(previous):],end='',flush=True);previous=content
  if 'LIVE_VALIDATION_FINISHED' in content:print('NATIVE_UI_SUCCESS',flush=True);break
  if 'VALIDATION_FAILURE' in content:raise RuntimeError(content.splitlines()[-1])
  if p.poll() is not None:raise RuntimeError('Burp process exited '+str(p.returncode))
  time.sleep(1)
 else:raise RuntimeError('Native verification timed out')
finally:
 server.shutdown()
 for p in reversed(procs):
  p.terminate()
 for p in reversed(procs):
  try:p.wait(timeout=5)
  except subprocess.TimeoutExpired:p.kill()
