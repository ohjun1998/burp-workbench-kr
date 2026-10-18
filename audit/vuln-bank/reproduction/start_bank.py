import os,sys,socket,ipaddress
os.environ.update(DB_HOST='127.0.0.1',DB_PORT='24532',DB_NAME='postgres',DB_USER='postgres',DB_PASSWORD='local',DEEPSEEK_API_KEY='demo-key')
# Permit only local database / local target traffic. No external API calls.
_original=socket.socket.connect
def connect(self,address):
 if isinstance(address,tuple) and address[0] not in ('127.0.0.1','localhost','::1'):raise OSError('Local lab blocks outbound connections')
 return _original(self,address)
socket.socket.connect=connect
target=os.environ['VULNBANK_DIR']
sys.path.insert(0,target)
os.chdir(target)
from app import app,init_db
init_db()
app.run(host='127.0.0.1',port=24540,debug=False,threaded=False)
