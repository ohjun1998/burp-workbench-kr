"""Real loopback TLS, ALPN/H2, one-use CSRF and CSD fixture checks."""
from pathlib import Path
import json,os,re,socket,ssl
import h2.connection,h2.config,h2.events
out=Path(os.environ['TEST_OUTPUT']);tests=[]
def check(name,ok):
 tests.append({'test':name,'passed':bool(ok)});print(('PASS ' if ok else 'FAIL ')+name,flush=True)
 if not ok:raise AssertionError(name)
try:
 ctx=ssl.create_default_context(cafile=str(out/'protocol/cert.pem'));ctx.set_alpn_protocols(['http/1.1'])
 with ctx.wrap_socket(socket.create_connection(('127.0.0.1',18443),5),server_hostname='localhost') as s:
  check('TLS certificate validated against isolated fixture certificate',s.version() in ['TLSv1.2','TLSv1.3'])
  check('TLS ALPN selects HTTP/1.1',s.selected_alpn_protocol()=='http/1.1');s.sendall(b'GET /tls?via=python-h1 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n');data=b''
  while chunk:=s.recv(8192):data+=chunk
  check('TLS HTTP/1.1 returns real fixture response',b'200 OK' in data and b'/api/tls-confirmed' in data)
 ctx.set_alpn_protocols(['h2'])
 with ctx.wrap_socket(socket.create_connection(('127.0.0.1',18443),5),server_hostname='localhost') as s:
  check('TLS ALPN selects HTTP/2',s.selected_alpn_protocol()=='h2');s.settimeout(5)
  h=h2.connection.H2Connection(config=h2.config.H2Configuration(header_encoding='utf-8'));h.initiate_connection();h.send_headers(1,[(':method','GET'),(':scheme','https'),(':authority','localhost'),(':path','/tls?via=python-h2')],end_stream=True);s.sendall(h.data_to_send());done=False;status=None;body=b''
  while not done:
   data=s.recv(65535)
   if not data:raise AssertionError('Unexpected H2 EOF')
   for e in h.receive_data(data):
    if isinstance(e,h2.events.ResponseReceived):status=dict(e.headers)[':status']
    if isinstance(e,h2.events.DataReceived):body+=e.data;h.acknowledge_received_data(e.flow_controlled_length,e.stream_id)
    if isinstance(e,h2.events.StreamEnded):done=True
   if data:=h.data_to_send():s.sendall(data)
  check('HTTP/2 stream returns 200',status=='200');check('HTTP/2 stream body reconstructed',body==b'fetch("/api/h2-confirmed");')
 marker=b'GET /marker HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n'
 for port,vuln in [(18091,True),(18092,False)]:
  with socket.create_connection(('127.0.0.1',port),5) as s:
   s.settimeout(.8);s.sendall(b'POST /csd HTTP/1.1\r\nHost: localhost\r\nContent-Length: '+str(len(marker)).encode()+b'\r\n\r\n'+marker);data=b''
   try:
    while chunk:=s.recv(8192):data+=chunk
   except socket.timeout:pass
   check('CSD '+('vulnerable body becomes second request' if vuln else 'fixed body consumed as declared'),data.count(b'HTTP/1.1 200')==(2 if vuln else 1))
 import urllib.request,urllib.error,urllib.parse
 def request(path,body=None,cookie='OWNED_FIXTURE_A'):
  req=urllib.request.Request('http://127.0.0.1:18092'+path,data=None if body is None else body.encode(),headers={'Cookie':cookie,'Content-Type':'application/x-www-form-urlencoded'})
  try:r=urllib.request.urlopen(req,timeout=5)
  except urllib.error.HTTPError as e:r=e
  return r.status,r.read().decode()
 token=re.search('value="([^"]+)"',request('/csrf/form')[1]).group(1)
 check('one-use CSRF rejects foreign session',request('/csrf/write','csrf='+token,'OWNED_FIXTURE_B')[0]==403)
 check('one-use CSRF accepts owner token',request('/csrf/write','csrf='+token)[0]==200)
 check('one-use CSRF rejects replay',request('/csrf/write','csrf='+token)[0]==403)
finally:(out/'protocol-checks.json').write_text(json.dumps({'tests':tests,'passed':sum(x['passed'] for x in tests),'failed':sum(not x['passed'] for x in tests),'method':'actual Python TLS/H2/socket HTTP against project protocol fixtures'},indent=2)+'\n')
