# 커뮤니티 3.9.0 - 인증 방식별 로그인과 권한 검증

커뮤니티 사이드 메뉴의 **인증 방식별 로그인·권한 검증**에서 시작합니다. 기존 커뮤니티 화면·로그인과 Workbench 0.43.0 배포 파일을 유지합니다.

| 페이지 | 인증 구성 |
|---|---|
| `/auth-lab/session` | HttpOnly `AUTHLABSID` 쿠키 |
| `/auth-lab/token` | `X-Auth-Token` 불투명 토큰 |
| `/auth-lab/jwt` | Authorization Bearer JWT |
| `/auth-lab/session-token` | 세션 + 토큰 |
| `/auth-lab/session-jwt` | 세션 + JWT |
| `/auth-lab/token-jwt` | 토큰 + JWT |
| `/auth-lab/all` | 세션 + 토큰 + JWT |

`alice`, `bob`, `admin`과 공통 비밀번호 `LabOnly!2026`을 사용합니다. 일곱 방식은 서로 다른 인증 정책을 비교하기 위한 실습이며 독립된 다중 인증 요소를 구현한 MFA 서비스가 아닙니다. 기본 유효 시간은 15분이며 만료 확인용 2초 인증도 선택할 수 있습니다.

## URL별 권한

`/api/auth-lab/{profile}/fixed/` 뒤에 다음 경로를 붙입니다.

| 경로 | 관리자 | Alice | Bob | 비로그인 |
|---|---:|---:|---:|---:|
| `member` | 200 | 200 | 200 | 401 |
| `admin/users` | 200 | 403 | 403 | 401 |
| `requests/901` | 200 | 200 | 404 | 401 |
| `requests/902` | 200 | 404 | 200 | 401 |

`GET /api/auth-lab/{profile}/me`는 현재 서버 사용자·역할을 반환합니다. 사용자 확인 경로는 `$.user.id`, 역할 경로는 `$.user.role`입니다. `POST .../requests/{id}/note`는 메모와 revision을 변경하며 정상 경로는 소유자·관리자만 허용합니다. 데이터는 기존 게시글과 분리된 실습 테이블에 둡니다.

각 인증값은 같은 로그인 묶음과 같은 방식에 연결합니다. 누락·다른 로그인 혼합·중복 헤더·잘못된 추가 인증값은 거부하며 다른 값으로 조용히 대체하지 않습니다. JWT는 HS256·typ·서명·issuer·audience·시간·subject·profile·폐기 여부를 검사합니다. 역할과 정지는 DB에서 현재 상태를 읽습니다. 인증 쿠키가 포함된 쓰기는 CSRF도 확인합니다. Nginx는 외부 Host의 포트를 보존하여 브라우저 Origin 검증과 맞춥니다.

기존 JSESSIONID 로그인과는 별개입니다. 인증 실습 페이지 이동 시 헤더 입력 상태는 새로 시작하며, 다시 로그인해 값을 준비합니다. 서명 키와 세션은 프로세스 메모리에 보관하므로 재시작하면 이전 인증을 사용할 수 없습니다. 서버 키를 명시적으로 지정해야 하는 테스트에서는 `AUTH_LAB_SIGNING_KEY_B64`에 32바이트 이상의 난수 키를 Base64로 전달합니다. 키를 보고서·로그·소스에 넣지 않습니다.

## 비교용 취약 URL

`fixed`를 `vuln`으로 바꾼 관리자 목록·비공개 요청 경로는 로그인만 검사하고 기능 권한·소유권을 생략하는 **의도된 대조 사례**입니다. UI에서 취약 실습을 선택하면 표시합니다. 정상 URL에서는 같은 요청을 차단합니다. 쓰기 대조는 실제 가상 메모를 바꿀 수 있습니다.

## 실제 검증과 보고서

실제 Burp Community 2026.3.3에 기존 0.43.0 JAR를 로드했습니다. 운영 계정 권한 비교 컨트롤러로 42개 비교 기록과 기준 제외 계정 대조 126개를 확인했습니다. 정상 대조 28개는 기대 정책과 일치했고, 두 결함 유형을 일곱 방식에서 실행한 취약 대조 14개는 우회 의심이었습니다. 만료 기준은 재전송을 중단하고 세션 확인 필요로 표시했습니다.

독립 HTTP 420개, 웹 브라우저 104개, 기존 커뮤니티 HTTP 384개·브라우저 66개가 통과했습니다. Docker 배포에 사용하는 인증 경계 검사 197개도 H2에서 확인했으며 실제 Oracle·Nginx 실행 결과와 구분합니다. 같은 검사를 반복한 수치는 합산하지 않습니다.

- [PDF 보고서](reports/2026-10-18/Auth_Comparison_Report_20261018.pdf)
- [가린 요청·응답 근거 ZIP](reports/2026-10-18/Auth_Comparison_Evidence_20261018.zip)
- [정확한 실행 환경·원격 결과](VERIFICATION_LAB_3.9.0.json)
- [실제 웹 페이지](verification/auth-lab-3.9.0/auth-lab-all-desktop.png)
- [운영 확장 비교 기록](verification/auth-lab-3.9.0/extension-history.png)

Burp 테스트는 임시 프로필과 실제 Montoya HTTP·운영 비교 코드를 사용한 자동 검사입니다. Burp 창 클릭·물리 키·Windows DPI/IME와 구분합니다. 상세 검사의 DB는 H2 Oracle 호환 모드입니다. Burp 헤드리스 내부 UI 경고는 검증 기록에 별도로 남깁니다.

```sh
python dev.py build
python dev.py http-test
python tools/check_auth_lab.py --mode adapter
python tools/check_community_ui.py --auth-lab
python tools/check_community_ui.py --auth-smoke
python dev.py release
python tools/package_downloads.py --verify
```

native 모드는 별도 Burp JAR·Java 21 경로와 `--accept-community-license`를 지정합니다. 브라우저는 기존 Community UI 검사와 같은 Playwright 설정을 사용합니다. 소스·원본 Burp 라이선스 파일을 실행 ZIP에 재배포하지 않습니다.

## 설치

[3.9.0 Docker 실행 ZIP](../downloads/docker/3.9.0/community-security-lab-3.9.0-docker.zip)을 새 폴더에 풀고 Windows는 `./start.ps1`, Linux/macOS는 `sh start.sh`로 실행합니다. 접속 주소는 `http://127.0.0.1:8088/auth-lab/session`입니다. 기존 스택이 같은 포트를 쓰면 해당 폴더에서 종료한 뒤 새 스택을 시작합니다. DB 볼륨을 삭제할 필요는 없습니다.
