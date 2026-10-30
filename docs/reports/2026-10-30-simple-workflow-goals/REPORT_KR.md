# Simple 1.4.2 · 실사용 목표 후속 검증

기준은 main `65ae9273d3b58d47ddf7e3b96d0e118875bb15b3`의 게시된 Simple1.4.1이다. 새 기능 수나 버튼 수 대신 **대상·계정을 바꾸어 확인하기, 수신 중 원본 읽기, 입력 오류를 고쳐 재시도하기**의 전후를 비교한다. 사용자가 선택한 원본 LOOP3.10.0 격리 실습 환경과 운영 Swing·Montoya 어댑터를 사용한다.

**최종 1.4.2 운영 JAR에서 같은 고정 프로브의 전후 비교와 전체8개 도구의 새 실행5회가 통과했다.** 대상·계정을 선택해 경로 하나를 확인하는 필수 동작은6→4개, 설정 창은2→0개였다. 전체 릴리스 검사5,570개와 화면·실제 Katana·배포 묶음 생성도 완료했다. 독립 소스 ZIP 재빌드도 배포본과 바이트 단위로 일치했다. 실제 PR head의 원격 CI와 main 병합 근거는 GitHub PR 페이지에서 확인한다. 최종 릴리스 근거는 [VERIFICATION.json](VERIFICATION.json)에 기록한다.

## 목표와 실제 작업

| 사용자 목표 | 재현한 작업과 1.4.1의 불편 | 최종 JAR에서 확인한 동작 |
|---|---|---|
| 같은 작업을 더 적은 조작으로 완료 | 서버·경로에서 대상 설정 열기→관측 대상 선택→후보 설정 열기→비로그인 계정 선택→경로 행 선택→직접 GET, 필수 **6개 동작** | 같은 두 기존 선택기를 기본 화면에서 사용해 **4개 동작**으로 완료했다. 창 닫기·텍스트 입력·OS 클릭을 임의로 더하지 않는다. |
| 복잡한 설정·절차 감소 | 이미 관측한 대상과 준비된 계정으로 경로 하나를 확인하는 데 설정 창 **2개**를 연다. 현재 검증 계정도 기본 화면에서 보이지 않는다. | 이 작업의 설정 창 진입은 **0개**였다. 계정 선택만으로 전송하지 않으며 GET은 직접 실행한다. |
| 일상 기능과 관리 기능 구분 | 대상·계정 선택이 고급 후보 조건과 함께 별도 창에 들어 있다. | 대상·계정은 일상 작업 행에서 확인하고, 예산·간격·타임아웃·기준 응답·동적 필드·후속 탐색은 후보 설정, 저장·불러오기·이력·초기화는 더보기에서 관리한다. 설정 창을 열어도 일상 선택기가 그 안으로 다시 이동하지 않는 회귀도 검사한다. |
| 속도·할당량·반응성·복구 개선 | 같은 관측이나 다른 대상의 관측이 와도 표와 요청·응답 문서를 다시 만들고 읽기 선택을 지운다. URL 대상 준비 오류는 화면에 없고, 검색 준비 중 완료 상태가 잠시 공개된다. | 같은 관측의 표·문서 이벤트가0이 되고 읽기 선택을 유지했다. 오류는 입력 창에서 확인·교정했으며, 강제로 보류한 준비·작업자 구간에서는 완료 상태를 공개하지 않았다. |

대상·계정 선택의 전후 필수 동작은 [Environment 프로브](../../../audit/simple-workflow-usability/EnvironmentWorkflowProbe.java)가 실제 운영 컨트롤을 호출한 기록이다. 기준 작업은 계정 목록에서 비로그인을 선택한다. 인증 계정의 Origin·세션 검사, 계정별 원본 격리·SCOPE·요청 예산의 보존은 별도 회귀와 전체 흐름의 확인 조건이며 이 조작 수만으로 검증됐다고 표시하지 않는다.

## 고정 프로브의 전후 측정

최종 비교는 `paired-v2/*-before`와 `paired-v2/final/*-after`다. 중간 후보의 `paired-v2/*-after`, 사전 조사·private override·이전 실행을 최종 결과로 대신하지 않는다. [고정 프로토콜](paired-v2/protocol.json), [복사 파일 해시](paired-v2/ARCHIVE_INDEX.json), 각 실행의 metadata와 결과 JSON을 보존했다. 세 기준 실행의 제품 JAR SHA-256은 모두 `ebda707fd762dcac91f641c7f149a6bf5e52865008d0f70e8861e502d8a5bbf1`이며, 어댑터 tree SHA는 `8d92faf6399ce00769729f29d8d78518c991c8d2bae32ca814598bc24e0392e5`로 같다. 실행기·프로브·제품 입력의 사후 불변과 비공개 임시 런타임 정리도 확인했다. 최종 개선 후 세 프로브는 모두 JAR SHA-256 `a10a6cfb2a51a4eb96ffafd07f7e31ec56df9d1a23158402b75de4ef65879bd8`를 실행했다. 전후 실행기·각 프로브·어댑터·의존성의 해시가 일치하며, 각 실행의 입력 불변·출력 검토·임시 공간 정리 조건도 통과했다.

환경 프로브는 임시 loopback 서버에서 두 관측 응답을 받은 뒤, 사용자가 선택한 경로에 직접 GET 1회를 실행한다. 실제 fixture HTTP는 총3회이며 그중 운영 전송을 통한 명시적 GET이1회다. 측정 구간에서는 이 응답들로 준비한 관측 메타데이터를 20번 재적용한다. 20개의 새 HTTP 요청이나 사이트 경로 20개를 탐색한 결과가 아니다.

각 종류를 1회 예열하고 5회 측정했다. 표의 시간·할당량은 **관측 메타데이터20회 처리 구간의 중앙값**이다. fixture 준비·네트워크 응답 시간·사람의 읽기 시간은 포함하지 않는다. 전후 프로브와 어댑터·의존성·JDK17을 고정하고 다른 빌드나 성능 프로브와 겹치지 않게 실행한다.

| 작업·지표 | 고정 1.4.1 기준 | 최종 1.4.2 JAR 결과 |
|---|---:|---|
| 대상·계정 선택 후 단일 GET 필수 동작 | 6개 | 4개 |
| 위 작업의 설정 창 열기 | 2개 | 0개 |
| 같은 대상 관측20회 EDT 중앙값 | 131.403911ms | 0.066354ms |
| 같은 대상 관측20회 EDT 할당량 중앙값 | 84,195,320 bytes | 3,200 bytes |
| 다른 대상 관측20회 EDT 중앙값 | 163.308880ms | 0.060056ms |
| 다른 대상 관측20회 EDT 할당량 중앙값 | 113,799,336 bytes | 3,200 bytes |
| 각 표본의 경로 표·기술 표 이벤트 | 460건·40건 | 각각0건 |
| 각 표본의 요청·응답 문서 이벤트 | 각각120건 | 각각0건 |
| 읽던 요청·응답 선택 유지 | 10개 표본 모두 실패 | 10개 표본 모두 유지 |
| 정확한 원본 유지·관측 재적용의 자동 HTTP | 10개 표본 모두 유지·0회 | 10개 표본 모두 유지·0회 |

[Environment 전](paired-v2/environment-before/results/result.json)·[최종 후](paired-v2/final/environment-after/results/result.json)에는 5개씩의 개별 표본과 최댓값이 있고, [전 metadata](paired-v2/environment-before/metadata.json)·[후 metadata](paired-v2/final/environment-after/metadata.json)에는 실제 실행 JAR·소스·어댑터·의존성 해시와 명령이 있다. 이 수치는 같은 메타데이터 또는 활성 대상과 무관한 관측의 불필요한 재계산을 생략한 경로다. 실제 새 후보·판정·원본·계정 조건의 변경을 생략해 얻은 수치로 해석하지 않는다. 전후 fixture의 생성 규칙과 응답 길이는 같지만 실행별 응답 해시는 달라 바이트가 완전히 같은 응답의 벤치마크라고 표시하지 않는다. 작은 표본의 시간 진단을 모든 작업의 속도 개선이나 실제 Burp 지연 보장으로 확대하지 않는다.

## 실패를 고치고 다시 작업하기

URL 대상 준비는 입력된 초안에서 컨트롤을 작동시키는 횟수를 센다. 버튼만 사용하는 준비는 기준에서1회다. Enter를 먼저 누르는 경로에서는 Enter가 동작하지 않아 버튼을 추가로 눌러 **2회**였다. 최종 JAR에서는 같은 Enter 경로를 **1회**로 완료했으며, 사람이 실제로 누른 마우스·키보드 수를 관찰했다는 뜻이 아니다. 준비 단계는 대상 수집이나 GET을 자동 시작하지 않는다.

| 조건 | 1.4.1 기준 관측 | 최종 1.4.2 JAR 결과 |
|---|---|---|
| 잘못된 대상의 오류를 같은 입력 창에서 읽기 | 이름 있는 오류 컴포넌트·보이는 오류 영역 없음 | 현재 입력 창 안의 오류 영역 표시 |
| 8개 대상 뒤9번째 준비의 상한 안내 | 보이는 오류 영역 없음, 기존8개 대상은 보존 | 같은 창에 상한 안내·기존8개 대상 보존 |
| 오류 후 같은 창에서 정상 입력 재시도 | 준비 가능하나 실패 원인의 화면 안내 없음 | 오류 확인 후 같은 창에서 교정·준비 성공 |
| Enter로 대상 준비 | 미동작, 버튼 fallback 필요 | Enter1회로 준비, fallback 없음 |
| 종료 뒤 남아 있던 준비 버튼·직접 open | 각 경로에서 대상 탭1개를 다시 생성 | 두 경로 모두0개, 늦은 Enter도 재생성 없음 |
| 적용하지 않은 초안·현재 대상·기존 원본 | 보존 | 보존 |
| 대상 준비·오류·재시도의 대상 HTTP | 0회 | 0회 |

[Passive 전](paired-v2/passive-before/results/result.json)·[최종 후](paired-v2/final/passive-after/results/result.json)는 오류 텍스트 필드가 존재하는지만 보지 않고 현재 대상 창의 실제 컴포넌트 조상과 보이는 영역도 확인한다. 진단 기록은 기준에서도 남지만 입력 창의 피드백을 대신하지 못했다. 새 종료 처리는 대기 중인 UI 동작과 외부 스레드 종료를 함께 확인해 교착 없이 정리되는지도 회귀로 검사한다. 대상 준비 관련 집중 재검증은 새 회귀30개와 기존 회귀121개가 통과했다. 이 중첩된 집중 검사151개를 전체 회귀 합계에 다시 더하지 않는다.

## 검색 준비 상태와 최초 전체 실행 실패

새로 실행한 1.4.1 전체 흐름은 **107개 통과·2개 실패**, 8개 핵심 도구 중7개 완료였다. 계정 비교의 `vuln` 원본을 History에서 가져온 뒤 정확한 원본이 검색 표에 보인다는 검사가 실패했고, 전체8개 완료 검사도 실패했다. 엔진 HTTP39회와 별도 로그인 준비2회를 기록했다. [실패 요약](baseline-1.4.1/summary.json)·[실행 metadata](baseline-1.4.1/metadata.json)를 보존했다. 요청 수가 적어진 것은 작업이 중간에 실패한 결과이며 성능 개선으로 읽지 않는다.

별도 결정적 프로브에서는 검색 조건의 identity supplier를 latch로 잠시 보류했다. **준비 구간 하나**에서 교차 스레드로 완료 상태를1,000번 읽었을 때 기준은1,000번 모두 `idle=true`였고 이전 원본의 검색 표가 남아 있었다. 작업자 계산 구간에서는 `idle=true`가0번이었고, 최종 반영 뒤 정확한 새 원본 선택은 성공했다. HTTP는0회였다. [Readiness 기준 결과](paired-v2/readiness-before/results/result.json)·[metadata](paired-v2/readiness-before/metadata.json)에 이 상태를 구분해 기록했다.

이 결과는 준비 중 완료 상태 공개의 결함을 재현한 것이다. **최초 전체 실행 실패가 같은 원인으로 발생했다고 확정하지 않으며, 원본 유실의 증거도 아니다.** 해당 프로브의 `result=PASS`는 관측 절차가 끝났다는 뜻이고 `idleContractPassed=false`가 실제 제품 계약의 실패를 나타낸다. 1,000회 읽기를 독립적인1,000개 시험으로 합산하지 않는다. [최종 개선 후 결과](paired-v2/final/readiness-after/results/result.json)에서 준비 구간의 잘못된 `idle=true`는 **0/1,000회**였고, 작업자 구간도0회였다. 최종 화면 반영 뒤 정확한 새 원본 선택·HTTP0은 유지했다.

## 최적화 중 발견한 계정 조건 회귀

중간 1.4.2 캐시 구현에서, 같은 계정 슬롯의 identity 검사 조건을 외부에서 바꾼 뒤 동일 메타데이터를 받으면 이전 계정 조건의 원본·상태가 남는 회귀를 발견했다. 이 중간 결과를 최종 개선 실적으로 사용하지 않았다. 최종 구현은 선택한 계정 조건의 변경도 확인해, 같은 대상의 동일 관측이나 다른 대상 관측이 오면 수동 새로고침 없이 이전 원본과 상태를 비운다. 새 계정 조건의 원본은 올바르게 다시 표시한다.

[환경 작업 회귀](../../../simple/burp-workbench/src/test/java/kr/workbench/SimpleEnvironmentWorkflowTest.java)의 해당 두 경로를 포함한32개와 기존 `EnvironmentPanelTest`6개·`EnvironmentUpgradeTest`72개·`EnvironmentBatchTest`23개, **집중133개가 통과**했다. 이는 부분 회귀이며 최종 전체 릴리스 검사나 독립적인133개 신규 기능으로 합산하지 않는다. 최종 전후 표와 아래 전체 흐름은 모두 이 보강이 반영된 JAR `a10a6cfb…879bd8`로 실행했다.

## 전체8개 도구의 안정성 재확인

최종 JAR와 동결된 기존 whole 하네스로 **새 JVM·임시 프로필의 전체 흐름5회**를 실행했다. 매번 요청·응답, Mapper, 계정 비교, JS 분석, 서버·경로, 응답 분석, payload, 재현·증거의8개 도구를 모두 수행했다. 각 실행은115개 통과·실패0, 엔진 GET42회와 별도 로그인 준비 POST2회를 기록했다. 정확한 원본과 증거 전달·명시적 전송·종료를 확인하고 비공개 임시 런타임을 삭제했다.

| 안정성 확인 실행 | 핵심 도구 | 검사 통과 / 실패 | 엔진 GET + 로그인 준비 POST | 원본 store UUID 앞부분 |
|---|---:|---:|---:|---|
| [1회](whole-after-final/run-1/results/summary.json) | 8개 | 115 / 0 | 42 + 2 | 95fdbd67 |
| [2회](whole-after-final/run-2/results/summary.json) | 8개 | 115 / 0 | 42 + 2 | cba20d10 |
| [3회](whole-after-final/run-3/results/summary.json) | 8개 | 115 / 0 | 42 + 2 | 9c89d3f9 |
| [4회](whole-after-final/run-4/results/summary.json) | 8개 | 115 / 0 | 42 + 2 | 9da90bbb |
| [5회](whole-after-final/run-5/results/summary.json) | 8개 | 115 / 0 | 42 + 2 | affb71c3 |

[합산 기록](whole-after-final/summary.json)과 [복사 해시·전체 UUID](whole-after-final/ARCHIVE_INDEX.json), 각 폴더의 metadata를 대조하면5개의 원본 store UUID·검사 요약 해시가 서로 다르고 실제 실행 제품은 같다. 동결 whole 소스 SHA는 `f3cfa5f91fd2435e0446d1e2da96919537df1eef706231b96821dc711f05aed4`, 어댑터 tree SHA는 전후 프로브와 같은 `8d92faf6…0392e5`다. **이5회는 최종 구현의 안정성 확인이며 새로운 개선 전후5라운드나575개의 고유 검사가 아니다.** 최초 기준 실패는 그대로 보존하며, 이후5회 통과만으로 당시 실패 원인을 확정하거나 모든 비결정적 오류가 사라졌다고 주장하지 않는다.

## 최종 확인과 한계

최종 `python simple/dev.py release`는 strict Java17에서 **146개 회귀 그룹의5,260개 + payload HTTP136개 + Mapper Java145개 + helper Node29개 = 5,570개**를 통과했다. 실제 `check-summary.json`은1.4.2·`subset=false`·`failedGroups=[]`를 기록했고, 새 배포 디렉터리가 생성됐다. 변경 관련 집중 검사와 위5회 whole의 반복 검사는 이 합계에 다시 더하지 않는다.

[기본 화면 검사](../../../simple/docs/images/1.4.2/layout-report.json)는60뷰·1,364개 컨트롤·256개 시나리오, [통합 점검 화면 검사](../../../simple/docs/images/1.4.2/scope-layout-report.json)는68뷰·1,344개 컨트롤·312개 시나리오를 통과했다. 총128뷰·2,708개 컨트롤·568개 시나리오에서 잘린 컨트롤·접힌 영역·실패 시나리오·누락 캡처가 없었다. [실제 Katana 검사](../../../simple/docs/verification/1.4.2/katana-native/katana-verification.json)102개도 통과했다. 이 검사들이 기록한 제품 SHA는 최종 프로브·whole과 같다.

새1.4.2 배포 묶음은 CRC·전체 소스 지문·체크섬 확인을 거쳐 생성됐다. 기존 배포 파일은 보존했다. [전체 체크섬](../../../simple/downloads/1.4.2/SHA256SUMS)과 [manifest](../../../simple/downloads/1.4.2/manifest.json)에서 파일과 입력 지문을 확인할 수 있다.

| 새 배포 파일 | 크기 bytes | SHA-256 |
|---|---:|---|
| [설치 JAR](../../../simple/downloads/1.4.2/burp-workbench-simple-1.4.2.jar) | 4,594,549 | `a10a6cfb2a51a4eb96ffafd07f7e31ec56df9d1a23158402b75de4ef65879bd8` |
| [독립 소스 ZIP](../../../simple/downloads/1.4.2/burp-workbench-simple-1.4.2-source.zip) | 73,310,035 | `ead4b6a1d5ce457de0982bf9414d60d3e516a0e48b28f8abc24900f1af48090d` |
| [helper0.4.1 ZIP](../../../simple/downloads/1.4.2/feature-mapper-browser-helper-0.4.1.zip) | 34,685 | `11fe3655c8dd7d7ab53714f5d01fb1b1c392a8db8be776f52ac7e975912698d2` |

<!-- LOCAL_RELEASE_GATES_COMPLETE_REMOTE_PROVENANCE_ON_PR -->
최종 JAR의 전후 비교·전체8개 도구의5회 안정성 확인·전체 릴리스·화면·Katana·배포 지문 검증을 완료했다. 독립 소스 ZIP을 별도 폴더에서 strict Java17로 빌드한 JAR도 배포본과 바이트 단위로 일치했다. [소스 재빌드](source-rebuild.json), [전체 검사·배포 지문](VERIFICATION.json), [패키지 검사 로그](package-verification.txt)를 따른다. 이 커밋은 업로드 전 로컬 완료 근거이며 실제 PR head의 CI와 main 병합 SHA는 해당 GitHub PR 페이지에서 확인한다.

확인할 보존 계약은 정확한 원본 ID·계정 조건·SCOPE·요청 한도·명시적 전송·중지·증거 저장이다. 관련 소스와 회귀는 [EnvironmentPanel](../../../simple/burp-workbench/src/main/java/kr/workbench/EnvironmentPanel.java), [환경 작업 회귀](../../../simple/burp-workbench/src/test/java/kr/workbench/SimpleEnvironmentWorkflowTest.java), [PassiveScanWorkspace](../../../simple/burp-workbench/src/main/java/kr/workbench/PassiveScanWorkspace.java), [대상 준비 회귀](../../../simple/burp-workbench/src/test/java/kr/workbench/SimplePassiveTargetFlowTest.java), [TrafficBrowser](../../../simple/burp-workbench/src/main/java/kr/workbench/TrafficBrowser.java), [검색 준비 회귀](../../../simple/burp-workbench/src/test/java/kr/workbench/SimpleTrafficReadinessTest.java)에서 추적한다. [재현 실행법](../../../audit/simple-workflow-usability/README_KR.md)도 제공한다.

이 기록은 운영 Swing·Montoya 어댑터와 명시한 격리 loopback HTTP의 자동 관측이다. native Burp GUI·OS 클릭·수동 사용성 조사·브라우저 DOM·Windows DPI/IME·OS 파일 선택기를 수행한 근거가 아니다. EDT 할당량은 해당 UI 작업의 누적 할당 bytes이며 잔존 heap·RSS·최고 메모리·다른 스레드의 할당량을 측정하지 않는다.

[이전1.4.1의 전체 흐름5라운드](../2026-10-30-simple-usability/REPORT_KR.md)는 당시의 검증 이력으로 보존한다. 이번 기준 프로브3개나 같은 프로그램의 반복 실행을 새 개선 라운드5회 또는 고유 검사 증가로 표시하지 않는다. 이전 배포 파일·Next 원본과 동결된 이전 하네스는 보존한다.


화면에서 일상 선택과 관리 기능의 배치를 비교하려면 [1.4.1 기존 화면](../../../simple/docs/images/1.4.1/light/1024x720/09-environment.png)과 [1.4.2 개선 화면](../../../simple/docs/images/1.4.2/light/1024x720/09-environment.png)을 확인한다. 두 이미지는 합성 fixture를 사용하는 운영 Swing 캡처이며 native Burp 화면이 아니다.
