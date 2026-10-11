# 저장소 안내

[첫 화면](../README.md) · [문서 목차](README.md) · [다운로드](../downloads/README.md)

다운로드는 `downloads/`, 사용법·현재 변경은 `docs/`, 운영 소스는 `project/`를 기준으로 찾습니다. 원래 개발 패키지의 중첩 구조는 빌드·재현 경로와 과거 근거를 보존하기 위해 유지합니다.

## 사용하는 파일

| 목적 | 공식 위치 | 선택 방법 |
|---|---|---|
| 확장 JAR와 소스 ZIP | [`downloads/extensions/`](../downloads/extensions/) | 확장 버전별 폴더. 최신 선택은 다운로드 목차 기준 |
| Docker 실행 ZIP | [`downloads/docker/`](../downloads/docker/) | 사이트 버전별 폴더. 확장 버전과 독립 관리 |
| PDF 가이드북 | [`downloads/guides/`](../downloads/guides/README.md) | 확장 버전과 맞는 가이드 선택 |
| 현재 사용 흐름·변경·검증 | [`docs/`](README.md) | 사용법, 릴리스 안내, 검증 JSON, 인수인계 |

버전 폴더의 파일은 덮어쓰지 않습니다. 파일을 열고 GitHub의 **Download raw file**로 받으세요. 실행 파일과 소스를 찾기 위해 `project/` 안의 과거 문서를 순서대로 열 필요는 없습니다.

## 개발하는 파일

| 목적 | 위치 |
|---|---|
| 공통 실행 진입점 | [`dev.py`](../dev.py) |
| 확장 구현 | [`project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java/`](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java/) |
| 확장 회귀 검사 | [`project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/test/java/`](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/test/java/) |
| 공식 확장 빌드 | [`project/burp-development-workspace/burp-workbench-kr/burp-workbench/build_all.py`](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/build_all.py) |
| 전체 기능 HTTP 하네스 | [`project/burp-development-workspace/burp-workbench-kr/burp-workbench/validation/all-features/`](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/validation/all-features/) |
| 실습 사이트 소스·Docker 설정 | [`project/burp-development-workspace/community-security-lab/`](../project/burp-development-workspace/community-security-lab/) |
| 버전별 다운로드 생성 | [`tools/package_downloads.py`](../tools/package_downloads.py) |
| GitHub 자동 검증 | [`.github/workflows/check.yml`](../.github/workflows/check.yml) |
| PDF 가이드 생성 | [`tools/build_guide.py`](../tools/build_guide.py), [`docs/GUIDE_0.24.0_KR.md`](GUIDE_0.24.0_KR.md) |
| 크기·테마별 화면 배치 검사 | [`tools/render_ui_layout.py`](../tools/render_ui_layout.py), [`docs/UI_0.24.0_KR.md`](UI_0.24.0_KR.md) |

`dist/`는 개발 중 빌드 결과이고 `downloads/`는 공유할 버전별 산출물입니다. 배포는 `python dev.py release`, 현재 소스와 다운로드의 대응 확인은 `python tools/package_downloads.py --verify`를 사용합니다.

## 상태와 과거 기록

| 위치 | 보존하는 내용 | 읽을 때 주의점 |
|---|---|---|
| [`PROJECT_STATUS_KR.md`](../PROJECT_STATUS_KR.md) | `main`에 합쳐진 상태 | 미병합 기능은 PR에서 확인 |
| [`AGENTS.md`](../AGENTS.md), [`docs/COLLABORATION_KR.md`](COLLABORATION_KR.md) | 공통 작업 규칙 | 새 작업 시작 전에 확인 |
| [`audit/0.21.0/`](../audit/0.21.0/) | 이전 모의 진단·개선 후보·재현 코드 | 제안과 구현 완료를 구분 |
| [`release/`](../release/) | 0.21.0 배포 원본과 당시 안내 | 현재 테스트 파일은 `downloads/` 사용 |
| [`history/`](../history/) | 과거 버전 문서·이미지·PDF·검증 | 현재 UI·현재 실행 결과로 해석하지 않기 |
| [`docs/archive/`](archive/) | 현재 문서가 대체한 이전 원문 | 당시 기록 그대로 보존 |
| [`setup/`](../setup/) | 기존 패키지 복원·환경 준비 기록 | 당시 환경과 이번 환경 구분 |
| [`reproduction/`](../reproduction/) | 네이티브 관찰자·참고 런처·런타임 | 옛 절대 경로 런처는 환경에 맞게 수정 |
| [`이어가기_인수인계.md`](../이어가기_인수인계.md) | 최초 0.21.0 인수인계 | 현재 후속 내용은 버전별 인수인계 우선 |
| `PACKAGE_MANIFEST.json`, `SETUP_MANIFEST.json` | 최초 패키지·설정 파일의 해시 기록 | 현재 작업 트리 전체를 검증하는 목록이 아님 |

새 사용 문서는 `docs/`에 추가하고 이 목차에 연결합니다. 새 다운로드는 종류별 버전 폴더에 추가합니다. 과거 파일의 이름이나 위치를 바꿔 이전 검증 근거를 현재 결과처럼 보이게 하지 않습니다.
