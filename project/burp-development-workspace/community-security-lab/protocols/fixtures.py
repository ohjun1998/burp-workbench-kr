"""Loopback-only TLS/H2, one-use CSRF, and genuine body-ignoring CSD fixtures."""
import socketserver, socket, ssl, threading, secrets, json, os
from collections import deque
from pathlib import Path
import h2.connection, h2.config, h2.events

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

class Fixtures:
    def __init__(self, cert, key, output):
        self.output = Path(output); self.lock = threading.Lock(); self.events = deque(maxlen=2000); self.tokens = {}; self.servers=[]
        ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.load_cert_chain(cert,key);ctx.set_alpn_protocols(['h2','http/1.1'])
        owner=self
        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                try:
                    conn=self.request
                    if self.server.tls:
                        conn=ctx.wrap_socket(conn,server_side=True)
                        owner.record('tls',alpn=conn.selected_alpn_protocol(),port=self.server.server_address[1])
                        if conn.selected_alpn_protocol()=='h2':owner.http2(conn);return
                    owner.http1(conn,self.server.vulnerable)
                except (OSError,ssl.SSLError,ValueError,h2.exceptions.ProtocolError):pass
                finally:
                    try:conn.close()
                    except Exception:pass
        for name,tls,vulnerable in [('tls',True,False),('csd_vuln',False,True),('csd_fixed',False,False)]:
            ports={'tls':8443,'csd_vuln':8091,'csd_fixed':8092};server=Server((os.environ.get('LAB_BIND','127.0.0.1'),int(os.environ.get('LAB_'+name.upper()+'_PORT',ports[name]))),Handler);server.tls=tls;server.vulnerable=vulnerable
            self.servers.append(server);setattr(self,name,'https' if tls else 'http')
            setattr(self,name,getattr(self,name)+'://127.0.0.1:'+str(server.server_address[1]))
            threading.Thread(target=server.serve_forever,daemon=True).start()
    def record(self,event,**fields):
        with self.lock:
            self.events.append({'event':event,**fields})
    def http2(self,sock):
        sock.settimeout(30);h=h2.connection.H2Connection(config=h2.config.H2Configuration(client_side=False,header_encoding='utf-8'));h.initiate_connection();sock.sendall(h.data_to_send());paths={}
        while True:
            data=sock.recv(65535)
            if not data:return
            for e in h.receive_data(data):
                if isinstance(e,h2.events.RequestReceived):paths[e.stream_id]=dict(e.headers).get(':path','')
                elif isinstance(e,h2.events.DataReceived):h.acknowledge_received_data(e.flow_controlled_length,e.stream_id)
                elif isinstance(e,h2.events.StreamEnded):
                    body=b'fetch("/api/h2-confirmed");';h.send_headers(e.stream_id,[(':status','200'),('content-type','application/javascript'),('content-length',str(len(body)))]);h.send_data(e.stream_id,body,end_stream=True);self.record('h2_request',path=paths.get(e.stream_id,''),stream=e.stream_id)
            data=h.data_to_send()
            if data:sock.sendall(data)
    def http1(self,sock,vulnerable):
        sock.settimeout(20);r=sock.makefile('rb')
        while True:
            line=r.readline(16384)
            if not line:return
            parts=line.decode('latin1').strip().split(' ')
            if len(parts)!=3:return
            method,path,version=parts;headers={}
            while True:
                line=r.readline(16384)
                if line in (b'\r\n',b'\n',b''):break
                if b':' not in line:return
                k,v=line.decode('latin1').split(':',1);headers[k.lower()]=v.strip()
            n=int(headers.get('content-length','0'));body=b'' if vulnerable else r.read(n)
            code=200;payload=b'fixture response';mime='text/plain'
            if path.startswith('/csrf/form'):
                sid=headers.get('cookie','');token=secrets.token_hex(18)
                with self.lock:self.tokens[sid]=token
                payload=('<html><input name="csrf" value="'+token+'"></html>').encode();mime='text/html'
                self.record('csrf_issue',session=sid,token_fingerprint=token[:4])
            elif path.startswith('/csrf/write'):
                from urllib.parse import parse_qs
                value=parse_qs(body.decode()).get('csrf',[''])[0];sid=headers.get('cookie','')
                with self.lock:
                    expected=self.tokens.get(sid)
                    code=200 if expected and value==expected else 403
                    if code==200:self.tokens.pop(sid,None)
                payload=b'accepted' if code==200 else b'rejected';self.record('csrf_write',session=sid,status=code)
            elif path.startswith('/tls') or path.startswith('/race'):
                payload=b'fetch("/api/tls-confirmed");';mime='application/javascript'
            self.record('h1_request',method=method,path=path,vulnerable=vulnerable,declared_length=n,consumed_length=len(body),status=code)
            response=('HTTP/1.1 '+str(code)+' '+('OK' if code==200 else 'Forbidden')+'\r\nContent-Type: '+mime+'\r\nContent-Length: '+str(len(payload))+'\r\nConnection: keep-alive\r\n\r\n').encode()+payload
            sock.sendall(response)
            if headers.get('connection','').lower()=='close':return
    def close(self):
        for s in self.servers:s.shutdown();s.server_close()
        self.output.write_text(json.dumps(list(self.events),indent=2))
