# Simple 1.4.20 · 승인 시안의 입력·카드·원문 세부 보정

1.4.19에 남아 있던 기본 Swing 숫자 입력·콤보의 이중 테두리, 카드 내부 여백, 필터·안내·아이콘 차이를 수정했습니다. 기존 모델과 입력 문서·커서·원본 ID/바이트·명시적 실행을 유지합니다. Mapper는 0.3.21, 브라우저 도우미는 0.4.1입니다.

[설치 JAR](../../../simple/downloads/1.4.20/burp-workbench-simple-1.4.20.jar) · [독립 소스 ZIP](../../../simple/downloads/1.4.20/burp-workbench-simple-1.4.20-source.zip) · [SHA256SUMS](../../../simple/downloads/1.4.20/SHA256SUMS) · [변경 안내](../../../simple/docs/RELEASE_SIMPLE_1.4.20_KR.md)

기존 1.4.19를 언로드한 뒤 새 JAR를 등록합니다. 이전 배포물은 변경하지 않았습니다. 작업 브랜치는 `account-a/approved-controls-review`이며 main 미병합입니다. 현재 환경에서 `api.github.com` 요청이 차단되어 PR은 생성하지 못했습니다.

## 화면 비교

- [승인 시안 ↔ 1.4.20 실제 창 비교 PDF · 32페이지](workbench-simple-1.4.20-approved-actual.pdf)
- [1.4.19 ↔ 1.4.20 실제 창 비교 PDF · 32페이지](workbench-simple-1.4.20-before-after.pdf)
- [승인 시안·현재 화면 갤러리](COMPARE.html)

원본 PNG는 편집·재표본화하지 않았습니다. PDF에서 배치한 이미지의 원본은 아래 링크로 제공합니다. 실제 데이터·건수·선택 상태는 모델을 따르므로 시안의 예제와 다릅니다. Java와 브라우저의 글자 렌더링을 포함한 픽셀 차이 0 검증은 아닙니다.

| 화면 | 승인 시안 | 1.4.19 실제 창 | 1.4.20 실제 창 |
|---|---|---|---|
| 요청·응답 목록 | [라이트](../2026-10-31-workflow-design/images/light/traffic.png) · [다크](../2026-10-31-workflow-design/images/dark/traffic.png) | [라이트](before/light/traffic.png) · [다크](before/dark/traffic.png) | [라이트](images/light/traffic.png) · [다크](images/dark/traffic.png) |
| 기능 자동 매핑 | [라이트](../2026-10-31-workflow-design/images/light/mapper.png) · [다크](../2026-10-31-workflow-design/images/dark/mapper.png) | [라이트](before/light/mapper.png) · [다크](before/dark/mapper.png) | [라이트](images/light/mapper.png) · [다크](images/dark/mapper.png) |
| JS에서 API 찾기 | [라이트](../2026-10-31-workflow-design/images/light/js.png) · [다크](../2026-10-31-workflow-design/images/dark/js.png) | [라이트](before/light/js.png) · [다크](before/dark/js.png) | [라이트](images/light/js.png) · [다크](images/dark/js.png) |
| 응답 보안 분석 | [라이트](../2026-10-31-workflow-design/images/light/response.recon.png) · [다크](../2026-10-31-workflow-design/images/dark/response.recon.png) | [라이트](before/light/response.recon.png) · [다크](before/dark/response.recon.png) | [라이트](images/light/response.recon.png) · [다크](images/dark/response.recon.png) |
| 계정 권한 비교 | [라이트](../2026-10-31-workflow-design/images/light/auth.png) · [다크](../2026-10-31-workflow-design/images/dark/auth.png) | [라이트](before/light/auth.png) · [다크](before/dark/auth.png) | [라이트](images/light/auth.png) · [다크](images/dark/auth.png) |
| 인증 후보 연결 | [라이트](../2026-10-31-workflow-design/images/light/auth.accounts.png) · [다크](../2026-10-31-workflow-design/images/dark/auth.accounts.png) | [라이트](before/light/auth.accounts.png) · [다크](before/dark/auth.accounts.png) | [라이트](images/light/auth.accounts.png) · [다크](images/dark/auth.accounts.png) |
| 입력값 검사 · 준비 | [라이트](../2026-10-31-workflow-design/images/light/payload.png) · [다크](../2026-10-31-workflow-design/images/dark/payload.png) | [라이트](before/light/payload.png) · [다크](before/dark/payload.png) | [라이트](images/light/payload.png) · [다크](images/dark/payload.png) |
| 입력값 검사 · 결과 | [라이트](../2026-10-31-workflow-design/images/light/payload.results.png) · [다크](../2026-10-31-workflow-design/images/dark/payload.results.png) | [라이트](before/light/payload.results.png) · [다크](before/dark/payload.results.png) | [라이트](images/light/payload.results.png) · [다크](images/dark/payload.results.png) |
| 서버·경로 탐색 | [라이트](../2026-10-31-workflow-design/images/light/environment.png) · [다크](../2026-10-31-workflow-design/images/dark/environment.png) | [라이트](before/light/environment.png) · [다크](before/dark/environment.png) | [라이트](images/light/environment.png) · [다크](images/dark/environment.png) |
| 외부 노출 조사 | [라이트](../2026-10-31-workflow-design/images/light/exposure.search.png) · [다크](../2026-10-31-workflow-design/images/dark/exposure.search.png) | [라이트](before/light/exposure.search.png) · [다크](before/dark/exposure.search.png) | [라이트](images/light/exposure.search.png) · [다크](images/dark/exposure.search.png) |
| CVE 후보 | [라이트](../2026-10-31-workflow-design/images/light/exposure.cve.png) · [다크](../2026-10-31-workflow-design/images/dark/exposure.cve.png) | [라이트](before/light/exposure.cve.png) · [다크](before/dark/exposure.cve.png) | [라이트](images/light/exposure.cve.png) · [다크](images/dark/exposure.cve.png) |
| 점검 진행 현황 | [라이트](../2026-10-31-workflow-design/images/light/activity.png) · [다크](../2026-10-31-workflow-design/images/dark/activity.png) | [라이트](before/light/activity.png) · [다크](before/dark/activity.png) | [라이트](images/light/activity.png) · [다크](images/dark/activity.png) |
| 증거 정리 | [라이트](../2026-10-31-workflow-design/images/light/bounty.png) · [다크](../2026-10-31-workflow-design/images/dark/bounty.png) | [라이트](before/light/bounty.png) · [다크](before/dark/bounty.png) | [라이트](images/light/bounty.png) · [다크](images/dark/bounty.png) |
| 업무 흐름 재현 | [라이트](../2026-10-31-workflow-design/images/light/bounty.replay.png) · [다크](../2026-10-31-workflow-design/images/dark/bounty.replay.png) | [라이트](before/light/bounty.replay.png) · [다크](before/dark/bounty.replay.png) | [라이트](images/light/bounty.replay.png) · [다크](images/dark/bounty.replay.png) |
| 점검 대시보드 | [라이트](../2026-10-31-workflow-design/images/light/dashboard.png) · [다크](../2026-10-31-workflow-design/images/dark/dashboard.png) | [라이트](before/light/dashboard.png) · [다크](before/dark/dashboard.png) | [라이트](images/light/dashboard.png) · [다크](images/dark/dashboard.png) |
| 데이터 변환·비교 | [라이트](../2026-10-31-workflow-design/images/light/data.png) · [다크](../2026-10-31-workflow-design/images/dark/data.png) | [라이트](before/light/data.png) · [다크](before/dark/data.png) | [라이트](images/light/data.png) · [다크](images/dark/data.png) |

Nimbus의 [라이트 검사 조건](nimbus/light/07-payload.png) · [다크 검사 조건](nimbus/dark/07-payload.png) · [라이트 변환](nimbus/light/14-data.png) · [다크 변환](nimbus/dark/14-data.png)도 확인할 수 있습니다. 인증 관리의 실제 세 탭은 appendix/의 원본 캡처로 보존합니다.

## 유지한 동작과 추가 수정

- 숫자 입력의 원래 formatter·모델·문서·미완성 입력·커서·키보드 증감·상하한을 유지합니다. 기본 화살표를 숨겨도 직접 입력과 키보드 조작은 작동합니다.
- 화면별 입력값·요청 한도·계정·필터·작성 문서를 시안의 예제 값으로 덮어쓰지 않습니다. 화면 조회·테마 전환·메뉴 조회만으로 요청을 보내지 않습니다.
- 일반 입력칸의 기존 13px 글꼴과 150% 설정을 유지합니다. 압축된 검색·내부 편집기는 12px 및 명시된 크기를 사용합니다.
- 새 카드 wrapper와 검색 헤더가 저장 키를 바꾸지 않도록 구성 요소 순서를 유지합니다. 1.4.19와 같은 이전 계층에서 저장한 333px 너비와 열 순서를 새 계층에서 복원하는 회귀 검사 22개를 추가했습니다.
- 실제 캡처에서 발견한 재현 결과의 ‘결과를 증거로’ 버튼 하단 1px 잘림을 수정하고 세 창 크기의 부모 영역 내 배치를 검사했습니다.

## 이번 환경의 최종 검증

| 구분 | 결과 | 근거 |
|---|---|---|
| 기본 전체 프로필 | Java **177개 그룹 / 7,704개**, payload HTTP **136개**, Mapper Java **147개**, Node **29개** = **8,016개 통과** | [현재 실행 요약](checks/check-summary.json) · [전체 로그](checks/final-complete.log) |
| Metal 실제 창 | 두 테마 × 세 크기, **162장 / 600개 시나리오**, 컨트롤 2,796개, 스크롤 접근 항목 외 잘림 0·실패 0 | [검증 JSON](native/metal/verification.json) · [측정 CSV](measurements/) |
| Nimbus 실제 창 | 두 테마 × 두 크기, **108장 / 400개 시나리오**, 컨트롤 1,864개, 잘림 0·실패 0 | [검증 JSON·측정](native/nimbus/verification.json) |
| 헤더·Guide·입력·창 동작 | 실제 테마 전환·메뉴·Guide·입력/선택 보존·전체화면/Esc **292개 통과**, 대상 HTTP 0 | [실제 창 검증](native/header/verification.json) |
| 소스 ZIP·독립 재빌드 | ZIP 2,235개 항목 CRC·지문, JAR 입력 281개 일치. 별도 추출본의 strict JDK 17.0.21 재빌드 JAR는 배포본과 **바이트 동일** | [재빌드 JSON](source-rebuild/source-rebuild-verification.json) · [빌드 로그](source-rebuild/build.log) |
| 배포 패키지 | `check` 완료 후 `package`, `verify` 통과. 이전 버전 파일 보존 | [패키지 로그](checks/package.log) · [무결성 로그](checks/package-verify.log) |

각 검증 축의 수는 겹치므로 합산하지 않습니다. 최종 전체 프로필은 `subset=false`, 실패 그룹 0, strict `--release 17`의 단일 완주 결과입니다. 저장소가 기존부터 제외한 과거 UI 검사 2개는 실행 수에 포함하지 않았습니다. Node의 skipped/cancelled는 0입니다.

중간 실행은 화면 설정 저장 문제와 1px 잘림을 발견해 중단했으며 통과한 실행으로 계산하지 않습니다. [최초 중단 로그](checks/initial-interrupted.log), [화면 잘림 수정 전 중단 로그](checks/before-native-fix-interrupted.log), [Metal 수정 전 실패 기록](prior-attempts/metal-before-replay-fix.json)을 보존합니다. 최종 JAR로 전체 프로필과 모든 최종 native 구성을 새로 실행했습니다.

1.4.19 비교 자료는 보존된 공개 JAR `56a744862b09ddd50e2599658c02e275577cd8901f870e9333ca7692eb63ea1d`에 같은 캡처 하네스를 연결해 두 테마를 실제 실행한 결과입니다. [비교본 검증](before-verification.json). 최종 전체 native 원본 270장과 component JSON은 작업환경 `tmp/approved-controls-final/`에, 공개 보고서에는 주요 원본 이미지·전체 측정·이미지 해시를 보존합니다.

## 복구 및 검증 범위

중단된 작업의 소스 수정 이력 137개를 복원했습니다. 기록이 잘린 `ApprovedResearchDesign`의 셀 페인트와 아이콘 helper 끝부분은 승인 시안의 SVG 및 현재 모델 동작을 기준으로 재작성했습니다. 해당 화면의 동작·원본 데이터 유지와 최종 캡처를 다시 검증했습니다.

실제 Linux Xorg/xfwm4의 Swing 창과 합성 Burp API 어댑터를 사용했습니다. Windows Burp 본체·DPI/IME, 수동 브라우저, Docker 전체 스택, 외부 공급자 API, 원본 Next 전체 프로필은 실행하지 않았습니다. 선택적 실제 Katana 수집은 이 환경의 읽기 전용 HOME 초기화 제약 때문에 실행할 수 없으며, 기본 loopback 어댑터 검사와 구분합니다.

최종 JAR SHA-256:

`be2ad66272f6039c64033cf19314d9bddacb503113fea00dd74cbcaaa87f0230`

[전체 최종 검증 JSON](verification.json)
