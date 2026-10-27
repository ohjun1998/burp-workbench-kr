# Workbench Simple 1.0.0

기존 Workbench Next 0.53.4에서 분리한 핵심 작업 중심 버전입니다. 원본 프로젝트와 기존 다운로드 파일을 유지하고, Simple 소스·설치 JAR·설정 저장 공간을 별도로 제공합니다.

**수집 → 분석 → 검증 → 증거**의 네 단계에서 기존 확장 도구를 사용합니다. 요청을 선택하면 **계정 비교 준비 / Repeater 준비 / 증거에 추가**를 바로 실행할 수 있습니다. 탐색·관리·자동 비교 등 부가 실행 화면은 메인 사용 흐름에서 제거했습니다.

| 필요한 파일 | 위치 |
|---|---|
| 설치 JAR | [burp-workbench-simple-1.0.0.jar](downloads/1.0.0/burp-workbench-simple-1.0.0.jar) |
| 독립 빌드 소스 ZIP | [burp-workbench-simple-1.0.0-source.zip](downloads/1.0.0/burp-workbench-simple-1.0.0-source.zip) |
| 무결성 목록 | [SHA256SUMS](downloads/1.0.0/SHA256SUMS) |
| 단계별 그림 가이드 | [HTML 안내서](docs/GUIDE_SIMPLE_KR.html) · [문서 원고](docs/GUIDE_SIMPLE_KR.md) |
| PDF 가이드 | [Workbench Simple 그림 가이드북](downloads/guides/1.0.0/Workbench_Simple_1.0.0_Guide_KR.pdf) |
| 새 Simple 검증 결과 | [검증 기록](docs/VERIFICATION_SIMPLE.json) |

## 설치와 첫 사용

1. 기존 Workbench JAR와 파일을 보관합니다. 한 확장에서만 수집하려면 기존 확장의 로드를 해제하고 Simple을 사용합니다.
2. Burp **Extensions → Add → Java**에서 Simple JAR를 추가하고 **Workbench Simple** 탭을 엽니다.
3. **SCOPE 목록**에서 수집할 URL과 체크 상태를 정하고 수집 버튼을 **OFF → SCOPE**로 바꿉니다.
4. 대상 사이트를 사용한 뒤 요청을 선택합니다. 분석이 필요하면 **2 분석**, 직접 비교할 때는 **계정 비교 준비**를 사용합니다.
5. 원본 또는 비교 결과를 **4 증거**에 추가하고 **가림 미리보기 → 증거 ZIP 저장**으로 저장합니다.

Simple의 설정 키는 `simple.*`, 저장 폴더는 `.burp-workbench-simple`입니다. 기존 Next의 설정·초안·보관 폴더와 분리합니다. 내장 Feature Mapper, 요청/응답·JS·서버/경로·응답 분석·계정 비교·입력값 검사 엔진을 유지하며, 페이지 이동만으로 대상 요청을 전송하지 않습니다.

## 안내서 캡처

그림은 Simple 운영 Swing 화면에 합성 예제 패킷을 넣어 렌더링한 캡처입니다. 실제 Burp GUI를 조작한 캡처가 아닙니다. 예제 URL·계정·판정 결과를 실제 대상 검증 결과로 해석하지 않습니다. 실제 실행한 검사와 미실행 범위는 새 Simple 검증 기록을 확인합니다.

## 개발

Simple 폴더에서 완전한 JDK를 준비한 뒤 `python dev.py build`, `python dev.py check`를 사용합니다. 기본 빌드는 `--release 17`을 유지합니다. 이 작업 환경은 Java 21의 `ct.sym`이 없어 명시적인 `--source-target` 옵션으로 소스·바이트코드 수준 17을 지정해 검증합니다. 이는 Java 17 API 호환성 검증을 의미하지 않으며 실제 실행 범위는 검증 기록을 확인합니다. 원본 0.53.4의 빌드 결과와 검사 수를 Simple 검증으로 합산하지 않습니다.

캡처는 Simple 테스트 어댑터 빌드 후 생성합니다. PDF 생성에는 Python 패키지 `reportlab`과 `Pillow`가 필요합니다. 캡처와 가이드는 다음 순서로 재생성합니다.

```sh
python tools/render_simple.py --out docs/images --check
python tools/build_simple_guide.py
```

이 작업 환경의 컴파일 방식을 재현할 때는 `python dev.py check --source-target`과 `python tools/render_simple.py --source-target --out docs/images --check`를 사용합니다. 완전한 JDK에서는 위의 기본 명령을 사용하세요. 독립 소스 ZIP의 구체적인 빌드·패키지 명령은 `dev.py --help`와 검증 기록을 확인하세요. GPL 및 제3자 고지는 원본과 함께 보존합니다.
