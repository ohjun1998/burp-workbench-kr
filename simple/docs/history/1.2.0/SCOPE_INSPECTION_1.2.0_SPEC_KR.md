# Simple 1.2.0 SCOPE 통합 점검의 적용 범위

사용자는 기존 8개 extension을 유지한 Simple에, SCOPE URL 한 번 선택으로 메뉴·환경·경로·API·민감정보를 확인하고 원본·증거까지 연결하는 제안 전체를 적용하도록 요청했습니다. 이 문서는 기능 누락을 막기 위한 요구사항과 검증 연결표입니다. **이 문서 자체는 구현 완료 또는 검사 통과 근거가 아닙니다.** 실제 결과는 [1.2.0 검증 기록](VERIFICATION_SIMPLE_1.2.0.json), 해당 테스트와 화면 보고서를 확인합니다.

## 공통 사용 흐름

SCOPE URL·HTTP 계정 선택 → 통합 점검 시작 → 메뉴·환경·경로 발견 → 응답과 시크릿 후보 분석 → 원본·기존 도구·증거로 연결합니다. 브라우저 모드는 준비한 helper와 로그인 세션으로 별도 탭의 메뉴·SPA 탐색을 추가합니다. 기존 8개 도구의 직접 진입점은 유지합니다.

## 요구사항·코드·검사·가이드 연결표

아래는 요구사항과 구현·검사의 연결입니다. 실행 여부와 통과 결과는 버전별 검증 기록에서 확인합니다. UI 캡처는 운영 컴포넌트에 합성 자료를 넣어 생성하고, 실제 네트워크 검증은 별도 fixture 검사로 구분합니다.

| ID | 적용할 요구사항 | 구현 연결 | 반드시 확인할 동작 | 가이드·캡처 |
|---|---|---|---|---|
| R01 | 기존 8개 도구 유지와 통합 실행 진입점 | `SimpleNavigation`, `WorkbenchExtension`, `ScopeInspectionPanel` | 화면 열기만으로 대상 전송 없음; 8개 도구·선택 원본·작성 입력 유지 | 기존 8개 도구 캡처, `12-inspection-setup.png` |
| R02 | URL·계정·조건을 한 실행으로 고정 | `ScopeInspection.Options`, `ScopeInspectionRunner` | 실행 ID, 프로필별 출처, 중복 요청 제거; 다른 계정·프로젝트 혼입 없음 | 설정·진행, `12`·`13` |
| R03 | 사이트 메뉴·정적 링크·JS·robots/sitemap·API 문서 발견 | `ScopeInspectionAnalysis`, `EnvironmentCatalog`, `EnvironmentDiscovery`, `ApiInventory` | 실제 참조/문서/추정 후보 구분; 미확정 메서드·필수값은 자동 전송 대상에서 제외 | 경로·API, `15`·`16` |
| R04 | 로그인된 SPA·메뉴 탐색 | `ScopeInspectionBrowser`, `BrowserBridge`, helper 0.4.0 | 별도 탐색 탭; hash route 구분; 메뉴·현재 라우트·API의 관측 문맥; 기존 사용자 탭 보존 | helper 설치·브라우저 모드, `21` |
| R05 | 브라우저·HTTP의 동일한 SCOPE 경계 | `WorkbenchScope`, `ScopeInspectionRunner`, helper scope/guard | 전송·리디렉션 단계마다 Origin·포트·경로·제외 조건 확인; 다른 Origin에 인증값 전달 없음 | 설정·커버리지·브라우저, `12`·`20`·`21` |
| R06 | 서버·WAS·프레임워크·프록시/CDN·DB 근거 분석 | `ScopeInspectionAnalysis`, `EnvironmentCatalog`, `SqlErrors` | 헤더·본문의 제품·숫자 버전과 출처; 관측과 추정 분리; 충돌 표시; DB 근거 부족은 미확인 | 환경, `14` |
| R07 | 환경과 실제 발견 근거별 주요 경로 우선순위 | `ScopeInspectionRunner`, `EnvironmentKnowledge`, `ScopeInspectionAnalysis` | 실제 관측→문서 선언→기술 추정 후보; 범위·깊이·요청 한도 안의 조회 | 경로, `15` |
| R08 | 응답의 실제 내용과 공통 화면 구분 | `EnvironmentControls`, `EnvironmentContent`, `ScopeInspectionRunner` | 부재 경로 대조·동적 값 고려; 로그인/WAF/429/soft404·불안정 기준을 성공 노출로 확정하지 않음 | 경로·커버리지, `15`·`20` |
| R09 | 키·민감정보 후보와 정확한 원문 위치 | `ScopeInspectionAnalysis`, `JsAnalysis`, `SecretCandidates` | 공급자 패턴/문맥·공개 식별자·예제·비밀키 후보 구분; 본문 줄/문자 위치; 기본 마스킹·지문; 자동 키 검증/인증 재사용 없음 | 민감정보·원본, `17`·`22` |
| R10 | 원본 요청·응답 보존과 기존 도구 연결 | `DiskTrafficStore.PacketRef`, `ScopeInspectionPanel`, `WorkbenchExtension` | 미리보기 문자열 대신 불변 원본 ID; 앞64 KiB 미리보기와 Burp 기본 읽기 전용 전체 원본 보기; 원문/Repeater/계정 비교/JS/응답/증거가 같은 원본을 참조 | 원본과 8개 도구, `22` |
| R11 | 권한별 노출 차이 | `ScopeInspectionRunner`, `ScopeInspection.Profile`, `AuthComparePanel` | 비교 계정의 출처 분리; 세션 상태·응답 차이·미성립 사유; 정책 없는 차이를 취약점으로 확정하지 않음 | 계정·권한 차이, `18` |
| R12 | API 문서·JS·관측 요청 대조 | `ScopeInspectionAnalysis.compareInventory` | 문서 선언/실제 응답/JS 후보를 구분하고 문서에 없는 관측·문서만 있는 항목을 표시 | API, `16` |
| R13 | 재점검 변경 비교 | `ScopeInspectionHistory` | 같은 프로젝트·Origin·계정·조건만 비교; 새/변경/해결 후보; 불완전 실행으로 해결 확정 없음 | 변경, `19` |
| R14 | 커버리지와 미확인 사유 | `ScopeInspection.Snapshot`, `ScopeInspectionRunner`, `ScopeInspectionPanel` | 완료·대기·제외·실패·부분 분석을 구분; 큐 완료와 사이트 전체 검증 완료를 구분 | 진행·커버리지, `13`·`20` |
| R15 | 요청 한도·간격·깊이·시간·리디렉션·중지/재개 | `ScopeInspection.Options`, `ScopeInspectionRunner` | 기준·신원·리디렉션도 HTTP 예산에 포함; 세션/429/범위 변경·종료 처리; 오래된 작업이 새 결과를 덮어쓰지 않음 | 설정·진행·커버리지, `12`·`13`·`20` |
| R16 | 대용량 파일과 분석 한계 구분 | `ScopeInspectionRunner`, `ScopeInspectionAnalysis` | 덤프·아카이브 자동 제외; 수신/보관과 본문 분석 한도 구분; 미검사 원인을 표시 | 경로·커버리지, `15`·`20` |
| R17 | 마스킹된 실행 이력과 계정·프로젝트 격리 | `ScopeInspectionHistory` | 헤더·쿠키·본문·시크릿 원문 미저장; 원자적 저장과 용량 제한; 원본 없는 과거 이력 구분 | 변경·원본·증거, `19`·`22` |
| R18 | 기존 파일·배포·가이드 보존 | 버전별 `downloads/`, `docs/history/` | Next·Simple 1.0.0/1.1.0 산출물 해시 유지; 새 1.2.0 파일·소스·가이드 지문 일치 | 설치·다운로드·검증 범위 |

## 검사별 요구사항 연결

| 검사 소스·명령 | 연결 요구사항 | 확인하는 경계 |
|---|---|---|
| `burp-workbench/src/test/java/kr/workbench/ScopeInspectionRunnerTest.java` | R02·R03·R05·R07·R08·R10·R11·R14·R15·R16 | 격리 전송·원본·조회 정책·범위·예산·신원·중지/재개 |
| `burp-workbench/src/test/java/kr/workbench/ScopeInspectionAnalysisTest.java` | R03·R06·R09·R12·R14·R16 | 실제 응답 단서·본문 위치·마스킹·API 출처·부분 분석 |
| `burp-workbench/src/test/java/kr/workbench/ScopeInspectionHistoryTest.java` | R02·R13·R17 | 저장 마스킹·프로젝트/조건 격리·완료/불완전 실행 비교 |
| `burp-workbench/src/test/java/kr/workbench/ScopeInspectionBrowserTest.java` | R04·R05·R14·R15 | 명령·이벤트 연결·브라우저 실행 상태·범위 표현 |
| `feature-mapper/tests/inspection-browser.test.mjs` · `feature-mapper/tests/inspection-native.mjs` · `feature-mapper/tests/inspection-dom-native.mjs` | R04·R05·R14·R15 | helper 가드·DOM 탐색·전용 탭 정리. 확장 통합 검사와 확장 없는 실제 Chromium DOM 검사를 구분 |
| `burp-workbench/src/test/java/kr/workbench/ScopeInspectionUiTest.java` | R01·R02·R10·R14·R15 | 8개 도구·통합 실행·원본 연결·입력 유지·종료 |
| `tools/render_simple.py --check` · `tools/render_scope_inspection.py --check` | R01·R10·R14 | 밝은/어두운 테마와 두 창 크기의 운영 컴포넌트·본문 높이·컨트롤 배치 |
| `dev.py verify` · `tools/check_simple_guide.py` | R18 | 배포 소스/해시·최종 JAR 캡처 지문·PDF 구조·기존 가이드 보존 |

## 검증할 실제 경계

- `ScopeInspectionRunnerTest`는 조회·리디렉션·SCOPE·예산·프로필·중지/재개·원본 보관을 격리 fixture/API 어댑터로 확인합니다.
- `ScopeInspectionAnalysisTest`는 기술·DB 단서, 민감정보 후보 마스킹·원문 위치, API 대조·부분 검사 범위를 확인합니다.
- `ScopeInspectionHistoryTest`는 마스킹된 저장·프로젝트/조건 격리·변경 비교와 불완전 실행의 보류 처리를 확인합니다.
- `ScopeInspectionBrowserTest`와 helper 검사는 scope/route/메서드 가드·명령·중지·이벤트 연결을 확인합니다. `feature-mapper/tests/inspection-native.mjs`의 확장 통합 검사, `inspection-dom-native.mjs`의 확장 없는 실제 Chromium DOM 검사, Node 어댑터 검사는 구분해서 기록합니다. DOM 검사만으로 MV3·DNR·설치/권한 흐름을 확인했다고 해석하지 않습니다.
- `ScopeInspectionUiTest`는 기존 8개 도구 유지, 원터치 실행·상태·원본·결과 연결, 자동 실행 없음과 종료 처리를 확인합니다.
- 밝은/어두운 테마·작은/기본 창의 운영 Swing 캡처로 실제 컨트롤과 주요 본문 영역을 검사합니다. 가이드는 최종 JAR 해시와 같은 캡처를 사용합니다.

## 설명과 판정 원칙

HTTP 200이나 기술 이름 하나만으로 노출·취약점·DB 제품을 확정하지 않습니다. 탐지 확실성, 영향 후보, 유효성 검증 상태를 분리합니다. 공개 SDK 식별자·예제 값·비밀키 후보를 구분하고 발견한 키를 자동 사용하지 않습니다.

브라우저 로그인 계정과 HTTP 점검 계정은 별개의 실행 주체입니다. helper는 브라우저 쿠키를 Workbench HTTP 계정으로 복사하지 않습니다. 브라우저의 페이지/동작 한도와 네트워크 관측 수는 HTTP worker의 엄격한 요청 예산과 구분합니다. 좁은 SCOPE가 필요한 리소스를 제외하면 부분 탐색으로 표시하며 범위를 자동 확장하지 않습니다.

브라우저의 화면·API 연결은 탭과 현재 라우트에서 함께 관측한 문맥이며 JavaScript 호출의 인과관계를 확정하지 않습니다. URL 규칙으로 안전하게 변환할 수 없는 네이티브 정규식 범위는 브라우저 모드에서 사용하지 않고 HTTP의 정확한 네이티브 범위 판정을 유지합니다. GET/HEAD와 알려진 변경 경로 제외 규칙은 서버의 모든 GET 기능에 부작용이 없다는 보장이 아닙니다.

Montoya 전송 API에서 본문 분석 한도를 둔 것은 실제 응답 다운로드를 그 바이트에서 중단했다는 뜻이 아닙니다. 대용량 후보 자동 제외, 본문 분석 범위, 실제 전송/수신 한계를 각각 설명합니다. 캡처·어댑터·실제 브라우저·실제 Burp의 실행 범위는 최종 검증 결과에서 구분합니다.
