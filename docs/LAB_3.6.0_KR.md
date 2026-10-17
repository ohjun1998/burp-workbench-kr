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

계정 권한 비교의 실행 방법과 세션 적용은 [0.37.0 안내](RELEASE_0.37.0_KR.md)를 참고하세요. 자동 판정만으로 확정하지 말고 응답 본문·소유자·상태 변경도 확인합니다.

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

`python tools/check_role_portals.py`는 현재 Spring MVC/Tomcat 소스를 컴파일하고 격리 H2 Oracle 모드에서 실제 HTTP와 별도 쿠키 저장소로 역할·소유자·CSRF·상태 변경·로그아웃을 확인합니다. 기존 `python dev.py release`의 전체 회귀·HTTP 실습과 배포 지문 검사를 별도로 수행합니다. 실행한 결과는 [3.6.0 검증 기록](VERIFICATION_LAB_3.6.0.json)과 PR Checks에서 확인합니다. 실제 Burp GUI·브라우저 DOM·전체 Docker/Oracle/Nginx 기동은 이 자동 HTTP 검사와 구분합니다.
