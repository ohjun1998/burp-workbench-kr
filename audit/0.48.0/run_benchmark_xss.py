"""Compile selected unmodified Benchmark servlets and compare two production engine JARs over HTTP."""
import argparse, hashlib, json, os, subprocess, time, urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--benchmark', type=Path, required=True)
parser.add_argument('--before', type=Path, required=True)
parser.add_argument('--after', type=Path, required=True)
parser.add_argument('--java', type=Path, required=True)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--browser', type=Path)
args = parser.parse_args()
benchmark, out = args.benchmark.resolve(), args.out.resolve()
out.mkdir(parents=True, exist_ok=True)
commit = subprocess.check_output(['git','rev-parse','HEAD'],cwd=benchmark,text=True).strip()
if commit != 'bc5ffeb09fb18ab039806d84ca1f718c2e332c54':
    raise RuntimeError('Use the pinned Benchmark commit from the report')
deps = out / 'deps'; deps.mkdir(exist_ok=True)
for item in json.loads((HERE/'benchmark-dependencies.json').read_text()):
    name = item['name']; artifact, version = name.removesuffix('.jar').rsplit('-', 1)
    groups = {'tomcat-embed-core':'org/apache/tomcat/embed','javax.annotation-api':'javax/annotation',
              'esapi':'org/owasp/esapi','commons-codec':'commons-codec','commons-lang':'commons-lang',
              'commons-configuration':'commons-configuration','commons-logging':'commons-logging',
              'commons-collections':'commons-collections'}
    path = deps/name
    if not path.exists():
        path.write_bytes(urllib.request.urlopen(f'https://repo.maven.apache.org/maven2/{groups[artifact]}/{artifact}/{version}/{name}',timeout=60).read())
    if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
        raise RuntimeError('Dependency checksum mismatch: '+name)
cases = json.loads((HERE/'benchmark-cases.json').read_text())
classes = out/'classes'; classes.mkdir(exist_ok=True)
source = benchmark/'src/main/java/org/owasp/benchmark'
files = [source/'testcode'/(c['id']+'.java') for c in cases]
files += [source/'helpers'/(n+'.java') for n in ['ThingInterface','ThingFactory','Thing1','Thing2']]
files += [HERE/'BenchmarkXssServer.java']
java=args.java.resolve(); javac=java.with_name('javac'+('.exe' if os.name=='nt' else ''))
cp=os.pathsep.join(map(str,deps.glob('*.jar')))
subprocess.run([str(javac),'-cp',cp,'-d',str(classes)]+list(map(str,files)),check=True)
ids=out/'ids.txt';ids.write_text('\n'.join(c['id'] for c in cases))
portfile=out/'port.txt';portfile.unlink(missing_ok=True)
servercp=os.pathsep.join([str(classes),str(benchmark/'src/main/resources'),cp])
with (out/'server.log').open('w') as log:
    server=subprocess.Popen([str(java),'-Dorg.owasp.esapi.resources='+str(benchmark/'src/main/resources'),'-cp',servercp,'BenchmarkXssServer',str(out/'tomcat'),str(portfile),str(ids)],stdout=log,stderr=subprocess.STDOUT)
    try:
        deadline=time.monotonic()+30
        while not portfile.exists():
            if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Server failed; inspect server.log')
            time.sleep(.1)
        port=int(portfile.read_text())
        spec=[]
        for case in cases:
            spec.append({**case,'template':f"GET /benchmark{case['path']}?{case['id']}=§PAYLOAD§ HTTP/1.1\r\nHost: HOST_PORT\r\nAccept: text/html\r\n\r\n"})
        specfile=out/'spec.json';specfile.write_text(json.dumps(spec))
        for label, jar in [('before',args.before.resolve()),('after',args.after.resolve())]:
            compiled=out/label;compiled.mkdir(exist_ok=True)
            subprocess.run([str(javac),'-cp',str(jar),'-d',str(compiled),str(HERE/'AssessmentReplay.java')],check=True)
            subprocess.run([str(java),'-cp',os.pathsep.join([str(compiled),str(jar)]),'kr.workbench.AssessmentReplay',f'http://127.0.0.1:{port}',str(specfile),str(out/(label+'.json'))],check=True)
    finally:
        server.terminate();server.wait(timeout=15)
if args.browser:
    subprocess.run(['node',str(HERE/'check_benchmark_browser.cjs'),str(args.browser.resolve()),str(out/'after.json'),str(out/'browser-results.json')],check=True)
