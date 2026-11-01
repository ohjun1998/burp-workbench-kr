#!/usr/bin/env python3
"""Prepare, start, check and stop an isolated real VulnBank lab; never changes upstream files."""
import argparse,hashlib,json,os,shutil,signal,socket,subprocess,sys,time,urllib.error,urllib.request,zipfile
from pathlib import Path

HERE=Path(__file__).resolve().parent
COMMIT='4bb7cd5f46921959f034455e5615782481966177'
UPSTREAM='https://github.com/Commando-X/vuln-bank.git'
PASSWORD='LocalWorkbench2026!'

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def request(base,path,body=None,token=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:response=opener.open(req,timeout=15)
    except urllib.error.HTTPError as error:response=error
    with response:
        raw=response.read()
        try:data=json.loads(raw)
        except (ValueError,UnicodeDecodeError):data=raw.decode('utf-8','replace')
        return response.status,data

def run(command,**kwargs):subprocess.run([str(x) for x in command],check=True,**kwargs)

def verify_source(source):
    expected=json.loads((HERE/'upstream-manifest.json').read_text())
    if expected['commit']!=COMMIT:raise RuntimeError('Source identity mismatch')
    for name,sha in expected['trackedFiles'].items():
        if not (source/name).is_file() or digest(source/name)!=sha:
            raise RuntimeError('Original upstream file changed: '+name)
    return expected

def python(root):return root/'venv'/('Scripts/python.exe' if os.name=='nt' else 'bin/python')

def prepare(a):
    root=a.root.resolve();root.mkdir(parents=True,exist_ok=True);source=root/'source'
    if not source.exists():
        bundled=HERE/'upstream'
        if bundled.exists():shutil.copytree(bundled,source)
        else:
            run(['git','init',source]);run(['git','-C',source,'remote','add','origin',UPSTREAM])
            run(['git','-C',source,'fetch','--depth','1','origin',COMMIT])
            run(['git','-C',source,'checkout','--detach','FETCH_HEAD'])
    verify_source(source)
    if not python(root).is_file():run([sys.executable,'-m','venv',root/'venv'])
    run([python(root),'-m','pip','install','--disable-pip-version-check','-r',HERE/'requirements-local-lock.txt'])
    npm=root/'npm';npm.mkdir(exist_ok=True)
    for name in ['package.json','package-lock.json','db.mjs']:shutil.copy2(HERE/name,npm/name)
    environment=dict(os.environ,NPM_CONFIG_CACHE=str(root/'npm-cache'))
    run(['npm','ci','--ignore-scripts','--no-audit','--no-fund','--prefix',npm],env=environment)
    print(json.dumps({'prepared':True,'root':str(root),'upstreamCommit':COMMIT,'originalFilesVerified':True}))

def free_port(port):
    with socket.socket() as sock:sock.bind(('127.0.0.1',port))

def alive(pid):
    status=Path('/proc')/str(pid)/'status'
    if status.exists() and '\nState:\tZ' in status.read_text():return False
    try:os.kill(pid,0);return True
    except ProcessLookupError:return False

def start(a):
    root=a.root.resolve();source=root/'source';verify_source(source)
    if not python(root).is_file() or not (root/'npm/node_modules').is_dir():
        raise RuntimeError('Run prepare first')
    runtime=root/'runtime';runtime.mkdir(exist_ok=True)
    state=runtime/'services.json'
    if state.exists():
        old=json.loads(state.read_text())
        if any(alive(p['pid']) for p in old.get('processes',[])):raise RuntimeError('Owned services already running; use status')
    free_port(a.port);free_port(a.db_port)
    processes=[];base=f'http://127.0.0.1:{a.port}'
    def spawn(command,name,environment=None):
        log=(runtime/(name+'.log')).open('wb')
        proc=subprocess.Popen([str(x) for x in command],stdout=log,stderr=subprocess.STDOUT,
                              stdin=subprocess.DEVNULL,start_new_session=True,env=environment)
        log.close();processes.append({'name':name,'pid':proc.pid,'command':[str(x) for x in command]})
        state.write_text(json.dumps({'base':base,'processes':processes,'createdBy':'bank_site.py'},indent=2)+'\n')
        return proc
    try:
        database=spawn(['node',root/'npm/db.mjs'],'database',dict(os.environ,WB_BANK_DB_PORT=str(a.db_port)))
        deadline=time.monotonic()+45
        while True:
            if database.poll() is not None:raise RuntimeError('Database exited; inspect runtime/database.log')
            try:
                with socket.create_connection(('127.0.0.1',a.db_port),timeout=1):break
            except OSError:
                if time.monotonic()>deadline:raise RuntimeError('Database readiness timeout')
                time.sleep(.15)
        bank=spawn([python(root),HERE/'launch_bank.py','--source',source,'--port',a.port,'--db-port',a.db_port],'bank')
        deadline=time.monotonic()+45
        while True:
            if bank.poll() is not None:raise RuntimeError('Bank exited; inspect runtime/bank.log')
            try:
                code,health=request(base,'/healthz')
                if code==200 and health.get('database')=='up':break
            except (OSError,ValueError):pass
            if time.monotonic()>deadline:raise RuntimeError('Bank readiness timeout')
            time.sleep(.15)
        actors=[]
        for name in ['admin','wb_alice','wb_bob']:
            if name!='admin':
                code,data=request(base,'/register',{'username':name,'password':PASSWORD})
                if code!=200:raise RuntimeError('Account registration failed: '+name+' '+str(code))
            code,data=request(base,'/login',{'username':name,'password':'admin123' if name=='admin' else PASSWORD})
            if code!=200 or not data.get('token'):raise RuntimeError('Normal login failed: '+name)
            actors.append({'name':name,'token':data['token'],'account':data['accountNumber'],'uid':data['debug_info']['user_id']})
        private=runtime/'private-state.json';private.write_text(json.dumps({'base':base,'actors':actors,'password':PASSWORD},indent=2)+'\n');private.chmod(0o600)
        checks=[]
        for path in ['/','/login','/static/dashboard.js','/static/openapi.json','/healthz']:
            code,_=request(base,path);checks.append({'path':path,'status':code});assert code==200,(path,code)
        code,data=request(base,'/check_balance/'+actors[1]['account']);assert code==200 and 'balance' in data
        checks.append({'path':'/check_balance/<alice>','status':code,'balanceReturned':True})
        source_record=verify_source(source)
        (runtime/'readiness.json').write_text(json.dumps({'result':'passed','base':base,'host':'127.0.0.1','dbPort':a.db_port,
          'sourceCommit':COMMIT,'upstreamTrackedFiles':len(source_record['trackedFiles']),'upstreamFilesUnchanged':True,
          'database':'PGlite PostgreSQL18.6 WASM compatibility layer, not official PostgreSQL13',
          'httpThreaded':False,'fakeActors':[x['name'] for x in actors],'checks':checks,'externalApplicationNetworkBlocked':True},indent=2)+'\n')
        print('BANK_READY '+base+' privateState='+str(private))
    except BaseException:
        for record in reversed(processes):
            try:os.kill(record['pid'],signal.SIGTERM)
            except ProcessLookupError:pass
        raise

def stop(a):
    root=a.root.resolve();state=root/'runtime/services.json'
    if not state.exists():print('No owned services state');return
    data=json.loads(state.read_text())
    if data.get('createdBy')!='bank_site.py':raise RuntimeError('Unrecognized state file')
    for record in reversed(data['processes']):
        pid=record['pid'];command=Path('/proc')/str(pid)/'cmdline'
        if not alive(pid):continue
        if command.exists() and str(root).encode() not in command.read_bytes():
            raise RuntimeError('PID was reused by another process; preserved: '+str(pid))
        os.kill(pid,signal.SIGTERM)
    deadline=time.monotonic()+12
    while any(alive(p['pid']) for p in data['processes']):
        if time.monotonic()>deadline:raise RuntimeError('Owned services did not stop; preserved for inspection')
        time.sleep(.1)
    print('Stopped only the owned bank/database services')

def status(a):
    root=a.root.resolve();state=root/'runtime/services.json'
    if not state.exists():raise RuntimeError('No owned lab has started')
    data=json.loads(state.read_text());code,health=request(data['base'],'/healthz')
    assert code==200 and health.get('database')=='up'
    assert all(alive(p['pid']) for p in data['processes'])
    print(json.dumps({'ready':True,'base':data['base'],'database':health['database']}))

def package(a):
    source=a.root.resolve()/'source';metadata=verify_source(source)
    if not a.output:raise RuntimeError('package requires --output path')
    output=a.output.resolve()
    if output.exists():raise RuntimeError('Preserve existing package')
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
        for name in ['bank_site.py','launch_bank.py','db.mjs','package.json','package-lock.json','requirements-local-lock.txt','upstream-manifest.json','README_KR.md']:
            archive.write(HERE/name,'bank-site/'+name)
        for name in metadata['trackedFiles']:archive.write(source/name,'bank-site/upstream/'+name)
    with zipfile.ZipFile(output) as archive:assert archive.testzip() is None
    print(json.dumps({'archive':str(output),'sha256':digest(output),'upstreamCommit':COMMIT,'runtimeAuthAndDependenciesIncluded':False}))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('command',choices=['prepare','start','status','stop','package'])
    p.add_argument('--root',type=Path,default=Path.cwd()/'.bank-runtime-1.4.21')
    p.add_argument('--port',type=int,default=24904);p.add_argument('--db-port',type=int,default=24914)
    p.add_argument('--output',type=Path)
    a=p.parse_args();globals()[a.command](a)
if __name__=='__main__':main()
