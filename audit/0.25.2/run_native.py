#!/usr/bin/env python3
"""Run the release entry point in a separately installed Burp Community, isolated from user settings."""
from pathlib import Path
import argparse, os, socket, subprocess, tempfile
p=argparse.ArgumentParser()
p.add_argument('--burp-jar',required=True,type=Path)
p.add_argument('--java',required=True,help='Java 21+ executable for Burp')
p.add_argument('--compiler-java',default='java',help='JDK executable providing com.sun.tools.javac.Main')
p.add_argument('--accept-community-license',action='store_true',help='Explicitly accept the Community license on first launch')
p.add_argument('--output',type=Path,default=Path('native-capture-result.txt'))
a=p.parse_args()
root=Path(__file__).resolve().parents[2]
wb=root/'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
jar=wb/'dist/burp-workbench-kr-0.25.2.jar'
with socket.socket() as check:check.bind(('127.0.0.1',18182))
with tempfile.TemporaryDirectory(prefix='workbench-native-qa-') as folder:
 t=Path(folder);classes=t/'classes';classes.mkdir();home=t/'home';home.mkdir();data=t/'data';data.mkdir()
 cp=os.pathsep.join(map(str,[*wb.glob('deps/*.jar'),jar]))
 subprocess.run([a.compiler_java,'com.sun.tools.javac.Main','--release','17','-cp',cp,'-d',str(classes),str(Path(__file__).with_name('NativeCaptureCheck.java'))],check=True)
 runtime_cp=os.pathsep.join(map(str,[a.burp_jar.resolve(),classes,jar]))
 result=t/'result.txt'
 args=[a.java,'-Djava.awt.headless=true','-Duser.home='+str(home),'-Djava.util.prefs.userRoot='+str(home),'-Dqa.output='+str(result),'-cp',runtime_cp,'burp.StartBurp','--data-dir='+str(data),'--use-defaults','--disable-auto-update','--developer-extension-class-name=qa.NativeCaptureCheck']
 run=subprocess.run(args,input='y\n' if a.accept_community_license else None,text=True,timeout=180)
 if result.exists():a.output.write_bytes(result.read_bytes());print(result.read_text())
 if run.returncode or not result.exists() or 'NATIVE_CAPTURE_SUCCESS' not in result.read_text():raise SystemExit(1)
