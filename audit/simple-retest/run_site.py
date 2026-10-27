#!/usr/bin/env python3
"""Run one immutable Simple JAR against an already running original local application."""
import argparse,hashlib,json,os,signal,subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
WB=ROOT/'simple/burp-workbench'
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--jar',type=Path,required=True)
p.add_argument('--config',type=Path,required=True)
p.add_argument('--out',type=Path,required=True)
p.add_argument('--katana',type=Path,default=Path('/workspace/tools/katana/1.8.0/katana'))
p.add_argument('--java',type=Path,default=Path('/workspace/tools/jdk17/bin/java'))
p.add_argument('--adapters',type=Path,default=Path('/workspace/simple-sites/harness-adapters'))
args=p.parse_args();out=args.out.resolve()
if any((parent/'.stop-new-runs').exists() for parent in out.parents):
 print(json.dumps({'result':'SKIPPED_QUEUE_STOP','output':str(out)}));raise SystemExit(75)
out.mkdir(parents=True,exist_ok=True)
out.chmod(0o700)
config_snapshot=out/'private-config.json';config_snapshot.write_bytes(args.config.read_bytes());config_snapshot.chmod(0o600)
jar=args.jar.resolve();before=hashlib.sha256(jar.read_bytes()).hexdigest();classes=out/'.classes';classes.mkdir(exist_ok=True)
cp=os.pathsep.join(map(str,[jar,*sorted((WB/'deps').glob('*.jar')),args.adapters.resolve()]))
subprocess.run([str(args.java),'com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(Path(__file__).with_name('SimpleSitesAudit.java'))],check=True)
home=out/'.host-home';temporary=out/'.tmp';home.mkdir(exist_ok=True);temporary.mkdir(exist_ok=True)
with (out/'execution.log').open('w') as log:
 process=subprocess.Popen([str(args.java),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8',f'-Duser.home={home}',f'-Djava.io.tmpdir={temporary}','-cp',str(classes)+os.pathsep+cp,'kr.workbench.SimpleSitesAudit',str(args.config.resolve()),str(out),str(args.katana.resolve())],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 try:code=process.wait(timeout=240)
 except subprocess.TimeoutExpired:
  try:os.killpg(process.pid,signal.SIGTERM)
  except ProcessLookupError:pass
  try:process.wait(timeout=5)
  except subprocess.TimeoutExpired:
   try:os.killpg(process.pid,signal.SIGKILL)
   except ProcessLookupError:pass
   process.wait()
  code=124
after=hashlib.sha256(jar.read_bytes()).hexdigest();meta={'jar':str(jar),'jarSHA256':before,'jarUnchanged':before==after,'java':str(args.java),'strictJava17':True,'config':str(args.config.resolve()),'exitCode':code}
(out/'runtime.json').write_text(json.dumps(meta,indent=2)+'\n')
if (out/'run.json').exists():
 data=json.loads((out/'run.json').read_text());data['jarSHA256']=before
 data['transportResponses']=sum(row.get('status',0)>0 for row in data.get('transport',[]));data['transportErrors']=sum(row.get('status',0)==0 for row in data.get('transport',[]))
 (out/'run.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n');print(json.dumps({key:data.get(key) for key in ['version','siteId','scenario','state','sent','actualTransportRequests','transportResponses','transportErrors','pending','partial']},ensure_ascii=False))
else:print(json.dumps(meta))
raise SystemExit(code or (0 if before==after else 1))
