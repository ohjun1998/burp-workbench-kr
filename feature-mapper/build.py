#!/usr/bin/env python3
"""Build the standalone JAR, run meaningful tests, and package reproducible downloads."""
from pathlib import Path
import argparse,hashlib,os,shutil,subprocess,sys,zipfile,json
r=Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--test',action='store_true');p.add_argument('--java',default='java');a=p.parse_args()
api=r/'deps/montoya-api-2026.7.jar';expected='cb852b7b883290fcc1d21b13d80fa10f5b13a0f57335d710d2b1e5956ecb2a94'
if not api.exists() or hashlib.sha256(api.read_bytes()).hexdigest()!=expected:raise SystemExit('Montoya API dependency checksum mismatch')
classes=r/'build/classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir(parents=True)
compiler=[a.java,'com.sun.tools.javac.Main','--release','17','-encoding','UTF-8']
subprocess.run(compiler+['-cp',str(api),'-d',str(classes)]+[str(x) for x in sorted((r/'src/main/java').rglob('*.java'))],check=True)
dist=r/'dist';dist.mkdir(exist_ok=True)
def archive(path,files):
 with zipfile.ZipFile(path,'w') as z:
  for name,content in sorted(files.items()):
   info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;z.writestr(info,content)
jar=dist/'feature-mapper-kr-0.1.0.jar'
files={x.relative_to(classes).as_posix():x.read_bytes() for x in classes.rglob('*.class')}
files['META-INF/MANIFEST.MF']=b'Manifest-Version: 1.0\r\nImplementation-Title: Feature Mapper KR\r\nImplementation-Version: 0.1.0\r\n\r\n';files['LICENSE']= (r/'LICENSE').read_bytes();archive(jar,files)
if a.test:
 tests=r/'build/tests';shutil.rmtree(tests,ignore_errors=True);tests.mkdir()
 cp=os.pathsep.join(map(str,[api,classes]))
 subprocess.run(compiler+['-cp',cp,'-d',str(tests)]+[str(x) for x in sorted((r/'src/test/java').rglob('*.java'))],check=True)
 run=subprocess.run([a.java,'-Djava.awt.headless=true','-cp',cp+os.pathsep+str(tests),'kr.featuremapper.MapperTest'],capture_output=True,text=True,encoding='utf-8',timeout=90)
 (r/'build/test-results.txt').write_text(run.stdout+run.stderr);print(run.stdout+run.stderr)
 if run.returncode:raise SystemExit(run.returncode)
 subprocess.run(['node','--test',str(r/'tests/browser-helper.test.mjs')],check=True)
helper={x.relative_to(r/'browser-helper').as_posix():x.read_bytes() for x in (r/'browser-helper').rglob('*') if x.is_file()};archive(dist/'feature-mapper-browser-0.1.0.zip',helper)
source={x.relative_to(r).as_posix():x.read_bytes() for root in ['src','browser-helper','tests','deps'] for x in (r/root).rglob('*') if x.is_file()}
for name in ['build.py','README_KR.md','LICENSE','THIRD_PARTY_NOTICES.md']:
 if (r/name).exists():source[name]=(r/name).read_bytes()
archive(dist/'feature-mapper-kr-0.1.0-source.zip',source)
checksums={x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in sorted(dist.glob('*')) if x.suffix in ['.jar','.zip']}
(dist/'SHA256SUMS').write_text(''.join(h+'  '+n+'\n' for n,h in checksums.items()))
(dist/'manifest.json').write_text(json.dumps({'version':'0.1.0','artifacts':checksums,'api_sha256':expected},indent=2)+'\n')
print('Built',jar,'and browser/source ZIPs')
