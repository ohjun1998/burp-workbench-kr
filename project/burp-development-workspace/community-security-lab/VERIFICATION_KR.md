# LOOP 커뮤니티 실습 검증 기록

일시: 2026-10-09 (UTC)

## 결과

| 검증 | 결과 | 실행 방식 |
|---|---:|---|
| 기존 앱 보안 회귀 | 54/54 통과 | Spring MVC·Tomcat·H2 임베디드 서버 |
| LOOP 커뮤니티 API | 49/49 통과 | Spring MVC·Tomcat·H2 임베디드 서버, 실제 HTTP 요청 |
| 커뮤니티 브라우저 | 8/8 통과 | Chromium headless, 로그인·XSS 비교·반응형 폭·오류 확인 |
| 확장 실습 API·프로토콜 fixture | 46/46 통과 | Spring/Tomcat API, TLS/HTTP2와 별도 CSD fixture |
| 전체 Docker/Oracle/Nginx 기동 | 실행 안 함 | 현재 실행 환경에서 Docker 스택을 시작하지 않음 |
| Burp 확장 대상 기능 검증 | 9/9 통과 | Burp Suite Community Edition 2026.9.1, 실제 Montoya HTTP API 요청과 native Param Miner |

## 커뮤니티 결과 범위

49개 HTTP 검사에서 alice/bob/moderator/admin 로그인, 공개·회원·비공개 글 권한, 계정 간 게시글/댓글/첨부/쪽지 접근, 운영자 권한, 검색 입력, CSRF, 업로드 제한, 회원가입과 데이터 초기화를 확인했습니다. 취약 경로에서는 지정된 문제를 재현하고 수정 경로에서는 차단하는 동작을 검증했습니다.

브라우저 검사는 홈 탐색, 로그인·역할 표시, 취약 모드 저장 댓글 XSS 재현, 수정 모드 HTML 이스케이프, 계정 전환, 모바일·데스크톱 가로 넘침, 미처리 JavaScript 오류를 확인했습니다.

## Burp 실제 실행 결과

Burp Suite Community Edition 2026.9.1에서 Workbench를 열고 지원 가능한 원본 엔진 8개를 로드했습니다. 각 엔진의 핵심 API 동작을 확인했고, Pro 전용 JS Miner는 사용할 수 없어 건너뛰었습니다. Burp Montoya HTTP API로 LOOP 사이트에 요청해 게시글·첨부·쪽지 IDOR, 역할 위조, 운영자 범위를 취약/수정 모드에서 비교했습니다. 9개 커뮤니티 검증이 모두 통과했습니다. native Param Miner도 `/api/community/vuln/posts`에서 숨은 `lab_hidden` 쿼리 입력을 찾아냈습니다.

검증 한계: HTTP Request Smuggler는 native probe 생성까지만 확인했고 CSD fixture에 대한 desync 탐지는 이번 커뮤니티 검증에서 수행하지 않았습니다. Scanner·Collaborator와 Pro 전용 기능도 포함하지 않았습니다. 전체 엔진별 핵심 기능은 `burp-community-test-results.json`, 원본 Burp 로그는 `burp-community-engine-validation.txt`에 기록했습니다.

## 파일

- `test-results.json` — 기존 앱 54개 검사 기록
- `community-test-results.json` — 커뮤니티 HTTP 49개 검사 기록
- `extension-test-results.json` — 확장 실습 API·프로토콜 46개 기록
- `community-ui-test-results.json` — 브라우저 UI 8개 검사 기록
- `burp-community-test-results.json` — 실제 Burp 로드 및 커뮤니티 연동 9개 검사 요약
- `burp-community-engine-validation.txt` — 원본 엔진별 실제 로드/동작 로그
