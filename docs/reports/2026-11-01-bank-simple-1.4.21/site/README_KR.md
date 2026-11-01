# Workbench Simple 1.4.21용 로컬 은행 실습 사이트

실제 **VulnBank** 원본 `Commando-X/vuln-bank`의 커밋 `4bb7cd5f46921959f034455e5615782481966177`을 사용합니다. 정상 로그인·잔액·계좌 이체·거래 내역·관리자·GraphQL·OpenAPI와 의도적인 취약점을 가진 웹 앱입니다. 은행 원본 파일은 수정하지 않았습니다. 원본 MIT 라이선스와 파일별 SHA-256은 `upstream/LICENSE.md`, `upstream-manifest.json`에 포함합니다.

프로젝트 저장소에는 launcher와 고정 의존성만 있으며 원본은 `prepare` 시 검증한 Git 커밋에서 받습니다. 배포한 실습 사이트 ZIP에는 같은 원본을 `upstream/`으로 함께 넣어 Git 다운로드 없이 준비할 수 있습니다. Python 3.12·Node 24·npm 및 인터넷 패키지 다운로드가 필요합니다. 이번 실행은 Linux이며 다른 OS에서의 전체 실행은 확인하지 않았습니다.

```sh
python bank_site.py prepare --root ./local-bank-runtime
python bank_site.py start --root ./local-bank-runtime
python bank_site.py status --root ./local-bank-runtime
```

기본 은행 주소는 `http://127.0.0.1:24904`, DB는 `127.0.0.1:24914`입니다. 이미 사용하는 포트이면 앱·DB 모두 기동하지 않고 중단합니다. 필요하면 `start --port 25004 --db-port 25014`로 새 전용 포트를 선택할 수 있습니다. Workbench의 SCOPE에 **현재 실행 중인 로컬 은행 주소만** 등록합니다. 사용 중인 Burp·프록시·브라우저 설정은 변경하지 않습니다.

실습 전용 계정은 `admin / admin123`, `wb_alice / LocalWorkbench2026!`, `wb_bob / LocalWorkbench2026!`입니다. 매 기동의 새 메모리 DB에서 생성합니다. 계좌 번호는 정상 등록 시 임의 생성되며 `runtime/private-state.json`의 각 계정의 `name/token/account/uid`를 참조합니다. 이 파일과 DB·venv·node_modules·실행 로그·JWT는 배포 ZIP에 포함하지 않습니다.

```sh
python bank_site.py stop --root ./local-bank-runtime
```

`stop`은 이 스크립트가 기록한 프로세스에만 종료 신호를 보내고 실제 종료를 기다립니다. 다른 프로세스가 PID를 재사용한 경우 보존합니다. 사이트를 다시 기동하면 새로운 실습 DB가 생성되므로 잔액·프로필·점검 결과의 상태는 초기화됩니다.

## 확인할 정상 대조와 취약 사례

| 항목 | 경로와 의미 |
|---|---|
| 정상 접근 차단 | `/sup3r_s3cr3t_admin`: 관리자 200, 일반 계정 403, 비로그인 401 |
| 정상 GraphQL 차단 | Alice 계좌의 `transactionSummary`: Alice/관리자 허용, Bob 명시적 오류, 비로그인 401 |
| 잔액 BOLA | `/check_balance/<Alice 계좌>`의 비로그인·Bob 조회 대조 |
| 사용자 IDOR | `/api/v3/user/<Alice UID>`를 Bob으로 요청 |
| SQL 입력 검사 | `/login`, `/check_balance/<입력>`의 정상·오류·조건 대조 |
| 업무 규칙 | `/transfer`의 음수 금액 전후 잔액 대조. 가짜 계좌에만 사용 |
| 분석 자료 | `/static/dashboard.js`, `/static/merchant.js`, `/static/openapi.json`, `/api/docs` |
| 의도적 실습 노출 | `/debug/users`, `/.env`, `/.env.bak`의 공개된 demo 값 |

DB는 공식 Compose의 PostgreSQL13 대신 **PGlite0.5.10 / PostgreSQL18.6 WASM 호환 계층**을 loopback TCP로 사용합니다. 앱은 `threaded=False`로 순차 처리합니다. 이번 결과는 공식 PostgreSQL13·동시성·격리·성능 검증이 아닙니다. 앱 wrapper는 loopback 밖의 연결을 막고 AI 키를 `demo-key`로 고정하므로 AI 응답은 원본의 mock 모드이며 실제 DeepSeek 취약점 결과로 해석하지 않습니다. 노출된 API 키·비밀번호·계좌도 원본이 제공하거나 이번에 만든 가짜 실습 값입니다.

## 재현 ZIP 생성

```sh
python bank_site.py package --root ./local-bank-runtime --output ./workbench-simple-1.4.21-bank-site.zip
```

원본 58개 파일과 launcher·고정 lockfile·라이선스만 포함합니다. ZIP 생성 전에 모든 원본 파일이 보존된 SHA-256과 일치해야 합니다. 실제 이번 실행 결과와 도구별 진단 보고서는 저장소의 `docs/reports/2026-11-01-bank-simple-1.4.21/`에서 별도로 확인합니다.
