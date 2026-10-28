#!/usr/bin/env python3
"""Build the versioned Scope inspection/eight-tool guide from production captures."""
from __future__ import annotations

import argparse
import base64
import html
import json
import os
from pathlib import Path
import runpy

from PIL import Image as PILImage
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A3, landscape
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph, Table, TableStyle

ROOT = Path(__file__).resolve().parents[1]
VERSION = runpy.run_path(str(ROOT / 'burp-workbench/release_info.py'))['VERSION']
EVIDENCE_FILE = f'VERIFICATION_SIMPLE_{VERSION}.json'
HELPER_VERSION = json.loads((ROOT/'feature-mapper/browser-helper/manifest.json').read_text(encoding='utf-8'))['version']
HELPER_ZIP = f'feature-mapper-browser-helper-{HELPER_VERSION}.zip'
SCOPE_CONTENT = runpy.run_path(str(Path(__file__).with_name('scope_guide_content.py')))
KATANA_CONTENT = runpy.run_path(str(Path(__file__).with_name('katana_guide_content.py')))
KATANA_VERSION = KATANA_CONTENT['KATANA_VERSION']
if json.loads((ROOT/'docs/KATANA_DEPENDENCY.json').read_text(encoding='utf-8'))['version'] != KATANA_VERSION:
    raise RuntimeError('Katana guide and pinned dependency version differ')
KATANA_SPEC = KATANA_CONTENT['KATANA_SPEC']
KATANA_INSTALL = KATANA_CONTENT['KATANA_INSTALL']
KATANA_NOTES = KATANA_CONTENT['KATANA_NOTES']
GUIDE_FIXED_PAGES = 7  # Six introduction pages and one reference page.
SCOPE_FEATURES = SCOPE_CONTENT['SCOPE_FEATURES']
HELPER_INSTALL = [text.replace('feature-mapper-browser-helper-0.4.0.zip', HELPER_ZIP) for text in SCOPE_CONTENT['HELPER_INSTALL']]
INTERPRETATION = SCOPE_CONTENT['INTERPRETATION']
CAPTURE_NOTE = '그림은 Simple 운영 Swing 화면에 합성 예제 패킷을 넣어 렌더링한 캡처입니다. 실제 Burp GUI를 조작한 캡처가 아닙니다.'
BROWSER_CAPTURE = ROOT/f'docs/images/{VERSION}/browser/helper-connected.png'
MODULES = [
    {'id':'traffic', 'nav':'요청·응답', 'name':'요청·응답 목록', 'anchor':'01', 'core':'SCOPE, OFF/SCOPE 수집, 검색, 요청·응답 원본, Repeater 준비와 증거 연결', 'aux':'중복 탐색·작업 관리 바 제거. 저장 검색 조건은 검색 설정에서 필요할 때 사용.'},
    {'id':'mapper', 'nav':'기능 매핑', 'name':'기능 자동 매핑', 'anchor':'04', 'core':'기능·실행·세션 분류, 패킷·분류 근거, 프로젝트 저장·열기와 점검 업무 연결', 'aux':'핵심 분류·프로젝트 흐름 유지. 브라우저 도우미와 세부 설정은 필요한 경우 선택.'},
    {'id':'auth', 'nav':'계정 비교', 'name':'계정 권한 비교', 'anchor':'08', 'core':'계정 연결, 세션·권한·쓰기 조회 조건, 선택 요청 직접 비교, 중지와 결과 근거', 'aux':'신규 요청 자동 비교 토글 제거. 선택한 원본의 준비와 직접 실행에 집중.'},
    {'id':'js', 'nav':'API 찾기', 'name':'JS에서 API 찾기', 'anchor':'05', 'core':'관측 텍스트 응답의 API·키 후보, 원본 위치·로컬 이력, 선택 API의 요청 준비', 'aux':'시작 시 기존 History 자동 순회 제거. 부가 NPM·소스맵 조회는 메인 흐름에서 제외.'},
    {'id':'environment', 'nav':'서버·경로', 'name':'서버·경로 탐색', 'anchor':'06', 'core':'관측 서버·경로 정보, 후보 경로, 선택 경로의 직접 GET 확인', 'aux':'선택 대상과 확인 조건 중심으로 사용. 보조 설정·수집 경로는 필요할 때 열기.'},
    {'id':'response.recon', 'nav':'응답 분석', 'name':'응답 보안 분석', 'anchor':'07', 'core':'보안 단서, API 문서, 저장 후 조회 흐름과 원본·증거 연결', 'aux':'주요 분석 기능 유지. 보조 설정은 필요할 때 열고 원본 근거 확인에 집중.'},
    {'id':'payload', 'nav':'입력값 검사', 'name':'입력값 보안 검사', 'anchor':'10', 'core':'검사 유형, 입력 위치·값, 간격·한도·SCOPE, 기준·변경·대조 결과와 증거 연결', 'aux':'검사 조건과 결과 검토에 집중. 선택 결과에서 증거로 바로 연결.'},
    {'id':'bounty', 'nav':'재현·증거', 'name':'재현·증거 정리', 'anchor':'11', 'core':'요청 순차 재현·중지·결과 확인, 원본·비교·재현 결과 추가, 가림과 ZIP 저장', 'aux':'OAST·외부 도구·기존 브라우저 보조 화면 제외. 요청 재현·증거 정리 유지, SPA는 통합 점검에서 선택.'},
]
STEPS = [
    {'number':'00', 'module':'시작', 'title':'기존 8개 도구를 상단에서 바로 엽니다', 'image':'00-start.png',
     'actions':['Burp Extensions → Add → Java에서 Simple JAR를 추가한 뒤 Workbench Simple 탭을 엽니다.',
                '상단에 표시된 8개 도구 중 필요한 도구를 바로 선택합니다. 원본을 고르고 필요한 도구에서 결과를 확인한 뒤 증거로 연결합니다.'],
     'check':'다음 확인: 도구 이동은 화면만 바꿉니다. 처음에는 수집 OFF이며, 원본을 선택하기 전 직접 작업 버튼은 비활성화됩니다.',
     'caption':'Simple 시작 화면 · 8개 도구 진입점을 모두 표시한 운영 Swing 캡처입니다.'},
    {'number':'01', 'module':'요청·응답 목록', 'title':'SCOPE에 점검할 대상을 등록합니다', 'image':'00-scope-editor.png',
     'actions':['요청·응답의 SCOPE 목록을 열고 점검할 URL을 추가합니다. 경로를 지정하면 해당 경로와 하위 경로가 대상입니다.',
                '수집할 범위의 체크 상태를 확인합니다. Burp HTTP history의 Add to scope로 등록한 범위도 이 목록에서 확인합니다.'],
     'check':'다음 확인: 의도한 프로토콜·호스트·포트·경로만 수집 범위에 들어 있는지 확인합니다.',
     'caption':'실제 SCOPE 편집 컴포넌트 · 예제 주소는 합성 자료입니다.'},
    {'number':'02', 'module':'요청·응답 목록', 'title':'SCOPE 수집을 켜고 사이트를 사용합니다', 'image':'01-capture.png',
     'actions':['수집 버튼을 OFF에서 SCOPE로 바꾼 뒤 Burp 브라우저에서 대상 기능을 사용합니다.',
                '목록에 요청이 표시되는지 확인합니다. 기존 History는 SCOPE 수집을 켠 상태에서 필요한 요청을 골라 Simple로 가져옵니다.'],
     'check':'다음 확인: OFF에서는 새 트래픽과 수동 가져오기를 수집하지 않습니다. 목록 고정은 화면 갱신만 멈춥니다.',
     'caption':'SCOPE ON 상태의 요청 목록 · 화면의 요청과 응답은 합성 자료입니다.'},
    {'number':'03', 'module':'요청·응답 목록', 'title':'원본을 선택하고 필요한 도구로 연결합니다', 'image':'02-request.png',
     'actions':['검색으로 요청을 찾고 행을 선택해 요청·응답 원문을 읽습니다. 여러 건은 Ctrl 또는 Shift로 선택합니다.',
                '계정 비교 준비, Repeater 준비, 증거에 추가를 사용하거나 행 메뉴에서 필요한 도구에 원본을 연결합니다. 상단 도구를 바꿔도 원본 선택은 유지됩니다.'],
     'check':'다음 확인: Repeater 준비는 요청을 복사하는 동작입니다. 실제 전송은 Repeater의 Send로 직접 실행합니다.',
     'caption':'선택 원본과 직접 작업 버튼 · 도구마다 원본을 다시 입력하는 작업을 줄입니다.'},
    {'number':'04', 'module':'기능 자동 매핑', 'title':'기능·실행·세션과 패킷을 함께 확인합니다', 'image':'08-mapper.png',
     'actions':['상단의 기능 매핑을 엽니다. SCOPE 수집 중 관측한 패킷이 기능·실행·세션에 어떻게 묶였는지 확인합니다.',
                '패킷과 분류 근거를 읽고 필요한 이름·메모를 정리합니다. 프로젝트 저장·열기와 점검 진행 현황 연결은 그대로 사용합니다.'],
     'check':'다음 확인: 자동 분류는 기능을 묶는 결과입니다. 실제 점검 완료는 검증 결과와 증거를 확인한 뒤 판단합니다.',
     'caption':'기존 Mapper 운영 화면 · 기능·실행·세션·프로젝트 핵심 기능을 유지합니다.'},
    {'number':'05', 'module':'JS에서 API 찾기', 'title':'선택 응답의 API 후보와 원본 위치를 확인합니다', 'image':'10-js.png',
     'actions':['요청·응답 목록에서 텍스트 응답을 선택하고 행 메뉴의 선택 응답에서 API 찾기로 연결합니다. 상단의 API 찾기에서도 결과를 엽니다.',
                'API·키 후보를 선택해 출처와 원본 위치를 읽습니다. 필요한 API만 요청으로 준비하고 메서드·인증·입력값을 확인합니다.'],
     'check':'다음 확인: 코드 분석은 JavaScript 실행이나 요청 전송이 아닙니다. 시작 시 기존 History를 자동 순회하지 않습니다.',
     'caption':'선택한 합성 JavaScript 응답을 처리한 운영 JS 분석 화면입니다.'},
    {'number':'06', 'module':'서버·경로 탐색', 'title':'관측 정보에서 필요한 경로만 직접 확인합니다', 'image':'09-environment.png',
     'actions':['상단의 서버·경로에서 관측한 사이트와 서버·경로 정보를 고릅니다.',
                '확인할 경로와 SCOPE·계정·요청 한도를 검토한 뒤 선택 경로의 GET 확인을 직접 실행합니다.'],
     'check':'다음 확인: 후보 경로만으로 존재나 취약점이 확정되지는 않습니다. GET 확인은 서버에 요청을 전송합니다.',
     'caption':'기존 서버·경로 확인 운영 화면 · 관측 자료와 직접 실행을 구분합니다.'},
    {'number':'07', 'module':'응답 보안 분석', 'title':'응답의 보안 단서와 원본 근거를 읽습니다', 'image':'03-analysis.png',
     'actions':['상단의 응답 분석을 열어 관측 응답에서 나온 후보를 선택합니다.',
                '보안 단서·API 문서·저장 후 조회 흐름의 원본 위치와 근거를 확인합니다. 필요한 결과를 증거로 전달하고 영향을 직접 검증합니다.'],
     'check':'다음 확인: 분석은 관측 응답을 읽는 동작입니다. 오류 정보·헤더·반사 관찰만으로 취약점을 확정하지 않습니다.',
     'caption':'실제 응답 분석 엔진이 합성 응답의 오류 구현 정보 노출 후보를 표시한 화면입니다.'},
    {'number':'08', 'module':'계정 권한 비교', 'title':'비교할 계정과 판정 조건을 준비합니다', 'image':'04-auth-accounts.png',
     'actions':['인증 탐지를 ON으로 켜고 필요한 계정으로 사이트를 사용합니다. 탐지된 후보 행을 우클릭해 관리자·일반·후보1 중 비교 슬롯에 지정합니다.',
                '선택 요청 비교 또는 계정·판정 설정에서 계정, 허용·차단 기준과 필요한 세션·쓰기 후 조회 조건을 확인합니다.'],
     'check':'다음 확인: 인증 탐지는 수집 OFF/SCOPE와 별도입니다. 후보 수집은 자동 비교 실행이 아니며 실제 비교에는 SCOPE가 필요합니다.',
     'caption':'운영 계정 준비 컴포넌트 · 계정과 인증값은 합성 예제입니다.'},
    {'number':'09', 'module':'계정 권한 비교', 'title':'설정을 확인한 뒤 비교를 직접 시작합니다', 'image':'04-auth-results.png',
     'actions':['목록에서 원본을 선택하고 계정 비교 준비를 누릅니다. 조건을 바꿨다면 적용 상태 다시 확인 후 비교 시작(제한이 있으면 제한 확인 후 비교)을 누릅니다.',
                '결과에서 원본·계정별 응답과 판정 근거를 읽습니다. 진행 중인 비교는 중지로 멈추고 필요한 조건을 보완합니다.'],
     'check':'다음 확인: 상태 코드 차이만으로 권한 문제를 확정하지 않습니다. 세션 오류·쓰기 결과 미확인 등은 근거와 함께 검토합니다.',
     'caption':'계정 비교 결과 운영 화면 · 표시 결과는 합성 예제이며 캡처 생성 시 HTTP를 전송하지 않았습니다.'},
    {'number':'10', 'module':'입력값 보안 검사', 'title':'입력 위치와 검사 조건을 정하고 결과를 확인합니다', 'image':'07-payload.png',
     'actions':['목록의 선택 요청을 입력값 검사에 연결하고 검사할 위치·값을 지정합니다. 검사 유형, 간격, 총 요청 한도와 SCOPE를 확인합니다.',
                '검사 시작은 직접 실행합니다. 기준·변경·대조 응답을 검토합니다. 결과 행을 선택하고 결과를 증거로 버튼으로 해당 검사의 기준·공격·대조 묶음 전체를 전달합니다.'],
     'check':'다음 확인: 요청 연결과 조건 편집은 전송하지 않습니다. 검사 시작은 여러 시험·대조 요청을 전송할 수 있습니다.',
     'caption':'기존 입력값 검사 운영 화면 · 검사 유형·입력·한도·결과 기능을 유지합니다.'},
    {'number':'11', 'module':'재현·증거 정리', 'title':'필요한 요청을 순서대로 직접 재현합니다', 'image':'11-replay.png',
     'actions':['상단의 재현·증거에서 요청 재현을 엽니다. 목록에서 선택한 원본 요청을 순서에 맞게 추가하고 단계별 입력과 인증 조건을 확인합니다.',
                'SCOPE·Origin·쓰기 허용·대기 시간·총 요청 한도를 확인한 뒤 직접 실행합니다. 단계별 결과를 읽고 결과 → 증거 버튼으로 이번 재현의 전체 단계 결과를 연결합니다.'],
     'check':'다음 확인: 단계 추가는 전송하지 않습니다. 순차 재현 실행은 서버에 요청을 전송하며, 중지와 실패 시 후속 실행 중단 조건을 확인합니다.',
     'caption':'복원한 요청 순차 재현 운영 화면 · 원본을 추가한 예제이며 캡처 생성 시 실행하지 않았습니다.'},
    {'number':'12', 'module':'재현·증거 정리', 'title':'원본·비교·재현 결과를 모으고 설명을 적습니다', 'image':'05-evidence.png',
     'actions':['재현·증거의 증거 정리 화면에서 증거 추가를 사용합니다. 비교 결과는 결과 목록에서 저장할 결과를 선택한 뒤 권한 비교 추가로 가져옵니다.',
                '제목, 영향, 재현 절차와 항목 설명을 작성합니다. 순서 변경·제거는 실행 취소와 다시 실행으로 복구합니다.'],
     'check':'다음 확인: 비교 결과 추가는 실행 당시 원본·계정·조회와 판정 조건을 함께 보존합니다. 작성 초안만 복원한 경우 원본은 직접 연결합니다.',
     'caption':'증거 작성 운영 화면 · 요청 재현과 증거 정리의 두 핵심 모드를 유지합니다.'},
    {'number':'13', 'module':'재현·증거 정리', 'title':'가림 결과를 확인하고 ZIP으로 저장합니다', 'image':'06-redaction.png',
     'actions':['가림 미리보기를 열어 보고서와 패킷에 인증값·민감한 값이 남았는지 확인합니다. 추가 이름은 더 보기 → 가림 설정·본문 포함에서 지정합니다.',
                '직접 적은 문장과 첨부 이미지를 확인한 뒤 증거 ZIP 저장을 누르고 저장 위치를 지정합니다. 저장 완료 상태를 확인합니다.'],
     'check':'완료 확인: ZIP의 report.html 또는 report.md와 evidence.json을 열어 설명·순서·원본 연결·가림 결과가 의도한 대로 들어 있는지 확인합니다.',
     'caption':'운영 가림 미리보기 · 합성 Cookie 값이 [REDACTED]로 가려진 내용을 보여 줍니다.'},
]
scope_steps = SCOPE_CONTENT['SCOPE_STEPS']
katana_steps = KATANA_CONTENT['KATANA_STEPS']
STEPS = scope_steps[:1] + katana_steps[:1] + scope_steps[1:2] + katana_steps[1:] + scope_steps[2:] + STEPS
if BROWSER_CAPTURE.is_file():
    CAPTURE_NOTE = '운영 Swing 그림은 합성 예제 패킷으로 렌더링했습니다. 별도 표시한 helper 한 장은 로컬 fixture에 연결한 실제 Chromium 페이지이며, 실제 Burp GUI 조작 캡처와 구분합니다.'
    STEPS.insert(0, {'number':'B01', 'module':'브라우저 helper 실제 화면',
        'title':'helper 연결과 SPA 허용 상태를 확인합니다', 'image':'helper-connected.png', 'captureKind':'browser',
        'actions':['helper의 연결 주소·연결 토큰을 입력하고 연결 확인·저장으로 로컬 Workbench 연결을 확인합니다. 토큰 입력란은 가림 상태로 표시됩니다.',
                   '현재 사이트 활성화 후 SPA 통합 탐색 허용을 켭니다. 실제 탐색은 Workbench의 SCOPE 통합 점검 시작에서 요청합니다.'],
        'check':'다음 확인: 브라우저 로그인 세션과 HTTP 점검 계정은 별개입니다. 이 캡처는 설치·권한 허용 전체 수동 조작 또는 실제 Burp 실행의 증명이 아닙니다.',
        'caption':'실제 Chromium에서 열린 helper 연결 페이지 · localhost 합성 fixture와 가짜 토큰을 사용한 검증 캡처입니다.'})
INSTALL = [
    f'Simple 설치 JAR burp-workbench-simple-{VERSION}.jar를 준비하고 기존 Workbench 파일을 보관합니다.',
    'Burp Extensions → Add → Java에서 Simple JAR를 선택하고 Workbench Simple 탭을 엽니다.',
    '한 확장에서만 수집하려면 기존 Workbench의 로드를 해제하고 Simple을 사용합니다. 기존 확장이나 원본 파일을 삭제할 필요는 없습니다.',
]
REFERENCE = [
    'SCOPE URL·HTTP 계정 선택 → Katana·통합 점검 → 결과·원본 확인 → 원문 값 분류 보고서·기존 도구·증거 저장으로 이어갑니다. 기존 8개 도구는 직접 선택해 사용할 수 있습니다.',
    'Katana 수집은 실행용 인증 loopback 프록시를 사용해 같은 SCOPE·HTTP 계정·요청 예산으로 조회합니다. 폼 메타데이터와 기술 탐지 결과는 근거가 필요한 후보로 읽습니다.',
    'Simple은 기존 폴더 탐색·화면 이력·작업 이어가기·도우미 관리 바를 메인 화면에서 제거했습니다. 각 확장의 주요 기능은 유지하고, 도구 이동은 화면만 바꿉니다.',
    '통합 점검 시작은 설정한 SCOPE 안에 조회 요청을 전송합니다. Repeater 준비는 원본을 복사하는 동작이며 실제 전송은 Send로 실행합니다. 입력값 검사·순차 재현은 해당 도구에서 직접 실행합니다.',
    '요청이 없으면 SCOPE 체크·수집 SCOPE 상태·Burp 프록시 경유를 확인하고 사이트를 다시 사용합니다. 인증 후보가 없으면 인증 탐지를 켜고 계정별 요청을 관측합니다.',
    '비교가 보류되면 세션·권한표·쓰기 후 조회 조건과 원본·응답 근거를 확인합니다. 중지는 이미 발송한 요청의 응답 또는 타임아웃 이후 마무리됩니다.',
    '작성 초안의 복원은 HTTP 원본의 자동 복원을 의미하지 않습니다. 필요한 원본을 연결하고 가림 미리보기를 확인한 뒤 ZIP으로 저장합니다.',
    '실행 이력은 마스킹된 요약과 지문을 저장합니다. 과거 원본이 현재 세션에 없으면 현재 원본처럼 열지 않고 이력으로 구분합니다.',
]


def esc(value: str) -> str:
    return html.escape(value)


def image_uri(path: Path) -> str:
    return 'data:image/png;base64,' + base64.b64encode(path.read_bytes()).decode('ascii')


def capture_path(images: Path, step: dict) -> Path:
    return BROWSER_CAPTURE if step.get('captureKind') == 'browser' else images / step['image']


def module_rows() -> str:
    return ''.join('<tr><td><strong>'+esc(module['nav'])+'</strong><br/>'+esc(module['name'])+'</td><td>'+esc(module['core'])+'</td><td>'+esc(module['aux'])+'</td></tr>' for module in MODULES)


def scope_rows() -> str:
    return ''.join('<tr><td><strong>'+esc(name)+'</strong></td><td>'+esc(action)+'</td><td>'+esc(check)+'</td></tr>' for name,action,check in SCOPE_FEATURES)


def write_html(images: Path) -> Path:
    blocks = []
    for step in STEPS:
        path = capture_path(images, step)
        blocks.append(f'''<section id="step-{step['number']}" class="step"><div class="eyebrow">{esc(step['module'])} / {step['number']}</div>
<h2>{esc(step['title'])}</h2><ol>{''.join('<li>'+esc(text)+'</li>' for text in step['actions'])}</ol>
<figure class="{'browser' if step.get('captureKind')=='browser' else 'swing'}"><a href="{image_uri(path)}" download="{esc(path.name)}"><img src="{image_uri(path)}" alt="{esc(step['caption'])}"></a><figcaption>{esc(step['caption'])}</figcaption></figure>
<p class="check">{esc(step['check'])}</p></section>''')
    output = ROOT / 'docs/GUIDE_SIMPLE_KR.html'
    nav = ''.join(f'<a href="#step-{m["anchor"]}">{esc(m["nav"])}</a>' for m in MODULES)
    output.write_text(f'''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Workbench Simple {VERSION} 사용 가이드</title><style>
:root{{--ink:#17233b;--muted:#53627c;--accent:#315fcb;--line:#dbe3f0}}*{{box-sizing:border-box}}body{{margin:0;color:var(--ink);background:#f2f5fa;font:16px/1.7 system-ui,-apple-system,"Malgun Gothic",sans-serif}}main{{max-width:1240px;margin:auto;padding:40px 24px 80px}}h1{{font-size:40px;line-height:1.25;margin:12px 0 18px}}h2{{font-size:28px;line-height:1.3;margin:8px 0 18px}}h3{{font-size:20px}}a{{color:var(--accent)}}.cover,.step,.appendix{{background:white;border:1px solid var(--line);border-radius:18px;padding:32px;margin-bottom:28px}}.eyebrow{{font-size:13px;font-weight:700;letter-spacing:.1em;color:var(--accent)}}.lead{{font-size:20px}}.tools{{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:26px 0}}.tools a{{padding:14px 9px;background:#eaf0ff;border-radius:10px;text-decoration:none;font-weight:700;text-align:center}}.note,.check{{border-left:4px solid var(--accent);padding:12px 16px;background:#f2f6ff;color:#30445f}}.muted,figcaption{{font-size:13px;color:var(--muted)}}li{{margin:7px 0}}figure{{margin:24px 0 14px}}figure img{{width:100%;height:auto;border:1px solid var(--line);border-radius:8px}}figcaption{{margin-top:8px}}code{{font-size:14px;background:#edf1f7;padding:2px 5px;border-radius:4px}}table{{width:100%;border-collapse:collapse;font-size:14px}}td,th{{padding:12px;text-align:left;border-bottom:1px solid var(--line);vertical-align:top}}th{{background:#f4f7fc}}.back{{display:block;text-align:right;margin-top:20px}}@media(max-width:640px){{main{{padding:16px 10px 40px}}.cover,.step,.appendix{{padding:22px 18px}}h1{{font-size:30px}}h2{{font-size:24px}}.tools{{grid-template-columns:repeat(2,1fr)}}}}@media print{{body{{background:white}}main{{max-width:none;padding:0}}.cover,.step,.appendix{{border:0;border-radius:0;break-after:page;margin:0;padding:12mm}}.back{{display:none}}}}
</style><style>figure.browser{{max-width:460px;margin-left:auto;margin-right:auto}}</style></head><body><main id="top"><section class="cover"><div class="eyebrow">BURP WORKBENCH SIMPLE / {VERSION}</div><h1>Katana 기반 SCOPE 점검과 8개 도구</h1>
<p class="lead">기존 8개 도구를 유지하면서 <strong>SCOPE URL·HTTP 계정 선택 → Katana·통합 점검 → 환경·경로·API·민감정보 확인 → 원본·증거 연결</strong>로 이어갑니다. 필요한 기존 도구도 상단에서 직접 엽니다.</p>
<p><a href="#step-S01"><strong>SCOPE 통합 점검부터 시작하기 →</strong></a> · <a href="../downloads/guides/{VERSION}/Workbench_Simple_{VERSION}_Guide_KR.pdf">PDF 가이드북</a></p>
<nav class="tools">{nav}</nav><h3>설치하고 시작하기</h3><ol>{''.join('<li>'+esc(x)+'</li>' for x in INSTALL)}</ol>
<p>Simple은 설정 키 <code>simple.*</code>와 저장 폴더 <code>.burp-workbench-simple</code>을 사용합니다. 기존 Next의 설정·초안·보관 폴더와 분리됩니다.</p>
<p class="note">{esc(CAPTURE_NOTE)} 예제 URL·계정·판정 결과는 실제 점검 성공을 뜻하지 않습니다. 이미지를 누르면 원본 PNG로 저장할 수 있습니다.</p>
<p class="muted">도구나 통합 점검 화면을 여는 동작은 전송하지 않습니다. 통합 점검 시작과 각 도구의 실행 버튼에서 대상 요청이 시작됩니다.</p></section>
<section class="appendix"><div class="eyebrow">8 EXTENSIONS / CORE FUNCTIONS</div><h2>확장별 핵심 기능과 부가 흐름 정리</h2><table><thead><tr><th>기존 통합 도구</th><th>유지한 핵심 기능</th><th>부가 흐름 정리</th></tr></thead><tbody>{module_rows()}</tbody></table><p class="muted">메인 화면에서 제외한 기능과 필요할 때 여는 보조 설정을 구분했습니다. 주요 분석·검증 기능을 부가 기능으로 취급해 제거하지 않습니다.</p></section>
<section class="appendix"><div class="eyebrow">SCOPE INSPECTION</div><h2>한 실행에서 확인하는 항목</h2><table><thead><tr><th>결과</th><th>확인할 기능</th><th>판정과 범위</th></tr></thead><tbody>{scope_rows()}</tbody></table><p><a href="{KATANA_SPEC}">Katana 요구사항·구현·검사·가이드 연결표</a></p></section>
<section class="appendix"><div class="eyebrow">KATANA / {KATANA_VERSION}</div><h2>Katana를 설치하고 수집을 준비합니다</h2><p><a href="../tools/install_katana.py">공식 배포·SHA-256 확인 설치 도구</a> · <a href="KATANA_DEPENDENCY.json">고정 의존성 기록</a></p><ol>{''.join('<li>'+esc(x)+'</li>' for x in KATANA_INSTALL)}</ol>{''.join('<p>'+esc(text)+'</p>' for text in KATANA_NOTES)}<p><a href="#step-K01">실행 조건 그림 보기 →</a></p></section>
<section class="appendix"><div class="eyebrow">BROWSER HELPER / {HELPER_VERSION}</div><h2>브라우저·SPA 탐색을 준비합니다</h2><p><a href="../downloads/{VERSION}/{HELPER_ZIP}">{HELPER_ZIP} 내려받기</a></p><ol>{''.join('<li>'+esc(x)+'</li>' for x in HELPER_INSTALL)}</ol><p class="note">브라우저 모드가 필요할 때 준비합니다. 아래 절차는 설치 안내이며 실제 Chrome/Edge 설치 화면을 캡처한 그림이 아닙니다. 전용 탐색 탭은 허용한 URL 범위를 지키고, 표현할 수 없는 범위나 제외 리소스는 상태에서 확인합니다.</p></section>
<section class="appendix"><div class="eyebrow">READ THE RESULTS</div><h2>관측·추정·미확인과 실제 실행 범위를 읽습니다</h2>{''.join('<p>'+esc(text)+'</p>' for text in INTERPRETATION)}</section>
{''.join(blocks)}<section class="appendix"><div class="eyebrow">REFERENCE</div><h2>공통 사용 흐름과 확인 사항</h2>{''.join('<p>'+esc(text)+'</p>' for text in REFERENCE)}
<p><strong>Ctrl+F</strong> 요청 검색 · <strong>Ctrl+R</strong> 선택 요청을 Repeater에 준비 · <strong>F1</strong> 현재 도구 안내. 상단 8개 도구의 단축키는 각 버튼의 안내를 확인하세요.</p>
<h3>캡처와 검증의 범위</h3><p>{esc(CAPTURE_NOTE)} 실제 Burp 설치·GUI 클릭, Windows DPI·IME와 외부 서비스의 검증은 별도입니다. 새 {VERSION}에서 실제 실행한 검사와 제한은 <a href="{EVIDENCE_FILE}">버전별 검증 기록</a>을 확인합니다. 기존 1.0.0·Next의 검사 수를 새 결과로 합산하지 않습니다.</p>
<p><a href="history/1.2.0/GUIDE_SIMPLE_KR.html">보존한 Simple 1.2.0 안내서</a> · <a href="history/1.1.0/GUIDE_SIMPLE_KR.html">1.1.0 안내서</a> · <a href="history/1.0.0/GUIDE_SIMPLE_KR.html">1.0.0 안내서</a> · 기존 버전 파일과 별도로 관리합니다.</p><a class="back" href="#top">처음으로 ↑</a></section></main></body></html>''', encoding='utf-8')
    return output


def write_markdown(images: Path) -> Path:
    rel = Path(os.path.relpath(images, ROOT/'docs')).as_posix()
    lines = [f'# Workbench Simple {VERSION} 사용 가이드', '', '**기존 8개 도구를 유지하고 Katana와 SCOPE 통합 점검에서 메뉴·환경·경로·API·민감정보를 연결합니다.**', '',
             'SCOPE URL·HTTP 계정 선택 → Katana·통합 점검 → 결과·원본 확인 → 원문 값 분류 보고서·기존 도구·증거 저장으로 이어갑니다.', '', f'> {CAPTURE_NOTE}', '', '## 설치', '']
    lines += [f'{i}. {text}' for i,text in enumerate(INSTALL,1)]
    lines += ['', 'Simple 설정은 `simple.*`, 저장 폴더는 `.burp-workbench-simple`로 기존 Next와 분리됩니다.', '', '## 확장별 핵심 기능과 부가 흐름 정리', '',
              '| 기존 도구 | 유지한 핵심 기능 | 부가 흐름 정리 |', '|---|---|---|']
    lines += ['| '+module['nav']+' ('+module['name']+') | '+module['core']+' | '+module['aux']+' |' for module in MODULES]
    lines += ['', '## 통합 점검에서 확인하는 항목', '', '| 결과 | 기능 | 판정과 범위 |', '|---|---|---|']
    lines += ['| '+name+' | '+action+' | '+check+' |' for name,action,check in SCOPE_FEATURES]
    lines += ['', f'## Katana {KATANA_VERSION} 설치와 수집', '', '[공식 배포·SHA-256 확인 설치 도구](../tools/install_katana.py) · [고정 의존성 기록](KATANA_DEPENDENCY.json)', '']
    lines += [f'{i}. {text}' for i,text in enumerate(KATANA_INSTALL,1)]
    lines += ['']+[f'- {text}' for text in KATANA_NOTES]
    lines += ['', '## 브라우저·SPA 탐색 준비', '', f'[브라우저 helper {HELPER_VERSION} ZIP](../downloads/{VERSION}/{HELPER_ZIP})', '']
    lines += [f'{i}. {text}' for i,text in enumerate(HELPER_INSTALL,1)]
    lines += ['', '이 설치 절차는 텍스트 안내이며 실제 Chrome/Edge 설치 화면을 캡처한 그림이 아닙니다.', '', '## 결과를 읽는 기준', '']
    lines += [f'- {text}' for text in INTERPRETATION]
    for step in STEPS:
        lines += ['',f"## {step['number']}. {step['title']}",'',f"**{step['module']}**",'']
        lines += [f'{i}. {text}' for i,text in enumerate(step['actions'],1)]
        image_relative = Path(os.path.relpath(capture_path(images,step),ROOT/'docs')).as_posix()
        lines += ['',f"![{step['caption']}]({image_relative})",'',step['check']]
    lines += ['', '## 공통 확인 사항', ''] + [f'- {text}' for text in REFERENCE]
    lines += ['', '**Ctrl+F** 요청 검색 · **Ctrl+R** Repeater 준비 · **F1** 현재 도구 안내. 상단 도구 단축키는 버튼 안내를 확인합니다.', '',
              '## 검증 범위','',CAPTURE_NOTE,f'실제 실행한 검사와 미실행 범위는 [{VERSION} 검증 기록]({EVIDENCE_FILE})을 확인합니다. 과거 버전의 통과 수를 새 버전 검사로 합산하지 않습니다.', '',
              f'[Katana 요구사항·구현·검사·가이드 연결표]({KATANA_SPEC})', '',
              '[보존한 1.2.0 안내서](history/1.2.0/GUIDE_SIMPLE_KR.md) · [1.1.0 안내서](history/1.1.0/GUIDE_SIMPLE_KR.md) · [1.0.0 안내서](history/1.0.0/GUIDE_SIMPLE_KR.md)', '']
    output=ROOT/'docs/GUIDE_SIMPLE_KR.md';output.write_text('\n'.join(lines),encoding='utf-8');return output


def write_pdf(images: Path) -> Path:
    pdfmetrics.registerFont(TTFont('SimpleKorean',str(ROOT/'burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf')))
    pdfmetrics.registerFontFamily('SimpleKorean',normal='SimpleKorean',bold='SimpleKorean',italic='SimpleKorean',boldItalic='SimpleKorean')
    output=ROOT/f'downloads/guides/{VERSION}/Workbench_Simple_{VERSION}_Guide_KR.pdf';output.parent.mkdir(parents=True,exist_ok=True)
    c=canvas.Canvas(str(output),pagesize=landscape(A3));c.setTitle(f'Workbench Simple {VERSION} 사용 가이드');c.setAuthor('Burp Workbench KR')
    width,height=landscape(A3);margin=42
    ink,muted,accent=map(colors.HexColor,['#17233b','#53627c','#315fcb'])
    body=ParagraphStyle('body',fontName='SimpleKorean',fontSize=12,leading=18,textColor=ink,wordWrap='CJK',alignment=TA_LEFT)
    small=ParagraphStyle('small',parent=body,fontSize=10,leading=15,textColor=muted)
    def paragraph(text,x,y,w,style=body):
        item=Paragraph(text,style);_,h=item.wrap(w,height);item.drawOn(c,x,y-h);return y-h
    def frame(page,label,bookmark,browser=False):
        c.bookmarkPage(bookmark);c.addOutlineEntry(label,bookmark,level=0)
        c.setFillColor(accent);c.setFont('SimpleKorean',11);c.drawString(margin,height-32,f'WORKBENCH SIMPLE {VERSION} / {label}')
        c.setStrokeColor(colors.HexColor('#dbe3f0'));c.line(margin,37,width-margin,37)
        c.setFillColor(muted);c.setFont('SimpleKorean',9);c.drawString(margin,23,'실제 Chromium helper 페이지 · 로컬 합성 fixture · 실제 Burp GUI 실행과 구분' if browser else '합성 데이터로 렌더링한 운영 Swing 화면 · 실제 Burp GUI 캡처와 구분');c.drawRightString(width-margin,23,f'{page:02}')
    def heading(title,y):
        c.setFillColor(ink);c.setFont('SimpleKorean',25);c.drawString(margin,y,title);return y-25
    frame(1,'설치와 시작하기','start');y=heading('Katana와 SCOPE 통합 점검',height-90)
    y=paragraph('SCOPE URL·HTTP 계정 선택 → Katana·통합 점검 → 환경·경로·API·민감정보 확인 → 원본·증거 연결. 기존 8개 도구도 직접 선택해 사용합니다.',margin,y,width-2*margin)-28
    card_w=(width-2*margin-36)/4
    for index,module in enumerate(MODULES):
        x=margin+(index%4)*(card_w+12);top=y-(index//4)*62
        c.setFillColor(colors.HexColor('#eaf0ff'));c.roundRect(x,top-50,card_w,50,8,fill=1,stroke=0)
        c.setFillColor(accent);c.setFont('SimpleKorean',14);c.drawCentredString(x+card_w/2,top-31,module['nav'])
    y-=150
    for text in INSTALL+[ 'Simple은 설정 키 simple.*와 저장 폴더 .burp-workbench-simple을 사용해 기존 Next와 분리됩니다.',CAPTURE_NOTE]:
        y=paragraph(esc(text),margin,y,width-2*margin)-18
    paragraph('기존 Next와 Simple 1.0.0–1.3.1 JAR·소스·PDF를 보존합니다. 통합 점검 화면 열기는 전송하지 않으며 시작 버튼에서 실제 조회 요청을 보냅니다.',margin,y,width-2*margin,small);c.showPage()
    frame(2,'확장별 핵심 기능과 부가 흐름 정리','modules');y=heading('8개 확장의 주요 기능을 모두 유지합니다',height-80)
    data=[[Paragraph(esc(text),body) for text in ['기존 통합 도구','유지한 핵심 기능','부가 흐름 정리']]]
    for module in MODULES:data.append([Paragraph('<b>'+esc(module['nav'])+'</b><br/>'+esc(module['name']),body),Paragraph(esc(module['core']),body),Paragraph(esc(module['aux']),body)])
    table=Table(data,colWidths=[160,460,width-2*margin-620]);table.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('BACKGROUND',(0,0),(-1,0),colors.HexColor('#eaf0ff')),('LINEBELOW',(0,0),(-1,-1),.5,colors.HexColor('#dbe3f0')),('LEFTPADDING',(0,0),(-1,-1),10),('RIGHTPADDING',(0,0),(-1,-1),10),('TOPPADDING',(0,0),(-1,-1),10),('BOTTOMPADDING',(0,0),(-1,-1),10)]))
    _,table_h=table.wrap(width-2*margin,height)
    if table_h>y-90:raise RuntimeError('Module table does not fit PDF page')
    table.drawOn(c,margin,y-table_h-8)
    paragraph('제거한 부가 화면과 필요할 때 여는 보조 설정을 구분합니다. 주요 분석·검증 기능을 부가 기능으로 취급해 제거하지 않습니다.',margin,y-table_h-30,width-2*margin,small);c.showPage()
    frame(3,'SCOPE 통합 점검의 결과','scope-results');y=heading('한 실행에서 확인하는 항목',height-80)
    data=[[Paragraph(esc(text),body) for text in ['결과','연결한 기능','판정과 범위']]]
    for name,action,check in SCOPE_FEATURES:data.append([Paragraph('<b>'+esc(name)+'</b>',body),Paragraph(esc(action),body),Paragraph(esc(check),body)])
    table=Table(data,colWidths=[160,460,width-2*margin-620]);table.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('BACKGROUND',(0,0),(-1,0),colors.HexColor('#eaf0ff')),('LINEBELOW',(0,0),(-1,-1),.5,colors.HexColor('#dbe3f0')),('LEFTPADDING',(0,0),(-1,-1),10),('RIGHTPADDING',(0,0),(-1,-1),10),('TOPPADDING',(0,0),(-1,-1),12),('BOTTOMPADDING',(0,0),(-1,-1),12)]))
    _,table_h=table.wrap(width-2*margin,height)
    if table_h>y-90:raise RuntimeError('Scope feature table does not fit PDF page')
    table.drawOn(c,margin,y-table_h-8)
    paragraph(esc(f'한 결과에서 발견 근거·원본·실행 계정·검사 상태를 함께 확인합니다. 실제 적용 범위와 검사 연결은 docs/{KATANA_SPEC}에서 확인합니다.'),margin,y-table_h-30,width-2*margin,small);c.showPage()
    frame(4,'Katana 설치와 수집 준비','katana');y=heading('Katana를 설치하고 수집을 준비합니다',height-80)
    y=paragraph(esc(f'외부 Katana {KATANA_VERSION} · 공식 배포와 SHA-256 확인 · 실행 파일 별도 설치'),margin,y,width-2*margin)-22
    for index,text in enumerate(KATANA_INSTALL,1):y=paragraph(f'{index}. {esc(text)}',margin,y,width-2*margin)-14
    for text in KATANA_NOTES:y=paragraph(esc(text),margin,y,width-2*margin)-12
    if y<70:raise RuntimeError('Katana setup does not fit PDF page')
    c.showPage()
    frame(5,'브라우저 helper 설치와 연결','helper');y=heading('브라우저·SPA 탐색을 준비합니다',height-80)
    y=paragraph(esc(f'설치용 ZIP: downloads/{VERSION}/{HELPER_ZIP}'),margin,y,width-2*margin)-22
    for index,text in enumerate(HELPER_INSTALL,1):y=paragraph(f'{index}. {esc(text)}',margin,y,width-2*margin)-14
    paragraph('이 페이지는 설치 절차 안내입니다. 실제 Chrome/Edge 확장 설치 화면을 캡처한 그림이 아닙니다. 기존 사용자 탭과 브라우저 로그인 세션, HTTP 점검 프로필을 구분합니다.',margin,y,width-2*margin,small);c.showPage()
    frame(6,'결과와 실행 범위 읽기','interpretation');y=heading('관측·추정·미확인 상태를 구분합니다',height-80)
    for text in INTERPRETATION:y=paragraph(esc(text),margin,y,width-2*margin)-14
    if y<70:raise RuntimeError('Interpretation reference does not fit PDF page')
    c.showPage()
    for page,step in enumerate(STEPS,GUIDE_FIXED_PAGES):
        frame(page,f"{step['module']} / {step['number']}",'step-'+step['number'],step.get('captureKind')=='browser');y=heading(step['title'],height-72)
        for index,text in enumerate(step['actions'],1):y=paragraph(f'{index}. {esc(text)}',margin,y,width-2*margin)-3
        path=capture_path(images,step)
        with PILImage.open(path) as image:iw,ih=image.size
        top=y-12;scale=min((width-2*margin)/iw,(top-114)/ih);image_w,image_h=iw*scale,ih*scale;x=(width-image_w)/2
        c.drawImage(str(path),x,top-image_h,width=image_w,height=image_h);c.setStrokeColor(colors.HexColor('#dbe3f0'));c.rect(x,top-image_h,image_w,image_h)
        paragraph(esc(step['caption']),margin,102,width-2*margin,small);paragraph(esc(step['check']),margin,80,width-2*margin);c.showPage()
    frame(len(STEPS)+GUIDE_FIXED_PAGES,'공통 사용 흐름과 확인 사항','reference');y=heading('원본에서 필요한 도구와 증거로 이어갑니다',height-80)
    for text in REFERENCE:y=paragraph(esc(text),margin,y,width-2*margin)-20
    y=paragraph('<b>단축키</b> Ctrl+F 요청 검색 / Ctrl+R Repeater 준비 / F1 현재 도구 안내. 상단 도구 단축키는 버튼의 안내를 확인하세요.',margin,y,width-2*margin)-24
    y=paragraph(esc(CAPTURE_NOTE),margin,y,width-2*margin)-18
    paragraph(esc(f'실제 Burp GUI·Windows DPI/IME·외부 서비스의 검증과 구분합니다. 새 {VERSION}에서 실제 실행한 검사와 제한은 docs/{EVIDENCE_FILE}를 확인합니다. 과거 버전 검사 수를 합산하지 않습니다.'),margin,y,width-2*margin)
    c.save();return output


def write_readme() -> Path:
    lines=[f'# Workbench Simple {VERSION}', '',
           '**기존 8개 통합 확장 도구를 유지하고 Katana와 SCOPE 통합 점검으로 메뉴·환경·경로·API·민감정보를 한 실행에 연결합니다.** 기존 Next와 Simple 1.0.0·1.1.0·1.2.0·1.3.0·1.3.1 JAR·소스·PDF는 보존합니다.', '',
           'SCOPE URL·HTTP 계정 선택 → Katana·통합 점검 시작 → 결과·원본 확인 → 필요한 기존 도구·증거 저장으로 이어갑니다. 도구를 정해진 순서대로 모두 거칠 필요는 없습니다.', '',
           '| 필요한 파일 | 위치 |', '|---|---|',
           f'| 설치 JAR | [burp-workbench-simple-{VERSION}.jar](downloads/{VERSION}/burp-workbench-simple-{VERSION}.jar) |',
           f'| 독립 빌드 소스 | [소스 ZIP](downloads/{VERSION}/burp-workbench-simple-{VERSION}-source.zip) · [SHA256SUMS](downloads/{VERSION}/SHA256SUMS) |',
           f'| 브라우저·SPA helper | [helper {HELPER_VERSION} ZIP](downloads/{VERSION}/{HELPER_ZIP}) |',
           f'| 외부 Katana {KATANA_VERSION} | [공식 배포·SHA-256 확인 설치 도구](tools/install_katana.py) · [고정 의존성 기록](docs/KATANA_DEPENDENCY.json) · 실행 파일 별도 설치 |',
           '| 단계별 그림 가이드 | [HTML 안내서](docs/GUIDE_SIMPLE_KR.html) · [문서 원고](docs/GUIDE_SIMPLE_KR.md) |',
           f'| PDF 가이드 | [Workbench Simple 그림 가이드북](downloads/guides/{VERSION}/Workbench_Simple_{VERSION}_Guide_KR.pdf) |',
           f'| 실제 검증 결과 | [검사와 미실행 범위](docs/{EVIDENCE_FILE}) |',
           f'| Katana 실제 실행 | [공식 프로세스·격리 HTTP/HTTPS 검증](docs/verification/{VERSION}/katana-native/katana-verification.json) |',
           f'| 변경 안내 | [Simple {VERSION} 적용 기능과 정리한 부가 흐름](docs/RELEASE_SIMPLE_{VERSION}_KR.md) |',
           f'| 적용 범위 | [Katana 요구사항·구현·검사·가이드 연결표](docs/{KATANA_SPEC}) |', '',
           '## SCOPE 통합 점검', '',
           '| 결과 | 연결한 기능 | 판정과 범위 |', '|---|---|---|']
    lines += ['| '+name+' | '+action+' | '+check+' |' for name,action,check in SCOPE_FEATURES]
    lines += ['', '## 설치와 첫 점검', '']
    lines += [f'{index}. {text}' for index,text in enumerate(INSTALL,1)]
    lines += ['4. SCOPE 목록에 대상 URL과 경로를 등록하고 허용·제외 범위를 확인합니다. 통합 점검 화면에서 URL·HTTP 계정과 요청 한도를 정합니다.',
              '5. Katana 탐색의 실행 파일·수집 조건을 확인한 뒤 통합 점검을 시작합니다. Katana 수집과 환경·경로·API·키/민감정보·권한 차이·커버리지 결과를 확인합니다. 브라우저 모드는 helper를 준비한 뒤 선택합니다.',
              '6. 원본과 근거를 읽고 필요한 기존 도구에 연결합니다. 제출할 증거는 가림 미리보기 → 증거 ZIP 저장으로, 파싱한 원문 값은 통합 점검의 분류 보고서 저장으로 각각 저장합니다.', '',
              '화면 열기와 도구 이동은 전송하지 않습니다. 통합 점검 시작은 설정한 범위에 조회 요청을 전송합니다. 일반 트래픽 수집 OFF/SCOPE와 명시적 통합 점검 실행을 구분합니다.', '',
              f'## Katana {KATANA_VERSION} 설치와 수집', '']
    lines += [f'{index}. {text}' for index,text in enumerate(KATANA_INSTALL,1)]
    lines += ['', 'Linux/macOS에서 Simple 폴더 또는 독립 소스 ZIP 루트의 사용 예:', '', '```sh', 'python tools/install_katana.py --dest "$HOME/Tools/Workbench-Katana/1.8.0"', '```', '',
              '저장소 루트에서 실행할 때는 `python simple/tools/install_katana.py`를 사용합니다.', '',
              'Windows에서는 `--dest "C:/Tools/Workbench-Katana/1.8.0" --platform windows_amd64`처럼 사용자가 쓸 수 있는 외부 폴더와 해당 플랫폼을 지정할 수 있습니다. 설치 도구는 PATH·HOME 설정을 재지정하거나 시스템 CA를 설치하지 않습니다.', '']
    lines += [f'- {text}' for text in KATANA_NOTES]
    lines += ['', '## 브라우저·SPA 모드 준비', '']
    lines += [f'{index}. {text}' for index,text in enumerate(HELPER_INSTALL,1)]
    lines += ['', '브라우저 모드는 URL 규칙으로 안전하게 표현할 수 있는 범위에서 사용합니다. 지원하지 않는 정규식 범위는 사용 불가 사유를 확인하고 HTTP 모드로 점검합니다. 좁은 SCOPE가 필요한 리소스를 제외하면 부분 탐색으로 표시하며 범위를 자동 확장하지 않습니다.', '',
              '브라우저 로그인 계정과 HTTP 점검 계정은 별개입니다. helper는 쿠키를 HTTP 계정으로 복사하지 않으며, 브라우저 페이지·동작 한도와 네트워크 관측 수는 HTTP 요청 예산과 구분합니다.', '',
              '## 기존 8개 도구', '', '| 도구 | 유지한 핵심 기능 | 부가 흐름 정리 |', '|---|---|---|']
    lines += ['| '+m['nav']+' ('+m['name']+') | '+m['core']+' | '+m['aux']+' |' for m in MODULES]
    lines += ['', '## 결과를 읽는 기준', '']+[f'- {text}' for text in INTERPRETATION]
    lines += ['', '## 원본·이력·증거', '',
              '통합 점검은 현재 실행의 전체 원본 요청·응답을 불변 ID로 연결하고, 기존 도구와 증거에서 같은 원본을 사용합니다. 저장된 실행 이력은 마스킹된 결과·지문 중심이며 원본 패킷 자동 복원을 의미하지 않습니다. 과거 원본이 현재 세션에 없으면 이력으로 구분합니다.', '',
              'Simple 설정 키는 `simple.*`, 저장 폴더는 `.burp-workbench-simple`입니다. 기존 Next와 분리되며 이전 Simple 설정은 같은 Simple 저장 공간을 사용합니다.', '',
              '## 캡처와 검증', '', CAPTURE_NOTE+' 예제 URL·계정·판정은 실제 대상 점검의 성공을 뜻하지 않습니다. DOM 어댑터·실제 브라우저·실제 Burp 실행 범위는 버전별 검증 기록에서 구분합니다.', '',
              '## 개발과 가이드 재생성', '',
              '완전한 JDK에서 `python dev.py build`, `python dev.py check`를 사용합니다. 기본 컴파일은 `--release 17`입니다. Node 검사 런처는 설치된 Node가 지원하는 옵션 표기를 확인하며 실제 하위 검사 집계를 검증합니다.', '',
              '이번 로컬 검증은 별도로 준비한 완전한 Temurin JDK 17과 `--release 17`을 사용합니다. 런타임·컴파일 방식과 실제 실행한 검사·제한은 위의 검증 기록을 확인합니다.', '',
              '테스트 어댑터 빌드 후 두 캡처 명령으로 기존 8개 도구와 통합 점검 화면을 같은 버전 디렉터리에 생성합니다. PDF 생성에는 Python `reportlab`, `Pillow`가 필요하고, 구조 검증에는 `PyMuPDF`를 사용합니다.', '',
              '```sh', 'python tools/render_simple.py --check', 'python tools/render_scope_inspection.py --check', 'python tools/build_simple_guide.py', 'python tools/check_simple_guide.py', '```', '',
              'Java 17을 PATH에 둔 환경에서 위 명령을 실행하거나 `--java`로 Java 17 경로를 지정합니다. 원본 Next나 이전 Simple 결과를 새 검사 수로 합산하지 않습니다. GPL·제3자 고지를 보존합니다.', '',
              '[보존한 Simple 1.3.1 안내서](docs/history/1.3.1/GUIDE_SIMPLE_KR.html) · [1.3.1 PDF](downloads/guides/1.3.1/Workbench_Simple_1.3.1_Guide_KR.pdf) · [1.3.0 안내서](docs/history/1.3.0/GUIDE_SIMPLE_KR.html) · [1.3.0 PDF](downloads/guides/1.3.0/Workbench_Simple_1.3.0_Guide_KR.pdf) · [1.2.0 안내서](docs/history/1.2.0/GUIDE_SIMPLE_KR.html) · [1.1.0 안내서](docs/history/1.1.0/GUIDE_SIMPLE_KR.html) · [1.0.0 안내서](docs/history/1.0.0/GUIDE_SIMPLE_KR.html)', '']
    output=ROOT/'README_KR.md';output.write_text('\n'.join(lines),encoding='utf-8');return output


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images',type=Path,default=ROOT/f'docs/images/{VERSION}/light/1280x800')
    args=parser.parse_args();args.images=args.images.resolve()
    if tuple(map(int,VERSION.split('.'))) < (1,3,2):raise SystemExit('Simple1.0.0/1.1.0/1.2.0/1.3.0/1.3.1 artifacts are immutable. This generator requires Simple 1.3.2 or newer.')
    missing=[step['image'] for step in STEPS if not capture_path(args.images,step).is_file()]
    if missing:raise SystemExit('Missing current-version production screenshots: '+', '.join(missing))
    outputs=[write_readme(),write_markdown(args.images),write_html(args.images),write_pdf(args.images)]
    for path in outputs:print(path.relative_to(ROOT))


if __name__=='__main__':main()
