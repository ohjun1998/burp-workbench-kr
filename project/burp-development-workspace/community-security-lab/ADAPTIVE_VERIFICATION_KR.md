# Payload Runner KR 1.3.0 검증 보고서

JAR SHA-256: `92b6fb189b6aaf2764160f7df6a7fe5b8da112a9f62553aba2a19fa00945bc13`

독립 검사 **127개**, 실제 **Burp Suite Community 2026.3.3-47134 / Java 21 / Linux 검사 159개** 통과. 제품 JAR를 네이티브 확장 로더로 설치했고, 제품 JAR를 검증 Java 클래스패스에 넣지 않았습니다. 검증 보조 확장을 별도로 로드해 제품의 Start/Stop/모드 선택/작업 창/Repeater/CSV/확장 해제 동작을 실행했습니다. 독립 검사는 Java 17로 실행했습니다.

## 요청 수 비교

원래 목록과 기준 응답을 포함한 실제 전송 횟수입니다. 재검증 행을 중복 계산하지 않았습니다.

| 사례 | 전체 목록 | 적응형 | 범위 |
|---|---:|---:|---|
| 취약 XSS 미리보기 | 8 | 6 | 실제 커뮤니티 HTML 반사, 실행 여부 미확인 |
| 수정 XSS 미리보기 | 8 | 4 | 실제 커뮤니티의 전체 태그 엔티티 인코딩 관찰 |
| 취약 SQL 검색 | 10 | 5 | 실제 H2의 고유 오류 반복 재현 |
| 수정 SQL 검색 | 10 | 10 | 실제 H2 바인딩; 후보 없음 |
| SSTI 구문 fixture | 8 | 5 | 합성 다중 엔진 산술식 응답; 실제 엔진 전체 지원 주장 아님 |
| HTML 무반사 fixture | 8 | 3 | 현재 응답의 HTML 반사만 생략 |
| 429 fixture | 2 | 1 | 중단; 안전 판정 아님 |

작은 목록에서는 기준·대조·재검증 때문에 전송이 늘 수 있습니다. 목적은 모든 요청 수를 무조건 줄이는 것이 아니라 근거가 있는 변형에 집중하고 잘못된 결론을 줄이는 것입니다.

## 추가 유형 검증

| 유형 | 개선 | 검증 |
|---|---|---|
| 업로드 | 반환 JSON 상대 URL을 같은 세션/대상에서 GET 재조회. 정확한 UTF-8 내용·MIME·Content-Disposition 비교 | 실제 Spring 커뮤니티/H2 저장: 취약 HTML inline 후보, 수정 구현의 attachment 확인. 다른 계정/브라우저/서버 실행은 미검사 |
| 다운로드/LFI | 동일 파일 2회 관찰 + 기준 + 존재하지 않는 파일 대조 | 실제 소유한 디렉터리의 경로 탐색 vuln/fixed 비교. 시스템 민감 파일을 읽는 검증은 하지 않음 |
| 표현식 삽입 | 다른 산술식과 새 마커 재검증 | 실제 Spring SpEL SimpleEvaluationContext의 `#{731*919}`/`#{731*920}`; 수정 문자열 응답 제외. RCE 없음 |
| 오픈 리다이렉트 | 다른 목적지 호스트로 재검증. Location 헤더만 확인 | native Burp + 통제된 HTTP redirect fixture. 자동 외부 이동 없음 |
| CRLF | 실제 응답 헤더의 고유 마커 2회 확인. 본문 반사 제외 | native Burp + 통제된 raw HTTP 헤더 fixture; 현대 Tomcat의 CRLF 방어를 우회했다고 주장하지 않음 |
| XML/XXE | 내부 엔티티와 외부 파일 증거 구분. 파일 엔티티에 없는 파일 대조 | 실제 JDK XML 파서와 소유한 파일, DTD 차단 fixed 구현 비교 |
| XPath | 조건 쌍/역순 비교와 엔진 고유 오류 반복 검증 | 실제 JDK XPath 오류 vuln/fixed + 별도 조건 차이 fixture. 오류만으로 인젝션 성공 확정 없음 |
| LDAP/NoSQL | 준비된 조건 쌍/역순 비교, 특정 엔진 오류 재검증 | 통제된 LDAP/Mongo 응답 fixture와 RAW JSON 처리. OpenLDAP/MongoDB 실서버 실행 검증은 아님 |
| SSRF | 선택형 로컬 HTTP 수신기. 고유 URL 2개에 GET 콜백 수신 | 실제 Spring 서버에서 항상 같은 `accepted` 응답을 반환하면서 HTTP 요청 발생. vuln 2개 콜백, fixed 콜백 없음/정상 응답 수집 확인 |
| 명령 삽입 | PR_CMD와 PR_CTRL의 서로 다른 조합 출력 대조 | 실제 제한된 셸 printf vuln/fixed + 합성 fixture. 임의 명령/RCE/Windows 셸 검증 없음 |
| 사용자 정의 | 지정 문자열을 재전송/기준 대조로 재현 | native Burp + 통제된 proof fixture. 사용자 조건 관찰이며 취약점 자동 확정 아님 |

## 오류와 경계 조건

중복, 전체 요청 한도, Stop, 403/429, 동적 기준, 반사된 오류/증거 문자열, 잘린 응답, 재검증 실패, 없는 파일에도 같은 내용을 주는 응답, 고정된 리다이렉트 목적지, 고정 명령 출력, 업로드 외부 URL/프로토콜 상대 URL/내용 불일치를 검사했습니다. 추가 GET도 Scope를 다시 확인합니다.

콜백 수신기는 검사 종료/오류/확장 해제 때 닫힙니다. 포트가 이미 사용 중이면 작업을 중단하고, 종료 후 연속 작업에서 재사용할 수 있도록 수정했습니다. 이 부분은 활성 포트 충돌·수신 후 종료/재시작 독립 검사와 실제 Burp의 vuln→fixed 연속 검사로 확인했습니다.

검증 중 Burp 내부 `flashTab`의 비동기 UI NullPointerException이 시작 단계에 기록됐습니다. 스택에는 제품 코드가 없으며 159개 기능 검증과 실제 UI 동작은 완료됐습니다. 이 보고서는 Burp 내부 UI 로그까지 오류가 전혀 없었다고 주장하지 않습니다.

## 사용 범위와 한계

유형과 입력 위치 한 개를 직접 선택하는 도구입니다. 자동 크롤링/입력점 수집, 브라우저 실행, stored/DOM XSS, 지연 기반 SQLi, public OAST/DNS/SMTP 연동, 임의 코드 실행 검증은 포함하지 않습니다. 로컬 HTTP 콜백은 서버와 확장이 같은 PC/네트워크 네임스페이스에 있을 때만 작동합니다. 원격 서버와 Docker의 127.0.0.1은 호스트 PC와 다릅니다. 콜백 수신은 의도된 URL 처리/중계/보안 장비에서도 발생할 수 있어 접근 정책과 출처를 추가 확인해야 합니다.

새 실습 경로는 `/adaptive-lab`에 있습니다. 커뮤니티 3.2 ZIP의 WAR에는 검증한 AdaptiveLabController 바이트코드를 포함했습니다. 이번 새 경로 검증은 Java 로컬 Spring/Tomcat/H2 환경에서 수행했고, Docker/Oracle/Nginx 전체 스택과 Windows/Professional은 이번 변경에서 재검증하지 않았습니다.

원시 성공 로그와 요청 수 CSV, 실제 Burp 화면은 소스 ZIP의 `validation/actual-burp/adaptive-verified-final`에 있습니다.
