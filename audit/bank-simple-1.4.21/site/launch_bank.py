#!/usr/bin/env python3
"""Launch the pinned original bank with local-only network access and fake credentials."""
import argparse,os,socket,sys
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--source',type=Path,required=True)
p.add_argument('--port',type=int,default=24904)
p.add_argument('--db-port',type=int,default=24914)
a=p.parse_args()
os.environ.update(DB_HOST='127.0.0.1',DB_PORT=str(a.db_port),DB_NAME='postgres',DB_USER='postgres',
                  DB_PASSWORD='local-bank-demo-only',DEEPSEEK_API_KEY='demo-key')
original_connect=socket.socket.connect
def local_connect(self,address):
    if isinstance(address,tuple) and address[0] not in ('127.0.0.1','localhost','::1'):
        raise OSError('This bank lab permits loopback connections only')
    return original_connect(self,address)
socket.socket.connect=local_connect
source=a.source.resolve();sys.path.insert(0,str(source));os.chdir(source)
from app import app,init_db
init_db()
app.run(host='127.0.0.1',port=a.port,debug=False,threaded=False)
