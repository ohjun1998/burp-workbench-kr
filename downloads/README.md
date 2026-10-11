# 테스트 파일 다운로드

GitHub에 로그인한 상태에서 아래 파일을 열고 **Download raw file**을 누르세요. ZIP은 다운로드한 뒤 한 폴더에 압축을 풉니다.

| 종류 | 버전 | 파일 |
|---|---|---|
| Burp 확장 · 최신 테스트 | 0.22.0 | [burp-workbench-kr-0.22.0.jar](extensions/0.22.0/burp-workbench-kr-0.22.0.jar) |
| 확장 소스·빌드 의존성 | 0.22.0 | [burp-workbench-kr-0.22.0-source.zip](extensions/0.22.0/burp-workbench-kr-0.22.0-source.zip) |
| 이전 배포 원본 | 0.21.0 | [burp-workbench-kr-0.21.0.jar](extensions/0.21.0/burp-workbench-kr-0.21.0.jar) |
| Docker 실행 패키지 | 실습 사이트 3.4.0 | [community-security-lab-3.4.0-docker.zip](docker/3.4.0/community-security-lab-3.4.0-docker.zip) |

확장: Burp → Extensions → Add → Java에서 JAR를 선택합니다. 이전 Workbench는 먼저 해제하세요.

Docker: Docker Desktop/Engine과 Compose v2가 필요합니다. ZIP을 풀고 Windows는 `start.ps1`, Linux/macOS는 `sh start.sh`를 실행합니다. 첫 실행에는 이미지와 Maven 의존성을 다운로드하며 Oracle 준비에 시간이 걸립니다. 주소는 http://127.0.0.1:8088 입니다. 실제 전체 Docker 기동은 이번 환경에서 미검증입니다.

소스 ZIP: 확장 폴더에서 `python build_all.py --test`로 확장 회귀 검사를 실행할 수 있습니다. `dev.py check`의 전체 사이트 HTTP 검사에는 저장소 전체 소스와 실습 런타임도 필요하므로 저장소 체크아웃을 사용하세요.

각 버전 폴더의 `SHA256SUMS`로 파일 무결성을 확인할 수 있습니다. 최신 테스트 파일도 실제 Burp/대상 환경 검증을 대신하지 않습니다. [변경 내역과 사용법](../docs/RELEASE_0.22.0_KR.md)을 확인하세요.

개발자는 `python dev.py release`로 새 버전 폴더를 생성하고 소스와 함께 PR에 포함합니다. 기존 버전은 덮어쓰지 않습니다. 확장과 실습 사이트는 버전을 따로 관리하므로 확장만 변경되면 기존 Docker ZIP을 그대로 사용할 수 있습니다.
