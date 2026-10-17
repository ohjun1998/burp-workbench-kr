#!/usr/bin/env python3
"""Run the separate native Swing frame check on the Windows CI desktop."""
import os
from pathlib import Path
import runpy
import subprocess

ROOT=Path(__file__).resolve().parents[1]
WB=ROOT/'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
version=runpy.run_path(str(WB/'release_info.py'))['VERSION']
classpath=os.pathsep.join(map(str,[WB/f'dist/burp-workbench-kr-{version}.jar',*(WB/'deps').glob('*.jar'),WB/'build/tests']))
subprocess.run(['java','-Djava.awt.headless=false','-cp',classpath,'kr.workbench.TestLauncher','kr.workbench.JsRepeaterWindowTest'],cwd=WB,check=True,timeout=60)
