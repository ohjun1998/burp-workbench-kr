# Burp Workbench KR 0.19.0

Java 17 이상. Burp Extensions에서 이전 Workbench를 해제하고 dist/burp-workbench-kr-0.19.0.jar를 Java 확장으로 로드합니다. 기존 기능의 사용법은 README_0.18.0_KR.md, 이번 추가 기능은 아래 내용을 참고하세요. 이전 안내의 버전 및 검증 수치는 당시 배포 기록입니다.

## 변경 전/후

|항목|변경 전|0.19.0|
|---|---|---|
|계정 비교|캡처한 인증값으로 비교|계정별 명시적 GET 갱신, CSRF/토큰 JSON 추출, 신원 대조 및 실패 시 비교 중단|
|권한 기준|공개/소유자 중심|메서드·경로별 3계정 ALLOW/DENY/UNKNOWN 매트릭스 추가|
|숨겨진 입력·GraphQL·동시성|각 도구에서 별도 진행|점검 지원에서 도구 안내와 Repeater 준비, 발견 이름 TSV→활동 추적|
|업무 흐름|요청을 개별 재현|최대 8단계 순차 실행, 응답 JSON 변수 연결, 실패 시 후속 중단|
|OAST|원본 요청과 별도 관리|Pro Collaborator ID와 요청 연결 또는 외부 결과 TSV 수동 연결|
|증거|기록·패킷을 따로 정리|보고서·원본 비교 결과·활동 현황·이미지를 ZIP으로 저장|
|브라우저 검증|수동 확인 후 별도 보관|Burp 브라우저 검증 절차 안내와 증거 이미지 첨부|

## 계정 세션·권한 매트릭스

권한 비교에서 후보를 계정 1/2/3에 지정한 후 세션·권한 매트릭스를 엽니다. 갱신은 기본 꺼짐이며 계정마다 같은 Origin/SCOPE의 GET URL을 지정합니다. 토큰 JSON 경로·목적 헤더·접두사와 CSRF 경로·헤더를 설정합니다. 선택적인 신원 JSON 경로는 비교 설정의 소유값과 대조합니다. 예를 들어 실습 사이트 /api/auth/me는 $.csrf, $.user.ID를 사용합니다. 비로그인은 갱신할 수 없습니다.

갱신은 리다이렉트를 따르지 않으며 실패하면 해당 계정의 비교 요청을 보내지 않습니다. 기존 쿠키 이름만 해당 Domain/Path/Secure 조건으로 갱신합니다. 복잡한 POST 로그인·다단계 로그인은 Burp Settings > Sessions의 세션 규칙/매크로로 처리합니다. 세션 규칙이 실제 요청 인증을 바꾸면 비교 판정을 보류합니다. 계정별 갱신 값은 메모리에서만 유지됩니다.

역할·조직은 진단자가 입력하는 설명입니다. 서버의 조직 정책을 추론하거나 조직별 테스트 계정을 생성하지 않습니다. 기대 권한은 아래처럼 직접 정의합니다. 정확한 경로가 {id} 규칙보다 우선합니다.

```text
GET /api/community/fixed/posts/101 ALLOW ALLOW ALLOW
GET /api/community/fixed/posts/102 DENY ALLOW DENY
```

ALLOW/DENY 불일치는 확인할 후보입니다. 404/410, 빈 응답, 세션 오류는 검토가 필요합니다. 설정 확인 후 다음 비교부터 적용되며 과거 결과는 당시 설정을 유지합니다.

## 점검 지원

외부 도구는 Param Miner, InQL, Turbo Intruder를 별도 설치합니다. 공식 안내와 선택 요청→Repeater 기능을 제공하며 실행은 해당 도구의 메뉴에서 직접 수행합니다. 자동 설치·공통 호출·원문 결과 자동 파싱은 제공하지 않습니다. 발견한 이름은 실제 탭 문자로 구분한 아래 TSV로 정리합니다. 값은 가져오지 않으며 전체 Scope 검증 후 활동 추적의 발견 상태로 등록합니다.

```text
GET<TAB>http://127.0.0.1:8088/api/community/fixed/search<TAB>URL<TAB>debug
GET<TAB>http://127.0.0.1:8088/api/community/fixed/search<TAB>HEADER<TAB>X-Preview
```

업무 흐름에서는 선택 요청을 단계로 추가하고 `===STEP===` 한 줄로 구분합니다. 대상 설정 창에서 Origin, 쓰기 허용, 응답 대기, 총 요청 한도를 설정합니다. 단계 구분 앞의 줄바꿈 한 개는 구분용이며 본문 값에 포함되지 않습니다. 추출 `1.id=$.id`와 참조 `{{id|URL}}`, `{{id|JSON}}`, `{{id|RAW}}`를 사용합니다. 변수 누락·잘못된 Host·chunked/압축 요청은 거부합니다. 같은 Origin/SCOPE만 전송하고 비2xx·추출 실패·응답 없음 등에서 후속을 중단합니다. 중지는 이미 전송한 요청을 되돌리지 않습니다. 순차 실행 결과와 업무의 최종 상태는 직접 확인합니다. 병렬 검증은 Turbo Intruder를 사용합니다.

OAST 연결에서 Pro Collaborator ID 발급·조회는 사용자의 버튼 클릭으로만 실행합니다. 주소를 검사 요청에 넣고 보내는 것은 수동입니다. Community는 외부 결과 `ID<TAB>DNS/HTTP/SMTP<TAB>시각<TAB>METHOD<TAB>URL`을 가져올 수 있습니다. 외부 결과 연결은 제공한 URL을 기준으로 하며 상호작용만으로 취약점을 확정하지 않습니다.

증거 묶음은 선택 트래픽, 선택 권한 비교 History, 종료된 업무 흐름을 최대 32개 요청으로 보관합니다. 제목·영향·절차를 직접 작성하고 ZIP을 저장합니다. ZIP은 report.md/report.html, evidence.json, coverage.json, requests/responses .http 및 선택 이미지로 구성됩니다. 본문은 기본 제외, 선택 시 제한된 JSON만 민감 키를 가립니다. 인증/쿼리 값은 가립니다. 사용자 작성 설명과 이미지의 민감 정보는 직접 제거하세요. 실제 재현에 필요한 인증 원본은 로컬에서 별도로 준비합니다.

브라우저 확인은 Burp 브라우저의 수동 검증을 안내합니다. JS/DOM·출력·이벤트와 실제 영향을 확인하고 가림 처리한 PNG/JPEG를 첨부합니다(8MB, 각 변 4096px 이하). 자동 브라우저 엔진이나 스크립트 실행 판정을 추가한 것은 아닙니다.

## 검증과 재현

```text
python build_all.py --test --java java --javac javac
python validation/all-features/run.py --java java --javac javac
```

첫 명령은 API 어댑터·headless Swing 회귀 검사, 두 번째는 임시 loopback Spring/Tomcat/H2 테스트 사이트와 실제 HTTP 검사입니다. 이번 배포에서는 설치된 실제 Burp Community 2026.7.3-52685 런타임에서도 배포 JAR 로드, 응답 디코딩, 계정 CSRF GET 갱신과 신원 오류 거부, 새 게시글 생성→조회, 취소 동작을 확인했습니다. GUI 창과 프록시 리스너를 열지 않고 임시 작업 프로필을 사용했습니다.

Pro Collaborator 외부 서비스, 실제 브라우저 XSS 실행, TLS/HTTP2, 전체 Docker/Oracle/Nginx 환경은 이번 검증 범위에 포함하지 않았습니다. 별도의 클라우드 가상 머신을 사용한 것은 아닙니다. 가이드 이미지도 배포 JAR의 실제 Swing 컴포넌트를 창 없이 렌더링한 교육용 예시입니다.

Windows에서 Java NIO의 임시 UNIX 소켓 경로 오류가 있다면 작업 폴더의 짧은 임시 디렉터리를 만든 후 `JDK_JAVA_OPTIONS`에 `-Djdk.net.unixdomain.tmpdir=C:/작업폴더/jtmp`를 설정해 재현할 수 있습니다. 테스트 사이트는 loopback에만 바인딩하며 작업 종료 시 중지합니다.
