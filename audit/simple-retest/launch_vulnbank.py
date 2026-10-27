#!/usr/bin/env python3
"""Run the unchanged pinned VulnBank application with a local PostgreSQL database."""
import argparse,os,socket,sys
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--source',type=Path,required=True)
p.add_argument('--port',type=int,default=24804)
p.add_argument('--db-port',type=int,default=24814)
p.add_argument('--db-name',default='vulnbank')
p.add_argument('--db-user',default='vulnbank')
args=p.parse_args()
if 'SIMPLE_RETEST_DB_PASSWORD' not in os.environ:
 p.error('Set SIMPLE_RETEST_DB_PASSWORD for this local-only test database.')
os.environ.update(DB_HOST='127.0.0.1',DB_PORT=str(args.db_port),DB_NAME=args.db_name,DB_USER=args.db_user,DB_PASSWORD=os.environ['SIMPLE_RETEST_DB_PASSWORD'],DEEPSEEK_API_KEY='demo-key')
original=socket.socket.connect
def local_connect(self,address):
 if isinstance(address,tuple) and address[0] not in ('127.0.0.1','localhost','::1'):
  raise OSError('Local retest blocks external application network connections')
 return original(self,address)
socket.socket.connect=local_connect
source=args.source.resolve();sys.path.insert(0,str(source));os.chdir(source)
from app import app,init_db
init_db()
app.run(host='127.0.0.1',port=args.port,debug=False,threaded=False)
