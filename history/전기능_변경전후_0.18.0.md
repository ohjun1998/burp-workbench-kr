# 0.18.0 전 기능 테스트 및 변경 전/후

Workbench KR 0.18.0 / 커뮤니티 사이트 3.4.0. 9개 기능군과 16개 프로필을 대상으로 총 1078개 검사 통과, 실패 0개입니다. 기능군에는 실제 사이트 응답을 연결하고 세부 옵션은 회귀 검사로 보완했습니다.

| 기능 | 변경 전 0.17.4 | 변경 후 0.18.0 |
|---|---|---|
| 트래픽/SCOPE | 내부 범위·창모드 관리 | 실제 응답 가져오기·Origin/경로 경계·저장/복원·현재 화면 유지 재검증 |
| 권한 비교 | 공개 글에서도 동일 응답/소유값 차이로 의심 가능 | PUBLIC/OWNER_ONLY 정책, 미설정 안내, 소유자 읽기 재조회로 은닉형 404 구분 |
| 데이터/JWT | 변환·JWT·구조 비교 | 실제 API/JWT로 변환 5종·UTC·서명 안내·구조 비교 재검증 |
| JS 추출 | async/module 실패, 속성 접근이 도메인으로 검출 | 문법 호환·단순 래퍼/범위 분석·dynamic import 보조, 잘못된 루트/속성 도메인 제거 |
| 환경 탐색 | entity 경로가 없는 경로 기준을 변동시킴 | 알려진 경로 entity 마스킹, 명시적 동적 필드 제외, 실제 변동 음성 검사 |
| URL 수집 | 패턴 내 다른 HTTP 상태가 대표에서 사라짐 | 상태별 대표·총 5건 제한, OpenAPI 메서드 보존, 쓰기 전용 명세 GET 방지 |
| 활동 추적 | OpenAPI 발견 정보 미연결 | 정적 명세 발견 근거 연결, 변경/완료 독립·인증 값 제외 저장 재검증 |
| 응답 분석 | 실제 Passive 규칙·저장형 흐름 연결 부족 | Passive 관찰·API 문서·고유 마커 저장/조회 연결 탭 추가 |
| 페이로드 | 원래 값이 실제 데이터에 있으면 SQL 참거짓 미탐 | 원문 동일성+정규화 비교, Socket/native/wrapped timeout 중단, Legacy 취소 상태 정리 |

## 실제 기능별 검사

| 기능군 | 내용 |
|---|---|
| 트래픽/SCOPE | 실제 응답 가져오기, Origin 규칙, 저장/가져오기, 경계 검사; 팝업/필터는 전체 UI 회귀 |
| 권한 비교 | 공개/공유/소유자 정책 실제 재전송, 소유자 재조회 404 차단; CSRF·Record·계정 스냅샷은 회귀/커뮤니티 검사 |
| 데이터 도구/JWT | UTF-8 5종 변환 왕복, 실제 API 소비, 실제 JWT UTC/서명 미검증, SHA256, 실제 객체 구조 비교 |
| JS 추출 | 실제 modern/community JS, 메서드·래퍼·동적 경로·잘못된 루트 제외·도메인·captured map; 시크릿/구문 14종 회귀 |
| 환경 탐색 | 실제 Tomcat 식별, entity soft404, 동적 JSON 명시적 제외, 진짜 데이터 변동 음성 검사; 후보 전송은 회귀 검사 |
| URL 수집 | 실제 History/JS/OpenAPI, GET 200/404, 응답별 대표, DB 값 제외; 외부 Wayback/CLI는 로컬 fixture 회귀 |
| 활동 추적 | 실제 요청/Repeater 인자 변경, OpenAPI 발견, 저장/가져오기·완료 독립; 근거 연결은 회귀 |
| 응답 분석 | 실제 Passive 양성/음성, OpenAPI/refs/변수/메서드, 세션 내 저장→조회·HTML/문자·실행 미검증 |
| 페이로드 검사 | 16개 프로필 취약/수정 쌍, 실제 SQL/HTML/파일/SpEL/XPath/XML/업로드/loopback SSRF, 제한된 LDAP/NoSQL/command/CRLF fixtures; 실제 압축·charset·큰 본문·socket timeout 검사 |

## 사이트 고도화

/diagnostics 센터에 33개 기대 결과 사례와 16개 요청 템플릿을 추가했습니다. 공개/공유/소유자 객체, 취약/수정 헤더·오류, 저장/조회, OpenAPI refs/메서드, modern JS/source map, entity·동적 JSON·불안정 기준, 상태 코드·gzip/deflate·MS949·빈/큰/깨진 본문·지연·리다이렉트·SCOPE 경계를 포함합니다. 기존 /v3/api-docs의 쓰기 메서드와 폼 본문도 실제 구현에 맞게 수정했습니다.

## 프로필 증거 수준

- XSS: 실제 반사 HTML. 브라우저 실행 미검증.
- SQLI: 실제 H2 검색의 취약/수정 참거짓 쌍. 고정·적응형 모두 검사.
- UPLOAD_NAME/BODY: 실제 업로드 수락/거부, MIME/본문 readback.
- DOWNLOAD/TRAVERSAL: 실습용 private 파일과 관리하는 임시 파일.
- SSTI: 실제 SpEL. XPATH: 실제 JAXP.
- XXE: 실제 내부 DTD 엔티티 확장. 외부 엔티티 취약점 확정 아님.
- SSRF: loopback 수신기로 실제 서버 호출을 두 마커로 확인.
- REDIRECT/CRLF/COMMAND/LDAP/NOSQL/CUSTOM: 명시된 제한 응답 fixture와 수정 쌍. 실제 LDAP/MongoDB/셸 취약점 성공을 주장하지 않음.

## 집계

| 검사군 | 통과 | 실패 |
|---|---:|---:|
| 확장 Java/UI/API 회귀 | 855 | 0 |
| 전 기능 실제 HTTP 연계 (199회) | 100 | 0 |
| JS 15개 응답/소스맵 | 62 | 0 |
| 커뮤니티 API | 49 | 0 |
| 진단 센터 DOM API 어댑터 | 12 | 0 |
| 합계 | 1078 | 0 |

600KB 본문 제한 표시, 빈/깨진 JSON, 리다이렉트 미추적, 실제 100ms socket timeout 이후 중단, 공개 HTML·JSON 차단 우선순위, 인코딩된 미해결 OpenAPI 변수도 확인했습니다.

소스: AuthPolicy/AuthJudgement/AuthComparePanel, JsSyntax/JsAnalysis/JsMiner, EnvironmentResponse, ReconMetadata/ApiInventory, PassiveFindings/StoredFlows/ResponseReconPanel, PassiveScanPanel/WorkbenchExtension, Engine/Adaptive/PayloadHttp. 추가 회귀는 AuditRepairsTest 63개입니다. 사이트는 DiagnosticsController/BoundaryController, LabController API docs, diagnostics 자원과 JsLabEngineTest를 수정했습니다.

실제 Burp GUI/Proxy·브라우저 실행·전체 Docker/Oracle/Nginx·TLS/HTTP2는 이번 범위 밖입니다. JS는 제한적인 정적 분석이며 크기/개수 한도 밖 결과·복잡한 흐름은 놓칠 수 있습니다. 후보는 수동 확인이 필요합니다.
