"""Developer-only compile/package helper for this verification environment (ECJ)."""
from pathlib import Path
import subprocess,shutil,zipfile,os
r=Path(__file__).parent;b=r/'.build';cp=':'.join(str(x) for x in (r/'.build-deps').glob('*.jar'))
src=[str(x) for x in (r/'src/main/java').rglob('*.java')]
subprocess.run(['java','-jar',os.environ.get('ECJ_JAR',str(r.parent/'burp-workbench/deps/ecj.jar')),'-17','-parameters','-warn:none','-encoding','UTF-8','-cp',cp,'-d',str(b)]+src,check=True)
shutil.copytree(r/'src/main/resources',b,dirs_exist_ok=True)
d=r/'dist';d.mkdir(exist_ok=True)
with zipfile.ZipFile(d/'ROOT.war','w',zipfile.ZIP_DEFLATED) as z:
 for p in (r/'src/main/webapp').rglob('*'):
  if p.is_file():z.write(p,p.relative_to(r/'src/main/webapp'))
 for p in b.rglob('*'):
  if p.is_file() and (p.suffix=='.class' or 'static' in p.parts):z.write(p,'WEB-INF/classes/'+p.relative_to(b).as_posix())
 for p in (r/'.build-deps').glob('*.jar'):
  if not p.name.startswith(('tomcat-embed-','jakarta.annotation-api')):z.write(p,'WEB-INF/lib/'+p.name)
print('WAR built:',(d/'ROOT.war').stat().st_size)
