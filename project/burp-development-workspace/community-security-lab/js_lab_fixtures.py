"""Rebuild deterministic, data-only fixtures; never run their JavaScript."""
from pathlib import Path
import json, base64

root=Path(__file__).parent/'src/main/resources/static/js-lab'
root.mkdir(parents=True,exist_ok=True)
cases=[]
def add(name,title,group,description,source,expected):
    source='// TRAINING ONLY: fake values; fetched as text, never executed.\n'+source.strip()+'\n'
    (root/name).write_text(source,encoding='utf-8')
    cases.append(dict(file=name,title=title,group=group,description=description,expected=expected))

add('01-fetch.js','Fetch · 요청 메타데이터','정상 추출','HTTP 메서드, 쿼리 이름, 헤더 이름, body 객체 키',r'''
fetch('/api/js-lab/posts?page=1&category=security');
window.fetch('/api/js-lab/comments', {method:'POST',headers:{'Content-Type':'application/json','X-Lab-Trace':'fixture'},body:{postId:101,text:'training'}});
const OPTIONS={method:'PATCH',headers:{'X-Lab-Mode':'test'},body:{title:'edited',visibility:'PUBLIC'}};
fetch('/api/js-lab/edit',OPTIONS);
fetch(new Request('/api/js-lab/profile',{method:'PUT',body:{nickname:'tester'}}));
globalThis.fetch('/api/js-lab/delete',{method:'DELETE'});
''', ['posts: GET · page/category','comments: POST · Content-Type/X-Lab-Trace · postId/text','edit: PATCH · X-Lab-Mode · title/visibility','profile: PUT (Request 생성자)','delete: DELETE'])
add('02-axios.js','Axios · 설정 객체','정상 추출','축약 호출, config, baseURL, params, data',r'''
axios.get('/api/js-lab/search',{params:{q:'training',page:1}});
axios.post('/api/js-lab/upload',{fileId:7,filename:'lab.txt'},{headers:{'X-Lab-Upload':'fixture'}});
axios.put('/api/js-lab/settings',{theme:'dark'});
axios.patch('/api/js-lab/preferences',{language:'ko'});
axios.delete('/api/js-lab/remove');
axios({baseURL:'/api/js-lab',url:'/audit',method:'POST',params:{limit:10},data:{event:'test'},headers:{'X-Lab-Audit':'fixture'}});
axios.request({url:'/api/js-lab/export',method:'HEAD'});
''', ['search: GET · q/page','upload: POST · fileId/filename · X-Lab-Upload','settings: PUT · theme','preferences: PATCH · language','remove: DELETE','audit: POST · limit · event','export: HEAD'])
add('03-xhr-navigation.js','XHR · 스트림 · 이동','정상 추출','XHR.open, WebSocket, EventSource, location, window.open',r'''
const xhr=new XMLHttpRequest();
xhr.open('GET','/api/js-lab/xhr');
new WebSocket('wss://socket.community-lab.invalid/events');
new EventSource('/api/js-lab/events');
window.location.href='/api/js-lab/login';
location.assign('/api/js-lab/redirect');
window.location.replace('/api/js-lab/logout');
window.open('/api/js-lab/help','_blank');
''', ['xhr: GET · XHR-like open','events: UPGRADE · WebSocket','events: GET · EventSource','login/redirect/logout/help: 이동 구문'])
add('04-constants.js','상수 · 템플릿 · 이스케이프','정상 추출','const 연결, 정적 템플릿, 상대 URL, URL 설정 변수',r'''
const ROOT='/api/js-lab';
const ITEM='constant';
fetch(ROOT+'/'+ITEM);
fetch(`${ROOT}/template`);
fetch('/api/js-lab/escaped\x2fpath');
fetch('../relative');
fetch('./nearby');
const apiUrl='/api/js-lab/config';
const cfg={endpoint:'/api/js-lab/object',path:'/api/js-lab/path'};
''', ['constant/template/escaped/path: 정적 완성 URL','../relative, ./nearby: JS 응답 URL 기준으로 해석','config/object/path: 설정 구문 · 메서드 ?'])
add('05-dynamic-scope.js','동적 URL · 스코프','한계 확인','미해결 부분, 매개변수 가림, 숫자 연산 오탐 방지',r'''
const ROOT='/api/js-lab';
fetch(`${ROOT}/users/${userId}`);
function load(userId) { fetch(`${ROOT}/nested-users/${userId}`); }
function loadPost(postId) { fetch('/api/js-lab/post/'+postId); }
function shadow(ROOT) { fetch(ROOT+'/shadow'); }
fetch(ROOT+'/outside');
fetch('/api/js-lab/numeric/'+(1+2));
fetch('/api/js-lab/repeated');
fetch('/api/js-lab/repeated');
''', ['users/{dynamic}, post/{dynamic}: 미완성','현재 한계: 함수 안의 바깥 const + 템플릿 nested-users는 미추출됨', 'shadow는 전역 ROOT로 잘못 확정하면 안 됨','outside: 완성','숫자 1+2를 12로 연결하면 안 됨','repeated: 서로 다른 코드 위치 2건'])
add('06-secrets.js','가짜 키 · 토큰','시크릿 확인','공급자 패턴과 이름/엔트로피 추정, 가림 확인', '''
const demo={
 aws:'AKIAIOSFODNN7EXAMPLE',
 google:'AIza%s',
 github:'ghp_%s',
 slack:'xoxb-123456789012-987654321098-LabOnlyFakeToken',
 stripe:'sk_test_LabOnly0123456789AbCdEf',
 jwt:'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJsYWIiLCJ0cmFpbmluZyI6dHJ1ZX0.c3ludGhldGljLXNpZ25hdHVyZQ',
 secret:'L4bOnly!7Zq2#9KmX3pV',
 privateKey:'-----BEGIN PRIVATE KEY-----\\nLABONLY-NOT-A-VALID-KEY\\n-----END PRIVATE KEY-----'
};
''' % ('Z'*35,'A1b2C3d4'*5), ['AWS/Google/GitHub/Slack/Stripe/JWT/Private key: 패턴 후보','secret: 이름·엔트로피 추정 후보','결과에서는 원문 대신 가림·길이·지문 표시','현재 엔진은 이스케이프된 Private key를 원문/해제 문자열로 각각 잡아 후보가 2건 보일 수 있음','전부 합성 문자열이며 키 유효성 판정 대상 아님'])
add('07-negative.js','오탐 방지 · 값 제거','한계 확인','placeholder와 일반 문장을 제외하고 URL 비밀값을 제거',r'''
const password='your_password';
const token='changeme';
const secret='placeholder_secret';
const label='커뮤니티 테스트 페이지';
const note='This is ordinary prose, not a credential.';
fetch('https://user:LAB_PASSWORD@api.community-lab.invalid/list?token=LAB_QUERY_SECRET#LAB_FRAGMENT');
''', ['placeholder/일반 문장: 시크릿 후보 없음','URL/API 결과에서 user:password, 쿼리 값, fragment 제거','쿼리 이름 token은 남음'])
add('08-modern-module.js','ES Module · 최신 문법','한계 확인','import/export, optional chaining, nullish coalescing, async',r'''
import axios from 'axios';
import reports from '@community/private-reports';
export async function modern(config) {
 const option=config?.mode ?? 'test';
 await fetch('/api/js-lab/modern');
 return import('lab-dynamic-package');
}
''', ['현재 Rhino 엔진: AST 실패 표시 + 문자열 보조 추출','modern URL은 보조 후보이며 메서드/완성도를 AST 성공과 동일하게 믿지 않기','axios, @community/private-reports, lab-dynamic-package: 의존성 후보'])
add('09-invalid.js','잘못된 구문 · 복구','한계 확인','문법 오류와 패턴 보조 분석 상태 구분', "const apiKey='AIza"+'Y'*35+"';\nfetch('/api/js-lab/broken', ;", ['AST 실패를 명확히 표시','broken: 문자열 보조 후보','Google API Key: 패턴 후보 1개'])
add('10-recon.js','도메인 · 클라우드 · NPM','정찰 확인','주소와 의존성을 읽기만 하는 합성 테스트',r'''
const libs=require('@community/private-widget');
require('axios');
const cloud=[
 'https://training-bucket.s3.amazonaws.com/docs?token=LAB_SECRET',
 'https://training.blob.core.windows.net/files',
 'https://storage.googleapis.com/training-bucket/file',
 'https://training.cloudfront.net/static',
 'https://training.digitaloceanspaces.com/file',
 'https://training.firebaseio.com/demo'
];
const apiUrl='https://api.community-lab.invalid/v1';
''', ['클라우드 공급자 URL 6개','도메인 및 NPM 의존성 후보','자동 외부 요청 없음 · 유효성/등록 여부는 별도 조회'])
original="fetch('/api/js-lab/recovered',{method:'POST',body:{reportId:1}});\nrequire('@community/recovered');\n"
smap=dict(version=3,file='11-sourcemap.js',sources=['src/private-reports.js'],sourcesContent=[original],names=[],mappings='')
(root/'11-sourcemap.js.map').write_text(json.dumps(smap),encoding='utf-8')
add('11-sourcemap.js','외부 소스맵','소스맵 확인','별도 .map 응답을 수집하거나 같은 출처 map 조회',"fetch('/api/js-lab/bundle');\n//# sourceMappingURL=11-sourcemap.js.map", ['외부 map 참조: 발견 · 요청 전','map 조회 또는 .map 응답 분석 후 recovered: POST · reportId','원본 src/private-reports.js 1개'])
encoded=base64.b64encode(json.dumps(smap).encode()).decode()
add('12-inline-map.js','인라인 소스맵','소스맵 확인','data URL 내 sourcesContent를 네트워크 요청 없이 복원',"fetch('/api/js-lab/inline');\n//# sourceMappingURL=data:application/json;base64,"+encoded, ['원본 1개 자동 복원','recovered: POST · reportId · source map 출처','@community/recovered: 복원된 의존성'])
html='''<!doctype html><html lang="ko"><meta charset="utf-8"><title>인라인 JS 실습</title>
<h1>HTML 내 script 위치 확인</h1>
<script>
fetch('/api/js-lab/html-inline',{method:'POST',body:{commentId:1}});
</script>
<script src="/assets/js-lab/01-fetch.js"></script>
<script type="application/json">{"url":"/api/js-lab/json-block"}</script>
<p>이 파일은 텍스트 수집으로 확인하세요.</p></html>'''
(root/'13-inline.html').write_text(html,encoding='utf-8')
cases.append(dict(file='13-inline.html',title='HTML · 인라인 스크립트',group='정상 추출',description='HTML 본문에서 JS 블록만 분석',expected=['html-inline: POST · commentId · 원본 HTML 4행','src 외부 파일을 분석기가 자동 요청하면 안 됨','application/json 블록은 JS AST 대상에서 제외']))
(root/'14-package.json').write_text(json.dumps(dict(private=True,dependencies={'axios':'0.0.0','@community/private-widget':'0.0.0'},devDependencies={'lab-build-tool':'0.0.0'}),indent=2),encoding='utf-8')
cases.append(dict(file='14-package.json',title='package.json · 의존성',group='정찰 확인',description='패키지 의존성의 종류 구분',expected=['axios/@community/private-widget: dependencies','lab-build-tool: devDependencies','비공개/미등록은 취약점 확정 아님']))
(root/'manifest.json').write_text(json.dumps(dict(version='3.3-js-lab',cases=cases),ensure_ascii=False,indent=2),encoding='utf-8')
print('Generated',len(cases),'cases')
