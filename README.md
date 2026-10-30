# Burp Workbench KR

**Simple 1.4.12는 승인한 버튼 배치를 13개 화면에 적용합니다.** 실행·중지는 함께, 선택 항목 작업은 해당 목록·상세 옆에 배치하고 Guide 그림도 갱신했습니다. [설치 JAR](simple/downloads/1.4.12/burp-workbench-simple-1.4.12.jar) · [실제 화면](docs/reports/2026-10-31-contextual-button-layout/README.md) · [변경 안내](simple/docs/RELEASE_SIMPLE_1.4.12_KR.md).

**Simple 1.4.11은 오른쪽 위 Guide 버튼으로 빨간 번호 사용 설명을 엽니다.** 16개 화면·96개 단계의 별도 가이드 창과 중복/보조 기능 정리를 제공합니다. [설치 JAR](simple/downloads/1.4.11/burp-workbench-simple-1.4.11.jar) · [가이드·정리 검토 이미지](simple/docs/images/1.4.11/README.md) · [사용법](simple/docs/RELEASE_SIMPLE_1.4.11_KR.md).

**Simple 1.4.10은 Workbench를 독립 창으로 엽니다.** 최대화·F11 전체화면, 36px 공통 상단과 색상·마우스 반응을 더한 메뉴를 적용합니다. [설치 JAR](simple/downloads/1.4.10/burp-workbench-simple-1.4.10.jar) · [실제 화면](simple/docs/images/1.4.10/README.md) · [사용법](simple/docs/RELEASE_SIMPLE_1.4.10_KR.md).

**Simple 1.4.9는 외부 노출·CVE 조사를 추가합니다.** Google 검색식·결과 URL과 NVD/KEV/EPSS 후보를 두 탭으로 검토합니다. [설치 JAR](simple/downloads/1.4.9/burp-workbench-simple-1.4.9.jar) · [구현 화면](simple/docs/images/1.4.9/README.md) · [기능·제한](simple/docs/RELEASE_SIMPLE_1.4.9_KR.md).

**Simple 1.4.8은 시안의 단계·요약 줄·계정 비교·상세 정보·증거 편집 구성 요소를 다시 적용합니다.** [설치 JAR](simple/downloads/1.4.8/burp-workbench-simple-1.4.8.jar) · [8개 실제 화면](simple/docs/images/1.4.8/README.md) · [시안 비교](simple/docs/design/1.4.8/COMPARE.html) · [변경·검증](simple/docs/RELEASE_SIMPLE_1.4.8_KR.md). 아래 이전 버전은 보존 이력입니다.

**Simple 1.4.7은 8개 도구의 세부 디자인을 적용합니다.** [설치 JAR](simple/downloads/1.4.7/burp-workbench-simple-1.4.7.jar) · [실제 8개 화면](simple/docs/images/1.4.7/README.md) · [시안/이전/현재 비교](simple/docs/design/1.4.7/COMPARE.html) · [변경·검증](simple/docs/RELEASE_SIMPLE_1.4.7_KR.md). 계정 카드·개수 배지·기능 트리와 공통 버튼·표·패킷·빈 상태를 정리했습니다.

**Simple 1.4.6은 승인 시안과 달랐던 1.4.5 화면을 다시 구성합니다.** [설치 JAR](simple/downloads/1.4.6/burp-workbench-simple-1.4.6.jar) · [실제 8개 화면](simple/docs/images/1.4.6/README.md) · [시안/구현 비교](simple/docs/design/1.4.6/COMPARE.html) · [변경·검증](simple/docs/RELEASE_SIMPLE_1.4.6_KR.md). 이전 배포 파일은 보존합니다.


**Simple 1.4.5는 승인한 제목 없는 도구별 배치를 적용합니다.** 계정 비교·응답 분석·증거의 선택 상세와 입력값 검사의 준비/결과 단계를 정리했습니다. [새 JAR](simple/downloads/1.4.5/burp-workbench-simple-1.4.5.jar) · [소스·체크섬](simple/downloads/1.4.5/) · [변경·실제 화면](simple/docs/RELEASE_SIMPLE_1.4.5_KR.md).

**Simple 1.4.4는 첨부한 0.39.2 스타일의 디자인·배치를 적용합니다.** 큰 도구 버튼 줄을 왼쪽 폴더와 작은 상단 메뉴로 바꾸고 Simple 기능·입력·원본 연결과 1.4.3 로드 수정을 유지합니다. [새 JAR](simple/downloads/1.4.4/burp-workbench-simple-1.4.4.jar) · [소스·체크섬](simple/downloads/1.4.4/) · [배치 안내](simple/docs/RELEASE_SIMPLE_1.4.4_KR.md).

**Simple 1.4.3은 Burp 확장 추가 시 `HttpServer` 클래스 누락으로 전체 로드가 실패하는 문제를 수정합니다.** 브라우저 연결 불가 이유를 표시하고 일반 HTTP 작업을 사용할 수 있게 합니다. [수정 JAR](simple/downloads/1.4.3/burp-workbench-simple-1.4.3.jar) · [소스·체크섬](simple/downloads/1.4.3/) · [Burp Java 복구 안내](simple/docs/RELEASE_SIMPLE_1.4.3_KR.md). 이전 배포와 아래 검증 이력은 보존합니다.
<!-- main-merge-0.53.4: 56b56e961d0d73e777228e60fdfc1a1e544496fd; pr-head: 64cc557bab399ae27d239ea928c266e810daf85d; pr: 94; merged-at-utc: 2026-10-27T05:19:48Z -->

**SCOPE를 한 번 지정하고, 관측한 요청에서 필요한 점검과 증거 정리로 이어가는 한국어 Burp Suite 확장입니다.** 같은 대상과 원본을 도구마다 다시 입력하는 수고를 줄이고, 사용자가 비교·재현·판단에 집중하도록 돕습니다.

**Workbench Simple 1.4.2는 반복 작업의 설정 절차와 오류 복구를 개선합니다.** 서버·경로의 대상·계정을 기본 화면에서 선택하고 고급 실행 조건·저장 관리는 별도로 유지합니다. 새 응답 수신 중 읽기 위치를 보존하고, URL 대상 준비 오류는 입력 창 안에서 바로 교정합니다. 검색 준비·완료 상태의 경합도 보강합니다. 실제 전후 검증과 남은 한계를 기록합니다.

[Simple 1.4.2 설치 JAR](simple/downloads/1.4.2/burp-workbench-simple-1.4.2.jar) · [독립 소스·체크섬](simple/downloads/1.4.2/) · [달라진 작업 흐름](simple/docs/RELEASE_SIMPLE_1.4.2_KR.md) · [목표별 전후 검증](docs/reports/2026-10-30-simple-workflow-goals/REPORT_KR.md)

**Workbench Simple 1.4.1은 로컬 실습 환경에서 전체 8개 도구의 테스트·개선·재검증을 5라운드 수행한 사용성 개선판입니다.** 매 라운드 전후를 새로 실행한 총 10번의 전체 흐름에서 각각 115개 확인 항목을 통과했습니다. 상세 근거의 읽기·복사 위치, 대상 변경 후 계정 선택, 대량 결과·URL 후보 갱신과 대시보드 결과 이동을 개선합니다. 기존 기능과 관리 메뉴를 유지하며 실제 HTTP와 합성 Swing 부하의 결과를 구분해 기록합니다.

[Simple 1.4.1 설치 JAR](simple/downloads/1.4.1/burp-workbench-simple-1.4.1.jar) · [독립 소스·체크섬](simple/downloads/1.4.1/) · [달라진 작업 흐름](simple/docs/RELEASE_SIMPLE_1.4.1_KR.md) · [5회 전후 비교와 검증](docs/reports/2026-10-30-simple-usability/REPORT_KR.md)

아래 1.4.0 대시보드 도입과 이전 버전은 보존한 배포 이력입니다.

**Workbench Simple 1.4.0은 내부 점검 대시보드를 추가합니다.** 현재 SCOPE 통합 점검의 관측 경로·API와 명세·환경과 DB·민감정보 후보·계정 관련 검토·미확인 수치를 보고, 카드·막대에서 조건별 목록 → 근거와 원본 → Repeater 준비·증거 추가로 이어갑니다. 계정·중요도 필터와 작은 창·다크테마를 지원하며 기존 8개 도구의 입력과 진입점을 유지합니다. 현재 실행만 집계하고 후보와 확정 취약점을 구분합니다.

[Simple 1.4.0 설치 JAR](simple/downloads/1.4.0/burp-workbench-simple-1.4.0.jar) · [독립 소스·체크섬](simple/downloads/1.4.0/) · [대시보드 사용법](simple/docs/RELEASE_SIMPLE_1.4.0_KR.md) · [실제 검증과 미실행 범위](simple/docs/VERIFICATION_SIMPLE_1.4.0.json)

아래 Simple 1.3.2 및 Next 기록은 기존 배포 이력입니다. 이전 산출물과 검증 기록은 보존합니다.

**Workbench Simple 1.3.2를 [PR #100](https://github.com/ohjun1998/burp-workbench-kr/pull/100)으로 main에 추가했습니다.** 기존 8개 도구를 유지하고 Scope·Katana 정보 수집과 메뉴 탐색·원문 보고서에 집중한 별도 버전입니다. 기존 Next와 과거 배포 파일은 보존합니다. 브라우저 메뉴 탐색 보완은 JAR와 helper 0.4.1을 함께 갱신해야 적용됩니다.

[Simple 설치 JAR](simple/downloads/1.3.2/burp-workbench-simple-1.3.2.jar) · [helper 0.4.1](simple/downloads/1.3.2/feature-mapper-browser-helper-0.4.1.zip) · [35쪽 가이드](simple/downloads/guides/1.3.2/Workbench_Simple_1.3.2_Guide_KR.pdf) · [소스·체크섬](simple/downloads/1.3.2/) · [사용 안내](simple/README_KR.md) · [병합 기록](docs/reports/2026-10-28-simple-main/MERGE_KR.md)

Simple 자동 검사5,184개·실제 Katana102개와112뷰 배치 검사를 통과한 파일 트리가 main 병합 결과와 일치합니다. 실제 Burp GUI·native MV3/DNR은 미검증이며, 실제 사이트의 미확인·부분 범위와 별도 직접 대조는 [검증 기록](simple/docs/VERIFICATION_SIMPLE_1.3.2.json)에 구분합니다.

기존 **Workbench Next 계열의 main 배포는 0.53.4 사용자 편의성 안정화판**입니다. 0.53.3 main `e0b40e3dae3fcca21d3cd9441260e3edae65d159`을 기준으로 개발하고 [PR #94](https://github.com/ohjun1998/burp-workbench-kr/pull/94)로 main에 병합했습니다. 내장 Feature Mapper **0.3.5**와 실습 사이트 **3.10.0**을 유지합니다. 0.53.3 배포·0.53.2 보존 후보판과 이전 모든 버전의 파일·검증 기록을 보존합니다.

**0.53.4 안정화는 고정한 개선 초안 복원·다단계 편집 복구·목록 고정·일괄 중요 표시·화면 개인화·키보드·비동기 비교·증거 작성 지원 8개를 모두 구현했습니다.** 소스 `937c42defbc4d76187f1d189b36b84f4272dd76a`의 [최종 CI](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40451792261)에서 Linux·Windows 각각 회귀 123개 그룹·4,270개와 페이로드 HTTP 136개·전체 기능 HTTP 102개·모의 진단 HTTP 266개, 총 **4,774개**를 통과했습니다. 플랫폼별 반복 실행은 고유 검사 수로 합산하지 않습니다. 전체 화면 **576뷰·8,088개 컨트롤·246개 상태**와 집중 화면 **414뷰·4,308개 컨트롤·246개 상태**의 배치 게이트를 모두 통과했습니다. 전체·집중 검사는 겹치므로 합산하지 않습니다. 검사 JAR와 배포 JAR의 SHA-256 `795e6566bcc1f5f7d059383b82d60e3efeb00d873310681c896b45eeddacef9d`가 일치하며, 소스 ZIP 382개 항목의 CRC·소스 지문도 확인했습니다. [PR #94](https://github.com/ohjun1998/burp-workbench-kr/pull/94)을 **2026-10-27 14:19:48 (한국 시간)**에 main에 병합했습니다. 병합 커밋은 `56b56e961d0d73e777228e60fdfc1a1e544496fd`, 최종 PR head는 `64cc557bab399ae27d239ea928c266e810daf85d`입니다. 실제 Burp GUI·Windows DPI/IME·브라우저 수동 조작·Docker·외부 AI/OAST/BApp 서비스는 이번 검증에서 실행하지 않았습니다.

이전 0.53.3은 **검색 일치 위치와 주변 문맥**, **증거 개별 제거·순서 변경·실행 취소**, **검색 0건의 원인 안내와 조건 복원**, **입력 옆 오류·버튼 비활성 사유**, **Ctrl+K 기능 찾기와 이전·다음 화면**, **검색·분석·비교·저장 작업 현황**, **상태·헤더·JSON 항목별 응답 차이**, **첫 사용 준비 체크리스트**를 추가합니다. 0.53.1의 비교 준비·검색 조건 저장·작업 이어가기는 유지합니다.

[0.53.4 설치·사용 안내](docs/RELEASE_0.53.4_NEXT_KR.md) · [설치 JAR·소스·버전별 다운로드](downloads/README.md) · [0.53.4 검증 기록](docs/VERIFICATION_0.53.4.json)

**보존한 0.53.3의 최종 자동 검증·배포 이력입니다.** 소스 `51184534`의 [최종 CI](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40414755887)에서 Linux·Windows 각각 4,476개 검사와 전체 Swing 450개 화면·162개 상태를 통과했습니다. 화면 배치 게이트는 모두 0건이며 검사 JAR와 배포 JAR의 SHA-256이 일치합니다. 작은 창에서 돌아올 때 탐색 막대가 불필요한 두 줄로 남는 문제를 고쳐 남은 점검 설명 높이를 확보했습니다. [설치 JAR](downloads/extensions/0.53.3/burp-workbench-kr-0.53.3.jar) · [소스 ZIP](downloads/extensions/0.53.3/burp-workbench-kr-0.53.3-source.zip) · [검증 기록](docs/VERIFICATION_0.53.3.json)을 확인하세요. main 병합을 완료했으며 실제 Burp GUI·Windows DPI/IME·브라우저 수동 조작·외부 서비스는 미실행입니다.

**0.53.2는 완료 배포가 아닌 보존 후보판입니다.** 초기 후보 CI는 통과했지만 [PR #91](https://github.com/ohjun1998/burp-workbench-kr/pull/91)의 전체 화면 검사에서 1024px 창·넓은 메뉴 조합의 남은 점검 설명 영역이 최소 높이 기준에 못 미쳤습니다. 기존 [0.53.2 파일](downloads/extensions/0.53.2/)과 [검증 이력](docs/VERIFICATION_0.53.2.json)은 그대로 보존하며, 탐색 막대 수정과 전체 화면 재검증은 새 버전 0.53.3에서 완료했습니다.

이전 main 배포인 0.53.1은 [PR #89](https://github.com/ohjun1998/burp-workbench-kr/pull/89)로 병합했습니다. [0.53.1 JAR·소스](downloads/extensions/0.53.1/) · [0.53.1 안내](docs/RELEASE_0.53.1_NEXT_KR.md) · [당시 CI·미실행 범위](docs/reports/2026-10-26-usability-0531/REMOTE_CI_KR.md)를 함께 보존합니다. 이전 0.52.0의 Windows·브라우저 CI 실패 기록도 [당시 검증 문서](docs/reports/2026-10-24-workbench-next-0520/REMOTE_CI_KR.md)에 남깁니다.

## 설치하고 첫 요청 확인하기

1. **[버전별 다운로드](downloads/README.md)**에서 사용하려는 버전과 검증 상태를 먼저 확인합니다. 0.53.4은 해당 버전의 JAR와 [0.53.4 안내](docs/RELEASE_0.53.4_NEXT_KR.md)를 함께 사용합니다. 이전 배포가 필요하면 보존된 0.53.1을 선택합니다. 기존 JAR와 프로젝트·설정 파일은 보관하고 이전 Workbench의 로드를 해제한 뒤 Burp의 **Extensions → Add → Java**에서 새 JAR를 선택합니다.
2. Workbench **요청·응답 목록 → SCOPE 목록**에서 URL을 추가하거나 Burp **HTTP history → Add to scope**로 대상을 등록합니다. 수집할 범위를 체크하고, 수집 버튼을 **OFF → SCOPE**로 바꾼 뒤 사이트를 다시 사용합니다. 기존 History는 범위 안의 요청을 골라 Workbench로 가져올 수 있습니다. 트래픽 OFF에서는 새 패킷·수동 가져오기·일반 자동 관찰을 수집하지 않습니다. 계정 권한 비교의 인증 탐지는 별도 ON/OFF로 제어합니다. 수동 URL은 해당 경로와 하위 경로에 적용됩니다. 목록에서 행을 선택해 URL을 수정하면 체크 상태를 유지한 채 즉시 반영됩니다.
3. **기능 찾기** 또는 **Ctrl+K**에서 이름·목적을 입력해 도구를 엽니다. 왼쪽 폴더에서도 선택할 수 있습니다. 메뉴를 숨겨도 상단 **메뉴 보기**로 다시 열 수 있습니다. 선택한 요청·응답을 읽고 목적에 맞는 도구로 이동합니다. 처음에는 **Repeater**로 요청을 준비해 직접 확인하고, 원본이 필요한 비교·증거 기능은 선택한 요청에서 이어갑니다.
4. 상단 **도우미 → 시작 준비 체크리스트**에서 SCOPE·수집·계정·첫 비교·증거 ZIP의 실제 상태를 확인합니다. **도우미 → 진행 중인 작업 현황**에서 실행 상태를 봅니다. **기능 안내**(**F1**)와 **[0.53.4 사용 안내](docs/RELEASE_0.53.4_NEXT_KR.md)**도 참고하세요. 재현한 결과는 **재현·증거 정리**에 모아 가림 미리보기를 확인하고 저장합니다.

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
| **Next 0.53.4 편의성 안정화 · main 병합** | [설치·새 작업 흐름](docs/RELEASE_0.53.4_NEXT_KR.md) · [현재 검증 상태](docs/VERIFICATION_0.53.4.json) |
| Next 0.53.3 이전 main 배포 · 보존 | [당시 안내](docs/RELEASE_0.53.3_NEXT_KR.md) · [당시 검증](docs/VERIFICATION_0.53.3.json) |
| Next 0.53.2 보존 후보판 · PR 화면 검사 미완료 | [당시 안내](docs/RELEASE_0.53.2_NEXT_KR.md) · [검증 이력](docs/VERIFICATION_0.53.2.json) |
| Next 0.53.1 이전 main 배포 · 보존 | [설치·사용 안내](docs/RELEASE_0.53.1_NEXT_KR.md) · [당시 검증](docs/VERIFICATION_0.53.1.json) |
| Next 0.52.0 main 병합 배포 · 보존 | [설치·기능별 안내](docs/RELEASE_0.52.0_NEXT_KR.md) · [당시 검증 기록](docs/VERIFICATION_0.52.0.json) |
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

**트래픽 → 기능 자동 매핑 → 기능 분류·실행**에서 사용합니다. 별도 Mapper JAR를 추가하지 않아도 기능·실행·세션·패킷·분류 근거·메모와 `.fmkr` 프로젝트 저장/열기를 사용할 수 있습니다. 수집 버튼은 요청·응답 목록과 같은 OFF/SCOPE 상태이며, OFF와 Scope 밖의 브라우저 이벤트도 보관하지 않습니다. Chrome/Edge의 버튼 이름까지 연결하려면 [다운로드 목록](downloads/README.md)에서 사용하는 확장 버전에 맞는 브라우저 도우미를 확인하고 **매핑 설정 → 브라우저 연결**의 주소·토큰을 입력합니다. 0.53.4·0.53.3·0.53.1의 내장 Mapper는 0.3.5입니다. 이전 별도 Mapper 확장은 해제하고 통합 Workbench를 사용하세요. [자세한 사용법](feature-mapper/README_KR.md).

[날짜별 작업 대화](docs/chat-logs/README.md)는 GitHub 변경과 함께 기록합니다. 이 버전의 안내서는 [Next 0.53.4 설치·사용 안내](docs/RELEASE_0.53.4_NEXT_KR.md)입니다. [0.25.4 PDF](downloads/guides/0.25.4-readable/Burp_Workbench_KR_0.25.4_guide.pdf)는 이전 화면을 설명하는 보존 자료입니다.



