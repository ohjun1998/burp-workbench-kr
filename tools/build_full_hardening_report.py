#!/usr/bin/env python3
"""Build the 0.49.0 Korean report from retained, current verification evidence."""
from pathlib import Path
import base64
import html
import io
import json
import re

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/reports/2026-10-20-full-hardening/Workbench_Full_Hardening_0.49.0_KR.html'
BASE = 'https://github.com/ohjun1998/burp-workbench-kr'


def esc(value):
    return html.escape(str(value))


def table(headers, rows):
    return '<div class="table-wrap"><table><thead><tr>' + ''.join('<th>'+esc(x)+'</th>' for x in headers) + '</tr></thead><tbody>' + ''.join('<tr>'+''.join('<td data-label="'+esc(headers[i])+'">'+esc(x)+'</td>' for i,x in enumerate(row))+'</tr>' for row in rows) + '</tbody></table></div>'


def main():
    v = json.loads((ROOT/'docs/VERIFICATION_0.49.0.json').read_text())
    check = v['releaseCheck']
    assert check['result'] == 'passed'
    sections = []
    sections.append('<section id="summary"><h2>이번 작업의 결론</h2><p>11개 도구를 유지하면서 수집·매핑, JS 분석, 신원 대조, 대형 응답과 증거 내보내기를 보강했습니다. 독립 검토에서 찾은 결함은 회귀 사례로 남겼고, 수정한 최종 소스로 자동 검사를 다시 수행했습니다.</p><p class="notice"><b>전체 실사용 검증 완료를 의미하지 않습니다.</b> 실제 Burp는 라이선스 동의 단계에서, 로컬 Chrome은 소켓 제한으로 멈췄습니다. 추가 실습 사이트 취약점 재현도 실행 환경 보안 검사로 중단됐습니다. 이 항목들은 이번 성공 건수에 포함하지 않았습니다.</p>')
    sections.append('<div class="stats">' + ''.join(f'<div><strong>{esc(n)}</strong><span>{esc(label)}</span></div>' for n,label in [(f"{check['totalChecks']:,}",'회귀·격리 HTTP 검사'),(f"{v['mapper']['javaChecks']} / {v['mapper']['nodeChecks']}",'Mapper Java / Node'),(v['ui']['totalViews'],'Swing 화면 렌더'),('11','유지한 도구')]) + '</div><p class="muted">검사 수는 서로 다른 종류의 조건을 합한 값입니다. 취약점 탐지율이나 운영 환경의 정확도 분모로 사용할 수 없습니다. 동일 검사의 재실행은 더하지 않았습니다.</p></section>')
    changes = [
      ('수집과 매핑','수집 제외·실패 원인 및 유사 요청 구분이 불충분','수신·저장·OFF/Scope 제외·실패를 구분. 같은 클릭의 메서드·본문 차이 보존. 브라우저 도우미 초기화 경쟁과 이전 연결 오류 표시 수정'),
      ('JS 정적 분석','분할 구간의 객체·함수 문맥 손실 및 변경 전 값의 오확정','제한된 불변 객체 속성·단순 반환 함수를 연계. 변경·별칭·동적 실행은 미확정 처리. 정적 요청 객체의 호출 위치 보존'),
      ('신원 대조','401/403의 세션 원인 분리 부족','선택한 동일 대상 GET에서 인증 신원과 독립 익명 대조 후 검사. 중단 뒤 신원을 재확인하되 페이로드는 재개하지 않음'),
      ('대형 응답','앞부분만 비교한 결과를 전체로 오인할 가능성','경로 비교는 1Mi 문자 또는 앞뒤512Ki 문자와 미검사 중간을 표시. JSON 구조 순차검사·본문 제한·큐 거절·오류를 별도 표시'),
      ('증거 가림','서비스 키·접힌 헤더·응답 발급 쿠키 반사와 JSON 특수문자 누락','추가 민감 필드 이름 설정, 구조 기반 가림, 원본/내보낸 해시와 생략 정책 보존. 숫자값·공개 쿠키 속성 오가림 수정'),
      ('출처와 진행 상태','URL 발견과 GET 검증, 수동 완료와 실제 검사 혼동','관측/정적/아카이브/수동 출처와 메서드 근거 분리. GET 시도·결과·시간 별도. 검사 미시도·증거 미저장 필터'),
    ]
    sections.append('<section id="changes"><h2>고도화 전후</h2>'+table(['영역','이전의 미흡한 부분','0.49.0 동작'],changes)+'</section>')
    review = v['reviewFixes']
    sections.append('<section id="review"><h2>재검증 중 발견하여 수정한 결함</h2>'+table(['결함','수정·재검증'],[(x['problem'],x['resolution']) for x in review])+'</section>')
    metrics = v['auth50Metrics']
    metric_rows = []
    for key, label in [('unconfigured','권한표·쓰기 조회 미설정'),('configured','권한표·쓰기 조회 설정')]:
        m = metrics[key]['account']
        metric_rows.append((label,m['samples'],m['TP'],m['FP'],m['TN'],m['FN'],m['reviewCount']))
    sections.append('<section id="accuracy"><h2>남은 핵심 문제: 설정에 따라 달라지는 탐지</h2><p>50개 업무 기능을 7개 인증 방식으로 비교한 <b>동일 0.49.0 JAR의 실행 자료</b>를 이번에 재집계했습니다. 실제 loopback HTTP와 운영 비교 컨트롤러의 API 어댑터 검사입니다. 실제 Burp 전체 재점검이나 기존 7개 사이트의 새 측정은 아닙니다.</p>'+table(['설정 상태','계정 대조','정탐','오탐','정상 구분','미탐','보류*'],metric_rows)+'<p class="notice"><b>설정 후 미탐 0건만으로 기본 설정도 충분하다고 볼 수 없습니다.</b> 권한표·쓰기 전후 조회가 없으면 392건을 놓칩니다. 이 중 385건은 보류이고, 7건은 차단 응답 뒤 실제 저장 변경이 발생한 사례입니다. 설정 공백을 알아보기 쉽게 안내하고 실제 상태를 조회하도록 돕는 흐름이 다음 개선 우선순위입니다.</p><p class="muted">* 보류는 정탐·오탐·정상 구분·미탐과 중복될 수 있어 합산하지 않습니다. 설정 후 보류 91건은 302 리다이렉트 7건과 쓰기 조회값이 변하지 않은 404 63건·410 21건입니다. 이번 표본에서 금지된 변화가 없었어도, 명시적 차단과 업무 결과를 확정할 근거는 부족합니다.</p></section>')
    tool_rows = [
      ('요청·응답 목록','수집 경로·제외·저장 실패 집계, Scope 경계','수집·동시성·복구 및 화면 회귀','실제 Burp 0.49 수집 흐름'),
      ('기능 자동 매핑','메서드·본문별 패킷 보존, 연결 상태 구분','Java·Node·Swing, Bridge fixture','실제 Chrome/Edge MV3 연결'),
      ('계정 권한 비교','기존 정책·신원·쓰기 조회 유지','기존 정책 회귀와 API 어댑터 대조','다중 실서버·SSO 환경'),
      ('데이터 변환·비교','JWT 해독·서명 미검증·인가 미확인을 구분','변환·비교 회귀와 화면','실제 서명·인가 검증은 별도'),
      ('JS에서 API 찾기','구간 간 제한된 정적 객체·함수 연결','정적/변이/동적 실행 부정 대조','복잡한 런타임 상태·별칭 전파'),
      ('서버·경로 탐색','대형 본문·상태·불완전 비교 분리','환경 비교·HTTP·화면 회귀','한도 밖 본문과 실제 서버 설정'),
      ('URL 후보 수집','출처·메서드 근거와 GET 검증 분리','출처·대표군·증거 연결 회귀','외부 아카이브 실연동'),
      ('점검 진행 현황','검사 미시도·증거 미저장 필터','수동 완료와 검사 기록의 독립 회귀','실제 업무 완료 판단'),
      ('응답 보안 분석','큰 JSON 구조 검사와 미검사 범위 표시','문자/노드/깊이·Scope·큐 제한 회귀','압축·본문 한도 밖과 미지원 형식'),
      ('입력값 보안 검사','선택적 신원 대조와 요청 간격·예산','일치/불일치/미확인, 취소·401 중단','실서버의 권한·CSRF·업무 원인'),
      ('재현·증거 정리','구조 기반 가림·정책·해시·종료 경합','키 반사·JSON·저장·미리보기 회귀','이미지 및 알려지지 않은 개인정보'),
    ]
    sections.append('<section id="tools"><h2>11개 도구별 확인 범위</h2>'+table(['도구','변경 또는 유지한 핵심','이번 확인 방식','남은 확인'],tool_rows)+'<p class="muted">아래 자동 회귀와 화면 렌더가 범위의 근거입니다. 이 표의 11개 행을 실제 Burp 수동 조작 11건으로 계산하지 않습니다.</p></section>')
    checks = [(x['name'],x['result'],x['detail']) for x in v['verificationRows']]
    evidence = base64.b64encode(json.dumps(v, ensure_ascii=False, indent=2).encode()).decode()
    csv = base64.b64encode((ROOT/'docs/verification/full-0.49.0/auth50-classification-reasons.csv').read_bytes()).decode()
    sections.append('<section id="checks"><h2>실행한 검증과 근거</h2>'+table(['검증','결과','실행 범위'],checks)+'<p>전체 그룹별 수치·명령·파일 해시는 <a download="VERIFICATION_0.49.0.json" href="data:application/json;base64,'+evidence+'">검증 JSON 내려받기</a>로 확인할 수 있습니다. <a download="auth50-classification-reasons.csv" href="data:text/csv;base64,'+csv+'">정탐·정상·미탐 사유 CSV</a>도 포함했습니다. 앞선 실패 로그는 최종 통과에 합산하지 않고, 수정 원인을 추적하는 기록으로 보존했습니다.</p></section>')
    figures = []
    for shot in v.get('screenshots',[]):
        p=ROOT/shot['path']
        encoded=base64.b64encode(p.read_bytes()).decode()
        figures.append('<figure><img src="data:image/png;base64,'+encoded+'" alt="'+esc(shot['caption'])+'"><figcaption>'+esc(shot['caption'])+'</figcaption></figure>')
    if figures:
        sections.append('<section id="screens"><h2>최종 JAR의 화면 배치</h2><p class="muted">운영 Swing 컴포넌트에 합성 데이터와 API 어댑터를 넣어 렌더링했습니다. 실제 Burp 데스크톱 클릭, 브라우저 DOM 실행, Windows DPI/IME 증빙과 구분합니다.</p><details><summary>확장 화면 3개 펼쳐 보기</summary>'+''.join(figures)+'</details></section>')
    sections.append('<section id="remaining"><h2>아직 남은 고도화·검증</h2>'+table(['우선순위','남은 범위','완료로 인정할 조건'],[(x['priority'],x['scope'],x['acceptance']) for x in v['remaining']])+'<p class="notice">신원 유지가 확인되어도 대상 요청의 권한·CSRF·업무 조건 원인을 확정할 수 없습니다. 정적 분석 후보, 브라우저 실행, 서버 상태 변화, 외부 콜백은 각 근거를 따로 확보해야 합니다.</p></section>')
    ref=esc(v['sourceRef'])
    links = '<p>설치 JAR·동일 소스 ZIP·브라우저 도우미는 로컬에서 검증하고 준비했습니다. 아직 원격에 게시하지 않아 다운로드 링크를 연결하지 않았습니다.</p>'
    if v.get('githubPublished'):
        links = '<p><a href="'+BASE+'/raw/'+ref+'/downloads/extensions/0.49.0/burp-workbench-kr-0.49.0.jar">Workbench 0.49.0 JAR</a> · <a href="'+BASE+'/raw/'+ref+'/downloads/extensions/0.49.0/burp-workbench-kr-0.49.0-source.zip">동일 소스 ZIP</a> · <a href="'+BASE+'/raw/'+ref+'/downloads/feature-mapper/0.3.3/feature-mapper-browser-0.3.3.zip">브라우저 도우미 0.3.3</a></p>'
    sections.append('<section id="download"><h2>배포 파일과 이력</h2>'+links+table(['파일','SHA-256'],[(x['name'],x['sha256']) for x in v['artifacts']])+'<p class="notice">'+esc(v['githubStatus'])+'</p><p>0.48.0 및 그 이전 실제 사이트 결과는 <a href="'+BASE+'/blob/main/docs/RELEASE_0.48.0_KR.md">이전 검증 기록</a>으로 보존합니다. 기존 XSS·SQL·계정 대조 수치를 이번 결과로 재사용하지 않았습니다. 0.21.0 원본과 기존 다운로드도 보존했습니다.</p></section>')
    style='''*{box-sizing:border-box}body{margin:0;background:#f2f5f8;color:#16273b;font:16px/1.7 "Noto Sans KR","Malgun Gothic",Arial,sans-serif}main{max-width:1220px;margin:auto;padding:36px 28px 72px}header{padding:38px;background:#142f4d;color:white;border-radius:18px}header p{max-width:850px;color:#d4e5f3}h1{font-size:34px;line-height:1.3;margin:12px 0}h2{font-size:24px;line-height:1.4;margin:0 0 20px}section{background:white;border:1px solid #dce4ed;border-radius:14px;padding:30px;margin-top:24px}nav{display:flex;gap:12px;flex-wrap:wrap;margin-top:24px}nav a{background:white;color:#173f68;text-decoration:none;border-radius:8px;padding:8px 14px;border:1px solid #dce4ed}a{color:#0864a8}p{margin:12px 0}.muted,figcaption{color:#53667b;font-size:14px}.notice{padding:16px 20px;background:#fff5df;border-left:4px solid #d99a26}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:15px;margin:24px 0}.stats div{background:#edf5fb;border-radius:10px;padding:18px}.stats strong{display:block;font-size:29px;color:#135c92}.stats span{font-size:14px}.table-wrap{overflow-x:auto}table{width:100%;border-collapse:collapse;font-size:14px;line-height:1.65}th{text-align:left;color:#365169;background:#eef3f8}th,td{padding:13px 15px;border-bottom:1px solid #dfe7ee;vertical-align:top;overflow-wrap:anywhere}td:first-child{font-weight:600;min-width:115px}figure{margin:24px 0}img{display:block;max-width:100%;height:auto;border:1px solid #cad6e2;border-radius:8px}figcaption{margin-top:8px}footer{padding:22px 4px;color:#53667b;font-size:13px}@media(max-width:700px){main{padding:16px 12px}header,section{padding:22px 18px}h1{font-size:27px}.stats{grid-template-columns:repeat(2,1fr)}table{min-width:700px}}@media print{body{background:white}main{padding:0}nav{display:none}section{break-inside:avoid;border:0;padding:18px 0}a{color:inherit}.table-wrap{overflow:visible}header{background:#eef3f8;color:#16273b}header p{color:#365169}h2{break-after:avoid}figure{break-inside:avoid}}'''
    nav=[('summary','결론'),('changes','전후 변화'),('accuracy','탐지와 설정'),('tools','11개 도구'),('checks','검증 결과'),('remaining','남은 범위'),('download','배포')]
    page='<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Workbench 0.49.0 전체 고도화·재검증</title><style>'+style+'</style><main><header><div>BURP WORKBENCH KR · 2026-10-20</div><h1>전체 고도화와 재검증</h1><p>Workbench 0.49.0 · 내장 Feature Mapper 0.3.3 · 실습 사이트 3.10.0<br>수정한 결함, 실제 수행한 검사, 환경 제약으로 남은 범위를 분리한 최종 기록</p></header><nav>'+''.join('<a href="#'+key+'">'+title+'</a>' for key,title in nav)+'</nav>'+''.join(sections)+'<footer>검증일: 2026-10-20 (한국 시간). 최신 결론은 이 문서와 연결한 0.49.0 검증 JSON 기준입니다.</footer></main></html>'
    OUT.parent.mkdir(parents=True,exist_ok=True)
    # Embed the repository's licensed Korean font so a minimal Linux renderer and
    # an offline mobile viewer display the same legible text.
    font_path=ROOT/'project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf'
    font_bytes=font_path.read_bytes()
    try:
        from fontTools import subset
        font=subset.load_font(str(font_path),subset.Options())
        subsetter=subset.Subsetter()
        subsetter.populate(text=html.unescape(re.sub('<[^>]*>',' ',page)))
        subsetter.subset(font)
        output=io.BytesIO();font.save(output);font_bytes=output.getvalue()
    except ImportError:
        pass
    font_css='@font-face{font-family:ReportKorean;src:url(data:font/ttf;base64,'+base64.b64encode(font_bytes).decode()+')}body{font-family:ReportKorean,"Malgun Gothic",Arial,sans-serif}'
    mobile_css='@media(max-width:700px){section:not(#accuracy) table{min-width:0}section:not(#accuracy) thead{display:none}section:not(#accuracy) tr{display:block;padding:12px 0;border-bottom:1px solid #dfe7ee}section:not(#accuracy) td{display:block;padding:5px 0;border:0;min-width:0}section:not(#accuracy) td:before{content:attr(data-label);display:block;font-size:12px;font-weight:400;color:#53667b}}summary{cursor:pointer;font-weight:600;color:#135c92}'
    page=page.replace('</style>','</style><style>'+font_css+mobile_css+'</style>',1)
    OUT.write_text(page,encoding='utf-8')
    print(OUT)


if __name__=='__main__':
    main()
