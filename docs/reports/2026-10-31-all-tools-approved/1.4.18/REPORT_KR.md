# Workbench Simple 1.4.18 · 승인 시안과 실제 실행 화면

원작업은 2026-10-31에 시작했습니다. 최종 수정·재캡처·검증 정리는 2026-11-01(Asia/Seoul)에 진행했으며, 보고서 폴더 날짜는 원작업 날짜를 유지합니다.

요청·응답 목록의 시안 적용 기준을 나머지 도구까지 확장했습니다. 실제 컨트롤과 문서를 재배치했으며, 원본 ID·바이트, 계정 연결과 기존 관리 3탭, 명시적 검사/조회, SCOPE·중지·프로젝트 설정 보존 동작을 유지합니다. 새 Simple은 1.4.18, 포함 Mapper는 0.3.19입니다.

- [승인 시안·실제 창 비교 PDF](workbench-simple-1.4.18-approved-actual.pdf): 16개 화면 × 2테마, 32페이지. 각 페이지에 원본 해상도의 시안과 실제 캡처를 함께 삽입했습니다.
- [테마 전환 비교 갤러리](COMPARE.html): 로컬에서 열어 라이트·다크를 전환할 수 있습니다. 각 이미지는 원본 PNG로 연결됩니다.
- [설치 JAR](../../../../simple/downloads/1.4.18/burp-workbench-simple-1.4.18.jar) · [독립 소스 ZIP](../../../../simple/downloads/1.4.18/burp-workbench-simple-1.4.18-source.zip)

실제 캡처는 Linux Xorg/xfwm4에서 운영 Swing 컴포넌트를 실행하고 Robot으로 화면 픽셀을 읽은 결과입니다. Burp API는 격리된 합성 어댑터이며, 캡처 중 대상 HTTP 요청은 0건입니다. 실제 Windows Burp 본체·DPI/IME·외부 제공자 연동을 검증한 결과는 아닙니다.

시안과 실제 화면의 합성 데이터, 선택 상태, 결과 건수, 시간은 다릅니다. Java와 브라우저의 글자 렌더링까지 픽셀 차이 0으로 일치한다는 의미도 아닙니다. [주요 카드 측정](frame-measurements.json)은 1600×1000의 승인 프로토타입 DOM과 실제 Swing의 주요 카드 34곳만 비교한 결과로, 좌표·크기의 최대 차이는 1px입니다. 화면 전체의 픽셀 일치 판정과 구분합니다.

계정 연결창을 같은 이벤트 처리 안에서 연속으로 다시 열거나 부모 창이 숨었다가 나타나는 경우도 확인했습니다. 닫힌 연결창이 다시 나타나거나 화면 덮개가 남지 않도록 종료 처리를 보완했으며, 해당 검증은 아래 600개 캡처 시나리오에 포함됩니다.

## 확인 자료

| 검증 | 확인한 결과 | 근거 |
|---|---|---|
| 실제 창 캡처 | 두 테마 × 세 크기(1600×1000·1280×800·1024×768), 162장 | [native-verification.json](native-verification.json) |
| 캡처 상태/동작 | 2,802개 컨트롤 기록·600개 시나리오, 스크롤로 접근하는 항목을 제외한 컨트롤 잘림 0·실패 시나리오 0 | [측정 CSV](measurements/) |
| 실제 창 회귀 | 버튼 80·창 모드 50·가이드 50 = 180개, 모두 같은 JAR·대상 HTTP0 | [native-regressions/verification.json](native-regressions/verification.json) |
| 화면 설명 | 16개 화면·32개 원본 이미지·96개 단계의 양 테마 위치 192개 확인 | [원본 위치](guide-anchor-verification.json) · [최종 창 재대조](guide-anchor-final-crosscheck.json) |
| 전체 검사·패키지·독립 재빌드 | Workbench 168그룹 7,228 + payload136 + Mapper Java147 + Node29 = 7,540개 통과. 독립 소스 재빌드 JAR은 배포본과 바이트 동일 | [verification.json](verification.json) |

이 문서의 캡처/가이드/창 회귀 검사수는 서로 다른 검증 축이며 전체 회귀 검사수와 합산하지 않습니다.

검증한 실제 창의 JAR SHA-256:

`0beaa976294bf74b924a2be3d95c03d699b631baf751144670c851fb713cfca1`

## 원본 화면

| 화면 | 라이트 실제 창 | 다크 실제 창 |
|---|---|---|
| 요청·응답 목록 | [PNG](images/light/traffic.png) | [PNG](images/dark/traffic.png) |
| 기능 자동 매핑 | [PNG](images/light/mapper.png) | [PNG](images/dark/mapper.png) |
| JS에서 API 찾기 | [PNG](images/light/js.png) | [PNG](images/dark/js.png) |
| 응답 보안 분석 | [PNG](images/light/response.recon.png) | [PNG](images/dark/response.recon.png) |
| 계정 권한 비교 | [PNG](images/light/auth.png) | [PNG](images/dark/auth.png) |
| 인증 후보 연결 | [PNG](images/light/auth.accounts.png) | [PNG](images/dark/auth.accounts.png) |
| 입력값 검사 · 준비 | [PNG](images/light/payload.png) | [PNG](images/dark/payload.png) |
| 입력값 검사 · 결과 | [PNG](images/light/payload.results.png) | [PNG](images/dark/payload.results.png) |
| 서버·경로 탐색 | [PNG](images/light/environment.png) | [PNG](images/dark/environment.png) |
| 외부 노출 조사 | [PNG](images/light/exposure.search.png) | [PNG](images/dark/exposure.search.png) |
| CVE 후보 | [PNG](images/light/exposure.cve.png) | [PNG](images/dark/exposure.cve.png) |
| 점검 진행 현황 | [PNG](images/light/activity.png) | [PNG](images/dark/activity.png) |
| 증거 정리 | [PNG](images/light/bounty.png) | [PNG](images/dark/bounty.png) |
| 업무 흐름 재현 | [PNG](images/light/bounty.replay.png) | [PNG](images/dark/bounty.replay.png) |
| 점검 대시보드 | [PNG](images/light/dashboard.png) | [PNG](images/dark/dashboard.png) |
| 데이터 변환·비교 | [PNG](images/light/data.png) | [PNG](images/dark/data.png) |

## 기존 인증 관리의 3탭

간단한 후보 연결창과 별도로 기존 관리의 운영 Swing 탭 콘텐츠도 캡처했습니다. 아래 자료는 각 탭의 콘텐츠이며, 후보 연결 화면만으로 관리 흐름 전체를 검증했다고 해석하지 않습니다.

| 관리 탭 | 라이트 | 다크 |
|---|---|---|
| 적용 상태 | [PNG](appendix/light/04-auth-accounts-full-overview.png) | [PNG](appendix/dark/04-auth-accounts-full-overview.png) |
| 계정·쓰기 조회 | [PNG](appendix/light/04-auth-accounts-full-accounts.png) | [PNG](appendix/dark/04-auth-accounts-full-accounts.png) |
| 세션·권한표 | [PNG](appendix/light/04-auth-accounts-full-policy.png) | [PNG](appendix/dark/04-auth-accounts-full-policy.png) |

[이미지 SHA-256 목록](images-manifest.json)은 승인 원본과 실제 이미지의 보존을 확인하는 자료입니다. 이미지 SHA가 다르다는 사실을 배치 불일치나 기능 실패로 판정하지 않습니다.
