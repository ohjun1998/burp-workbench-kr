#!/usr/bin/env python3
"""Build the screenshot guide from the production Simple Swing captures (no mock UI)."""
from __future__ import annotations

import argparse
import base64
import html
import os
from pathlib import Path

from PIL import Image as PILImage
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A3, landscape
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph

ROOT = Path(__file__).resolve().parents[1]
VERSION = '1.0.0'
CAPTURE_NOTE = '그림은 Simple 운영 Swing 화면에 합성 예제 패킷을 넣어 렌더링한 캡처입니다. 실제 Burp GUI를 조작한 캡처가 아닙니다.'
STEPS = [
    {
        'number': '00', 'stage': '시작', 'title': 'Simple을 열고 네 단계의 작업 화면을 확인합니다',
        'image': '00-start.png',
        'actions': ['Burp Extensions → Add → Java에서 Simple JAR를 추가한 뒤 Workbench Simple 탭을 엽니다.',
                    '상단의 1 수집, 2 분석, 3 검증, 4 증거로 바로 이동합니다. 오른쪽 도움말·설정에서 현재 도구 안내와 화면 설정을 엽니다.'],
        'check': '다음 확인: 처음에는 수집이 OFF입니다. 요청을 선택하기 전 비교·Repeater·증거 버튼은 비활성화됩니다.',
        'caption': 'Simple 시작 화면의 운영 Swing 캡처 · Burp 설치 메뉴 자체의 캡처는 아닙니다.'
    },
    {
        'number': '01', 'stage': '1 수집', 'title': 'SCOPE에 점검할 대상을 등록합니다',
        'image': '00-scope-editor.png',
        'actions': ['Simple의 SCOPE 목록을 열고 실제 점검할 URL을 추가합니다. 경로를 지정하면 해당 경로와 하위 경로가 대상입니다.',
                    '수집할 범위의 체크 상태를 확인합니다. Burp HTTP history의 Add to scope로 등록한 범위도 이 목록에서 확인합니다.'],
        'check': '다음 확인: 의도한 프로토콜·호스트·포트·경로만 수집 범위에 들어 있는지 확인합니다.',
        'caption': '실제 SCOPE 편집 컴포넌트 · 예제 주소는 합성 자료입니다.'
    },
    {
        'number': '02', 'stage': '1 수집', 'title': 'SCOPE 수집을 켜고 사이트를 사용합니다',
        'image': '01-capture.png',
        'actions': ['수집 버튼을 OFF에서 SCOPE로 바꾼 뒤 Burp 브라우저에서 대상 기능을 사용합니다.',
                    '목록에 요청이 표시되는지 확인합니다. 기존 History는 SCOPE 수집을 켠 상태에서 필요한 요청을 골라 Simple로 가져옵니다.'],
        'check': '다음 확인: OFF에서는 새 트래픽과 수동 가져오기를 수집하지 않습니다. 목록 고정은 화면 갱신만 멈춥니다.',
        'caption': 'SCOPE ON 상태의 요청 목록 · 화면의 요청과 응답은 합성 자료입니다.'
    },
    {
        'number': '03', 'stage': '1 수집', 'title': '원본을 고르고 바로 다음 작업으로 이어갑니다',
        'image': '02-request.png',
        'actions': ['검색으로 요청을 찾고 행을 선택해 요청·응답 원문을 읽습니다. 여러 건은 Ctrl 또는 Shift로 선택합니다.',
                    '계정 비교 준비, Repeater 준비, 증거에 추가 중 필요한 작업을 누릅니다. 선택 전에는 세 버튼이 비활성화됩니다.'],
        'check': '다음 확인: Repeater 준비는 요청을 복사하는 동작입니다. 실제 전송은 Repeater의 Send로 직접 실행합니다.',
        'caption': '선택 원본과 직접 작업 버튼 · 선택한 원본 연결을 다음 화면에서도 유지합니다.'
    },
    {
        'number': '04', 'stage': '2 분석', 'title': '필요한 분석 도구 하나를 선택합니다',
        'image': '03-analysis.png',
        'actions': ['2 분석에서 기능 자동 매핑, JS에서 API 찾기, 응답 보안 분석, 서버·경로 탐색 중 목적에 맞는 도구를 고릅니다.',
                    '선택한 원본·출처와 분석 근거를 함께 확인합니다. 추가 요청이 필요한 확인은 도구의 실행 버튼으로 직접 시작합니다.'],
        'check': '다음 확인: 분석 결과는 검토할 단서입니다. 화면을 바꾸는 것만으로 대상 요청을 전송하지 않습니다.',
        'caption': '기존 응답 분석 엔진을 사용하는 Simple 분석 화면 · 예제 결과입니다.'
    },
    {
        'number': '05', 'stage': '3 검증', 'title': '비교할 계정과 판정 조건을 준비합니다',
        'image': '04-auth-accounts.png',
        'actions': ['인증 탐지를 ON으로 켜고 필요한 계정으로 사이트를 사용합니다. 탐지된 후보 행을 우클릭해 관리자·일반·후보1 중 비교 슬롯에 지정합니다.',
                    '선택 요청 비교 또는 계정·판정 설정에서 계정, 허용·차단 기준과 필요한 세션·쓰기 후 조회 조건을 확인합니다.'],
        'check': '다음 확인: 인증 탐지는 수집 OFF/SCOPE와 별도입니다. 후보 수집은 자동 비교 실행이 아니며 실제 비교에는 SCOPE가 필요합니다.',
        'caption': '운영 계정 준비 컴포넌트 · 계정과 인증값은 합성 예제이며 실제 로그인 검증 결과가 아닙니다.'
    },
    {
        'number': '06', 'stage': '3 검증', 'title': '설정을 확인한 뒤 비교를 직접 시작합니다',
        'image': '04-auth-results.png',
        'actions': ['수집 목록에서 원본을 선택하고 계정 비교 준비를 누릅니다. 조건을 바꿨다면 적용 상태 다시 확인 후 비교 시작(제한이 있으면 제한 확인 후 비교)을 누릅니다.',
                    '결과에서 원본·계정별 응답과 판정 근거를 읽습니다. 진행 중인 비교는 중지로 멈추고 필요한 조건을 보완합니다.'],
        'check': '다음 확인: 상태 코드 차이만으로 권한 문제를 확정하지 않습니다. 같은 인증·세션 오류·쓰기 결과 미확인은 근거와 함께 검토합니다.',
        'caption': '계정 비교 결과 목록 · 화면의 결과는 합성 예제이며 이 캡처를 만들 때 HTTP를 전송하지 않았습니다.'
    },
    {
        'number': '07', 'stage': '4 증거', 'title': '원본·비교 결과를 모으고 재현 절차를 적습니다',
        'image': '05-evidence.png',
        'actions': ['수집 화면의 증거에 추가 또는 4 증거의 증거 추가 메뉴를 사용합니다. 비교 결과는 결과 목록에서 저장할 결과를 선택한 뒤 권한 비교 추가로 가져옵니다.',
                    '제목, 영향, 재현 절차를 작성하고 항목 설명으로 각 증거의 의미를 적습니다. 순서 변경·제거는 실행 취소와 다시 실행으로 복구합니다.'],
        'check': '다음 확인: 권한 비교 추가는 실행 당시 원본·계정·조회와 판정 조건을 함께 보존합니다. 작성 초안만 복원한 경우 원본은 직접 연결합니다.',
        'caption': '증거 작성 화면 · 전체 업무 관리 대신 원본 연결과 보고서 작성에 집중합니다.'
    },
    {
        'number': '08', 'stage': '4 증거', 'title': '가림 결과를 확인하고 ZIP으로 저장합니다',
        'image': '06-redaction.png',
        'actions': ['가림 미리보기를 열어 보고서와 패킷에 인증값·민감한 값이 남았는지 확인합니다. 추가 이름은 더 보기 → 가림 설정·본문 포함에서 지정합니다.',
                    '직접 적은 문장과 첨부 이미지를 확인한 뒤 증거 ZIP 저장을 누르고 저장 위치를 지정합니다. 저장 완료 상태를 확인합니다.'],
        'check': '완료 확인: ZIP의 report.html 또는 report.md와 evidence.json을 열어 설명·순서·원본 연결·가림 결과가 의도한 대로 들어 있는지 확인합니다.',
        'caption': '운영 가림 미리보기 컴포넌트 · 합성 인증값이 가려진 제출용 내용을 보여 줍니다.'
    },
]

TOOLS = [
    ('수집', '요청·응답 목록 / 변환·비교', 'SCOPE, 수집, 검색, 원본 확인과 직접 작업'),
    ('분석', '기능 자동 매핑 / JS에서 API 찾기 / 응답 보안 분석 / 서버·경로 탐색', '출처를 따라 단서를 확인하고 필요한 분석을 선택'),
    ('검증', '계정 권한 비교 / 입력값 보안 검사', '조건을 준비한 뒤 명시적으로 실행하고 근거 검토'),
    ('증거', '증거 작성 / 가림 미리보기 / ZIP 저장', '원본·실행 결과와 설명을 묶어 저장'),
]


def esc(text: str) -> str:
    return html.escape(text)


def image_uri(path: Path) -> str:
    return 'data:image/png;base64,' + base64.b64encode(path.read_bytes()).decode('ascii')


def write_html(images: Path) -> Path:
    blocks = []
    for step in STEPS:
        path = images / step['image']
        blocks.append(f'''<section id="step-{step['number']}" class="step"><div class="eyebrow">{esc(step['stage'])} / STEP {step['number']}</div>
<h2>{esc(step['title'])}</h2><ol>{''.join('<li>'+esc(x)+'</li>' for x in step['actions'])}</ol>
<figure><a href="{image_uri(path)}" download="{esc(path.name)}"><img src="{image_uri(path)}" alt="{esc(step['caption'])}"></a><figcaption>{esc(step['caption'])}</figcaption></figure>
<p class="check">{esc(step['check'])}</p></section>''')
    output = ROOT / 'docs/GUIDE_SIMPLE_KR.html'
    rows = ''.join('<tr>'+''.join('<td>'+esc(c)+'</td>' for c in row)+'</tr>' for row in TOOLS)
    output.write_text(f'''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Workbench Simple {VERSION} 사용 가이드</title><style>
:root{{--ink:#17233b;--muted:#53627c;--accent:#315fcb;--line:#dbe3f0;--paper:#fff;--canvas:#f2f5fa}}
*{{box-sizing:border-box}}body{{margin:0;color:var(--ink);background:var(--canvas);font:16px/1.7 system-ui,-apple-system,"Malgun Gothic",sans-serif}}main{{max-width:1240px;margin:auto;padding:40px 24px 80px}}h1{{font-size:42px;line-height:1.2;margin:12px 0 18px}}h2{{font-size:28px;line-height:1.3;margin:8px 0 18px}}h3{{font-size:20px}}a{{color:var(--accent)}}.cover,.step,.appendix{{background:var(--paper);border:1px solid var(--line);border-radius:18px;padding:32px;margin-bottom:28px}}.eyebrow{{font-size:13px;font-weight:700;letter-spacing:.1em;color:var(--accent)}}.lead{{font-size:20px;max-width:900px}}.flow{{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:28px 0}}.flow a{{padding:15px;background:#eaf0ff;border-radius:10px;text-decoration:none;font-weight:700;text-align:center}}.note,.check{{border-left:4px solid var(--accent);padding:12px 16px;background:#f2f6ff;color:#30445f}}.muted,figcaption{{font-size:13px;color:var(--muted)}}li{{margin:7px 0}}figure{{margin:24px 0 14px}}figure img{{width:100%;height:auto;border:1px solid var(--line);border-radius:8px}}figcaption{{margin-top:8px}}code{{font-size:14px;background:#edf1f7;padding:2px 5px;border-radius:4px}}pre{{white-space:pre-wrap}}table{{width:100%;border-collapse:collapse;font-size:14px}}td,th{{padding:12px;text-align:left;border-bottom:1px solid var(--line);vertical-align:top}}th{{background:#f4f7fc}}.back{{display:block;text-align:right;margin-top:20px}}@media(max-width:640px){{main{{padding:16px 10px 40px}}.cover,.step,.appendix{{padding:22px 18px}}h1{{font-size:32px}}h2{{font-size:24px}}.flow{{grid-template-columns:repeat(2,1fr)}}}}@media print{{body{{background:white}}main{{max-width:none;padding:0}}.cover,.step,.appendix{{border:0;border-radius:0;break-after:page;margin:0;padding:12mm}}.back{{display:none}}}}
</style></head><body><main id="top"><section class="cover"><div class="eyebrow">BURP WORKBENCH SIMPLE / {VERSION}</div><h1>필요한 작업만, 네 단계로</h1>
<p class="lead">수집한 원본에서 분석과 직접 검증을 거쳐 증거 ZIP을 만드는 사용 가이드입니다. 기존 확장 엔진을 유지하고 실행 화면을 핵심 작업 중심으로 정리했습니다.</p>
<nav class="flow"><a href="#step-01">1 수집</a><a href="#step-04">2 분석</a><a href="#step-05">3 검증</a><a href="#step-07">4 증거</a></nav>
<h3>설치하고 시작하기</h3><ol><li>Simple 설치 JAR <code>burp-workbench-simple-{VERSION}.jar</code>를 준비합니다. 기존 Workbench JAR·파일은 그대로 보관합니다.</li><li>Burp의 <strong>Extensions → Add → Java</strong>에서 Simple JAR를 선택합니다. 상단의 <strong>Workbench Simple</strong> 탭을 엽니다.</li><li>한 확장에서만 수집하려면 기존 Workbench의 로드를 해제하고 Simple을 사용합니다. 기존 확장을 삭제하거나 원본 파일을 덮어쓸 필요는 없습니다.</li></ol>
<p>Simple은 설정 키 <code>simple.*</code>와 저장 폴더 <code>.burp-workbench-simple</code>을 사용합니다. 기존 Next의 설정·초안·보관 폴더와 분리됩니다.</p>
<p class="note">{esc(CAPTURE_NOTE)} 계정·URL·판정 결과는 설명용 예제이며 실제 점검 성공을 뜻하지 않습니다. 이미지를 누르면 원본 PNG로 저장할 수 있습니다.</p>
<p class="muted">단계 선택과 다음 단계 버튼은 화면 이동입니다. 필요한 분석 단계는 건너뛰어도 되며 원본 선택은 유지됩니다. 대상 요청 전송은 각 도구에서 사용자가 직접 실행합니다. 이 안내서는 화면 중심의 가로형 PDF로도 제공합니다.</p></section>
{''.join(blocks)}
<section class="appendix"><div class="eyebrow">REFERENCE</div><h2>유지한 도구와 달라진 실행 흐름</h2><table><thead><tr><th>단계</th><th>유지한 도구</th><th>주요 작업</th></tr></thead><tbody>{rows}</tbody></table>
<p>기존 폴더 탐색·화면 이력·작업 이어가기·도우미 관리 메뉴는 Simple 메인 화면에서 제거했습니다. 계정 자동 비교 토글과 증거 화면의 업무 흐름·OAST 등 부가 실행 화면도 제공하지 않습니다. 확장 엔진과 원본·비교·증거 연결 코드는 별도로 보존합니다.</p>
<p>시작 시 기존 HTTP History를 반복해서 자동 분석하는 작업을 없앴습니다. SCOPE에 맞게 수집한 트래픽이나 직접 선택한 원본을 중심으로 분석합니다. 필요한 도구만 단계 안에서 열어 사용하세요.</p>
<h3>단축키와 문제 해결</h3><ul><li><strong>Alt+1~4</strong>: 단계 이동 · <strong>Ctrl+F</strong>: 수집 목록 검색 · <strong>Ctrl+R</strong>: 선택 요청을 Repeater에 준비 · <strong>F1</strong>: 현재 도구 안내.</li><li>요청이 없으면 SCOPE 체크·수집 SCOPE 상태·브라우저 프록시 경유를 확인하고 사이트를 다시 사용합니다.</li><li>비교할 계정이 없으면 인증 탐지를 켜고 계정별 로그인 요청을 만든 뒤 후보를 계정에 지정합니다.</li><li>비교가 보류되면 세션·권한표·쓰기 후 조회 조건과 응답 근거를 확인합니다. 중지는 이미 발송한 요청의 응답 또는 타임아웃 이후 마무리됩니다.</li><li>원본이 없으면 증거 추가·가림 미리보기·저장이 제한됩니다. 작성 초안은 HTTP 원본 자동 복원을 의미하지 않습니다.</li></ul>
<h3>캡처와 검증의 범위</h3><p>{esc(CAPTURE_NOTE)} 실제 Burp 설치·GUI 클릭, Windows DPI·IME, 외부 OAST·BApp·AI 서비스의 실행은 이 그림으로 확인할 수 없습니다. 새 Simple에서 실제로 실행한 검사는 별도 <a href="VERIFICATION_SIMPLE.json">검증 기록</a>에 남깁니다. 기존 버전의 통과 수를 Simple 결과로 합산하지 않습니다.</p>
<a class="back" href="#top">처음으로 ↑</a></section></main></body></html>''', encoding='utf-8')
    return output


def write_markdown(images: Path) -> Path:
    rel = Path(os.path.relpath(images, ROOT / 'docs')).as_posix()
    text = [f'# Workbench Simple {VERSION} 사용 가이드', '', '수집 → 분석 → 검증 → 증거의 네 단계로 원본 확인과 증거 저장을 이어갑니다.', '', f'> {CAPTURE_NOTE}', '',
            '## 설치', '', f'1. `burp-workbench-simple-{VERSION}.jar`를 준비하고 기존 Workbench JAR·파일을 보관합니다.',
            '2. Burp **Extensions → Add → Java**에서 Simple JAR를 추가하고 **Workbench Simple** 탭을 엽니다.',
            '3. 한 확장에서만 수집하려면 기존 Workbench 로드를 해제하고 Simple을 사용합니다. 기존 파일 삭제나 덮어쓰기는 필요하지 않습니다.', '',
            'Simple 설정은 `simple.*`, 저장 폴더는 `.burp-workbench-simple`로 기존 Next와 분리됩니다. 단계는 필요한 순서로 바로 이동할 수 있으며, 다음 단계 버튼은 화면만 바꿉니다.', '']
    for step in STEPS:
        text += [f"## {step['number']}. {step['title']}", '', f"**{step['stage']}**", '']
        text += [f'{i+1}. {item}' for i, item in enumerate(step['actions'])]
        text += ['', f"![{step['caption']}]({rel}/{step['image']})", '', step['check'], '']
    text += ['## 유지한 도구', '', '| 단계 | 도구 | 하는 일 |', '|---|---|---|']
    text += ['| '+' | '.join(row)+' |' for row in TOOLS]
    text += ['', 'Simple은 폴더 탐색·화면 이력·작업 이어가기·도우미 관리, 계정 자동 비교 토글, 증거 화면의 업무 흐름·OAST 등 부가 실행 화면을 메인 사용 흐름에서 제거합니다. 기존 확장 엔진과 원본·비교·증거 연결은 보존합니다. 시작 시 기존 History를 반복 분석하지 않습니다.', '',
             '## 단축키', '', '- **Alt+1~4**: 단계 이동', '- **Ctrl+F**: 수집 목록 검색', '- **Ctrl+R**: 선택 요청을 Repeater에 준비', '- **F1**: 현재 도구 안내', '',
             '## 검증 범위', '', CAPTURE_NOTE,
             '실제 Burp GUI·Windows DPI/IME·외부 서비스의 실행 검증과 구분합니다. Simple에서 실제 실행한 검사만 [검증 기록](VERIFICATION_SIMPLE.json)에 기록하며 기존 버전의 검사 수를 합산하지 않습니다.', '']
    output = ROOT / 'docs/GUIDE_SIMPLE_KR.md'
    output.write_text('\n'.join(text), encoding='utf-8')
    return output


def write_pdf(images: Path) -> Path:
    font = ROOT / 'burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf'
    pdfmetrics.registerFont(TTFont('SimpleKorean', str(font)))
    pdfmetrics.registerFontFamily('SimpleKorean', normal='SimpleKorean', bold='SimpleKorean', italic='SimpleKorean', boldItalic='SimpleKorean')
    output = ROOT / f'downloads/guides/{VERSION}/Workbench_Simple_{VERSION}_Guide_KR.pdf'
    output.parent.mkdir(parents=True, exist_ok=True)
    c = canvas.Canvas(str(output), pagesize=landscape(A3))
    c.setTitle(f'Workbench Simple {VERSION} 사용 가이드')
    c.setAuthor('Burp Workbench KR')
    width, height = landscape(A3)
    margin = 42
    ink, muted, accent = colors.HexColor('#17233b'), colors.HexColor('#53627c'), colors.HexColor('#315fcb')
    body = ParagraphStyle('body', fontName='SimpleKorean', fontSize=12, leading=18, textColor=ink, wordWrap='CJK', alignment=TA_LEFT)
    small = ParagraphStyle('small', parent=body, fontSize=10, leading=15, textColor=muted)

    def paragraph(text, x, y, max_width, style=body):
        p = Paragraph(text, style)
        _, h = p.wrap(max_width, height)
        p.drawOn(c, x, y - h)
        return y - h

    def frame(page, label):
        c.setFillColor(accent); c.setFont('SimpleKorean', 11)
        c.drawString(margin, height - 32, f'WORKBENCH SIMPLE {VERSION}  /  {label}')
        c.setStrokeColor(colors.HexColor('#dbe3f0')); c.line(margin, 37, width - margin, 37)
        c.setFillColor(muted); c.setFont('SimpleKorean', 9)
        c.drawString(margin, 23, '합성 데이터로 렌더링한 운영 Swing 화면 · 실제 Burp GUI 캡처와 구분')
        c.drawRightString(width - margin, 23, f'{page:02d}')

    def heading(title, y):
        c.setFillColor(ink); c.setFont('SimpleKorean', 25); c.drawString(margin, y, title)
        return y - 25

    c.bookmarkPage('start')
    c.addOutlineEntry('설치와 시작하기', 'start', level=0)
    frame(1, '시작하기')
    y = heading('필요한 작업만, 네 단계로', height - 95)
    y = paragraph('수집한 원본에서 분석과 직접 검증을 거쳐 증거 ZIP을 만드는 화면별 가이드입니다.', margin, y, width - 2*margin)
    y -= 35
    card_width = (width - 2*margin - 36) / 4
    for i, text in enumerate(['1 수집', '2 분석', '3 검증', '4 증거']):
        x = margin + i * (card_width + 12)
        c.setFillColor(colors.HexColor('#eaf0ff')); c.roundRect(x, y - 65, card_width, 65, 10, fill=1, stroke=0)
        c.setFillColor(accent); c.setFont('SimpleKorean', 20); c.drawCentredString(x + card_width/2, y - 40, text)
    y -= 110
    for text in [
        f'<b>설치 JAR</b>  burp-workbench-simple-{VERSION}.jar',
        '<b>설치 순서</b>  Burp Extensions → Add → Java → Simple JAR 선택 → Workbench Simple 탭',
        '기존 Workbench 파일과 extension은 보관합니다. 한 확장에서만 수집하려면 기존 확장의 로드를 해제하고 Simple을 사용합니다.',
        '설정 키 simple.*와 저장 폴더 .burp-workbench-simple을 사용해 기존 Next의 설정·초안·보관 폴더와 분리합니다.',
    ]:
        y = paragraph(text, margin, y, width - 2*margin) - 20
    y = paragraph(CAPTURE_NOTE, margin, y - 10, width - 2*margin) - 16
    paragraph('계정·URL·판정 결과는 설명용 합성 예제입니다. 이 그림은 실제 대상에서 검증한 결과를 뜻하지 않습니다. 화면을 바꾸는 동작은 대상 요청을 자동 전송하지 않습니다.', margin, y, width - 2*margin, small)
    c.showPage()

    for page, step in enumerate(STEPS, 2):
        c.bookmarkPage('step-' + step['number'])
        c.addOutlineEntry(step['number'] + '. ' + step['title'], 'step-' + step['number'], level=0)
        frame(page, f"{step['stage']} / STEP {step['number']}")
        y = heading(step['title'], height - 72)
        for index, action in enumerate(step['actions'], 1):
            y = paragraph(f'{index}. {esc(action)}', margin, y, width - 2*margin) - 3
        path = images / step['image']
        with PILImage.open(path) as im:
            iw, ih = im.size
        image_top = y - 12
        image_bottom = 114
        scale = min((width - 2*margin)/iw, (image_top - image_bottom)/ih)
        image_w, image_h = iw*scale, ih*scale
        image_x = (width-image_w)/2
        c.drawImage(str(path), image_x, image_top-image_h, width=image_w, height=image_h)
        c.setStrokeColor(colors.HexColor('#dbe3f0')); c.rect(image_x,image_top-image_h,image_w,image_h)
        paragraph(esc(step['caption']), margin, 102, width-2*margin, small)
        paragraph(esc(step['check']), margin, 80, width-2*margin, body)
        c.showPage()

    c.bookmarkPage('reference')
    c.addOutlineEntry('도구와 확인 사항', 'reference', level=0)
    frame(len(STEPS)+2, '도구와 확인 사항')
    y = heading('유지한 도구와 달라진 실행 흐름', height - 80)
    for stage, tool, purpose in TOOLS:
        y = paragraph(f'<b>{stage}</b>  {esc(tool)}<br/>{esc(purpose)}', margin, y, width-2*margin) - 22
    for text in [
        'Simple은 기존 폴더 탐색·화면 이력·작업 이어가기·도우미 관리 메뉴와 계정 자동 비교 토글을 메인 화면에서 제거했습니다. 증거 화면은 원본 추가·설명 작성·가림·ZIP 저장에 집중합니다.',
        '기존 확장 엔진과 원본·계정 비교·증거 연결은 보존합니다. 시작 시 기존 HTTP History를 반복 분석하지 않고, 수집한 트래픽과 직접 선택한 원본을 중심으로 사용합니다.',
        '<b>단축키</b>  Alt+1~4 단계 이동 / Ctrl+F 수집 목록 검색 / Ctrl+R Repeater 준비 / F1 현재 도구 안내',
        '<b>목록에 요청이 없으면</b>  SCOPE 체크·수집 SCOPE 상태·Burp 프록시 경유를 확인하고 사이트를 다시 사용합니다.',
        '<b>비교가 보류되면</b>  세션·권한표·쓰기 후 조회 조건과 원본·응답 근거를 확인합니다. 중지는 이미 발송한 요청의 응답 또는 타임아웃 이후 마무리됩니다.',
        '<b>검증 범위</b>  이 가이드는 운영 Swing 컴포넌트의 합성 자료 렌더링입니다. 실제 Burp GUI, Windows DPI/IME와 외부 서비스의 검증과 구분합니다. 새 Simple에서 실제 실행한 검사는 docs/VERIFICATION_SIMPLE.json을 확인하세요.',
    ]:
        y = paragraph(text, margin, y, width-2*margin) - 17
    c.save()
    return output


def write_readme():
    path = ROOT / 'README_KR.md'
    path.write_text(f'''# Workbench Simple {VERSION}

기존 Workbench Next 0.53.4에서 분리한 핵심 작업 중심 버전입니다. 원본 프로젝트와 기존 다운로드 파일을 유지하고, Simple 소스·설치 JAR·설정 저장 공간을 별도로 제공합니다.

**수집 → 분석 → 검증 → 증거**의 네 단계에서 기존 확장 도구를 사용합니다. 요청을 선택하면 **계정 비교 준비 / Repeater 준비 / 증거에 추가**를 바로 실행할 수 있습니다. 탐색·관리·자동 비교 등 부가 실행 화면은 메인 사용 흐름에서 제거했습니다.

| 필요한 파일 | 위치 |
|---|---|
| 설치 JAR | [burp-workbench-simple-{VERSION}.jar](downloads/{VERSION}/burp-workbench-simple-{VERSION}.jar) |
| 독립 빌드 소스 ZIP | [burp-workbench-simple-{VERSION}-source.zip](downloads/{VERSION}/burp-workbench-simple-{VERSION}-source.zip) |
| 무결성 목록 | [SHA256SUMS](downloads/{VERSION}/SHA256SUMS) |
| 단계별 그림 가이드 | [HTML 안내서](docs/GUIDE_SIMPLE_KR.html) · [문서 원고](docs/GUIDE_SIMPLE_KR.md) |
| PDF 가이드 | [Workbench Simple 그림 가이드북](downloads/guides/{VERSION}/Workbench_Simple_{VERSION}_Guide_KR.pdf) |
| 새 Simple 검증 결과 | [검증 기록](docs/VERIFICATION_SIMPLE.json) |

## 설치와 첫 사용

1. 기존 Workbench JAR와 파일을 보관합니다. 한 확장에서만 수집하려면 기존 확장의 로드를 해제하고 Simple을 사용합니다.
2. Burp **Extensions → Add → Java**에서 Simple JAR를 추가하고 **Workbench Simple** 탭을 엽니다.
3. **SCOPE 목록**에서 수집할 URL과 체크 상태를 정하고 수집 버튼을 **OFF → SCOPE**로 바꿉니다.
4. 대상 사이트를 사용한 뒤 요청을 선택합니다. 분석이 필요하면 **2 분석**, 직접 비교할 때는 **계정 비교 준비**를 사용합니다.
5. 원본 또는 비교 결과를 **4 증거**에 추가하고 **가림 미리보기 → 증거 ZIP 저장**으로 저장합니다.

Simple의 설정 키는 `simple.*`, 저장 폴더는 `.burp-workbench-simple`입니다. 기존 Next의 설정·초안·보관 폴더와 분리합니다. 내장 Feature Mapper, 요청/응답·JS·서버/경로·응답 분석·계정 비교·입력값 검사 엔진을 유지하며, 페이지 이동만으로 대상 요청을 전송하지 않습니다.

## 안내서 캡처

{CAPTURE_NOTE} 예제 URL·계정·판정 결과를 실제 대상 검증 결과로 해석하지 않습니다. 실제 실행한 검사와 미실행 범위는 새 Simple 검증 기록을 확인합니다.

## 개발

Simple 폴더에서 완전한 JDK를 준비한 뒤 `python dev.py build`, `python dev.py check`를 사용합니다. 기본 빌드는 `--release 17`을 유지합니다. 이 작업 환경은 Java 21의 `ct.sym`이 없어 명시적인 `--source-target` 옵션으로 소스·바이트코드 수준 17을 지정해 검증합니다. 이는 Java 17 API 호환성 검증을 의미하지 않으며 실제 실행 범위는 검증 기록을 확인합니다. 원본 0.53.4의 빌드 결과와 검사 수를 Simple 검증으로 합산하지 않습니다.

캡처는 Simple 테스트 어댑터 빌드 후 생성합니다. PDF 생성에는 Python 패키지 `reportlab`과 `Pillow`가 필요합니다. 캡처와 가이드는 다음 순서로 재생성합니다.

```sh
python tools/render_simple.py --out docs/images --check
python tools/build_simple_guide.py
```

이 작업 환경의 컴파일 방식을 재현할 때는 `python dev.py check --source-target`과 `python tools/render_simple.py --source-target --out docs/images --check`를 사용합니다. 완전한 JDK에서는 위의 기본 명령을 사용하세요. 독립 소스 ZIP의 구체적인 빌드·패키지 명령은 `dev.py --help`와 검증 기록을 확인하세요. GPL 및 제3자 고지는 원본과 함께 보존합니다.
''', encoding='utf-8')
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images', type=Path, default=ROOT/'docs/images/light/1280x800')
    args = parser.parse_args()
    args.images = args.images.resolve()
    missing = [step['image'] for step in STEPS if not (args.images/step['image']).is_file()]
    if missing:
        raise SystemExit('Missing production screenshots: '+', '.join(missing))
    outputs = [write_readme(), write_markdown(args.images), write_html(args.images), write_pdf(args.images)]
    for path in outputs:
        print(path.relative_to(ROOT))


if __name__ == '__main__':
    main()
