# Simple 회귀 검사 적용 범위

Simple 1.2.0은 Workbench Next 0.53.4의 통합 엔진과 8개 extension의 주요 기능, 각 도구를 직접 여는 메뉴를 보존하고 Scope 통합 점검을 연결합니다. 주요 흐름인 여러 요청의 수동 재현과 증거 저장은 함께 유지하며, 부가 도구만 실행 화면에서 제외합니다. 이전 Simple 1.0.0·1.1.0의 JAR·소스·가이드는 그대로 보관합니다. 기존 검사에서 **사용자가 제거하도록 요청한 화면의 모양**과 **계속 동작해야 하는 엔진·원본·증거 계약**을 구분합니다. 이 문서는 검사 선택의 근거이며 통과 결과가 아닙니다. 실제 실행 결과와 건수는 `burp-workbench/build/test-summary.json` 및 `check-summary.json`을 기준으로 확인합니다.

## Simple 검사로 교체한 이전 화면 검사

| 이전 검사 | Simple에 적용하지 않는 구체적 전제 | 대체 확인 |
|---|---|---|
| `UiCleanupTest` | 루트 상단이 `WorkContextPanel`이어야 함, 옛 우클릭 메뉴의 15개 항목이 정확히 존재해야 함, 트래픽에 직접 다음 작업 버튼이 없어야 함 | `SimpleWorkflowTest`: 8개 도구의 직접 이동·선택 원본·주요 실행·부가 메뉴 제거·무전송. 실제 생산 Swing 화면 렌더링: 모든 도구 배치 |
| `FolderNavigationTest` | 화면에 폴더 트리·폭 조절·숨김 토글·NEXT 배지가 있어야 함 | `SimpleWorkflowTest`: 통합 도구 이동, 요청 선택과 입력 보존, Simple 식별·저장 공간 분리 |
| `AppearanceWorkflowTest` | 제거한 폴더 트리의 주황 아이콘·렌더러·행 높이를 호스트 테마 변경 후 복구해야 함 | `SimpleAppearanceTest`: 실제 Simple에서 테마 복구·트래픽 글꼴/행 높이·OFF/SCOPE 상태등·입력/선택 보존·무전송 |

세 검사의 원문은 Simple 소스에도 보관하지만 기본 실행 목록에서는 제외합니다. 각 사유는 결과 JSON의 `excludedGroups`에도 기록합니다.

## 핵심 검사를 유지하기 위한 좁은 수정

| 검사 | Simple 복사본에서 바꾼 전제 | 유지한 검사 |
|---|---|---|
| `PacketUiTest` | 입력값 검사의 이전 더보기 버튼 대신 설정·시작·중지·직접 증거 버튼의 이름을 확인하고 CSV/별도 작업 창/더보기가 본 실행 화면에 없음을 확인 | 공통 패킷 정렬/Raw 원본·압축 해제·구문 표시·실제 Burp 편집기 전달/스타일·요청 초안 미리보기 원본 보존 |
| `BuildInfoTest` | JAR 파일명을 `burp-workbench-simple-<버전>.jar`로 확인 | 실제 패키징 JAR의 manifest·런타임 버전·표시 이름 일치 |
| `NextIsolationTest` | Simple 이름·저장 공간을 확인하고 기존 Next 설정 fixture도 보존 대상으로 추가 | 기존 설정 미변경, Simple의 별도 읽기·쓰기와 JS 디스크 프로젝트 격리 |
| `FeatureGuideWorkflowTest` | Simple 옵션 메뉴의 실제 도움말 항목, 현재 재현/증거 제목, 8개 extension 시작 안내를 사용 | 모든 통합 도구 라우팅, 도움말/설정 열기 후 선택·작성 중 입력 보존, 좁은 안내 창, 종료 후 동작, HTTP 무전송 |
| `DiagnosticsWorkflowTest` | 상시 3개 버튼 대신 실제 Simple 옵션 메뉴에서 로그 열기 | 오류 원문 가림, 동시 기록의 1,000개 상한과 누적 수, 선택·비우기·다시 열기, 종료 정리, HTTP 무전송 |
| `UiPanelsOptimizationTest` | 1.1.0에서 핵심 여러 요청 재현 화면을 유지하므로 원래 viewport 크기 검사를 복원하고 index 1의 재현 경로도 확인 | 응답 비교의 원본 연결·가림·편집 상태, 증거 추가/비우기·이미지 해시, 응답 관찰 선택, worker 중지/완료 상태 |
| `LayoutPassiveWorkflowTest` | 이전 우클릭 메뉴 0번 위치 대신 보존한 명시적 URL 후보 수집 경로를 사용 | 같은 CLI fixture 수집·대상/패킷 격리·공통 배치 변경·8개 대상 한도·해제 정리 |
| `TrafficScopeTest` | 삭제한 중복 우클릭 SCOPE 메뉴 대신 실제 상단 SCOPE 목록 버튼과 URL 입력/추가를 사용. 기존 선택 원본 소급 관찰 대신 SCOPE를 켠 뒤 실제 수집 콜백으로 전달 | URL 규칙 등록/수정/삭제·경계/Origin/경로·가림/저장·선택 보존·활동/환경 연결·OFF 차단·Burp 전역 Scope 미변경·대상 요청 무전송 |
| `AuthLayoutFitTest` | 이전 6개 toolbar 대신 인증 탐지·선택 비교·중지·계정/판정 설정의 정확한 4개 이름·순서를 확인 | 800/1024/1280/1600px 표 최소 너비·viewport 채움·도구 모음 줄바꿈·계정 상세 입력 배치 |
| `AuthSetupBundlesTest` | 이전 `next.` 접두사 대신 `BuildInfo.storageKey("auth-setup-library-project-v1")`의 정확한 프로젝트 키를 확인 | 묶음 교환·파일 원자성/복구·용량 제한·인증값 제외·프로젝트 격리 |
| `AuditRepairsTest` | 원본 실습 사이트 JS fixture를 Simple의 `src/test/resources/fixtures/community.js`로 복사해 읽기 | 같은 실습 JS를 대상으로 한 분석 회귀 내용 |

수정 사유는 결과 JSON의 `adaptedGroups`에도 기록합니다. 이전 테스트 이름에 `Next`나 당시 버전이 남아 있어도 실제 실행 대상은 Simple JAR입니다.

## 그대로 유지하는 검사

원본 저장·수집 OFF/SCOPE·검색 취소·정렬된 요청 연결·계정별 인증/권한 비교·설정 스냅샷·세션 갱신·응답 분석·입력값 검사·증거 가림/ZIP 원자적 저장·프로젝트 격리·해제 정리 검사는 유지합니다. `NavigationLifecycle0534Test`, `Navigation0532Test`, `WorkspaceResumeTest`, `StabilizationIntegration0534Test`, `UsabilityIntegration0532Test`는 보존한 라우팅과 비동기 원본 처리 계약을 확인하므로 제거하지 않습니다.

`BountyFeaturesTest`, `CompletenessTest`, `IntegrationEdgesTest` 등은 화면에서 제거한 부가 도구의 내부 메타데이터·가림·원본 연결도 검사합니다. 해당 엔진이 보존되어 있으므로 계속 실행합니다. 엔진 검사를 통과했다고 해당 부가 도구를 Simple 화면에서 제공한다는 뜻은 아닙니다.

Mapper Java 검사는 별도 Mapper JAR 대신 **Simple JAR 안에 포함한 실제 Mapper 클래스**를 대상으로 실행합니다. 기존 `MapperTest`가 내부에서 호출하는 하위 `run()` 검사를 다시 합산하지 않으며, 새 `InspectionBridgeTest`의 독립 `main()`은 별도 실행·집계합니다. 자동 선택은 `Test.run()`의 전이 호출 관계를 확인해 다른 main에 이미 포함된 하위 main을 제외합니다. 구체적으로 `BrowserBodyObservationTest.run()`은 `MapperTest.main()`에 포함되므로 별도 main으로 다시 실행하지 않으며, 이 관계는 `mapperJavaNestedMains`에 남깁니다. 브라우저 도우미 검사는 Node의 TAP 출력으로 파일별 선언된 하위 검사가 모두 실행되었는지 확인합니다. 설치된 Node의 `--help`에서 지원을 확인한 뒤 stable `--test-isolation=none` 또는 experimental `--experimental-test-isolation=none`을 사용하며, 둘 다 없으면 기본 동작을 사용합니다. 파일 1개만 검사했다는 요약을 여러 하위 검사 통과로 인정하지 않습니다. 테스트별 `user.home`은 `build/test-home/<검사명>`으로 격리하며 기존 사용자 설정을 읽거나 바꾸지 않습니다.

## 1.2.0 Scope 통합 점검 검사

기본 프로필은 기존 회귀와 함께 `ScopeInspection*Test.java`를 중복 없이 자동 등록합니다. 아래 표는 확인할 계약이며, 최종 실행 결과나 통과 건수를 미리 확정하는 표가 아닙니다.

| 검사 | 확인할 계약 |
|---|---|
| `ScopeInspectionRunnerTest` | Scope·Origin·리디렉션 경계, 신원·참조 요청을 포함한 예산, 경로 탐색·분석·계정별 원본, 일시정지·중지·재개와 부분검사 사유 |
| `ScopeInspectionAnalysisTest` | 서버·WAS·DB·API의 근거·추정 구분, 문서/관측 API 대조, 민감정보 후보의 가림·분류·발견 위치, 분석 제한의 명시 |
| `ScopeInspectionHistoryTest` | 프로젝트·실행 조건별 이력 분리, 인증값·원문을 담지 않는 저장, 변경 비교와 불완전한 실행의 해소 판정 방지 |
| `ScopeInspectionBrowserTest` | 실행 ID·만료·허용 범위·브라우저 관측 메타데이터의 연결, 오래되거나 범위 밖인 이벤트 거부와 종료 정리 |
| `ScopeInspectionUiTest` | 기존 8개 도구 보존, 창 열기만으로 전송하지 않음, 명시적 실행·원본/도구/증거 연결과 완료·제외·부분검사 표시 |
| `InspectionBridgeTest` | Simple JAR에 내장된 실제 loopback 브리지의 인증·명령·이벤트·종료 계약 |

Node 검사는 기존 `browser-helper.test.mjs`의 직접 선언 15개와 새 `inspection-browser.test.mjs`의 직접 선언 9개를 구분해 실행합니다. 이 숫자는 현재 파일의 선언 수이며 실제 통과 수는 최종 TAP 결과를 기준으로 합니다. 새 검사는 전용 탭의 Scope/GET·HEAD session rule, 라우트·메뉴 구분, DOM·MAIN guard 어댑터, 실행 명령·중지·정리 등을 확인합니다. 파일별 실제 수는 `check-summary.json`의 `mapperBrowserHelperGroups`, Java 수는 `mapperJavaGroups`에 남깁니다.

Node의 DOM·Chrome API 어댑터 검사는 실제 Chromium에서 MV3 확장이 로드되어 탐색한 결과와 다릅니다. 현재 관리 환경의 시스템 Chromium은 `ExtensionInstallBlocklist: ["*"]` 정책 때문에 도우미 로드를 차단했습니다. 이를 브라우저 확장 실행 통과로 표시하지 않으며, native harness 진단과 Scope 렌더링·Node 검사 결과를 별도로 읽어야 합니다. CI에는 현재 native 확장 실행 검사를 추가하지 않습니다.

화면 검사는 기존 Simple 화면 뒤에 `render_scope_inspection.py`로 실제 운영 패널을 별도 렌더링합니다. 합성 패킷·브라우저 관측을 사용한 캡처이며 실제 Burp GUI·대상 사이트 점검·브라우저 DOM 실행 결과로 합산하지 않습니다. 신규 helper ZIP은 manifest·필수 JS·라이선스·CRC·SHA와 소스 지문을 검증합니다.

`python simple/dev.py check`는 선언한 전체 회귀·loopback 페이로드 HTTP·Mapper Java·브라우저 도우미 Node 검사를 실행합니다. 실제 Burp GUI, Windows DPI/IME, 브라우저 수동 조작, Docker 실습 스택, 외부 OAST/BApp/AI 서비스와 기존 실습 사이트 전체 HTTP 하네스는 별도 검증 범위입니다.
