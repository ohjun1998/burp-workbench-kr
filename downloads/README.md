# 테스트 파일 다운로드

GitHub에 로그인한 뒤 파일을 열고 **Download raw file**을 누르세요. 필요한 패키지만 내려받을 수 있습니다.

## 최신 테스트 배포

| 종류 | 버전 | 다운로드 |
|---|---|---|
| Burp 확장 JAR | 0.24.0 | [burp-workbench-kr-0.24.0.jar](extensions/0.24.0/burp-workbench-kr-0.24.0.jar) |
| 확장 소스·빌드 의존성 | 0.24.0 | [burp-workbench-kr-0.24.0-source.zip](extensions/0.24.0/burp-workbench-kr-0.24.0-source.zip) |
| 커뮤니티 실습 Docker 실행 ZIP | 3.5.0 | [community-security-lab-3.5.0-docker.zip](docker/3.5.0/community-security-lab-3.5.0-docker.zip) |
| 한국어 그림 가이드북 | 0.24.0 | [Burp_Workbench_KR_0.24.0_guide.pdf](guides/0.24.0/Burp_Workbench_KR_0.24.0_guide.pdf) |

[변경 안내](../docs/RELEASE_0.24.0_KR.md) · [단계별 모의 진단](../docs/TEST_WORKFLOW_KR.md) · [실제 검증 범위](../docs/VERIFICATION_0.24.0.json)

확장: Burp → Extensions → Add → Java에서 JAR를 선택합니다. 이전 Workbench는 먼저 해제하세요.

Docker: Docker Desktop/Engine과 Compose v2가 필요합니다. ZIP을 한 폴더에 풀고 Windows는 `start.ps1`, Linux/macOS는 `sh start.sh`를 실행합니다. 첫 실행에는 이미지와 Maven 의존성을 다운로드하며 Oracle 준비에 시간이 걸립니다. 주소는 http://127.0.0.1:8088 이며 새 실습은 `/bounty-lab`입니다. 전체 Docker 기동은 이번 환경에서 미검증입니다.

소스 ZIP: 확장 폴더의 `python build_all.py --test`로 확장 회귀 검사를 실행합니다. `dev.py check`의 전체 사이트 HTTP·모의 진단에는 저장소 전체 소스와 실습 런타임이 필요하므로 저장소 체크아웃을 사용하세요.

각 버전 폴더의 `SHA256SUMS`로 무결성을 확인합니다. 저장소가 비공개이므로 다운로드에도 저장소 접근 권한이 필요합니다.

## 이전 버전

| 종류 | 버전 | 파일 |
|---|---|---|
| 이전 확장·가이드 | 0.23.0 | [JAR](extensions/0.23.0/burp-workbench-kr-0.23.0.jar) · [소스](extensions/0.23.0/burp-workbench-kr-0.23.0-source.zip) · [PDF](guides/0.23.0/Burp_Workbench_KR_0.23.0_guide.pdf) |
| 이전 확장 | 0.22.1 | [JAR](extensions/0.22.1/burp-workbench-kr-0.22.1.jar) · [소스](extensions/0.22.1/burp-workbench-kr-0.22.1-source.zip) |
| 초기 테스트 배포 · CI 대기 문제 수정 전 | 0.22.0 | [JAR](extensions/0.22.0/burp-workbench-kr-0.22.0.jar) · [소스](extensions/0.22.0/burp-workbench-kr-0.22.0-source.zip) |
| 과거 배포 원본 | 0.21.0 | [JAR](extensions/0.21.0/burp-workbench-kr-0.21.0.jar) |
| 이전 Docker 실행 ZIP | 3.4.0 | [Docker ZIP](docker/3.4.0/community-security-lab-3.4.0-docker.zip) |

개발자는 `python dev.py release`로 검증 후 새 버전 폴더를 생성하고 소스와 함께 PR에 포함합니다. 기존 버전은 덮어쓰지 않습니다. 확장과 실습 사이트는 버전을 따로 관리합니다. PDF 재생성은 `tools/build_guide.py`를 사용하며, 이미 배포한 PDF를 수정할 때도 새 버전을 사용합니다.
