# 공유 대화의 Docker 실행 여부 확인

확인일: 2026-10-11. 출처: https://chatgpt.com/s/cx_6acade346b848191b65a494004bfdbc9

공유 대화 「Burp Suite 확장 개발 준비」의 공개 본문 32개 턴을 확인했습니다. Docker 설정 파일은 프로젝트에 포함되어 있지만, 이 대화에서 Docker 전체 스택을 실행하거나 Docker 실행 권한을 확보했다는 근거는 없습니다.

| 기록 | 실제 검증 방식 | Docker 상태 |
|---|---|---|
| 0.17.4 진단 패키징 | loopback Spring/Tomcat/H2 서버와 확장 API 어댑터 | Docker/Oracle 배포 검증 아님을 명시 |
| 0.18.0 배포 문서 | 임베디드 서버, 실제 HTTP, headless Swing | 전체 Docker/Oracle/Nginx 미실행 |
| 0.19.0 검증 JSON/README | 설치된 실제 Burp의 격리 프로필과 Java 서버 | 전체 Docker 스택 미검증 |
| 0.20.0 배포 검증 | 실제 Burp 네이티브 API, 프록시 History, Java loopback 서버 | 전체 Docker/Oracle/Nginx 실행하지 않음 |

0.20.0 패키징 코드 `work/package020.py`에는 `Full Docker/Oracle/Nginx and TLS/HTTP2 not exercised`가 한계로 기록되어 있습니다. 이전 대화의 “테스트에 필요한 로컬 네트워크 권한도 확보했습니다”는 실제 Burp HTTP API의 loopback 통신 문맥이며, Docker 데몬 권한을 확보했다는 뜻이 아닙니다. 공유 본문에는 셸 실행 로그가 없으므로 본문에 없는 실행 여부까지 단정하지는 않습니다.

현재 환경에서는 별도의 Xvfb 화면에서 실제 Burp GUI 12개 화면과 네이티브 API 검사 180개를 완료했습니다. Docker 실행기와 소켓이 없으며 권한 및 rootless 네임스페이스 생성도 제한되어 있습니다. 이전 대화에서 가져와 재사용할 Docker 권한 확보 절차는 확인되지 않았습니다. 실제 Docker 검증을 완료하려면 Docker 데몬에 접근 가능한 별도 실행 환경이 연결되어야 합니다.
