#!/usr/bin/env python3
"""Rebuild Workbench and run its tests plus the community JS fixtures (JDK 17+)."""
from pathlib import Path
import os, subprocess, zipfile, json, sys
root = Path(__file__).resolve().parent
workbench = root / 'burp-workbench-kr/burp-workbench'
lab = root / 'community-security-lab'
subprocess.run([sys.executable, str(workbench/'build_all.py'), '--test'], cwd=workbench, check=True)
deps = lab/'.setup/deps'
classes = lab/'.setup/classes'
deps.mkdir(parents=True, exist_ok=True)
classes.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(lab/'dist/ROOT.war') as war:
    for name in war.namelist():
        if name.startswith('WEB-INF/lib/') and name.endswith('.jar'):
            (deps/Path(name).name).write_bytes(war.read(name))
classpath = os.pathsep.join(map(str, sorted(deps.glob('*.jar')) + [workbench/'dist/burp-workbench-kr-0.21.0.jar', workbench/'deps/montoya-2026.jar', workbench/'deps/legacy-api.jar']))
sources = [lab/'src/main/java/lab/enterprise/JsSyntaxLabController.java', lab/'validation/JsLabControllerTest.java', lab/'validation/JsLabEngineTest.java']
subprocess.run(['java','com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',classpath,'-d',str(classes), *map(str,sources)], check=True)
classpath += os.pathsep + str(classes) + os.pathsep + str(lab/'src/main/resources')
for name, args in [('lab.enterprise.JsLabControllerTest', []), ('kr.workbench.JsLabEngineTest', [str(lab/'src/main/resources/static/js-lab'), str(lab/'.setup/js-engine-results.json')])]:
    result = subprocess.run(['java','-Djava.awt.headless=true','-cp',classpath,name,*args],cwd=lab,capture_output=True,text=True,encoding="utf-8",errors="replace")
    (lab/'.setup'/f'{name.split(".")[-1]}.txt').write_text(result.stdout+result.stderr)
    print(result.stdout+result.stderr, end='')
    result.check_returncode()
report_path = lab/'.setup/js-engine-results.json'
report = json.loads(report_path.read_text())
report['engine'] = 'Workbench KR 0.21.0 rebuilt from supplied source'
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print('Verification complete. Docker and actual Burp GUI were not run.')
