#!/usr/bin/env python3
"""Build a separate visual guide; never rewrite a published Simple input."""
from __future__ import annotations

import base64
import hashlib
import html
import io
import json
import math
import re
import sys
from pathlib import Path
import zipfile

from PIL import Image
from reportlab.lib import colors
from reportlab.lib.pagesizes import A3
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph, Table, TableStyle

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
BASE = 'Workbench_Simple_1.4.0_Visual_Guide_20261029_KR'
PRODUCT_COMMIT = '710af06020bfe205cb65d47d78fc60260f929bac'
REMOTE = f'https://github.com/ohjun1998/burp-workbench-kr/blob/{PRODUCT_COMMIT}/'
RAW = f'https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/{PRODUCT_COMMIT}/'
SHARE = 'https://chatgpt.com/share/6ae2bf94-c1fc-83ee-9efb-4f2adc5ad86a'
INK, MUTED, ACCENT, PALE, LINE = '#172c3b', '#566777', '#006c67', '#edf6f4', '#d7e2e8'
RED = '#d84425'
FONT_DIR = REPO/'simple/burp-workbench/src/main/resources/fonts'
FONT_REGULAR = FONT_DIR/'NanumGothic-Regular.ttf'
FONT_BOLD = FONT_REGULAR
CAPTURE_NOTE = '운영 UI + 합성 예제 캡처 · 실제 Burp GUI 조작 화면과 구분'
FLOW = 'Scope·계정 선택 → Katana·통합 점검 → 주요정보·커버리지 → 원본·재점검 → 보고서·증거'
MODULES = [
    ('요청·응답', 'Scope·수집·검색·요청/응답 원본 확인'),
    ('기능 매핑', '기능·실행·세션 분류와 프로젝트 저장'),
    ('계정 비교', '준비한 HTTP 계정의 실제 응답과 판정 근거 비교'),
    ('API 찾기', '선택 텍스트 응답의 API·키 후보와 원문 위치 확인'),
    ('서버·경로', '관측한 환경 단서와 선택 경로 직접 GET 확인'),
    ('응답 분석', '응답의 보안 단서·API 문서·저장 후 조회 근거 확인'),
    ('입력값 검사', '선택 위치의 기준·변경·대조 요청과 결과 비교'),
    ('재현·증거', '순차 재현·가림 미리보기·증거 ZIP 저장'),
]
TROUBLE = [
    ('요청 목록이 비어 있음', 'Scope 체크·수집 SCOPE 상태·Burp 프록시 경유를 확인하고 사이트를 다시 사용합니다. 통합 점검 실행은 일반 수집 OFF와 별개입니다.'),
    ('Katana 설치/실행 실패', '설치 도구가 출력한 실행파일 절대경로와 설치 확인 결과를 읽습니다. 현재 OS용 1.8.0인지 확인하고 원인을 해결한 뒤 실행합니다.'),
    ('브라우저 helper 미연결', 'Mapper에 실제 표시된 loopback 주소·토큰을 복사하고 연결 확인·저장을 누릅니다. 그림의 예제 포트·토큰을 복사하지 않습니다.'),
    ('401·로그인·챌린지 화면', 'HTTP 프로필의 신원 확인과 실제 로그인 상태를 확인합니다. HTTP 계정과 브라우저 세션은 별개이며 자동 우회 성공으로 처리하지 않습니다.'),
    ('분석 부분·미방문·중지', '예산·시간·Scope 제외·미해결 입력·전송 실패 사유를 읽습니다. COMPLETED도 사이트의 모든 메뉴를 확인했다는 뜻은 아닙니다.'),
    ('재점검 버튼 비활성', '같은 실행·현재 인증 revision·남은 공통 예산·안전 조회 조건을 확인합니다. 동적 필수값·거부·쓰기 요청은 자동 재점검하지 않습니다.'),
    ('과거 원본이 열리지 않음', '이력은 가린 요약·지문입니다. 현재 세션에 원본이 없으면 현재 원본처럼 열지 않습니다. 필요한 원본은 수집·로컬 보고서와 함께 보관합니다.'),
    ('CSV 값에 보호 문자 표시', '스프레드시트 수식 방지용 보호 표기입니다. 정확한 기계 판독 값과 원문 표기는 report.json의 value·rawText를 확인합니다.'),
    ('관리형 브라우저의 파일 차단', 'HTML 직접 열기가 정책으로 차단되면 허용된 문서 서버를 사용합니다. 가이드 폴더에서 python -m http.server 8765 --bind 127.0.0.1로 제공하고 localhost에서 엽니다. 문서 폴더만 제공합니다.'),
]
REPORT_FIELDS = [
    ('values', 'API·민감정보·토큰·키·환경 등 분류별 추출 항목'),
    ('value / rawText', '파싱한 값 / 실제 원문의 표기. 재구성 경로가 있으면 resolvedEndpoint와 구분'),
    ('OriginalID·응답 SHA', '값을 발견한 보관 원본과 응답 지문을 대조할 근거'),
    ('offset / line', '디코딩 본문·정규화 헤더의 UTF-16 위치. offset은 0, line은 1부터 시작'),
    ('pages / responseStatus', '실제 보관 응답과 HTTP 상태. 키 이름은 status가 아닌 responseStatus'),
    ('coverage / correlations', '경로 처리·미방문 사유 / 선언과 관측 경로의 형태 일치 후보'),
    ('complete / notes', '부분·중지·미확인 범위. 원문 추출 성공과 제품 분석 완료를 구분'),
]
LIMITS = [
    '환경·WAS·DB 결과는 HTTP 헤더·본문·오류·설정에서 읽은 단서입니다. DB에 직접 접속한 결과가 아닙니다.',
    '키·토큰의 형식과 문맥은 노출 후보의 근거입니다. 외부 공급자의 유효성 확인이나 사용 가능성 검증은 별도입니다.',
    'PATH_SHAPE_ONLY는 경로 형태 일치 후보입니다. 변수 복원·같은 인증·권한 획득·실행 가능성으로 승격하지 않습니다.',
    'RESPONSE_PENDING_ANALYSIS는 수신 원본은 있지만 제품 분석은 끝나지 않은 상태입니다. complete=false와 원본 참조를 유지합니다.',
    '실행 이력은 가린 요약·지문입니다. 보고서 저장과 원문 요청/응답의 재시작 후 자동 복원은 구분합니다.',
]


def esc(value):
    return html.escape(str(value), quote=True)


def data_uri(path, mime):
    return f'data:{mime};base64,' + base64.b64encode(path.read_bytes()).decode('ascii')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_content():
    data = json.loads((HERE/'content.json').read_text(encoding='utf-8'))
    steps = data['steps']
    for index, step in enumerate(steps, 1):
        step['number'] = f'{index:02d}'
        step['anchor'] = f'step-{index:02d}'
        path = HERE/'assets'/step['sourceImage']
        with Image.open(path) as image:
            step['imageWidth'], step['imageHeight'] = image.size
        step['sourceSha256'] = sha(path)
        assert len(step['actions']) == len(step['highlights']), step['sourceImage']
        assert 1 <= len(step['highlights']) <= 3
        for point in step['highlights']:
            x, y, w, h = point['rect']
            assert 0 <= x < x+w <= step['imageWidth']
            assert 0 <= y < y+h <= step['imageHeight']
        if not step.get('zoomBox'):
            x, y, w, h = step['highlights'][0]['rect']
            zw, zh = min(520, w+40), min(155, h+42)
            zx, zy = max(0, x-20), max(0, y-20)
            step['zoomBox'] = [min(zx, step['imageWidth']-zw), min(zy, step['imageHeight']-zh), zw, zh]
        zx,zy,zw,zh=step['zoomBox']
        step['zoomBox']=[zx,zy,min(360,zw),min(100,zh)]
    return data


def callout_position(rect, width, height, side=None):
    x, y, w, h = rect
    if side=='right':
        return min(width-18,x+w+24),y+h/2,(x+w,y+h/2)
    if y < 36:
        cx = max(18, x-23) if x >= 45 else min(width-18, x+w+23)
        return cx, y+h/2, (x if x >= 45 else x+w, y+h/2)
    cx = max(18, min(width-18, x+10))
    cy = y-18
    endpoint = (min(x+w-5, x+max(26, w*.35)), y)
    return cx, cy, endpoint


def svg_image(step, crop=None, cls='capture'):
    width, height = step['imageWidth'], step['imageHeight']
    view = crop or [0, 0, width, height]
    uri = data_uri(HERE/'assets'/step['sourceImage'], 'image/png')
    labels = '; '.join(f"{i}: {p['label']}" for i,p in enumerate(step['highlights'],1))
    parts = [f'<svg class="{cls}" viewBox="{" ".join(map(str, view))}" role="img" aria-label="{esc(step["title"])}. {esc(labels)}" xmlns="http://www.w3.org/2000/svg">',
             '<defs><marker id="arrow-'+step['number']+('-z' if crop else '')+'" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 Z" fill="'+RED+'"/></marker></defs>',
             f'<image href="{uri}" width="{width}" height="{height}"/>']
    marker = 'arrow-'+step['number']+('-z' if crop else '')
    for i, point in enumerate(step['highlights'], 1):
        x,y,w,h=point['rect'];cx,cy,end=callout_position(point['rect'],width,height,point.get('markerSide'))
        parts += [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="3" fill="none" stroke="{RED}" stroke-width="3"/>',
                  f'<path d="M{cx+6} {cy+8} L{end[0]} {end[1]}" stroke="{RED}" stroke-width="2.5" fill="none" marker-end="url(#{marker})"/>',
                  f'<circle cx="{cx}" cy="{cy}" r="15" fill="{RED}" stroke="white" stroke-width="3"/>',
                  f'<text x="{cx}" y="{cy+7}" text-anchor="middle" fill="white" font-family="GuideFont,sans-serif" font-size="22" font-weight="700">{i}</text>']
    parts.append('</svg>')
    return ''.join(parts)


def download_links():
    entries = [
        ('Simple 1.4.0 JAR', RAW+'simple/downloads/1.4.0/burp-workbench-simple-1.4.0.jar'),
        ('helper 0.5.0', RAW+'simple/downloads/1.4.0/feature-mapper-browser-helper-0.5.0.zip'),
        ('소스 ZIP', RAW+'simple/downloads/1.4.0/burp-workbench-simple-1.4.0-source.zip'),
        ('SHA256SUMS', REMOTE+'simple/downloads/1.4.0/SHA256SUMS'),
    ]
    return ' · '.join(f'<a href="{url}">{esc(label)}</a>' for label,url in entries)


def write_html(data):
    steps = data['steps']
    cards, navigation = [], []
    for step in steps:
        n,anchor=step['number'],step['anchor']
        previous = steps[int(n)-2]['anchor'] if int(n)>1 else 'overview'
        following = steps[int(n)]['anchor'] if int(n)<len(steps) else 'troubleshooting'
        navigation.append(f'<a href="#{anchor}" data-step-nav="{anchor}"><span>{n}</span>{esc(step["title"])}</a>')
        actions=''.join(f'<li><span class="number">{i}</span><div><strong>{esc(point["label"])}</strong><p>{esc(action)}</p></div></li>' for i,(point,action) in enumerate(zip(step['highlights'],step['actions']),1))
        note=f'<p class="note">{esc(step["note"])}</p>' if step.get('note') else ''
        caption = '운영 helper popup + Chrome API fixture·가짜 토큰' if step.get('captureKind')=='browser' else CAPTURE_NOTE
        cards.append(f'''<article id="{anchor}" class="step-card" data-step="{n}"><header><span class="eyebrow">STEP {n} / {esc(step['chapter'])}</span><h2>{esc(step['title'])}</h2><p class="goal">{esc(step['goal'])}</p></header>
<figure><button class="image-button" data-zoom="{anchor}" aria-label="{esc(step['title'])} 화면 확대">{svg_image(step)}</button><figcaption>{esc(caption)} · 번호는 아래 설명과 연결됩니다.</figcaption></figure>
<div class="figure-tools"><button class="secondary" data-zoom="{anchor}">화면 확대</button><label><input type="checkbox" data-done="{anchor}"> 이 단계 확인</label></div>
<ol class="actions">{actions}</ol><div class="result"><strong>정상 결과 확인</strong><p>{esc(step['expected'])}</p></div>{note}
<details><summary>막힐 때 확인할 사항</summary><p>{esc(step['troubleshooting'])}</p></details>
<details class="zoom-detail"><summary>중요 영역 확대</summary>{svg_image(step,step['zoomBox'],'detail-capture')}</details>
<footer class="step-footer"><a href="#{previous}" data-direction="previous">← 이전</a><span>{n} / {len(steps)}</span><a href="#{following}" data-direction="next">다음 →</a></footer></article>''')
    font=data_uri(FONT_REGULAR,'font/ttf');bold=data_uri(FONT_BOLD,'font/ttf')
    module_rows=''.join(f'<tr><th scope="row">{esc(a)}</th><td>{esc(b)}</td></tr>' for a,b in MODULES)
    trouble_rows=''.join(f'<tr><th scope="row">{esc(a)}</th><td>{esc(b)}</td></tr>' for a,b in TROUBLE)
    report_rows=''.join(f'<tr><th scope="row"><code>{esc(a)}</code></th><td>{esc(b)}</td></tr>' for a,b in REPORT_FIELDS)
    limits=''.join('<li>'+esc(s)+'</li>' for s in LIMITS)
    text='''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; font-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<title>Workbench Simple 1.4.0 · 화면별 사용 가이드</title><style>
@font-face{font-family:GuideFont;src:url(FONTREG)}
:root{--bg:#f3f7f8;--card:#fff;--ink:#172c3b;--muted:#566777;--line:#d7e2e8;--accent:#006c67;--pale:#edf6f4;--red:#d84425;--shadow:0 8px 28px #163b4a0b;color-scheme:light}
[data-theme=dark]{--bg:#101b24;--card:#172630;--ink:#e7eff4;--muted:#adc0ce;--line:#354956;--accent:#73d4c7;--pale:#203b40;--red:#ff947b;--shadow:none;color-scheme:dark}
*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:100px}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.75 GuideFont,system-ui,sans-serif}a{color:var(--accent)}button,input{font:inherit}button,a,input,summary{touch-action:manipulation}button{cursor:pointer}button:focus-visible,a:focus-visible,input:focus-visible,summary:focus-visible{outline:3px solid var(--red);outline-offset:4px}.skip{position:fixed;top:-100px;left:16px;background:var(--card);padding:12px;z-index:99}.skip:focus{top:10px}.topbar{position:sticky;top:0;z-index:10;background:var(--card);border-bottom:1px solid var(--line);padding:14px 28px;display:flex;gap:18px;align-items:center;justify-content:space-between}.brand{font-weight:700;text-decoration:none;color:var(--ink);line-height:1.5}.brand small{display:block;font-size:11px;letter-spacing:.13em;color:var(--muted)}.toolbar{display:flex;align-items:center;gap:12px}.secondary{background:var(--card);color:var(--ink);border:1px solid var(--line);border-radius:9px;padding:7px 12px}.layout{max-width:1580px;margin:auto;display:grid;grid-template-columns:278px minmax(0,1fr);gap:32px;padding:30px 28px 80px}.sidebar{align-self:start;position:sticky;top:100px;max-height:calc(100vh - 120px);overflow:auto;padding-right:10px}.sidebar input{width:100%;padding:11px;border:1px solid var(--line);border-radius:10px;background:var(--card);color:var(--ink)}.sidebar p{font-size:13px;color:var(--muted)}.sidebar nav{display:grid;gap:5px}.sidebar nav a{padding:9px 10px;font-size:13px;line-height:1.65;text-decoration:none;color:var(--muted);border-radius:8px;display:flex;gap:9px}.sidebar nav a:hover,.sidebar nav a[aria-current=true]{background:var(--pale);color:var(--accent)}.sidebar nav span{font-weight:700;font-size:12px;min-width:22px}.content{min-width:0}.hero,.intro,.step-card,.appendix{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:34px;margin-bottom:28px;box-shadow:var(--shadow)}.hero{border-top:5px solid var(--accent)}h1{font-size:38px;line-height:1.4;letter-spacing:-.04em;margin:15px 0}h2{font-size:27px;line-height:1.4;letter-spacing:-.03em;margin:10px 0 18px}h3{font-size:18px;margin:20px 0 10px}.eyebrow{font-size:12px;color:var(--accent);font-weight:700;letter-spacing:.11em}.lead{font-size:19px;max-width:850px}.flow{background:var(--pale);border-radius:10px;padding:16px;font-weight:700}.chips{display:flex;gap:9px;flex-wrap:wrap}.chips span{font-size:13px;border:1px solid var(--line);padding:5px 10px;border-radius:30px}.muted,figcaption{font-size:12px;color:var(--muted)}.goal{color:var(--muted)}figure{margin:22px 0 8px}.image-button{display:block;width:100%;padding:0;background:white;border:1px solid var(--line);border-radius:9px;overflow:hidden}.capture{width:100%;height:auto;display:block;max-height:640px;background:#fff}.figure-tools{display:flex;justify-content:space-between;align-items:center;gap:12px;font-size:13px}.figure-tools label{display:flex;gap:6px;align-items:center}.actions{list-style:none;padding:0;margin:26px 0;display:grid;gap:17px}.actions li{display:flex;align-items:flex-start;gap:13px}.actions .number{background:#d84425;color:white;border-radius:50%;min-width:28px;height:28px;display:grid;place-items:center;font-weight:700;font-size:14px}.actions p{margin:5px 0 0}.result,.note{background:var(--pale);padding:15px 18px;border-left:4px solid var(--accent);border-radius:0 8px 8px 0}.result p{margin:6px 0 0}.note{font-size:14px}.step-card details{border-top:1px solid var(--line);padding:12px 0}.step-card details p{font-size:14px}summary{cursor:pointer;font-weight:700}.detail-capture{display:block;width:100%;max-height:260px;margin-top:16px;background:white;border:1px solid var(--line)}.step-footer{border-top:1px solid var(--line);margin-top:20px;padding-top:18px;display:flex;justify-content:space-between;gap:10px;font-size:14px}.step-footer a{text-decoration:none}.step-footer span{color:var(--muted)}table{width:100%;border-collapse:collapse;font-size:14px}td,th{padding:12px;text-align:left;vertical-align:top;border-bottom:1px solid var(--line)}th{font-weight:700;width:23%}code,pre{font-family:GuideFont,monospace;background:var(--pale);border-radius:4px}code{padding:2px 5px;overflow-wrap:anywhere}pre{padding:16px;font-size:13px;white-space:pre-wrap;overflow-wrap:anywhere}#empty{padding:30px;text-align:center;background:var(--card)}[hidden]{display:none!important}dialog{max-width:96vw;max-height:95vh;background:var(--card);color:var(--ink);border:1px solid var(--line);border-radius:14px;padding:18px}dialog::backdrop{background:#07151bd9}.dialog-bar{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:14px}.zoom-scroll{max-height:78vh;max-width:90vw;overflow:auto;background:white}#zoom-content .capture{max-height:none;width:auto;display:block}body.modal-open{overflow:hidden}.footer{padding:16px;color:var(--muted);font-size:12px}
@media(max-width:1000px){.layout{grid-template-columns:225px minmax(0,1fr);padding:20px 16px;gap:18px}.hero,.intro,.step-card,.appendix{padding:24px}h1{font-size:32px}.topbar{padding:12px 18px}.toolbar{gap:8px}}
@media(max-width:760px){.layout{display:block;padding:16px 10px}.sidebar{position:static;max-height:none;padding:0;margin-bottom:18px}.sidebar nav{max-height:220px;overflow:auto}.topbar{padding:10px 14px;gap:10px}.brand{font-size:13px}.brand small{font-size:9px}.toolbar{font-size:11px;gap:6px}.toolbar .secondary{padding:7px 9px}.hero,.intro,.step-card,.appendix{padding:22px 17px;border-radius:12px;margin-bottom:18px}h1{font-size:29px}h2{font-size:23px}.lead{font-size:17px}.flow{font-size:14px}table{font-size:12px}td,th{padding:9px 6px}th{width:28%}.capture{max-height:560px}.dialog-bar{align-items:flex-start}.figure-tools{flex-wrap:wrap}}
@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}}@media print{.topbar,.sidebar,.figure-tools,.step-footer,.zoom-detail,.footer,dialog{display:none}.layout{display:block;padding:0}.hero,.intro,.step-card,.appendix{box-shadow:none;border:0;border-radius:0;break-after:page;padding:10mm}.capture{max-height:150mm}body{background:white;color:black}.note,.result{break-inside:avoid}.actions{font-size:12px}}
</style></head><body><a class="skip" href="#main">본문으로 건너뛰기</a><header class="topbar"><a href="#overview" class="brand">WORKBENCH SIMPLE<small>VISUAL USER GUIDE · 1.4.0</small></a><div class="toolbar"><span id="progress" aria-live="polite">0 / COUNT단계 확인</span><a class="secondary" href="PDFFILE">PDF</a><button class="secondary" id="theme" aria-pressed="false">다크 모드</button></div></header>
<div class="layout"><aside class="sidebar" aria-label="가이드 목차"><label for="search"><strong>필요한 작업 찾기</strong></label><input id="search" type="search" placeholder="예: Katana, 원문, 재점검" autocomplete="off"><p id="search-count" aria-live="polite">COUNT개 단계</p><nav><a href="#overview">시작 안내</a><a href="#prepare">설치·준비</a>NAVIGATION<a href="#report">보고서 읽기</a><a href="#troubleshooting">오류 해결</a><a href="#sources">출처·검증 범위</a></nav></aside><main class="content" id="main">
<section class="hero" id="overview"><span class="eyebrow">SCREENSHOT-BASED STEP-BY-STEP GUIDE</span><h1>화면을 따라 사용하는<br>Workbench Simple</h1><p class="lead">클릭할 위치는 번호로, 확인할 근거는 박스로.<br>기존 8개 도구를 유지하며 Scope 점검부터 원문 보고서까지 이어갑니다.</p><div class="chips"><span>Simple 1.4.0</span><span>COUNT개 화면 단계</span><span>번호·화살표·박스</span><span>검색·확대·다크 모드</span><span>오프라인 사용</span></div><p class="flow">FLOWTEXT</p><p><a href="#step-01"><strong>첫 단계부터 시작 →</strong></a> · <a href="PDFFILE">PDF 가이드북</a> · <a href="#troubleshooting">문제 해결부터 보기</a></p><p class="muted">이 문서는 문서 탐색용입니다. 이미지의 번호·화면 확대·단계 확인을 눌러도 대상 사이트에 요청하지 않습니다. 단계 확인 상태는 이 브라우저에만 저장되며 실제 점검 완료 판정이 아닙니다.</p></section>
<section class="intro" id="prepare"><span class="eyebrow">BEFORE YOU START</span><h2>설치하고 첫 점검을 준비합니다</h2><p>DOWNLOADS</p><ol><li>기존 파일과 설정을 보관하고 Burp의 <strong>Extensions → Add → Java</strong>에서 Simple JAR를 추가합니다. 중복 수집을 피하려면 한 Workbench에서 수집합니다.</li><li>허용할 프로토콜·호스트·포트·경로를 Scope에 등록합니다. HTTP 계정·요청 예산과 필요하면 브라우저 세션을 따로 준비합니다.</li><li>Katana는 Simple 외부 절대 폴더에 설치하고, 도구가 출력한 실행파일 경로를 실행 조건에 넣습니다.</li></ol><p><strong>독립 소스 ZIP을 푼 Simple 루트</strong>의 Windows 예:</p><pre>python tools/install_katana.py --dest C:/Tools/workbench-katana</pre><p>Linux/macOS 예(현재 사용자 홈 아래의 외부 폴더):</p><pre>python3 tools/install_katana.py --dest ~/security-tools/workbench-katana</pre><p class="muted">저장소 루트에서는 tools/ 앞에 simple/을 붙입니다. 기본 플랫폼은 auto입니다. 설치 도구는 공식 1.8.0과 SHA-256을 확인하고 현재 OS에서 버전을 검사합니다.</p><h3>브라우저·SPA 기능을 쓸 때</h3><ol><li>helper 0.5.0 ZIP을 새 폴더에 풉니다.</li><li>Chrome/Edge 확장 관리 화면에서 개발자 모드 → 압축해제된 확장 프로그램 로드를 선택합니다.</li><li>Mapper의 실제 연결 주소·토큰을 helper에 복사하고 연결 확인·저장을 누릅니다. 현재 사이트 활성화와 SPA 통합 탐색 허용을 확인합니다.</li></ol><p class="note">설치는 텍스트 안내입니다. 아래 그림은 운영 Swing에 합성 예제를 넣은 캡처와 Chrome API fixture의 helper popup입니다. 실제 Burp GUI·native MV3/DNR 설치 검증으로 읽지 않습니다.</p><h3>기존 8개 도구</h3><table><tbody>MODULES</tbody></table></section>
<p id="empty" hidden>일치하는 단계가 없습니다. <button class="secondary" id="reset-search">검색 초기화</button></p>CARDS
<section class="appendix" id="report"><span class="eyebrow">READ THE EVIDENCE</span><h2>분류 보고서에서 정확한 값을 읽습니다</h2><p>통합 점검의 <strong>분류 보고서 저장</strong>은 <code>report.html</code>, <code>report.json</code>, <code>values.csv</code>, <code>manifest.json</code>을 ZIP으로 저장합니다. 일반 화면은 가림을 유지하고 명시적 로컬 보고서에는 원문 값이 포함됩니다. 공유용 증거는 별도의 가림 미리보기를 확인합니다.</p><table><tbody>REPORTFIELDS</tbody></table><ul>LIMITS</ul><p class="note">캡처에 표시된 35개 값·URL·계정·응답은 합성 예제입니다. 보존 검증의 선별 79항목이나 새 사이트 진단 결과로 세지 않습니다.</p></section>
<section class="appendix" id="troubleshooting"><span class="eyebrow">WHEN YOU GET STUCK</span><h2>막히는 지점에서 확인합니다</h2><table><tbody>TROUBLESHOOT</tbody></table></section>
<section class="appendix" id="sources"><span class="eyebrow">SOURCES &amp; LIMITS</span><h2>캡처 출처와 검증 범위</h2><p>공유 대화 <a href="SHARELINK">사용방법 가이드 템플릿 안내</a>의 화면별 번호·화살표·박스, 이미지 아래 설명, 확대 보기와 단계 이동 구성을 적용했습니다. 제3자 서비스의 화면·브랜드·예시 이미지를 복제하지 않았습니다.</p><p>제품 기준은 <a href="PRLINK">PR #102</a>의 Simple 1.4.0 / Mapper 0.3.7 / helper 0.5.0이며 Katana는 외부 1.8.0입니다. 이 가이드의 문서 개정은 제품 버전 변경이나 main 병합을 뜻하지 않습니다.</p><p>기존 1.4.0의 자동 검사 5,476개·실제 Katana 102개·Chromium 어댑터 14개·7개 앱 전후 28회와 선별 79항목 비교는 <a href="VERIFYLINK">보존된 제품 검증</a>입니다. 이번 문서 작성에서 해당 사이트 점검을 다시 실행하지 않았습니다.</p><p>실제 Burp GUI는 별도 검증이며 native MV3/DNR 설치는 관리형 Chrome 정책으로 차단됐습니다. 이 가이드의 새 검사는 PDF·HTML·번호 좌표·접근성·오프라인 동작·원본 불변 확인에 한정합니다.</p><p>기존 배포 JAR·소스·PDF와 Simple 원본 입력은 보존하고 새 문서만 별도 경로에 생성했습니다. 출처 지문은 <a href="source-manifest.json">source-manifest.json</a>, 문서 검사 결과는 <a href="validation.json">validation.json</a>에서 확인합니다.</p></section><footer class="footer">Workbook guide revision 2026.10.29 · Original screenshots remain unchanged.</footer></main></div>
<dialog id="zoom-dialog" aria-labelledby="zoom-title"><div class="dialog-bar"><div><strong id="zoom-title">화면 확대</strong><p class="muted">원본 비율 유지 · 스크롤로 전체 영역 확인</p></div><div><button class="secondary" id="zoom-plus">+ 확대</button> <button class="secondary" id="zoom-reset">원본 크기</button> <button class="secondary" id="zoom-close">닫기</button></div></div><div class="zoom-scroll" id="zoom-content"></div></dialog>
<script>
(()=>{'use strict';const $=s=>document.querySelector(s),$$=s=>[...document.querySelectorAll(s)],steps=$$('.step-card'),key='workbench-simple-visual-20261029';const storage={get(k){try{return localStorage.getItem(key+k)}catch{return null}},set(k,v){try{localStorage.setItem(key+k,v)}catch{}}};
const theme=$('#theme');function setTheme(dark){document.documentElement.dataset.theme=dark?'dark':'light';theme.setAttribute('aria-pressed',String(dark));theme.textContent=dark?'라이트 모드':'다크 모드';storage.set('.theme',dark?'dark':'light')};setTheme(storage.get('.theme')==='dark');theme.addEventListener('click',()=>setTheme(document.documentElement.dataset.theme!=='dark'));
const done=$$('[data-done]');function progress(){$('#progress').textContent=done.filter(x=>x.checked).length+' / '+steps.length+'단계 확인'}done.forEach(el=>{el.checked=storage.get('.'+el.dataset.done)==='1';el.addEventListener('change',()=>{storage.set('.'+el.dataset.done,el.checked?'1':'0');progress()})});progress();
const search=$('#search');function filter(){const q=search.value.trim().toLocaleLowerCase();let n=0;steps.forEach(step=>{const show=!q||(step.textContent+' '+step.dataset.step).toLocaleLowerCase().includes(q);step.hidden=!show;$('[data-step-nav="'+step.id+'"]').hidden=!show;if(show)n++});$('#search-count').textContent=n+' / '+steps.length+'개 단계';$('#empty').hidden=n!==0};search.addEventListener('input',filter);$('#reset-search').addEventListener('click',()=>{search.value='';filter();search.focus()});
const dialog=$('#zoom-dialog'),zoomContent=$('#zoom-content');let scale=1,baseWidth=0,returnFocus=null;function resize(){const svg=zoomContent.querySelector('svg');if(svg)svg.style.width=Math.round(baseWidth*scale)+'px'}function close(){dialog.close();document.body.classList.remove('modal-open');zoomContent.replaceChildren();if(returnFocus)returnFocus.focus()};$$('[data-zoom]').forEach(button=>button.addEventListener('click',()=>{const step=document.getElementById(button.dataset.zoom),svg=step.querySelector('figure svg').cloneNode(true);returnFocus=button;svg.querySelectorAll('[id]').forEach(x=>{const old=x.id;x.id=old+'-dialog';svg.querySelectorAll('[marker-end]').forEach(p=>{if(p.getAttribute('marker-end')==='url(#'+old+')')p.setAttribute('marker-end','url(#'+x.id+')')})});baseWidth=Number(svg.getAttribute('viewBox').split(' ')[2]);scale=1;zoomContent.replaceChildren(svg);$('#zoom-title').textContent=step.querySelector('h2').textContent;resize();document.body.classList.add('modal-open');dialog.showModal();$('#zoom-close').focus()}));$('#zoom-close').addEventListener('click',close);dialog.addEventListener('cancel',e=>{e.preventDefault();close()});$('#zoom-plus').addEventListener('click',()=>{scale=Math.min(3,scale+.25);resize()});$('#zoom-reset').addEventListener('click',()=>{scale=1;resize()});
document.addEventListener('keydown',e=>{if(dialog.open||e.ctrlKey||e.altKey||e.metaKey||/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName))return;if(e.key!=='ArrowRight'&&e.key!=='ArrowLeft')return;const visible=steps.filter(x=>!x.hidden),current=visible.findIndex(x=>x.getBoundingClientRect().top>=-100);const at=current<0?visible.length-1:current;const next=visible[Math.max(0,Math.min(visible.length-1,at+(e.key==='ArrowRight'?1:-1)))];if(next){e.preventDefault();location.hash=next.id}});
$$('[data-direction]').forEach(a=>a.addEventListener('click',e=>{if(!search.value.trim())return;const current=a.closest('.step-card'),visible=steps.filter(x=>!x.hidden),i=visible.indexOf(current),next=visible[i+(a.dataset.direction==='next'?1:-1)];e.preventDefault();location.hash=next?next.id:(a.dataset.direction==='next'?'troubleshooting':'overview')}));
const observer=new IntersectionObserver(entries=>{for(const entry of entries)if(entry.isIntersecting){$$('[data-step-nav]').forEach(a=>a.removeAttribute('aria-current'));const a=$('[data-step-nav="'+entry.target.id+'"]');if(a)a.setAttribute('aria-current','true')}},{rootMargin:'-10% 0px -65% 0px'});steps.forEach(x=>observer.observe(x));
})();
</script></body></html>'''
    replacements={'FONTREG':font,'FONTBOLD':bold,'COUNT':str(len(steps)),'PDFFILE':BASE+'.pdf','NAVIGATION':''.join(navigation),'FLOWTEXT':esc(FLOW),'DOWNLOADS':download_links(),'MODULES':module_rows,'CARDS':''.join(cards),'REPORTFIELDS':report_rows,'LIMITS':limits,'TROUBLESHOOT':trouble_rows,'SHARELINK':SHARE,'PRLINK':'https://github.com/ohjun1998/burp-workbench-kr/pull/102','VERIFYLINK':REMOTE+'simple/docs/VERIFICATION_SIMPLE_1.4.0.json'}
    pattern='|'.join(re.escape(k) for k in replacements)
    text=re.sub(pattern,lambda match:replacements[match.group()],text)
    output=HERE/(BASE+'.html');output.write_text(text,encoding='utf-8')
    return output


class Book:
    def __init__(self, filename, count):
        pdfmetrics.registerFont(TTFont('Guide',str(FONT_REGULAR)))
        pdfmetrics.registerFont(TTFont('GuideBold',str(FONT_BOLD)))
        self.c=canvas.Canvas(str(filename),pagesize=A3,pageCompression=1)
        self.c.setTitle('Workbench Simple 1.4.0 화면별 사용 가이드')
        self.c.setAuthor('Burp Workbench KR')
        self.c.setSubject('번호·화살표·박스와 화면별 조작 설명 · 문서 개정 2026.10.29')
        self.w,self.h=A3;self.number=0;self.total=count+7
        self.layout=[]

    def para(self, text, x, top, width, size=13, color=INK, bold=False, max_height=None):
        style=ParagraphStyle('g',fontName='GuideBold' if bold else 'Guide',fontSize=size,leading=size*1.6,textColor=colors.HexColor(color),wordWrap='CJK',spaceAfter=0)
        paragraph=Paragraph(esc(text).replace('\n','<br/>'),style)
        _,height=paragraph.wrap(width,2000)
        if max_height is not None and height>max_height+.01:
            raise ValueError(f'Page {self.number}: paragraph exceeds allocated height: {height}>{max_height}: {text[:80]}')
        paragraph.drawOn(self.c,x,top-height)
        self.layout.append({'page':self.number,'kind':'text','x':x,'y':top-height,'w':width,'h':height})
        return top-height

    def start(self,key,eyebrow,title):
        if self.number:self.c.showPage()
        self.number+=1
        self.c.setFillColor(colors.white);self.c.rect(0,0,self.w,self.h,fill=1,stroke=0)
        self.c.bookmarkPage(key);self.c.addOutlineEntry(title,key,level=0,closed=False)
        self.para(eyebrow,36,self.h-30,self.w-72,10,ACCENT,True)
        self.para(title,36,self.h-55,self.w-72,26,INK,True,max_height=84)
        self.c.setStrokeColor(colors.HexColor(LINE));self.c.line(36,43,self.w-36,43)
        self.para('WORKBENCH SIMPLE 1.4.0 · VISUAL GUIDE · 2026.10.29',36,32,self.w-110,8,MUTED)
        self.c.setFillColor(colors.HexColor(MUTED));self.c.setFont('Guide',9)
        self.c.drawRightString(self.w-36,20,f'{self.number} / {self.total}')

    def box(self,x,y,w,h,fill=PALE):
        self.c.setFillColor(colors.HexColor(fill));self.c.roundRect(x,y,w,h,8,fill=1,stroke=0)

    def table(self,rows,x,top,width,col_width=160,size=12):
        style=ParagraphStyle('cell',fontName='Guide',fontSize=size,leading=size*1.65,wordWrap='CJK',textColor=colors.HexColor(INK))
        cells=[[Paragraph(esc(a),style),Paragraph(esc(b),style)] for a,b in rows]
        table=Table(cells,colWidths=[col_width,width-col_width],hAlign='LEFT')
        table.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('BACKGROUND',(0,0),(0,-1),colors.HexColor(PALE)),('LINEBELOW',(0,0),(-1,-1),.6,colors.HexColor(LINE)),('LEFTPADDING',(0,0),(-1,-1),12),('RIGHTPADDING',(0,0),(-1,-1),12),('TOPPADDING',(0,0),(-1,-1),11),('BOTTOMPADDING',(0,0),(-1,-1),11)]))
        _,height=table.wrap(width,2000)
        assert top-height>=60,(self.number,'table overflow',height)
        table.drawOn(self.c,x,top-height)
        self.layout.append({'page':self.number,'kind':'table','x':x,'y':top-height,'w':width,'h':height})
        return top-height

    def screenshot(self,step,x,y,w,h,crop=None):
        iw,ih=step['imageWidth'],step['imageHeight']
        path=HERE/'assets'/step['sourceImage']
        if crop:
            zx,zy,zw,zh=crop;scale=min(w/zw,h/zh);ox=x+(w-zw*scale)/2;oy=y+(h-zh*scale)/2
            self.c.setFillColor(colors.white);self.c.setStrokeColor(colors.HexColor(LINE));self.c.rect(x,y,w,h,fill=1,stroke=1)
            self.c.saveState();p=self.c.beginPath();p.rect(ox,oy,zw*scale,zh*scale);self.c.clipPath(p,stroke=0,fill=0)
            self.c.drawImage(str(path),ox-zx*scale,oy-(ih-zy-zh)*scale,width=iw*scale,height=ih*scale)
            self.c.restoreState()
            return
        scale=min(w/iw,h/ih);ox=x+(w-iw*scale)/2;oy=y+(h-ih*scale)/2
        self.c.setFillColor(colors.white);self.c.setStrokeColor(colors.HexColor(LINE));self.c.roundRect(x,y,w,h,5,fill=1,stroke=1)
        self.c.drawImage(str(path),ox,oy,width=iw*scale,height=ih*scale)
        for number,point in enumerate(step['highlights'],1):
            rx,ry,rw,rh=point['rect'];cx,cy,end=callout_position(point['rect'],iw,ih,point.get('markerSide'))
            self.c.setStrokeColor(colors.HexColor(RED));self.c.setLineWidth(3*scale)
            self.c.rect(ox+rx*scale,oy+(ih-ry-rh)*scale,rw*scale,rh*scale,fill=0,stroke=1)
            sx,sy=ox+(cx+6)*scale,oy+(ih-cy-8)*scale;ex,ey=ox+end[0]*scale,oy+(ih-end[1])*scale
            self.c.setLineWidth(2.5*scale);self.c.line(sx,sy,ex,ey)
            angle=math.atan2(ey-sy,ex-sx);size=9*scale
            arrow=self.c.beginPath();arrow.moveTo(ex,ey);arrow.lineTo(ex-size*math.cos(angle-.48),ey-size*math.sin(angle-.48));arrow.lineTo(ex-size*math.cos(angle+.48),ey-size*math.sin(angle+.48));arrow.close()
            self.c.setFillColor(colors.HexColor(RED));self.c.drawPath(arrow,fill=1,stroke=0)
            self.c.setStrokeColor(colors.white);self.c.setLineWidth(2*scale);self.c.circle(ox+cx*scale,oy+(ih-cy)*scale,15*scale,fill=1,stroke=1)
            self.c.setFillColor(colors.white);self.c.setFont('GuideBold',22*scale)
            self.c.drawCentredString(ox+cx*scale,oy+(ih-cy)*scale-7*scale,str(number))
        self.layout.append({'page':self.number,'kind':'screenshot','x':ox,'y':oy,'w':iw*scale,'h':ih*scale,'source':step['sourceImage']})

    def save(self):
        assert self.number==self.total,(self.number,self.total)
        self.c.save()
        (HERE/'pdf-layout.json').write_text(json.dumps({'pages':self.number,'items':self.layout},ensure_ascii=False,indent=2),encoding='utf-8')


def write_pdf(data):
    steps=data['steps'];output=HERE/(BASE+'.pdf');book=Book(output,len(steps));w,h=book.w,book.h
    book.start('cover','SCREENSHOT-BASED STEP-BY-STEP GUIDE','화면을 따라 사용하는\nWorkbench Simple')
    book.para('기존 8개 도구를 유지하고\nScope 점검부터 원문 보고서까지 연결합니다.',36,h-175,w-72,22,max_height=92)
    book.box(36,h-370,w-72,95)
    book.para(FLOW,58,h-295,w-116,17,ACCENT,True,max_height=72)
    for top,title,body in [(h-415,'번호를 따라 조작합니다','화면의 빨간 번호·화살표·박스와 이미지 아래의 같은 번호를 연결합니다. 화면마다 하나의 작업에 집중합니다.'),(h-565,'결과와 미확인을 함께 읽습니다','정상 결과, 부분 분석, 원본 근거와 막힐 때 확인할 사항을 각 단계에서 확인합니다.'),(h-715,'필요한 부분을 확대합니다','PDF는 북마크·목차로, 오프라인 HTML은 검색·확대·이전/다음·다크 모드로 사용합니다.')]:
        book.para(title,36,top,w-72,19,INK,True);book.para(body,36,top-42,w-72,14,max_height=90)
    book.para(f'문서 개정 2026.10.29 · Simple 1.4.0 / Mapper 0.3.7 / helper 0.5.0 / Katana 1.8.0\n{len(steps)}개 화면 단계 · 원본 캡처 보존 · 제품 코드를 변경하지 않는 별도 안내서',36,210,w-72,12,MUTED,max_height=75)
    book.para('그림은 운영 Swing의 합성 예제와 Chrome API fixture의 helper popup입니다. 실제 Burp GUI·native MV3/DNR 설치 성공을 보여주는 캡처와 구분합니다.',36,115,w-72,10,MUTED,max_height=50)

    book.start('contents','CONTENTS / QUICK START','필요한 작업으로 바로 이동합니다')
    book.para('처음 사용한다면 준비사항을 읽고 Scope 설정부터 진행합니다. PDF 목차의 제목을 누르면 해당 페이지로 이동합니다.',36,h-125,w-72,13,max_height=50)
    col=(w-90)/2
    for index,step in enumerate(steps):
        column=0 if index<17 else 1;row=index if column==0 else index-17;x=36+column*(col+18);top=h-205-row*43
        book.para(step['number']+'  '+step['title'],x,top,col,11.5,max_height=38)
        book.c.linkRect('',step['anchor'],(x,top-38,x+col,top),relative=0,thickness=0)
    book.box(36,95,w-72,105)
    book.para('기본 흐름',52,180,w-104,14,ACCENT,True)
    book.para('Scope·계정 → 실행 조건 → Katana·통합 점검 → 환경·API·민감정보 → 커버리지·원본 → 보고서·증거',52,152,w-104,12,max_height=50)

    book.start('prepare','PREPARE / INSTALL','설치·Scope·계정을 준비합니다')
    top=h-130
    prep=[('1. Simple JAR 로드','기존 파일·설정을 보관합니다. Burp Extensions → Add → Java에서 Simple 1.4.0 JAR를 선택합니다. 한 Workbench에서 수집하도록 중복 로드를 확인합니다.'),('2. Scope·HTTP 계정 준비','허용할 프로토콜·호스트·포트·경로와 제외 범위를 등록합니다. 비로그인 또는 준비한 HTTP 프로필을 선택합니다. 브라우저 로그인 세션은 별도입니다.'),('3. 외부 Katana 1.8.0 설치','독립 소스 ZIP을 푼 Simple 루트에서 설치 도구를 실행합니다. 대상은 Simple 밖의 절대 폴더입니다. 실제 실행파일 경로를 실행 조건에 넣고 설치 확인을 누릅니다.')]
    for title,body in prep:
        book.para(title,36,top,w-72,17,INK,True);top=book.para(body,36,top-37,w-72,13,max_height=70)-34
    book.box(36,top-103,w-72,103)
    book.para('Windows 예: python tools/install_katana.py --dest C:/Tools/workbench-katana\nLinux/macOS 예: python3 tools/install_katana.py --dest ~/security-tools/workbench-katana',51,top-15,w-102,10.3,max_height=75)
    top-=139
    book.para('4. 브라우저·SPA를 사용할 때 helper 준비',36,top,w-72,17,INK,True)
    top=book.para('helper 0.5.0 ZIP을 새 폴더에 풉니다. Chrome/Edge 확장 관리 → 개발자 모드 → 압축해제된 확장 프로그램 로드에서 폴더를 선택합니다. Mapper에 표시되는 실제 주소·토큰을 helper에 넣고 연결 확인·저장을 누릅니다.',36,top-37,w-72,13,max_height=100)-30
    top=book.para('현재 사이트 활성화·SPA 통합 탐색 허용을 확인합니다. 그림에 보이는 예제 포트·토큰을 그대로 입력하지 않습니다. 설치 절차는 텍스트 안내이며 native 설치 검증 결과와 구분합니다.',36,top,w-72,12,MUTED,max_height=80)-30
    book.para('배포 파일·체크섬',36,top,w-72,15,ACCENT,True)
    top-=30
    for label,url in [('Simple JAR',RAW+'simple/downloads/1.4.0/burp-workbench-simple-1.4.0.jar'),('helper 0.5.0 ZIP',RAW+'simple/downloads/1.4.0/feature-mapper-browser-helper-0.5.0.zip'),('소스 ZIP·SHA256SUMS',REMOTE+'simple/downloads/1.4.0/')]:
        book.para(label+' →',36,top,w-72,12,ACCENT);book.c.linkURL(url,(36,top-25,320,top),relative=0);top-=32

    book.start('modules','8 TOOLS / INTERPRETATION','기존 도구와 결과 해석 기준')
    bottom=book.table(MODULES,36,h-130,w-72,135,12)
    top=bottom-34
    book.para('실행과 준비를 구분합니다',36,top,w-72,17,INK,True)
    top=book.para('화면 열기·필터·원본 연결·Repeater 준비는 대상 요청을 보내지 않습니다. 통합 점검 시작·조건이 맞는 선택 재점검·각 도구의 직접 실행에서 요청이 전송됩니다. Repeater의 실제 전송은 Send입니다.',36,top-37,w-72,13,max_height=90)-26
    book.para('발견·응답·분석 완료를 구분합니다',36,top,w-72,17,INK,True)
    book.para('경로 문자열 발견은 실제 접근 성공과 다릅니다. 응답 관측은 본문 분석과 별개입니다. 후보·부분·미해결·권한 차이는 원본과 현재 실행 조건으로 판단합니다. HTTP 총 예산에는 기준·신원·리디렉션·재점검이 포함됩니다.',36,top-37,w-72,13,max_height=110)

    for step in steps:
        book.start(step['anchor'],f"STEP {step['number']} / {step['chapter']}",step['title'])
        book.para(step['goal'],36,h-127,w-72,12,MUTED,max_height=44)
        book.screenshot(step,36,539,w-72,480)
        caption='운영 helper popup · Chrome API fixture·가짜 토큰' if step.get('captureKind')=='browser' else CAPTURE_NOTE
        book.para(caption,36,525,w-72,8,MUTED,max_height=20)
        top=498
        for number,(point,action) in enumerate(zip(step['highlights'],step['actions']),1):
            book.c.setFillColor(colors.HexColor(RED));book.c.circle(49,top-12,11,fill=1,stroke=0);book.c.setFillColor(colors.white);book.c.setFont('GuideBold',12);book.c.drawCentredString(49,top-16,str(number))
            top=book.para(point['label']+' — '+action,70,top,w-106,12.1,max_height=59)-11
        assert top>=329,(step['sourceImage'],'actions overflow',top)
        book.box(36,211,w-72,110)
        book.para('정상 결과 확인',50,308,w-100,11,ACCENT,True)
        book.para(step['expected'],50,284,w-100,11,max_height=37)
        if step.get('note'):book.para(step['note'],50,247,w-100,9,MUTED,max_height=30)
        book.para('중요 영역 확대',36,209,360,10,ACCENT,True)
        book.screenshot(step,36,75,360,112,step['zoomBox'])
        book.para('막힐 때 확인할 사항',419,209,w-455,10,ACCENT,True)
        book.para(step['troubleshooting'],419,184,w-455,11,max_height=72)
        book.para('원본 SHA-256 '+step['sourceSha256'][:20]+'…',419,98,w-455,8,MUTED,max_height=18)
        book.para('전체 원본·위치 지문: source-manifest.json',419,80,w-455,8,MUTED,max_height=18)

    book.start('report','REPORT / EXACT VALUES','분류 보고서에서 원문 근거를 읽습니다')
    book.para('분류 보고서 ZIP에는 report.html · report.json · values.csv · manifest.json이 들어 있습니다. 일반 화면은 가림을 유지하고, 명시적 로컬 보고서에는 파싱한 원문 값이 포함됩니다.',36,h-130,w-72,14,max_height=90)
    bottom=book.table(REPORT_FIELDS,36,h-245,w-72,180,12)
    top=bottom-32
    for body in ['값·원문 표기·위치·OriginalID·응답 SHA를 연결해 읽습니다. rawText와 resolvedEndpoint가 다르면 원문과 재구성 값을 구분합니다.','원문 위치는 디코딩된 응답의 UTF-16 문자 기준이며 wire-byte 위치가 아닙니다. CSV 보호 표기와 정확한 JSON 값을 구분합니다.','report.json의 complete·notes와 제품 분석 대기 상태를 읽습니다. 원문 추출에 성공해도 제품 분석이 완료된 것으로 바꾸지 않습니다.','캡처의 35개 값은 합성 예제입니다. 보존 검증의 선별 79항목이나 새 사이트 발견 수가 아닙니다.']:
        top=book.para(body,36,top,w-72,12,max_height=75)-20

    book.start('troubleshooting','TROUBLESHOOTING','막히는 지점에서 확인합니다')
    book.table(TROUBLE,36,h-130,w-72,165,12)

    book.start('sources','SOURCES / VERIFICATION','출처·검증 범위와 보존 원칙')
    top=h-130
    for title,body in [('공유 대화에서 반영한 형식','화면별 번호·화살표·박스, 이미지 아래의 짧은 설명, 정상 결과·오류 대처, 중요 영역 확대, HTML 검색·이전/다음·다크 모드를 반영했습니다. 제3자 제품의 화면이나 브랜드를 복제하지 않았습니다.'),('캡처의 출처','Simple 1.4.0 운영 Swing 컴포넌트에 합성 예제를 넣어 만든 기존 캡처와 Chrome API fixture의 helper popup을 재사용합니다. PNG 픽셀은 변경하지 않고 문서의 벡터 주석만 겹쳤습니다.'),('제품 검증과 이번 문서 검증','5,476개 자동 검사·실제 Katana102개·Chromium 어댑터14개·7앱 전후28회 및 선별79항목은 보존된 제품 검증입니다. 이번 가이드 작성에서 사이트 진단을 다시 실행한 결과가 아닙니다.'),('남은 실제 환경 검증','실제 Burp GUI는 별도 검증입니다. native MV3/DNR 설치는 관리형 Chrome 정책으로 차단됐으며 어댑터 성공과 구분합니다. 이번 새 검사는 PDF·HTML·번호 좌표·오프라인 동작·원본 불변 확인입니다.'),('기존 파일 보존','기존 Next·Simple JAR·소스·PDF와 Simple 패키지 입력은 유지합니다. 이번 결과물은 문서만 별도 이름·경로로 생성한 개정판입니다. 제품 버전 변경이나 main 병합 완료를 뜻하지 않습니다.')]:
        book.para(title,36,top,w-72,16,INK,True);top=book.para(body,36,top-33,w-72,12,max_height=90)-28
    for label,url in [('공유 대화 원문',SHARE),('제품 기준 PR #102','https://github.com/ohjun1998/burp-workbench-kr/pull/102'),('Simple 1.4.0 제품 검증',REMOTE+'simple/docs/VERIFICATION_SIMPLE_1.4.0.json')]:
        book.para(label+' →',36,top,w-72,12,ACCENT);book.c.linkURL(url,(36,top-25,380,top),relative=0);top-=30
    book.save();return output


def package_downloads(html_path,pdf_path,require_validation=False):
    validation=HERE/'validation.json'
    if require_validation:
        record=json.loads(validation.read_text(encoding='utf-8'))
        assert record['result']=='passed'
        for path in [html_path,pdf_path]:
            assert record['artifacts'][path.name]['sha256']==sha(path),path.name
    bundle=HERE/(BASE+'_Bundle.zip')
    with zipfile.ZipFile(bundle,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
        for p in [html_path,pdf_path,HERE/'source-manifest.json',HERE/'SOURCE_NOTES_KR.md',HERE/'OFL_NanumGothic.txt']:
            archive.write(p,p.name)
        if require_validation:archive.write(validation,validation.name)
        archive.writestr('SHA256SUMS',''.join(sha(p)+'  '+p.name+'\n' for p in [html_path,pdf_path]))
        archive.writestr('README_KR.md',f'# Workbench Simple 1.4.0 화면별 사용 가이드\n\n[{BASE}.pdf]({BASE}.pdf) · [{BASE}.html]({BASE}.html)\n\n압축을 풀고 HTML을 브라우저에서 엽니다. 이미지·폰트·본문은 파일에 포함되어 오프라인으로 읽습니다. PDF는 같은 폴더에 두세요. 외부 제품 설치·검증·공유 대화 링크를 열 때만 인터넷이 필요합니다.\n\n검색·화면 확대·중요 영역 펼치기·이전/다음·다크 모드와 브라우저에 저장되는 단계 확인을 제공합니다. 단계 확인은 실제 사이트 점검 완료 판정이 아닙니다.\n\n원본 캡처는 운영 UI의 합성 예제와 Chrome API fixture이며 실제 Burp GUI/native MV3 설치 성공과 구분합니다. 출처·검증 범위는 SOURCE_NOTES_KR.md와 validation.json, source-manifest.json을 확인하세요. SHA256SUMS는 이 묶음 안의 PDF·HTML을 검증합니다.\n')
    files=[html_path,pdf_path,bundle]
    (HERE/'SHA256SUMS').write_text(''.join(sha(p)+'  '+p.name+'\n' for p in files),encoding='utf-8')
    return files


def main():
    data=read_content()
    font=TTFont('BuildGlyphCheck',str(FONT_REGULAR))
    missing=sorted({c for c in json.dumps(data,ensure_ascii=False) if not c.isspace() and ord(c) not in font.face.charToGlyph})
    assert not missing,('Missing document source glyphs',missing)
    if '--bundle-only' in sys.argv:
        files=package_downloads(HERE/(BASE+'.html'),HERE/(BASE+'.pdf'),require_validation=True)
    else:
        html_path=write_html(data);pdf_path=write_pdf(data)
        manifest={'documentRevision':'2026-10-29','productVersion':'1.4.0','productCommit':PRODUCT_COMMIT,'referenceConversation':SHARE,'imagePixelsModified':False,'annotations':'Document SVG/PDF vector overlay only; original pixels preserved.','screenshots':[{'step':s['number'],'file':'assets/'+s['sourceImage'],'sha256':s['sourceSha256'],'width':s['imageWidth'],'height':s['imageHeight'],'highlights':s['highlights'],'zoomBox':s['zoomBox'],'captureKind':s.get('captureKind','swing-synthetic')} for s in data['steps']]}
        (HERE/'source-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        files=package_downloads(html_path,pdf_path)
    print(json.dumps({'steps':len(data['steps']),'pdfPages':len(data['steps'])+7,'files':[{'file':p.name,'bytes':p.stat().st_size,'sha256':sha(p)} for p in files]},ensure_ascii=False,indent=2))


if __name__=='__main__':
    main()
