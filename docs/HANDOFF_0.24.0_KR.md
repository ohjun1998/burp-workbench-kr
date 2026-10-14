# 0.24.0 인수인계

담당 계정 A. 작업 브랜치 `account-a/0.24.0-ui-layout`, [Issue #5](https://github.com/ohjun1998/burp-workbench-kr/issues/5), [작업 브랜치 PR](https://github.com/ohjun1998/burp-workbench-kr/pulls?q=is%3Apr+head%3Aaccount-a%2F0.24.0-ui-layout). 출발 main은 `747187431077fab7657af24b08f7dd24df2a08fc`, 이전 PR #4 head는 `8c6e5919f631809d1e23870b81a1edc54485bc10`입니다. 이전 변경을 보존한 원격 통합 커밋은 `90615c9840f8cc76509912b32c3d5f2ee3535e8c`입니다. 합쳐진 상태만 기록하는 `PROJECT_STATUS_KR.md`는 수정하지 않습니다.

## 전달하는 변경

| 영역 | 변경 내용 | 주요 소스 |
|---|---|---|
| 공통·트래픽 | 기본 분할 배치·문장 입력 스타일, 비활성 강조 버튼의 중립색, 표 최소 폭, 빈 목록·검색 동작 비활성 | `WorkbenchUi.java`, `WorkbenchExtension.java` |
| 활동·계정·업무 | URL 버튼을 해당 탭으로 이동, 선택에 따른 버튼 상태, 계정·입력 표 공간, 증거 파일 메뉴 | `ActivityPanel.java`, `AssessmentPanel.java` |
| JS | 원문 위 도구 축소, 원문·결과 공간, 출처 툴팁, 현재 탭·선택에 맞춘 동작 | `JsAnalysisPanel.java` |
| 비교·응답·점검 지원 | 좌우 원본 가져오기, 탭별 API·마커 동작, 업무 흐름 결과 상세, 증거 저장 배치 | `StructuredComparePanel.java`, `ResponseReconPanel.java`, `BountySupportPanel.java` |
| 검증·가이드 | 크기·테마별 화면 측정, UI 회귀, 현재 화면의 PDF·원고·안내 | `tools/render_ui_layout.py`, `tools/UiLayoutPreview.java`, `docs/UI_0.24.0_KR.md`, `docs/GUIDE_0.24.0_KR.md` |

Java 파일은 `project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java/kr/workbench/`에 있습니다. 회귀 검사는 인접한 `src/test/java/kr/workbench/`의 `UiLayoutTest`, `AssessmentUiOptimizationTest`, `JsUiOptimizationTest`, `UiPanelsOptimizationTest`와 기존 검사를 사용합니다.

기존 권한·SCOPE·증거 가림·수동 완료·저장/조회 판정 절차를 유지합니다. UI 변경으로 검사 실행이나 선택만으로 점검이 완료되도록 바꾸지 않았습니다. lab와 Docker는 3.5.0을 사용하며 이번 작업에서 별도 버전을 만들지 않습니다. 이전 0.23.0 JAR·소스·PDF·검증 자료는 그대로 보존합니다.

## 검증 기록

`python dev.py release`가 종료 코드 0으로 완료되었습니다. 회귀·API·헤드리스 Swing 1,136개/38묶음(신규·확장 84개 포함), 페이로드 HTTP 127개, 전체 기능 HTTP 100개/199요청, 커뮤니티 모의 진단 266개/259요청으로 **기능 검사 총 1,629개와 패키징 검사 4개가 통과**했습니다. 현재 소스의 전체 릴리스 검사와 화면 검사를 순서대로 실행해 [VERIFICATION_0.24.0.json](VERIFICATION_0.24.0.json)에 수치·로그·환경·산출물 해시를 기록했습니다.

다운로드와 소스 지문이 일치하고 이전 버전의 배포 파일을 보존했습니다. lab 3.5.0 소스 130개가 유지되며, 모의 진단 중 운영 소스 불변과 loopback 18099/18399 종료를 확인했습니다. 최종 로그는 `docs/verification/0.24.0/`에 있습니다. GitHub CI 상태는 작업 브랜치 PR에서 별도로 확인합니다.

```sh
python dev.py release
python tools/render_ui_layout.py --out tmp/ui-layout --check
python tools/package_downloads.py --verify
```

화면 비교는 이전 0.23.0 JAR와 현재 운영 Swing 코드를 같은 조건에서 렌더링합니다. 기본 조건은 1024×720, 1280×800, 1440×920의 확장 작업 영역과 밝은/어두운 테마입니다. JS 대체 배치를 포함해 조건별 16개, 총 96개 화면의 버튼·입력 위치와 핵심 작업 영역 노출 크기를 측정합니다. 개선 전 작은 화면에서 JS 수동 원문과 업무 흐름 상세의 실제 노출 높이 0px를 재현한 기록은 `docs/UI_0.24.0_KR.md`에 연결합니다. `.github/workflows/check.yml`도 기능 검사 뒤 화면 배치 게이트를 실행합니다.

최종 화면 게이트는 **96개 화면·918개 컨트롤**, 스크롤 밖 잘림 0건, 24px 미만 컨트롤 0건, 핵심 작업 영역 미달 0건입니다. 1024×720에서 JS 기본·대체 배치 원문 노출 높이 99px, 업무 흐름 상세 214px를 확인했습니다. [측정 결과](ui/0.24.0/after/layout-report.json)와 `docs/ui/0.24.0/after/`의 CSV·PNG를 확인하세요.

크기 측정은 Swing 이벤트 큐의 배치를 마친 뒤 수행하고, 표처럼 스크롤 가능한 영역과 스크롤 밖으로 잘린 동작을 구분했습니다. PDF는 23쪽·번호를 붙인 화면 그림 15개이며 전체 페이지 렌더와 시각 검토, 같은 입력의 재생성 해시 일치를 확인했습니다. 초기 원문 공간 부족, 테스트 클래스와 배포 JAR의 글꼴 차이, JS 대체 배치의 원문 붕괴를 수정한 뒤 최종 JAR 기준으로 재검증했습니다. 준비 중 실행과 이전 버전 검사는 최종 통과 수에 합산하지 않았습니다.

## 다운로드·문서 위치

- 확장 JAR·소스: `downloads/extensions/0.24.0/`
- PDF: `downloads/guides/0.24.0/Burp_Workbench_KR_0.24.0_guide.pdf`
- Docker 실행 ZIP: 기존 `downloads/docker/3.5.0/`
- 변경 안내: [RELEASE_0.24.0_KR.md](RELEASE_0.24.0_KR.md)
- 현재 사용 흐름: [TEST_WORKFLOW_KR.md](TEST_WORKFLOW_KR.md)
- 개발·화면·PDF 재생성: [DEVELOPMENT_SETUP_KR.md](../DEVELOPMENT_SETUP_KR.md)

현재 문서 목차는 0.24.0으로 갱신하고 0.23.0 릴리스·검증·인수인계·PDF 원고 링크는 이전 변경 항목에 유지합니다. 최초 0.21.0 개발 환경 원문과 기존 소스·이력 경로도 보존합니다.

## 검토와 병합

최신 PR의 GitHub Actions와 다운로드 해시, 화면·기능 검증의 실제 범위를 확인합니다. 이전 PR #4를 포함한 이번 통합 PR을 검토하여 중복 병합을 피합니다. 이전 브랜치·커밋·배포물은 보존합니다. 병합 후 `PROJECT_STATUS_KR.md`를 확장 0.24.0 / 사이트 3.5.0의 합쳐진 상태로 갱신합니다.

## 남은 확인 범위

이번 UI 그림·측정은 실제 Burp GUI가 아닌 운영 Swing 코드의 헤드리스 렌더링입니다. 실제 Burp에서 창 크기를 바꾸고, 키보드 이동·툴팁·팝업 메뉴·분할 막대와 운영체제별 글꼴·DPI를 확인해야 합니다. 브라우저 DOM·저장형 실행, 전체 Docker/Oracle/Nginx, 실제 Pro Collaborator·외부 BApp, 장시간 JS 수집은 현재 실행 근거와 구분합니다.

0.23.0의 기능 한계도 유지합니다. 원본 연결은 128KiB 및 보관 건수 제한이 있고, 브라우저 실행은 사용자 확인과 이미지 근거입니다. 일반 HTML·바이너리 원문 파일 첨부, 자동 `/me` 신원 검증, 정책 태그별 URL 대표 선택은 별도 개선 후보입니다.
