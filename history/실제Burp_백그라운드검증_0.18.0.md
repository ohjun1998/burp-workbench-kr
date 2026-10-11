# 실제 Burp 백그라운드 검증 · Workbench KR 0.18.0

검증 시각: 2026-10-10T21:17:34.423591+09:00 (한국 시간). 실제 설치된 **Burp Suite Community Edition 2026.7.3-52685**에 배포 JAR를 로드했습니다. 마우스·키보드 입력이나 기존 Burp 창 조작을 하지 않았습니다.

## 확인 결과

- Burp의 파일 확장 로더가 배포 JAR를 로드하고 loaded=true로 보고했습니다.
- 입력 설정의 임시 이름 Workbench loading check가 실제 초기화 후 Burp Workbench KR 0.18.0으로 변경됐습니다.
- Workbench와 Payload Runner 초기화 메시지를 모두 확인했습니다.
- 별도로 지정한 확장 오류 파일은 0바이트였습니다.
- 실제 Montoya HTTP API로 커뮤니티 실습 서버의 읽기 경로 7개를 요청했고 7개 모두 통과했습니다.
- 배포 JAR의 원래 SHA256을 유지했습니다: d7cdb646c04b184514affa29ebd2dc0c93aa63167d880292870ff57ceeffa8d7
- 테스트용 Burp 프로세스와 127.0.0.1:18299 실습 서버는 검증 후 종료했습니다.

| 실제 HTTP 경로 | 검사 | 결과 |
|---|---|---|
| /health | 200 응답 | 통과 |
| /api/diagnostics/boundary/utf8 | 배포 JAR가 한글 본문 디코딩 | 통과 |
| /api/diagnostics/boundary/gzip | 실제 압축 응답 바이트 처리 | 통과 |
| /api/diagnostics/boundary/deflate | 실제 압축 응답 바이트 처리 | 통과 |
| /api/diagnostics/boundary/cp949 | MS949 선언과 한글 처리 | 통과 |
| /api/diagnostics/boundary/empty | 빈 성공 본문 | 통과 |
| /api/diagnostics/boundary/redirect | 302 유지, 자동 이동 금지 | 통과 |

## 방법과 증거

Burp 자체의 네이티브 확장 목록 설정으로 outputs의 JAR를 읽게 했습니다. 검증 관찰용 확장은 버전·확장 목록을 실제 Burp API에서 내보내고 읽기 요청을 보냈습니다. 테스트의 HTTP API를 모킹하지 않았습니다. 응답 디코딩은 실제 Burp가 반환한 바이트에 배포 JAR의 Engine.parse를 호출했습니다. 이 순수 디코더 검사를 위해 관찰용 클래스 로더를 추가로 사용했으며, Workbench의 로드는 별도의 Burp 파일 확장 로더가 담당했습니다.

초기 확인 코드에서 Montoya bodyToString의 바이트 문자열 표현을 한글 디코딩 결과로 가정해 한 사례가 실패했습니다. 실제 확장은 HttpText/Engine 디코더를 사용하므로 이를 배포 JAR와 같은 방식으로 검사해 정상 결과를 확인했습니다. 확장 소스나 JAR를 변경할 문제는 발견하지 않았습니다.

사용자 홈·Burp data-dir·Java Preferences·임시 폴더를 작업 디렉터리 아래로 분리했습니다. Java Preferences는 테스트 전용 파일 저장 구현을 사용했습니다. Proxy listener 목록도 테스트 프로젝트에서는 비웠습니다. 실제 기존 프로젝트나 Windows 레지스트리의 사용자 Burp Preferences를 테스트에 사용하지 않았습니다.

증거 파일: 실제Burp_백그라운드검증_0.18.0.json, 실제Burp_확장출력_0.18.0.txt, 실제Burp_확장오류_0.18.0.txt, 실제Burp_백그라운드재현코드_0.18.0.zip.

재현 코드는 이번 작업 디렉터리와 Burp 설치 경로, work/lab-audit의 기존 사이트 빌드를 기준으로 합니다. 다른 환경에서 실행하려면 해당 경로와 준비한 빌드 경로를 수정해야 합니다. 증거 ZIP에는 제품 JAR나 라이선스 약관 원문을 포함하지 않았습니다.

## 범위와 남은 확인

이번 확인은 **실제 Burp의 확장 로드·초기화와 기본 HTTP/디코딩 연동**입니다. 기존 1,078개 회귀/연계 검사를 실제 Burp에서 모두 반복한 결과가 아닙니다. Payload Runner 초기화 성공을 모든 프로필의 실제 GUI 실행 성공으로 해석하지 않습니다.

Burp 자체는 headless 모드에서 no ComponentUI class 경고와 NullPointerException을 출력했습니다. 같은 종류의 메시지는 Workbench 없이 실행한 기준에서도 발생했고, Workbench 전용 오류 로그는 비어 있습니다. 이 경고를 숨기거나 GUI 검증 통과로 처리하지 않았습니다.

실제 우클릭 메뉴·창모드 팝업·브라우저를 통한 Proxy 흐름과 눈에 보이는 UI 동작은 여전히 미검증입니다. 이 작업에는 별도의 클라우드 VM을 사용하지 않았습니다.

실행 방식은 [PortSwigger의 명령줄 실행 문서](https://portswigger.net/burp/documentation/desktop/troubleshooting/launch-from-command-line)를 참고했습니다.
