#!/usr/bin/env python3
"""Compile an isolated adapter harness and analyze frozen evidence with a chosen JAR."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar',type=Path,required=True)
    p.add_argument('--corpus',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--java',type=Path,default=Path('/workspace/tools/jdk17/bin/java'))
    p.add_argument('--adapters',type=Path,default=Path('/workspace/simple-sites/harness-adapters'))
    a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True);a.out.chmod(0o700)
    if (a.out/'result.json').exists() or (a.out/'runtime.json').exists():
        p.error('Use a new output directory; an existing result is preserved')
    jar=a.jar.resolve();original=hashlib.sha256(jar.read_bytes()).hexdigest()
    classes=a.out/'classes';classes.mkdir(exist_ok=True)
    home=a.out/'host-home';home.mkdir(exist_ok=True)
    cp=os.pathsep.join(map(str,[jar,*sorted((ROOT/'simple/burp-workbench/deps').glob('*.jar')),a.adapters.resolve()]))
    harness=Path(__file__).with_name('OfflineDetectionAudit.java')
    harness_sha=hashlib.sha256(harness.read_bytes()).hexdigest()
    subprocess.run([str(a.java),'com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(harness)],check=True)
    with (a.out/'execution.log').open('w') as log:
        result=subprocess.run([str(a.java),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8',f'-Duser.home={home}',
            '-cp',str(classes)+os.pathsep+cp,'kr.workbench.OfflineDetectionAudit',str(a.corpus.resolve()),str((a.out/'result.json').resolve())],
            stdout=log,stderr=subprocess.STDOUT,timeout=600)
    unchanged=original==hashlib.sha256(jar.read_bytes()).hexdigest()
    meta=dict(jarSHA256=original,jarUnchanged=unchanged,exitCode=result.returncode,strictJava17=True,networkRequests=0,
        corpusSHA256=hashlib.sha256(a.corpus.read_bytes()).hexdigest(),harnessSHA256=harness_sha)
    (a.out/'runtime.json').write_text(json.dumps(meta,indent=2)+'\n')
    print(json.dumps(meta));return result.returncode or (0 if unchanged else 1)

if __name__=='__main__':
    raise SystemExit(main())
