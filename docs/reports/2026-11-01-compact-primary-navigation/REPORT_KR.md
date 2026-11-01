# Simple 1.4.21 · 통합 점검 / 대시보드 컴팩트 강조형

사용자가 선택한 C안으로 왼쪽 최상단에 **통합 점검·대시보드**를 각각 42px 한 줄로 적용했습니다. 얇은 색상 테두리·왼쪽 포인트·아이콘·화살표로 강조하고, 아래 도구 목록을 스크롤해도 두 줄이 고정됩니다. 통합 점검은 청록색, 대시보드는 파란색이며 현재 대시보드의 선택 상태를 표시합니다.

[설치 JAR](../../../simple/downloads/1.4.21/burp-workbench-simple-1.4.21.jar) · [독립 소스 ZIP](../../../simple/downloads/1.4.21/burp-workbench-simple-1.4.21-source.zip) · [체크섬](../../../simple/downloads/1.4.21/SHA256SUMS) · [사용 안내](../../../simple/docs/RELEASE_SIMPLE_1.4.21_KR.md) · [실제 화면 갤러리](GALLERY.html)

기존 1.4.20을 언로드한 뒤 새 JAR를 등록합니다. Mapper0.3.21·도우미0.4.1은 유지하며 이전 배포물은 보존했습니다. 작업 브랜치는 `account-a/compact-primary-navigation`이고 완료된 1.4.20 변경을 포함합니다. main 미병합이며, 현재 환경의 `api.github.com` 접근 제한으로 PR은 생성하지 못했습니다.

## 실제 적용 화면

| 상태 | 라이트 | 다크 |
|---|---|---|
| 상단 두 메뉴와 일반 도구 | [실제 창](native/primary/light/1600x1000/01-navigation.png) | [실제 창](native/primary/dark/1600x1000/01-navigation.png) |
| 대시보드 선택 | [실제 창](native/primary/light/1600x1000/03-dashboard.png) | [실제 창](native/primary/dark/1600x1000/03-dashboard.png) |
| 통합 점검 준비 창 | [실제 창](native/primary/light/1600x1000/02-inspection.png) | [실제 창](native/primary/dark/1600x1000/02-inspection.png) |
| 1024×600에서 목록 아래로 스크롤 | [고정 위치](native/primary/light/1024x600/04-sidebar-bottom.png) | [고정 위치](native/primary/dark/1024x600/04-sidebar-bottom.png) |

[승인한 C안](design/compact-options-proposal.png)은 생성한 제안 이미지입니다. 위 PNG 48장은 최종 JAR의 실제 Linux Swing 창을 Robot으로 캡처한 원본이며 편집·재표본화하지 않았습니다. 창 크기와 클라이언트 영역의 PNG 크기는 다를 수 있고 실제 좌표·크기·해시는 검증 JSON에 기록했습니다. 기존 16개 도구의 두 테마 화면도 [원본 이미지 목록](images/all-tools/manifest.json)에 보존합니다.

## 유지한 동작과 수정

- 통합 점검은 기존 준비 컨트롤러와 창을 엽니다. 반복 클릭·닫았다 다시 열기에서 같은 패널·입력 문서·URL 초안·한도를 유지합니다. 실제 시작은 준비 화면에서 사용자가 직접 선택합니다.
- 대시보드는 기존 현재 실행의 결과·스냅샷을 보여 줍니다. 하단의 중복 항목을 위로 옮겼고, 기존 톱니 메뉴·빠른 탐색의 원래 컨트롤러도 유지합니다. 반복 클릭해도 선택 표시가 유지됩니다.
- 일반 도구 11개 항목, 기존 8개 엔진, 입력·필터·원본 ID/요청·응답 바이트·열 너비 저장 경로와 메뉴 숨김/복원을 유지합니다. 실제 Tab·Space·Enter와 뒤로/앞으로를 검증했습니다.
- 작은 메뉴 폭에서 마지막 항목으로 스크롤할 때 상단 두 줄이 수평으로 밀리던 결함을 수정했습니다. 기존 JScrollPane의 column header에 고정하고, viewport 폭을 추종하는 classic 트리는 수직으로만 스크롤합니다. 페이지의 표시 설정 경로는 바꾸지 않습니다.
- 원래 버튼의 활성·선택 상태를 반영하고, 언로드 시 리스너를 해제하며 이후 동작을 차단합니다. 두 메뉴를 누르거나 테마·도구를 바꾸는 동작으로 HTTP 요청이나 점검을 시작하지 않습니다.

## 최종 검증

| 구분 | 결과 | 근거 |
|---|---|---|
| 기본 전체 프로필 | Java **178그룹 / 7,789개**, payload HTTP **136개**, Mapper Java **147개**, Node **29개** = **8,101개 통과** | [요약](checks/check-summary.json) · [전체 로그](checks/final-complete.log) |
| 상단 메뉴 실제 조작 | 두 테마 × 1600×1000·1280×800·1024×768·1024×600, **808개 / PNG 48장**, 대상 HTTP 0·점검 시작 없음 | [검증 JSON](native/primary/verification.json) |
| Metal 전체 도구 | 두 테마 × 세 크기, **600개 시나리오 / 컨트롤 3,048개 / PNG 162장**, 스크롤 접근 항목 외 잘림 0·실패 0 | [검증·측정](native/all-tools/metal/verification.json) |
| Nimbus 전체 도구 | 두 테마 × 두 크기, **400개 시나리오 / 컨트롤 2,032개 / PNG 108장**, 스크롤 접근 항목 외 잘림 0·실패 0 | [검증·측정](native/all-tools/nimbus/verification.json) |
| 독립 소스 재빌드 | ZIP **2,238개** CRC·안전 추출·지문, JAR 입력 **282개** 일치. 별도 추출본의 strict JDK17 재빌드 JAR가 설치본과 **바이트 동일** | [검증 JSON](source-rebuild/source-rebuild-verification.json) · [빌드 로그](source-rebuild/build.log) |
| 패키지 | 전체 검사와 실제 창 검증 후 Simple 입력 동결 → `package` → `verify` 통과 | [생성 로그](checks/package.log) · [검증 로그](checks/package-verify.log) |

각 축은 겹치므로 합산하지 않습니다. 전체 프로필은 `subset=false`, 실패 그룹 0이며 strict `--release 17`의 단일 완주 결과입니다. 저장소가 기존부터 제외한 과거 UI 그룹 2개는 실행 수에 포함하지 않았고 Node skipped/cancelled는 0입니다. [집중 12그룹 673개](checks/focused-summary.json)도 별도로 통과했습니다. 중간 실패와 하네스 교정은 [이력](prior-attempts/README_KR.md)에 보존하며 최종 통과 수에 포함하지 않습니다.

전체 all-tool 원본 270장과 component JSON은 작업환경 `tmp/compact-primary-regression/`에 있습니다. 공개 보고서에는 주요 원본 32장·전체 측정 CSV·각 구성 결과·전체 이미지 해시를 보존합니다. 재현 도구는 `tools/run_compact_primary_native.sh`와 `simple/tools/run_approved_all_native.sh`이며 JDK17·Xorg dummy·xfwm4·D-Bus가 필요합니다.

실제 창 검증은 Linux Xorg/xfwm4와 합성 Burp API·패킷 기준입니다. Windows Burp 본체·DPI/IME, 수동 브라우저, Docker 전체 스택, 외부 공급자 API, 원본 Next 전체 프로필과 실제 Katana 수집은 이번에 실행하지 않았습니다.

최종 JAR SHA-256: `9d3d20af946add3498da79a021be31fafe8d84e046352463c4a559616187301c`

[전체 검증 요약](verification.json)

공개 커밋 `03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d`의 JAR·소스 ZIP·도우미 ZIP·체크섬·당시 보고서·두 실제 화면을 다시 내려받았습니다. 7개 모두 HTTP200·크기·SHA-256이 일치하고 JAR/ZIP CRC도 통과했습니다. [공개 다운로드 검증](public-download-verification.json).
