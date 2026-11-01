# Simple1.4.19 · 달 버튼 테마 전환과 빈 메뉴 수정

달 모양 버튼에 창 도구 메뉴가 연결되어 있었고, 아이콘 전용 원본 버튼의 빈 제목을 복사하면서 다섯 행의 이름과 아이콘이 사라져 Guide만 보였습니다. 달/해 버튼은 Workbench 라이트·다크를 즉시 전환하도록 바로잡았으며, 기존 창 도구는 별도 톱니 메뉴로 옮겼습니다.

- 선택한 테마를 Simple 전용 환경설정에 저장합니다. Burp의 UIManager 기본값을 변경하지 않습니다.
- 설정·전체 화면·로그·대시보드·통합 점검·Guide를 원래 컨트롤러로 실행합니다. 전체 화면 상태의 메뉴 이름 변경과 선택·활성 상태를 유지하고, 팝업은 창 안쪽으로 정렬합니다.
- 계정 선택칸의 두 스타일 코드가 편집기를 반복 교체하던 문제를 공통 UI 상속으로 해결했습니다. 실제 UI·모델·렌더러·입력 문서·미완성 입력·커서·현재 필터를 보존합니다.
- Workbench·Mapper 선택 목록과 스크롤바, 원문 검색 강조도 현재 테마로 그립니다. 검색 태그·원문 오프셋·선택·문서를 교체하지 않습니다.

[설치 JAR](../../../simple/downloads/1.4.19/burp-workbench-simple-1.4.19.jar) · [독립 소스 ZIP](../../../simple/downloads/1.4.19/burp-workbench-simple-1.4.19-source.zip) · [변경 안내](../../../simple/docs/RELEASE_SIMPLE_1.4.19_KR.md) · [전체 검증 JSON](verification.json)

기존 Simple1.4.18을 언로드하고 새 JAR을 등록합니다. 이전 버전 배포 파일은 보존했습니다. 작업 브랜치는 `account-a/header-tools-menu-fix`이며 main 미병합입니다. 현재 네트워크 정책에서 GitHub API 호스트를 사용할 수 없어 PR은 생성하지 못했습니다.

## 실제 화면

[비교 갤러리](COMPARE.html)는 저장소 파일을 로컬에서 열어 라이트·다크와 두 창 크기를 선택할 수 있습니다. 아래 링크는 편집하지 않은 Robot 원본 PNG입니다.

| 창 크기·시작 모드 | 수정 전 메뉴 | 수정 후 톱니 메뉴 | 달/해 클릭 후 화면 |
|---|---|---|---|
| 1600×1000 · 라이트 | [PNG](before/light/1600x1000/header-tools-menu.png) | [PNG](after/light/1600x1000/header-tools-menu.png) | [다크 PNG](after/light/1600x1000/theme-toggled.png) |
| 1600×1000 · 다크 | [PNG](before/dark/1600x1000/header-tools-menu.png) | [PNG](after/dark/1600x1000/header-tools-menu.png) | [라이트 PNG](after/dark/1600x1000/theme-toggled.png) |
| 1024×768 · 라이트 | [PNG](before/light/1024x768/header-tools-menu.png) | [PNG](after/light/1024x768/header-tools-menu.png) | [다크 PNG](after/light/1024x768/theme-toggled.png) |
| 1024×768 · 다크 | [PNG](before/dark/1024x768/header-tools-menu.png) | [PNG](after/dark/1024x768/header-tools-menu.png) | [라이트 PNG](after/dark/1024x768/theme-toggled.png) |

창 크기는 운영체제 장식 포함 크기입니다. 콘텐츠 원본 PNG는 각각1590×966,1014×734이며, 이미지 편집이나 재표본화를 하지 않았습니다. 캡처는 Linux Xorg/xfwm4의 운영 Swing 창에 합성 Burp API 어댑터를 연결한 결과입니다. Windows Burp 본체·DPI/IME 또는 글자 렌더링을 포함한 픽셀 차이0 검증과 구분합니다.

## 검증

| 구분 | 결과 | 근거 |
|---|---|---|
| 전체 선언 검사 | Workbench168그룹7,322 +payload HTTP136 +Mapper Java147 +Node29 = **7,634개 통과** | [검사 요약](checks/check-summary.json) |
| 실제 테마·메뉴 | 두 테마·두 창 크기 **296개 통과**, 대상 HTTP0. 실제 픽셀·선택 목록·입력/필터·설정 저장·전체 화면·Guide·Esc 확인 | [원본 캡처·검증](after/verification.json) |
| 전체 도구 실제 창 | 두 테마·세 작업영역 크기,162장·2,802개 컨트롤·600개 시나리오, 스크롤 접근 항목 외 잘림0·실패0 | [측정 자료](native-all/verification.json) |
| 버튼·창·가이드 | 180개 통과, 대상 HTTP0 | [회귀 검증](native-regressions/verification.json) |
| 패키지·독립 재빌드 | ZIP CRC·소스2,217개·JAR입력273개 해시 통과. JDK17 재빌드 JAR은 배포본과 바이트 동일 | [독립 재빌드](source-rebuild/verification.json) |

위 검증 축의 숫자는 합산하지 않습니다. 전체 도구의162장 원본은 작업환경 `tmp/header-all-completion`에 보존했고, 공개 보고서에는 전체 CSV·컴포넌트 JSON·PNG 해시와 이번 수정의 원본8장을 저장했습니다.

전체 Workbench 실행에서는 Mapper0.3.19를 기대하던 버전 검사 한 그룹만 실패했습니다. 기대값을 실제0.3.20으로 정정한 뒤 해당 그룹18개를 다시 통과했습니다. 나머지 전체 실행 결과와 재실행을 결합했으며, 배포 JAR의 SHA는 변경되지 않았습니다. 최초 실패 요약·로그, 재실행 요약·로그를 `checks/`에 각각 보존했습니다. Mapper·Node 검사 후 `python simple/dev.py package`와 `python simple/dev.py verify`가 통과했습니다. 최초 `release` 명령이 단일 실행으로 성공한 것으로 기록하지 않습니다.

연결창의 펼침/접힘 캡처는 창과 컨트롤 좌표가150ms 이상 안정된 뒤 물리 클릭을 한 번 수행합니다. 기존 선택 상태·높이·역할 표시 검증을 유지하며, 실패를 반복 클릭이나 생략으로 처리하지 않습니다.

배포 JAR SHA-256:

`56a744862b09ddd50e2599658c02e275577cd8901f870e9333ca7692eb63ea1d`
