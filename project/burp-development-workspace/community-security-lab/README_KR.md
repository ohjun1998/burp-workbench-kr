# 사이트 3.5.2 — Windows 시작 스크립트와 로컬 실습

3.5.2는 한글이 있는 start.ps1을 Windows PowerShell 5.1에서 읽을 수 있도록 UTF-8 BOM을 포함합니다. 3.5.1에서 고친 Docker 네트워크 구성을 유지합니다. 웹·프로토콜 서비스는 접속용 bridge를 사용하고 DB·WAS는 내부 전용망에 둡니다. 공개 포트는 모두 호스트의 127.0.0.1로 제한하며, Nginx의 `/health`가 준비된 뒤 시작 완료로 판정합니다. 기존 3.5.0·3.5.1 ZIP은 보존합니다.

실제 실행 결과는 [릴리스별 검증 기록](https://github.com/ohjun1998/burp-workbench-kr/blob/main/docs/VERIFICATION_0.25.4.json)에서 확인하세요. ZIP은 검증 실행 전에 생성하므로 `START_HERE_KR.md`의 미실행 문구는 생성 시점의 기록입니다.

`/bounty-lab`에서 계정·조직별 정책, 저장/조회, 업로드/다운로드, 증거 정리와 초기화를 한 흐름으로 연습할 수 있습니다. [3.5 안내](BOUNTY_LAB_3.5_KR.md)를 먼저 확인하세요. 기존 검증 숫자는 각 버전의 실행 기록이며 새 버전의 검증으로 합산하지 않습니다.

현재 `docker compose up -d --build --wait --wait-timeout 900`은 `Dockerfile.source`를 사용해 현재 소스를 Maven으로 빌드합니다. 과거 `dist/ROOT.war`를 복사하지 않습니다. 첫 실행에는 Maven 의존성과 이미지 다운로드를 위한 인터넷 연결이 필요합니다. 기본 `Dockerfile`은 과거 WAR 재현용이며 현재 버전 실행은 Compose 또는 `docker build -f Dockerfile.source`를 사용합니다.

# LOOP 커뮤니티 보안 진단 실습 3.4 · 전 기능 검증 센터

/diagnostics에 33개 사례와 16개 프로필을 추가했습니다. 최신 설명은 DIAGNOSTICS_3.4_KR.md를 확인하세요. 이번 검증은 임베디드 Spring/Tomcat/H2, API/DOM 어댑터를 사용합니다.

## 3.3 이하 실행 안내와 과거 기록

아래 Burp/브라우저/버전 기록은 이전 패키지 기록이며 이번 3.4 검증과 구분해서 읽으세요.

# LOOP 커뮤니티 보안 진단 실습 3.3 · JS 추출 실습 추가

Spring MVC·Tomcat·H2로 실행되는 한국어 커뮤니티 실습 사이트입니다. 게시판, 회원 전용·비공개 글, 댓글, 첨부파일, 회원 쪽지, 운영자 기능을 갖췄고 각 기능에서 취약 구현과 수정 구현의 HTTP 동작을 비교할 수 있습니다. Burp Suite의 HTTP proxy, Repeater, Logger++, Param Miner, Autorize 등으로 로컬 요청을 점검하도록 가상 데이터와 API를 준비했습니다.

이 패키지는 의도적으로 취약한 학습 경로를 포함합니다. 인터넷이나 사내망에 공개하지 말고 개인 PC의 로컬 환경에서만 실행하세요. 로그인 암호와 이메일, 첨부파일은 실습용 가짜 데이터입니다.

## JS 추출 실습 업데이트

`http://127.0.0.1:8088/js-lab`에서 14개 유형의 구문 샘플, 전체/단일 수집, 기준표, 요청/응답과 소스맵을 확인합니다. 커뮤니티 상단 메뉴에 링크를 추가했습니다. 자세한 사용법과 현재 엔진의 분석 한계는 `JS_SYNTAX_LAB_KR.md`를 확인하세요.

이번 검사: 실제 Workbench KR 0.15.0 분석 엔진 62개, 컨트롤러 직접 호출 11개, UI DOM 동작 19개 통과. 이번 업데이트의 전체 WAS/Docker 기동 및 Burp GUI 검증은 수행하지 않았습니다. 아래 기존 확인 기록은 이전 버전 기록입니다.

## 실행

Docker Desktop과 Compose v2가 설치된 환경에서 압축을 풀고 실행합니다.

```bash
docker compose up -d --build --wait --wait-timeout 900
```

- 커뮤니티: http://127.0.0.1:8088/
- 확장 실습 센터: http://127.0.0.1:8088/extensions
- API 문서: http://127.0.0.1:8088/v3/api-docs
- TLS/HTTP2 프로토콜 실습: https://127.0.0.1:8443/tls
- CSD 취약·수정 fixture: http://127.0.0.1:8091/csd, http://127.0.0.1:8092/csd

모든 포트는 호스트의 127.0.0.1에만 연결됩니다. Burp에서 위 로컬 주소만 Scope에 넣고 Intercept를 끈 뒤 사이트를 사용하세요. TLS 실습에는 로컬 생성 인증서가 쓰입니다.

| 계정 | 비밀번호 | 권한 |
|---|---|---|
| alice | `LabOnly!2026` | 일반 회원 |
| bob | `LabOnly!2026` | 일반 회원 |
| moderator | `LabOnly!2026` | 보안 게시판 운영자 |
| admin | `LabOnly!2026` | 전체 관리자 |

사이트 상단의 계정 선택 메뉴에서 계정을 전환합니다. `학습: 취약 구현`과 `학습: 수정 구현`을 바꿔 같은 요청의 결과를 비교하세요. 수정 모드의 쓰기 요청은 로그인 응답에서 받은 CSRF 토큰을 브라우저가 자동으로 보냅니다.

## 실습 경로

기능별 API는 `/api/community/vuln/...`과 `/api/community/fixed/...`로 나뉩니다.

| 진단 대상 | 취약 동작 | 수정 동작 |
|---|---|---|
| 비공개 게시글 | 다른 회원이 게시글 번호를 바꾸면 읽을 수 있음 | 작성자 또는 전체 관리자만 읽음 |
| 회원 전용 글 | 비회원 읽기 허용 | 로그인 회원만 읽음 |
| 게시글 수정 | 타 회원 글 수정 요청 허용 | 소유자·관리자 정책 확인 |
| 댓글 | 저장한 HTML이 취약 화면에서 실행됨 | HTML 문자로 표시 |
| 첨부파일 | 타 회원 비공개 첨부 다운로드 허용 | 게시글 열람 권한 확인 |
| 쪽지 | 타 회원의 비공개 쪽지 열람 허용 | 발신자와 수신자만 열람 |
| 관리자 목록 | 요청의 `role=ADMIN` 값을 신뢰 | 실제 세션 역할을 확인 |
| 검색 | SQL 문자열 조합으로 입력 주입 가능 | 파라미터 바인딩과 접근 범위 적용 |
| 숨은 파라미터 | `lab_hidden` 입력이 취약 응답에 반영됨 | 입력은 응답에 노출되지 않음 |
| 파일 업로드 | 전달한 MIME 유형·파일명을 신뢰 | 크기·확장자·파일명 제한 적용 |

검증용 예시: alice 세션으로 Bob의 글 `/api/community/fixed/posts/102`를 요청하면 404, Bob 세션이면 200입니다. 같은 주소의 `vuln` 경로에서는 alice도 글을 읽을 수 있습니다. Param Miner 실습은 게시글 목록에 `lab_hidden` 파라미터를 찾아 보도록 구성했습니다. 외부 사이트나 실제 계정을 대상으로 하지 마세요.

`/extensions` 페이지는 각 확장이 다룰 합성 요청·응답, JWT, 반복 요청, 변환 데이터, JS fixture와 프로토콜 대상의 사용 순서를 설명합니다. 이는 Burp의 모든 기능과 원본 확장의 모든 옵션을 지원한다는 뜻은 아닙니다. Scanner·Collaborator·Pro 전용 JS Miner 기능도 이 실습 사이트가 대신하지 않습니다.

## 확인 기록

- 기존 취약점 회귀: 54개 통과. 확장 실습 API·프로토콜 fixture: 46개 통과.
- 커뮤니티 API: 49개 통과(Spring MVC·Tomcat·H2 임베디드 실행).
- 커뮤니티 브라우저: 8개 통과(로그인, 취약·수정 댓글 표시, 계정 전환, 모바일·데스크톱 레이아웃).
- 전체 Docker/Oracle/Nginx 스택은 이 실행 환경에서 기동하지 않았습니다.
- 실제 Burp Suite Community Edition 2026.9.1에서 지원 엔진 8개를 로드하고 커뮤니티 통합 9개 및 native Param Miner 탐지를 통과했습니다. Pro 전용 JS Miner, Scanner/Collaborator 및 Smuggler desync 탐지는 이번 범위에서 제외했습니다. 세부 기록은 `VERIFICATION_KR.md`를 참고하세요.

문제 재현 후 관리자 메뉴에서 커뮤니티 데이터만 초기화할 수 있습니다. 전체 컨테이너 종료는 `docker compose down`입니다. DB 볼륨까지 지우려면 `docker compose down -v`를 사용하세요.
