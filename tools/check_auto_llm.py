#!/usr/bin/env python3
"""Compile the automatic coordinator tests against the built production JAR."""
from pathlib import Path
import argparse,subprocess,os
p=argparse.ArgumentParser();p.add_argument('--java',default='java');p.add_argument('--javac',default='javac');a=p.parse_args()
r=Path(__file__).resolve().parents[1];w=r/'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
jar=w/'dist/burp-workbench-kr-0.55.0.jar';out=r/'build/auto-llm-check';out.mkdir(parents=True,exist_ok=True)
subprocess.run([a.javac,'--release','17','-encoding','UTF-8','-cp',str(jar),'-d',str(out),str(w/'src/test/java/kr/workbench/AutoLlmEngineTest.java')],check=True)
subprocess.run([a.java,'-Djava.awt.headless=true','-cp',os.pathsep.join((str(out),str(jar))),'kr.workbench.AutoLlmEngineTest'],check=True)
