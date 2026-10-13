"""Build temporary loopback lab and test the release JAR using API adapters.
Requires Python 3.10+, JDK 17+, matching community-security-lab beside burp-workbench-kr.
No Burp GUI, desktop browser, Docker, external target traffic or cloud VM is used.
"""
from pathlib import Path
import argparse,subprocess,os,time,zipfile,shutil,urllib.request,json,hashlib,sys
if hasattr(sys.stdout,'reconfigure'):sys.stdout.reconfigure(encoding='utf-8')
p=argparse.ArgumentParser();p.add_argument('--java',default='java');p.add_argument('--javac',default='javac');p.add_argument('--lab',type=Path);p.add_argument('--port',type=int,default=18099);args=p.parse_args()
here=Path(__file__).resolve().parent;root=here.parent.parent;lab=(args.lab or root.parent.parent/'community-security-lab').resolve();task=here/'.run';task.mkdir(exist_ok=True)
deps=task/'deps';deps.mkdir(exist_ok=True);site=task/'site';site.mkdir(exist_ok=True);classes=task/'classes';classes.mkdir(exist_ok=True)
jar=root/'dist/burp-workbench-kr-0.25.1.jar'
if not jar.exists():raise SystemExit('First run: python build_all.py --java <java> --javac <javac>')
if not 1024<=args.port<=65535:raise SystemExit('port range')
with zipfile.ZipFile(lab/'dist/ROOT.war') as war:
 for name in war.namelist():
  if name.startswith('WEB-INF/lib/') and name.endswith('.jar'):(deps/Path(name).name).write_bytes(war.read(name))
hashes=json.loads((here/'runtime-hashes.json').read_text())
for artifact,version,group in [('tomcat-embed-core','10.1.60','org/apache/tomcat/embed'),('jakarta.annotation-api','2.1.1','jakarta/annotation')]:
 destination=deps/f'{artifact}-{version}.jar'
 if not destination.exists():
  with urllib.request.urlopen(f'https://repo.maven.apache.org/maven2/{group}/{artifact}/{version}/{destination.name}',timeout=60) as response:destination.write_bytes(response.read())
 for_check=hashlib.sha256(destination.read_bytes()).hexdigest()
 if for_check!=hashes[destination.name]:raise SystemExit('Runtime checksum mismatch: '+destination.name)
cp=os.pathsep.join(str(x) for x in deps.glob('*.jar'))
subprocess.run([args.javac,'--release','17','-parameters','-encoding','UTF-8','-cp',cp,'-d',str(site)]+[str(x) for x in (lab/'src/main/java').rglob('*.java')],check=True)
shutil.copytree(lab/'src/main/resources',site,dirs_exist_ok=True)
testcp=os.pathsep.join([str(jar),str(root/'deps/montoya-2026.jar'),str(root/'deps/legacy-api.jar'),cp])
subprocess.run([args.javac,'--release','17','-encoding','UTF-8','-cp',testcp,'-d',str(classes)]+[str(x) for x in (root/'src/test/java').rglob('*.java')],check=True)
testcp=str(classes)+os.pathsep+testcp
subprocess.run([args.javac,'--release','17','-encoding','UTF-8','-cp',testcp,'-d',str(classes)]+[str(here/x) for x in ['CommunityExtensionAudit.java','AllFeaturesTest.java','AllFeaturesLauncher.java']],check=True)
server=None
with (task/'server.log').open('w',encoding='utf-8') as log:
 try:
  server=subprocess.Popen([args.java,'-cp',str(site)+os.pathsep+cp,'lab.enterprise.DevServer'],env=dict(os.environ,PORT=str(args.port)),stdout=log,stderr=subprocess.STDOUT)
  opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
  for _ in range(100):
   if server.poll() is not None:raise RuntimeError('lab startup failed; see '+str(task/'server.log'))
   try:
    with opener.open(f'http://127.0.0.1:{args.port}/health',timeout=1) as response:
     if response.status==200:break
   except Exception:time.sleep(.2)
  else:raise RuntimeError('lab startup timeout')
  result=subprocess.run([args.java,'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-cp',testcp,'kr.workbench.AllFeaturesLauncher',str(args.port),str(task/'results.json')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=300)
  (task/'tests.txt').write_text(result.stdout+result.stderr,encoding='utf-8')
  print(result.stdout+result.stderr)
  if result.returncode:raise SystemExit(result.returncode)
 finally:
  if server:
   server.terminate()
   try:server.wait(timeout=10)
   except subprocess.TimeoutExpired:server.kill();server.wait(timeout=5)
print('Results:',task/'results.json','; temporary server stopped.')
