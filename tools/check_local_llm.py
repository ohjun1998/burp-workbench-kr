#!/usr/bin/env python3
"""Focused production client/Swing checks, with an explicit no-socket mode."""
from pathlib import Path
import argparse, subprocess
p=argparse.ArgumentParser()
p.add_argument('--java',default='java');p.add_argument('--javac',default='javac')
p.add_argument('--no-http',action='store_true')
a=p.parse_args()
root=Path(__file__).resolve().parents[1]
wb=root/'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
out=root/'build/local-llm-check';out.mkdir(parents=True,exist_ok=True)
sources=[wb/'src/main/java/kr/workbench'/f'{n}.java' for n in ('LocalLlmClient','LocalLlmPanel','MinerJson','MetadataExport')]
sources.append(wb/'src/test/java/kr/workbench/LocalLlmTest.java')
subprocess.run([a.javac,'--release','17','-encoding','UTF-8','-d',str(out),*map(str,sources)],check=True)
subprocess.run([a.java,'-Djava.awt.headless=true','-cp',str(out),'kr.workbench.LocalLlmTest',*(['--no-http'] if a.no_http else [])],check=True)
if a.no_http: print('HTTP, live Halogen and native Burp were NOT tested.')
