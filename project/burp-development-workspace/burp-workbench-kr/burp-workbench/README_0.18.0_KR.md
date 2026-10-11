# Burp Workbench KR 0.18.0 사용 안내

커뮤니티 테스트 사이트 3.4.0과 함께 검증한 누적 수정본입니다. Java 17 이상에서 사용합니다. Burp Extensions에서 기존 Workbench를 해제하고 burp-workbench-kr-0.18.0.jar를 Java 확장으로 추가합니다.

## SCOPE와 창모드

트래픽 행을 우클릭해 도메인 전체 또는 URL 경로를 SCOPE에 등록합니다. SCOPE 목록과 설정은 현재 탭을 유지한 채 별도 창으로 열립니다. 등록 목록은 확장 내부 범위입니다. JS 추출·환경 탐색·URL 수집·활동 추적의 URL 입력은 대상 설정 창에서 관리합니다.

## 권한 비교

Alice/Bob/비로그인 인증을 실제 트래픽에서 지정합니다. 비교 설정 창에서 계정 소유값과 소유 JSON 경로를 설정하세요. 진단 센터 객체의 소유 경로는 $.ownerId, Alice는 1, Bob은 2입니다. 기본 접근 정책이나 경로별 규칙을 지정합니다.

~~~text
GET /api/diagnostics/vuln/objects/101 PUBLIC
GET /api/diagnostics/vuln/objects/103 PUBLIC
GET /api/diagnostics/vuln/objects/{id} OWNER_ONLY
GET /api/diagnostics/fixed/objects/101 PUBLIC
GET /api/diagnostics/fixed/objects/103 PUBLIC
GET /api/diagnostics/fixed/objects/{id} OWNER_ONLY
~~~

정확한 경로가 변수 규칙보다 우선합니다. PUBLIC은 공개/공유 허용 관측, OWNER_ONLY는 타인 객체 접근 후보로 표시합니다. 정책 미설정 상태의 소유값 불일치는 인가 위반으로 단정하지 않습니다. HTTP/설정한 JSON 차단 규칙은 PUBLIC보다 우선합니다.

대상이 404/410인 GET/HEAD 요청은 원래 계정으로 한 번 재조회합니다. 기준과 같은 2xx 본문이면 은닉형 접근 차단 후보로 표시합니다. 실패·본문 변동은 차단/미발견 후보로 남습니다. 쓰기는 존재 확인 목적으로 추가 재전송하지 않습니다. 기존 결과는 당시 설정 스냅샷을 유지합니다.

## 응답 분석

새 응답 분석 탭은 SCOPE 내 수신 응답을 관찰합니다. Passive 관찰을 위해 추가 요청을 보내지 않습니다. HTML 헤더, 세션 쿠키 속성, 구현 오류 문자열, 설정 키 노출을 검토 후보로 표시합니다. HTTPS의 HSTS/Secure는 HTTPS 응답에서만 확인합니다. 쿠키·시크릿 값은 근거에 저장하지 않습니다.

API 문서는 캡처한 OpenAPI JSON의 메서드, 경로 변수, query/header/body 이름, 내부 ref를 표시합니다. 변수를 실제 값으로 지정하면 Repeater 초안을 준비합니다. 다른 Origin·미해결 변수·인증 값은 자동 추가하지 않습니다. multipart는 실제 요청을 사용하세요. POST 전용 명세를 GET으로 자동 검증하지 않습니다.

저장/조회 마커에서 WB_STORE_DEMO01 같은 고유 문자열을 등록하세요. 진단 센터에서 &lt;b&gt;WB_STORE_DEMO01&lt;/b&gt;를 저장한 뒤 같은 Origin에서 조회하면 흐름이 연결됩니다. HTML 출력 관측과 HTML 문자 표시 후보를 구분하며 스크립트 실행을 검증하거나 저장형 XSS를 확정하지 않습니다. 초기화/확장 해제 시 흐름을 제거합니다.

## 활동 추적

SCOPE를 지정하고 일반 탐색으로 요청을 수집합니다. Repeater에서 같은 메서드/경로의 파라미터를 변경하면 변경 관찰로 표시합니다. JS·HTML·OpenAPI 발견 근거는 발견 상태에 연결되며 실제 요청과 진단 완료는 별도 상태입니다. 변경 관찰은 공격 성공·점검 완료가 아닙니다. 완료는 사용자가 검토 후 지정합니다. 저장/가져오기는 URL·파라미터 이름·상태 위주이며 인증 값은 보존하지 않습니다.

## 나머지 기능

JS는 async/await, 정적 module import/export, 동적 import, 단순 API 래퍼를 보조 분석하며 실행하지 않습니다. 동적 경로는 미해결로 남기고 AST 실패 문자열 조각은 자동 발견에서 제외합니다. 최신 JavaScript 전체 문법·복잡한 데이터 흐름을 지원하는 것은 아닙니다.

환경 비교는 경로의 HTML entity 표현도 마스킹합니다. requestId/timestamp는 사용자가 비교 제외에 지정해야 합니다. 실제 업무 데이터 변화는 불안정 기준으로 유지합니다.

URL 대표는 동일 패턴의 응답 상태를 구분합니다. 상태별 설정 수만큼 선택하되 패턴당 최대 5건입니다. 전체 원본은 보존되며 대표 표시가 모든 URL 점검을 의미하지 않습니다. DB 저장은 기본으로 쿼리 값을 제외합니다.

데이터 도구는 UTF-8 변환·JWT UTC 해석·구조 비교를 제공합니다. JWT 서명은 검증하지 않습니다.

페이로드 검사는 실제 요청에 §PAYLOAD§ 표시를 하나 지정합니다. 예산·대기시간·인코딩을 확인하세요. 401/403/429/503과 타임아웃은 후속 전송을 중단합니다. SQL 참/거짓은 기준 재검증과 원래 본문 동일성도 비교합니다. 결과는 후보이며 Repeater에서 재검토합니다.

## 테스트 사이트와 재실행

소스 ZIP의 community-security-lab에는 갱신된 dist/ROOT.war와 기존 Docker 실행 설정이 있습니다. /diagnostics에 33개 사례와 16개 프로필 템플릿이 있습니다. 계정은 alice/bob/moderator/admin, 암호는 LabOnly!2026 (실습용)입니다. 쓰기는 선택한 버튼으로 실행합니다.

전체 소스를 같은 루트에 압축 해제하고 JDK 17 이상과 Python 3.10 이상으로 실행합니다.

~~~bash
cd burp-workbench-kr/burp-workbench
python build_all.py --test --java java --javac javac
python validation/all-features/run.py --java java --javac javac
~~~

두 번째 명령은 임시 loopback 실습 서버를 띄우고 종료합니다. 필요한 Tomcat 런타임은 공식 Maven 저장소에서 해시 확인 후 받을 수 있습니다. 결과는 validation/all-features/.run/results.json과 tests.txt에 저장됩니다. 재현 Java/API 어댑터와 DOM 검사 코드가 소스에 포함됩니다.

총 1078개 통과, 실패 0개: 확장 회귀 855, 전 기능 연계 100, JS 구문 62, 커뮤니티 API 49, 진단 센터 DOM 어댑터 12입니다. 전 기능 연계는 실제 HTTP 199회를 사용했습니다. 검사군 합계이며 독립 취약점 개수가 아닙니다.

실제 Burp GUI/Proxy·브라우저 XSS 실행·전체 Docker/Oracle/Nginx·TLS/HTTP2는 이번 검증에서 실행하지 않았습니다. 임베디드 Spring/Tomcat/H2와 API 어댑터·headless Swing을 사용했습니다. 별도 클라우드 VM을 띄운 것이 아닙니다. PNG는 창 없이 렌더링한 실제 확장 컴포넌트입니다.
