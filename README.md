# Burp Workbench KR

**SCOPE를 한 번 지정하고, 관측한 요청에서 필요한 점검과 증거 정리로 이어가는 한국어 Burp Suite 확장입니다.** 같은 대상과 원본을 도구마다 다시 입력하는 수고를 줄이고, 사용자가 비교·재현·판단에 집중하도록 돕습니다.

이 작업 브랜치는 **Workbench Next 0.52.1 안정화판**입니다. 현재 `main`의 0.52.0에서 검색 예약·응답 초기화·브라우저 본문 관측 근거를 보완합니다. 기존 **Workbench KR 0.49.1**과 **Next 0.50.0**의 배포 파일·소스와 과거 커밋은 그대로 보존합니다. 확장 이름·탭·배지의 **Next** 표시와 기존 버전에서 분리한 설정·기록 저장소를 유지합니다. 내장 Feature Mapper는 **0.3.5**, 실습 사이트는 **3.10.0**입니다.

0.50.0의 **8개 주요 메뉴**를 유지하면서 검색·응답 분석의 반복 작업과 보유 데이터를 줄였습니다. **권한 설정 묶음·여러 쓰기 조회 규칙**, **Mapper 기능 → 점검 업무**, **원본 → 실행 → 정책 revision → 증거 ZIP** 연결, **JS 기록의 디스크 페이지 탐색·보존 설정**을 추가했습니다. GET·JSON 후보는 창을 먼저 표시한 뒤 백그라운드에서 읽습니다.

[0.52.1 안정화 변경·종료조건](docs/RELEASE_0.52.1_KR.md) · [최종 검증](docs/VERIFICATION_0.52.1.json) · [JAR·소스 다운로드](downloads/README.md)

[0.52.0 기능별 상세 사용법](docs/RELEASE_0.52.0_NEXT_KR.md)과 당시 검증·실패 기록은 보존합니다.

[PR #86](https://github.com/ohjun1998/burp-workbench-kr/pull/86)에서 0.52.0을 main에 병합한 당시의 Windows 검색·브라우저 실패 기록은 [보존 문서](docs/reports/2026-10-24-workbench-next-0520/REMOTE_CI_KR.md)에 남깁니다. 후속 **[PR #88](https://github.com/ohjun1998/burp-workbench-kr/pull/88)의 0.52.1 소스는 기본·실제 Chrome·Windows/내구성 CI를 모두 첫 실행에서 통과**했습니다. [원격 검증 상세](docs/reports/2026-10-24-stability-0521/REMOTE_CI_KR.md)

기존 버전의 당시 동작과 검사 범위는 [0.49.1 안내](docs/RELEASE_0.49.1_KR.md), [0.49.0 안내](docs/RELEASE_0.49.0_KR.md)에 남깁니다. 해당 문서의 미실행 표시는 그 버전에 대한 기록입니다.

## 설치하고 첫 요청 확인하기

1. **[Next JAR·소스 다운로드](downloads/README.md)**에서 **0.52.1 Next JAR**를 받습니다. [Next 상세 안내](docs/RELEASE_0.52.0_NEXT_KR.md)에 새 화면과 기능별 순서를 정리했습니다. 기존 JAR는 보관하고 이전 Workbench의 로드만 해제한 뒤 Burp의 **Extensions → Add → Java**에서 JAR를 선택합니다.
2. Workbench **요청·응답 목록 → SCOPE 목록**에서 URL을 추가하거나 Burp **HTTP history → Add to scope**로 대상을 등록합니다. 수집할 범위를 체크하고, 수집 버튼을 **OFF → SCOPE**로 바꾼 뒤 사이트를 다시 사용합니다. 기존 History는 범위 안의 요청을 골라 Workbench로 가져올 수 있습니다. 트래픽 OFF에서는 새 패킷·수동 가져오기·일반 자동 관찰을 수집하지 않습니다. 계정 권한 비교의 인증 탐지는 별도 ON/OFF로 제어합니다. 수동 URL은 해당 경로와 하위 경로에 적용됩니다. 목록에서 행을 선택해 URL을 수정하면 체크 상태를 유지한 채 즉시 반영됩니다.
3. 왼쪽 폴더를 펼쳐 목적에 맞는 도구를 선택합니다. 메뉴를 숨겨도 상단 **메뉴 보기**로 다시 열 수 있습니다. 선택한 요청·응답을 읽고 목적에 맞는 도구로 이동합니다. 처음에는 **Repeater**로 요청을 준비해 직접 확인하고, 원본이 필요한 비교·증거 기능은 선택한 요청에서 이어갑니다.
4. 상단 **기능 안내** 버튼(또는 **F1**)과 **[Next 기능별 안내](docs/RELEASE_0.52.0_NEXT_KR.md)**로 준비할 입력과 다음 버튼을 확인합니다. 재현한 결과는 **재현·증거 정리**에 모아 저장합니다.

SCOPE 공유와 이미 관측한 응답 분석은 반복 작업을 줄입니다. 추가 요청 전송·계정 비교 실행·수동 검증·취약점 판단·증거 저장은 사용자가 선택합니다. Repeater 창 열기와 Burp Repeater 복사는 요청을 준비하는 동작입니다. 실제 요청은 해당 창의 **Send**를 직접 눌러 전송합니다.

## 하려는 일로 도구 찾기

| 하고 싶은 일 | 현재 화면 |
|---|---|
| 요청·응답을 읽고 점검 범위를 정하기 | **요청·응답 목록** |
| 사이트 패킷을 기능·실행 사례별로 자동 묶기 | **기능 자동 매핑** |
| 같은 요청을 다른 계정 조건으로 비교하기 | **계정 권한 비교** |
| 인코딩·JWT 내용을 읽거나 두 응답의 차이 찾기 | **요청·응답 목록 → 변환·비교** |
| JS·JSON·XML·텍스트에서 API·키 후보와 원본 위치 찾기 | **JS에서 API 찾기** |
| 서버 정보와 경로를 살펴보기 | **서버·경로 탐색** |
| 사이트와 문서에서 URL 후보 모으기 | **서버·경로 탐색 → URL 후보 수집** |
| URL·계정·업무의 완료와 재검토 상태 보기 | **기능 자동 매핑 → 점검 진행 현황** |
| 응답의 보안 단서·API 문서·저장 후 조회 흐름 읽기 | **응답 보안 분석** |
| 선택한 입력값에 검사값을 넣어 응답 비교하기 | **입력값 보안 검사** |
| 요청을 순서대로 재현하고 원본·증거 파일 정리하기 | **재현·증거 정리** |

폴더는 **트래픽 / 탐색·정보 수집 / 보안 분석·검증 / 점검 관리·증거**입니다. **설정**은 상단의 별도 버튼으로 열고, SCOPE 목록은 기존처럼 **요청·응답 목록** 안에서 관리합니다.

[이전 이름과 새 이름 대응표](docs/RELEASE_0.25.0_KR.md) · [화면·버튼 안내](docs/UI_0.25.0_KR.md) · [문서 목차](docs/README.md)

## 로컬 커뮤니티에서 연습하기

**3.10.0 인증·권한 실습 커뮤니티**: `/login`에서 로그인하면 실제 서버 권한에 맞는 메뉴를 제공합니다. 공통 권한 정책에 기능별 인가·작성자·담당 게시판 검사를 모으고, 내 권한·관리자 권한표·접근 기록을 추가했습니다. 왼쪽 탐색, 카드·관리 표, 통합 검색과 모바일 메뉴 서랍으로 화면을 개편했습니다. [50개 기능·권한 검증 안내](docs/LAB_3.10.0_KR.md)를 참고하세요. 기존 취약점 실습 8개를 같은 커뮤니티에서 비교할 수 있습니다.

[Docker 실행 ZIP](downloads/README.md)을 별도 폴더에 풀고 Windows는 `start.ps1`, Linux/macOS는 `sh start.sh`를 실행합니다. Docker Desktop/Engine과 Compose v2가 필요하며 처음에는 이미지·빌드 의존성을 내려받습니다. `http://127.0.0.1:8088`을 Burp 브라우저에서 열고 실습 주소를 SCOPE에 추가하세요.

**[점검 순서와 판정 기준](docs/TEST_WORKFLOW_KR.md)**에 따라 정상·취약·수정 동작을 비교하고 증거를 저장할 수 있습니다. Docker ZIP은 사이트 소스와 실행 스크립트이며 Docker 설치 파일이나 사전 빌드 이미지는 아닙니다. 실습용 취약 경로와 가짜 계정이 있으므로 로컬 격리 환경에서 사용합니다.

인증 방식별 로그인·권한 검증 메뉴는 세션, 불투명 토큰, JWT와 네 가지 복합 조합을 제공합니다. 관리자·일반 사용자·비로그인으로 정상/취약 URL을 비교하고 인증값 누락·혼합·변조·만료와 실제 쓰기 상태를 확인합니다. [실제 Burp 검증 보고서](docs/reports/2026-10-18/Auth_Comparison_Report_20261018.pdf)와 [세부 근거](docs/VERIFICATION_LAB_3.9.0.json)를 함께 제공합니다.

50개 업무 기능을 admin·Alice·Bob·비로그인과 인증 조합 7개로 대조했습니다. **0.44.0 재측정**은 정상/취약 경로의 독립 HTTP 2,800개와 실제 Burp 비교 기록 1,400개·계정 대조 4,200개입니다. 권한표·차단 규칙·전후 조회를 설정한 2,100개 계정 대조에서 정탐 392건·오탐 0건·미탐 0건·보류 91건입니다. 이전 0.43.0의 오탐 21건을 없애고 보류 532건을 줄였습니다. 설정 전에는 엄격한 집계의 미탐 392건이며, 보류는 TN/FN과 겹치므로 합산하지 않습니다. 같은 실습 서버의 반복 측정이며 운영 정탐 확률로 일반화하지 않습니다. [고도화 재검증 보고서](docs/reports/2026-10-18-auth50-upgrade/Auth50_Upgrade_Report_0.44.0_KR.pdf) · [판정별 사유 CSV](docs/verification/auth50-0.44.0/account-results.csv) · [0.44.0 검증](docs/VERIFICATION_0.44.0.json) · [이전 0.43.0 측정 보고서](docs/reports/2026-10-18-auth50/Auth50_Report_20261018.pdf)

## 변경과 검증 확인하기

| 찾는 자료 | 위치 |
|---|---|
| **Next 0.52.0 고도화 버전** | [설치·기능별 안내](docs/RELEASE_0.52.0_NEXT_KR.md) · [검증 기록](docs/VERIFICATION_0.52.0.json) |
| Next 0.50.0 최초 분리 버전 · 보존 | [설치·기능별 안내](docs/RELEASE_0.50.0_NEXT_KR.md) · [당시 검증](docs/VERIFICATION_0.50.0.json) |
| 0.49.1 계정 비교 전 설정·판정 한계 안내 | [변경 안내](docs/RELEASE_0.49.1_KR.md) · [빌드·검사 범위](docs/VERIFICATION_0.49.1.json) |
| 0.49.0 전체 고도화·재검증과 남은 범위 | [변경 안내](docs/RELEASE_0.49.0_KR.md) · [검증 기록](docs/VERIFICATION_0.49.0.json) |
| Feature Mapper 통합·11개 도구 설치와 사용법 | [0.47.0 변경 안내](docs/RELEASE_0.47.0_KR.md) |
| 통합 회귀·Windows/Linux·Chromium 검사와 main 병합 근거 | [0.47.0 검증 기록](docs/VERIFICATION_0.47.0.json) |
| JAR·소스 ZIP·Docker ZIP·PDF | [테스트 파일 다운로드](downloads/README.md) |
| GraphQL·SQL 상태 차이·숫자 업무 규칙 개선 | [0.46.1 변경 안내](docs/RELEASE_0.46.1_KR.md) |
| Vuln Bank 실제 확장 재검증과 4쪽 화면 보고서 | [0.46.1 검증 기록](docs/VERIFICATION_0.46.1.json) |
| 메뉴를 유지한 계정·JS·SQL·저장 흐름 고도화 | [0.45.0 변경 안내](docs/RELEASE_0.45.0_KR.md) |
| Juice Shop 전후 비교와 실제 검증 범위 | [0.45.0 검증 기록](docs/VERIFICATION_0.45.0.json) |
| 계정 응답 판정·쓰기 보류 사유와 배포 변경점 | [0.44.0 변경 안내](docs/RELEASE_0.44.0_KR.md) |
| 실제 실행한 검사와 남은 범위 | [0.44.0 검증 기록](docs/VERIFICATION_0.44.0.json) |
| 소스·도구·과거 자료 위치 | [저장소 안내](docs/REPOSITORY_MAP_KR.md) |

이전 버전 파일과 검증 기록은 보존합니다. `main`에 합쳐진 상태는 [프로젝트 상태](PROJECT_STATUS_KR.md), 진행 중인 변경과 CI는 [Pull requests](https://github.com/ohjun1998/burp-workbench-kr/pulls)에서 확인합니다. 자동 검사·Swing 화면 렌더링과 실제 Burp GUI·브라우저·Docker 실행은 구분하며, 수행한 범위는 버전별 검증 기록을 기준으로 읽습니다.

## 개발과 협업

Python 3.10+ 및 JDK 17+를 준비하고 저장소 루트에서 실행합니다.

```sh
python dev.py doctor
python dev.py check
```

`check`는 확장을 다시 빌드하고 회귀 검사와 격리된 loopback HTTP 검사를 실행합니다. 공식 확장 빌드 경로는 `dev.py` → `build_all.py`입니다. 과거 버전이 적힌 Maven POM은 확장 배포 경로가 아닙니다. [개발 환경 안내](DEVELOPMENT_SETUP_KR.md)에서 각 명령과 화면·PDF 재생성 절차를 확인하세요.

작업 전에 [AGENTS.md](AGENTS.md), [프로젝트 상태](PROJECT_STATUS_KR.md), [협업 안내](docs/COLLABORATION_KR.md)를 읽습니다. 계정 A/B는 `account-a/<작업명>` 또는 `account-b/<작업명>` 브랜치에서 작업하고, 변경·실제 검증·남은 한계를 PR로 전달합니다. 새 배포는 버전을 올린 뒤 `python dev.py release`로 만들고 버전별 `downloads/`를 소스와 함께 PR에 포함합니다. 기존 버전은 덮어쓰지 않습니다.

`release/`, `history/`, `setup/`, `reproduction/`의 문서는 작성 당시의 기록입니다. 현재 사용법은 위의 다운로드·가이드를 먼저 확인하세요. 확장 소스의 [GPL 라이선스](project/burp-development-workspace/burp-workbench-kr/burp-workbench/LICENSE)와 [제3자 고지](project/burp-development-workspace/burp-workbench-kr/burp-workbench/THIRD_PARTY_NOTICES.md)를 유지합니다.


## 기능별 패킷 자동 매핑

Workbench Next 0.50.0의 **트래픽 → 기능 자동 매핑 → 기능 분류·실행**에서 사용합니다. 별도 Mapper JAR를 추가하지 않아도 기능·실행·세션·패킷·분류 근거·메모와 `.fmkr` 프로젝트 저장/열기를 사용할 수 있습니다. 수집 버튼은 요청·응답 목록과 같은 OFF/SCOPE 상태이며, OFF와 Scope 밖의 브라우저 이벤트도 보관하지 않습니다. Chrome/Edge의 버튼 이름까지 연결하려면 [브라우저 도우미 0.3.3](downloads/feature-mapper/0.3.3/feature-mapper-browser-0.3.3.zip)를 브라우저에 설치하고 **매핑 설정 → 브라우저 연결**의 주소·토큰을 입력합니다. 이전 별도 Mapper 확장은 해제하고 통합 Workbench를 사용하세요. [자세한 사용법](feature-mapper/README_KR.md).

[날짜별 작업 대화](docs/chat-logs/README.md)는 GitHub 변경과 함께 기록합니다. 현재 안내서는 [Next 0.50.0 설치·기능별 안내](docs/RELEASE_0.52.0_NEXT_KR.md)입니다. [0.25.4 PDF](downloads/guides/0.25.4-readable/Burp_Workbench_KR_0.25.4_guide.pdf)는 이전 화면을 설명하는 보존 자료입니다.


