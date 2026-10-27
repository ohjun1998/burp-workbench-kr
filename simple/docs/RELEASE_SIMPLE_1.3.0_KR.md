# Workbench Simple 1.3.0 변경 안내

지정한 SCOPE의 HTML·JavaScript·폼 경로를 외부 **Katana 1.8.0**으로 수집하고, 환경·경로·API·민감정보·계정별 차이·변경·미확인 결과까지 같은 실행에서 확인하도록 확장했습니다. **기존 8개 도구와 SCOPE 통합 점검을 유지합니다.** 기존 Next와 Simple 1.0.0·1.1.0·1.2.0 파일은 보존합니다.

## 사용 흐름

1. **Katana 준비:** 공식 배포와 고정 SHA-256을 확인하는 [설치 도구](../tools/install_katana.py)로 Simple 밖의 사용자 폴더에 Katana를 설치합니다. 실행 조건에서 파일 선택 또는 경로 입력 후 **설치 확인**을 누릅니다.
2. **대상과 조건 선택:** SCOPE URL·허용 경로·HTTP 계정·전체 요청 예산을 확인하고 **Katana 탐색**을 선택합니다. Katana 페이지 한도·수집 시간도 정한 뒤 **통합 점검 시작**을 누릅니다.
3. **수집과 분석:** **Katana 수집**에서 상태·출처·후보·차단 사유를 읽습니다. 실제 응답은 환경·DB·경로·API·키/민감정보·계정별 차이·이전 실행 비교 탭으로 연결됩니다. 완료·제외·실패·부분·미확인 항목은 커버리지에서 함께 확인합니다.
4. **원본과 후속 검토:** 같은 불변 패킷의 원본을 열고 기존 Repeater·계정 비교·API 찾기·입력값 검사·증거로 연결합니다. 앞64 KiB 미리보기의 더 보기(...)에서는 Burp 기본 읽기 전용 편집기로 전체 원본을 엽니다.

Katana의 대상 HTTP/HTTPS 요청은 실행용 인증 loopback 프록시와 기존 Workbench 전송을 사용합니다. 선택한 HTTP 계정·현재 SCOPE·리디렉션·조회 정책·공통 요청 예산을 적용하고 대상 Cookie·Authorization을 Katana 명령행이나 설정 파일로 전달하지 않습니다. HTTPS CONNECT에는 실행용 임시 인증서를 사용하며 사용자·시스템 CA를 설치하지 않습니다.

## 수집 범위와 판정

Katana의 표준 HTML/JS 수집, 폼 메타데이터와 기술 단서를 사용합니다. JSONL의 발견 메타데이터는 자동 재조회하지 않으며 실제 Katana의 프록시 요청에 깊이·시간·페이지 한도를 적용합니다. 폼의 action·메서드·입력 이름을 확인하고 값이나 메서드가 미확정이면 원본 필요로 남깁니다. 폼 자동 제출·자동 로그인·쓰기 요청·키 자동 검증·외부 공급자 API 호출·자동 업데이트는 실행하지 않습니다.

SPA 메뉴·동적 라우트는 기존 브라우저 helper에서 선택합니다. 이 버전의 Katana는 표준 HTML/JS 모드를 사용합니다. 브라우저 로그인 세션과 선택한 Workbench HTTP 계정은 별개이며 브라우저 쿠키를 복사하지 않습니다.

환경·DB·숫자 버전은 근거와 확실성을 함께 읽고, HTTP 200·기술 이름·응답 차이만으로 노출이나 취약점을 확정하지 않습니다. API 문서 선언·JS 후보·실제 관측을 대조하고, 시크릿 후보는 마스킹·공개/예제/비밀키 분류·원본 위치로 검토합니다. 발견한 키를 인증에 재사용하지 않습니다.

Katana 종료는 발견한 큐의 처리 상태입니다. 시간·페이지·출력·HTTP 예산·중지·실패로 남은 항목은 미확인으로 표시합니다. 실행 파일이 없거나 실행에 실패해도 일반 HTTP 결과는 유지하고 Katana 실패·부분 사유를 명시합니다. 재개해도 처음 정한 절대 시간과 공통 예산을 유지합니다. 이전 실행은 같은 프로젝트·SCOPE·계정·Katana 조건과 **Katana 수집을 시작한 HTTP 계정**일 때 비교합니다. 불완전 실행으로 이전 발견의 해결을 확정하지 않습니다.

## 설치와 이전 파일 보존

Katana 실행 파일은 JAR·소스 ZIP에 포함되지 않습니다. [고정 의존성 기록](KATANA_DEPENDENCY.json)에 공식 배포·플랫폼·SHA-256과 라이선스를 보존합니다. 설치기는 외부 절대 경로에 `katana` 또는 `katana.exe`와 `katana-install.json`을 저장하며 PATH·HOME 설정을 재지정하거나 CA를 설치하지 않습니다.

기존 Katana 재개 자료가 필요하면 먼저 보관합니다. 공급자 Katana 1.8.0은 버전 확인 때도 기본 사용자 설정 위치의 10일 지난 resume 파일을 정리할 수 있습니다. 이 공급자 동작은 의존성·설치 기록에 명시합니다.

기존 8개 도구의 주요 기능과 Simple의 정리된 메인 화면을 유지합니다. 중복 폴더 탐색·화면 이력·작업 이어가기·도우미 관리 바, 신규 요청 자동 비교 토글, 전체 History 자동 순회와 OAST·외부 도구 보조 화면을 메인 흐름에 다시 추가하지 않았습니다.

## 파일과 검증

- [Simple 1.3.0 JAR](../downloads/1.3.0/burp-workbench-simple-1.3.0.jar) · [독립 빌드 소스 ZIP](../downloads/1.3.0/burp-workbench-simple-1.3.0-source.zip) · [SHA256SUMS](../downloads/1.3.0/SHA256SUMS)
- [브라우저 helper 0.4.0](../downloads/1.3.0/feature-mapper-browser-helper-0.4.0.zip)
- [단계별 HTML 가이드](GUIDE_SIMPLE_KR.html) · [PDF 가이드북](../downloads/guides/1.3.0/Workbench_Simple_1.3.0_Guide_KR.pdf)
- [Katana 요구사항·코드·검사·가이드 연결표](KATANA_SCOPE_1.3.0_SPEC_KR.md) · [실제 검증 기록](VERIFICATION_SIMPLE_1.3.0.json)
- [공식 Katana 실제 프로세스·HTTP/HTTPS 검증](verification/1.3.0/katana-native/katana-verification.json) · [항목별 결과](verification/1.3.0/katana-native/native-results.json)

가이드 그림은 운영 Swing 컴포넌트에 합성 패킷·Katana·브라우저 이벤트를 넣어 렌더링한 화면입니다. 별도의 실제 Katana 프로세스·격리 HTTP/HTTPS 검사는 JS·폼·기술 단서·계정·이력·일시정지/중지·공통 예산·인증값 분리·임시 파일 정리를 확인합니다. 실제 Burp GUI·브라우저 설치 검증과 구분하며 실행한 검사와 제한은 1.3.0 검증 기록을 확인합니다. 이전 버전의 검사 수를 합산하지 않습니다.
