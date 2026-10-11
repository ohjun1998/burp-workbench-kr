from pathlib import Path
import subprocess,os,time,urllib.request
r=Path(__file__).parent;cp=str(r/'.build')+':'+':'.join(str(p) for p in (r/'.build-deps').glob('*.jar'))
with (r/'.build/ui-server.log').open('w') as log:
 p=subprocess.Popen(['java','-cp',cp,'lab.enterprise.DevServer'],stdout=log,stderr=log)
 try:
  for i in range(120):
   try:
    if urllib.request.urlopen('http://127.0.0.1:8088/health',timeout=1).status==200:break
   except Exception:time.sleep(.2)
  env=dict(os.environ,CHROME_PATH='/workspace/scratch/def99e30c3ca/browser/chrome-headless-shell-linux64/chrome-headless-shell')
  subprocess.run(['node',str(r/'ui-tests.cjs')],env=env,check=True)
 finally:p.terminate();p.wait(timeout=10)
