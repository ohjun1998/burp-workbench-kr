#!/usr/bin/env python3
"""Compile and boot the real embedded upstream JAR with synthetic Montoya, no target traffic."""
from pathlib import Path
import os, subprocess, sys
r=Path(__file__).resolve().parents[1]
w=r/'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
sys.path.insert(0,str(w))
from release_info import VERSION
out=w/'build/tests';out.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join(map(str,[w/f'dist/burp-workbench-kr-{VERSION}.jar',w/'deps/montoya-2026.jar',w/'deps/legacy-api.jar',w/'deps/rhino.jar',w/'deps/brotli-dec-0.1.2.jar']))
subprocess.run(['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(out),*[str(p) for p in sorted((w/'src/test/java').rglob('*.java'))]],check=True)
evidence=r/'build/custom-agent-check';evidence.mkdir(parents=True,exist_ok=True)
for name in ['CustomAgentTest','CustomAgentBootTest']:
    cmd=['java','-Djava.awt.headless=true',f'-Dcustom.agent.capture={evidence}/upstream-embedded.png','-cp',cp+os.pathsep+str(out),'kr.workbench.TestLauncher','kr.workbench.'+name]
    result=subprocess.run(cmd,cwd=w,text=True,capture_output=True)
    (evidence/(name+'.log')).write_text(result.stdout+result.stderr)
    print(result.stdout+result.stderr,flush=True)
    if result.returncode:raise SystemExit(result.returncode)
