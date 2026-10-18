#!/usr/bin/env python3
"""Build a measured Korean report and redacted evidence package (no vendor files).

Run verify_auth50_results.py first. The report summarizes observations, including
false positives and inconclusive results; it does not require a perfect detector.
"""
import argparse
import csv
import hashlib
import html
import io
import json
from pathlib import Path
import shutil
import zipfile

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen.canvas import Canvas
from reportlab.platypus import Paragraph, Table, TableStyle
from reportlab.lib.utils import ImageReader

ROOT = Path(__file__).resolve().parents[1]
BLUE = colors.HexColor('#243b64'); INK = colors.HexColor('#20314b')
MUTED = colors.HexColor('#617189'); LINE = colors.HexColor('#dce4ee')
ACTOR = {'admin': 'admin', 'alice': 'Alice', 'bob': 'Bob', 'anonymous': '비로그인'}
SETTING = {'configured': '권한표·판정 규칙 설정', 'unconfigured': '권한표·판정 규칙 미설정'}


def pct(x):
    return '-' if x is None else f'{x*100:.1f}%'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run-dir', type=Path, default=ROOT / 'tmp/auth50-draft')
    p.add_argument('--analysis-dir', type=Path, default=ROOT / 'tmp/auth50-analysis')
    p.add_argument('--ui-dir', type=Path, default=ROOT / 'tmp/auth50-browser')
    p.add_argument('--out', type=Path, default=ROOT / 'docs/reports/2026-10-18-auth50')
    a = p.parse_args(); a.out.mkdir(parents=True, exist_ok=True)
    evidence = ROOT / 'docs/verification/auth50-3.10.0'; evidence.mkdir(parents=True, exist_ok=True)
    summary = json.loads((a.run_dir / 'summary.json').read_text())
    assert summary['nativeExtensionLoaded'] and summary['transport'] == 'actual Burp Montoya HTTP', 'This native report requires an actual Burp run; adapter results can be analyzed with verify_auth50_results.py.'
    native = json.loads((a.run_dir / 'extension-results.json').read_text())
    analysis = json.loads((a.analysis_dir / 'analysis.json').read_text())
    ui = json.loads((a.ui_dir / 'ui-results.json').read_text())
    with (a.analysis_dir / 'function-results.csv').open(encoding='utf-8-sig') as f:
        funcs = [r for r in csv.DictReader(f) if r['setting'] == 'configured']
    assert analysis['result'] == 'passed' and len(funcs) == 50
    for name in ['analysis.json', 'function-results.csv', 'actor-results.csv', 'profile-results.csv']:
        shutil.copyfile(a.analysis_dir / name, evidence / name)
    shutil.copyfile(a.run_dir / 'summary.json', evidence / 'native-summary.json')
    shutil.copyfile(ROOT / 'audit/auth50/expected-policy.csv', evidence / 'expected-policy.csv')
    shutil.copyfile(a.ui_dir / 'ui-results.json', evidence / 'ui-results.json')
    for path in a.ui_dir.glob('auth50-*.png'): shutil.copyfile(path, evidence / path.name)
    font = ROOT / 'project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf'
    if not font.exists():
        font = next(ROOT.rglob('NanumGothic-Regular.ttf'))
    pdfmetrics.registerFont(TTFont('Korean', str(font)))
    W, H = A4; M = 42; CW = W - M * 2
    pdf = a.out / 'Auth50_Report_20261018.pdf'; c = Canvas(str(pdf), pagesize=A4, pageCompression=1)
    c.setTitle('50개 기능 계정 권한 비교 검증 - 2026-10-18'); c.setAuthor('Burp Workbench KR')
    page = 0

    def text(x, y, value, size=10, color=INK):
        c.setFont('Korean', size); c.setFillColor(color); c.drawString(x, y, str(value))

    def para(value, y, size=10, width=CW, x=M, color=INK, leading=None):
        st = ParagraphStyle('p', fontName='Korean', fontSize=size, leading=leading or size*1.65,
                            textColor=color, wordWrap='CJK', spaceAfter=0)
        p = Paragraph(value, st); _, h = p.wrap(width, 1000); p.drawOn(c, x, y-h); return y-h-12

    def new(title, sub):
        nonlocal page
        if page: c.showPage()
        page += 1
        c.setFillColor(BLUE); c.rect(0, H-18, W, 18, fill=1, stroke=0)
        text(M, H-48, 'AUTHORIZATION LAB  /  50 FUNCTIONS', 8, MUTED)
        text(M, H-82, title, 21)
        text(M, H-105, sub, 9, MUTED)
        c.setStrokeColor(LINE); c.line(M, 50, W-M, 50)
        text(M, 32, 'Workbench 0.43.0  |  Lab 3.10.0  |  2026-10-18 (Asia/Seoul)', 8, MUTED)
        text(W-M-22, 32, f'{page:02d}', 9, MUTED)
        return H-136

    def table(rows, widths, y, size=9, padding=7):
        st = ParagraphStyle('cell', fontName='Korean', fontSize=size, leading=size*1.45, textColor=INK, wordWrap='CJK')
        cells = [[Paragraph(str(v), st) for v in row] for row in rows]
        t = Table(cells, colWidths=widths, hAlign='LEFT')
        t.setStyle(TableStyle([('BACKGROUND', (0,0), (-1,0), colors.HexColor('#eaf0f8')),
                               ('VALIGN', (0,0), (-1,-1), 'TOP'),
                               ('LEFTPADDING', (0,0), (-1,-1), 7), ('RIGHTPADDING', (0,0), (-1,-1), 6),
                               ('TOPPADDING', (0,0), (-1,-1), padding), ('BOTTOMPADDING', (0,0), (-1,-1), padding),
                               ('LINEBELOW', (0,0), (-1,-1), .45, LINE),
                               ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, colors.HexColor('#f8fafc')])]))
        _, h = t.wrap(CW, 1000); assert y-h > 64, ('table overflow',page,y,h)
        t.drawOn(c, M, y-h); return y-h-16

    def image(path, y, width=CW, maxheight=400, x=M):
        im = ImageReader(str(path)); iw, ih = im.getSize(); scale = min(width/iw,maxheight/ih)
        c.drawImage(im, x, y-ih*scale, width=iw*scale, height=ih*scale, mask='auto')
        return y-ih*scale-14

    # 1: measured outcome, denominator and inconclusive results together.
    y = new('50개 기능, 계정별 권한 검증', '실제 Burp 확장의 판정과 독립 HTTP·DB 관측을 대조한 결과')
    y = para('권한표와 판정 규칙을 설정했을 때 <b>정밀도 94.9%, 재현율 100.0%</b>를 기록했습니다. '
             '권한 위반 392건을 탐지했고, 실제 차단을 우회로 잘못 표시한 오탐은 21건이었습니다.', y, 13)
    rows = [['계정 대조 2,100개 / 설정별', '규칙 미설정', '규칙 설정'],
            ['정탐 TP / 오탐 FP', '0 / 0', '392 / 21'], ['미탐 FN / 비경고 음성 TN', '392 / 1,708', '0 / 1,687'],
            ['정밀도 TP / (TP + FP)', '산출 불가 (경고 0건)', '94.9% (392 / 413)'],
            ['재현율 TP / (TP + FN)', '0.0%', '100.0% (392 / 392)'],
            ['오탐률 FP / (FP + TN)', '0.0%', '1.23% (21 / 1,708)'],
            ['노랑: 확인 필요 (위 수치와 중복)', '1,337건', '623건']]
    y = table(rows, [251,130,130], y, 10)
    y = para('노랑을 정탐으로 계산하지 않았습니다. 미설정의 미탐 392건 중 385건은 확인 필요, '
             '7건은 실제 저장값이 바뀌었는데도 차단으로 표시됐습니다. 설정 후 노랑 623건은 실제 권한 위반이 없는 대조입니다. '
             'TN은 빨간 오탐이 없었다는 뜻이며, 전부 초록으로 안전을 확정했다는 뜻이 아닙니다.', y)
    y = para('이 수치는 의도적으로 구성한 공통 실습 서버의 측정값입니다. 인증 조합과 설정을 반복한 결과를 독립된 취약점으로 '
             '합산하지 않으며, 운영 사이트에서의 정탐 확률을 보장하지 않습니다.', y, 10, color=MUTED)
    assert y > 65

    # 2: reproducible design and independent oracle.
    y = new('검사 구성과 판정 기준', '50개 업무 기능 × 7개 인증 방식 × 4개 신원 × 정상/취약 대조')
    y = table([['항목','실행 범위'],['신원','admin · Alice · Bob · 비로그인 (인증 헤더·쿠키 없음)'],
               ['인증 방식','세션, 불투명 토큰, JWT, 세션+토큰, 세션+JWT, 토큰+JWT, 세 가지 모두'],
               ['기능','서로 다른 메서드·업무 경로 50개: GET 23개, 실제 쓰기 27개'],
               ['독립 HTTP','2,800개 계정·기능 대조 + 잘못된 인증 조합 33개 / 대상 요청 5,879회'],
               ['실제 확장','1,400개 비교 기록, 계정 응답 5,600개, 기준 제외 대조 4,200개'],
               ['설정별 분모','비교 기록 700개, 기준 제외 계정 대조 2,100개'],
               ['쓰기 관측','확장 요청 전후의 독립 DB 조회 3,024쌍; 별도 관측 HTTP 7,560회'],
               ['웹 브라우저','실제 Chromium: 210개 검사, 1440·1024·390px 화면 확인']], [100,411], y, 9)
    y = para('서버가 선언한 허용 라벨을 정답으로 읽지 않았습니다. 별도 CSV의 검토자 권한표와 실제 결과를 대조했습니다. '
             '읽기는 기능별 비공개 마커 노출, 쓰기는 관리자 전용 관측 경로의 새 DB 조회로 revision·처리 이력·업무 필드의 변경을 확인합니다. '
             '기준 계정은 해당 기능의 합법적인 사용자를 선택하고 정탐 통계에서 제외합니다.', y)
    y = para('정상 경로는 권한표를 적용합니다. 취약 경로는 로그인·CSRF는 검사하되 업무 권한 검사를 생략합니다. '
             '공개 5개·회원 공통 3개를 제외한 42개 기능에서 위반 대조가 생깁니다. 기능별 허용 정책이 다르므로 '
             '관리자도 Alice/Bob 전용 개인정보나 공유 프로젝트에 접근할 수 없는 사례를 포함했습니다.', y)
    y = para('미설정도 계정 지정·SCOPE·쓰기 전송 허용은 켠 상태입니다. 설정에는 권한표, status=DENIED JSON 차단 규칙, '
             '본문 “Denied by access policy”, 쓰기 후 $.data.revision 변경 조회를 추가했습니다. '
             'FORBIDDEN 응답을 이번 결과에 맞춰 추가 보정하지 않았습니다.', y, 9, color=MUTED)
    assert y > 65

    # 3-5: every function, full suffix and per-feature measured detection.
    for part, start in enumerate([0,17,34],1):
        chunk = funcs[start:start+17]
        y = new(f'기능별 결과 {part}/3', f'설정 후 · 기능 {start+1:02d}-{start+len(chunk):02d} · 계정별 비교 합계 (정상+취약, 인증 7개)')
        y = para('URL 접두사: /api/auth-lab/{profile}/{fixed|vuln}/features/  · 아래 표는 그 뒤 경로입니다.<br/>'
                 '허용: A=admin, L=Alice, B=Bob, N=비로그인. 기재하지 않은 계정은 차단이 정답입니다.', y, 8.3)
        rows = [['ID','기능 / 메서드·URL 경로','허용','TP','FP','FN','확인<br/>필요']]
        for r in chunk:
            allow='/'.join({'admin':'A','alice':'L','bob':'B','anonymous':'N'}[x] for x in r['allowedActors'].split('/'))
            rows.append([r['featureId'], html.escape(r['name'])+'<br/><font size="7">'+r['method']+' '+html.escape(r['path'])+'</font>',
                         allow,r['TP'],r['FP'],r['FN'],r['reviewCount']])
        y = table(rows, [25,278,63,32,32,32,49], y, 7.8, padding=3.5)
        y = para('TP=실제 위반 탐지, FP=실제 비위반을 우회로 경고, FN=실제 위반에 빨간 경고 없음. '
                 '확인 필요는 TP/FP/TN/FN과 별도로 겹쳐 집계합니다. 전체 계정별 응답과 권한표는 CSV·근거 ZIP에 있습니다.', y, 8.5, color=MUTED)
        assert y > 65

    # 6: actor and profile comparisons.
    y = new('계정별·인증 조합별 결과', '설정 후 · 동일 인증 구현을 반복한 값이므로 독립 표본으로 해석하지 않음')
    rows = [['계정','대조 수','TP','FP','FN','정밀도','확인 필요']]
    for r in analysis['actors']:
        if r['setting'] == 'configured':
            rows.append([ACTOR[r['actor']],r['samples'],r['TP'],r['FP'],r['FN'],pct(r['precision']),r['reviewCount']])
    y = table(rows,[81,65,58,58,58,89,102],y,9)
    y = para('비로그인은 보호 기능에서 모두 차단됐습니다. 공개 기능의 정상 접근은 허용했습니다. '
             '비로그인 권한 위반이 0건이므로 그 계정의 재현율은 산출할 수 없습니다. '
             '계정별 대조 수가 다른 것은 기능마다 기준 계정이 달라 통계에서 빠지는 수가 다르기 때문입니다.',y,9)
    rows = [['인증 방식','TP','FP','FN','정밀도','확인 필요']]
    for r in analysis['profiles']:
        if r['setting']=='configured':rows.append([r['profile'],r['TP'],r['FP'],r['FN'],pct(r['precision']),r['reviewCount']])
    y=table(rows,[170,58,58,58,79,88],y,9)
    y=para('종합 결과(요청 한 건당 하나) 기준으로는 설정 후 700개 기록에서 TP 294, FP 14, FN 0, TN 392입니다. '
           '정밀도는 95.5%(294/308), 재현율은 100.0%(294/294), 확인 필요는 210개입니다. '
           '계정 대조 기준 94.9%와 분모를 섞지 않습니다.',y,9)
    assert y>65

    # 7: measured errors and why status/effects matter.
    y = new('오탐 21건과 미탐의 원인', '오류를 숨기거나 판정 코드를 수정해 점수를 맞추지 않았습니다')
    y = table([['실제 관측','확장의 판정 / 해석'],
               ['18. Alice 개인정보 CSV 내보내기<br/>정상 경로 / admin·Bob / 인증 7개',
                'HTTP 200 + {error:FORBIDDEN}, 비공개 마커 없음.<br/>설정 후 우회 의심 14건 = 오탐.'],
               ['42. Bob 파일 다운로드<br/>정상 경로 / Alice / 인증 7개',
                'HTTP 200 + {error:FORBIDDEN}, 파일 데이터 없음.<br/>설정 후 우회 의심 7건 = 오탐.'],
               ['50. Bob 지원 요청 종료<br/>취약 경로 / Alice / 인증 7개',
                'HTTP 403이지만 실제 쓰기 처리·revision 증가.<br/>미설정: 차단 7건 = 미탐. 설정 후: 탐지 7건.']], [220,291],y,10)
    hit = next(a for r in native['comparisons'] if r['profile']=='all' and r['setting']=='configured' and r['mode']=='vuln' and r['featureId']==50
               for a in r['accounts'] if a['actor']=='alice')
    e=hit['independentWriteEvidence']; b=e['before']; z=e['after']
    y=para(f'실제 근거 예시: all / configured / vuln / 50 / Alice<br/>'
           f'HTTP {hit["status"]}, revision {b["revision"]:g} → {z["revision"]:g}, '
           f'eventCount {b["eventCount"]:g} → {z["eventCount"]:g}, lastActor=alice, ticketState=CLOSED.<br/>'
           '기준 사용자인 Bob의 전후 조회와 독립 관리자 DB 관측이 변경을 확인했습니다.',y,10)
    y=para('이 실습은 반복 실행도 관측할 수 있도록 실제 요청마다 처리 이벤트와 revision을 증가시킵니다. '
           '이미 CLOSED였던 값만으로 새 변경을 주장하지 않습니다. HTTP 202 접수·204 삭제도 실제 처리 이력으로 판단했습니다.',y,9)
    y=para('고도화 후보: 파일·CSV에서 JSON 오류로 바뀌는 응답의 의미를 우선 확인하고, 사이트별 차단 서명을 보강합니다. '
           'HTTP 성공 코드나 본문 유사도만으로 성공을 확정하지 않아야 합니다. 이는 이번 검사로 찾은 개선안이며 '
           'Workbench 0.43.0의 판정 코드는 수정하지 않았습니다.',y,10,color=MUTED)
    assert y>65

    # 8: actual browser screenshots, not generated mockups.
    y=new('추가한 커뮤니티 실습 화면','실제 Chromium 화면 · 사이드 메뉴 12개 업무 그룹 · 50개 기능 권한표')
    y=image(evidence/'auth50-desktop.png',y,maxheight=340)
    y=para('인증 방식·요청 계정·정상/취약 경로를 선택해 로그인합니다. URL 목록에서 실행하면 실제 응답과 쓰기 전후 조회를 확인합니다. '
           '390px에서는 사이드 메뉴를 접고 표 안을 가로로 이동할 수 있습니다. 전체 페이지 가로 넘침과 브라우저 오류는 없었습니다.',y,10)
    y=image(evidence/'auth50-denial-after-write.png',y,maxheight=175)
    assert y>60

    # 9: reproducibility and honest limits. Remote data can be updated separately.
    y=new('환경·재현·해석의 범위','실제 확장 실행과 API 어댑터·Docker 배포 검사를 구분합니다')
    y=table([['항목','확인 내용'],['실제 Burp','Community 2026.3.3 / Java 21.0.8 / Montoya 실제 HTTP'],
             ['확장','기존 0.43.0 JAR 그대로 로드, 판정 로직·500ms 대기 유지'],
             ['병렬 실행','인증 방식별 7개 독립 운영 비교 컨트롤러 인스턴스'],
             ['DB','상세 HTTP·native 대조: 격리 H2 Oracle 호환 모드'],
             ['신원 검사','로그인 계정 21개 신원 일치·독립 비로그인 거부'],
             ['주의 기록','헤드리스 Burp 시작 중 내부 ComponentUI 경고 2건. HTTP·판정·종료는 성공.'],
             ['원격 검증','최종 GitHub CI의 Oracle/Nginx·브라우저·회귀 결과는 별도 검증 JSON에 기록.']], [102,409],y,9)
    y=para('재현: python dev.py build → python dev.py http-test → tools/check_auth50.py --mode native '
           '(별도 Burp JAR·Java 21·임시 프로필 필요). CI는 --mode adapter로 실제 HTTP와 같은 운영 판정 코드를 실행하며 '
           '이를 실제 Burp 실행으로 표시하지 않습니다. tools/verify_auth50_results.py는 1,400개 기록의 누락·중복, '
           '4개 계정·독립 관측·정답 도달 여부와 통계를 다시 계산합니다.',y,9)
    y=para('일곱 조합은 하나의 발급 시스템 안에서 세션·토큰·JWT를 묶는 실습입니다. MFA의 독립 인증 요소가 아니며 '
           '외부 OAuth/OIDC·SAML·다른 JWT 서명 체계를 검증한 결과가 아닙니다. 공통 권한 엔진을 사용하는 50개 업무 기능이므로 '
           '서로 다른 운영 서비스 50개에서 얻은 확률로 해석할 수 없습니다. Burp GUI 클릭·Windows DPI/IME는 수행하지 않았습니다.',y,9)
    y=para('근거 ZIP: 전체 가림 처리 HTTP·확장 결과, 계정/기능/인증 조합 CSV, 정책표, UI PNG, 실행 요약, 보고서. '
           '요청 인증값·키·로그인 원문과 Burp 공급업체 JAR는 포함하지 않습니다. 응답 해시는 실제 본문 바이트의 SHA-256입니다. '
           '다운로드 ZIP과 해시·현재 소스 일치 검사는 별도 배포 명세로 확인합니다.',y,9)
    y=para('출처: audit/auth50/expected-policy.csv, native-summary.json, analysis.json, ui-results.json. '
           '추가 배포·원격 검사: docs/VERIFICATION_LAB_3.10.0.json. PDF 생성에 사용한 원본 결과의 해시는 analysis.json에 있습니다.',y,8,color=MUTED)
    assert y>65
    c.save()

    md = ['# 50개 기능 계정 권한 비교 검증\n',
          '2026-10-18 / Workbench 0.43.0 / 실습 사이트 3.10.0\n',
          '실제 Burp Community 2026.3.3 + H2 Oracle 호환 모드. 의도된 실습 대조의 결과이며 운영 정탐 확률이 아닙니다.\n',
          '| 설정 | TP | FP | TN | FN | 정밀도 | 재현율 | 확인 필요 |\n|---|---:|---:|---:|---:|---:|---:|---:|']
    for s in ['unconfigured','configured']:
        r=analysis['metrics'][s]['account']; md.append(f'| {SETTING[s]} | {r["TP"]} | {r["FP"]} | {r["TN"]} | {r["FN"]} | {pct(r["precision"])} | {pct(r["recall"])} | {r["reviewCount"]} |')
    md += ['\n정밀도=TP/(TP+FP), 재현율=TP/(TP+FN). 빨간 SUSPECT만 탐지로 계산하고 노랑 REVIEW는 미탐에 포함합니다. '
           'TN은 빨간 오탐 없음이며 확정 차단/안전과 다릅니다. 기준 계정은 제외합니다.\n',
           '미설정도 계정·SCOPE·쓰기 허용은 설정했습니다. 추가 규칙은 권한표, JSON status=DENIED, 본문 Denied by access policy, '
           '쓰기 전후 $.data.revision 변경 조회입니다.\n',
           '오탐: 18번 개인정보 CSV의 admin/Bob 14건 + 42번 파일 다운로드 Alice 7건. 정상 HTTP 200 FORBIDDEN 응답이며 데이터 노출 없음. '
           '50번의 403 뒤 실제 쓰기는 미설정에서 7건 차단 오판, 설정 후 탐지했습니다.\n',
           '비로그인 보호 경로는 차단되어 위반 양성 사례가 없습니다. 5개 공개 기능의 정상 접근은 허용했습니다.\n',
           '| ID | 기능 | 메서드·경로 | 허용 계정 | TP | FP | FN | 확인 필요 |\n|---:|---|---|---|---:|---:|---:|---:|']
    for r in funcs: md.append('| '+' | '.join([r['featureId'],r['name'],r['method']+' '+r['path'],r['allowedActors'],r['TP'],r['FP'],r['FN'],r['reviewCount']])+' |')
    md += ['\nURL 접두사: `/api/auth-lab/{profile}/{fixed|vuln}/features/`。\n',
           'HTTP 2,800개 + 잘못된 인증 조합 33개, 실제 확장 1,400개 비교 기록·기준 제외 4,200개 대조. '
           '설정별 동일 2,100개 대조입니다. 50개 기능은 읽기23·쓰기27이며 동일 서버의 공통 엔진과 7개 인증 조합 반복을 포함합니다.\n',
           '실제 DB 전후 조회는 처리 이력·업무 필드·revision을 확인합니다. 반복 요청은 처리 이벤트를 증가시킵니다. '
           '같은 CLOSED 값만으로 실행 성공을 주장하지 않습니다.\n',
           'Native 실행은 실제 JAR·Montoya HTTP, CI 어댑터는 실제 loopback HTTP+API 대체 환경입니다. '
           '독립 관리자 관측 HTTP 7,560회는 Burp 대상 요청 10,822회에 포함하지 않습니다.\n',
           '전체 원본 가림 결과·CSV·UI·요약은 동봉 근거 ZIP, 배포/원격 검사 상세는 ../../VERIFICATION_LAB_3.10.0.json을 참고하세요.\n']
    (a.out/'Auth50_Report_20261018.md').write_text('\n'.join(md))
    package = a.out/'Auth50_Evidence_20261018.zip'
    with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name in ['extension-results.json','http-results.json','summary.json']:
            z.write(a.run_dir/name, 'raw/'+name)
        for path in sorted(a.analysis_dir.iterdir()):
            if path.is_file():z.write(path,'analysis/'+path.name)
        for path in sorted(evidence.glob('*.png')):z.write(path,'ui/'+path.name)
        z.write(evidence/'ui-results.json','ui/ui-results.json')
        z.write(evidence/'expected-policy.csv','expected-policy.csv')
        z.write(pdf,pdf.name); z.write(a.out/'Auth50_Report_20261018.md','Auth50_Report_20261018.md')
    with zipfile.ZipFile(package) as z:assert z.testzip() is None
    print(json.dumps({'pdf':str(pdf),'pages':page,'evidenceZip':str(package),'zipBytes':package.stat().st_size},ensure_ascii=False))


if __name__=='__main__': main()
