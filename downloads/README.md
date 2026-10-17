# 테스트 파일 다운로드

파일을 열고 **Download raw file**을 누르세요. 처음 사용한다면 최신 확장 JAR와 표에 안내된 PDF부터 받으세요. 개발하거나 로컬 사이트에서 실습할 때만 소스·Docker ZIP을 추가로 받으면 됩니다.

## 최신 테스트 배포

| 종류 | 버전 | 다운로드 |
|---|---|---|
| 최신 확장 · 요청·응답 목록과 JS 분석 고도화 | 0.43.0 | [JAR](extensions/0.43.0/burp-workbench-kr-0.43.0.jar) · [소스](extensions/0.43.0/burp-workbench-kr-0.43.0-source.zip) |
| 커뮤니티 실습 Docker 실행 ZIP | 3.8.0 | [community-security-lab-3.8.0-docker.zip](docker/3.8.0/community-security-lab-3.8.0-docker.zip) |
| Feature Mapper JAR·브라우저 도우미·소스 | 0.3.1 | [다운로드](feature-mapper/0.3.1/) |
| 읽기 쉬운 사용 안내서 | 0.25.4용 · 26쪽 | [Burp_Workbench_KR_0.25.4_guide.pdf](guides/0.25.4-readable/Burp_Workbench_KR_0.25.4_guide.pdf) |

[0.43.0 요청·응답 목록·JS 분석 개선](../docs/RELEASE_0.43.0_KR.md) · [0.25.0 기능 안내](../docs/RELEASE_0.25.0_KR.md) · [단계별 모의 진단](../docs/TEST_WORKFLOW_KR.md) · [실제 검증 범위](../docs/VERIFICATION_0.43.0.json)

확장: Burp → Extensions → Add → Java에서 JAR를 선택합니다. 이전 Workbench는 먼저 해제하세요. 새 화면 이름과 첫 사용 순서는 [저장소 첫 안내](../README.md#설치하고-첫-요청-확인하기)를 참고합니다.

Docker: Docker Desktop/Engine과 Compose v2가 필요합니다. ZIP을 한 폴더에 풀고 Windows는 `start.ps1`, Linux/macOS는 `sh start.sh`를 실행합니다. 첫 실행에는 이미지와 Maven 의존성을 다운로드하며 Oracle 준비에 시간이 걸립니다. 로그인 주소는 http://127.0.0.1:8088/login 입니다. 일반 사용자는 게시글 목록, 관리자는 같은 커뮤니티의 사용자 관리 화면으로 연결됩니다. [공통 권한·새 화면·실행 방법](../docs/LAB_3.8.0_KR.md)을 참고하세요. 동시 계정 비교는 서로 다른 브라우저 프로필을 사용합니다. [현재 버전의 실제 검증 범위](../docs/VERIFICATION_LAB_3.8.0.json)를 확인하세요.

소스 ZIP: 확장 폴더의 `python build_all.py --test`로 확장 회귀 검사를 실행합니다. `dev.py check`의 전체 사이트 HTTP·모의 진단에는 저장소 전체 소스와 실습 런타임이 필요하므로 저장소 체크아웃을 사용하세요.

각 버전 폴더의 `SHA256SUMS`로 무결성을 확인합니다. 공개 저장소에서 다운로드할 수 있습니다.

## 이전 버전

| 종류 | 버전 | 파일 |
|---|---|---|
| 이전 확장 · 세션·권한 판정 | 0.41.0 | [JAR](extensions/0.41.0/burp-workbench-kr-0.41.0.jar) · [소스](extensions/0.41.0/burp-workbench-kr-0.41.0-source.zip) |
| 이전 확장 · 종합 결과 | 0.39.2 | [JAR](extensions/0.39.2/burp-workbench-kr-0.39.2.jar) · [소스](extensions/0.39.2/burp-workbench-kr-0.39.2-source.zip) |
| 이전 확장 | 0.37.4 | [JAR](extensions/0.37.4/burp-workbench-kr-0.37.4.jar) · [소스](extensions/0.37.4/burp-workbench-kr-0.37.4-source.zip) |
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

별도 기능 매핑 확장: [Feature Mapper KR 설치와 다운로드](../feature-mapper/README_KR.md). Workbench JAR에 포함되지 않습니다.


최신 확장은 0.42.0입니다. JS·JSON·XML·텍스트 자동 분석, 분석 목록·패킷 팝업과 Ctrl+R 사용법은 [0.42.0 안내](../docs/RELEASE_0.42.0_KR.md)를 먼저 읽으세요. 0.25.4 PDF는 이전 상단 탭 화면을 기준으로 한 도구 사용 참고 자료입니다.

