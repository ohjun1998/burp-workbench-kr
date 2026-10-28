"""Korean instructions for Simple's pinned external Katana collection flow.

The text is paired with final production-component captures. Runtime validation
results belong to the versioned verification record, not this source module.
"""

KATANA_VERSION = '1.8.0'
KATANA_SPEC = 'KATANA_SCOPE_1.4.0_SPEC_KR.md'

KATANA_INSTALL = [
    'Simple 폴더 또는 독립 소스 ZIP의 루트에서 python tools/install_katana.py --dest 뒤에 쓸 수 있는 외부 절대 경로를 지정합니다. 저장소 루트에서는 simple/tools/install_katana.py를 사용합니다. 공식 Katana 1.8.0 배포와 SHA-256을 확인해 실행 파일·설치 기록을 저장합니다.',
    '설치 완료 시 출력된 실행 파일 경로를 복사합니다. Workbench의 SCOPE 통합 점검 → 실행 조건에서 파일 선택 또는 경로 입력 후 설치 확인을 누릅니다. Katana는 별도 설치하며 관리자 권한·PATH·사용자 CA 변경이 필요하지 않습니다.',
    'Katana 탐색을 선택하고 Katana 페이지 한도와 수집 시간(초)을 정합니다. 선택한 HTTP 계정이 Katana 수집의 실행 주체입니다. 대상 URL·허용 경로·계정·총 요청 한도를 확인한 뒤 통합 점검을 시작합니다.',
    'Katana 수집에서 상태·발견 출처·후보와 차단 사유를 확인합니다. 환경·경로·API·민감정보·계정별 차이·변경·커버리지는 기존 결과 탭에서 이어서 읽습니다.',
]

KATANA_NOTES = [
    '기존 Katana 재개 자료가 필요하면 먼저 보관합니다. 공급자 Katana 1.8.0은 버전 확인 때도 기본 사용자 설정 위치의 10일 지난 resume 파일을 정리할 수 있습니다. 설치 도구는 HOME을 재지정하지 않으며 이 동작을 의존성·설치 기록에 명시합니다.',
    'Katana는 HTML·JavaScript·폼 메타데이터·기술 단서를 수집합니다. 실제 대상 요청은 실행 전용 인증 loopback 프록시를 거쳐 Workbench의 HTTP 계정·SCOPE·요청 예산을 적용합니다.',
    'HTTPS CONNECT도 같은 전송 경계를 사용합니다. 실행용 임시 인증서를 사용하며 사용자 운영체제·브라우저에 CA 인증서를 설치하지 않습니다. 대상 Cookie·Authorization은 Katana 명령행이나 설정 파일로 전달하지 않습니다.',
    'JSONL의 경로·폼·기술 메타데이터는 자동 재조회하지 않습니다. 폼의 action·메서드·입력 이름은 발견 정보이며 값은 메타데이터에 남기지 않고 민감 필드 이름을 가립니다. 폼 제출·자동 로그인·쓰기·키 자동 검증·외부 공급자 API·자동 업데이트는 실행하지 않습니다.',
    'Katana는 # 조각이 없는 HTTP(S) 주소에서 표준 HTML/JS 모드로 수집합니다. SPA 메뉴·hash route는 기존 브라우저 helper에서 선택합니다. 브라우저 로그인 세션은 선택한 Workbench HTTP 계정과 별개입니다.',
    'Katana 종료와 사이트 전체 검증 완료를 구분합니다. 시간·페이지·요청 한도, 차단·실패·부분 분석과 미확인 항목을 확인합니다. 일시정지 후 재개해도 처음 정한 절대 시간과 공통 요청 예산을 유지합니다.',
    'Katana 실행 파일이 없거나 실행에 실패하면 Katana 실패·부분 수집 사유를 확인합니다. 일반 HTTP 점검 결과는 따로 유지하며 Katana 수집 성공으로 표시하지 않습니다.',
]

KATANA_STEPS = [
    {'number':'K01', 'module':'Katana 설치와 실행 조건', 'title':'Katana 설치를 확인하고 수집 조건을 정합니다', 'image':'23-katana-settings.png',
     'actions':['공식 Katana 1.8.0을 설치하고 SCOPE 통합 점검 → 실행 조건에서 파일 선택 또는 경로 입력 후 설치 확인 결과를 읽습니다.',
                'Katana 탐색과 페이지·수집 시간 한도를 정합니다. 대상 URL·HTTP 계정·SCOPE와 총 요청 예산을 확인한 뒤 통합 점검을 시작합니다.'],
     'check':'다음 확인: Katana 실행 파일은 별도 설치합니다. 실제 대상 조회에는 Workbench HTTP 계정·SCOPE·요청 한도가 적용됩니다.',
     'caption':'Katana 실행 조건 운영 컴포넌트 · 경로와 설치 상태는 합성 예제입니다.'},
    {'number':'K02', 'module':'Katana 수집', 'title':'수집 출처와 후보·차단·미확인 상태를 읽습니다', 'image':'24-katana-results.png',
     'actions':['Katana 수집에서 시작·진행·종료 상태, HTML·JS·폼·기술 출처와 발견 후보를 확인합니다. 실제 응답이 연결된 항목은 원본과 기존 분석 탭으로 이어집니다.',
                '범위 밖·쓰기·필수값 미확정·한도·실패 사유를 읽습니다. JSONL의 발견 메타데이터는 자동 재조회하지 않습니다. 환경·DB·API·민감정보·계정 차이·변경은 각각의 탭에서 근거와 함께 확인합니다.'],
     'check':'다음 확인: 후보 발견과 대상 내용 확인은 다릅니다. Katana가 끝나도 미검사·실패·부분 항목을 완료로 합산하지 않습니다.',
     'caption':'Katana 수집 상태와 출처 운영 컴포넌트 · 합성 이벤트이며 실제 Katana 실행 증명과 구분합니다.'},
]
