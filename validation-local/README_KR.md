# 실제 Burp 화면 / 네이티브 검증

이번 하네스는 실제 Burp 프로세스와 가상 X 화면을 실행하고 배포 확장의 화면을 선택·캡처합니다. 헤드리스 Swing 모의 검사와 구분합니다. 결과는 `setup/gui/`에 선별 보관합니다.

## 이번 구성

- Burp Suite Community 2026.7.3-52685, 공식 Desktop JAR. SHA-256: `c8262dc5426f38bedc490d66c5d21b6ff77d6dc6d85cefe6a66c882690134069`.
- Java 21.0.3, Xvfb 1600×1000. Burp가 이 JRE 조합은 충분히 검증하지 않았다는 경고를 표시했으며, 기록에 유지합니다.
- 사용자 프로필·홈·임시 데이터는 `validation-local/results/` 내부로 격리합니다. 기존 사용자 Burp/브라우저/프록시 설정을 변경하지 않습니다.
- 전용 실습 HTTP 18299, Burp 프록시 18288, 테스트 콜백 19112, 가상 화면 6098을 사용합니다. 실행 전 포트 사용 여부를 확인합니다.
- `GuiLauncher.java`: 실제 화면 트리와 현재 화면 기록. `results/click.txt`에 실제 표시된 버튼의 정확한 이름을 입력하면 그 버튼을 누릅니다.
- `GuiAuditObserver.java`: 배포 JAR의 실제 Workbench 화면을 찾고 기능 탭 및 계정/업무 화면을 선택해 캡처한 뒤 네이티브 하네스를 실행합니다.
- 네이티브 하네스와 관찰자는 별도 클래스 경로로 컴파일해 클래스 로더 충돌을 방지합니다. 첫 실행의 로더 오류는 `setup/gui/attempt1-burp.log`에 보존합니다.

## 다시 실행하기 (Linux)

먼저 `python dev.py check`를 실행해 실습 서버 클래스와 런타임 캐시를 준비합니다. 별도 runtime 폴더에 공식 `burp.jar`, Xvfb와 Java 21 GUI 런타임을 준비한 뒤 실행합니다.

```text
python validation-local/run_gui.py --runtime /absolute/path/to/gui-runtime
```

런처는 이번 환경에서 사용한 Ubuntu 패키지 압축 해제 구조(`root/usr/bin/Xvfb`, `root/usr/lib/jvm/java-21-openjdk-amd64/bin/java`)를 기준으로 합니다. XKB 키맵, xkbcomp 및 GUI 라이브러리도 필요합니다. 공식 Burp JAR/Java/X 서버 자체는 프로젝트 ZIP에 포함하지 않았습니다.

시작 시 JRE 경고, 라이선스 약관, Community 선택, 임시 프로젝트 시작 화면을 확인합니다. 이미 확인한 버튼을 `click.txt`에 지정하는 방식으로 진행하며, 유료 라이선스 구매·로그인은 수행하지 않습니다. 새 사용 환경에서는 약관과 시작 화면을 다시 확인하세요.

실습 서버는 로컬 H2 기반입니다. 실제 Docker의 Oracle·Nginx·Tomcat 스택 검증은 포함하지 않습니다. Pro Collaborator와 외부 BApp 검증도 별도입니다. 후속 브라우저·TLS/HTTP2 검증은 아래 명령과 `setup/추가검증_및_남은단계_KR.md`를 참고하세요.

## Docker 상태

Docker 실행기/데몬/소켓이 없고, 컨테이너 생성 권한과 rootless 사용자 네임스페이스 생성도 제한되어 실제 컨테이너를 시작하지 못했습니다. 상세 확인은 `setup/gui/docker-environment.json`을 기준으로 읽습니다. 발견한 DigitalOcean 원격 작업공간 연결은 설치·연결되지 않았으며 원격 서버를 생성하지 않았습니다.

Docker 실행 권한이 있는 환경이 연결되면 `project/burp-development-workspace/community-security-lab/verify-stack.sh` 또는 `verify-stack.ps1`로 전체 스택 검증을 진행할 수 있습니다. 기존 스택과 구분하는 Compose 프로젝트 이름과 포트를 먼저 구성해야 합니다. 현재 미실행 상태를 성공으로 기록하지 않습니다.

## 실제 브라우저 및 TLS/HTTP2 추가 검증

Playwright의 Node 패키지, Chrome Headless Shell, 프로젝트 `protocols/requirements.txt`의 Python 의존성을 준비합니다. `NODE_PATH`와 `CODEX_PRIMARY_RUNTIME_NODE_MODULES`에 Playwright 패키지가 있는 경로를 지정하거나 일반 Node 모듈 탐색 경로에서 사용할 수 있게 준비하세요.

```text
python validation-local/run_additional.py --runtime /absolute/path/to/gui-runtime --chrome /absolute/path/to/chrome-headless-shell --protocol-pythonpath /absolute/path/to/python-protocol-site --gui
```

실제 브라우저 23개, 프로토콜 fixture 11개, 실제 Burp API 6개를 검사합니다. 임시 HTTP 18599, TLS 18443, CSD 18091/18092, 가상 화면 6096을 사용합니다. 출력·프로필·데이터는 `validation-local/additional-results/`로 격리합니다. 기존 `run_gui.py`와 마찬가지로 표시된 시작 버튼을 `additional-results/click.txt`에 정확한 이름으로 지정합니다. `--native-only`는 앞서 완료한 브라우저·Python 검사를 반복하지 않고 Burp 전송만 재실행합니다.

헤드리스 Burp에서도 네트워크 검사는 통과했지만 Burp UI 초기화 오류가 발생했습니다. 최종 확인은 `--gui --native-only`로 실제 가상 화면을 열어 다시 수행했으며 미처리 예외가 없었습니다. JRE 조합에 대한 Burp 경고와 SLF4J 로거 안내는 남습니다.

Docker 데몬 없이도 설정과 빌드 입력을 검사할 수 있습니다.

```text
python validation-local/docker-preflight.py --output setup/additional/docker-preflight.json
```

독립 Compose 실행 파일을 사용하는 경우 `--compose /absolute/path/to/docker-compose`를 추가합니다. 이 검사는 컨테이너를 시작하지 않습니다.
