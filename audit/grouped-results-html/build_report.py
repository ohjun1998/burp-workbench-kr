#!/usr/bin/env python3
"""Create one offline grouped-results HTML, preserving the original recorded bank data."""
import argparse
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
SOURCE=ROOT/'docs/reports/2026-11-01-bank-simple-1.4.21/layout-view/Workbench_Simple_1.4.21_Results_Layout_KR.html'
SOURCE_SHA='b3939ff7be194077078192c84f8a6feb2120adfcc26628e29fd025490a8cf3f0'
JAR=ROOT/'simple/downloads/1.4.22/burp-workbench-simple-1.4.22.jar'
JAR_SHA='d5ac13a818d0fd641c72cd093cd86c21c3fea7793e01e3cc5b8da25900f0ff7c'
NAME='Workbench_Simple_1.4.22_Grouped_Results_KR'
DEFAULT_OUTPUT=ROOT/'docs/reports/2026-11-01-grouped-results/html-view'
sha=lambda data:hashlib.sha256(data).hexdigest()
safe_json=lambda value:json.dumps(value,ensure_ascii=False,separators=(',',':'),allow_nan=False).replace('<','\\u003c').replace('\u2028','\\u2028').replace('\u2029','\\u2029')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=DEFAULT_OUTPUT)
    parser.add_argument('--java',default='/workspace/tools/burp-workbench/jdk-17.0.21+9/bin/java')
    parser.add_argument('--workspace',type=Path,default=Path('/workspace/tools/burp-workbench/grouped-html-build'))
    args=parser.parse_args();args.workspace.mkdir(parents=True,exist_ok=True);args.output.mkdir(parents=True,exist_ok=True)
    original=SOURCE.read_bytes();assert sha(original)==SOURCE_SHA
    assert sha(JAR.read_bytes())==JAR_SHA
    html=original.decode('utf-8')
    raw_data=re.search(r'<script type="application/json" id="layout-data">([\s\S]*?)</script>',html).group(1)
    data=json.loads(raw_data)
    urls=set()
    def collect(value):
        if isinstance(value,dict):
            for key,child in value.items():
                if key in ('url','origin','source') and isinstance(child,str) and child and len(child)<16384:urls.add(child)
                collect(child)
        elif isinstance(value,list):
            for child in value:collect(child)
        elif isinstance(value,str) and '\n' not in value and len(value)<8192 and value.startswith(('http://','https://','/','inline:','webpack:','blob:','data:')):
            urls.add(value)
    collect(data)
    b64=lambda value:base64.b64encode(str(value).encode()).decode()
    lines=['U\t'+b64(url) for url in sorted(urls)]
    m=data['mapper']['metadata'];counts=Counter(feature for p in m['packets'] for feature in p.get('links',{}))
    for feature in m['features']:
        fields=['F',*[b64(feature[key]) for key in ['id','origin','entity','name','state']],str(counts[feature['id']]),b64(feature['key']),str(feature.get('renamed',False)).lower()]
        lines.append('\t'.join(fields))
    tsv=args.workspace/'presentation.tsv';tsv.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    classes=args.workspace/'classes';classes.mkdir(exist_ok=True)
    subprocess.run([args.java,'com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',str(JAR),'-d',str(classes),str(HERE/'ExportPresentation.java')],check=True)
    exported=args.workspace/'presentation.json'
    subprocess.run([args.java,'-cp',str(classes)+':'+str(JAR),'kr.workbench.ExportPresentation',str(tsv),str(exported)],check=True)
    presentation=json.loads(exported.read_text())
    assert presentation['sourceJarSha256']==JAR_SHA and presentation['version']=='1.4.22'
    captures={};image_sources={}
    names={'traffic':'traffic','mapper':'mapper','auth':'auth','js':'js','environment':'environment','response':'response-recon','payload':'payload','bounty':'bounty','data':'data','activity':'activity','scope':'scope','dashboard':'dashboard','exposure':'exposure'}
    native=ROOT/'docs/reports/2026-11-01-grouped-results/native'
    verification=json.loads((native/'verification.json').read_text());assert verification['jarSha256']==JAR_SHA and verification['result']=='passed'
    for theme in ['light','dark']:
        captures[theme]={}
        for key,filename in names.items():
            path=native/'metal'/theme/'1600x1000'/(filename+'.png');raw=path.read_bytes()
            assert raw.startswith(b'\x89PNG\r\n\x1a\n')
            width,height=struct.unpack('>II',raw[16:24])
            captures[theme][key]={'dataUrl':'data:image/png;base64,'+base64.b64encode(raw).decode(),'width':width,'height':height}
            image_sources[path.relative_to(ROOT).as_posix()]={'sha256':sha(raw),'bytes':len(raw)}
    css=(HERE/'grouped-results.css').read_text();js=(HERE/'grouped-results.js').read_text()
    html=html.replace('const filtered=rows=>','let filtered=rows=>',1)
    html=html.replace('</style>',css+'\n</style>',1)
    html=html.replace('const VIEWS=',js+'\nconst VIEWS=',1)
    html=html.replace('<script>','<script type="application/json" id="presentation-data">'+safe_json(presentation)+'</script>\n<script type="application/json" id="native-captures">'+safe_json(captures)+'</script>\n<script>',1)
    html=html.replace('<title>Workbench Simple 1.4.21 · 도구별 실행 결과</title>','<title>Workbench · 13개 도구 A 그룹형 결과</title>')
    html=html.replace('<body data-theme="light">','<body data-theme="dark">')
    html=html.replace('<span class="readonly">저장된 실행 결과</span>','<div class="display-mode" role="group" aria-label="표시 방식"><button type="button" class="active" data-display-mode="results">결과 탐색</button><button type="button" data-display-mode="native">실제 확장 화면</button></div>')
    html=html.replace('aria-label="다크 화면으로 전환" title="테마 전환">☾','aria-label="라이트 화면으로 전환" title="테마 전환">☀')
    html=html.replace('현재 도구의 URL · 값 검색','기능명 · 원본 값 검색')
    html=html.replace('<main class="main">','<main class="main"><div class="provenance-strip"><strong>1.4.22 A 그룹형 보기</strong><span>· 은행 실행값은 1.4.21 저장 기록 · 새 요청 없음</span></div>')
    html=html.replace('VulnBank · 127.0.0.1:24904','VulnBank · 은행 저장 결과')
    html=html.replace('Workbench Simple 1.4.21 · 저장된 실행 결과','Workbench · 도구별 그룹형 결과 보기')
    html=html.replace('window.WorkbenchSavedResults={data:D,state:UI,navigate,render};','window.WorkbenchSavedResults={data:D,state:UI,navigate,render};\nwindow.WorkbenchGroupedResults.captures=CAPTURES;')
    # Source data must stay byte-for-byte unchanged; even identical public findings remain separate rows.
    assert re.search(r'<script type="application/json" id="layout-data">([\s\S]*?)</script>',html).group(1)==raw_data
    assert '__LAYOUT_DATA__' not in html
    body=html.encode();html_path=args.output/(NAME+'.html');html_path.write_bytes(body)
    readme=('HTML 열기\n\nZIP 압축 해제 후 '+NAME+'.html을 브라우저로 엽니다.\n13개 도구와 그룹·기능별 결과, 원본 정보, 테마 전환을 사용할 수 있습니다.\n결과 탐색: 은행1.4.21 실제 저장 결과에1.4.22표시명을 적용한 읽기 전용 보기입니다.\n실제 확장 화면:1.4.22 공식 JAR의 합성 검증 데이터로 촬영한 실제 Swing 캡처입니다.\n두 데이터 출처를 구분하며 새 스캔/HTTP를 실행하지 않습니다. 별도 서버·인터넷·이미지 폴더가 필요하지 않습니다.\n').encode('utf-8-sig')
    zip_path=args.output/(NAME+'.zip')
    with zipfile.ZipFile(zip_path,'w',zipfile.ZIP_DEFLATED) as archive:
        for filename,value in [(html_path.name,body),('먼저_읽기.txt',readme)]:
            info=zipfile.ZipInfo(filename,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;archive.writestr(info,value)
    with zipfile.ZipFile(zip_path) as archive:assert archive.testzip() is None and archive.read(html_path.name)==body
    manifest={'format':'workbench-grouped-results-html','displayVersion':'1.4.22','recordedExecutionVersion':'1.4.21','recordedCoreHttpRequests':len(data['traffic']),'recordedScopeFindings':len(data['scope']['findings']),'sourceHtml':{'sha256':SOURCE_SHA,'path':SOURCE.relative_to(ROOT).as_posix()},'embeddedOriginalJsonSha256':sha(raw_data.encode()),'sourceDisplayJarSha256':JAR_SHA,'html':{'filename':html_path.name,'sha256':sha(body),'bytes':len(body)},'zip':{'filename':zip_path.name,'sha256':sha(zip_path.read_bytes()),'bytes':zip_path.stat().st_size,'crcVerified':True},'nativeCaptures':image_sources,'generatorInputs':{p.name:sha(p.read_bytes()) for p in [HERE/'build_report.py',HERE/'ExportPresentation.java',HERE/'grouped-results.js',HERE/'grouped-results.css']},'toolPages':13,'embeddedScreenshots':26,'newNetworkRequests':0,'recordedDataByteIdentical':True,'standalone':True}
    (args.output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({key:manifest[key] for key in ['html','zip','toolPages','embeddedScreenshots','recordedDataByteIdentical']},ensure_ascii=False))

if __name__=='__main__':main()
