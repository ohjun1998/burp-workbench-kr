# 로컬 재현 하네스

실제 검증에 사용한 Java·브라우저·PGlite 실행 코드를 보존합니다. `run_local.py`와 `start_bank.py`는 검증본의 절대 경로를 환경 변수로 바꾼 이식용 템플릿입니다. 이식본은 구문 검사만 했으며 다른 PC에서 실행 검증하지 않았습니다. Linux Python/Java/Node 환경을 전제로 합니다.

## 준비

1. 원본 Vuln Bank를 `WB_LAB_ROOT/vuln-bank`에 체크아웃합니다. 검증 커밋은 `4bb7cd5f46921959f034455e5615782481966177`입니다. 의존성을 `WB_LAB_ROOT/bank-venv`에 설치합니다. Python3.12/Flask2.0.1 조합의 호환 의존성은 대상 프로젝트에 맞춰 고정해야 합니다.
2. 이 폴더에서 `npm ci --ignore-scripts`로 PGlite를 준비합니다. Playwright는 별도 설치하거나 `CODEX_PRIMARY_RUNTIME_NODE_MODULES`에 제공된 런타임 경로를 지정합니다.
3. `WB_JAVA21`, `WB_BURP_JAR`, `WB_CHROMIUM`을 본인이 설치한 실행 파일의 절대 경로로 설정합니다. `WB_LAB_ROOT`, 선택적 `WB_RESULTS`도 지정합니다. Burp 라이선스를 직접 확인·수락한 뒤에만 `WB_ACCEPT_BURP_LICENSE=yes`를 지정합니다.
4. `classpath.txt`에 Workbench0.46.1 JAR, FeatureMapper0.3.1 JAR, Montoya API JAR, legacy API JAR 순서로 콜론 구분 절대 경로를 한 줄에 씁니다. `javac --release 17 -encoding UTF-8 -cp "$(cat classpath.txt)" -d classes NativeBankLab.java`로 하네스를 컴파일합니다.
5. `python run_local.py`를 실행합니다. DB24532·Flask24540·Burp24541은 개인용 loopback에서만 사용합니다. 다른 프로세스가 사용하는 포트와 겹치지 않는지 확인합니다. 전체 프로세스를 같은 실행 환경에서 시작해야 합니다.

## 결과와 제한

`extension-results.json`, `browser-results.json`, `business-rule.json`, `sql-panel-rows.json`과 PNG가 핵심 결과입니다. `private-*`, 전체 Burp 프로필·DB·트래픽에는 실습 인증값이 들어갈 수 있으므로 업로드하지 마세요.

브라우저 보조 확장은 기존 환경 제약 때문에 NOT_RUN으로 남깁니다. 이는 실제 연동 재시도를 수행했다는 뜻이 아닙니다. UI PNG는 실제 실행 데이터가 있는 확장 패널의 headless Swing 렌더링입니다. 수동 데스크톱 클릭이나 Windows DPI/IME 검증은 아닙니다.

PGlite는 PostgreSQL18.6 WASM 호환 계층이고 공식 Compose/PostgreSQL13과 다릅니다. 앱은 순차 실행하고 연결 한도50·idle timeout0을 사용합니다. 동시성·성능·트랜잭션 격리 검증이 아닙니다. 대상 코드와 진단 제품의 동작을 가짜 응답으로 대체하지 않습니다.
