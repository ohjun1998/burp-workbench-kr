#!/usr/bin/env python3
"""Create the Korean report from recorded HTTP, native extension and browser evidence."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import zipfile
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, Image, KeepTogether

ROOT=Path(__file__).resolve().parents[1]
LABELS={'session':'세션 쿠키','token':'불투명 토큰','jwt':'JWT','session-token':'세션 + 토큰','session-jwt':'세션 + JWT','token-jwt':'토큰 + JWT','all':'세션 + 토큰 + JWT'}
PARTS={'session':'Cookie: AUTHLABSID','token':'X-Auth-Token','jwt':'Authorization: Bearer JWT','session-token':'Cookie + X-Auth-Token','session-jwt':'Cookie + Authorization','token-jwt':'X-Auth-Token + Authorization','all':'Cookie + X-Auth-Token + Authorization'}
NAVY=colors.HexColor('#14283f');BLUE=colors.HexColor('#315a83');TEAL=colors.HexColor('#137769');GREY=colors.HexColor('#617087');PALE=colors.HexColor('#edf3f8');ORANGE=colors.HexColor('#a74b22')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=Path,default=ROOT/'docs/verification/auth-lab-3.9.0')
    parser.add_argument('--out',type=Path,default=ROOT/'docs/reports/2026-10-18')
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    read=lambda name:json.loads((args.evidence/name).read_text())
    summary,http,ext,ui=map(read,['summary.json','http-results.json','extension-results.json','ui-results.json'])
    assert all(x['result']=='passed' for x in [summary,http,ext,ui])
    assert summary['nativeExtensionLoaded'] and ext['comparisonRuns']==42 and ext['comparedAccountCells']==126
    remote=read('remote-verification.json') if (args.evidence/'remote-verification.json').exists() else None
    font=ROOT/'project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf'
    pdfmetrics.registerFont(TTFont('KR',str(font)));pdfmetrics.registerFontFamily('KR',normal='KR',bold='KR',italic='KR',boldItalic='KR')
    style={
      'body':ParagraphStyle('Body',fontName='KR',fontSize=9.8,leading=16.2,textColor=NAVY,spaceAfter=9,wordWrap='CJK'),
      'small':ParagraphStyle('Small',fontName='KR',fontSize=8.4,leading=12.5,textColor=GREY,spaceAfter=6,wordWrap='CJK'),
      'title':ParagraphStyle('Title',fontName='KR',fontSize=27,leading=39,textColor=NAVY,spaceAfter=20,wordWrap='CJK'),
      'h1':ParagraphStyle('H1',fontName='KR',fontSize=18,leading=26,textColor=NAVY,spaceAfter=16,wordWrap='CJK'),
      'h2':ParagraphStyle('H2',fontName='KR',fontSize=12,leading=19,textColor=BLUE,spaceBefore=10,spaceAfter=9,wordWrap='CJK'),
      'cell':ParagraphStyle('Cell',fontName='KR',fontSize=8.8,leading=13.3,textColor=NAVY,wordWrap='CJK'),
      'head':ParagraphStyle('Head',fontName='KR',fontSize=8.8,leading=13,textColor=colors.white,wordWrap='CJK')}
    story=[];width=A4[0]-88
    def p(text,kind='body'):
        story.append(Paragraph(escape(str(text)).replace('\n','<br/>'),style[kind]))
    def table(headers,rows,widths=None):
        cells=[[Paragraph(escape(str(v)).replace('\n','<br/>'),style['head']) for v in headers]]
        cells += [[Paragraph(escape(str(v)).replace('\n','<br/>'),style['cell']) for v in row] for row in rows]
        t=Table(cells,colWidths=widths or [width/len(headers)]*len(headers),repeatRows=1,hAlign='LEFT')
        t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),NAVY),('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,PALE]),('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),9),('RIGHTPADDING',(0,0),(-1,-1),9),('TOPPADDING',(0,0),(-1,-1),6),('BOTTOMPADDING',(0,0),(-1,-1),6),('LINEBELOW',(0,0),(-1,0),1,TEAL),('LINEBELOW',(0,1),(-1,-1),.25,colors.HexColor('#dfe7ef'))]))
        story.extend([t,Spacer(1,12)])
    def page(title):story.append(PageBreak());p(title,'h1')
    def picture(name,max_height=360):
        path=args.evidence/name
        if not path.exists():return
        iw,ih=ImageReader(str(path)).getSize();scale=min(width/iw,max_height/ih);story.extend([Image(str(path),width=iw*scale,height=ih*scale,hAlign='LEFT'),Spacer(1,8)])
    def native(route):return next(c for c in ext['comparisons'] if c['profile']=='all' and c['path'].endswith(route))
    def raw(name):return next(c for c in http['cases'] if c['name']==name)
    counts=Counter(c['group'] for c in http['cases'])
    normal=sum(not c['expectedSuspect'] for c in ext['comparisons']);negative=ext['comparisonRuns']-normal
    p('AUTHENTICATION & AUTHORIZATION','small');p('인증 조합·URL 권한\n검증 보고서','title')
    p('LOOP Community 3.9.0  /  Burp Workbench KR 0.43.0','h2');p('작성일 2026.10.18 · 로컬 실습 환경 · 가상 계정과 학습용 데이터','small')
    table(['검증 항목','실행 결과'],[
      ['인증 방식','세션, 토큰, JWT와 복합 조합 7개'],['독립 HTTP 검사',f"{http['checks']}개 통과 / 기록 요청 {http['targetHttpRequests']}회"],
      ['실제 Burp 권한 비교',f"비교 기록 {ext['comparisonRuns']}건 / 기준 제외 계정 대조 {ext['comparedAccountCells']}건"],['정상·취약 대조',f'정상 {normal}건 기대 정책 일치 / 의도된 취약 {negative}건 우회 의심'],
      ['브라우저',f"{ui['checks']}개 통과 / Chromium {ui['browser']}"],['기존 기능 회귀','권한·업무 HTTP 384개, 커뮤니티 브라우저 66개 통과']], [150,width-150])
    p('판정 요약','h2');p('검사한 정상 URL에서 비로그인 차단, 관리자 권한, 데이터 소유권과 쓰기 전후 상태를 기대한 대로 확인했습니다. 인증값 누락·혼합·변조·만료·폐기 후 재사용도 거부했습니다.')
    p('관리자 인가 누락과 객체 소유권 누락은 별도의 취약 실습 URL에서 의도적으로 재현했습니다. 같은 두 결함 유형을 7개 인증 방식에서 반복 관찰한 결과이며, 14개의 서로 다른 신규 취약점을 뜻하지 않습니다.')
    p('이 결과는 아래에 명시한 경로와 조건에 한정됩니다. 실습 사이트 전체의 안전성이나 다른 인증 시스템까지 보증하는 결과로 해석하지 않습니다.','small')

    page('1. 범위와 기대 권한')
    table(['실습 페이지','필수 인증값'],[[LABELS[k]+'\n/auth-lab/'+k,PARTS[k]] for k in LABELS],[width*.42,width*.58])
    p('계정별 서버 정책','h2')
    table(['URL 기능','관리자','Alice','Bob','비로그인'],[['회원 공통','200','200','200','401'],['관리자 회원 목록','200','403','403','401'],['Alice 요청 #901','200','200','404','401'],['Bob 요청 #902','200','404','200','401']],[width*.36,*[width*.16]*4])
    p('인증 실습은 기존 JSESSIONID 커뮤니티 로그인과 구분합니다. 동일 로그인 묶음과 동일 인증 방식에 발급된 값을 함께 사용해야 합니다. 하나의 필수 값이 없거나 다른 계정·로그인의 값이 섞이면 401로 거부합니다. 쿠키가 포함된 변경 요청은 CSRF도 확인합니다.','small')

    page('2. 실행 환경과 결과 집계')
    table(['항목','기록'],[['실제 호스트',summary['burpVersion']],['확장',summary['extensionVersion']+' / 배포 JAR를 수정하지 않고 로드'],['전송',summary['transport']],['대상 서버','Tomcat 10.1.60 / Spring MVC / 별도 loopback 서버'],['DB',summary['database']],['프로필','새 임시 Burp 프로필 / 기존 브라우저·프록시 설정 보존']],[125,width-125])
    p('방식별 실제 비교 기록','h2')
    table(['인증 방식','정상 대조','취약 대조','기준 제외 계정 대조'],[[LABELS[k],4,2,18] for k in LABELS]+[['합계',normal,negative,ext['comparedAccountCells']]],[width*.40,width*.17,width*.17,width*.26])
    p('비교 42건의 표에는 계정 칸이 168개 있습니다. 그중 42개는 기준 계정이며, 기준을 제외한 실제 계정 대조는 126개입니다. 사용자 신원·역할 및 독립 비로그인 검사는 21건, 만료 기준 중단 검사는 별도 1건입니다. 반복 실행 결과는 고유 검사 수에 더하지 않았습니다.','small')

    page('3. 의도된 인가 결함 대조')
    p('F-01  관리자 기능의 권한 검증 누락','h2')
    c=native('/vuln/admin/users');f=native('/fixed/admin/users')
    table(['구분','실제 관측'],[['재현 경로','GET /api/auth-lab/{profile}/vuln/admin/users'],['기대 정책','관리자 허용 / 일반 사용자와 비로그인 거부'],['취약 대조','Alice·Bob이 HTTP 200으로 회원 목록 조회'],['정상 대응','/fixed/admin/users에서 Alice·Bob은 403, 비로그인은 401'],['확장 판정',c['id']+' : '+c['aggregate']+' / '+f['id']+' : '+f['aggregate']]],[105,width-105])
    p('응답의 AUTHLAB_ADMIN_DIRECTORY와 회원 데이터 포함 여부를 확인했습니다. 일반 사용자 200만으로 판단하지 않고 관리자 기준 응답, 서버 정책과 응답 내용까지 대조했습니다.','small')
    p('F-02  타인 객체의 소유권 검증 누락','h2')
    c=native('/vuln/requests/901');f=native('/fixed/requests/901')
    table(['구분','실제 관측'],[['재현 경로','GET /api/auth-lab/{profile}/vuln/requests/901'],['기대 정책','Alice 소유 요청은 Alice·관리자만 조회'],['취약 대조','Bob이 HTTP 200으로 Alice의 비공개 메모 조회'],['정상 대응','/fixed/requests/901에서 Bob 404, 비로그인 401'],['확장 판정',c['id']+' : '+c['aggregate']+' / '+f['id']+' : '+f['aggregate']]],[105,width-105])
    p('취약 실습의 Bob 메모 변경 요청은 실제 저장값 변화로 확인했습니다. 정상 URL의 동일 타인 변경은 404로 거부되었고 저장값도 유지되었습니다. 이 두 유형은 탐지 확인을 위해 제공한 대조 사례입니다.','small')

    page('4. 인증 오류와 쓰기 상태 증거')
    names=['required part absent: Cookie','different logins of same user mixed','duplicate authorization headers','cross-profile credential replay','issuer mismatch','audience mismatch','future not-before','none algorithm rejected','payload tampering invalidates signature','signed role claim cannot elevate server role','logout revokes issued credentials','expired authentication rejected']
    labels=['필수 쿠키 누락','같은 사용자라도 다른 로그인 혼합','중복 Authorization','다른 인증 방식에 재사용','JWT issuer 불일치','JWT audience 불일치','JWT 사용 시작 전','alg=none','JWT 본문 변조','서명된 role 주장으로 승격 시도','로그아웃 후 재사용','유효 시간 만료']
    table(['대표 검사','증거 ID','기대 / 실제'],[[label,raw(name)['id'],f"{raw(name)['expectedStatus']} / {raw(name)['status']}"] for label,name in zip(labels,names)],[width*.54,width*.22,width*.24])
    p('쓰기 전후 확인','h2')
    denied=raw('other owner write rejected');owned=raw('owner write succeeds');unsafe=raw('intentional write ownership flaw')
    table(['사례','HTTP','서버 상태'],[[denied['id']+' 타인 변경',404,'변경 전후 동일'],[owned['id']+' 소유자 변경',200,'메모와 revision 증가 확인'],[unsafe['id']+' 취약 대조 변경',200,'타인의 실제 저장값 변경 확인']],[width*.50,width*.16,width*.34])
    p('만료된 기준 요청을 저장된 200 응답과 함께 다시 비교했을 때, 확장은 현재 기준을 401로 재확인하고 모든 계정 재전송을 중단했습니다. 종합 결과는 녹색 차단이 아니라 ‘세션 확인 필요’였습니다.','small')

    page('5. 로그인 화면과 재현 순서')
    picture('auth-lab-login-panel.png',310)
    p('일곱 페이지에서 계정·유효 시간을 선택하고 직접 URL을 실행할 수 있습니다. 인증값은 접힌 상세에서 확인하고, 혼합·제외·JWT 변조와 실제 메모 변경을 재현할 수 있습니다.','small')
    for number,text in enumerate(['커뮤니티 사이드 메뉴에서 인증 방식별 로그인·권한 검증을 엽니다.','각각의 로그인으로 admin·alice·bob의 요청을 수집합니다. 여러 계정을 동시에 비교할 때는 별도 브라우저 프로필을 사용합니다.','Workbench SCOPE에 로컬 실습 주소를 추가하고 수집을 SCOPE로 전환합니다.','계정 권한 비교에서 관리자·일반·후보1·비로그인을 지정하고 정상 URL의 허용·차단 정책을 맞춥니다.','사용자 확인 URL은 /api/auth-lab/{profile}/me, 신원 경로는 $.user.id, 역할 경로는 $.user.role입니다.','요청을 선택해 비교하고 계정별 응답과 종합 결과를 확인합니다. 취약 URL에서는 의도된 우회 의심을 대조합니다.'],1):p(f'{number}. {text}')

    page('6. 실제 확장 기록과 검사 구분')
    picture('extension-history.png',210)
    p('위 이미지는 실제 Burp에 로드한 확장 컨트롤러의 비교 기록 표를 렌더링한 것입니다. 인증값을 보여 주는 후보·패킷 영역은 제외했습니다. 근거 JSON의 responseSha256은 Burp가 반환한 본문 문자열을 UTF-8로 인코딩한 해시이며 전송 바이트 해시와 구분합니다.','small')
    table(['분류','범위와 통과 수'],[['인증·전송 조건',sum(counts[k] for k in ['login','identity','missing-component','invalid-component','no-fallback','mixed-binding','ambiguity','profile-binding','transport','public'])],['JWT·역할 위조',sum(counts[k] for k in ['jwt-claims','jwt-header','jwt-signature','role-spoof'])],['일반 권한·객체·현재 DB 역할',sum(counts[k] for k in ['authorization','ownership','live-role'])],['CSRF·만료·폐기',sum(counts[k] for k in ['csrf','expiration','revocation'])],['실제 쓰기·의도된 취약 대조',sum(counts[k] for k in ['write-ownership','write-positive','negative-control'])],['독립 HTTP 검사 합계',http['checks']]],[width*.74,width*.26])
    p('확장 테스트는 실제 Montoya 요청·응답과 운영 비교 컨트롤러를 사용했습니다. 자동 하네스가 계정 후보를 지정하고 비교 실행 경로를 호출했으며, 사람이 Burp 창을 클릭한 검사는 아닙니다. 브라우저 104개 검사는 별도 Chromium으로 웹 페이지를 조작한 결과입니다.','small')

    page('7. 배포·근거와 검증 한계')
    p('검증된 배포 파일','h2')
    p('확장 JAR: burp-workbench-kr-0.43.0.jar','small');p(summary['extensionJarSha256'],'small')
    docker=ROOT/'downloads/docker/3.9.0/community-security-lab-3.9.0-docker.zip'
    if docker.exists():p('커뮤니티 실행 ZIP: community-security-lab-3.9.0-docker.zip','small');p(hashlib.sha256(docker.read_bytes()).hexdigest(),'small')
    p('검사 재현','h2');p('기본 CI: python tools/check_auth_lab.py --mode adapter\n웹 페이지: python tools/check_community_ui.py --auth-lab\n실제 Burp: check_auth_lab.py의 native 모드에 별도 Burp JAR·Java 21 경로와 라이선스 수락 옵션을 지정합니다.','small')
    p('실행 환경과 남은 범위','h2')
    p('상세 420개와 실제 Burp 비교는 격리된 H2 Oracle 호환 모드에서 실행했습니다. 실제 Burp 창의 클릭·물리 키·Windows DPI/IME, 외부 운영 사이트, OAuth/OIDC·SAML·JWE·RS256 발급 시스템은 이번 검사에 포함하지 않습니다. HTTP 실습 쿠키의 Secure 동작은 HTTPS 환경 검증과 구분합니다.')
    warning=summary.get('hostUiWarningCount',0)
    if warning:p(f'Burp 헤드리스 시작 시 내부 ComponentUI 경고 {warning}건이 기록되었습니다. 비교 컨트롤러·HTTP·종료 검사는 통과했으며 실제 호스트 GUI 표시는 검증하지 않았습니다.','small')
    if remote:
        p('원격 검사','h2');p(remote.get('description','원격 실행 결과는 별도 검증 JSON에 기록했습니다.'))
        if remote.get('oracleAuthChecks'):p('Oracle·Nginx 인증 경로 대조: '+str(remote['oracleAuthChecks'])+'개 통과.','small')
    else:p('원격 Docker·Oracle 결과는 VERIFICATION_LAB_3.9.0.json에 별도로 기록합니다. 로컬 Docker 실행 결과로 표현하지 않습니다.','small')
    p('기존 트래픽 비동기 검색 회귀가 한 차례 실패한 뒤 분리 재실행 23개를 통과했습니다. 해당 0.43.0 소스·검사는 바꾸지 않았으며 최종 전체 배포 검사 결과와 실패 이력을 상세 검증 JSON에 함께 남겼습니다.','small')
    p('첨부 근거','h2');p('summary.json: 실행 환경과 수치\nhttp-results.json: 420개 기대/실제 결과와 가린 요청·응답\nextension-results.json: 계정별 판정·응답 근거·본문 문자열 해시\nui-results.json: 실제 브라우저 동작과 화면 목록','small')
    p('참고 기준','h2');p('RFC 7519: https://www.rfc-editor.org/rfc/rfc7519.html\nRFC 8725: https://www.rfc-editor.org/rfc/rfc8725.html\n저장소: https://github.com/ohjun1998/burp-workbench-kr','small')
    def decoration(canvas,doc):
        canvas.saveState();canvas.setFillColor(NAVY);canvas.setFont('KR',8);canvas.drawString(44,A4[1]-28,'LOOP COMMUNITY / 인증·권한 검증');canvas.setFillColor(GREY);canvas.drawRightString(A4[0]-44,A4[1]-28,'2026.10.18  |  LAB 3.9.0');canvas.setStrokeColor(colors.HexColor('#d8e2ec'));canvas.line(44,A4[1]-39,A4[0]-44,A4[1]-39);canvas.setFont('KR',7.5);canvas.drawString(44,25,'로컬 실습 / 가상 데이터 / 인증값 제외');canvas.drawRightString(A4[0]-44,25,str(doc.page));canvas.restoreState()
    pdf=args.out/'Auth_Comparison_Report_20261018.pdf'
    SimpleDocTemplate(str(pdf),pagesize=A4,leftMargin=44,rightMargin=44,topMargin=61,bottomMargin=45,title='인증 조합·URL 권한 검증 보고서',author='Burp Workbench KR').build(story,onFirstPage=decoration,onLaterPages=decoration)
    markdown=args.out/'Auth_Comparison_Report_20261018.md'
    rows='\n'.join(f"| {LABELS[k]} | 4 | 2 | 18 |" for k in LABELS)
    markdown.write_text(f'''# 인증 조합·URL 권한 검증 보고서

작성일: 2026-10-18 / 커뮤니티 3.9.0 / 확장 0.43.0

실제 {summary['burpVersion']}에 배포 JAR를 로드했습니다. 독립 HTTP {http['checks']}개, 확장 비교 42건(기준 제외 계정 대조 126건), 신원·만료 보호 22건, Chromium {ui['checks']}개가 통과했습니다. 기존 커뮤니티 HTTP 384개·브라우저 66개도 통과했습니다. 반복 검사는 합산하지 않습니다.

검사한 정상 구현은 기대 정책과 일치했습니다. 의도된 관리자 인가 누락·객체 소유권 누락 두 유형을 7개 방식에서 탐지했습니다. 이는 14개 신규 결함이 아니라 대조용 14건입니다. 정상 경로의 타인 쓰기는 거부되고 데이터가 유지되었으며 취약 대조의 실제 변경은 재조회로 확인했습니다.

| 인증 방식 | 정상 대조 | 취약 대조 | 기준 제외 계정 대조 |
|---|---:|---:|---:|
{rows}

일곱 페이지는 `/auth-lab/session`, `/auth-lab/token`, `/auth-lab/jwt`, `/auth-lab/session-token`, `/auth-lab/session-jwt`, `/auth-lab/token-jwt`, `/auth-lab/all`입니다. 기존 커뮤니티 사이드 메뉴에서 들어갑니다.

실제 Burp 검사는 임시 프로필의 Montoya HTTP와 운영 비교 컨트롤러를 사용한 자동 검사입니다. Burp 창 클릭·물리 키·Windows DPI/IME 검사가 아닙니다. 상세 검사는 H2 Oracle 호환 모드이며 Docker·Oracle 결과는 별도 검증 기록을 참조합니다. 인증값·서명 키·로그인 본문은 배포 근거에서 제외했습니다.

[PDF](Auth_Comparison_Report_20261018.pdf) · [상세 검증](https://github.com/ohjun1998/burp-workbench-kr/blob/main/docs/VERIFICATION_LAB_3.9.0.json) · [근거 JSON](https://github.com/ohjun1998/burp-workbench-kr/blob/main/docs/verification/auth-lab-3.9.0/summary.json)
''',encoding='utf-8')
    bundle=args.out/'Auth_Comparison_Evidence_20261018.zip'
    with zipfile.ZipFile(bundle,'w',zipfile.ZIP_DEFLATED) as z:
        for file in [pdf,markdown,*sorted(args.evidence.glob('*.json')),*sorted(args.evidence.glob('*.png'))]:z.write(file,file.name)
        verification=ROOT/'docs/VERIFICATION_LAB_3.9.0.json'
        if verification.exists():
            snapshot=json.loads(verification.read_text());snapshot.pop('reportArtifacts',None)
            z.writestr('verification-snapshot.json',json.dumps(snapshot,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'pdf':str(pdf),'markdown':str(markdown),'evidenceZip':str(bundle),'httpChecks':http['checks'],'nativeComparisonRuns':ext['comparisonRuns'],'browserChecks':ui['checks']},ensure_ascii=False))


if __name__=='__main__':main()
