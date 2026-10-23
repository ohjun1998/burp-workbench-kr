# Workbench Next / 기존 버전 다운로드

파일을 열고 **Download raw file**을 누르세요. Next를 처음 사용한다면 아래 **0.52.0 JAR**와 **Next 설치·기능별 상세 안내**부터 확인하세요. 이전 PDF는 과거 화면의 보존 자료입니다. 개발하거나 로컬 사이트에서 실습할 때만 소스·Docker ZIP을 추가로 받으면 됩니다.

## 새 버전: Workbench Next 0.52.0

0.50.0 개선안을 반영한 별도 브랜치 배포입니다. 기존 0.49.1·0.50.0 파일과 `main`은 보존합니다.

| 종류 | 다운로드 |
|---|---|
| Next 0.52.0 설치 JAR | [burp-workbench-kr-0.52.0.jar](extensions/0.52.0/burp-workbench-kr-0.52.0.jar) |
| Next 0.52.0 빌드 가능한 소스 | [burp-workbench-kr-0.52.0-source.zip](extensions/0.52.0/burp-workbench-kr-0.52.0-source.zip) |
| 설치·기능별 상세 가이드 | [0.52.0 단계별 안내](../docs/RELEASE_0.52.0_NEXT_KR.md) |
| 현재 검증·제한사항 | [VERIFICATION_0.52.0.json](../docs/VERIFICATION_0.52.0.json) |
| 선택 사항: Feature Mapper 0.3.4 | [브라우저 도우미·독립 배포](feature-mapper/0.3.4/) |

Burp → **Extensions → Add → Java**에서 JAR를 선택합니다. 기존 Workbench/Next의 로드를 먼저 해제하고 파일은 보관하세요. 0.52.0은 Next 0.50.0과 같은 설정·기록 계열을 사용하며, 0.49.1과는 분리됩니다. 0.50.0으로 되돌릴 계획이라면 업그레이드 전에 Next 설정과 기록 폴더를 백업하세요. 새 JS 인덱스는 별도 `v2` 디렉터리를 사용하며 이전 원본 파일을 변환·삭제하지 않습니다.

주요 추가 기능은 권한 설정 묶음, 복수 쓰기 조회 규칙, Mapper 업무 연결, 실행·증거 추적, JS 기록 페이지·보존 설정입니다. 이번 배포의 검증 범위는 자동 회귀·격리 HTTP·Swing 화면 검사입니다. 앞선 실제 Burp 실행은 이전 JAR의 라이선스 확인 단계에서 중단됐으므로 0.52.0의 실구동 검증으로 포함하지 않습니다.

## 보존: Workbench Next 0.50.0

기존 버전과 분리한 새 작업 브랜치 배포입니다. 기존 JAR·소스 파일은 수정하지 않습니다.

| 종류 | 다운로드 |
|---|---|
| Next 0.50.0 설치 JAR | [burp-workbench-kr-0.50.0.jar](extensions/0.50.0/burp-workbench-kr-0.50.0.jar) |
| Next 0.50.0 빌드 가능한 소스 | [burp-workbench-kr-0.50.0-source.zip](extensions/0.50.0/burp-workbench-kr-0.50.0-source.zip) |
| 설치·기능별 상세 안내 | [Next 변경 안내](../docs/RELEASE_0.50.0_NEXT_KR.md) |
| 현재 검증 기록 | [VERIFICATION_0.50.0.json](../docs/VERIFICATION_0.50.0.json) |

설치 후 확장 목록과 상단 탭에서 **Workbench Next**를 확인하세요. 기존 JAR는 지우지 말고 이전 확장의 로드만 해제합니다. Next는 별도 저장 설정으로 시작하므로 SCOPE와 계정을 다시 지정합니다. 두 버전 동시 로드 동작은 별도 검증하지 않았습니다.

## 보존한 기존 배포

0.49.1은 컴파일·패키징한 배포입니다. 0.49.1을 배포할 당시에는 사용자 요청에 따라 해당 버전의 회귀·HTTP·GUI·원격 CI 검사를 실행하지 않았습니다. 각 Next 버전의 검사 결과는 해당 버전 검증 기록에 따로 제공합니다.

| 종류 | 버전 | 다운로드 |
|---|---|---|
| 기존 확장 · 계정 비교 전 설정·판정 한계 안내 | 0.49.1 | [JAR](extensions/0.49.1/burp-workbench-kr-0.49.1.jar) · [소스](extensions/0.49.1/burp-workbench-kr-0.49.1-source.zip) |
| 커뮤니티 실습 Docker 실행 ZIP | 3.10.0 | [community-security-lab-3.10.0-docker.zip](docker/3.10.0/community-security-lab-3.10.0-docker.zip) |
| 기능 매핑 브라우저 도우미 · 내장 Mapper의 선택 연동 | 0.3.3 | [Chrome/Edge ZIP](feature-mapper/0.3.3/feature-mapper-browser-0.3.3.zip) · [독립 호환 배포](feature-mapper/0.3.3/) |
| 읽기 쉬운 사용 안내서 | 0.25.4용 · 26쪽 | [Burp_Workbench_KR_0.25.4_guide.pdf](guides/0.25.4-readable/Burp_Workbench_KR_0.25.4_guide.pdf) |

[0.49.1 비교 전 설정 안내](../docs/RELEASE_0.49.1_KR.md) · [0.49.0 전체 고도화·재검증](../docs/RELEASE_0.49.0_KR.md) · [0.48.0 미탐 보강·재검증](../docs/RELEASE_0.48.0_KR.md) · [0.47.0 기능 자동 매핑 통합](../docs/RELEASE_0.47.0_KR.md) · [0.25.0 기능 안내](../docs/RELEASE_0.25.0_KR.md) · [단계별 모의 진단](../docs/TEST_WORKFLOW_KR.md) · [실제 검증 범위](../docs/VERIFICATION_0.45.0.json)

확장: Burp → Extensions → Add → Java에서 JAR를 선택합니다. 이전 Workbench는 먼저 해제하세요. 새 화면 이름과 첫 사용 순서는 [저장소 첫 안내](../README.md#설치하고-첫-요청-확인하기)를 참고합니다.

Docker: Docker Desktop/Engine과 Compose v2가 필요합니다. ZIP을 한 폴더에 풀고 Windows는 `start.ps1`, Linux/macOS는 `sh start.sh`를 실행합니다. 첫 실행에는 이미지와 Maven 의존성을 다운로드하며 Oracle 준비에 시간이 걸립니다. 로그인 주소는 http://127.0.0.1:8088/login 입니다. 일반 사용자는 게시글 목록, 관리자는 같은 커뮤니티의 사용자 관리 화면으로 연결됩니다. [인증 방식별 로그인·권한 검증·실행 방법](../docs/LAB_3.10.0_KR.md)을 참고하세요. 동시 계정 비교는 서로 다른 브라우저 프로필을 사용합니다. [현재 버전의 실제 검증 범위](../docs/VERIFICATION_LAB_3.10.0.json)를 확인하세요.

소스 ZIP: 확장 폴더의 `python build_all.py --test`로 확장 회귀 검사를 실행합니다. `dev.py check`의 전체 사이트 HTTP·모의 진단에는 저장소 전체 소스와 실습 런타임이 필요하므로 저장소 체크아웃을 사용하세요.

각 버전 폴더의 `SHA256SUMS`로 무결성을 확인합니다. 공개 저장소에서 다운로드할 수 있습니다.

[초급자용 고도화 재검증 보고서](../docs/reports/2026-10-18-auth50-upgrade/Auth50_Upgrade_Report_0.44.0_KR.pdf) · [판정 사유 CSV](../docs/verification/auth50-0.44.0/account-results.csv)

## 이전 버전

| 종류 | 버전 | 파일 |
|---|---|---|
| 이전 확장 · 전체 고도화 | 0.49.0 | [JAR](extensions/0.49.0/burp-workbench-kr-0.49.0.jar) · [소스](extensions/0.49.0/burp-workbench-kr-0.49.0-source.zip) |
| 이전 확장 · XSS·JSON 미탐 보강 | 0.48.0 | [JAR](extensions/0.48.0/burp-workbench-kr-0.48.0.jar) · [소스](extensions/0.48.0/burp-workbench-kr-0.48.0-source.zip) |
| 이전 Feature Mapper | 0.3.2 | [다운로드](feature-mapper/0.3.2/) |
| 이전 확장 · Feature Mapper 통합·11개 도구 | 0.47.0 | [JAR](extensions/0.47.0/burp-workbench-kr-0.47.0.jar) · [소스](extensions/0.47.0/burp-workbench-kr-0.47.0-source.zip) |
| 이전 확장 · Vuln Bank 판정 고도화 | 0.46.1 | [JAR](extensions/0.46.1/burp-workbench-kr-0.46.1.jar) · [소스](extensions/0.46.1/burp-workbench-kr-0.46.1-source.zip) |
| 이전 확장 · 요청·응답 목록·JS 분석 | 0.43.0 | [JAR](extensions/0.43.0/burp-workbench-kr-0.43.0.jar) · [소스](extensions/0.43.0/burp-workbench-kr-0.43.0-source.zip) |
| 이전 확장 · 세션·권한 판정 | 0.41.0 | [JAR](extensions/0.41.0/burp-workbench-kr-0.41.0.jar) · [소스](extensions/0.41.0/burp-workbench-kr-0.41.0-source.zip) |
| 이전 확장 · 종합 결과 | 0.39.2 | [JAR](extensions/0.39.2/burp-workbench-kr-0.39.2.jar) · [소스](extensions/0.39.2/burp-workbench-kr-0.39.2-source.zip) |
| 이전 확장 | 0.37.4 | [JAR](extensions/0.37.4/burp-workbench-kr-0.37.4.jar) · [소스](extensions/0.37.4/burp-workbench-kr-0.37.4-source.zip) |
| 이전 커뮤니티 Docker | 3.9.0 | [Docker ZIP](docker/3.9.0/community-security-lab-3.9.0-docker.zip) |
| 이전 커뮤니티 Docker | 3.8.0 | [Docker ZIP](docker/3.8.0/community-security-lab-3.8.0-docker.zip) |
| 이전 커뮤니티 Docker | 3.7.1 | [Docker ZIP](docker/3.7.1/community-security-lab-3.7.1-docker.zip) |
| 이전 커뮤니티 Docker | 3.7.0 | [Docker ZIP](docker/3.7.0/community-security-lab-3.7.0-docker.zip) |
| 이전 커뮤니티 Docker | 3.6.0 | [Docker ZIP](docker/3.6.0/community-security-lab-3.6.0-docker.zip) |
| 이전 커뮤니티 Docker | 3.5.2 | [Docker ZIP](docker/3.5.2/community-security-lab-3.5.2-docker.zip) |
| 이전 확장 · 메서드 자동 선택·기본 창 버튼 | 0.37.3 | [JAR](extensions/0.37.3/burp-workbench-kr-0.37.3.jar) · [소스](extensions/0.37.3/burp-workbench-kr-0.37.3-source.zip) |
| 이전 확장 · Repeater 전송 확인 | 0.37.2 | [JAR](extensions/0.37.2/burp-workbench-kr-0.37.2.jar) · [소스](extensions/0.37.2/burp-workbench-kr-0.37.2-source.zip) |
| 이전 확장 · JS 분석 가독성 | 0.37.1 | [JAR](extensions/0.37.1/burp-workbench-kr-0.37.1.jar) · [소스](extensions/0.37.1/burp-workbench-kr-0.37.1-source.zip) |
| 이전 확장 · 쿠키·세션 검사 | 0.37.0 | [JAR](extensions/0.37.0/burp-workbench-kr-0.37.0.jar) · [소스](extensions/0.37.0/burp-workbench-kr-0.37.0-source.zip) |
| 이전 확장 · JS 분석 고도화 | 0.36.0 | [JAR](extensions/0.36.0/burp-workbench-kr-0.36.0.jar) · [소스](extensions/0.36.0/burp-workbench-kr-0.36.0-source.zip) |
| 이전 확장 · 두 줄 버튼·빨간 집계 | 0.35.1 | [JAR](extensions/0.35.1/burp-workbench-kr-0.35.1.jar) · [소스](extensions/0.35.1/burp-workbench-kr-0.35.1-source.zip) |
| 이전 확장 · JS 작업 영역·Repeater | 0.33.0 | [JAR](extensions/0.33.0/burp-workbench-kr-0.33.0.jar) · [소스](extensions/0.33.0/burp-workbench-kr-0.33.0-source.zip) |
| 이전 Feature Mapper · 화면·로그 | 0.3.0 | [다운로드](feature-mapper/0.3.0/) |
| 이전 확장 · Pretty·Raw·Hex·Search | 0.31.2 | [JAR](extensions/0.31.2/burp-workbench-kr-0.31.2.jar) · [소스](extensions/0.31.2/burp-workbench-kr-0.31.2-source.zip) |
| 이전 확장 · SCOPE 편집·조밀한 목록·네이티브 패킷 | 0.30.0 | [JAR](extensions/0.30.0/burp-workbench-kr-0.30.0.jar) · [소스](extensions/0.30.0/burp-workbench-kr-0.30.0-source.zip) |
| 이전 확장 · 화면 정리·로그 | 0.29.0 | [JAR](extensions/0.29.0/burp-workbench-kr-0.29.0.jar) · [소스](extensions/0.29.0/burp-workbench-kr-0.29.0-source.zip) |
| 이전 확장 · OFF/SCOPE 네이티브 수집 | 0.28.1 | [JAR](extensions/0.28.1/burp-workbench-kr-0.28.1.jar) · [소스](extensions/0.28.1/burp-workbench-kr-0.28.1-source.zip) |
| 이전 Feature Mapper | 0.2.0 | [다운로드](feature-mapper/0.2.0/) |
| 이전 확장 · 전체 화면 디자인 | 0.28.0 | [JAR](extensions/0.28.0/burp-workbench-kr-0.28.0.jar) · [소스](extensions/0.28.0/burp-workbench-kr-0.28.0-source.zip) |
| 이전 확장 · 폴더 아이콘·선택 강조 | 0.27.1 | [JAR](extensions/0.27.1/burp-workbench-kr-0.27.1.jar) · [소스](extensions/0.27.1/burp-workbench-kr-0.27.1-source.zip) |
| 이전 확장 · 왼쪽 폴더 메뉴 | 0.27.0 | [JAR](extensions/0.27.0/burp-workbench-kr-0.27.0.jar) · [소스](extensions/0.27.0/burp-workbench-kr-0.27.0-source.zip) |
| 이전 확장 · Windows 동시 I/O 보완 | 0.26.1 | [JAR](extensions/0.26.1/burp-workbench-kr-0.26.1.jar) · [소스](extensions/0.26.1/burp-workbench-kr-0.26.1-source.zip) |
| Windows 동시 I/O 보완 전 | 0.26.0 | [JAR](extensions/0.26.0/burp-workbench-kr-0.26.0.jar) · [소스](extensions/0.26.0/burp-workbench-kr-0.26.0-source.zip) |
| 이전 확장 | 0.25.5 | [JAR](extensions/0.25.5/burp-workbench-kr-0.25.5.jar) · [소스](extensions/0.25.5/burp-workbench-kr-0.25.5-source.zip) |
| 이전 확장 | 0.25.4 | [JAR](extensions/0.25.4/burp-workbench-kr-0.25.4.jar) · [소스](extensions/0.25.4/burp-workbench-kr-0.25.4-source.zip) |
| 이전 확장 | 0.25.3 | [JAR](extensions/0.25.3/burp-workbench-kr-0.25.3.jar) · [소스](extensions/0.25.3/burp-workbench-kr-0.25.3-source.zip) |
| 이전 확장 | 0.25.2 | [JAR](extensions/0.25.2/burp-workbench-kr-0.25.2.jar) · [소스](extensions/0.25.2/burp-workbench-kr-0.25.2-source.zip) |
| 이전 확장 | 0.25.1 | [JAR](extensions/0.25.1/burp-workbench-kr-0.25.1.jar) · [소스](extensions/0.25.1/burp-workbench-kr-0.25.1-source.zip) |
| 이전 확장·가이드 | 0.25.0 | [JAR](extensions/0.25.0/burp-workbench-kr-0.25.0.jar) · [소스](extensions/0.25.0/burp-workbench-kr-0.25.0-source.zip) · [PDF](guides/0.25.0-easy-ko/Burp_Workbench_KR_0.25.0_easy_guide.pdf) |
| 이전 확장·가이드 | 0.24.0 | [JAR](extensions/0.24.0/burp-workbench-kr-0.24.0.jar) · [소스](extensions/0.24.0/burp-workbench-kr-0.24.0-source.zip) · [PDF](guides/0.24.0/Burp_Workbench_KR_0.24.0_guide.pdf) |
| 이전 확장·가이드 | 0.23.0 | [JAR](extensions/0.23.0/burp-workbench-kr-0.23.0.jar) · [소스](extensions/0.23.0/burp-workbench-kr-0.23.0-source.zip) · [PDF](guides/0.23.0/Burp_Workbench_KR_0.23.0_guide.pdf) |
| 이전 확장 | 0.22.1 | [JAR](extensions/0.22.1/burp-workbench-kr-0.22.1.jar) · [소스](extensions/0.22.1/burp-workbench-kr-0.22.1-source.zip) |
| 초기 테스트 배포 · CI 대기 문제 수정 전 | 0.22.0 | [JAR](extensions/0.22.0/burp-workbench-kr-0.22.0.jar) · [소스](extensions/0.22.0/burp-workbench-kr-0.22.0-source.zip) |
| 과거 배포 원본 | 0.21.0 | [JAR](extensions/0.21.0/burp-workbench-kr-0.21.0.jar) |
| 이전 Docker 실행 ZIP | 3.5.1 | [Docker ZIP](docker/3.5.1/community-security-lab-3.5.1-docker.zip) |
| 이전 Docker 실행 ZIP | 3.5.0 | [Docker ZIP](docker/3.5.0/community-security-lab-3.5.0-docker.zip) |
| 이전 Docker 실행 ZIP | 3.4.0 | [Docker ZIP](docker/3.4.0/community-security-lab-3.4.0-docker.zip) |

개발자는 `python dev.py release`로 검증 후 새 버전 폴더를 생성하고 소스와 함께 PR에 포함합니다. 기존 버전은 덮어쓰지 않습니다. 확장과 실습 사이트는 버전을 따로 관리합니다. PDF도 기존 파일을 보존하고 새 문서 개정판 폴더에 추가합니다. 이번 문서 개정판은 `python tools/build_readable_guide.py`로 생성합니다.

기능 자동 매핑은 Workbench 0.47.0 JAR에 포함됩니다. [내장 기능과 브라우저 도우미 사용법](../feature-mapper/README_KR.md)을 확인하세요.


이전 0.44.0의 계정 판정·쓰기 보류 사유 개선은 [0.44.0 안내](../docs/RELEASE_0.44.0_KR.md)를 확인하세요. JS·JSON·XML·텍스트 자동 분석, 분석 목록·패킷 팝업과 Ctrl+R 사용법은 [0.43.0 안내](../docs/RELEASE_0.43.0_KR.md)를 먼저 읽으세요. 0.25.4 PDF는 이전 상단 탭 화면을 기준으로 한 도구 사용 참고 자료입니다.


[인증 조합·계정 권한 비교 PDF 보고서](../docs/reports/2026-10-18/Auth_Comparison_Report_20261018.pdf) · [가린 요청·응답 근거 ZIP](../docs/reports/2026-10-18/Auth_Comparison_Evidence_20261018.zip)

[50개 기능 권한 검증 PDF](../docs/reports/2026-10-18-auth50/Auth50_Report_20261018.pdf) · [전체 가림 근거 ZIP](../docs/reports/2026-10-18-auth50/Auth50_Evidence_20261018.zip)
