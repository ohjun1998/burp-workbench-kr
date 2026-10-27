# Workbench Simple 1.1.0

**기존 8개 통합 확장 도구의 핵심 기능을 모두 유지하면서 메인 화면에서 바로 사용하도록 정리한 버전입니다.** 기존 Workbench Next와 Simple 1.0.0의 JAR·소스·PDF를 보존합니다.

원본 선택 → 필요한 도구 → 결과 확인·증거 저장으로 이어갑니다. 도구를 정해진 순서대로 거칠 필요는 없습니다.

| 필요한 파일 | 위치 |
|---|---|
| 설치 JAR | [burp-workbench-simple-1.1.0.jar](downloads/1.1.0/burp-workbench-simple-1.1.0.jar) |
| 독립 빌드 소스 | [소스 ZIP](downloads/1.1.0/burp-workbench-simple-1.1.0-source.zip) · [SHA256SUMS](downloads/1.1.0/SHA256SUMS) |
| 화면별 가이드 | [HTML 안내서](docs/GUIDE_SIMPLE_KR.html) · [문서 원고](docs/GUIDE_SIMPLE_KR.md) |
| PDF 가이드 | [Workbench Simple 그림 가이드북](downloads/guides/1.1.0/Workbench_Simple_1.1.0_Guide_KR.pdf) |
| 새 버전 검증 결과 | [실제 검사와 미실행 범위](docs/VERIFICATION_SIMPLE_1.1.0.json) |

## 8개 도구의 핵심 기능

| 기존 도구 | 유지한 핵심 기능 | 부가 흐름 정리 |
|---|---|---|
| 요청·응답 (요청·응답 목록) | SCOPE, OFF/SCOPE 수집, 검색, 요청·응답 원본, Repeater 준비와 증거 연결 | 중복 탐색·작업 관리 바 제거. 저장 검색 조건은 검색 설정에서 필요할 때 사용. |
| 기능 매핑 (기능 자동 매핑) | 기능·실행·세션 분류, 패킷·분류 근거, 프로젝트 저장·열기와 점검 업무 연결 | 핵심 분류·프로젝트 흐름 유지. 브라우저 도우미와 세부 설정은 필요한 경우 선택. |
| 계정 비교 (계정 권한 비교) | 계정 연결, 세션·권한·쓰기 조회 조건, 선택 요청 직접 비교, 중지와 결과 근거 | 신규 요청 자동 비교 토글 제거. 선택한 원본의 준비와 직접 실행에 집중. |
| API 찾기 (JS에서 API 찾기) | 관측 텍스트 응답의 API·키 후보, 원본 위치·로컬 이력, 선택 API의 요청 준비 | 시작 시 기존 History 자동 순회 제거. 부가 NPM·소스맵 조회는 메인 흐름에서 제외. |
| 서버·경로 (서버·경로 탐색) | 관측 서버·경로 정보, 후보 경로, 선택 경로의 직접 GET 확인 | 선택 대상과 확인 조건 중심으로 사용. 보조 설정·수집 경로는 필요할 때 열기. |
| 응답 분석 (응답 보안 분석) | 보안 단서, API 문서, 저장 후 조회 흐름과 원본·증거 연결 | 주요 분석 기능 유지. 보조 설정은 필요할 때 열고 원본 근거 확인에 집중. |
| 입력값 검사 (입력값 보안 검사) | 검사 유형, 입력 위치·값, 간격·한도·SCOPE, 기준·변경·대조 결과와 증거 연결 | 검사 조건과 결과 검토에 집중. 선택 결과에서 증거로 바로 연결. |
| 재현·증거 (재현·증거 정리) | 요청 순차 재현·중지·결과 확인, 원본·비교·재현 결과 추가, 가림과 ZIP 저장 | OAST·외부 도구·브라우저 보조 화면 제거. 요청 재현과 증거 정리 두 화면 유지. |

## 설치와 첫 사용

1. Simple 설치 JAR burp-workbench-simple-1.1.0.jar를 준비하고 기존 Workbench 파일을 보관합니다.
2. Burp Extensions → Add → Java에서 Simple JAR를 선택하고 Workbench Simple 탭을 엽니다.
3. 한 확장에서만 수집하려면 기존 Workbench의 로드를 해제하고 Simple을 사용합니다. 기존 확장이나 원본 파일을 삭제할 필요는 없습니다.
4. 요청·응답 목록에서 SCOPE를 등록하고 OFF → SCOPE로 바꾼 뒤 사이트를 사용합니다.
5. 원본 요청을 선택하고 필요한 도구를 엽니다. 결과를 확인한 뒤 재현·증거 정리에서 가림 미리보기 → 증거 ZIP 저장으로 저장합니다.

Simple 설정 키는 `simple.*`, 저장 폴더는 `.burp-workbench-simple`입니다. 기존 Next와 설정·초안·보관 폴더가 분리됩니다. 기존 Simple 설정은 같은 Simple 저장 공간을 사용합니다.

## 요청 재현과 증거

재현·증거 정리는 **요청 재현 / 증거 정리** 두 핵심 화면을 유지합니다. 선택한 원본을 순서대로 추가해 직접 재현하고 결과를 증거에 연결할 수 있습니다. OAST·외부 도구·브라우저 보조 화면은 메인 사용 흐름에서 제외합니다.

## 안내서 캡처

그림은 Simple 운영 Swing 화면에 합성 예제 패킷을 넣어 렌더링한 캡처입니다. 실제 Burp GUI를 조작한 캡처가 아닙니다. 예제 URL·계정·판정 결과는 실제 대상의 검증 결과가 아닙니다. 실제 실행한 검사와 미실행 범위는 위의 버전별 검증 기록을 확인합니다.

## 개발과 재생성

완전한 JDK에서 `python dev.py build`, `python dev.py check`를 사용합니다. 기본 컴파일은 `--release 17`입니다. 이 작업 환경은 Java21의 `ct.sym`이 없어 명시적인 `--source-target`으로 소스·바이트코드 수준17을 지정합니다. Java17 표준 API 호환성 검증과 구분하며 실제 결과는 버전별 검증 기록에 남깁니다.

캡처는 테스트 어댑터 빌드 후 생성합니다. PDF 생성에는 Python `reportlab`과 `Pillow`가 필요합니다. 가이드 버전은 BuildInfo에서 읽으므로 파일명과 캡처 경로도 함께 바뀝니다.

```sh
python tools/render_simple.py --check
python tools/build_simple_guide.py
```

이 환경의 컴파일 방식은 `python dev.py check --source-target`, `python tools/render_simple.py --source-target --check`로 재현합니다. 원본 Next와 이전 Simple의 검사 수를 새 검사 결과로 합산하지 않습니다. GPL 및 제3자 고지는 보존합니다.

[보존한 Simple1.0.0 안내서](docs/history/1.0.0/GUIDE_SIMPLE_KR.html) · [Simple1.0.0 PDF](downloads/guides/1.0.0/Workbench_Simple_1.0.0_Guide_KR.pdf)
