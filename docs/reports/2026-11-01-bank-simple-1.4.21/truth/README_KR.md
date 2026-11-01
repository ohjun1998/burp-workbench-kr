# 독립 HTTP 대조 검증

이 결과는 실행 중인 원본 VulnBank에 별도 HTTP 요청을 보내 확인한 **독립 수동 재현**입니다. Workbench가 자동 탐지한 후보 수나 확정 판정으로 합산하지 않습니다. 최종 실행은 10개 요청이며, [groundtruth.json](groundtruth.json)에 응답 상태·필터된 실제 값·원문 SHA-256과 증거 ID를 보존했습니다.

| 구분 | 실제 결과 | 해석 |
|---|---|---|
| SQL 로그인 우회 | 관리자 이름과 잘못된 암호는 401. `admin' -- ` 입력과 같은 잘못된 암호는 200, 토큰 반환·관리자 상태 true | 로그인 우회를 재현했습니다. 전체 토큰과 암호는 공개하지 않습니다. |
| 타인 계좌 무인증 조회 | Bob 본인 요청과 비로그인 요청 모두 200. 비로그인 응답에 `wb_bob`, 계좌 `3902391125`, 잔액 `1000.0` 반환 | 단순 HTTP200이 아니라 실제 타인 계좌 데이터가 반환됨을 확인했습니다. 잔액은 해당 요청 시점의 값입니다. |
| 정상 역할·인증 제어 | 정상 Alice 토큰은 관리자 화면 403, 관리자 토큰은 200, 비로그인은 401 | 정상 토큰으로는 관리자 역할 제어가 동작합니다. 모든 경로가 무조건 열려 있다는 뜻이 아닙니다. |
| 음수 대출 거절 | Alice의 대출 금액 `-1` 요청은 400, `Loan amount must be greater than zero` | 이 정상 입력 검증을 음수 이체 취약점과 구분합니다. |
| 실제 JWT 메타데이터 | `alg=HS256`, `typ=JWT`, `is_admin=false`, `iat` 있음, `exp` 없음 | 실제 발급 토큰을 디코딩한 관측입니다. 만료 claim 누락만으로 임의 서명 수용을 단정하지 않습니다. |
| JWT 서명 검증 우회 | 정상 Alice는 관리자 화면 403. `is_admin=true`로 바꾸고 의도적으로 틀린 서명을 붙인 토큰은 200 및 실제 관리자 HTML 반환 | 서버의 서명 키를 사용하지 않고 권한 상승을 재현했습니다. JWT 보기 도구의 자동 확정 기능을 뜻하지 않습니다. |

SQL 로그인 우회·무인증 타인 계좌 조회·JWT 서명 검증 우회 **3개**를 독립 재현했습니다. 정상 대조 **2개**와 설정 관측 **1개**는 취약점 개수에 포함하지 않습니다. 타 도구의 음수 이체·등록 시험과 겹쳐 합산하지 않습니다.

재현 스크립트는 [validate_bank.py](../../../../audit/bank-simple-1.4.21/validate_bank.py)입니다.

```sh
python3 audit/bank-simple-1.4.21/validate_bank.py \
  --state /workspace/tools/burp-workbench/bank-simple-1.4.21/runtime/private-state.json \
  --out /tmp/bank-independent-public \
  --private-out /tmp/bank-independent-private
```

기존 결과를 덮어쓰지 않도록 두 출력 폴더는 새 경로여야 합니다. 인증 원문을 담는 private 폴더는 Git 체크아웃 밖에만 허용합니다. 실제 이번 실행의 private 원문은 작업환경 `/workspace/tools/burp-workbench/bank-simple-1.4.21/independent-truth-private-002/`에 있으며 공개 Git에 넣지 않습니다. 공개 JSON은 전체 JWT·Authorization·암호·관리자 HTML을 포함하지 않습니다.

원문 증거는 HTTP 애플리케이션 body와 파싱한 헤더입니다. TCP framing 원시 캡처가 아닙니다. 요청은 지정한 loopback 은행 origin에만 전송하고 외부 origin redirect는 거부합니다. 원본 사이트·제품 소스는 수정하지 않고, 이 검증기는 이체·등록을 보내지 않습니다. 전체 권한 정책·동시성·운영 은행·native Burp GUI를 검증한 결과는 아닙니다.
