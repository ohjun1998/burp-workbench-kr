# 커뮤니티 3.6.0 — 관리자·사용자 포털과 계정 권한 비교

기존 커뮤니티에 역할별 로그인·업무 화면을 추가했습니다. 같은 로컬 Origin 아래에서 경로를 구분하므로 Workbench 계정 권한 비교의 같은 Origin 검사를 그대로 사용할 수 있습니다. 기존 커뮤니티·JS·진단·버그바운티 실습은 유지합니다. 확장은 0.37.0, 사이트만 3.6.0입니다.

## 실행과 로그인

새 Docker ZIP을 별도 폴더에 풀고 `start.ps1` 또는 `sh start.sh`를 실행합니다. Compose 사용자는 `docker compose up -d --build --wait --wait-timeout 900`을 실행합니다. 이전 실습 스택이 같은 포트를 쓰면 먼저 해당 폴더에서 `docker compose down`으로 종료하세요. 기존 볼륨 삭제는 필요하지 않습니다. 새 테이블은 시작할 때 만들어지며 기존 커뮤니티 데이터는 유지됩니다.

| 구분 | 주소 | 계정 | 비밀번호 |
|---|---|---|---|
| 관리자 | http://127.0.0.1:8088/admin | admin | `LabOnly!2026` |
| 일반 사용자 A | http://127.0.0.1:8088/user | alice | `LabOnly!2026` |
| 일반 사용자 B | http://127.0.0.1:8088/user | bob | `LabOnly!2026` |

로그인하지 않고 센터에 접근하면 해당 `/admin/login`, `/user/login`으로 이동합니다. 로그인 후에도 서버가 페이지 역할을 검사하므로 사용자 세션의 관리자 페이지 접근은 403입니다. 관리자도 사용자 전용 페이지에는 접근하지 않습니다. moderator는 이번 두 전용 센터의 계정에 포함하지 않으며 기존 커뮤니티에서 사용합니다.

각 포털은 동일한 JSESSIONID 세션을 사용합니다. 한 브라우저 프로필에서 다른 계정으로 로그인하면 기존 세션이 무효화됩니다. **동시 비교에는 브라우저 프로필·브라우저를 각각 사용해 admin·alice·bob 세션을 따로 유지하세요.** 동일 프로필에서 순서대로 수집한 옛 세션은 로그인 재시도 후 유효하지 않을 수 있습니다. 세션 검사를 통해 확인하세요.

## 화면과 기능

| 관리자 센터 | 사용자 센터 |
|---|---|
| 요청 검토: 전체 요청의 신청자·사유 확인, 승인·반려 | 내 요청: 본인 요청과 처리 상태만 조회 |
| 회원 관리: 전체 회원 ID·역할·이메일 조회 | 새 요청: 제목·사유 작성, 본인 계정으로 등록 |
| 서비스 설정: 사용자 화면 공지 변경 | 요청 상세: 본인 신청 내용 확인, 대기 요청 취소 |
| 실습 초기화: 요청 501·502·503 및 공지 복원 | 내 프로필: 본인 ID·역할·이메일 확인 |

상단 메뉴와 색상으로 두 영역을 구분합니다. 왼쪽에서 기능과 **수정 구현 / 취약 구현**을 선택하고, 화면 아래의 최근 요청·응답에서 실제 메서드·URL·상태를 읽을 수 있습니다. 기본은 수정 구현입니다. 업무 요청은 버튼을 눌렀을 때 전송하며, 승인·취소된 요청은 재처리 시 409입니다.

두 구현은 동일한 실습 데이터를 사용합니다. 취약 모드에서 승인·취소·공지 변경을 수행하면 수정 모드에도 결과가 보입니다. 다시 비교하기 전에 관리자의 서비스 설정에서 **실습 데이터 초기화**를 누르세요. 이 초기화는 기존 커뮤니티 게시글·회원 계정을 삭제하지 않습니다.

## Workbench 비교 순서

1. `http://127.0.0.1:8088/`를 Burp Scope에 추가하고 Workbench 수집을 SCOPE로 바꿉니다. `/admin` 경로만 Scope에 넣으면 `/api/access/` 요청은 포함되지 않습니다.
2. 서로 다른 브라우저 프로필에서 admin·alice·bob으로 로그인합니다. 각 로그인 후 `/api/access/me` 요청을 수집하고 인증 후보를 비교 슬롯 1·2·3에 지정합니다.
3. **계정 권한 비교 → 세션 검사**에서 확인 URL을 `http://127.0.0.1:8088/api/access/me`, JSON 경로를 **`$.user.USERNAME`**, 기대 값을 해당 슬롯의 **admin / alice / bob**으로 설정합니다. JSON 키의 대소문자를 맞춥니다. 비로그인은 401, 로그인은 자기 신원을 반환합니다.
4. 관리자 센터의 회원 관리·요청 검토를 열어 GET 요청을 수집합니다. 이를 사용자·비로그인 조건과 비교합니다. 화면 이동의 302 로그인 문서보다 아래 실제 데이터 API를 기준으로 비교하세요.
5. Alice의 요청 상세 501과 Bob의 상세 502를 수집해 사용자 간 수평 권한을 비교합니다. 수정/취약 경로를 각각 수집해 같은 역할 기대 정책으로 실행합니다.
6. 쓰기 요청은 읽기 검사를 마친 뒤 별도로 확인합니다. 수정 모드에는 **해당 계정의 X-CSRF-Token**이 필요합니다. 쿠키만 바꾸고 이전 계정의 CSRF 토큰을 남기면 403이 CSRF 오류일 수 있으므로 권한 차단으로 단정하지 마세요. `/api/access/me`의 `csrf`가 현재 계정 토큰입니다. 폼 본문은 UTF-8로 전송합니다.

계정 권한 비교의 실행 방법과 세션 적용은 [0.37.0 안내](https://github.com/ohjun1998/burp-workbench-kr/blob/main/docs/RELEASE_0.37.0_KR.md)를 참고하세요. 자동 판정만으로 확정하지 말고 응답 본문·소유자·상태 변경도 확인합니다.

## 비교 기준표

`{mode}`는 `fixed` 또는 `vuln`입니다. 아래 HTTP 코드는 최초 실습 상태의 **GET** 기준입니다.

| API | admin | alice | bob | 비로그인 |
|---|---:|---:|---:|---:|
| `/api/access/fixed/admin/members` | 200 | 403 | 403 | 401 |
| `/api/access/fixed/admin/review-queue` | 200 | 403 | 403 | 401 |
| `/api/access/fixed/admin/settings` | 200 | 403 | 403 | 401 |
| `/api/access/fixed/user/requests/501` | 403 | 200 | 404 | 401 |
| `/api/access/fixed/user/requests/502` | 403 | 404 | 200 | 401 |
| `/api/access/vuln/admin/members` | 200 | 200 | 200 | 401 |
| `/api/access/vuln/user/requests/501` | 200 | 200 | 200 | 401 |
| `/api/access/vuln/user/requests/502` | 200 | 200 | 200 | 401 |

취약 구현은 로그인만 요구하고 역할·소유자·CSRF 검사를 생략하는 의도적인 실습 경로입니다. 역할을 쿼리나 헤더로 바꿔도 수정 API는 실제 세션과 DB 역할을 확인합니다. 취약 대시보드 목록도 본인 요청만 표시하지만, 타인 상세 URL 직접 접근과 취소 API는 허용되어 목록 필터와 객체 권한 검사의 차이를 확인할 수 있습니다.

| 쓰기 API | 수정 구현 정책 |
|---|---|
| `POST /api/access/{mode}/user/requests` | USER, 제목·내용 유효성, 계정 CSRF |
| `POST /api/access/{mode}/user/requests/{id}/cancel` | USER, 본인 소유, PENDING, 계정 CSRF |
| `POST /api/access/{mode}/admin/requests/{id}/review` | ADMIN, PENDING, APPROVED/REJECTED, 계정 CSRF |
| `POST /api/access/{mode}/admin/settings` | ADMIN, 공지 1~500자, 계정 CSRF |
| `POST /api/access/reset` | 항상 ADMIN과 CSRF 요구, 요청·공지 초기화 |

## 검증 범위

`python tools/check_role_portals.py`는 현재 Spring MVC/Tomcat 소스를 컴파일하고 격리 H2 Oracle 모드에서 실제 HTTP와 별도 쿠키 저장소로 역할·소유자·CSRF·상태 변경·로그아웃을 확인합니다. 기존 `python dev.py release`의 전체 회귀·HTTP 실습과 배포 지문 검사를 별도로 수행합니다. 실행한 결과는 [3.6.0 검증 기록](https://github.com/ohjun1998/burp-workbench-kr/blob/main/docs/VERIFICATION_LAB_3.6.0.json)과 PR Checks에서 확인합니다. 실제 Burp GUI·브라우저 DOM·전체 Docker/Oracle/Nginx 기동은 이 자동 HTTP 검사와 구분합니다.

---

이하 3.5.2 이전 버전 기록은 당시 실행 범위와 사용법으로 보존합니다.

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
