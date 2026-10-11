# LOOP 커뮤니티 · JS 추출 실습

기존 커뮤니티에 `/js-lab` 페이지를 추가했습니다. 14개 유형의 JS·HTML·JSON 샘플과 외부 소스맵, 예상 탐지 기준표를 제공합니다. 모든 키와 토큰은 합성이며 샘플 수집은 소스를 실행하지 않고 HTTP 응답 본문만 읽습니다.

## 실행과 사용

업데이트된 압축 파일을 기존 프로젝트 폴더에 반영한 다음 그 폴더에서 다시 빌드합니다. 기본 Dockerfile이 사용하는 `dist/ROOT.war`에도 변경 사항을 반영했습니다.

```bash
docker compose up -d --build --wait --wait-timeout 900
```

1. Burp 브라우저에서 `http://127.0.0.1:8088/js-lab`을 엽니다. 커뮤니티 상단의 **JS 추출 실습** 링크로도 이동할 수 있습니다.
2. `http://127.0.0.1:8088`을 Burp Target Scope에 추가하고 Intercept를 끕니다.
3. **전체 수집**을 누르면 14개 샘플 응답이 HTTP history에 기록됩니다. 특정 샘플만 확인하려면 목록에서 선택하고 **단일 수집**을 누릅니다.
4. Workbench KR 0.15.0의 **JS 추출**에서 수집 결과를 확인합니다. 이미 수집한 요청은 History 분석 기능으로 다시 확인합니다.
5. 사이트 가운데의 **예상 탐지 결과**와 오른쪽 요청/응답, 원본 코드의 위치를 비교합니다. 요청/응답 배치는 상/하 또는 좌/우로 선택할 수 있습니다.
6. 외부 소스맵은 **외부 map 수집**으로 별도 응답을 기록하거나 JS 추출의 같은 출처 map 조회로 확인합니다. 인라인 map은 별도 HTTP 요청 없이 복원됩니다.

오른쪽 패킷 창은 브라우저가 노출하는 상태·헤더·본문을 보여줍니다. 정확한 원본 요청/응답 바이트와 자동 생성된 쿠키·헤더는 Burp에서 확인하세요. JSON 응답은 들여쓰기하여 표시합니다. 원본 다운로드 링크는 HTML 샘플도 다운로드하므로 해당 페이지의 스크립트를 실행하지 않습니다.

## 샘플 분류

| 파일 | 확인할 구문 / 결과 |
|---|---|
| 01-fetch.js | fetch/window.fetch/globalThis.fetch, const 옵션, Request, 메서드·쿼리 이름·헤더 이름·body 키 |
| 02-axios.js | get/post/put/patch/delete/request, baseURL·url·params·data |
| 03-xhr-navigation.js | XMLHttpRequest.open, WebSocket, EventSource, location, window.open |
| 04-constants.js | const 연결, 정적 템플릿, 이스케이프 해제, 상대 경로, 설정 변수 |
| 05-dynamic-scope.js | 동적 템플릿/연결, 스코프 가림, 숫자 연산, 반복 URL의 코드 위치 |
| 06-secrets.js | AWS·Google·GitHub·Slack·Stripe·JWT·가짜 private key, 이름/엔트로피 기반 후보 |
| 07-negative.js | placeholder 제외, URL 사용자 정보·쿼리 값·fragment 제거 |
| 08-modern-module.js | import/export, async/await, optional chaining, nullish coalescing, 동적 import |
| 09-invalid.js | 문법 오류와 문자열/시크릿 보조 추출 |
| 10-recon.js | 클라우드 공급자 6종, 도메인·require 의존성 |
| 11-sourcemap.js + .map | 외부 map 참조와 sourcesContent 원본 복원 |
| 12-inline-map.js | Base64 data URL map 원본과 의존성 복원 |
| 13-inline.html | HTML 내 JS 원본 행 번호, 외부 script 자동 요청 방지, JSON 블록 제외 |
| 14-package.json | dependencies/devDependencies 후보 분류 |

## 실제 엔진으로 확인한 결과와 한계

`js-lab-engine-results.json`에 Workbench KR 0.15.0의 실제 `JsAnalysis`·`JsMiner` 결과를 저장했습니다. 14개 샘플과 외부 map 응답, 총 15개 응답을 분석하여 62개 검사 항목을 통과했습니다. 페이지와 합성 API 컨트롤러의 직접 호출 검사 11개, UI의 DOM 동작 검사 19개도 통과했습니다.

이 결과는 실제 분석 엔진 및 직접 호출/DOM 테스트입니다. 이번 업데이트에서 Burp GUI 통합 실행, 실제 브라우저 렌더링, Docker/Tomcat/Oracle 전체 기동은 수행하지 않았습니다. 이전 패키지의 검증 기록은 이전 버전에 대한 기록이며 이번 업데이트의 실행 증거로 간주하지 않습니다.

다음 사례는 엔진의 현재 한계를 확인하기 위해 포함했습니다.

- **함수 안의 바깥 const + 템플릿**: `05-dynamic-scope.js`의 `nested-users` URL은 현재 미추출됩니다. 같은 파일의 최상위 `users/{dynamic}`은 추출됩니다. 이 차이를 확인하는 회귀 사례입니다.
- **이스케이프된 private key**: 문자열 원문과 이스케이프 해제 값의 지문이 달라 현재 후보가 두 건 나올 수 있습니다. `06-secrets.js`의 실제 후보 수는 9개입니다.
- **최신 모듈 구문**: `08-modern-module.js`는 현재 AST 실패·문자열 보조 추출로 표시됩니다. URL 후보와 의존성을 얻어도 메서드/스코프 분석 성공을 뜻하지 않습니다.
- **잘못된 JS**: `09-invalid.js`는 AST 실패가 예상 결과입니다. Google 키 패턴은 보조 탐지됩니다.
- **map JSON**: 원본 복원 결과와 JSON 문자열 보조 후보가 함께 보일 수 있습니다. `source map:` 출처의 POST 결과를 기준으로 비교하세요.

샘플의 외부 도메인·클라우드 주소·NPM 이름은 관찰용 문자열입니다. 페이지는 이 주소를 호출하지 않습니다. 별도 조회 기능을 누르기 전 후보를 확인하고, 이 사이트를 사용한 테스트에서는 로컬 Scope를 유지합니다.

## HTTP 응답 샘플

페이지 하단 버튼은 `/api/js-lab/echo`에 POST JSON을 보내거나 `unauthorized`, `forbidden`, `rate-limit` 경로로 GET을 보냅니다. 각각 200·401·403·429 합성 응답을 반환하며 429에는 `Retry-After: 5`가 포함됩니다. 이 API는 DB 변경이나 외부 요청을 하지 않습니다. JS 파일 안의 모든 가상 API 경로가 실제 서비스 기능으로 구현된 것은 아닙니다.

## 소스와 재생성

- 페이지: `src/main/resources/static/js-lab.html`
- UI/CSS: `js-lab-ui.js`, `js-lab.css`
- fixture: `src/main/resources/static/js-lab/`
- 페이지·응답 샘플 컨트롤러: `src/main/java/lab/enterprise/JsSyntaxLabController.java`
- fixture 재생성: `python3 js_lab_fixtures.py`
- 검사 소스: `validation/JsLabEngineTest.java`, `JsLabControllerTest.java`, `js_lab_ui_test.cjs`

표의 예상 탐지 기준과 실제 결과 파일을 함께 사용하면 확장 수정 전후의 결과를 비교할 수 있습니다.
