# 인증 조합·URL 권한 검증 보고서

작성일: 2026-10-18 / 커뮤니티 3.9.0 / 확장 0.43.0

실제 Burp Suite Community Edition 2026 3.3에 배포 JAR를 로드했습니다. 독립 HTTP 420개, 확장 비교 42건(기준 제외 계정 대조 126건), 신원·만료 보호 22건, Chromium 104개가 통과했습니다. 기존 커뮤니티 HTTP 384개·브라우저 66개도 통과했습니다. 반복 검사는 합산하지 않습니다.

검사한 정상 구현은 기대 정책과 일치했습니다. 의도된 관리자 인가 누락·객체 소유권 누락 두 유형을 7개 방식에서 탐지했습니다. 이는 14개 신규 결함이 아니라 대조용 14건입니다. 정상 경로의 타인 쓰기는 거부되고 데이터가 유지되었으며 취약 대조의 실제 변경은 재조회로 확인했습니다.

| 인증 방식 | 정상 대조 | 취약 대조 | 기준 제외 계정 대조 |
|---|---:|---:|---:|
| 세션 쿠키 | 4 | 2 | 18 |
| 불투명 토큰 | 4 | 2 | 18 |
| JWT | 4 | 2 | 18 |
| 세션 + 토큰 | 4 | 2 | 18 |
| 세션 + JWT | 4 | 2 | 18 |
| 토큰 + JWT | 4 | 2 | 18 |
| 세션 + 토큰 + JWT | 4 | 2 | 18 |

일곱 페이지는 `/auth-lab/session`, `/auth-lab/token`, `/auth-lab/jwt`, `/auth-lab/session-token`, `/auth-lab/session-jwt`, `/auth-lab/token-jwt`, `/auth-lab/all`입니다. 기존 커뮤니티 사이드 메뉴에서 들어갑니다.

실제 Burp 검사는 임시 프로필의 Montoya HTTP와 운영 비교 컨트롤러를 사용한 자동 검사입니다. Burp 창 클릭·물리 키·Windows DPI/IME 검사가 아닙니다. 상세 검사는 H2 Oracle 호환 모드이며 Docker·Oracle 결과는 별도 검증 기록을 참조합니다. 인증값·서명 키·로그인 본문은 배포 근거에서 제외했습니다.

[PDF](Auth_Comparison_Report_20261018.pdf) · [상세 검증](https://github.com/ohjun1998/burp-workbench-kr/blob/main/docs/VERIFICATION_LAB_3.9.0.json) · [근거 JSON](https://github.com/ohjun1998/burp-workbench-kr/blob/main/docs/verification/auth-lab-3.9.0/summary.json)
