# Burp Workbench KR

한국어 Burp Suite 확장 **0.22.1**과 커뮤니티 실습 사이트 **3.4.0** 개발 저장소입니다. 첨부한 개발 준비 패키지를 기준으로 소스, 의존성, 배포 원본 및 인수인계 기록을 함께 보존합니다.

## 테스트 파일 다운로드

[버전별 JAR · 소스 · Docker 실행 ZIP](downloads/README.md)에서 필요한 파일만 내려받을 수 있습니다. [0.22.0 변경 안내](docs/RELEASE_0.22.0_KR.md)를 함께 확인하세요.

## 시작하기

Python 3.10+ 및 JDK 17+가 필요합니다. 저장소 루트에서 실행합니다.

```sh
python dev.py doctor
python dev.py check
```

공식 빌드 경로는 `dev.py` → `build_all.py`입니다. 과거 버전이 적힌 Maven POM으로 확장 배포본을 만들지 않습니다. 재빌드된 확장은 `project/burp-development-workspace/burp-workbench-kr/burp-workbench/dist/`에 생성됩니다. 원본 JAR는 `release/`에 있습니다.

## 두 ChatGPT 계정으로 진행하기

1. 각 ChatGPT 계정의 GitHub 연결에서 이 비공개 저장소를 사용할 수 있도록 연결합니다. 같은 GitHub 계정 `ohjun1998`에 연결하면 별도 GitHub 사용자 초대가 필요하지 않습니다.
2. 각자의 Work 대화에서 저장소와 작업을 지정합니다. 계정 A는 `account-a/<작업명>`, 계정 B는 `account-b/<작업명>` 브랜치를 사용합니다.
3. 작업 시작 시 최신 `main`, `AGENTS.md`, `PROJECT_STATUS_KR.md`와 `docs/COLLABORATION_KR.md`를 읽습니다.
4. 변경과 검증 결과를 PR로 올리고 다른 계정에서 검토합니다. 검증을 확인한 후 `main`에 합칩니다.
5. 다음 작업 전에 최신 `main`을 다시 읽습니다. 소스와 기록은 GitHub에 동기화되며 ChatGPT 대화와 사용량은 계정별입니다.

구체적인 연결 절차와 두 계정의 시작문은 [협업 안내](docs/COLLABORATION_KR.md)에 있습니다. 저장소를 연결했다고 특정 Work 환경에 로컬 Git 인증이나 파일 작업공간이 자동 생성되는 것은 아닙니다. 작업 환경이 제공하는 GitHub 기능 또는 인증된 Git 체크아웃을 사용합니다.

## 주요 파일

| 용도 | 위치 |
|---|---|
| 공통 개발 지침 | `AGENTS.md` |
| 현재 상태와 미완료 항목 | `PROJECT_STATUS_KR.md` |
| 개발 환경 안내 | `DEVELOPMENT_SETUP_KR.md` |
| 협업 절차 및 시작문 | `docs/COLLABORATION_KR.md` |
| 과거 대화 인수인계 | `이어가기_인수인계.md` |
| 운영 소스/회귀 테스트 | `project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/` |
| 실습 사이트 | `project/burp-development-workspace/community-security-lab/` |
| 기존 진단 및 개선 후보 | `audit/0.21.0/` |
| 최신 배포 원본/가이드 | `release/` |
| 이전 검증 기록 | `history/`, `setup/`, `reproduction/` |

## 자동 검증

GitHub Actions는 push 및 PR에서 `python dev.py check`를 실행하고 검증된 JAR와 로그를 아티팩트로 제공합니다. `ubuntu-24.04`에서 JDK 17 / Python 3.12를 사용합니다. 실제 Burp GUI, Docker, Oracle, Pro Collaborator 검증은 이 CI 범위에 포함되지 않습니다. 과거 검증 기록과 현재 CI 결과를 구분합니다.

실습용 가짜 토큰/계정이 소스에 포함되어 있습니다. 프로토콜 실행 과정에서 생성하는 개인키와 인증서는 Git 추적에서 제외합니다. 실습 사이트는 로컬 격리 환경에서 사용합니다. 기존 GPL 라이선스와 제3자 고지는 확장 소스 폴더에 유지합니다.
