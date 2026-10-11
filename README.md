# Burp Workbench KR

한국어 Burp Suite 확장과 LOOP 커뮤니티 보안 실습 사이트를 함께 개발합니다. 이 브랜치의 테스트 배포는 **확장 0.24.0 · 실습 사이트 3.5.0**입니다. `main`에 합쳐진 상태는 [프로젝트 상태](PROJECT_STATUS_KR.md), 진행 중인 변경은 [Pull requests](https://github.com/ohjun1998/burp-workbench-kr/pulls)에서 확인합니다.

## 필요한 곳으로 바로 가기

| 하려는 일 | 시작 위치 |
|---|---|
| 확장 JAR·소스 ZIP·Docker 실행 ZIP 다운로드 | **[테스트 파일 다운로드](downloads/README.md)** |
| 그림과 함께 사용법 보기 | **[버전별 PDF 가이드북](downloads/guides/README.md)** |
| 커뮤니티 사이트에서 모의 진단하기 | **[점검 순서와 판정 기준](docs/TEST_WORKFLOW_KR.md)** |
| 이번 버전의 개선점·검증 범위 확인 | [0.24.0 변경 안내](docs/RELEASE_0.24.0_KR.md) |
| 버튼 위치와 작은 화면 개선 확인 | [0.24.0 화면·버튼 안내](docs/UI_0.24.0_KR.md) |
| 문서·소스·과거 기록 찾기 | [문서 목차](docs/README.md) · [저장소 안내](docs/REPOSITORY_MAP_KR.md) |

## 설치하고 점검하기

1. 다운로드 목록에서 같은 버전의 확장 JAR와 PDF를 받습니다. Burp의 **Extensions → Add → Java**에서 JAR를 선택합니다. 이전 Workbench는 먼저 해제합니다.
2. Docker 실행 ZIP을 별도 폴더에 풀고 Windows는 `start.ps1`, Linux/macOS는 `sh start.sh`를 실행합니다. Docker Desktop/Engine과 Compose v2가 필요하며 처음에는 이미지·빌드 의존성을 내려받습니다.
3. `http://127.0.0.1:8088`을 Burp 브라우저에서 열고 실습 주소를 SCOPE에 추가합니다. [점검 순서](docs/TEST_WORKFLOW_KR.md)에 따라 정상·취약·수정 동작을 비교하고 증거를 저장합니다.

Docker ZIP은 사이트 소스와 실행 스크립트입니다. Docker 설치 파일이나 사전 빌드 이미지가 아닙니다. 실습용 취약 경로와 가짜 계정이 있으므로 로컬 격리 환경에서 사용합니다. 실제 Burp·브라우저·Docker에서 확인한 범위는 해당 버전의 검증 기록을 기준으로 읽습니다.

0.24.0은 버튼을 해당 탭과 선택 대상 가까이에 배치하고 JS 원문·업무 흐름 상세의 공간 부족을 개선합니다. 실습 사이트와 Docker 패키지는 3.5.0을 사용합니다. 이전 확장·PDF 배포 파일도 다운로드 목록에서 계속 받을 수 있습니다.

## 개발과 자동 검증

Python 3.10+ 및 JDK 17+가 필요합니다. 저장소 루트에서 실행합니다.

```sh
python dev.py doctor
python dev.py check
```

`check`는 확장을 다시 빌드하고 회귀 검사와 격리된 loopback HTTP 검사를 실행합니다. 공식 확장 빌드 경로는 `dev.py` → `build_all.py`입니다. 과거 버전이 적힌 Maven POM은 확장 배포 경로가 아닙니다. 환경 설정과 각 명령은 [개발 환경 안내](DEVELOPMENT_SETUP_KR.md), 코드 위치는 [저장소 안내](docs/REPOSITORY_MAP_KR.md)를 확인합니다.

새 배포는 버전을 올린 뒤 `python dev.py release`로 생성합니다. 생성한 `downloads/`를 소스와 함께 PR에 포함하며 기존 버전 파일은 보존합니다. GitHub Actions는 빌드·기능 검사·화면 배치 검사·다운로드 무결성을 확인하고 JAR와 로그를 아티팩트로 제공합니다. 자동 검사가 실제 Burp GUI, 브라우저 DOM, Docker 전체 스택 또는 Pro 서비스 검증까지 대신하지는 않습니다.

## 협업

작업 전에 [AGENTS.md](AGENTS.md), [프로젝트 상태](PROJECT_STATUS_KR.md), [협업 안내](docs/COLLABORATION_KR.md)를 읽습니다. 계정 A/B는 `account-a/<작업명>` 또는 `account-b/<작업명>` 브랜치에서 작업하고, 변경·실제 검증·남은 한계를 PR로 전달합니다. 소스와 작업 기록은 GitHub에서 공유하며 ChatGPT 대화와 사용량은 계정별입니다.

기존 배포 원본·검증 자료는 `release/`, `history/`, `setup/`, `reproduction/`에 보존합니다. 이 폴더의 과거 문서보다 위의 현재 다운로드·가이드를 먼저 확인하세요. 확장 소스의 [GPL 라이선스](project/burp-development-workspace/burp-workbench-kr/burp-workbench/LICENSE)와 [제3자 고지](project/burp-development-workspace/burp-workbench-kr/burp-workbench/THIRD_PARTY_NOTICES.md)를 유지합니다.
