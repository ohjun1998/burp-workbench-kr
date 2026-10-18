#!/usr/bin/env python3
"""Readable Korean report from the observed, redacted OSS lab results."""
import argparse,collections,datetime,hashlib,json,zipfile
from pathlib import Path
from xml.sax.saxutils import escape
from reportlab.pdfgen import canvas
from reportlab.platypus import SimpleDocTemplate,Paragraph,Spacer,Table,TableStyle,PageBreak,KeepTogether
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
p=argparse.ArgumentParser();p.add_argument('--results',type=Path,required=True);p.add_argument('--metadata',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--font',type=Path,required=True);a=p.parse_args();R=a.results;O=a.out;O.parent.mkdir(parents=True,exist_ok=True)
def read(n):return json.loads((R/n).read_text())
j=read('extension-results.json');browser=read('browser-results.json');capture=read('capture-statistics.json');urls=read('url-collection.json');meta=json.loads(a.metadata.read_text());checks=j['checks'];baseline=[r for r in j['authComparisons'] if r['id'].startswith('AUTH')];configured=[r for r in j['authComparisons'] if r['id'].startswith('CONF')]
metrics=collections.Counter(c['metric'] for r in baseline for c in r['cells']);cm=collections.Counter(c['metric'] for r in configured for c in r['cells']);counts=collections.Counter(r['status'] for r in checks);cal=next((r for r in checks if r['id']=='AU-08'),None);cfgok=bool(cal and cal['status']=='PASS')
pdfmetrics.registerFont(TTFont('Nanum',str(a.font)));pdfmetrics.registerFontFamily('Nanum',normal='Nanum',bold='Nanum',italic='Nanum',boldItalic='Nanum')
NAVY=colors.HexColor('#162C46');TEAL=colors.HexColor('#067D82');INK=colors.HexColor('#263B4E');MUTED=colors.HexColor('#64748B');PALE=colors.HexColor('#F0F5F8');LINE=colors.HexColor('#DCE5EB');RED=colors.HexColor('#AF373B');AMBER=colors.HexColor('#9B6500');GREEN=colors.HexColor('#1D775A')
styles={
 'body':ParagraphStyle('body',fontName='Nanum',fontSize=10.5,leading=17,textColor=INK,wordWrap='CJK',spaceAfter=8),
 'small':ParagraphStyle('small',fontName='Nanum',fontSize=8.8,leading=13.3,textColor=MUTED,wordWrap='CJK',spaceAfter=5),
 'cell':ParagraphStyle('cell',fontName='Nanum',fontSize=9.4,leading=14.2,textColor=INK,wordWrap='CJK'),
 'th':ParagraphStyle('th',fontName='Nanum',fontSize=9.2,leading=13.8,textColor=colors.white,wordWrap='CJK'),
 'h1':ParagraphStyle('h1',fontName='Nanum',fontSize=23,leading=32,textColor=NAVY,wordWrap='CJK',spaceAfter=15),
 'h2':ParagraphStyle('h2',fontName='Nanum',fontSize=14,leading=21,textColor=TEAL,wordWrap='CJK',spaceBefore=10,spaceAfter=9),
 'label':ParagraphStyle('label',fontName='Nanum',fontSize=9,leading=14,textColor=TEAL,spaceAfter=8),
 'code':ParagraphStyle('code',fontName='Nanum',fontSize=9,leading=14,textColor=NAVY,wordWrap='CJK',backColor=PALE,borderPadding=9,spaceBefore=5,spaceAfter=12),
}
def para(s,style='body'):return Paragraph(s,styles[style])
def txt(s,style='body'):return para(escape(str(s)).replace('\n','<br/>'),style)
S=[]
def add(s,style='body'):S.append(para(s,style))
def title(num,name,subtitle=''):
 if S:S.append(PageBreak())
 add(f'WORKBENCH FIELD TEST  /  {num:02d}','label');add(name,'h1')
 if subtitle:add(subtitle,'small')
def table(headers,rows,widths):
 data=[[txt(x,'th') for x in headers]]+[[txt(x,'cell') for x in row] for row in rows]
 t=Table(data,colWidths=widths,repeatRows=1,hAlign='LEFT');t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),NAVY),('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),9),('RIGHTPADDING',(0,0),(-1,-1),9),('TOPPADDING',(0,0),(-1,-1),9),('BOTTOMPADDING',(0,0),(-1,-1),9),('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,PALE]),('LINEBELOW',(0,-1),(-1,-1),.7,LINE)]));S.append(t);S.append(Spacer(1,10))
def note(head,body):
 t=Table([[para(head,'h2')],[para(body,'body')]],colWidths=[511]);t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),PALE),('LEFTPADDING',(0,0),(-1,-1),13),('RIGHTPADDING',(0,0),(-1,-1),13),('TOPPADDING',(0,0),(-1,0),2),('BOTTOMPADDING',(0,-1),(-1,-1),7)]));S.append(t);S.append(Spacer(1,11))
# 1. Summary
add('실제 사용 검증  /  2026.10.18 · KST','label');add('Workbench 테스트 보고서','h1');add('OWASP Juice Shop에서 확인한 10개 기능의 동작과 탐지 한계','body');S.append(Spacer(1,10))
summary=[['검증 기능','기능 체크','브라우저 체크'],['10개',f'{counts["PASS"]} / {len(checks)} 충족',f'{sum(c["status"]=="PASS" for c in browser["checks"])} / {len(browser["checks"])} 통과']]
t=Table([[txt(c,'small') for c in summary[0]],[para(c,'h2') for c in summary[1]]],colWidths=[170,171,170]);t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),PALE),('TOPPADDING',(0,0),(-1,-1),10),('BOTTOMPADDING',(0,0),(-1,-1),10),('LEFTPADDING',(0,0),(-1,-1),13)]));S.append(t);S.append(Spacer(1,17))
add('수집·경로 확인·기록·증거 저장 흐름은 작동했습니다. 계정 판정과 일부 탐지 결과는 추가 확인이 필요합니다.','h2')
add(f'Workbench 0.44.0을 실제 Burp Community에 로드하고 Chromium으로 사이트를 사용했습니다. 수집 {capture["captured"]}건, URL 후보 {urls["count"]}건을 확인했으며 수집 처리 오류·누락은 0건이었습니다.')
issues=[['P1 · F01','계정 비교','개인정보가 없는 200 응답을 우회 의심으로 표시. 기본 규칙에서 오탐 1건.'],['P1 · F02','JS/API 탐색','실제 main.js에서 대표 API 3개를 발견하지 못함. 분석 실패와 미확정 결과 확인.'],['P2 · F03','오류 분석·입력 검사','SQLITE_ERROR가 오류 노출·SQL 오류 후보로 분류되지 않음.'],['P2 · F04','저장·조회 연결','프로필 저장은 됐지만 302 응답 때문에 후속 조회 마커를 연결하지 못함.']]
table(['우선순위','관련 기능','개선이 필요한 결과'],issues,[75,110,326])
add('숫자 읽는 법','h2');add(f'기능 체크는 동작 조건의 충족 여부입니다. 탐지 정확도와는 별도입니다. 계정 비교 {sum(v for k,v in metrics.items() if k!="REFERENCE")}개 대조 칸에서는 정탐 {metrics["TP"]}, 오탐 {metrics["FP"]}, 미탐 {metrics["FN"]}, 정상 판단 {metrics["TN"]}, 보류 {metrics["REVIEW"]}건이었습니다. 이 수치를 전체 사이트나 모든 취약점의 정확도로 일반화하지 않습니다.','small')
add('실행 범위: 자체 로컬 Juice Shop 20.3.0. crAPI는 실행 환경 제한으로 미검증입니다. 제품 소스·배포 JAR는 수정하지 않았습니다.','small')
# 2. Feature matrix
title(2,'10개 기능 사용 결과','계정 비교 외의 기능도 실제 응답·브라우저 기록·파일로 확인했습니다.')
features=[('traffic','요청·응답 목록','수집 OFF/SCOPE, 다른 포트 제외, 브라우저 Proxy History','정상','원본 수집·연결 확인'),('auth','계정 권한 비교','관리자·Alice·Bob·비로그인으로 6개 요청 비교','주의','오탐 1·보류 2. 대상 전용 규칙 결과는 다음 쪽'),('data','데이터 변환·비교','JWT 읽기, 한글 변환, 주소 응답 비교·편집','정상','차이 확인. 편집하면 원본 증거 연결 해제'),('js','JS에서 API 찾기','실제 1.24MB 번들·브라우저 소스 분석','일부 실패','대표 API 3개 미발견. 전체 JS 기능 실패를 뜻하지 않음'),('environment','서버·경로 탐색','실재 경로 3개와 미존재 경로 1개 GET 대조','정상','HTTP 200 공통 화면을 SOFT404로 구분'),('passive','URL 후보 수집','외부 아카이브 없이 History/JS 수집·GET 확인','정상',f'후보 {urls["count"]}개와 선택 GET 2건 확인'),('activity','점검 진행 현황','관측·수동 완료·재검토·저장 복원','정상','관측만으로 자동 완료 처리하지 않음'),('response.recon','응답 보안 분석','헤더·SQLite 오류·저장/조회 마커','일부 실패','헤더·2xx 저장 연결은 확인. SQLite·302 흐름 누락'),('payload','입력값 보안 검사','기본 SQL 후보, 맞춤 참/거짓 조건, 내장 패널','조건부','기본 후보에서 SQL 후보 미분류. 맞춤 조건은 탐지'),('bounty','재현·증거 정리','2단계 변수 연결 재현·원본 저장·가림 ZIP','정상','단계 기대값과 인증값 가림 확인')]
rows=[]
for key,name,used,status,result in features:
 cs=[c for c in checks if c['feature']==key];ok=sum(c['status']=='PASS' for c in cs);rows.append([name,used,f'{status}\n{ok}/{len(cs)}',result])
table(['기능','사용한 흐름','결과','사용자가 알아둘 점'],rows,[94,166,57,194])
add('정상은 이번 대표 흐름의 통과를 의미합니다. 각 기능의 모든 옵션·예외·공격 유형을 검증했다는 뜻은 아닙니다.','small')
# 3. Authorization
title(3,'계정 비교의 정탐·오탐·보류','기본 차단 규칙과 대상 전용 보완 규칙을 구분했습니다.')
add('테스트 권한표','h2');add('사용자 목록은 관리자만, 장바구니·주소는 소유자만 허용하도록 직접 지정했습니다. 공개 상품은 모두 허용했고, 신원 응답은 실제 사용자 ID가 있을 때만 정보 접근으로 보았습니다. 관리자도 타인 장바구니에서는 차단을 기대한 실습 정책입니다.','small')
rows=[]
labels={'AUTH-01':'사용자 목록','AUTH-02':'Alice 장바구니','AUTH-03':'Bob 장바구니','AUTH-04':'Alice 주소','AUTH-05':'공개 상품','AUTH-06':'신원 확인 /whoami'}
for r in baseline:
 c=collections.Counter(x['metric'] for x in r['cells']);rows.append([labels[r['id']],c['TP'],c['FP'],c['FN'],c['TN'],c['REVIEW']])
table(['요청','정탐','오탐','미탐','정상','보류'],rows,[206,61,61,61,61,61])
add(f'기준 계정 {metrics["REFERENCE"]}칸은 집계에서 제외했습니다. 판정 보류 {metrics["REVIEW"]}칸도 정상·오탐·미탐으로 바꾸지 않았습니다. 실제 사용자 ID, 소유자 ID, 개별 마커로 접근 여부를 대조했습니다.','small')
note('F01 · 빈 200 응답의 오탐','비로그인 GET /rest/user/whoami는 <b>{"user":{}}</b>를 반환했습니다. 사용자 정보는 없었지만 기본 규칙에서는 우회 의심으로 표시됐습니다. 응답 코드만으로 실제 데이터 노출을 확정하면 안 됩니다.')
add('주소 요청의 보류는 보수적인 판정입니다. 타 계정은 400과 “Malicious activity detected.”를 받았지만, HTTP 400만으로 권한 차단을 확정하지 않아 2칸이 보류됐습니다.','small')
add('대상 전용 규칙 적용 결과','h2')
if configured:
 add(f'빈 user 객체와 명시적 차단 문구를 응답 차단 규칙에 등록해 다시 비교했습니다. 대조 {sum(v for k,v in cm.items() if k!="REFERENCE")}칸에서 정상 {cm["TN"]}, 오탐 {cm["FP"]}, 보류 {cm["REVIEW"]}건이었습니다. 빈 신원 응답의 오탐은 해소됐지만 400 보류 2건은 남았습니다. 따라서 “둘 다 해소”를 기대한 AU-08 체크는 미충족입니다.')
else:add('보완 규칙 적용 결과가 없습니다.')
add('BODY_REGEX: "user"\\s*:\\s*\\{\\s*\\}<br/>BODY: Malicious activity detected.','code')
# 4. Discovery
title(4,'JS·URL·서버 경로 탐색','실제 호출 기록과 정적 분석 후보를 구분해 읽어야 합니다.')
add('F02 · 대형 JS 번들에서 대표 API 미발견','h2')
main=read('js-main-analysis.json');expected=read('js-expected-paths.json');versions=read('js-main-versions.json') if (R/'js-main-versions.json').exists() else []
add(f'실제 main.js는 {main["sourceBytes"]:,}바이트입니다. 원문에 있는 로그인·장바구니·검색 경로 3개를 기준으로 확인했지만, 이 번들의 보관된 분석 버전들을 합쳐도 {len(expected["missing"])}/3개가 누락됐습니다.')
table(['원문에 확인한 경로','Workbench의 대표 경로 확인'],[[x,'미발견' if x in expected['missing'] else '발견'] for x in expected['expected']],[294,217])
add('보관된 번들 분석 버전 중 하나에는 “분석 실패 · 입력/지원 문법 확인”이 표시됐습니다. 별도의 동일 JAR 파서 재생에서는 일부 문자열 후보를 얻었습니다. 따라서 “파일이 커서 무조건 실패”로 단정할 수 없으며, 브라우저 문맥·모듈 연결·버전 처리까지 포함한 경로를 점검해야 합니다.','small')
if versions:
 table(['보관한 main.js 결과','API 후보 수','상태'],[[f'버전 {i+1}',v['endpointCount'],('분석 실패 · 입력/지원 문법 확인' if '분석 실패' in v['status'] else '분할 분석 · 문맥/메서드 미확정 후보 포함')] for i,v in enumerate(versions)],[88,70,353])
add('개선 우선순위: 일부 구간이 실패해도 다른 후보를 보존하고, 실패한 구간과 사유를 보여주는 처리가 필요합니다. 미확정 문자열과 확정 메서드를 구분하고, 실제 History와 함께 검토하는 흐름이 유효합니다.','small')
add('History·경로 확인은 사용할 수 있었습니다','h2');add(f'URL 후보 수집에서 {urls["count"]}개를 모았고, 선택한 robots.txt·상품 URL의 GET 응답을 확인했습니다. 서버·경로 탐색은 미존재 URL이 200 공통 화면을 반환해도 “SOFT404 / 없는 경로와 동일”로 구분했습니다.')
table(['확인 경로','최종 분류'],[['/robots.txt · /ftp','REVIEW · 응답 검토'],['/wb-missing-20261018','SOFT404 · 없는 경로와 동일'],['/rest/admin/application-version','JSON · 응답 검토']],[294,217])
# 5. Inputs and response
title(5,'입력 검사·응답 분석의 탐지 한계','단순 오류·후보와 실제 동작 확인을 구분했습니다.')
add('F03 · SQLite 오류가 두 기능에서 누락','h2');add('검색 입력에 의해 실제 500 응답과 SQLITE_ERROR가 발생했습니다. 응답 보안 분석에는 오류 노출 후보가 추가되지 않았고, 기본 SQL 입력 검사도 SQL 오류 후보로 분류하지 못했습니다. 현재 오류 규칙에 이 형식이 빠져 있는 것을 소스에서 확인했습니다.')
payload=read('payload-sqli-results.json');context=read('payload-sqli-context-results.json')
table(['검사 방식','실제 실행','확인된 결과'],[['기본 SQL 후보',f'{payload["requests"]}회 요청','응답 변화/서버 오류. SQL 후보 미분류'],['대상 문맥에 맞춘 참·거짓 조건',f'{context["requests"]}회 요청','참은 기준과 동일, 거짓은 달라져 SQL 조건 차이 후보 탐지'],['실제 브라우저 DOM XSS','console 마커 실행 확인','브라우저에서만 실행을 확인. 입력 검사기의 확정 판정으로 집계하지 않음']],[153,98,260])
add('맞춤 SQL 조건은 기준 재검증 2회와 참·거짓 2회를 사용했습니다. 기본 페이로드만으로 모든 SQL 문맥을 검사할 수 없으며, 오류 패턴 보완과 문맥별 대조 조건을 함께 제공하는 것이 필요합니다.','small')
add('F04 · 302 저장 응답의 연결 한계','h2')
table(['흐름','서버에서 확인한 사실','Workbench 표시'],[['프로필 저장 → 조회','POST 302 → GET 200. 고유 마커 재출력','쓰기 성공 미확인. 조회 원본 연결 없음'],['주소 생성 → 조회','POST 201 → GET 200. 고유 마커 재출력','2xx 저장·조회 연결 확인']],[153,179,179])
add('현재 저장/조회 연결은 2xx 쓰기만 성공 후보로 보관합니다. 일반적인 저장 후 리다이렉트 흐름을 놓칠 수 있습니다. 302 자체를 성공으로 간주하기보다, 같은 Origin의 후속 조회와 마커 일치를 근거로 사용자가 연결을 승인할 수 있게 하는 편이 적절합니다.','small')
note('헤더 후보·마커는 취약점 확정과 다릅니다','HTML 헤더 미관측 후보는 표시됐습니다. 저장 마커 재출력은 브라우저 실행을 뜻하지 않으며, 2xx 주소 생성 사례에서도 “실행 미검증” 상태를 유지했습니다.')
# 6. Workflow and simplification
title(6,'기록·증거 관리와 화면 경량화 제안','동작이 확인된 기능은 공통 작업으로 옮기는 방식이 적절합니다.')
add('운영 흐름에서 확인한 부분','h2');table(['기능','실제로 확인한 결과'],[['데이터 변환·비교','JWT의 계정 내용 확인, 한글 URL 변환 왕복, 두 주소 응답의 차이 표시. 입력 편집 시 원본 증거 연결 해제.'],['점검 진행 현황','관측 요청은 자동 완료되지 않음. 수동 완료·재검토 이유 표시·저장 후 복원 확인.'],['순차 재현','1단계 응답의 상품 ID를 추출해 2단계 요청에 넣고, 상태·JSON 기대값 일치 확인.'],['증거 저장','실제 원본을 연결해 ZIP 저장. 모든 ZIP 항목을 확인했으며 테스트 계정의 원문 토큰은 포함되지 않음.']],[129,382])
add('사용자 요청을 반영한 정리 방향','h2')
table(['현재 위치','제안하는 위치','유지할 핵심'],[['응답 보안 분석 독립 화면','서버·경로 탐색의 “응답 관찰” 영역으로 통합','헤더·오류·API 근거 보기. SQLite·302 한계 먼저 보완.'],['재현·증거 정리 독립 화면','각 결과의 “선택 원본 → 증거 ZIP” 공통 메뉴','검증된 가림·원본 연결. 다단계 재현은 고급 메뉴로 이동.'],['점검 진행 현황 독립 화면','상단 요약과 미완료/재검토 필터로 축소','수동 완료, 재검토 이유, 저장·복원 유지.']],[129,190,192])
add('화면은 줄여도 인증값 가림과 원본 연결 기능은 남기는 것을 권합니다. 이번 실측에서는 증거 저장이 작동했습니다. 반면 응답 보안 분석의 오류·흐름 연결은 보완이 필요해, 별도 확장 화면으로 유지할 이유가 상대적으로 약합니다.')
add('이 제안은 사용 흐름을 줄이기 위한 것입니다. 확장 기능 삭제·통합은 구현하지 않았으며, 메모리·CPU 절감량도 측정하지 않았습니다.','small')
# 7. Scope and evidence
title(7,'실행 범위와 검증 근거','재현 조건과 확인하지 못한 항목을 함께 남깁니다.')
table(['항목','실행 조건'],[['Workbench','0.44.0 · 배포 JAR 그대로 로드'],['Burp / Java',f'{j["burpVersion"]} / Java {j["javaVersion"]} · 임시 전용 프로필'],['브라우저',f'Chromium {browser["version"]} · 실제 DOM·전용 Burp Proxy'],['대상','OWASP Juice Shop 20.3.0 · Node 24 · 로컬 독립 실행'],['계정','실습 관리자, 새 Alice, 새 Bob, 비로그인'],['수집 범위',f'127.0.0.1 전용. {j["httpRequests"]}건 Burp HTTP 관측 / {capture["captured"]}건 저장. 준비용 직접 HTTP는 별도.'],['종료','테스트가 만든 Juice Shop·Burp 프로세스 종료 확인']],[112,399])
add('검증하지 않은 범위','h2');add('crAPI는 Docker/Podman이 없고 사용자 네임스페이스·PRoot 실행도 제한되어 서비스 실행을 완료하지 못했습니다. 소스 확보만 했으며 성공·실패 탐지 집계에 넣지 않았습니다. Burp GUI의 수동 클릭, 외부 OAST/Collaborator·BApp, 공개 아카이브, Web3·외부 AI, 모든 공격 유형·대규모 성능은 미검증입니다.','small')
add('기존 회귀 검사: python dev.py check도 통과했습니다(2,890개). 이 숫자는 내부 회귀·실습 검사이며 이번 Juice Shop 33개 기능 체크와 별도입니다.','small');add('증거 읽는 순서','h2');add('동봉 ZIP의 summary.json → checks.csv → auth-comparisons.json 순서로 보면 됩니다. 기능별 JSON, 브라우저 체크, 환경·해시 정보, 가림 증거 ZIP을 함께 넣었습니다. 인증 원문·개인 프로필·서버 원본 저장소·실습 데이터베이스는 제외했습니다.','small')
add('재현 식별자','h2');add(f'Workbench SHA-256<br/><font name="Courier" size="7.7">{meta["workbenchJarSha256"]}</font><br/>Juice Shop 배포 파일 SHA-256<br/><font name="Courier" size="7.7">{meta["juiceShopArchiveSha256"]}</font>','small')
add('공식 자료','h2');add('<link href="https://github.com/juice-shop/juice-shop/releases/tag/v20.3.0" color="#067D82">[1] OWASP Juice Shop v20.3.0 배포</link>  ·  <link href="https://github.com/juice-shop/juice-shop" color="#067D82">[2] 대상 소스·실행 안내</link><br/><link href="https://github.com/OWASP/crAPI/blob/develop/docs/setup.md" color="#067D82">[3] crAPI 공식 설치 안내</link>  ·  <link href="https://github.com/ohjun1998/burp-workbench-kr/tree/b63175a3f311e978f5290b4c5cbff5d58797b8fc" color="#067D82">[4] 검증 기준 Workbench 소스</link>','small')
add('공개 운영 데모를 공격하지 않았습니다. 외부 Alchemy 접속 확인이 자동 검토에서 차단된 뒤, 대상의 외부 통신을 막고 로컬 검증을 진행했습니다.','small')
class NumberedCanvas(canvas.Canvas):
 def __init__(self,*args,**kwargs):canvas.Canvas.__init__(self,*args,**kwargs);self.saved=[]
 def showPage(self):self.saved.append(dict(self.__dict__));self._startPage()
 def save(self):
  total=len(self.saved)
  for state in self.saved:
   self.__dict__.update(state);self.setStrokeColor(LINE);self.line(42,43,553,43);self.setFont('Nanum',8);self.setFillColor(MUTED);self.drawString(42,28,'Workbench 0.44.0  |  실제 사용 검증  |  2026.10.18');self.drawRightString(553,28,f'{self._pageNumber} / {total}');canvas.Canvas.showPage(self)
  canvas.Canvas.save(self)
doc=SimpleDocTemplate(str(O),pagesize=(595.276,841.89),leftMargin=42,rightMargin=42,topMargin=38,bottomMargin=58,title='Workbench 실제 사용 검증 보고서',author='Codex',pageCompression=1)
doc.build(S,canvasmaker=NumberedCanvas)
print(json.dumps({'pdf':str(O),'featureChecks':dict(counts),'baseline':dict(metrics),'configured':dict(cm),'cfgok':cfgok},ensure_ascii=False))
