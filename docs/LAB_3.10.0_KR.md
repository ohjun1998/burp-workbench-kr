# 커뮤니티 3.10.0 - 50개 기능 계정 권한 검증

커뮤니티 사이드 메뉴의 **50개 기능 권한 검증** 또는 `http://127.0.0.1:8088/auth-50`에서 시작합니다. 게시글·댓글·개인정보·보안·교육·행사·결제·조직·파일·협업·운영·지원 업무를 제공합니다. 기존 7개 인증 페이지와 커뮤니티 기능을 보존합니다. Workbench는 기존 0.43.0 JAR·판정 코드를 사용합니다.

## 실행과 사용

[3.10.0 Docker 실행 ZIP](../downloads/docker/3.10.0/community-security-lab-3.10.0-docker.zip)을 새 폴더에 풀고 Windows는 `./start.ps1`, Linux/macOS는 `sh start.sh`로 실행합니다. Docker와 Compose v2가 필요합니다. 기존 스택이 같은 포트를 사용하면 해당 폴더에서 먼저 종료합니다. 기존 DB 볼륨 삭제는 필요하지 않습니다.

인증 방식·요청 계정·정상/취약 경로를 선택하고 **로그인·계정 준비**를 누릅니다. 계정은 admin·alice·bob, 가상 비밀번호는 `LabOnly!2026`입니다. 비로그인은 인증값 없이 요청합니다. 표에서 기능을 실행하면 실제 URL·응답과 쓰기 전후 조회가 표시됩니다. 왼쪽 업무 그룹과 검색·메서드 필터를 사용할 수 있습니다. 좁은 화면에서는 표 안을 가로로 이동합니다.

| 인증 선택 | 값 |
|---|---|
| 세션 | HttpOnly AUTHLABSID 쿠키 |
| 토큰 | X-Auth-Token 불투명 토큰 |
| JWT | Authorization Bearer JWT |
| 복합 | 세션+토큰 / 세션+JWT / 토큰+JWT / 세션+토큰+JWT |

복합 조합의 값은 같은 로그인 묶음에 속해야 합니다. 독립 인증 요소를 제공하는 MFA 실습은 아닙니다. 이전 인증 경계·키 처리·만료 정책은 [3.9.0 안내](LAB_3.9.0_KR.md)에 있습니다.

## 50개 기능과 정답

GET 23개와 POST/PATCH/PUT/DELETE 27개입니다. 경로는 `/api/auth-lab/{profile}/{fixed|vuln}/features/` 뒤에 업무 경로를 붙입니다. [독립 기대 권한표](../audit/auth50/expected-policy.csv)에 전체 50개 메서드·URL·기준 사용자·허용/차단 계정이 있습니다. [기능별 결과 CSV](verification/auth50-3.10.0/function-results.csv)는 설정 전후의 정탐·오탐·미탐·확인 필요를 제공합니다.

공개 5개와 회원 공통 3개, 관리자 전용, 관리자+객체 소유자, 관리자+조직 구성원, 관리자도 거부하는 개인정보, Alice/Bob 공동 프로젝트를 포함합니다. 관리자라는 이유만으로 모든 기능을 허용하지 않습니다.

정상 `fixed`는 기대 권한을 검사합니다. 취약 `vuln`은 로그인·쓰기 CSRF는 확인하고 업무 권한 검사를 생략하는 의도된 대조입니다. 실제 가상 데이터를 변경하지만 실사용 계정 잠금·역할 변경에는 영향을 주지 않습니다. 쓰기마다 업무 필드·처리 이벤트·revision을 저장하여 반복 요청도 관측합니다. 개인정보 CSV, 파일 다운로드, 202 접수, 204 삭제, 200 차단 JSON/HTML, 302·403·404·410, **403 뒤 실제 저장 변경**도 포함합니다.

## 실제 확장 결과

실제 Burp Community 2026.3.3·Java 21.0.8에서 배포 JAR를 로드하고 Montoya HTTP로 실행했습니다. 인증별 7개 운영 비교 컨트롤러 인스턴스와 기존 요청 지연을 사용했습니다. 정상+취약, 설정 전+후의 **1,400개 비교 기록**·계정 응답 5,600개에서 기준 계정 1,400개를 빼고 **4,200개 계정 대조**를 측정했습니다. 설정별 분모는 2,100개입니다.

| 설정 | 정탐 TP | 오탐 FP | 미탐 FN | 정밀도 | 재현율 | 확인 필요 |
|---|---:|---:|---:|---:|---:|---:|
| 권한표·판정 규칙 미설정 | 0 | 0 | 392 | 산출 불가 | 0.0% | 1,337 |
| 권한표·판정 규칙 설정 | 392 | 21 | 0 | 94.9% | 100.0% | 623 |

미설정도 계정·SCOPE·쓰기 허용은 켜 둔 상태입니다. 설정 후에는 독립 권한표, JSON `status=DENIED`, 본문 `Denied by access policy` 차단 규칙과 쓰기 전후 `$.data.revision` 변경 조회를 사용했습니다. 확인 필요는 정탐으로 계산하지 않았습니다. 미설정의 미탐 392건 중 385건은 노랑, 7건은 잘못된 차단 표시입니다. 설정 후 노랑 623건은 실제 위반이 없는 대조이며, TN은 빨간 오탐이 없다는 의미이지 안전 확정을 뜻하지 않습니다.

| 계정 | 설정 후 TP | FP | FN | 정밀도 | 비고 |
|---|---:|---:|---:|---:|---|
| admin | 56 | 7 | 0 | 88.9% | 개인 자료 접근 거부 포함 |
| Alice | 154 | 7 | 0 | 95.7% | Bob 파일 차단 응답 오탐 |
| Bob | 182 | 7 | 0 | 96.3% | Alice CSV 차단 응답 오탐 |
| 비로그인 | 0 | 0 | 0 | 산출 불가 | 보호 경로 차단, 양성 사례 없음 |

오탐은 18번 개인정보 CSV의 admin/Bob 14건과 42번 파일 다운로드 Alice 7건입니다. 정상 서버가 HTTP 200과 `error=FORBIDDEN`만 반환해 비공개 정보가 없는데 확장은 우회 의심으로 판단했습니다. 이 결과를 없애기 위한 판정 코드/규칙 보정은 하지 않았습니다. 50번 Bob 문의 완료 기능은 Alice 요청 뒤 실제 저장값을 바꾸고 403을 반환합니다. 설정 전에는 차단으로 오판했고 전후 조회 설정 후에는 탐지했습니다.

요청당 종합 결과 기준은 TP 294·FP 14·FN 0·TN 392, 정밀도 95.5%입니다. 계정 대조 기준과 분모가 다릅니다. 같은 서버·공통 엔진의 인증 반복이므로 **운영 사이트의 정탐 확률로 해석하지 않습니다**.

## 근거와 재현

- [9쪽 PDF 보고서](reports/2026-10-18-auth50/Auth50_Report_20261018.pdf)
- [전체 가린 HTTP·확장 결과·CSV·화면 근거 ZIP](reports/2026-10-18-auth50/Auth50_Evidence_20261018.zip)
- [환경·배포·원격 검증 기록](VERIFICATION_LAB_3.10.0.json)
- [실제 화면](verification/auth50-3.10.0/auth50-desktop.png)
- [계정별 요약 CSV](verification/auth50-3.10.0/actor-results.csv)

독립 HTTP 2,800개와 인증값 누락·잘못된 값·계정 혼합 33개가 통과했습니다. 쓰기는 실제 요청 전후의 관리자 전용 새 DB 조회, 읽기는 기능별 마커 노출로 정답을 확인합니다. 실제 확장의 후속 조회와 독립 관리자 관측은 별개입니다. 상세 검사 DB는 H2 Oracle 호환 모드이고 실제 Oracle/Nginx 검사는 원격 CI로 구분합니다. 브라우저 210개·1440/1024/390px 화면을 확인했습니다.

```sh
python dev.py build
python dev.py http-test
python tools/check_auth50.py --mode adapter --out tmp/auth50
python tools/verify_auth50_results.py --run-dir tmp/auth50 --out tmp/auth50-analysis
python tools/check_community_ui.py --auth50 --out tmp/auth50-ui
python dev.py release
python tools/package_downloads.py --verify
```

보고서의 실제 Burp 결과를 재현할 때는 `--mode native --burp-jar <별도 설치 JAR> --burp-java <Java21> --accept-community-license`를 사용합니다. 해당 native 결과를 분석한 다음 `tools/build_auth50_report.py --run-dir <native 결과> --analysis-dir <분석 결과> --ui-dir <브라우저 결과>`로 PDF와 근거 ZIP을 생성합니다. 보고서 생성기는 adapter 결과의 native 오표기를 거부합니다. CI의 adapter 결과를 native 결과로 보고하지 않습니다. 실제 Burp GUI 클릭·Windows DPI/IME, 외부 OAuth/OIDC·SAML·다른 서명 체계는 검사하지 않았습니다. 헤드리스 Burp 내부 ComponentUI 경고 2건을 검증 기록에 남깁니다. 공개 근거에서 인증값·키·로그인 원문과 공급업체 JAR를 제외합니다.
