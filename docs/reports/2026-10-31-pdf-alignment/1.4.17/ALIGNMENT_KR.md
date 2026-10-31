# Simple 1.4.17 · 승인 PDF와 실제 화면의 대조 항목

이 문서는 승인 시안의 구조를 운영 코드의 어느 화면·컴포넌트에서 확인해야 하는지 기록한 **대조 목록**입니다. 코드에 해당 컴포넌트가 존재한다는 사실과 실제 화면이 시안대로 표시·작동한다는 검증은 구분합니다. 최종 빌드·회귀·캡처·배포 검증의 결과나 통과 수는 이 문서에 기입하지 않습니다.

## 기준 원본과 작업 복구 범위

- 기준 PDF: [workbench-ux-flow-design.pdf](../../2026-10-31-workflow-design/workbench-ux-flow-design.pdf)
- PDF SHA-256: `1fe91f0bf6eb9a06204a687445b8ea0ebd2aec37e9f3a96bdaf61f9e92dda972`
- 화면별 원본: [라이트 시안](../../2026-10-31-workflow-design/images/light/) · [다크 시안](../../2026-10-31-workflow-design/images/dark/) · [HTML 원본](../../2026-10-31-workflow-design/prototype/) · [16개 화면 계약](../../2026-10-31-workflow-design/catalog.json)
- 보존된 구현 기준: `account-a/approved-workflow-implementation`의 `9bb8920c22ab932cab8022889f4b1b8ae03f62a5` / Simple 1.4.16.
- 작업 브랜치: `account-a/simple-1.4.17-completion`.

사용자가 제공한 대화 스크린샷에는 PDF와 다른 1.4.16 배치를 확인하고 1.4.17 수정·1차 비교 중 실행 환경이 오프라인으로 전환되었다는 기록이 있습니다. 현재 연결된 원격의 관련 구현·설계 브랜치에는 당시의 **1.4.17 수정 소스가 없었습니다**. 이 작업은 그 미저장 변경을 그대로 되살렸다고 주장하지 않고, 보존된 1.4.16과 위 승인 원본을 기준으로 차이를 다시 대조하여 구현합니다. 이번 메시지에 첨부된 것은 대화 스크린샷이므로, 이전 대화의 첨부 PDF 바이트와 현재 저장소 PDF가 같은지는 별도로 검증한 사실이 아닙니다.

기존 1.4.16의 회귀·캡처 통과 기록은 기존 기능과 화면 표시의 근거입니다. 그것만으로 승인 PDF와의 구조·배치 일치를 입증하지 않습니다. 기존 패키지·보고서·캡처를 새 결과로 덮어쓰지 않습니다.

## 공통 화면 계약

공통 셸은 Workbench 브랜드와 현재 도구의 위치를 알 수 있는 헤더, 분류별 왼쪽 탐색, 이름 있는 설정·Guide 접근, 도구 본문으로 구분합니다. 구현 확인 지점은 `FolderNavigation.java`의 `wb.navigation.brand`, `wb.navigation.breadcrumb`, `wb.navigation.location`, `wb.navigation.actions`와 `WorkbenchUi.java`·`WorkbenchSkin.java`·`VisualTheme.java`입니다.

카드 제목·본문·도구의 경계, 선택 탭, 행 높이, 입력·출력 영역, 여백을 각 화면의 시안과 함께 확인합니다. 메뉴 폭 하나만 바꾸거나 공통 색만 맞춘 것을 화면별 배치 완료로 판단하지 않습니다. 작은 창에서 재배치가 필요할 때도 주 동작·중지·현재 선택·실제 편집 영역은 읽고 조작할 수 있어야 합니다.

시안의 URL·계정·개수·CVE·응답 본문은 합성 예시입니다. 운영 화면의 실제 프로젝트·선택 원본·실행 결과·미확인 상태를 그 예시와 같게 조작하지 않습니다. 실제 값의 길이, 빈 상태, 비활성화 사유, 추가 기능 때문에 생기는 차이는 기능 보존과 연결하여 설명합니다.

## 16개 화면 연결 목록

표의 경로는 저장소 루트 기준입니다. `anchor`는 Swing 컴포넌트의 `name`이며, 화면 이름이나 캡처 파일 이름만 같다는 이유로 대조를 완료 처리하지 않습니다.

| # / 시안 키 | 시안에서 확인할 구조와 주 동작 | 운영 구현·anchor | 보존해야 하는 실제 동작·자료 차이 |
|---|---|---|---|
| 01 `traffic` 요청·응답 목록 | 대상·계정·수집 조건 한 줄 → 검색·빠른 필터 → 요청 목록 → 선택 method/path·상태·계정·시간 → 요청/응답 카드. **Repeater 준비** 강조, 다른 연결은 요청 활용 메뉴. | `simple/burp-workbench/src/main/java/kr/workbench/WorkbenchExtension.java`, `TrafficBrowser.java`, `PacketInspector.java`; `wb.actions.traffic.context`, `wb.actions.traffic.search`, `wb.simple.traffic.selection`, `wb.placement.traffic.selection`, `wb.workflow.traffic.use`, `wb.traffic.messages`. | 상태·계정·시각은 선택된 실제 원본에서 표시합니다. 정렬·필터 후 같은 URL의 다른 요청과 혼동하지 않아야 합니다. 중요 표시·메모·목록 고정·기존 설정은 메뉴와 원래 컨트롤러로 보존합니다. 준비는 실제 전송이 아닙니다. |
| 02 `mapper` 기능 자동 매핑 | 프로젝트 문맥 → 기능 트리/기능 편집 → 검색·실행·세션 조건 → 선택 기능의 요청 목록 → 요청/응답 또는 분류 근거. **분류 수정** 강조. | `simple/feature-mapper/src/main/java/kr/featuremapper/FeatureMapperWorkspace.java`, `MapperUi.java`; `wb.actions.mapper.context`, `wb.actions.mapper.packets`, `wb.placement.mapper.selection`, `fm.workflow.request.options`. | 트리의 실제 기능·실행·세션·미분류 개수는 프로젝트 모델을 따릅니다. 기존 프로젝트 열기/저장, 업무 연결, 원본 연결을 삭제하지 않습니다. 원본 요청·응답 바이트는 읽기 모드나 분류 변경으로 바뀌지 않아야 합니다. |
| 03 `js` JS에서 API 찾기 | 소스 문맥 → 왼쪽 경로/유형·검색·목록 → 오른쪽 선택 경로·**Repeater 준비** → 호출 소스 카드·파일/행 번호 → 접힌 분석 근거·탐색. | `simple/burp-workbench/src/main/java/kr/workbench/JsAnalysisPanel.java`, `JsResultsView.java`; `wb.js.findings`, `wb.workflow.js.selection`, `wb.pdf.js.call.source`, `wb.pdf.js.call.location`, `wb.workflow.js.navigation`, `wb.workflow.js.evidence`. | 코드·파일명·호출 행은 실제 선택 결과에 연결합니다. 메서드 미확정·실행 미확인 등 기존 상태와 근거는 유지합니다. 긴 소스 URL·추가 분석 유형·원문 탐색은 접근 가능한 메뉴·접힘 정보로 보존하고, 시안 예제 소스를 운영 데이터에 삽입하지 않습니다. |
| 04 `environment` 서버·경로 탐색 | 대상·계정 → 전체 폭의 접힌 관찰 환경 → 왼쪽 경로 후보/검색·일괄 작업 → 오른쪽 선택 경로 카드와 **선택 경로 GET 확인**·일시정지·중지 → 응답/요청/판정 근거. | `simple/burp-workbench/src/main/java/kr/workbench/EnvironmentPanel.java`; `wb.environment.observations`, `wb.pdf.environment.candidate.count`, `wb.actions.environment.candidates`, `wb.flow.environment.inspector`, `wb.placement.environment.execution`, `wb.environment.messages`. | 시안의 HTTP 200은 예시입니다. 실제 미검증·저장된 판정·현재 계정의 원본 유무를 구분합니다. GET은 직접 선택한 때만 전송합니다. 후보 설정·일괄 실행·재개·비교 조건을 숨겨 없애지 않고 메뉴·기존 설정으로 유지합니다. |
| 05 `exposure.search` 외부 노출·검색 | 대상·검색 탭 → 검색 분류/검색식/복사·**Google에서 열기** → 가져온 URL 목록 → 선택 URL·미확인 상태·분류/SCOPE/발견 경로/검색식 사실표 → Repeater/복사 → 접힌 발견 원문. | `simple/burp-workbench/src/main/java/kr/workbench/ExposureResearchPanel.java`; `wb.exposure.query`, `wb.flow.exposure.search.masterdetail`, `wb.pdf.exposure.discovery.url`, `wb.pdf.exposure.discovery.facts`, `wb.actions.exposure.search.detail`, `wb.pdf.exposure.discovery.original`. | 가져온 URL은 실제 노출을 확인한 결과가 아닙니다. 검색식과 가져온 시각을 원문에 보존합니다. 정렬 후에도 사실표·복사·Repeater가 같은 URL을 사용하고 현재 SCOPE를 재확인해야 합니다. Google 열기와 대상 요청 전송을 구분합니다. |
| 06 `exposure.cve` 외부 노출·CVE | 관측 제품/수정·**CVE 후보 조회**/취소 → 필터·조회 상태 → 후보 표 → 선택 CVE/점수/조건 판정 → 대상 적용 조건·관측 근거 탭 → 영향 범위/원격 인증/모듈/백포트/수정 버전 사실표 → 출처·복사. | `simple/burp-workbench/src/main/java/kr/workbench/ExposureResearchPanel.java`; `wb.actions.exposure.cve.lookup`, `wb.actions.exposure.cve.filters`, `wb.pdf.exposure.cve.metrics`, `wb.flow.exposure.cve.detail.tabs`, `wb.pdf.exposure.cve.facts`, `wb.pdf.exposure.cve.conditions`, `wb.pdf.exposure.cve.references`. | 공급자·현재 제품·조회 시각·KEV/EPSS 미확인·버전 조건을 실제 결과대로 표시합니다. EPSS 등 기존 유효한 추가 열과 출처 선택은 유지합니다. 영향 범위 끝값을 수정 버전으로 만들어 내지 않습니다. 전체 적용 조건 설명과 원본 ID는 접힌 정보·관측 근거·복사 내용에 보존합니다. |
| 07 `auth` 계정 권한 비교 | 역할별 신원 요약·관리 → 기록 필터·검색과 **비교 준비**/중지 → 비교 기록 → 선택 요청과 계정별 응답/차이 리더. | `simple/burp-workbench/src/main/java/kr/workbench/AuthComparePanel.java`, `AuthInlinePreview.java`; `wb.auth.account.cards`, `wb.actions.auth.context`, `wb.placement.auth.execution`, `wb.auth.history`, `wb.auth.inline.selected`, `wb.auth.inline.tabs`. | 실제 비로그인/계정별 연결·정책·쓰기 조회·미검증 상태를 구분합니다. 첫 계정을 검증된 기준으로 가정하지 않습니다. 기존 세션·인가·쓰기 조건과 중지 기능은 실제 준비창·관리 메뉴에 보존합니다. |
| 08 `response.recon` 응답 보안 분석 | 관찰/API 문서/저장·조회 유형과 분석 관리 → 관찰 목록 → 선택 관찰·짧은 문맥·**증거로 정리** → 원문 대조 카드의 응답/요청/영향 URL/분석 근거. | `simple/burp-workbench/src/main/java/kr/workbench/ResponseReconPanel.java`, `PacketInspector.java`; `wb.flow.response.observations`, `wb.placement.response.finding`, `wb.card.pdf.response.reader`, `wb.response.finding.pages`, `wb.actions.response.finding`. | 문자열·헤더 관찰은 취약점 확정이 아닙니다. 분석 근거에는 실제 선택 관찰의 원문을 유지하고, API 초안·마커/쓰기 조회·재시도는 기존 유형이나 관리 메뉴로 보존합니다. 응답/요청의 보기·검색·복사는 같은 PacketInspector와 실제 바이트를 사용합니다. |
| 09 `payload` 입력값 검사·요청 준비 | 단계 표시와 **검사 시작**/중지 → 대상·계정 요약 → 넓은 검사 요청 편집기와 편집 작업 → 오른쪽 검사 조건·후보 수·접힌 후보/고급 조건. | `simple/burp-workbench/src/main/java/kr/payloadrunner/PayloadRunnerPanel.java`; `wb.payload.step.prepare`, `wb.placement.payload.execution`, `wb.placement.payload.request`, `wb.flow.payload.preparation`, `wb.flow.payload.conditions`, `wb.payload.candidates`, `wb.payload.advanced`. | 실제 작성 요청·선택 입력 위치·시험 문자열·계정·SCOPE·요청 예산을 보존합니다. 후보·인코딩·적응형/대조·간격 등 기존 실행 조건을 시각 단순화를 이유로 삭제하지 않습니다. 편집·단계 이동·접힘만으로 검사하지 않습니다. |
| 10 `data` 데이터 변환·비교 | 인코딩 변환/JWT/비교 탭 → 변환 방식·**변환** → 입력/출력 카드 → 출력의 복사·출력 작업. | `simple/burp-workbench/src/main/java/kr/workbench/WorkbenchExtension.java`; `wb.actions.data.codec.input`, `wb.actions.data.codec.output`, `wb.codec.input`, `wb.codec.output`, `wb.codec.messages`, `wb.actions.data.jwt.input`. | 변환은 로컬 동작입니다. 입력·출력을 따로 보존하고 출력→입력은 직접 선택했을 때만 반영합니다. JWT·데이터 비교의 원문 가져오기·정확한 값 비교·기존 편집 동작을 유지하며 시안의 예시 문자열을 기본 실제 결과로 만들지 않습니다. |
| 11 `activity` 점검 진행 현황 | 추적/SCOPE 문맥·기록 → URL/미확정/계정/업무 탭 → URL 검색·상태 필터·목록 → 선택 URL·발견 근거·완료 표시 → 입력 목록과 입력 완료. | `simple/burp-workbench/src/main/java/kr/workbench/ActivityPanel.java`; `wb.placement.activity.filters`, `wb.flow.activity.selection`, `wb.flow.activity.inspector`, `wb.activity.selected.facts`, `wb.actions.activity.parameters`. | 실제 URL 전체 완료와 개별 입력 완료는 다른 기록입니다. 발견 근거·Repeater·완료 취소와 계정/업무별 상태를 유지합니다. 정렬·필터로 view 행이 바뀌어도 같은 URL·입력 ID에 작업해야 합니다. |
| 12 `bounty` 증거 정리 | 문서 제목/문서 작업·**증거 ZIP 저장** → 왼쪽 검색·증거 목록·항목 작업 → 가운데 단일 **보고서 작성** 카드(선택 근거/실제 영향/재현 절차) → 오른쪽 원문 대조 응답/요청·접힌 원본 정보. | `simple/burp-workbench/src/main/java/kr/workbench/BountySupportPanel.java`; `wb.flow.bounty.document`, `wb.bounty.evidence.list`, `wb.bounty.evidence.report`, `wb.bounty.evidence.field.impact`, `wb.bounty.evidence.field.repro`, `wb.bounty.evidence.source.card`, `wb.bounty.evidence.packet.tabs`, `wb.bounty.evidence.source.info`. | 보고서 작성 카드를 영향/재현의 별도 바깥 패널 두 개로 대체하지 않습니다. 기존 작성 원문·항목 이름·메모·순서·undo/redo·원본 ID·가림 설정을 유지합니다. 선택 변경·필터·보기 전환만으로 작성 내용을 바꾸거나 저장하지 않습니다. |
| 13 `dashboard` 점검 대시보드 | 실행·대상 요약·통합 점검 열기 → 여섯 분류 카드 → 계정/중요도 조건 → 결과 목록 → 선택 항목 사실/근거 요약·**원본 보기**/다음 작업 → 접힌 원본·처리 정보와 분포. | `simple/burp-workbench/src/main/java/kr/workbench/ScopeDashboardPanel.java`; `wb.scope.dashboard.cards`, `wb.actions.dashboard.filters`, `wb.scope.dashboard.results`, `wb.scope.dashboard.detail.facts`, `wb.scope.dashboard.detail.summary`, `wb.scope.dashboard.detail.origin`, `wb.actions.dashboard.selection`, `wb.flow.dashboard.distribution`. | 실제 실행 스냅샷의 개수·한도·미확인·원본 가용성을 표시합니다. 시안의 카드 숫자·선택 예시와 같게 맞추지 않습니다. 분류/계정/중요도 필터와 행 선택의 원본 연결, 별도 원본·증거 준비를 보존합니다. |
| 14 `payload.results` 입력값 검사·실행 결과 | 결과 단계 상단의 **결과를 증거로**·보조 검사 관리·중지 → 전체/관측/대조 필터 → 실제 번호·검사값·HTTP·크기·응답 시간·관측 결과 → 선택 결과 요약과 요청/응답. | `simple/burp-workbench/src/main/java/kr/payloadrunner/PayloadRunnerPanel.java`; `wb.actions.payload.results`, `wb.payload.results.filter.all`, `wb.payload.results.filter.observed`, `wb.payload.results.filter.control`, `wb.placement.payload.results`, `wb.flow.payload.reason`, `wb.payload.workspace`. | 응답 시간은 엔진이 기록한 수치를 사용합니다. 기준·대조·관측 분류는 검사값 문자열 추정이 아닌 실제 결과 metadata를 따릅니다. 필터 뒤 선택·증거·Repeater·CSV는 같은 원본 결과를 사용합니다. 반사 관찰과 실제 브라우저 실행 확인을 구분합니다. |
| 15 `bounty.replay` 요청 재현 | 프로젝트/워크플로 문맥·**순차 실행**/중지 → 왼쪽 작성 요청·추가/변수·기대 조건 → 오른쪽 실행 결과·선택 단계 요청/응답·증거 연결. | `simple/burp-workbench/src/main/java/kr/workbench/BountySupportPanel.java`; `wb.flow.replay.context`, `wb.actions.bounty.flow.execution`, `wb.actions.bounty.flow.request`, `wb.bounty.flow.inputs`, `wb.bounty.flow.results`, `wb.bounty.flow.packet.tabs`, `wb.actions.bounty.flow.results`. | 실제 작성 CRLF/바이트, 변수·HTTP/조회 기대 조건, 단계 순서와 실패 시 중단을 유지합니다. 기대 조건의 UI 예시는 실행 성공을 뜻하지 않습니다. 실제 실행은 명시 동작이며 원문 선택·증거 연결은 전송하지 않습니다. |
| 16 `auth.accounts` 인증 후보 연결 | 대상 Origin을 한 번 표시 → 기본 역할별 후보·연결 상태를 한 행에 정렬 → 접힌 추가 계정 → 닫기. **이 구성과 함께 실제 준비창 전체를 확인**. | `simple/burp-workbench/src/main/java/kr/workbench/AuthComparePanel.java`; `wb.auth.preparation.accounts`, `wb.auth.preparation.role.0` 등 역할 행, `wb.auth.preparation.connection.0`, `wb.auth.preparation.tabs`, `wb.auth.preparation.close`, `wb.auth.preparation.start`. | 실제 준비창의 **1 적용 상태 / 2 계정·쓰기 조회 / 3 세션·권한표**를 보존합니다. 시안의 후보 연결 부분만 캡처하여 전체 준비창과 같다고 주장하지 않습니다. Origin·역할 연결·정책·세션 설정은 실제 상태로 표시하고, 추가 계정을 접어도 기존 선택을 지우지 않습니다. |

## 실제 화면 대조 시 확인할 사항

각 화면의 라이트·다크 캡처를 **해당 시안 키**에 연결하여 확인합니다. 비교할 때 원본 이미지, 운영 캡처, 운영 JAR 식별자, 실제 창 크기·테마·선택 상태를 함께 기록해야 합니다. 본 목록의 체크박스는 수행 결과를 나타내지 않습니다.

- [ ] 16개 화면 모두 전체 화면을 확인하고 헤더·본문·툴바·카드·탭·주 동작·접힘 정보 위치를 대조합니다.
- [ ] 화면 번호 09/14의 요청 준비·실행 결과를 별도로 확인합니다. 응답 시간 열과 필터가 실제 결과를 사용하고 필터 후 원본 연결이 유지되는지 확인합니다.
- [ ] 화면 번호 12는 목록/보고서 작성/원문 대조 3열을 확인합니다. 가운데 단일 보고서 카드와 실제 영향·재현 편집 내용이 함께 유지되는지 확인합니다.
- [ ] 화면 번호 16은 인증 후보 행뿐 아니라 준비창의 세 탭 전체를 확인합니다. 후보 연결·세션·권한표·쓰기 조회를 화면 정리 명목으로 제거하지 않았는지 확인합니다.
- [ ] 작은 창·빈 목록·선택 없음·긴 실제 값·메뉴/접힘 확장·정렬/필터 후 선택을 확인합니다. 주 동작과 취소/중지의 가용성·인접 배치를 확인합니다.
- [ ] 추가 기능은 원래 컨트롤러·동일 모델에 연결되어 있어야 합니다. 캡션·색·좌표만 같은 가짜 버튼이나 결과 값을 사용하지 않습니다.
- [ ] 원본 ID/요청·응답 바이트/작성 편집값/가림·undo/계정·SCOPE·수명주기 검사를 화면 대조와 별도로 확인합니다.

## 검증과 주장 범위

`SimplePdfResearchLayoutTest` 등 화면별 회귀는 실제 컨트롤·선택 모델·접힘 상태와 데이터 보존을 확인하기 위한 검사입니다. 전체 회귀 통과가 시안과의 시각적 일치를 자동으로 의미하지는 않습니다. 캡처는 실제 운영 Swing 컴포넌트와 합성 Burp 어댑터를 사용한 경우 그 범위를 명시합니다.

Linux Swing 캡처, 별도 Linux 창 관리자에서의 실제 창 검증, 사용자의 Windows Burp 본체·DPI/IME·브라우저 수동 조작은 다른 검증입니다. 실행하지 않은 범위를 통과로 표현하지 않습니다. 최종 검증 기록과 배포 artifact의 체크섬·소스 지문은 이 대조 문서와 별도의 결과 파일에서 확인해야 합니다.
