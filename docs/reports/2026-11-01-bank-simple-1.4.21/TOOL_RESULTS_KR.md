# Workbench Simple 1.4.21 · 은행 실습 도구별 실제 값

[그림·검색·상세 결과](RESULTS.html) · [CSV](tool-results.csv) · [원본 값 JSON](report-data.json)

| 도구 | 실제 출력 | 값·예시 |
|---|---|---|
| 요청·응답 목록 | 실제 HTTP 89건 수집 | 수신 89 · 처리 오류 0 / http://127.0.0.1:24904/ |
| 기능 자동 매핑 | 관측 135항목 → 43개 기능 그룹 | Check account balance / Admin panel |
| 계정 권한 비교 | 잔액 조회에서 우회 의심 2개 인증 조건 | http://127.0.0.1:24904/check_balance/1791279111 / wb_bob, anonymous → HTTP 200, 타인 계좌 표식 관측 |
| JS에서 API 찾기 | 60개 소스 · 304개 후보 행 · URL 문자열 217개 | dashboard.js 19 / merchant.js 7 / OpenAPI 42 / /transfer · /request_loan · /api/virtual-cards 등의 경로 후보 |
| 서버·경로 탐색 | Flask / Werkzeug · Swagger / OpenAPI · GraphQL 단서 | /healthz → 200 / JSON / /wb-bank-missing-1421 → 404 / MISSING |
| 응답 보안 분석 | 5개 현재 분석 항목 | HTML 응답 헤더 검토 / 설정 키 노출 후보 |
| 입력값 보안 검사 | 5회 제한 실행 · SQL 조건 차이 후보 1개 | 기준 200 → 참 조건 200 → 거짓 조건 404 / 기준·거짓 조건을 각각 재검증 |
| 재현·증거 정리 | 순차 요청·변수 추출·증거 ZIP 및 음수 이체 재현 | 별도 실제 workflow 5단계 · 금액 -5 / Alice 1000 → 1005 / Bob 1000 → 995 |
| 데이터 변환·비교 | 실제 계좌 응답 변경 구간 3개 · JWT 내용 해석 | Alice·Bob의 실제 잔액/사용자명/계좌 응답 비교 / 실제 JWT: HS256 · is_admin=false · exp 없음 |
| 점검 진행 현황 | 관측·발견 경로 91항목 관리 | 관측과 미관측, 파라미터·재검토·수동 완료 상태 / GET /는 하네스에서 직접 확인 후 완료 표시 |
| 외부 노출·CVE 조사 | 검색식 생성 · 외부 조회 미실행 | site:127.0.0.1 (inurl:swagger OR inurl:api-docs OR inurl:openapi) / 외부 공급자 API / Google / CVE 조회: NOT_RUN |
| 통합 점검 | COMPLETED · 전송 46 / 완료 응답 44 | 대기 0 / 제외 119 / 실패 0 / 미확인 10 / 관측·후보 finding 행 525개 |
| 대시보드 | 같은 실행 0784c671의 현재 결과 표시 | snapshot pages 46 / finding 행 525 / 통합 점검과 동일 runId 확인 |

각 도구의 관측 항목은 중복됩니다. 후보 수를 취약점 수나 독립 HTTP 수로 합산하지 않습니다. 상세 의미와 원본 ID·응답 지문은 각 JSON을 확인하세요.
