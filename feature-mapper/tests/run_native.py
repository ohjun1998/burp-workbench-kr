from pathlib import Path
import runpy
import argparse,subprocess,tempfile,os
p=argparse.ArgumentParser();p.add_argument('--burp-jar',required=True,type=Path);p.add_argument('--java',required=True);p.add_argument('--accept-community-license',action='store_true');p.add_argument('--output',type=Path,default=Path('build/native-verification'));a=p.parse_args()
r=Path(__file__).resolve().parents[1];a.output.mkdir(parents=True,exist_ok=True)
version=runpy.run_path(str(r/'release_info.py'))['VERSION']
with tempfile.TemporaryDirectory(prefix='mapper-native-') as folder:
 t=Path(folder);home=t/'home';home.mkdir();data=t/'data';data.mkdir();out=t/'result.txt'
 args=[a.java,'-Djava.awt.headless=true','-Duser.home='+str(home),'-Djava.util.prefs.userRoot='+str(home),'-Dqa.output='+str(out),'-cp',os.pathsep.join(map(str,[a.burp_jar.resolve(),r/f'dist/feature-mapper-kr-{version}.jar',r/'build/tests'])),'burp.StartBurp','--data-dir='+str(data),'--use-defaults','--disable-auto-update','--developer-extension-class-name=kr.featuremapper.NativeMapperCheck']
 with (a.output/'burp.log').open('w') as log:run=subprocess.run(args,input='y\n' if a.accept_community_license else None,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
 if out.exists():(a.output/'native-mapper-result.txt').write_bytes(out.read_bytes());print(out.read_text())
 if run.returncode or not out.exists() or 'NATIVE_MAPPER_SUCCESS' not in out.read_text():raise SystemExit(1)
