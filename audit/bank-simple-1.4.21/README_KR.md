# Simple 1.4.21 · 실제 은행 실행 하네스

은행은 [site/README_KR.md](site/README_KR.md)와 `site/bank_site.py`로 준비합니다. 원본 VulnBank·원본 패키지를 수정하지 않습니다. 결과 보고서는 [도구별 실제 값](../../docs/reports/2026-11-01-bank-simple-1.4.21/README_KR.md)에 있습니다.

하네스는 실제 loopback HTTP body와 고정된 배포 JAR를 연결하는 Montoya 호환 호스트입니다. 실제 Burp 본체 자동화나 합성 응답 생성기가 아닙니다. JDK17, Linux Xorg dummy·xfwm4·D-Bus 및 기존 `simple/burp-workbench/deps` API JAR가 필요합니다. 브라우저 캡처는 Chromium·Playwright를 사용합니다.

```sh
python3 audit/bank-simple-1.4.21/site/bank_site.py prepare --root /workspace/tools/burp-workbench/bank-simple-1.4.21
python3 audit/bank-simple-1.4.21/site/bank_site.py start --root /workspace/tools/burp-workbench/bank-simple-1.4.21
bash audit/bank-simple-1.4.21/run_bank_native.sh /workspace/tools/burp-workbench/bank-simple-1.4.21/runtime/private-state.json /workspace/tools/burp-workbench/bank-simple-1.4.21/my-new-core-run 94
```

출력 폴더는 매번 새로 지정하고 Git 체크아웃 밖에 둡니다. `private-results`는 JWT·인증 원문을 포함합니다. `public-results`도 공개 전 정확한 인증값·일반 JWT 패턴·ZIP 내부 검사를 해야 합니다. 결과 사진은 전체 인증값 노출을 검사해 안전한 화면만 캡처합니다. 이번 최종 근거는 core003이며 helper의 JS 열 메타데이터 사후 수정과 실제 `/login` 선택은 별도 정정 노트에 구분했습니다.

`BANK_JAVA`, `BANK_JAVAC`으로 검증한 JDK17 경로를 지정할 수 있습니다. runner는 원본 adapter 소스4개를 자체 `.adapter-classes`에 strict17로 컴파일하고 원본 SHA를 기록합니다. `simple/.../build/tests`나 Simple 전체 재빌드는 필요하지 않습니다. `BANK_ADAPTER_SOURCES`로 동일4소스의 별도 디렉터리를 지정할 수도 있습니다.

음수 이체 보조 실행은 **실습 Alice·Bob의 금액만** 바꾸며5요청으로 제한합니다. 실행 후 Alice가5늘고 Bob이5줄어든 상태를 유지합니다. 기본 금액 복원이나 기존 실행 덮어쓰기를 암묵적으로 하지 않습니다.

```sh
BANK_MAIN=kr.workbench.BankBusinessAudit bash audit/bank-simple-1.4.21/run_bank_native.sh /workspace/tools/burp-workbench/bank-simple-1.4.21/runtime/private-state.json /workspace/tools/burp-workbench/bank-simple-1.4.21/my-new-business-run 94
```

`validate_bank.py --help`는 독립 대조의 명령을 보여 줍니다. 이 결과는 제품 자동 탐지와 분리합니다. 공개 결과 폴더를 준비한 뒤 `python3 audit/bank-simple-1.4.21/build_report.py --report docs/reports/2026-11-01-bank-simple-1.4.21`로 저장된 값의 HTML·CSV·표를 생성합니다. 이 보고서 생성기는 HTTP 요청을 보내지 않습니다.

PGlite DB·앱 두 프로세스는 `site/bank_site.py stop --root ...`로 소유 프로세스만 종료합니다. 새 기동은 새 메모리 은행·새 계좌 번호를 만듭니다. 서비스와 DB·실제 토큰은 다운로드나 Git에 포함하지 않습니다.
