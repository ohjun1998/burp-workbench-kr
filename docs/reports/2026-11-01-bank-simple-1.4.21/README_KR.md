# 로컬 은행 사이트 · Workbench Simple 1.4.21 실제 결과

실제 VulnBank를 이 클라우드 환경의 **127.0.0.1:24904**에 만들고, 보존한 **Simple 1.4.21 JAR**의 도구들을 실행했습니다. 은행 원본의 로그인·계좌·이체·거래·관리자·OpenAPI 화면과 실제 HTTP 응답을 사용했습니다. 생성한 예제 응답이나 분석 결과를 제품 모델에 넣지 않았습니다. 사이트의 통화 표시는 달러($)이며 모든 계정·잔액은 실습용입니다.

[도구별 실제 값 표](TOOL_RESULTS_KR.md) · [그림·검색·상세 HTML](RESULTS.html) · [CSV](tool-results.csv) · [값 JSON](report-data.json) · [은행 사이트 ZIP](downloads/workbench-simple-1.4.21-bank-site.zip) · [전체 결과 ZIP](downloads/workbench-simple-1.4.21-bank-results.zip)

HTML은 결과 ZIP을 풀고 `RESULTS.html`을 브라우저로 열면 도구 검색, 주요 8개 도구 필터, 라이트·다크 실제 화면 전환을 사용할 수 있습니다. GitHub의 HTML 파일 보기는 소스 표시이므로 로컬 파일로 여는 방식을 사용합니다. 관리형 Chromium은 file URL을 차단하므로 이번 HTML 동작 검증은 같은 파일을 임시 loopback 정적 HTTP로 제공해 수행했습니다. 도구13개·주요8개 필터·검색·13개 화면의 두 테마·외부 요청0을 확인했습니다. [HTML 검증](report-ui-verification.json).

## 실행 환경과 재현

원본은 [Commando-X/vuln-bank](https://github.com/Commando-X/vuln-bank)의 커밋 `4bb7cd5f46921959f034455e5615782481966177`입니다. **원본 58개 파일의 SHA와 MIT 라이선스를 보존**했습니다. 공식 Compose의 PostgreSQL13 대신 **PGlite PostgreSQL18.6 WASM 호환 DB**를 사용하고 Flask는 순차 HTTP로 실행했습니다. 원본 앱 파일을 변경하지 않았습니다.

사이트 ZIP을 풀고 `bank-site` 폴더에서 실행합니다. Python3.12·Node24·npm과 최초 패키지 다운로드가 필요합니다. 이번 재현 검증은 Linux 기준입니다.

```sh
python bank_site.py prepare --root ./local-bank-runtime
python bank_site.py start --root ./local-bank-runtime
python bank_site.py status --root ./local-bank-runtime
```

PC에서 같은 명령을 실행하면 PC의 로컬 은행이 생성됩니다. 클라우드의 loopback 주소가 사용자 PC의 은행 주소와 자동으로 연결되는 것은 아닙니다. 은행은 `127.0.0.1:24904`, DB는 `127.0.0.1:24914`에만 열리며 기존 포트가 사용 중이면 중단합니다. Workbench SCOPE에는 이 로컬 은행만 추가합니다. 기존 Burp·프록시 설정을 바꾸지 않습니다.

실습 계정은 `admin / admin123`, `wb_alice / LocalWorkbench2026!`, `wb_bob / LocalWorkbench2026!`입니다. 계좌 번호는 기동할 때 생성되므로 이번 캡처의 번호와 다를 수 있습니다. 기동마다 새 메모리 DB가 생성됩니다. 종료는 `python bank_site.py stop --root ./local-bank-runtime`이며 소유한 두 서비스만 종료합니다.

별도 ZIP 추출본에서 **prepare → start → 로그인 3계정·health·JS·OpenAPI·잔액 → status → stop**을 실제 검증했습니다. [사이트 검증](site/site-verification.json) · [사이트 실행 안내](site/README_KR.md). 테스트에 쓴 주 은행은 결과를 확인할 수 있게 계속 실행 중입니다.

## 확인된 취약 사례와 정상 대조

| 항목 | 실제 대조 | 판정의 출처 |
|---|---|---|
| SQL 로그인 우회 | 잘못된 암호 401 → SQL 입력을 넣은 로그인 200·관리자 토큰 반환 | 독립 실제 HTTP 재현. 입력값 검사 도구의 조건 차이 후보와 별도 구분 |
| 타인 계좌 잔액 노출 | 인증·쿠키가 없는 요청으로 Bob의 계좌·사용자명·잔액 200 반환 | 독립 HTTP 재현. 제품 계정 비교에서도 Alice 계좌의 Bob·비로그인 조건을 우회 의심으로 표시 |
| JWT 서명 검증 우회 | 정상 Alice 토큰은 관리자 화면 403 → `is_admin` 변조와 잘못된 서명은 실제 관리자 HTML 200 | 독립 HTTP 재현. 변환 도구의 JWT 디코딩만으로 확정한 결과가 아님 |
| 음수 이체 | 실제 재현 도구 5단계에서 금액 -5: Alice **1000→1005**, Bob **1000→995** | 제품은 설정한 HTTP 조건 일치 표시. 실제 응답 숫자를 대조해 업무 규칙 위반 확인 |
| 정상 관리자 역할 제어 | admin 200 / Alice·Bob 403 / 비로그인 401 | 제품 비교와 독립 대조에서 정상 차단 확인 |
| 정상 대출 입력 검증 | 음수 대출 -1 → HTTP 400, `Loan amount must be greater than zero` | 독립 대조. 대출은 생성되지 않음 |

[독립 검증 JSON · 10요청](truth/groundtruth.json) · [음수 이체 JSON · 별도 5요청](results/business/business-negative-transfer.json) · [Alice 실제 결과](images/business/alice-after.png) · [Bob 실제 결과](images/business/bob-after.png)

은행 브라우저 캡처는 진단 전 잔액 1000·거래 0개 상태입니다. 이후 음수 이체를 수행했고, Workbench 최종 캡처는 Alice1005/Bob995인 상태입니다. 서로 다른 단계의 숫자를 같은 시점으로 합치지 않습니다. 독립 검증 3종과 별도 업무 규칙 재현 1종이며 제품의 자동 정탐률·전체 취약점 수를 뜻하지 않습니다.

## 도구별 출력과 해석

[도구별 실제 값 표](TOOL_RESULTS_KR.md)는 주요 8개 도구와 보조 도구·통합 점검·대시보드 총 13개를 다룹니다. 모든 값은 [최종 core003 결과](results/results.json)와 [마지막 관측 시점](results/measurement-stages.json)을 기준으로 읽었습니다.

- 실제 네트워크 **89회**를 수집 **89건**, 수집 처리 오류 **0**으로 확인했습니다. **13개 기능 시나리오**가 완료됐고 실제 결과의 라이트·다크 **28장**을 저장했습니다. 별도 음수 이체 5요청·2장은 별도 축입니다.
- Mapper **135개 관측 / 43개 그룹**은 일반 수집과 Scope handoff가 겹칩니다. 135회를 추가로 전송했다는 뜻이 아닙니다.
- JS의 **60개 소스 / 304개 후보 행 / URL 문자열 217개**에는 중복·동적·미완성 경로가 있습니다. 호출 가능한 은행 API 217개를 확정한 것이 아닙니다. `dashboard.js`는 AST 실패 후 보조 패턴 분석으로 19개 경로를 찾았습니다.
- 통합 점검은 **46전송 / 44완료 응답 / 대기0 / 제외119 / 실패0 / 미확인10**, `COMPLETED`입니다. **525개 finding 행**은 관측·경로·분석 후보이며 확정 취약점 525개가 아닙니다. 완료는 발견 대기열 처리를 뜻합니다. 대시보드의 snapshot page 수도 전송·완료 응답과 구별합니다.
- 응답 분석은 SQL 오류, 설정 키, 헤더 검토와 본문 검사 범위 제한을 표시합니다. 큰 Swagger JS는 앞 1,048,576자만 검사하므로 후반을 전부 확인했다고 해석하지 않습니다.
- 외부 노출/CVE 도구에서는 검색식만 만들었습니다. 외부 CVE·Google 공급자 조회는 **NOT_RUN**이므로 결과 0행을 안전 판정으로 쓰지 않습니다.

## 이번에 드러난 제품 한계

[제품 관측 기록](product-observations.md)에 두 가지를 재현 조건·소스 근거와 함께 기록했습니다.

1. 기준 계정이 첫 계정이 아닐 때 응답 비교의 기준 캡션이 첫 계정 이름으로 표시됩니다. 본문은 실제 기준 응답을 유지합니다. 이번 캡처는 기존 **계정 간 차이·판정 근거** 탭을 사용합니다.
2. 익명 원본의 **증거 ZIP 생성·가림·CRC는 성공**했지만, 연결할 계정/업무가 없어 **자동 연결은 실패**했습니다. 진단 로그 1건을 보존하며 ‘모든 오류 0’으로 보고하지 않습니다.

JS 캡처 helper의 view/model 열 혼동으로 원본 보조 메타데이터 `selectedEndpoint`가 `?`로 기록됐습니다. 실제 선택은 `/login`이고 이미지와 제품 JSON은 정상입니다. 이 필드는 분석에 사용하지 않았으며 [정정 노트](images/workbench/capture-corrections.json)를 별도로 보존했습니다. 최종 PNG와 원본 캡처 JSON은 바꾸지 않았습니다.

## 검증 범위와 자료 경계

최종 근거는 `core003`, 별도 `business001`, 독립 `truth002`, 은행 브라우저 최종 실행입니다. 초기 어댑터·비동기 준비 문제와 시각적으로 부족했던 캡처는 [중간 이력](prior-attempts.md)에 구분하며 최종 수에 합산하지 않습니다. [전체 검증 JSON](verification.json).

은행 화면은 실제 Chromium, 제품 화면은 실제 Linux Xorg/xfwm4 Swing입니다. 제품은 고정한 JAR와 **Montoya 호환 호스트·실제 HTTP 어댑터**로 실행했습니다. Java HTTP client가 받은 서버 body 바이트를 유지하고 HTTP 헤더·상태행은 정규화해 재구성했으므로 TCP/chunk 프레이밍 그대로의 원시 캡처라는 의미는 아닙니다. 최종 core003은 어댑터 4개를 독립 컴파일해 사전 빌드 테스트 클래스를 사용하지 않았습니다. 별도 business001은 같은 기존 API 어댑터를 사용했습니다. 어느 실행도 은행 응답을 가짜로 바꾸지 않았습니다.

실제 Burp 본체 GUI·Windows·공식 PostgreSQL13·동시성·실제 DeepSeek·브라우저 보조 확장 연동·외부 CVE API는 미검증입니다. Native Katana1.8.0은 읽기 전용 HOME의 기본 form 설정 초기화 제약으로 **미실행**입니다. 명시적 설정 경로를 준 1회 진단도 HTTP/JSONL0이므로 exit0을 성공으로 계산하지 않았습니다. [Katana 근거](limitations/katana/README_KR.md).

원본 ID와 지문은 공개 결과에 보존하고, 전체 JWT·인증 요청/응답·은행 DB·브라우저 프로필은 Git 밖에만 보관했습니다. 공개된 ZIP/JSON과 실제 화면은 인증값 노출 검사를 거칩니다. `simple/` 소스·기존 배포물은 변경하지 않았으며 [불변 패키지 검증](immutable-simple-verify.log)도 다시 통과했습니다. 배포 JAR SHA-256은 `9d3d20af946add3498da79a021be31fafe8d84e046352463c4a559616187301c`입니다.

클라우드 재사용용 `install_script`·`start_skill`은 [설정 초안](environment/)에 저장했습니다. 저장 자체가 실행·발행을 뜻하지 않으며 향후 적용은 환경 설정에서 검토·저장·Publish가 필요합니다. 현재 은행 기동과 ZIP 재현은 별도로 실제 검증했습니다.

작업 브랜치는 `account-a/bank-simple-1.4.21-results`입니다. main 미병합이며 현재 네트워크의 GitHub API 제한으로 PR 생성은 진행하지 못했습니다.
