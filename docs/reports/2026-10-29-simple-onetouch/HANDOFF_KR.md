# Simple 1.4.0 원터치 주요정보 탐지 고도화

기준 main은 `7a54e9842a90fca9b8cf0a47ed59932a7bec4afb`, 작업 브랜치는 `account-a/simple-onetouch-detection-140`입니다. 기존 Next와 Simple의 도구8개·이전 배포를 유지합니다. main 병합 상태와 GitHub 검증은 해당 PR의 실제 기록을 기준으로 읽습니다.

## 바뀐 동작

1. 제품 화면과 원문 보고서에 공통 응답 파서를 연결했습니다. 주소·전화·SSN·생년월일·계좌번호·환경·DB 설정과 API 문자열을 분류하고, 일반 화면·이력은 가림을 유지합니다. 명시적 로컬 보고서는 정확한 값·원문·위치·OriginalID·응답 SHA를 보존합니다.
2. CSS 아이콘, DOMTokenList, 빈값, Swagger 코드 조각, 쿠키·인증 형식, 공개·예시·데모 값을 구분합니다. URI 인증정보를 이메일로 중복 분류하지 않으며 URL 인증정보·쿼리 값이 일반 메타데이터로 새지 않도록 했습니다.
3. 경로별 발견→Scope→큐→전송→응답→분석과 미방문 사유를 표시합니다. 미해결 입력·접근 거부를 자동 조회 성공으로 바꾸지 않고, 같은 실행·현재 인증 revision·남은 예산에서 선택한 안전 조회만 재점검합니다.
4. helper0.5.0은 Fetch/XHR 요청·응답·실패·frame·캐시·대기 상태와 같은 Scope의 iframe 경로를 연결합니다. 브라우저 세션과 HTTP 계정, 브라우저 응답 메타데이터와 본문 분석은 별개입니다.
5. 동적 API와 실제 응답의 경로 형태 일치는 `PATH_SHAPE_ONLY` 후보입니다. 선언·관측의 계정·revision·원본을 따로 표시하며 식 평가·변수 복원·추가 요청·실행 가능 승격은 하지 않습니다. 명시적 sourceMappingURL은 추정 환경 경로 한도와 분리합니다.
6. 응답을 보관한 뒤 분석 전 중지된 원본도 `RESPONSE_PENDING_ANALYSIS`, `complete=false`, 빈 digest로 Snapshot에 연결합니다. 원본 열기·내보내기를 유지하고 원문 추출 성공을 제품 분석 완료로 해석하지 않습니다.

## 설치 파일과 가이드

- [Simple JAR](../../../simple/downloads/1.4.0/burp-workbench-simple-1.4.0.jar)
- [브라우저 helper0.5.0](../../../simple/downloads/1.4.0/feature-mapper-browser-helper-0.5.0.zip)
- [독립 빌드 소스](../../../simple/downloads/1.4.0/burp-workbench-simple-1.4.0-source.zip) · [체크섬](../../../simple/downloads/1.4.0/SHA256SUMS)
- [40쪽 PDF 가이드](../../../simple/downloads/guides/1.4.0/Workbench_Simple_1.4.0_Guide_KR.pdf) · [HTML 가이드](../../../simple/docs/GUIDE_SIMPLE_KR.html)
- [적용 범위와 검사 연결표](../../../simple/docs/KATANA_SCOPE_1.4.0_SPEC_KR.md) · [배포 파일 지문](artifacts.json)

내장 Mapper는0.3.7, Katana는 외부 설치1.8.0입니다. 기본 흐름은 Scope·계정 선택→시작→주요정보·탐색 커버리지→원본·미확인 재점검→보고서 저장입니다. 기존 설치·이전 파일 삭제는 필요하지 않습니다. 브라우저 기능에는 새 helper도 사용합니다.

## 실제 검증

| 구분 | 결과와 범위 |
|---|---|
| 전체 자동 검사 | **5,476개 통과** = Workbench140그룹5,161 + 페이로드 HTTP136 + Mapper Java145 + Node34. 집중·반복 검사는 더하지 않습니다. |
| 실제 Katana | 고정1.8.0 프로세스·loopback HTTP/HTTPS CONNECT **102개 통과**. 시스템 CA 변경 없음. |
| 브라우저 | 실제 Chromium DOM·운영 서비스의 Chrome API 어댑터 **14개 통과**. helper 구성요소 지문 일치. |
| 화면·가이드 | 운영 Swing 합성 증거 **128뷰·2,764컨트롤·136시나리오**, 배치 게이트0. PDF40쪽·북마크40·33캡처, 구조·폰트·시각 검토 통과. |
| 독립 소스 빌드 | 소스 ZIP1,748개 항목의 CRC·전체 지문 검증. 별도 폴더·같은 JDK17 strict 빌드에서 배포 JAR와 바이트 동일. |
| 보고서·감사 도구 | 새 감사 Python20개 통과. 기존 보고서25개도 통과했으며 공통10개를 중복 합산하지 않습니다. |

[버전 검증 기록](../../../simple/docs/VERIFICATION_SIMPLE_1.4.0.json) · [독립 빌드 근거](source-rebuild.json)

### 동일 원본 응답 재분석

실제7개 앱의 보존 응답38개에서 선별한 주요항목79개·분류 대조17개를 같은 하네스로 비교했습니다. 제품 위치·지문 연결은 **46→79개**, 고유 사이트·값은 **46→78종**이며 정확한 값·실제 UTF-16 원문 위치 검증은 **64→79개**입니다. 기존 양성 회귀·노이즈 잔존·대조 항목의 비밀값 오승격은 해당 표본에서0개입니다.

이전 보충 파서와 원문 리터럴 문자열 자체가 같은 항목은73개입니다. 나머지6개는 백틱 전체를 근거로 보존하는 경계 차이이며 실제 새 원문 좌표·응답 SHA 검증을 통과했습니다. 이 수치는 사이트 전체 탐지율·취약점 개수가 아닙니다.

[동일 입력 비교](../../../audit/simple-onetouch-140/offline-comparison.json)

### 원본 앱 새 실행 대조

Juice Shop·BenchmarkJava·VulnerableApp·OopsSecStore·DVBank·VulnBank·LOOP에서 root와 직접/보호 seed를 각 버전으로 실행했습니다. 최종28회는 각 사례의 새 입력 스냅샷·Scope·계정·예산이 동일하며 모든 전송·원본 참조 검증을 통과했습니다.

| 버전 | 실행 | 요청 시도 | 실제 HTTP 응답 | 전송 오류 | 상태 |
|---|---:|---:|---:|---:|---|
| 1.3.2 | 14 | 1,257 | 1,247 | 10 | COMPLETED11 / STOPPED3 |
| 1.4.0 | 14 | 1,163 | 1,158 | 5 | COMPLETED11 / STOPPED3 |

HTTP 응답에는 대조·비2xx가 포함됩니다. 전송 수 차이를 정확도나 성능 점수로 해석하지 않습니다. Benchmark는 요청 예산, Oops 두 사례는105초 감사 시간 한도로 중지했고, 부분·미방문·실패는 그대로 남깁니다. LOOP root에서 형태 일치 후보4행의 선언·관측 원본이 연결됐으며 변수·권한 해소를 뜻하지 않습니다. Oops root의 수신 후 분석 대기 원본1건도 완료로 승격하지 않고 보존했습니다.

[새 실행 비교·전체 무결성 기준](../../../audit/simple-onetouch-140/live-comparison.json)

## 후보·실패·보존 기록

- 초기 공통 파서의 분류·중요도 정합성 검사3그룹 실패를 수정했습니다.
- 후보9368에서 DB URI 일부의 이메일 중복 분류를 시각 검토로 찾아 수정하고 후보14실행·가이드를 별도 보존했습니다.
- 후보ad31의 Oops 중지 경계에서 원본 참조2건이 Snapshot 페이지에 빠져 source-evidence 기준이 실패했습니다. 기준을 낮추지 않고 원본 보존을 수정한 새 JAR로14실행·전체 캡처를 다시 만들었습니다.
- LOOP 기존 인증정보는 configured identity401로 올바르게 중지했습니다. 정확한 만료·무효화 원인은 별도 확정하지 않았습니다. 이전 baseline2건과 후보 결과를 보존하고 정상 로그인 후 같은 새 인증 스냅샷으로 전후4사례를 대조했습니다.
- 675 JAR의 첫 전체 검사에서 종료 프록시의 TCP 연결 실패를 즉시 요구하던 기존 검사가1회 실패했습니다. 정확한 OS 원인은 미확정입니다. 통제한 포트 재사용으로 해당 전제가 부정확함을 확인했고, 운영 코드는 유지한 채 소유 listener·acceptor 종료와 Scope 허용 상태의 실제 전송 차단을 검사하도록 바꿨습니다. 고정50개 검사3회와 전체5,476개 재검증을 통과했습니다.

최종28회에는 후보28회와 교체한 LOOP baseline2회를 더하지 않습니다. 원문·인증정보·중간 결과는 저장소 외부에 보존하고 공개 요약만 올립니다. 이전 배포·PDF322개 지문은 모두 유지했습니다. 원래 Next 구현은 변경하지 않았습니다.

[실패·후보 기록](../../../audit/simple-onetouch-140/retest-attempts.json) · [LOOP 인증 관측](../../../audit/simple-onetouch-140/loop-session-observations.json) · [테스트 서비스 종료](../../../audit/simple-onetouch-140/services-stop-summary.json)

## 남은 검증 범위

실제 Burp GUI·Windows DPI/IME는 이번 환경에서 검증하지 않았습니다. Native MV3/DNR 설치는 관리형 Chrome 정책으로 차단돼 우회하지 않았으며, 어댑터 검사를 native 검증으로 합산하지 않습니다. 원문 보고서의 키는 형식·문맥 관측이고 외부 공급자 유효성을 자동 검사하지 않습니다. 서비스 전체 메뉴 점검 완료와 중단 후 앱 재시작을 넘는 원문·인증 큐 복구를 보장하는 결과가 아닙니다.
