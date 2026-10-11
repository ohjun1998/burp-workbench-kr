# 0.17.2 → 0.17.3 변경 전후와 추가 개선 검토

## 실제 반영한 변경

| 대상 | 변경 전 | 변경 후 |
|---|---|---|
| 백그라운드 JS 발견 | 결과를 화면에 표시할 때 활동 추적 전달 | 화면 선택 여부와 무관하게 모든 분석 결과를 즉시 전달 |
| Source Map 발견 | 업데이트 결과의 화면 표시와 추적 연결에 의존 | 복원 원본의 발견도 즉시 연결하고 파일 근거 보관 |
| 미점검 상태 | 발견만 한 항목과 실제 관측 항목 모두 미점검 | 발견만 함 / 요청 관측 / Repeater 작업 / 재검토 필요 / 수동 완료 구분 |
| 미관측 API | 요청 횟수·출처를 직접 읽음 | JS/활동 추적에 미관측 필터 추가 |
| 발견 근거 | 활동 추적에는 JS 분석 출처 문자열 | 출처 파일·줄·맥락을 별도 창에서 확인, 원본/복원 파일로 이동 |
| 요청 초안 | GET 경로만 준비 | 추출 메서드·쿼리 이름·본문 키를 placeholder로 준비 |
| 동적 URL | 미해결이면 Repeater 준비 불가 | 별도 창에서 경로 확정 후 SCOPE 검사와 초안 준비 |
| 계정 구분 | 인가 1·2·3과 인증 지문 | 계정 이름과 소유값 설정, 실행 당시 이름/규칙 스냅샷 유지 |
| 권한 결과 | 한 열에 계정별 관찰 요약 | 계정별 결과 열 + 판정 근거 영역 |
| 응답 비교 | 상태 코드·본문 byte 일치와 텍스트 차이 | 기존 패킷 뷰 + JSON 경로별 차이, 비교 제외 규칙 |
| 업무 오류 | HTTP 200이면 성공 응답 검토 | 설정한 JSON 차단 코드면 차단 근거로 구분 |
| 객체 소유 | 세션만 바꿔 직접 판단 | 소유 JSON 경로·계정 소유값으로 타인 객체 접근 후보 표시 |
| 후속 검토 | 각 탭의 결과를 직접 연결 | 권한 의심/보류 결과를 활동 추적 재검토 항목으로 연결 |
| 기록 내보내기 | 활동 메타데이터 버전 1 | 버전 2에 JS 근거·재검토 근거 추가, 버전 1 읽기 지원 |
| JSON 숫자 | Double 기반 파싱 | BigDecimal로 큰 숫자 ID 정밀도 유지, 큰 지수 문자열 확장 제한 |
| JS 기본 배치 | 원본 패널 비율 30% | 헤더에 원본이 가려지지 않도록 43%로 조정 |

### 실제 동작 예시

JS에서 POST /api/orders/refund와 orderId를 발견했지만 요청이 없으면 발견만 함으로 남습니다. Repeater 초안을 준비해도 서버 요청/완료 상태는 바뀌지 않습니다. 실제 요청을 관측한 후 요청 관측 또는 Repeater 작업 상태가 됩니다.

사용자 A 객체 요청을 B 세션으로 비교했는데 ownerId=A가 유지된다면, 소유 설정을 기준으로 타인 객체 접근 의심으로 표시합니다. requestId를 제외해도 원본 응답은 보존되며, JSON 비교에서만 해당 필드를 제외합니다. 의심 결과는 활동 추적에 재검토 근거로 연결됩니다.

동적 미해결 후보는 JS 목록에 보관하고 수동 경로 확정 시 정적 추적 대상으로 등록합니다. 메서드가 미확정인 항목, Scope 밖 대상, 인증값을 포함한 URL은 초안 준비에서 제한합니다.

## 다른 기능의 코드 검토: 아래 항목은 향후 개선 제안

다음 표는 현재 코드에서 확인한 동작과 개선 시의 전후 차이입니다. 이번 JAR에서는 해당 모듈의 진단 방식/검사 범위를 바꾸지 않았습니다. 기능 제안을 구현 완료한 것으로 표시하지 않습니다.

| 우선순위 | 기능 | 확인한 현재 동작 | 개선한다면 변경 후 |
|---|---|---|---|
| 높음 | 페이로드 검사 | 적응형 모드는 401/403/429/503에 중단하지만 고정 목록 모드는 같은 일괄 중단 규칙이 없음. Legacy makeHttpRequest 호출에는 확장별 응답 timeout 옵션이 없음 | 두 모드에 공통 중단/대기 정책과 응답 timeout을 적용. Stop 시 후속 요청 차단과 이미 보낸 요청 대기를 명확히 표시 |
| 높음 | 환경 탐색 | 없는 경로와 상태·Content-Type·정규화 본문이 같으면 공통 응답으로 분류. 정규화는 경로와 공백 중심 | 대상별 요청 ID/시간 필드 제외, 복수 기준 응답 안정성 비교, 기준 요청 실패/Scope 제외 여부를 결과 근거에 표시 |
| 중간 | URL 수집 | 전체 원본 URL별 중복 제거와 이전/신규 표시. 쿼리 값이 다른 URL은 별도 행 | 원본 보존과 함께 메서드·경로·파라미터 이름으로 대표 패턴을 묶고, 실행 대상/관측 시점별 재검토 우선순위 제공 |
| 중간 | URL 기록 저장 | Excel은 쿼리 값을 가리지만 URL DB 텍스트는 원본 쿼리 값을 저장하고 완료 메시지에 이를 알림 | 쿼리값 제외를 기본값으로 하고 원본 URL 저장을 별도 옵션으로 제공 |
| 중간 | JWT 도구 | 헤더·payload 디코딩, alg=none 후보 안내, 서명 미검증 표시 | exp/nbf/iat를 시간으로 표시하고 만료/사용 가능 시점을 설명. 서명 검증은 명시적으로 제공된 키/알고리즘 설정에서만 수행 |
| 낮음 | Wayback 내장 수집 | 응답 읽기 한도는 8MB이나 기본 JSON 파서 입력 한도는 2MB | 실제 지원 한도를 통일하거나 스트리밍 파싱 적용. 수집 개수/크기 제한을 화면에 구분 표시 |
| 낮음 | 트래픽·SCOPE | 경로/Origin 규칙을 공통 사용하며 지정 후 Burp Scope fallback을 대체 | 각 항목에 적용된 SCOPE 규칙과 제외 사유 표시, 규칙 내보내기/불러오기 및 중복 포함 관계 설명 |

환경 탐색의 동적 응답 비교는 오탐 억제 개선이며, HTTP 200을 취약점으로 확정하는 변경을 의미하지 않습니다. URL 패턴 묶기는 원본 URL을 삭제하는 방식으로 적용하지 않는 것이 좋습니다.

## 코드 근거

- JS 결과 전달: `src/main/java/kr/workbench/JsAnalysisPanel.java:56`
- Source Map 전달: `src/main/java/kr/workbench/JsAnalysisPanel.java:72`
- 활동 상태: `src/main/java/kr/workbench/ActivityTracker.java:13`
- 초안 생성: `src/main/java/kr/workbench/JsRequestDraft.java:10`
- JSON 판정: `src/main/java/kr/workbench/AuthJudgement.java:14`
- 계정별 실행: `src/main/java/kr/workbench/AuthComparePanel.java:82`
- 환경 본문 비교: `src/main/java/kr/workbench/EnvironmentResponse.java:8`
- 환경 기준 요청: `src/main/java/kr/workbench/EnvironmentPanel.java:55`
- URL 중복/신규: `src/main/java/kr/workbench/PassiveScanPanel.java:19`
- URL DB 원본 저장: `src/main/java/kr/workbench/PassiveScanPanel.java:33`
- JWT 디코딩: `src/main/java/kr/workbench/Core.java:94`
- 고정 목록 검사: `src/main/java/kr/payloadrunner/Engine.java:123`
- 적응형 중단: `src/main/java/kr/payloadrunner/Adaptive.java:39`
- Legacy HTTP 전송: `src/main/java/kr/payloadrunner/PayloadRunnerPanel.java:126`
- Wayback 크기: `src/main/java/kr/workbench/ArchiveUrls.java:8`
- 기본 JSON 한도: `src/main/java/kr/workbench/MinerJson.java:6`
- SCOPE 적용: `src/main/java/kr/workbench/WorkbenchScope.java:16`

코드 근거는 함께 제공한 source.zip 내부 경로입니다. 추가 개선 제안은 정적 코드 검토 결과이며 해당 제한을 실제 Burp GUI에서 재현한 보고서가 아닙니다.

## 검증 범위

전체 회귀 테스트와 새 흐름 테스트를 수행했습니다. 새 테스트에는 백그라운드 JS 전달, Source Map 근거, 미관측 필터, POST 초안, 동적 경로, JSON 제외/차단/소유 판정, 큰 숫자 정밀도, 재검토 연결, 설정 스냅샷, 이전 기록 호환성이 포함됩니다.

최종 UI 조정 후 관련 테스트를 다시 실행했습니다. Swing 화면은 headless로 렌더링하고 육안 확인했습니다. 실제 Burp GUI 로딩·실제 서비스 취약점 탐지는 검증하지 않았습니다.

검증 결과: **732개 통과, 0개 실패**. 새 기능 회귀 테스트 48개 포함.

### JS 추출 — 합성 데이터 headless 화면

![JS 추출](C:/Users/kwonohjun/Documents/Codex/2026-10-10/new-chat/outputs/화면_0.17.3_js.png)

### 활동 추적 — 합성 데이터 headless 화면

![활동 추적](C:/Users/kwonohjun/Documents/Codex/2026-10-10/new-chat/outputs/화면_0.17.3_activity.png)

### 권한 비교 — 합성 데이터 headless 화면

![권한 비교](C:/Users/kwonohjun/Documents/Codex/2026-10-10/new-chat/outputs/화면_0.17.3_auth.png)

### 비교 설정 — 합성 데이터 headless 화면

![비교 설정](C:/Users/kwonohjun/Documents/Codex/2026-10-10/new-chat/outputs/화면_0.17.3_settings.png)

