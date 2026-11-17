#!/usr/bin/env python3
"""Use the same harness/fixtures/display for baseline and candidate actual native windows."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,struct,zlib
ROOT=Path('/workspace/burp-workbench-kr')
WORK=Path('/workspace/tools/burp-workbench/strix-1.0.1-native')
JAVA=Path('/workspace/tools/burp-workbench/jdk-17.0.21+9/bin/java')
BASELINE=Path('/workspace/tools/burp-workbench/completion-1.4.21/public-downloads/03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d/0-burp-workbench-simple-1.4.21.jar')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--candidate',type=Path,default=ROOT/'simple/downloads/strix/1.0.1/burp-workbench-strix-1.0.1.jar');p.add_argument('--out',type=Path,default=WORK/'capture');p.add_argument('--sizes',nargs='+',default=['1600x1000','1280x800']);p.add_argument('--themes',nargs='+',default=['light','dark']);p.add_argument('--compile-only',action='store_true');a=p.parse_args()
 jar=a.candidate.resolve();assert jar.is_file(),jar;classes=WORK/'classes';classes.mkdir(exist_ok=True)
 common=[classes,ROOT/'simple/burp-workbench/build/tests',*sorted((ROOT/'simple/burp-workbench/deps').glob('*.jar'))]
 cp=lambda artifact:os.pathsep.join(map(str,[artifact,*common]))
 subprocess.run([str(JAVA),'com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',cp(jar),'-d',str(classes),str(WORK/'Strix1421Native.java')],check=True)
 if a.compile_only:return
 baseline_hash=sha(BASELINE);candidate_hash=sha(jar);runs=[]
 for theme in a.themes:
  for size in a.sizes:
   w,h=map(int,size.split('x'))
   for edition,artifact in [('baseline-simple-1.4.21',BASELINE),('strix',jar)]:
    out=a.out/edition/theme/size;out.mkdir(parents=True,exist_ok=True)
    home=out/'.host-home';cache=out/'.font-cache';home.mkdir(exist_ok=True);cache.mkdir(exist_ok=True)
    command=[str(JAVA),'-Djava.awt.headless=false','-Dfile.encoding=UTF-8','-Duser.home='+str(home.resolve()),'-cp',cp(artifact),'kr.workbench.Strix1421Native',str(out.resolve()),edition,theme,str(w),str(h)]
    env={**os.environ,'DISPLAY':':97','XDG_CACHE_HOME':str(cache.resolve())}
    with (out/'execution.log').open('w') as log:subprocess.run(command,cwd=ROOT,check=True,stdout=log,stderr=subprocess.STDOUT,env=env,timeout=100)
    report=json.loads((out/'native-report.json').read_text());assert report['jarSha256']==(baseline_hash if edition.startswith('baseline') else candidate_hash);assert report['version']==('1.4.21' if edition.startswith('baseline') else '1.0.1')
    for v in report['views']:
     image=out/v['png'];v['sha256']=sha(image)
     data=image.read_bytes();assert data[:8]==b'\x89PNG\r\n\x1a\n';assert struct.unpack('>II',data[16:24])==(v['width'],v['height']);i=8
     while i<len(data):
      n=struct.unpack('>I',data[i:i+4])[0];payload=data[i+4:i+8+n];assert zlib.crc32(payload)&0xffffffff==struct.unpack('>I',data[i+8+n:i+12+n])[0];i+=n+12
    (out/'native-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');runs.append(report)
 comparisons=[];differences=[]
 for theme in a.themes:
  for size in a.sizes:
   before=json.loads((a.out/'baseline-simple-1.4.21'/theme/size/'native-report.json').read_text());after=json.loads((a.out/'strix'/theme/size/'native-report.json').read_text())
   for filename in ['01-main.png','02-dashboard.png']:
    b=next(x for x in before['views'] if x['png']==filename);c=next(x for x in after['views'] if x['png']==filename)
    bmap={x['key']:x for x in b['controls']};cmap={x['key']:x for x in c['controls']}
    missing=sorted(set(bmap)-set(cmap));added=sorted(set(cmap)-set(bmap));changes=[]
    for key in sorted(set(bmap)&set(cmap)):
     old,new=bmap[key],cmap[key]
     for field in ['x','y','width','height']:
      if old[field]!=new[field]:changes.append({'key':key,'field':field,'baseline':old[field],'candidate':new[field]})
    primary=[x for x in c['controls'] if x['name'] in ['wb.navigation.primary.inspect','wb.navigation.primary.dashboard']]
    assert len(primary)==2 and all(x['height']==42 for x in primary),primary
    row={'theme':theme,'size':size,'view':filename,'commonNamedComponents':len(set(bmap)&set(cmap)),'missing':missing,'added':added,'rectangleDifferences':changes,'primaryRows':primary}
    comparisons.append(row)
    if missing or added or changes:differences.append(row)
 result={'result':'passed' if not differences else 'review_required','baselineJarSha256':baseline_hash,'candidateJarSha256':candidate_hash,'baselineVersion':'1.4.21','candidateVersion':'1.0.1','captureMode':'Native Linux Xorg/xfwm4 windows captured with Robot','nativeBurp':False,'syntheticHostAndPackets':True,'targetRequests':0,'strixExecutions':0,'modelCalls':0,'screenshots':sum(len(x['views']) for x in runs),'comparisons':comparisons,'differentComparisons':len(differences),'note':'Exact component-rectangle comparison of the existing main/dashboard views. This is not pixel equality or real Burp/model verification.'}
 (a.out/'native-comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='comparisons'},ensure_ascii=False,indent=2))
 if differences:raise SystemExit(2)
if __name__=='__main__':main()
