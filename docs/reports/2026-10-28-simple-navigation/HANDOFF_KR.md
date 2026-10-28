# Simple 1.3.2 메뉴 탐색·신원 대조·원문 보고서 인수인계

지연 SPA 메뉴와 메뉴 동작 전 링크 누락, 탐색 한도의 잘못된 완료 표시, 개별 API401에 의한 전역 세션 중단, backtick 원문 보고서 누락을 보완했습니다. 기존 Next·8개 도구·이전 배포는 유지하며 새 Simple1.3.2와 helper0.4.1로 전달합니다. 브라우저 탐색 보완을 쓰려면 **JAR와 helper를 함께 갱신**해야 합니다.

기반은 Simple1.3.1 `7113e7996dd1406c6262d896627e891769b80123`, 작업 브랜치는 `account-a/simple-navigation-retest`입니다. 선행 PR#99의 `account-a/simple-retest-values` 대상으로 후속 초안 PR을 만듭니다. main 병합과 merged 상태 문서 변경은 수행하지 않습니다.

## 검증한 최종 파일

- JAR SHA-256: `a8dd345b44b4893a301bf3092817335585c15ebd67cb5e6508bd3847fee49400`, 4,536,359bytes. strict Java17.0.17 / --release17, 내장 소스 입력216개 일치.
- 전체 기본 검사 **5,184개**: Workbench134그룹/4,874 + payload HTTP136 + Mapper Java145 + Node29. 실패 그룹 없음. Report119·Runner152 등 집중 검사는 이 합계와 중복입니다.
- 별도 실제 Katana1.8.0 HTTP/HTTPS CONNECT **102개** 통과.
- 운영 Swing/합성 자료 **112뷰·2,244컨트롤·120상태**, 모든 배치 게이트0. 가이드35쪽/28캡처, 구조·지문·시각검수 통과.
- 실제 Chromium service/API adapter의6 fixture에서 수정 전 결함 재현28개와 수정 후28개를 각각 확인했습니다. 전후를 더하지 않습니다. 별도 DOM/MAIN guard17개는 중첩되는 근거입니다. native MV3/DNR 로드는 관리 정책에 막혀 검증하지 못했고 정책을 변경하지 않았습니다.
- 보고서 도구 단위검사25개 통과. 원문 보고서 HTML/PDF/ZIP의 최종 검증은 별도 첨부물 기록에서 확인합니다.
- 기존 배포28개와 과거 가이드 보존 항목142개의 해시 유지. 원본 `project/`·루트 `feature-mapper/`는 변경하지 않았습니다.
- 소스 ZIP1,403항목 CRC·전체 지문과 새 폴더의 strict JDK17 독립 생산 빌드를 확인했습니다. 재빌드 JAR는 배포 JAR와 바이트 단위로 일치합니다. 소스 ZIP SHA-256: `a0a1dab3daef3a9bc2495a8888a05bfbb279f0fbb850376dfd01fe0e7bcc7580`. [재빌드 근거](source-rebuild-1.3.2.json)

[최종 검증 JSON](../../../simple/docs/VERIFICATION_SIMPLE_1.3.2.json) · [변경별 근거](improvement-checks.json) · [브라우저 근거](../../../audit/simple-navigation-retest/browser-validation.json)

## 실제 앱 전후 대조와 해석

7개 원본 앱에서 새로 실행한 본 행렬은 **28회**입니다. 버전별 루트 자동 수집과 직접 seed 대조를 나눴습니다. 1.3.1은1,240전송 시도/1,235완료 응답/5전송 오류, 1.3.2는1,317/1,312/5입니다. 과거 실행이나 중간 후보를 합산하지 않습니다.

최종 자동 VulnBank 루트 실행은 문제의 merchant API를 방문하지 않았습니다. 따라서 루트 완료 상태만으로401 수정 효과를 입증하지 않습니다. 자동 수집의 해당 게이트false와 결과review를 그대로 유지합니다. 별도 **직접 대조2회**에서는 같은 merchant API401을 두 버전 모두 실제로 수신했습니다. 이전은5요청 후PAUSED, 수정본은새 인증 신원200·비로그인401 대조와 기대JSON/원본ID·응답SHA 일치를 확인한 뒤24요청 후COMPLETED였습니다. 이 경로는 여전히 ACCESS 미확인이며 접근 성공·권한 우회·취약점으로 판정하지 않습니다. 직접 대조는 자동 발견 성과와 본28회 수치에 합산하지 않습니다.

OopsSec의 첫 수정 후 실행은 실제 신원 URL500으로28요청에서 멈췄습니다. 같은 고정 인증값의 신원 응답이200으로 회복된 뒤 Oops만 양 버전2회씩 한 세트의 추가 paired 재시험을 수행했습니다. 최초 원본·개발 서버 오류 관측과 제외 이유를 보존했고 재시도를 반복하지 않았습니다. 최종 Oops4회는 모두105초 한도에서STOPPED이며 전체 사이트 완료가 아닙니다. 가상 Next 개발 모듈 경로·404/500·원본 전송 오류도 미확인으로 남습니다.

[본 결과·review](../../../audit/simple-navigation-retest/results-summary.json) · [별도 직접 대조16개 게이트](../../../audit/simple-navigation-retest/realm-direct-verification.json) · [Oops 이력](../../../audit/simple-navigation-retest/retry-observations.json) · [재현](../../../audit/simple-navigation-retest/LIVE_RETEST_KR.md)

## 원문 보고서·가이드·설치

원문 보고서는 API·민감정보·Token·Key·환경·DB·경로·내부·기타로 분류하고 값·rawText·정확한 위치·원본ID·응답 지문·계정/revision과 제품 탐지/보충 파싱을 구분합니다. 동적 backtick은 DYNAMIC_UNRESOLVED이며 식 평가·추정 endpoint·자동 요청은 없습니다. 같은 실제 번들의 공식JAR 대조는17→115행이며 template64행은 API4/PATH60입니다. 전체 번들 지원이나294행 복원을 주장하지 않습니다.

원문 값·실행 인증·HTTP corpus는 Git 밖의 `/workspace/simple-navigation-1.3.2-report`로 전달합니다. 반복 관측·예제·공개·미검증 값이 포함되므로 행 수는 고유 취약점·유효 시크릿 수가 아닙니다. 최종 첨부물 수치·지문은 같은 폴더의 `raw-report-artifacts.json`에 안전한 요약으로 기록합니다.

최종 보고서는32쪽PDF/대표88값과 전체43,355행입니다. 제품 탐지3,062 / 제품 보고서 보충36,237 / 독립 보충4,045 / 테스트 인증11을 구분합니다. JSON·CSV·HTML 값 일치, 원문span·SHA 전수 대조, 직접 대조3개 원본, HTML10개 검사·외부 요청0, PDF 시각검토, ZIP CRC·내부 해시를 통과했습니다. 작은 `parsed-values.zip`은14,964,230bytes이며 전체 HTTP 근거는198,787,578bytes의 별도 `evidence.zip`에 있습니다. [첨부물 검증·해시](raw-report-artifacts.json) · [공개 요약](public-summary.json)

- [설치 JAR·helper0.4.1·소스·체크섬](../../../simple/downloads/1.3.2/)
- [35쪽 가이드 PDF](../../../simple/downloads/guides/1.3.2/Workbench_Simple_1.3.2_Guide_KR.pdf)
- [사용 안내](../../../simple/README_KR.md) · [새 버전 변경](../../../simple/docs/RELEASE_SIMPLE_1.3.2_KR.md)
- [관련 공개 작업 대화](../../chat-logs/2026-10-28/simple-navigation-retest.md)

가이드 검수에서 수정 전 a92 후보의 옛 helper 표시를 발견했습니다. 표시 두 곳을 버전 중립 문구로 고치고 최종a8dd JAR로14회 after·모든 캡처·근거를 다시 생성했습니다. 중간 후보는 별도 폴더로 보존하고 최종 행렬·배포 입력에서 제외했습니다. Simple1.3.2 배포 입력은 패키징 후 고정했으며 독립 ZIP 재빌드·원격CI 기록은 순환 지문을 피하도록 Simple 밖의 증거와 PR에 남깁니다.

## 남은 범위·종료

유한 DOM 안정 대기는 전체 메뉴 확인을 보장하지 않습니다. 빈 문서·busy·탐색 한도와 모호한 JS 구문은 부분/미확인으로 표시합니다. 실제 Burp GUI·Windows DPI/IME·native MV3/DNR·7개 앱의 브라우저 전체 메뉴·외부 키 유효성·모든 취약점 정확도는 검증하지 않았습니다. Java HTTP adapter의 저장본은 수신payload와 정규화 헤더를 재구성한 근거이며 TCP framing 원시 캡처가 아닙니다.

7개 앱·소유 컨테이너3개·relay는 종료했고 LOOP·PostgreSQL·Benchmark DB 백업을 보존했습니다. 직접 대조를 위한 VulnBank/PG의 일시 재기동과 재종료도 기록합니다. 사용자 전역 Burp·브라우저·프록시 설정은 바꾸지 않았습니다. [종료 확인](../../../audit/simple-navigation-retest/services-stop-summary.json)을 참고하세요.
