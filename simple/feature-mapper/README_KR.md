# Simple 내장 Feature Mapper 0.3.7

Workbench Simple 1.4.0의 기존 8개 도구 중 **기능 매핑**에서 요청·응답을 메뉴·기능·실행·세션에 연결합니다. 별도 Mapper JAR를 설치할 필요가 없습니다. 프로젝트 저장·열기와 `.fmkr` 형식은 유지합니다.

[Simple 설치·가이드](../README_KR.md) · [helper 0.5.0 ZIP](../downloads/1.4.0/feature-mapper-browser-helper-0.5.0.zip) · [이전 구성요소 안내 보존본](../docs/history/1.3.2/feature-mapper-README_KR.md)

## 기본 사용

1. Simple JAR를 Burp에 설치하고 **요청·응답 → SCOPE 목록**에서 대상을 정합니다.
2. 일반 사이트 사용을 매핑하려면 수집을 **OFF → SCOPE**로 바꾸고 사이트를 사용합니다. 통합 점검의 명시적 실행과 일반 수집 상태는 구분합니다.
3. **기능 매핑**에서 기능·실행·세션을 선택하고 원본 패킷과 분류 근거를 확인합니다.
4. 필요한 항목의 기능 이름·메모·점검 상태를 정리하거나 프로젝트를 저장합니다. Repeater 준비는 전송하지 않으며 Send를 직접 실행합니다.

## 원터치 브라우저 탐색 연결

1. helper 0.5.0 ZIP을 별도 폴더에 풀고 Chrome/Edge의 압축해제된 확장으로 불러옵니다.
2. Workbench의 매핑 설정에서 브라우저 연결 주소와 토큰을 확인하고 helper에 입력합니다.
3. 대상 사이트를 활성화하고 브라우저에서 로그인한 뒤 **SPA 통합 탐색 허용**을 켭니다.
4. Workbench **SCOPE 통합 점검**에서 브라우저 모드를 선택하고 시작합니다.

helper는 별도 탐색 탭에서 메뉴·탭·지연 로딩·같은 Scope의 iframe 경로를 수집하고, Fetch/XHR 요청·응답·실패를 메타데이터로 연결합니다. 응답 대기는 제한하며 남은 요청은 미확인으로 표시합니다. 현재 사용자 탭은 유지합니다.

브라우저 세션과 HTTP 점검 계정은 별개입니다. 브리지는 쿠키·응답 본문·쿼리 값을 HTTP 계정으로 복사하지 않습니다. 브라우저 응답 상태만으로 HTTP 본문 분석 완료를 표시하지 않습니다. Scope·GET/HEAD·변경 경로 제외 규칙을 유지합니다.

동적 API와 실제 관측 경로의 연결은 `PATH_SHAPE_ONLY` 후보입니다. 변수 복원·동일 인증·권한·메뉴 인과를 입증하거나 실행 가능성을 바꾸지 않습니다.

## 검증과 개발

Simple 전체 검사:

```sh
python simple/dev.py check
```

독립 구성요소 검사:

```sh
python simple/feature-mapper/build.py --test
```

실제 수행한 검사와 미실행 범위는 [Simple 1.4.0 검증 기록](../docs/VERIFICATION_SIMPLE_1.4.0.json)을 확인합니다. DOM·Chrome API 어댑터 검사는 native MV3/DNR 설치·실제 Burp GUI 검증과 구분합니다. 기존 GPL과 제3자 고지는 유지합니다.
