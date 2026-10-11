from pathlib import Path
import subprocess, os, json, hashlib, socket, time, urllib.request, argparse
p=argparse.ArgumentParser();p.add_argument('--workspace',type=Path,required=True);p.add_argument('--lab',type=Path,required=True);p.add_argument('--port',type=int,default=18299);a=p.parse_args()
root=a.workspace.resolve();lab=a.lab.resolve();here=Path(__file__).resolve().parent
wb=root/'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
runtime=wb/'validation/all-features/.run';out=here/'results';out.mkdir(exist_ok=True);classes=here/'classes';classes.mkdir(exist_ok=True);site=here/'site-classes';site.mkdir(exist_ok=True)
jar=wb/'dist/burp-workbench-kr-0.21.0.jar'
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
sources=[*sorted((wb/'src/main').rglob('*')), *sorted((lab/'src').rglob('*')),jar,lab/'dist/ROOT.war'];sources=[p for p in sources if p.is_file()];before={str(p):digest(p) for p in sources}
deps=os.pathsep.join(str(x) for x in sorted((runtime/'deps').glob('*.jar')))
cp=os.pathsep.join([str(jar),str(wb/'deps/montoya-2026.jar'),str(wb/'deps/legacy-api.jar'),str(runtime/'classes'),deps])
subprocess.run(['java','com.sun.tools.javac.Main','--release','17','-parameters','-encoding','UTF-8','-cp',deps,'-d',str(site),*[str(x) for x in (lab/'src/main/java').rglob('*.java')]],check=True)
subprocess.run(['java','com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(here/'BountyAudit.java')],check=True)
with socket.socket() as s:s.bind(('127.0.0.1',a.port))
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}));server=None
with (out/'server.log').open('w') as log:
 try:
  server=subprocess.Popen(['java','-cp',os.pathsep.join([str(site),str(lab/'src/main/resources'),deps]),'lab.enterprise.DevServer'],env=dict(os.environ,PORT=str(a.port)),stdout=log,stderr=subprocess.STDOUT)
  for _ in range(100):
   if server.poll() is not None:raise RuntimeError('server failed')
   try:
    with opener.open(f'http://127.0.0.1:{a.port}/health',timeout=1) as r:
     if r.status==200:break
   except OSError:time.sleep(.2)
  else:raise RuntimeError('startup timeout')
  with (out/'audit.log').open('w') as auditlog:result=subprocess.run(['java','-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',str(classes)+os.pathsep+cp,'kr.workbench.BountyAudit',str(a.port),str(out)],stdout=auditlog,stderr=subprocess.STDOUT,timeout=180)
 finally:
  if server:
   server.terminate()
   try:server.wait(timeout=10)
   except subprocess.TimeoutExpired:server.kill();server.wait()
  after={str(p):digest(p) for p in sources};unchanged=before==after
  with socket.socket() as s:closed=s.connect_ex(('127.0.0.1',a.port))!=0
  (out/'integrity.json').write_text(json.dumps({'jarSha256':digest(jar),'warSha256':digest(lab/'dist/ROOT.war'),'productionFilesCompared':len(before),'productionFilesUnchanged':unchanged,'serverPortClosed':closed},indent=2))
print((out/'integrity.json').read_text());raise SystemExit(result.returncode)
