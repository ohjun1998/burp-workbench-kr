# 추가 검증 및 남은 단계

2026-10-11, Workbench KR 0.21.0 / 커뮤니티 실습 사이트 3.4.0.

## 이번에 완료한 검증

- 실제 Chromium 155.0.8059.39에서 브라우저 검사 23개 통과. 로그인·세션 복원·계정 전환, 취약 댓글의 저장형 XSS 실행, 수정 댓글의 문자 표시와 실행 방지, 검증 센터의 쓰기/조회 및 안전한 응답 미리보기, 14개 JS 구문 fixture 수집을 확인했습니다.
- 프로젝트 프로토콜 fixture에 실제 TLS 연결·인증서 확인·ALPN HTTP/1.1 및 HTTP/2 협상·H2 스트림 수신, 취약/수정 CSD, 일회용 CSRF의 교차 세션 거부·정상 수용·재사용 거부를 검사했습니다. 11개 통과.
- 실제 Burp Montoya API로 HTTPS 요청을 HTTP/1.1 및 HTTP/2로 각각 전송해 상태·본문·프로토콜 버전을 확인했습니다. 6개 통과. fixture의 실제 연결 기록도 별도로 보관합니다.
- 공식 Docker Compose v2.39.4로 두 Compose 파일을 함께 파싱했습니다. 포트의 loopback 바인딩, 내부 네트워크, 서비스 건강 상태 의존성, Oracle 접속 설정과 배포 WAR 등을 포함한 14개 사전 검사 통과. 컨테이너 기동이나 Oracle DB 통신을 검증한 결과는 아닙니다.

## 발견하여 수정한 문제

390px 모바일 화면에서 커뮤니티 페이지의 검색 입력란 때문에 문서 폭이 414px로 늘어났습니다. 입력란의 최소 폭을 해제하고 남는 폭 안에서 줄어들도록 CSS를 수정했습니다. 소스와 Docker 빌드 입력인 `dist/ROOT.war`에 동일한 CSS를 반영했고, 실제 브라우저에서 문서 폭 390px 및 가로 넘침 해소를 재확인했습니다. 초기 실패 JSON·로그와 요소 측정 결과는 `additional/attempt1-*`, `additional/mobile-layout-before.json`에 보존합니다. 확장의 운영 Java 소스 및 JAR는 변경하지 않았습니다.

## 재실행

`validation-local/README_KR.md`의 준비 절차와 추가 명령을 참고하세요. 외부 사이트 대신 사용자 소유의 loopback 실습 서버만 사용하며, 테스트 프로세스·프로필·데이터를 분리합니다. 추가 검증 결과는 `setup/additional/`에 있습니다.

## 아직 실행하지 못한 항목

1. **Docker 전체 스택**: Docker 데몬/소켓 접근과 컨테이너 실행 권한이 없습니다. DigitalOcean 원격 작업공간 플러그인은 설치·연결되지 않았습니다. 데몬에 접근 가능한 환경이 연결되어야 Nginx·Tomcat·Oracle Free·프로토콜 컨테이너를 실행할 수 있습니다. Oracle HTTP 회귀 54개 및 전체 스택 건강 상태 검사는 그 환경에서 실행해야 합니다.
2. **실제 Pro Collaborator 서비스**: 현재 검증은 Community Edition입니다. 기존 모의 서비스 제어 흐름과 실제 loopback SSRF 콜백을 유료 서비스 확인으로 해석하지 않습니다.
3. **외부 BApp 설치·실행**: 현재 Workbench 기능 검증과 별개로 남아 있습니다.

원격 환경이 연결되면 별도 Compose 프로젝트 이름을 사용하고 8088·8443·8091·8092 포트 충돌을 먼저 확인합니다. 기존 스택을 변경하거나 DB 볼륨을 삭제하지 않습니다. 예를 들어 실습 폴더에서 다음과 같이 실행합니다.

```bash
COMPOSE_PROJECT_NAME=wbkr-verify021 bash verify-stack.sh
```

검증 후 로그를 먼저 보관하고 같은 프로젝트 이름으로 `docker compose down`을 실행합니다. Docker 성공 여부는 실제 `stack-verification.json`을 기준으로 판정합니다.
