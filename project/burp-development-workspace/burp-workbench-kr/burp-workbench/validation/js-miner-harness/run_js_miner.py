from pathlib import Path
import os,subprocess,socket,time,threading,json,urllib.request,urllib.parse,re
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
r=Path(__file__).resolve().parent;root=r.parent;out=root/'burp-workbench/validation/actual-js-miner'/os.environ.get('JS_RUN_NAME','run-'+time.strftime('%Y%m%d-%H%M%S'));out.mkdir(parents=True,exist_ok=True)
class Boundary(BaseHTTPRequestHandler):
 def do_GET(self):
  (out/'outside-requests.txt').open('a').write(self.path+'\n');self.send_response(200);self.end_headers();self.wfile.write(b'outside')
 def log_message(self,*args):pass
server=ThreadingHTTPServer(('127.0.0.1',19111),Boundary);threading.Thread(target=server.serve_forever,daemon=True).start()
env=dict(os.environ,LD_LIBRARY_PATH=str(r/'xroot/usr/lib/x86_64-linux-gnu'),DISPLAY='127.0.0.1:97')
from fontTools.ttLib import TTFont
fontdir=r/'fonts';fontdir.mkdir(exist_ok=True);f=TTFont(root/'community-runtime/WEB-INF/classes/static/lab-korean.woff');f.flavor=None;f.save(fontdir/'LabKorean.ttf')
fontconfig=r/'fontconfig.xml';fontconfig.write_text(f'<fontconfig><include>/etc/fonts/fonts.conf</include><dir>{fontdir}</dir><cachedir>{r}/font-cache</cachedir></fontconfig>');env['FONTCONFIG_FILE']=str(fontconfig)
procs=[]
try:
 x=subprocess.Popen([str(r/'xroot/usr/bin/Xvfb'),':97','-screen','0','1600x1000x24','-nolisten','unix','-nolisten','local','-listen','tcp','-ac','-noreset','-nolock','-fp','built-ins','-xkbdir',str(r/'xroot/usr/share/X11/xkb')],env=env,stdout=open(out/'xvfb.log','w'),stderr=subprocess.STDOUT);procs.append(x)
 for _ in range(30):
  try:
   with socket.create_connection(('127.0.0.1',6097),timeout=.3):break
  except OSError:time.sleep(.2)
 else:raise RuntimeError('display unavailable: '+(out/'xvfb.log').read_text())
 cp=str(root/'community-runtime/WEB-INF/classes')+':'+str(root/'community-runtime/WEB-INF/lib/*')+':'+str(root/'payload-runner/deps/tomcat-embed-core-10.1.60.jar')+':'+str(root/'payload-runner/deps/jakarta.annotation-api-2.1.1.jar')
 lab=subprocess.Popen(['java','-Xmx256m','-cp',cp,'lab.enterprise.DevServer'],env=dict(env,PORT='19088'),stdout=open(out/'community.log','w'),stderr=subprocess.STDOUT);procs.append(lab)
 for _ in range(100):
  try:urllib.request.urlopen('http://127.0.0.1:19088/health',timeout=.3);break
  except Exception:time.sleep(.2)
 java=next((r/'java21').glob('*/bin/java'));cp=str(r/'burp.jar')+':'+str(r/'harness')
 command=[str(java),'-Xmx1800m','-Dsun.java2d.xrender=false','-Djava.util.prefs.userRoot='+str(r/'js-prefs'),'-Duser.home='+str(r/'test-home'),'-Dharness.autoStart=true','-Dharness.dir='+str(out),'-cp',cp,'BurpHarness','--use-defaults','--developer-extension-class-name=validation.LiveJsMiner','--disable-auto-update','--data-dir='+str(r/'js-burp-data'),'--user-config-file='+str(r/'js-user-config.json')]
 burp=subprocess.Popen(command,env=env,stdout=open(out/'burp.log','w'),stderr=subprocess.STDOUT);procs.append(burp)
 print('Actual Burp started, pid',burp.pid,flush=True);last='';deadline=time.monotonic()+240
 while time.monotonic()<deadline:
  if burp.poll() is not None:raise RuntimeError('Burp exited '+str(burp.returncode)+' '+(out/'burp.log').read_text()[-2000:])
  report=(out/'js-runtime-tests.txt').read_text() if (out/'js-runtime-tests.txt').exists() else ''
  if report!=last:print(report[len(last):][-4000:],flush=True);last=report
  if 'LIVE_VALIDATION_FINISHED' in report:print('SUCCESS',flush=True);break
  if 'VALIDATION_FAILURE' in report:raise RuntimeError(report[-2000:])
  time.sleep(1)
 else:raise TimeoutError('Inspect ui-state.txt and live-screen.png')
finally:
 for p in reversed(procs):
  if p.poll() is None:
   p.terminate()
   try:p.wait(timeout=8)
   except subprocess.TimeoutExpired:p.kill();p.wait()
 server.shutdown()
