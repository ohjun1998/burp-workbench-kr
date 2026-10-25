from pathlib import Path
import argparse,subprocess,tempfile,time,os,json
p=argparse.ArgumentParser();p.add_argument('--browser',required=True,type=Path);p.add_argument('--java',default='java');p.add_argument('--classes',type=Path);p.add_argument('--tests',type=Path);p.add_argument('--output',type=Path,default=Path('build/browser-verification'));a=p.parse_args()
r=Path(__file__).resolve().parents[1];a.output.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join(map(str,[a.classes or r/'build/classes',a.tests or r/'build/tests',r/'deps/montoya-api-2026.7.jar']))
with tempfile.TemporaryDirectory(prefix='mapper-browser-fixture-') as folder:
 t=Path(folder)
 with (a.output/'fixture.log').open('w') as log:
  fixture=subprocess.Popen([a.java,'-cp',cp,'kr.featuremapper.BrowserFixture',str(t)],stdout=log,stderr=subprocess.STDOUT)
  try:
   end=time.monotonic()+15
   while not (t/'native-browser-connection.json').exists() and time.monotonic()<end:
    if fixture.poll() is not None:raise RuntimeError('Browser fixture failed to start')
    time.sleep(.1)
   with (a.output/'browser.log').open('w') as browser_log:
    browser=subprocess.run(['node',str(r/'tests/browser-native.mjs'),str(t/'native-browser-connection.json'),str(a.browser.resolve())],stdout=browser_log,stderr=subprocess.STDOUT,timeout=65)
   if browser.returncode:
    print((a.output/'browser.log').read_text());raise RuntimeError('Browser validation failed; see browser.log')
   fixture_result=fixture.wait(timeout=10)
   evidence=t/'browser-correlation-evidence.json'
   if evidence.exists():(a.output/evidence.name).write_bytes(evidence.read_bytes())
   if fixture_result:
    print((a.output/'fixture.log').read_text());raise RuntimeError('Mapping fixture validation failed; see fixture.log')
   for name in ['browser-native-result.txt','browser-fixture-result.txt']:
    (a.output/name).write_bytes((t/name).read_bytes());print((t/name).read_text())
  finally:
   if fixture.poll() is None:fixture.terminate();fixture.wait(timeout=5)
