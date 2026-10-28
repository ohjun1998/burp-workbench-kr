#!/usr/bin/env python3
"""Prepare the three owned original apps without changing application source files.

Does not start services. Other four apps have independent source preparation records.
"""
import argparse,hashlib,json,subprocess,tarfile,urllib.request
from pathlib import Path
from zipfile import ZipFile

REPO=Path(__file__).resolve().parents[2]
JUICE_SHA='47420604f215eb412cc02890973ee208a63fc0c62fe7da1d64ac5743b12c1186'
JUICE_URL='https://github.com/juice-shop/juice-shop/releases/download/v20.3.0/juice-shop-20.3.0_node24_linux_x64.tgz'
VULN_SHA='4bb7cd5f46921959f034455e5615782481966177'
def download(url,path):
 if path.exists():return
 path.parent.mkdir(parents=True,exist_ok=True)
 with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Workbench-Simple-Local-Retest'}),timeout=120) as response,path.open('wb') as target:
  while chunk:=response.read(1024*1024):target.write(chunk)
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path('/workspace/simple-sites'));args=p.parse_args();root=args.root.resolve();root.mkdir(parents=True,exist_ok=True)
 juice=root/'juice-shop';juice.mkdir(exist_ok=True);archive=juice/'juice-shop-20.3.0_node24_linux_x64.tgz';download(JUICE_URL,archive)
 if digest(archive)!=JUICE_SHA:raise RuntimeError('Official Juice Shop archive hash mismatch')
 if not (juice/'source').exists():
  with tarfile.open(archive) as data:data.extractall(juice/'unpacked',filter='data')
  next(path for path in (juice/'unpacked').iterdir() if path.is_dir()).rename(juice/'source')
 bank=root/'vulnbank';source=bank/'source';source.mkdir(parents=True,exist_ok=True)
 if not (source/'.git').exists():
  for command in [['git','init',str(source)],['git','-C',str(source),'remote','add','origin','https://github.com/Commando-X/vuln-bank.git'],['git','-C',str(source),'fetch','--depth','1','origin',VULN_SHA],['git','-C',str(source),'checkout','--detach','FETCH_HEAD']]:subprocess.run(command,check=True)
 if subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()!=VULN_SHA:raise RuntimeError('Existing VulnBank source is another revision; preserved')
 loop=root/'loop';loop.mkdir(exist_ok=True);war=REPO/'project/burp-development-workspace/community-security-lab/dist/ROOT.war'
 if not (loop/'war').exists():
  with ZipFile(war) as data:data.extractall(loop/'war')
 for artifact,url in [('tomcat-embed-core-10.1.60.jar','https://repo.maven.apache.org/maven2/org/apache/tomcat/embed/tomcat-embed-core/10.1.60/tomcat-embed-core-10.1.60.jar'),('jakarta.annotation-api-2.1.1.jar','https://repo.maven.apache.org/maven2/jakarta/annotation/jakarta.annotation-api/2.1.1/jakarta.annotation-api-2.1.1.jar')]:download(url,loop/'deps'/artifact)
 print(json.dumps({'juiceArchiveSha256':JUICE_SHA,'vulnBankCommit':VULN_SHA,'loopWarSha256':digest(war),'root':str(root),'servicesStarted':False},indent=2))
if __name__=='__main__':main()
