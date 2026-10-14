# 0.25.0 인수인계 - 처음 쓰는 사람을 위한 이름과 안내

사용자가 Workbench KR을 계속 고도화하기로 선택했습니다. 기능 이름을 보고 용도를 예상하고, 처음 열었을 때 다음 동작을 찾을 수 있게 만드는 것이 이번 목표입니다.

## 변경 위치

- `FeatureGuide.java`: 안정적인 기능 ID, 화면 이름, 용도, 시작 순서와 추가 전송 설명.
- `WorkbenchExtension.java`와 각 패널: 같은 이름을 사용하는 탭·제목·우클릭 메뉴. `기능 안내`와 F1에서 전체 기능을 찾아 화면으로 이동.
- `docs/GUIDE_0.25.0_KR.md`, `tools/build_guide.py`, `tools/GuidePreview.java`: 처음 사용하는 사람을 위한 PDF와 운영 Swing 미리보기.
- `FeatureGuideWorkflowTest.java`, `tools/UiLayoutPreview.java`, `tools/render_ui_layout.py`: 실제 내장 입력값 검사까지 포함한 탐색·안내·화면 검증.
- `downloads/extensions/0.25.0/`, `downloads/guides/0.25.0/`: 같은 버전의 JAR·소스·PDF.

기존 클래스·설정 키·관측 모델을 기능 이름 때문에 새 모델로 옮기지 않습니다. ScopeFlow의 UI로 교체하는 작업이 아니며 Workbench의 기존 작업 흐름과 도구를 사용합니다. 과거 배포와 검증 자료는 보존합니다.

## 이어받은 기준

원격 main `747187431077fab7657af24b08f7dd24df2a08fc`에서 `account-a/0.25.0-beginner-guide`를 만들었습니다. 아직 미병합인 PR #6의 0.24.0 구현 `be41a38c87f505363e2bc27574916c3faf988cee`는 통합 커밋 `449f9807b7c6ca4c7c8d2c89dfcaab86ddeebfda`로 보존합니다. 새 작업은 Issue #7에서 추적하고 PR로 전달합니다. 다른 계정의 브랜치와 main은 직접 갱신하지 않습니다.

## 검증 확인

이번 실행의 검사 수, 명령, PDF 페이지 수, 산출물 해시와 남은 범위는 [VERIFICATION_0.25.0.json](VERIFICATION_0.25.0.json)을 기준으로 확인합니다. 이전 배포의 검사 수를 합산하지 않습니다.

```sh
python dev.py release
python tools/render_ui_layout.py --out tmp/ui-layout-0.25.0 --check
python tools/render_ui_layout.py --guide-only --out tmp/ui-guide-0.25.0 --check
python tools/build_guide.py --refresh-previews
python -m unittest discover -s tools -p 'test_*.py' -v
python tools/package_downloads.py --verify
```

실제 Burp GUI, OS별 DPI·글꼴·포커스, 브라우저 DOM 실행, Docker 전체 스택, 실제 Collaborator/외부 BApp은 별도 환경에서 확인해야 합니다. 화면 그림은 현재 코드와 합성 데이터를 사용한 헤드리스 Swing 미리보기입니다.

## 사용 설명에서 지킬 점

- 화면 이동·요청 연결·Repeater 준비는 실행과 구분합니다. 즉시 실행하는 메뉴와 자동 비교 ON은 실제 동작을 설명합니다.
- URL 수집의 로컬 출처와 외부 아카이브 출처를 구분하고, 수집된 후보를 존재·권한 확인 결과로 쓰지 않습니다.
- 계정 비교 실습은 Cookie 없는 로그인 요청으로 독립 세션을 만들고 `/api/auth/me`로 주체를 확인합니다.
- 결과 후보, 수동 재현, 점검 완료, 실제 저장 증거는 다른 상태입니다.
- PDF·화면 이름을 다시 바꾸면 원고·미리보기·탐색 테스트를 함께 갱신합니다. 기존 버전 다운로드를 덮어쓰지 않습니다.
