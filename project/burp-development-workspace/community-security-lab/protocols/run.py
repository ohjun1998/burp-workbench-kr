"""Integrated loopback protocol services for Enterprise Lab."""
from pathlib import Path
import subprocess,signal,threading,os
from fixtures import Fixtures
root=Path(__file__).resolve().parent
runtime=Path(os.environ.get('LAB_PROTOCOL_RUNTIME',str(root/'runtime')));runtime.mkdir(parents=True,exist_ok=True)
cert=runtime/'cert.pem';key=runtime/'key.pem'
if not cert.exists() or not key.exists():
 subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','30','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 try:key.chmod(0o600)
 except OSError:pass
f=Fixtures(cert,key,runtime/'events.json');end=threading.Event()
for s in [signal.SIGINT,signal.SIGTERM]:signal.signal(s,lambda *_:end.set())
print('Enterprise Lab protocols ready:',f.tls,f.csd_vuln,f.csd_fixed,flush=True)
try:end.wait()
finally:f.close()
