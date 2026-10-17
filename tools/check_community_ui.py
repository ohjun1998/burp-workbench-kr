#!/usr/bin/env python3
"""Compile current lab sources and exercise the actual UI in isolated Chromium."""
import argparse, hashlib, json, os, shutil, socket, subprocess, time, urllib.request, uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
LAB=ROOT/'project/burp-development-workspace/community-security-lab'
RUNTIME=ROOT/'project/burp-development-workspace/burp-workbench-kr/burp-workbench/validation/all-features'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=18739)
    parser.add_argument('--java',default='java')
    parser.add_argument('--node',default='node')
    parser.add_argument('--out',type=Path,default=ROOT/'tmp/community-ui')
    args=parser.parse_args();args.out=args.out.resolve()
    with socket.socket() as probe:probe.bind(('127.0.0.1',args.port))
    hashes=json.loads((RUNTIME/'runtime-hashes.json').read_text());jars=[]
    for name,digest in hashes.items():
        path=RUNTIME/'.run/deps'/name
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise RuntimeError('Prepare verified runtime with python dev.py http-test: '+name)
        jars.append(str(path))
    site=args.out/'site'
    if site.exists():shutil.rmtree(site)
    site.mkdir(parents=True)
    cp=os.pathsep.join(jars)
    subprocess.run([args.java,'com.sun.tools.javac.Main','--release','17','-parameters','-encoding','UTF-8','-cp',cp,'-d',str(site)]+[str(p) for p in sorted((LAB/'src/main/java').rglob('*.java'))],check=True)
    shutil.copytree(LAB/'src/main/resources',site,dirs_exist_ok=True)
    environment=dict(os.environ,PORT=str(args.port),DB_URL='jdbc:h2:mem:ui_'+uuid.uuid4().hex+';MODE=Oracle;DB_CLOSE_DELAY=-1',DB_USER='sa',DB_PASSWORD='')
    base=f'http://127.0.0.1:{args.port}';server=None
    try:
        with (args.out/'server.log').open('w') as log:
            server=subprocess.Popen([args.java,'-cp',str(site)+os.pathsep+cp,'lab.enterprise.DevServer'],env=environment,stdout=log,stderr=subprocess.STDOUT)
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            for _ in range(100):
                if server.poll() is not None:raise RuntimeError('Lab startup failed; inspect server.log')
                try:
                    with opener.open(base+'/health',timeout=2) as response:
                        if response.status==200:break
                except OSError:pass
                time.sleep(.2)
            else:raise RuntimeError('Lab startup timeout')
            subprocess.run([args.node,str(ROOT/'tools/community_ui_checks.mjs'),base,str(args.out)],check=True,cwd=ROOT)
    finally:
        if server is not None:
            server.terminate()
            try:server.wait(timeout=10)
            except subprocess.TimeoutExpired:server.kill();server.wait(timeout=5)
        with socket.socket() as probe:closed=probe.connect_ex(('127.0.0.1',args.port))!=0
        result=args.out/'results.json'
        if result.exists():
            data=json.loads(result.read_text());data['serverPortClosed']=closed;result.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
        if not closed:raise RuntimeError('Owned UI server did not close')

if __name__=='__main__':main()
