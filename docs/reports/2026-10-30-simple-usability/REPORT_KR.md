# Simple 1.4.1 실사용 흐름 개선 보고서

2026-10-30(Asia/Seoul), Simple 1.4.0을 기준으로 **전체 8개 도구 수행 → 개선 1건 적용 → 전체 8개 도구 재수행**을 5라운드 완료했다. 각 라운드의 before와 after에서 각각 115개 검사가 통과했고 실패는 0건이었다. 전체 흐름 실행은 총 10회이며, 매번 새 JVM·임시 홈·Java 설정·임시 작업 공간을 사용했다. 중간 제품의 이전 after 실행 결과를 다음 before로 재사용하지 않았다.

앞서 수행한 작업별 전후 비교 5건은 [preliminary/](preliminary/)에 보존한 **사전 조사**다. 이 보고서의 5라운드 완료 수와 지표에는 포함하지 않는다. 기준과 수행 순서는 [계획](PLAN_KR.md), [라운드 정의](ROUND_DEFINITION_KR.md), 실제 완료 기록은 [rounds/execution-v1/state.json](rounds/execution-v1/state.json)에 있다. 아래 수치는 `/workspace/usability-five-rounds/execution-v1`에서 새로 실행한 결과다.

기준 소스는 main `81b48defb81db7c656e913a8ebd6848e30621cf1`이다. 별도 격리 소스에 수정 1~5를 순서대로 누적하고 단계별 JAR을 빌드했다. 최종 비교 대상은 1.4.1 후보이며, 기존 1.4.0 배포물과 Next 소스는 보존했다. 각 실행의 `metadata.json`은 실행한 JAR·하네스 SHA-256, 명령, Java 17 환경, 결과 파일 해시와 격리 공간 삭제 여부를 기록한다.

전체 흐름은 저장소 원본 **LOOP Community 3.10.0**에 실제 HTTP를 보내며 운영 Swing 컴포넌트를 Montoya 어댑터로 조작했다. 고정한 [WholeWorkbenchWorkflowAudit.java](../../../audit/simple-usability/WholeWorkbenchWorkflowAudit.java)의 SHA-256은 `f3cfa5f91fd2435e0446d1e2da96919537df1eef706231b96821dc711f05aed4`다. 단순히 각 화면을 여는 것으로 완료를 세지 않았다.

| 도구 | 매번 수행·확인한 작업 |
|---|---|
| 트래픽 | 캡처 OFF의 수집 차단, 실제 응답의 History 메뉴 가져오기, 검색·원본 선택, 동일 요청의 Repeater 준비와 증거 전달 |
| 기능 분류 | 수집한 HTTP의 Mapper 분류, 기능·패킷 선택, 원본 요청·응답 일치, 프로젝트 직렬화·복원 |
| 계정·권한 비교 | Alice/Bob의 실제 인증 패킷 지정, 잘못된 조건의 전송 차단, fixed/vuln 읽기 경로 비교, 계정별 원본·응답 확인 |
| JS 분석 | 실제 JS 응답의 트래픽 메뉴로 API 분석, 예상 경로 선택, 소스 JS 원본 확인, 전송 없는 요청 초안 준비 |
| 서버·경로 | 1,000개 URL 후보 가져오기·필터, 선택한 URL의 명시적 GET, 관측 경로의 개별 확인·근거, 프로젝트 직렬화 |
| 응답 분석 | 실제 응답의 보안 관측과 원본 증거 선택, OpenAPI 항목 선택·요청 초안 준비 |
| 페이로드 실행 | 무해한 쿼리 값의 기준 요청·비교 실행, 결과·원본 증거 선택, 제한된 후속 실행 중지 |
| 재현·증거 | 읽기 전용 GET 2단계 재현, 결과·가림 미리보기 확인, 운영 직렬화 API로 ZIP 생성·내용 검증 |

같은 실행에서 통합 점검·대시보드의 요청 예산과 원본 전달, 빈 조건 복구, 도움말·설정 진입, 종료 후 작업 정리도 확인했다. 전체 흐름 1회당 엔진 HTTP는 **42건**, 별도 로그인 준비 HTTP는 **2건**이었다. Repeater 준비와 원본·증거 선택은 전송 횟수와 구분했다. 환경 도구의 1,000개 후보는 같은 실제 읽기 경로에 합성 쿼리를 붙인 목록이며, 1,000개 경로를 요청하거나 발견했다는 뜻이 아니다.

| 라운드 | 누적 개선 | before 전체 흐름 | after 전체 흐름 | 새 집중 측정 결과 |
|---|---|---|---|---|
| 1 | 같은 원본·내용 갱신 시 상세 텍스트 교체 생략 | 115 통과 / 실패 0, 8도구 | 115 통과 / 실패 0, 8도구 | 5회 갱신 중 텍스트 재선택 **5 → 0회**, 문서 이벤트 **10 → 0건** |
| 2 | URL 입력 완료 시 해당 대상의 계정 목록 갱신 | 115 통과 / 실패 0, 8도구 | 115 통과 / 실패 0, 8도구 | 대상 변경·계정 선택·시작까지 명시적 UI 조작 **6 → 4회**, 복구 조작 **2 → 0회** |
| 3 | 내용이 바뀐 스냅샷 표만 한 번에 갱신 | 115 통과 / 실패 0, 8도구 | 115 통과 / 실패 0, 8도구 | 동일 스냅샷 EDT 중앙값 **25.210611 → 2.900999ms**, 모델 이벤트 **6,009 → 0건** |
| 4 | URL 일괄 가져오기 후 필터·정렬 1회 적용 | 115 통과 / 실패 0, 8도구 | 115 통과 / 실패 0, 8도구 | 1,000개 가져오기 중앙값 **316.070339 → 111.963185ms**, 필터 적용 **1,000 → 1회** |
| 5 | 명시적 대시보드 조건 선택 후 결과 영역 표시 | 115 통과 / 실패 0, 8도구 | 115 통과 / 실패 0, 8도구 | 12관측 중 추가 스크롤 필요 **6 → 0건**, 한 번 클릭으로 목록·상세 표시 **6/12 → 12/12** |

각 라운드의 전체·집중 결과와 적용 패치는 [1회](rounds/execution-v1/round-1/round-summary.json), [2회](rounds/execution-v1/round-2/round-summary.json), [3회](rounds/execution-v1/round-3/round-summary.json), [4회](rounds/execution-v1/round-4/round-summary.json), [5회](rounds/execution-v1/round-5/round-summary.json)에서 추적한다. 각 폴더의 `before-whole/`, `after-whole/`, `before-focus/`, `after-focus/`가 독립 실행 근거다. 매번 같은 검사를 반복한 것이므로 이를 서로 다른 1,150개 기능 검사로 합산하지 않는다.

1회 집중 측정은 실제 loopback 응답과 연결된 근거를 읽는 동안 전송이 **10 → 10건**으로 유지됨을 확인했다. 같은 내용에서는 선택을 보존하고, 내용·행·실행이 바뀌면 상세와 caret을 교체하는 조건도 확인했다. 2회는 두 실제 loopback origin을 사용했다. 명시적 시작 전 전송은 0건이며, 두 대상의 실행은 각각 10건이었다. 계정 목록 조회 실패·잘못된 URL·선택 계정 부재에서 이전 계정을 비우고 복구하며, 원본 동작이 새 대상의 정확한 ID를 사용함을 확인했다. 원시 지표: [1회 전](rounds/execution-v1/round-1/before-focus/results/result.json)·[후](rounds/execution-v1/round-1/after-focus/results/result.json), [2회 전](rounds/execution-v1/round-2/before-focus/results/result.json)·[후](rounds/execution-v1/round-2/after-focus/results/result.json).

3회 집중 부하는 실제 응답 2개에 연결한 **합성 finding 2,000개·page 2,000개**다. 시나리오마다 준비 2회를 제외한 5회 측정의 중앙값을 사용했다. 선택 원본·계정·정렬·열 너비·행 순서를 유지했고, 아래 작업과 원본 열기에 추가 HTTP는 0건이었다. 모델 이벤트·원본/계정/정렬 보존은 통과 조건이며, 시간은 로컬 진단값으로만 기록했다. 정렬만 한 대조 작업에서는 시간 감소가 관측되지 않았다. [전](rounds/execution-v1/round-3/before-focus/results/result.json)·[후](rounds/execution-v1/round-3/after-focus/results/result.json)에는 개별 표본과 최소·최대값도 있다.

| 3회 시나리오 | EDT 시간 중앙값 ms, 전 → 후 | EDT 할당량 중앙값 bytes, 전 → 후 | 모델 이벤트 중앙값, 전 → 후 |
|---|---:|---:|---:|
| 동일 스냅샷 | 25.210611 → 2.900999 | 9,889,192 → 568,232 | 6,009 → 0 |
| finding 1개 변경 | 20.827818 → 3.626816 | 9,751,144 → 1,048,208 | 6,009 → 2 |
| 정렬 상태에서 finding 변경 | 24.873537 → 4.965660 | 12,365,200 → 1,462,840 | 6,009 → 2 |
| finding·경로 순서 변경 | 75.958316 → 36.846018 | 53,457,200 → 16,896,616 | 6,009 → 9 |
| 정렬만 수행 | 1.359573 → 1.474861 | 612,896 → 612,896 | 0 → 0 |

4회 집중 측정은 준비 1회를 제외한 5회 중앙값이다. EDT 할당량은 **143,618,856 → 55,929,528 bytes**, 필터의 행 평가 횟수는 **501,499 → 1,000회**, 정렬 이벤트는 **1,999 → 1건**이었다. URL 1,000개와 쿼리 차이·메서드·출처·필터의 정확한 원본 연결을 보존했고, 가져오기 전후 대상 전송은 0건이었다. [전](rounds/execution-v1/round-4/before-focus/results/result.json)·[후](rounds/execution-v1/round-4/after-focus/results/result.json)에 표본별 결과가 있다.

5회 집중 측정은 합성 패킷을 운영 저장소에 넣고 전체 SimpleNavigation에서 수행했다. 라이트·다크 × 1024×720·1280×800 × API 카드·API 분류·높음 중요도 선택의 **12관측**이다. 각 관측은 제어 1회 클릭 뒤 목록·상세·선택 행의 실제 표시 영역을 검사했다. 작은 화면의 6관측에서 필요했던 추가 명시적 스크롤이 없어졌으며, 휠 횟수나 사람의 작업 시간은 추정하지 않았다. 계정·원본 선택, 빈 조건 복구와 자동 갱신 시 스크롤 보존도 통과했고 대상 HTTP는 0건이었다. [전 요약](rounds/execution-v1/round-5/before-focus/results/summary.json)·[후 요약](rounds/execution-v1/round-5/after-focus/results/summary.json)에서 비교할 수 있다. 캡처는 light 1024×720 API 카드 클릭 전후 2장만 보존했다. 실제 이미지 위치는 [라운드 증거 안내](rounds/README_KR.md)를 따른다. 사용자 선택만 결과로 이동하며 자동 스냅샷·비동기 집계 완료는 읽던 위치를 유지한다.

측정 범위와 검증 공백은 다음과 같다.

- 이 10회는 **운영 Swing·Montoya 어댑터·실제 LOOP HTTP 자동 검증**이다. native Burp 데스크톱, OS 클릭·파일 선택기, 브라우저 DOM·확장 프로그램, Katana·Docker·외부 서비스는 이 전체 흐름에서 실행하지 않았다. 사람이 수행한 사용성 조사나 전체 사이트의 안전성 검증으로 해석하지 않는다.
- 시간·할당량은 이 환경의 Java 17.0.21에서 관측한 값이다. EDT 할당량은 `ThreadMXBean`이 측정한 해당 UI 작업의 할당 bytes이며 **잔존 heap·프로세스 RSS·최대 메모리·배경 작업의 할당량이 아니다**. 3회는 스냅샷 적용/정렬 호출 내부를 측정하며 fixture 생성·대기·검증을 제외했다. 일반 네트워크 성능이나 전체 메모리 절감률로 확장하지 않는다.
- 전체 흐름 하네스는 대시보드에서 Alice 조건 선택을 시도하고 정확한 선택 원본의 전달을 검사하지만, **계정 조건이 실제 Alice로 선택됐는지와 모든 행의 계정을 직접 단정하는 검사가 없다**. 계정 필터 보존·다른 계정의 정확한 원본은 5회 집중 측정과 [SimpleDashboardRevealTest](../../../simple/burp-workbench/src/test/java/kr/workbench/SimpleDashboardRevealTest.java), [ScopeInspectionDashboardUiTest](../../../simple/burp-workbench/src/test/java/kr/workbench/ScopeInspectionDashboardUiTest.java), [SimpleDashboardIntegrationTest](../../../simple/burp-workbench/src/test/java/kr/workbench/SimpleDashboardIntegrationTest.java)의 별도 회귀 근거로 확인한다. 전체 흐름 115개가 이 공백까지 직접 검증했다고 표시하지 않는다.
- 프로젝트·ZIP 저장은 운영 직렬화 API와 임시 경로로 내용·복원을 검사했다. OS 파일 선택기 사용 완료를 의미하지 않는다. 인증정보를 가진 임시 런타임과 원시 ZIP은 공개 증거에 올리지 않고 삭제했다. 공개 텍스트의 비밀정보 점검은 휴리스틱이며 완전한 탐지 보장은 아니다.

최종 `python simple/dev.py release`는 **143개 회귀 그룹 5,179개 + payload HTTP136개 + Mapper Java145개 + helper Node29개 = 5,489개**를 통과했다. 위 라운드·집중·렌더 검사는 이 합계에 다시 더하지 않는다. 밝은/어두운 테마와 두 크기의 128개 화면·2,700개 컨트롤·568개 시나리오, 실제 Katana102개와 추가 LOOP 수명주기25개도 각각 통과했다.

Simple1.4.1 JAR·독립 소스 ZIP·helper ZIP을 새 버전 디렉터리에 생성했고 CRC·전체 소스 지문·체크섬 검증을 통과했다. 소스 ZIP을 별도 폴더에 풀어 strict Java17로 빌드한 JAR는 최종 5라운드 제품과 바이트가 같았다. 소유 LOOP 서버는 종료했고 포트18888의 닫힘을 확인했다. 세부 근거와 배포 해시는 **[VERIFICATION.json](VERIFICATION.json)**, [소스 재빌드](source-rebuild.json), [배포 안내](../../../simple/docs/RELEASE_SIMPLE_1.4.1_KR.md)에 있다. 이 커밋의 기록은 업로드 전 로컬 검증을 다룬다. 해당 PR의 정확한 head CI 결과와 main 병합 SHA는 GitHub PR 페이지를 기준으로 확인한다.
