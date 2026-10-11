# Burp 확장별 실습 순서 · LOOP Community Lab 3.0

Workbench KR → 원본 엔진에서 사용할 확장을 활성화합니다. 브라우저를 Burp Proxy에 연결하고 http://127.0.0.1:8088/extensions 에서 계정을 선택해 로그인합니다. 기준 요청 수집 버튼은 실제 브라우저 HTTP 요청을 발생시킵니다. 일반 경로는 메인 Spring 서버에서 처리하며 프로토콜 서비스도 동일 패키지에 포함됩니다.

| 확장 | 대상 | 조작과 기대 결과 |
|---|---|---|
| Autorize | GET /api/vuln/orders/102 및 /api/fixed/orders/102 | bob의 정상 주문 요청을 수집하고 alice 세션으로 재전송. 취약 200, 수정 404. 비로그인 401. 관리자 목록은 admin 원본과 일반 사용자 재전송을 비교 |
| Auth Analyzer | GET /api/extensions/csrf/form → POST /api/extensions/csrf/write | form 응답에서 name='csrf'의 value를 추출해 본문 csrf=값에 교체. 신규 200, 같은 토큰 재사용·다른 계정 토큰 403. 쓰기 전 매번 form 재요청 |
| Logger++ | GET /api/extensions/log?marker=LAB-001 | marker별 요청 생성 → 확장 로깅 → URL/메서드/상태 필터 → 내보내기 |
| Hackvertor | GET /api/extensions/convert?value=eyJsYWIiOiJPSyJ9 | value는 {"lab":"OK"}의 Base64. 디코딩·수정·재인코딩 태그로 테스트. 올바르면 decoded JSON, 잘못되면 400 |
| Param Miner | GET /api/extensions/hidden | 정상 baseline 확보 → /assets/param-miner-wordlist.txt의 3개 이름을 사용하는 작은 단어 목록으로 query/header/cookie 추측. lab_hidden, X-Lab-Debug, lab_debug가 존재할 때만 별도 응답 필드가 생김 |
| JWT Editor | GET /api/extensions/jwt/issue | 발급 응답의 trainingKey를 HS256 키로 등록. Repeater에서 Authorization: Bearer 토큰 설정 → role 변경·서명 수정 → 아래 관리자 경로 비교 |
| Turbo Intruder | POST /api/extensions/vuln/counter / fixed/counter | 로그인 Cookie 유지, 20개 동시 작업으로 40개 요청. vuln은 읽기/쓰기 분리로 증가 유실 가능, fixed는 원자 증가. 30ms 지연은 유실 재현용이며 모든 실행에서 동일 유실 건수가 보장되지는 않음 |
| JS Miner (Pro) | /assets/extensions-fixtures.js 및 .js.map | 소스맵 탐색·원본 synthetic source 확인. 일부 합성 키가 제공자 규칙에 따라 후보로 표시될 수 있음. 유효한 외부 키가 아님 |
| HTTP Request Smuggler | :8091/csd, :8092/csd | 동일 CSD 검사를 두 서버에 적용. 취약은 Content-Length 본문을 소비하지 않고 다음 요청으로 해석. 수정은 본문 소비. 원본 엔진 출력의 탐지와 실제 증거를 비교 |

## JWT 비교

- 로그인 후 `/api/extensions/jwt/issue`를 호출합니다. `trainingKey`는 실습용으로 공개된 공유 서명 키이며 운영 비밀키가 아닙니다.
- `/api/extensions/vuln/jwt/admin`은 서명을 검사하지 않고 role=ADMIN을 신뢰합니다. 서명 없이 일반 사용자 역할을 바꾼 토큰도 200이 됩니다.
- `/api/extensions/fixed/jwt/admin`은 HS256 알고리즘, 서명, 만료와 관리자 계정 sub=3을 확인합니다. 잘못된 서명·만료·alg=none은 401, alice의 토큰을 올바르게 재서명해 role만 ADMIN으로 바꾼 경우에도 403입니다.
- admin이 정상 발급받은 토큰은 200입니다. 서명 키가 공개되어 있으므로 sub까지 3으로 바꾸고 올바르게 서명한 토큰은 이 훈련 모델에서 관리자 토큰으로 취급됩니다. 이 실습은 키 유출에 대한 방어를 제공하는 예제가 아닙니다.

## 반복 요청과 동시 요청

`POST /api/extensions/counter/reset`으로 두 카운터와 사용한 합성 쿠폰 목록을 초기화합니다. `GET /api/extensions/counter`로 최종 값을 비교합니다. 모두 전용 훈련 데이터입니다.

`POST /api/extensions/vuln/redeem`에 `key=demo`를 두 번 보내면 둘 다 200입니다. 수정 경로 `/api/extensions/fixed/redeem`은 사용자별 같은 키를 첫 요청에서 200, 다음 요청에서 409로 처리합니다. 반복 차이를 검토하는 작은 대상이며 실제 결제 기능은 없습니다.

TLS·HTTP2 전송 검사는 `https://127.0.0.1:8443/tls`에서 수행합니다. 이 경로는 합성 응답을 제공하는 프로토콜 서비스이며 인증된 Spring 카운터 API를 그대로 HTTPS로 노출한 경로가 아닙니다. 일반 게이트 동시 전송과 HTTP2 전송을 확인할 수 있지만 모든 Turbo Intruder 엔진/전송 전략을 보증하지 않습니다.

## Workbench 자체 기능

- 트래픽: 검증 센터의 버튼으로 요청 생성 → 수집/검색/Repeater 이동.
- 공통 세션·인가 비교: 기존 로그인에서 얻은 alice/bob Cookie 설정 → orders/102 취약·수정 비교.
- JS History: `/assets/extensions-fixtures.js`를 여러 번 수집 → 중복 처리 확인. `/assets/extensions-fixtures.js?v=2` 등으로 새 History 항목도 생성 가능.
- 변환: 위 Base64 JSON 예제와 기존 gzip/인코딩 도구 사용. 서버는 모든 Hackvertor 변환 태그의 처리기를 구현한 것이 아닙니다.
- JWT: 발급 토큰 디코딩. 원본 JWT Editor와 달리 자체 탭에서 서명 검증했다고 해석하지 마세요.
- 환경·경로: `/health`, JS와 Docker Nginx 응답 수집. 환경 서명이 가려지면 탐지되지 않을 수 있고 추천 경로가 실제 존재한다고 보장하지 않습니다.

## 범위와 실행 기록

원본 엔진 9개 각각을 사용해 볼 대상·절차를 한 패키지에 마련했습니다. 실제 제품의 모든 옵션, 프로토콜 변종, 공격 기법을 구현한 것은 아닙니다. JS Miner의 실행에는 Burp Pro가 필요하며 Scanner/Collaborator는 이 사이트가 대체하지 않습니다. 외부 DNS/HTTP 상호작용 서비스와 실제 유효한 시크릿 검증은 제공하지 않습니다.

검증 기록: 기존 HTTP 회귀 54개, 커뮤니티 API 49개, 브라우저 UI 8개, 확장 실습 API/프로토콜 46개, 실제 Burp 커뮤니티 연동 9개가 통과했습니다. 실제 Burp에서 원본 엔진 8개가 로드됐고 Pro 전용 JS Miner는 제외했습니다. TLS/HTTP2/CSD 프로토콜 검사는 프로젝트 내 fixture를 대상으로 별도 수행했습니다. 전체 Docker/Oracle/Nginx 스택은 이번 확인에서 실행하지 않았습니다.


## LOOP 커뮤니티에서 실제 요청 만들기

홈의 커뮤니티 기능은 회원 권한 검사와 비공개 리소스를 반복해 확인할 수 있도록 구성했습니다. Burp Proxy에서 사이트를 사용해 기록을 만들고, Repeater에서 로그인 계정의 `Cookie`를 바꿔 A/B 결과를 비교하세요.

| 확장/도구 | 커뮤니티 대상 | 점검 포인트 |
|---|---|---|
| Logger++ | `/api/community/{mode}/posts`, 댓글·첨부·쪽지 API | 요청/응답과 상태 코드 기록 |
| Param Miner | `/api/community/vuln/posts` | `lab_hidden` query 파라미터 발견, 수정 모드에서는 차이 없음 |
| Autorize / Auth Analyzer | Alice/Bob의 `Cookie`와 `/posts/102`, `/files/bob-private-file`, `/messages/202` | 취약 모드의 교차 계정 접근과 수정 모드의 404/403 |
| Hackvertor | 검색 입력, 댓글, URL 인코딩 값 | 인코딩·디코딩 후 서버 응답 비교 |
| JWT Editor | `/api/extensions/jwt/issue`, `/api/extensions/{mode}/jwt/admin` | 실습 JWT를 편집하고 응답 차이를 검토 |
| Turbo Intruder | `/api/extensions/{mode}/counter` | synthetic counter에서 동시 요청 차이 확인 |
| HTTP Request Smuggler | 별도 `protocols` CSD fixture (8091/8092) | 커뮤니티 앱 앞단이 아닌 전용 프로토콜 fixture를 대상으로 사용 |
| JS 분석 | `/assets/community.js`, `/assets/extensions-fixtures.js` | 앱이 실제 사용하는 API 경로와 fixture 분석 |

## 현재 검증 상태

커뮤니티 API 49개와 브라우저 UI 8개 검사가 통과했습니다. Burp Suite Community Edition 2026.9.1에서 원본 엔진 8개를 로드하고 커뮤니티 HTTP API 통합 검사 9개를 통과했으며 native Param Miner가 `lab_hidden`을 찾았습니다. JS Miner는 Pro 전용입니다. Smuggler는 probe 생성까지만 확인했고 desync 스캔은 별도 실행하지 않았습니다. 원본 결과는 `burp-community-test-results.json`과 `burp-community-engine-validation.txt`를 참고하세요.
