# Burp Workbench KR

**SCOPE를 한 번 지정하고, 관측한 요청에서 필요한 점검과 증거 정리로 이어가는 한국어 Burp Suite 확장입니다.** 같은 대상과 원본을 도구마다 다시 입력하는 수고를 줄이고, 사용자가 비교·재현·판단에 집중하도록 돕습니다.

처음 사용하는 분은 아래 **설치하고 첫 요청 확인하기**부터 시작하세요. 현재 테스트 배포는 **확장 0.31.0 · 실습 사이트 3.5.2**입니다. 수집 모드는 **OFF ↔ SCOPE**이며 시작 상태는 OFF입니다. SCOPE는 목록에서 체크한 **Burp Scope와 수동 추가 URL**을 수집합니다. 수동 URL은 행을 선택하고 입력값을 바꾼 뒤 **URL 수정**으로 편집합니다. 트래픽은 24px 행과 13px 나눔고딕으로 표시하며, 요청·응답에는 Pretty·Raw·Hex 탭과 구문 강조, 독립적인 Search 바를 제공합니다. 검색 결과 강조·이전/다음·대소문자·정규식을 지원하며 ⋯ 버튼으로 Burp 기본 편집기도 열 수 있습니다. **기능 안내 → 설정 → 로그**는 모든 도구에서 상단에 표시됩니다. [0.31.0 변경 안내](docs/RELEASE_0.31.0_KR.md)를 참고하세요.

## 설치하고 첫 요청 확인하기

1. **[최신 JAR와 PDF 다운로드](downloads/README.md)**에서 0.31.0 JAR와 0.25.4용 가이드를 받습니다. 이전 Workbench를 해제한 뒤 Burp의 **Extensions → Add → Java**에서 JAR를 선택합니다.
2. Workbench **요청·응답 목록 → SCOPE 목록**에서 URL을 추가하거나 Burp **HTTP history → Add to scope**로 대상을 등록합니다. 수집할 범위를 체크하고, 수집 버튼을 **OFF → SCOPE**로 바꾼 뒤 사이트를 다시 사용합니다. 기존 History는 범위 안의 요청을 골라 Workbench로 가져올 수 있습니다. OFF에서는 새 패킷·수동 가져오기·자동 관찰을 수집하지 않습니다. 수동 URL은 해당 경로와 하위 경로에 적용됩니다. 목록에서 행을 선택해 URL을 수정하면 체크 상태를 유지한 채 즉시 반영됩니다.
3. 왼쪽 폴더를 펼쳐 목적에 맞는 도구를 선택합니다. 메뉴를 숨겨도 상단 **메뉴 보기**로 다시 열 수 있습니다. 선택한 요청·응답을 읽고 목적에 맞는 도구로 이동합니다. 처음에는 **Repeater**로 요청을 준비해 직접 확인하고, 원본이 필요한 비교·증거 기능은 선택한 요청에서 이어갑니다.
4. 상단 **기능 안내** 버튼(또는 **F1**)과 **[그림 가이드북](downloads/guides/README.md)**으로 준비할 입력과 다음 버튼을 확인합니다. 재현한 결과는 **재현·증거 정리**에 모아 저장합니다.

SCOPE 공유와 이미 관측한 응답 분석은 반복 작업을 줄입니다. 추가 요청 전송·계정 비교 실행·수동 검증·취약점 판단·증거 저장은 사용자가 선택합니다. Repeater로 보내기는 요청을 준비하는 동작이며, Burp Repeater의 **Send**는 직접 누릅니다.

## 하려는 일로 도구 찾기

| 하고 싶은 일 | 현재 화면 |
|---|---|
| 요청·응답을 읽고 점검 범위를 정하기 | **요청·응답 목록** |
| 같은 요청을 다른 계정 조건으로 비교하기 | **계정 권한 비교** |
| 인코딩·JWT 내용을 읽거나 두 응답의 차이 찾기 | **데이터 변환·비교** |
| JavaScript에 적힌 API와 원본 위치 찾기 | **JS에서 API 찾기** |
| 서버 정보와 경로를 살펴보기 | **서버·경로 탐색** |
| 사이트와 문서에서 URL 후보 모으기 | **URL 후보 수집** |
| URL·계정·업무의 완료와 재검토 상태 보기 | **점검 진행 현황** |
| 응답의 보안 단서·API 문서·저장 후 조회 흐름 읽기 | **응답 보안 분석** |
| 선택한 입력값에 검사값을 넣어 응답 비교하기 | **입력값 보안 검사** |
| 요청을 순서대로 재현하고 원본·증거 파일 정리하기 | **재현·증거 정리** |

폴더는 **트래픽 / 탐색·정보 수집 / 보안 분석·검증 / 데이터 도구 / 점검 관리·증거**입니다. **설정**은 상단의 별도 버튼으로 열고, SCOPE 목록은 기존처럼 **요청·응답 목록** 안에서 관리합니다.

[이전 이름과 새 이름 대응표](docs/RELEASE_0.25.0_KR.md) · [화면·버튼 안내](docs/UI_0.25.0_KR.md) · [문서 목차](docs/README.md)

## 로컬 커뮤니티에서 연습하기

[Docker 실행 ZIP](downloads/README.md)을 별도 폴더에 풀고 Windows는 `start.ps1`, Linux/macOS는 `sh start.sh`를 실행합니다. Docker Desktop/Engine과 Compose v2가 필요하며 처음에는 이미지·빌드 의존성을 내려받습니다. `http://127.0.0.1:8088`을 Burp 브라우저에서 열고 실습 주소를 SCOPE에 추가하세요.

**[점검 순서와 판정 기준](docs/TEST_WORKFLOW_KR.md)**에 따라 정상·취약·수정 동작을 비교하고 증거를 저장할 수 있습니다. Docker ZIP은 사이트 소스와 실행 스크립트이며 Docker 설치 파일이나 사전 빌드 이미지는 아닙니다. 실습용 취약 경로와 가짜 계정이 있으므로 로컬 격리 환경에서 사용합니다.

## 변경과 검증 확인하기

| 찾는 자료 | 위치 |
|---|---|
| JAR·소스 ZIP·Docker ZIP·PDF | [테스트 파일 다운로드](downloads/README.md) |
| 수집 모드·Scope와 배포 변경점 | [0.31.0 변경 안내](docs/RELEASE_0.31.0_KR.md) |
| 실제 실행한 검사와 남은 범위 | [0.31.0 검증 기록](docs/VERIFICATION_0.31.0.json) |
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

[Feature Mapper KR](feature-mapper/README_KR.md)은 사이트 사용 중 발생한 패킷을 기능·실행 사례별로 묶는 별도 Burp 확장입니다. Chrome/Edge 보조 확장의 화면·버튼 정보를 함께 사용할 수 있습니다. 설치용 JAR·브라우저 보조 확장·소스는 [별도 다운로드](downloads/feature-mapper/0.3.0/)에서 받으세요. Workbench의 기존 기능과 배포 파일을 유지합니다.

[날짜별 작업 대화](docs/chat-logs/README.md)는 GitHub 변경과 함께 기록합니다. 최신 안내서는 [0.25.4 읽기 쉬운 PDF](downloads/guides/0.25.4-readable/Burp_Workbench_KR_0.25.4_guide.pdf)입니다.

