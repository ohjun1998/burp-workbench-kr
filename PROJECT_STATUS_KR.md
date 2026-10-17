# 현재 개발 상태

기준: 2026-10-17 (한국 시간) / Workbench 0.39.2 / 실습 사이트 3.8.0 / 별도 Feature Mapper KR 0.3.1.


## 계정별 응답 코드·요청당 종합 결과 하나 — 0.39.2

계정별 칸에는 HTTP 응답 코드·기준 여부만 표시하고 URL 바로 오른쪽의 종합 결과 열에서 실행 한 건당 판정과 색상 하나를 표시합니다. 원본과 동일 인증인 기준 계정은 종합 판정에서 제외합니다. 일부 전송 실패·미확인·중지가 있으면 전체를 차단 확인으로 표시하지 않으며, 진행 중 결과·명시적 허용 정책 일치도 구분합니다. 상세 창에서 종합 근거와 계정별 근거를 확인합니다. 기존 개별 판정·실제 재전송·Ctrl+Space는 유지합니다.

이번 로컬 정식 release **2,657개**(Java 2,157·HTTP 132/102/266), 종합 판정 회귀 **23개**, 레이아웃 회귀 **102개**, 두 테마·세 해상도의 Swing 화면 **54개**·컨트롤 **786개**, 패키징 회귀 4개와 다운로드 해시·소스 지문 검사가 통과했습니다. 실제 Burp GUI·Windows DPI/IME는 미수행입니다. 원격 검증은 해당 PR의 Checks와 검증 기록을 기준으로 구분합니다.

[변경 안내·화면](docs/RELEASE_0.39.2_KR.md) · [검증 기록](docs/VERIFICATION_0.39.2.json) · [JAR·소스 ZIP](downloads/extensions/0.39.2/) · [날짜별 대화](docs/chat-logs/2026-10-17/workbench-auth-summary-0392.md)

## 계정 비교 시안 배치 보정 — 0.39.1

[PR #55](https://github.com/ohjun1998/burp-workbench-kr/pull/55)를 main에 병합했습니다. 상단 주요 버튼 6개, 더보기의 세션 검사·권한 매트릭스, 비교 기록 영역의 기록 비우기와 개수·판정 범례를 적용했습니다. 표는 넓은 화면을 채우고 좁은 화면에서는 최소 열 너비를 유지하며 스크롤합니다. 비교 기록의 메서드 열과 두 열 계정 매핑 폼, 기본·최소 크기의 상세 창도 포함합니다.

기존 정식 release 기록은 2,630개(Java 2,130·HTTP 132/102/266), 새 레이아웃 회귀 98개, 두 테마·세 해상도 Swing 화면 54개·컨트롤 786개입니다. 이번 재개에서는 로컬 전체 테스트를 재실행한 것으로 합산하지 않고, PR 원격 Build·Windows·Linux 내구성 성공 결과를 직접 확인했습니다.

검증 PR head는 `0d86b3afaa338d6cd54938154140dff979bcec38`, main 병합 커밋은 `8092b39d980d5ea757a3c99e239ee1e3098a5d3e`이며 두 커밋의 파일 트리가 동일합니다. main에서 내려받은 JAR·소스 ZIP의 SHA-256과 압축 CRC를 직접 확인했고, 소스 ZIP 211개 파일의 지문도 배포 목록과 일치했습니다. 후속 기록 변경은 상태·검증·대화 문서만 갱신하며 소스·JAR·ZIP을 보존합니다.

main의 [Build and verify](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39036078185)와 [Windows·Linux 내구성 검사](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39036078202)가 모두 성공했습니다. 이 결과는 기능 병합 커밋 `8092b39d980d5ea757a3c99e239ee1e3098a5d3e`에 대한 원격 검사입니다.

실제 네이티브 Burp GUI·Windows DPI/IME는 미수행입니다.

[변경 안내·화면](docs/RELEASE_0.39.1_KR.md) · [검증 기록](docs/VERIFICATION_0.39.1.json) · [JAR·소스 ZIP](downloads/extensions/0.39.1/) · [날짜별 대화](docs/chat-logs/2026-10-17/workbench-auth-layout-0391-completion.md)

## 계정 비교 화면·독립 인증 탐지·Ctrl+Space — 0.39.0

[PR #54](https://github.com/ohjun1998/burp-workbench-kr/pull/54)를 통해 인증 탐지 ON/OFF를 트래픽 OFF/SCOPE와 분리하고 요청 비교 ON/OFF, 사용자 계정 매핑, 인증 후보·비교 기록의 더블클릭 상세 창을 적용했습니다. 메인 화면은 인증 후보와 비교 기록을 위아래로 배치하며 비교 기록·패킷 탭에는 실행 당시 계정명을 표시합니다. 계정 매핑은 수동 정보이며 선택적 신원 검사와 구분합니다.

비교 상세의 Repeater 버튼을 제거하고 Ctrl+Space로 선택 계정의 실제 요청·응답을 별도 Workbench Repeater 창에 준비합니다. 창 열기는 요청을 보내지 않으며 길게 누른 단축키의 자동 전송을 막습니다. 실제 비교의 Scope·쓰기 요청 제한과 기존 응답 분류를 유지합니다.

로컬 정식 release 2,532개(Java 2,032·payload HTTP 132·전체 기능 HTTP 102·모의 진단 HTTP 266), 새 흐름 21개, 별도 Mapper Java 94 및 브라우저 도우미 회귀, 패키징 4개, 두 테마·두 해상도 Swing 화면 20개·컨트롤 320개와 다운로드 해시·소스 지문을 검증했습니다. [원격 Build](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39033578733)와 [Linux 내구성·Windows](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39033578686) 모두 성공했습니다. Windows는 자동 어댑터 검사이며 실제 Burp GUI·Windows DPI/IME는 미수행입니다.

검증 소스 커밋은 `9fe0dd42d63e0fcc856c9ed3f829743b7aa90335`입니다. 병합 기록 커밋은 상태·검증·대화 문서만 갱신하며 검증된 소스·JAR·소스 ZIP을 보존합니다.

[사용법·화면](docs/RELEASE_0.39.0_KR.md) · [검증 기록](docs/VERIFICATION_0.39.0.json) · [JAR·소스 ZIP](downloads/extensions/0.39.0/) · [대화](docs/chat-logs/2026-10-17/workbench-auth-workspace-0390.md)

## 계정 비교 응답 분류·차단 규칙 — 0.38.0

[PR #53](https://github.com/ohjun1998/burp-workbench-kr/pull/53)의 계정별 결과 셀·패킷 탭에 빨강(우회 의심)·초록(차단 응답 확인)·노랑(확인 필요)·회색(비교 미성립)을 적용했습니다. 기존 화면·표 열·스크롤을 유지하며 BODY/BODY_REGEX/HEADER/HEADER_REGEX 차단 규칙을 선택적으로 지정할 수 있습니다. 인증 후보만 지정해 비교할 수 있으며 신원 확인 API는 필수가 아닙니다.

404/410·리다이렉트·서버 오류는 정상 차단으로 자동 확정하지 않습니다. 같은 JSON 경로의 여러 차단 값을 OR로 유지하고, 잘못된 기준·기대 권한 충돌·정규식 제한은 판정을 보류합니다.

로컬 정식 release 2,525개(Java 2,025·payload HTTP 132·전체 기능 HTTP 102·모의 진단 HTTP 266), 새 응답 회귀 47개, 별도 Mapper 회귀, 두 테마 운영 Swing 화면 12개·컨트롤 200개와 다운로드 해시·소스 지문 검증을 통과했습니다. 실제 Burp GUI·Windows DPI/IME·외부 Autorize 실행은 미수행입니다. 소스 검증 커밋은 `c7e08c359d627987d2fb863875a0463251cc3000`이며 main 반영 문서 커밋은 소스·JAR·소스 ZIP을 보존합니다.

[사용법·화면](docs/RELEASE_0.38.0_KR.md) · [검증 기록](docs/VERIFICATION_0.38.0.json) · [JAR·소스 ZIP](downloads/extensions/0.38.0/) · [대화](docs/chat-logs/2026-10-17/workbench-auth-response-0380.md)

## 공통 권한 정책·사이트 디자인 — 3.8.0

Spring MVC 공통 인터셉터와 기능별 AccessRule·CommunityAccessPolicy를 적용했습니다. 실제 DB 역할·계정 상태·기능 권한·CSRF를 확인하고, 정상 커뮤니티의 미선언 정책은 기본 거부합니다. 작성자·담당 게시판·업무 상태 검사는 공통 정책과 각 업무에서 유지합니다. 화면은 서버의 허용 작업 목록을 사용합니다. 관리자 권한표·최근 200건 메모리 접근 기록과 게시판 운영자 목록을 추가했습니다. 기존 취약점 실습은 명시된 취약 경로에 유지합니다.

커뮤니티·로그인·관리 화면을 왼쪽 탐색, 카드·표, 검색, 모바일 메뉴 서랍으로 개편했습니다. 실제 H2 HTTP 384개·로그인 스크립트 4개·기존 JS 분석 호환 63개·Chromium UI/반응형 검사 66개와 화면 18장을 확인했습니다. 패키징 회귀 6개도 통과했습니다. 최신 확장 0.37.4 기반 정식 release 2,478개(Java 1,978·HTTP 132+102+266), Mapper Java 94·브라우저 도우미 회귀와 실행 ZIP의 해시·소스 지문 검증도 통과했습니다. [원격 Build](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39010489864)·[Linux/Windows 내구성](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39010489769)·[Docker/Oracle/Nginx](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39010487457)·[실제 Chromium](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39010487209)도 모두 성공했습니다. 검증 코드 head는 `b7747b74b5e6fb838a06d657c2355e9a3c889dc9`이며 최종 기록 커밋은 상태·검증·대화 세 문서만 갱신하고 소스·실행 ZIP·화면을 보존합니다. 정확한 범위는 [검증 기록](docs/VERIFICATION_LAB_3.8.0.json)을 확인하세요. 최신 Workbench 0.37.4·Mapper 0.3.1과 이전 배포를 보존합니다.

[사용법](docs/LAB_3.8.0_KR.md) · [Docker ZIP](downloads/docker/3.8.0/community-security-lab-3.8.0-docker.zip) · [대화](docs/chat-logs/2026-10-17/community-rbac-design-380.md)

## 단독 로그인·권한별 연결 — 사이트 3.7.1

`/login`에서 일반 사용자/관리자 유형을 선택합니다. 서버가 실제 DB 권한과 유형을 확인한 뒤 일반 사용자는 게시글 목록, 관리자는 통합 커뮤니티의 사용자 관리로 연결합니다. 관리자도 공통 기능을 사용하며 moderator는 일반 사용자 유형으로 기존 게시판 관리 범위를 유지합니다. 기존 로그인 별칭, 회원가입·현재 계정 계속하기·로그아웃을 새 페이지로 연결했습니다. 기존 API 클라이언트의 유형 없는 로그인도 지원합니다.

현재 소스 HTTP 307개·JS 로그인 흐름 4개·기존 분석기 호환 63개, 최신 확장 0.37.3 기반 정식 release 2,470개(Java 1,971·HTTP 132+101+266), Mapper Java 94·브라우저 도우미 회귀, 패키징 회귀 6개와 배포 해시·소스 지문 검증을 통과했습니다. [원격 Build](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39007253290)·[Linux/Windows 내구성](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39007253285)·[Docker/Oracle/Nginx](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39007251107)도 모두 성공했습니다. 검증 코드 head는 `b281d819ca876df86ca5dd9ce6909f0e50795359`이며 최종 기록 커밋은 상태·검증·대화 세 문서만 갱신하고 소스와 실행 ZIP을 보존합니다. [정확한 검증 범위](docs/VERIFICATION_LAB_3.7.1.json)를 확인하세요. 브라우저 실제 화면·Burp GUI 검증은 미실행입니다.

[사용법](docs/LAB_3.7.1_KR.md) · [Docker ZIP](downloads/docker/3.7.1/community-security-lab-3.7.1-docker.zip) · [대화](docs/chat-logs/2026-10-17/community-standalone-login-371.md)

## Repeater 미확정 메서드 기본 GET — 0.37.4

미확정 초안은 GET을 기본값으로 준비합니다. JS에서 확인한 메서드를 우선하고, 단일 관찰 메서드가 있으면 사용합니다. 근거 없음·충돌·불완전한 관찰 기록은 `GET · 기본값`으로 표시합니다. 수동 변경과 원본 URL·본문·Content-Type, 미확정 경로·값·SCOPE 검사 및 Send/Ctrl+Space의 명시적 전송을 유지합니다. 제목 표시줄의 기본 □/X는 0.37.3 동작을 유지합니다.

출발 main `1d1efa2`에서 정식 release **2,478개**(Java 1,978·HTTP 132+102+266), Mapper Java 94·Node 7, 기본 GET을 포함한 두 테마 JS 화면 **64개**, 패키징 회귀 **4개**를 통과했습니다. Repeater **95개**는 기본 GET 전송·수동 변경·관찰 근거와 실제 loopback 응답 **6회**를 포함합니다. 이전 동작 기대·새 화면 측정 크기·활동 저장본 검사를 보완한 뒤 전체 검사를 재실행했습니다. 새 main `b15833d`의 사이트 3.7.1을 보존하도록 통합하고 HTTP 102+266개와 패키징을 다시 통과했습니다. 확장 JAR·소스는 같은 바이트를 유지합니다. 로컬은 API 어댑터·헤드리스 Swing이며 Windows 기본 창·내구성 검사는 이번 PR의 Checks에서 별도로 확인합니다. 실제 Burp GUI·물리 키/IME는 미실행입니다.

[선택 기준·사용법·화면](docs/RELEASE_0.37.4_KR.md) · [실행 검증](docs/VERIFICATION_0.37.4.json) · [다운로드](downloads/README.md) · [날짜별 대화](docs/chat-logs/2026-10-17/workbench-default-get-0374.md)

이하 0.37.3 및 사이트 기록은 당시 범위로 보존합니다.

## Repeater 메서드 자동 선택·기본 창 버튼 0.37.3

JS에서 확인한 메서드를 우선하고, 미확정 초안은 활동 추적의 실제 관찰 메타데이터에 같은 Origin·경로의 메서드가 하나만 있을 때 자동 선택합니다. 선택 근거를 표시하며 충돌·근거 없음·불완전한 추적 메타데이터에서는 수동 선택을 유지합니다. 추가 HTTP 요청·원본 인증/값 복사·무조건 GET 선택은 하지 않습니다. 본문의 최대화·이전 크기를 삭제하고 OS 제목 표시줄의 기본 □/X를 쓰는 표준 JFrame으로 변경했습니다. 닫기·확장 종료 시 기존 세션·작업자·단축키 정리를 유지합니다.

기준 main `d1fd77a`에서 정식 release **2,470개**(Java 1,971·HTTP 132+101+266), 별도 Mapper Java 94·Node 7, 두 테마 화면 **278개**, 패키징·배포 보존 회귀 **6개**와 배포 해시·소스 지문 검사를 통과했습니다. Repeater 88개는 자동 선택·충돌·Origin 격리·초기화·자동 선택 후 Ctrl+Space 전송과 실제 loopback 응답 5회를 포함합니다. 합쳐진 사이트 3.7.0의 별도 loopback HTTP 242개도 통과했습니다. Windows CI에 별도 headful 표준 창의 최대화·복원·닫기 검사 6개를 추가했으며 실행 결과는 이번 PR의 Windows 작업을 기준으로 확인합니다. 이 검사는 실제 Burp GUI·IME 물리 키 검사를 뜻하지 않습니다.

[자동 선택 기준·사용법·화면](docs/RELEASE_0.37.3_KR.md) · [로컬 실행 검증](docs/VERIFICATION_0.37.3.json) · [다운로드](downloads/README.md) · [날짜별 대화](docs/chat-logs/2026-10-17/workbench-method-auto-0373.md)

이하 사이트 3.7.0과 확장 0.37.2 기록은 당시 범위로 보존합니다.

## 통합 커뮤니티·취약점 실습 — 사이트 3.7.0

관리자·일반 사용자가 `/`에 함께 로그인하며, 관리자는 공통 기능과 사용자·전체 게시글/댓글·신고·강의·수강 신청·서비스 요청을 같은 메뉴에서 관리합니다. `/admin`, `/user`는 통합 화면 별칭입니다. 계정 정지·해제와 권한 변경은 기존 세션에 반영합니다. 기본 관리자 ID 3은 복구용으로 유지합니다.

공통 화면은 수정 API를 사용합니다. 별도 **취약점 실습** 메뉴는 게시글·신고 IDOR, 관리자 기능 접근, 강의 처리 권한 누락, SQLi, 저장형 XSS, 합성 API 키·토큰 노출, CSRF 검증 누락의 취약/수정 8개 사례와 실제 요청·응답을 제공합니다. 관리자 초기화로 실습 상태를 복원할 수 있습니다.

사이트 소스 loopback HTTP **242개**, 새 JS와 기존 분석기 호환 회귀 **63개**, 패키징 회귀 **6개**가 통과했습니다. 확장 0.37.1 기반 초기 Java·HTTP 검증 **2,442개**(Java 1,943·payload HTTP 132·전체 기능 HTTP 101·버그바운티 HTTP 266), Mapper Java 94·브라우저 도우미 회귀도 통과했습니다. 새 3.7.0 Docker ZIP과 기존 배포의 해시·소스 지문을 확인했습니다. 정확한 단계별 명령과 한계는 이번 검증 기록을 확인하세요. 실제 브라우저 DOM·XSS 실행·Burp GUI는 미실행입니다. 원격 Docker/Oracle/Nginx 기동·기존 Oracle 회귀·포트·TLS/HTTP2 검사는 성공했습니다. 병행 반영된 확장 0.37.2·Mapper 0.3.1과 이전 배포를 보존합니다. 0.37.2 통합 후 HTTP 242개·JS 분석 호환 63개·다운로드 지문 재검증과 [원격 Build and verify](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39004833006), [Linux/Windows 내구성](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39004833033)이 모두 성공했습니다. 최종 문서·대화 기록 커밋은 이 검증된 소스·실행 ZIP을 그대로 보존합니다.

[사용법](docs/LAB_3.7.0_KR.md) · [실행 검증](docs/VERIFICATION_LAB_3.7.0.json) · [Docker ZIP](downloads/docker/3.7.0/community-security-lab-3.7.0-docker.zip) · [대화](docs/chat-logs/2026-10-17/community-unified-lab-370.md)

이하 항목의 검사 수와 화면은 각 이전 작업 당시의 기록입니다.


## JS Repeater 전송 확인·Ctrl+Space·최대화 0.37.2

사용자 첨부 화면은 `REPLACE_METHOD`가 남아 있어 전송 전 차단된 상태였습니다. Repeater에 메서드 선택·적용과 줄바꿈되는 안내를 추가했습니다. 자동으로 GET을 추정하거나 미확정 값·SCOPE 검사를 우회하지 않습니다. Ctrl+Space는 창 내부 포커스에서 Send와 같은 검사를 거치고 키 반복·다른 창 간 전파를 막도록 보강했습니다. 최대화·이전 크기 버튼은 현재 모니터의 작업 영역과 원래 창 크기를 오갑니다.

기준 main `b072bc2`에서 정식 release **2,455개**(Java 1,956·HTTP 132+101+266), 별도 Mapper Java 94·Node 7, 두 테마 화면 **270개**, 패키징·배포 보존 회귀 **6개**와 배포 해시·소스 지문 검사를 통과했습니다. Repeater 73개 검사는 미확정 메서드 수정 후 실제 loopback 요청·응답 4회, 합성 Ctrl+Space·중복 억제·다른 창 격리, 최대화·복원 경계 어댑터를 포함합니다. 실제 Burp API·물리 키·Windows IME·OS 창 조작은 미실행이며 외부 첨부 대상에는 요청하지 않았습니다. 최초 검사 설정 오류와 재실행은 검증 기록에 분리했습니다. 원격 CI와 최종 병합 상태는 이번 PR Checks를 기준으로 확인합니다.

[변경·사용 순서·화면](docs/RELEASE_0.37.2_KR.md) · [실행 검증](docs/VERIFICATION_0.37.2.json) · [다운로드](downloads/README.md) · [날짜별 대화](docs/chat-logs/2026-10-17/workbench-repeater-0372.md)

이하 0.37.1과 이전 기록은 당시 검증 범위로 보존합니다.

## JS 분석 가독성·명칭 통일 0.37.1

승인된 이미지 시안을 반영해 상단 2×2 빨간 집계의 이름을 13px, 숫자를 16px 굵게, 한 행을 40px로 키우고 내부 여백과 옅은 테두리를 적용했습니다. 주요 경로 / 민감한 정보 / 도메인 / 클라우드로 결과 탭 이름을 맞췄으며 분석 목록과 상세의 관련 명칭도 통일했습니다. 전체 분석의 중복 제외 계산과 기존 작업 동작은 유지합니다.

최신 main `9179057` 기준 정식 release **2,442개**(Java 1,943·HTTP 132+101+266), 별도 Mapper Java 94·브라우저 도우미 회귀, 두 테마 운영 화면 **266개**, 패키징·배포 보존 회귀 **6개**와 다운로드 해시·소스 지문 검사를 통과했습니다. JS 화면 44개에 1024×720·1280×800, 큰 집계 50,000을 포함하며 잘림·작업 영역 붕괴는 0개입니다. 실제 Burp GUI·OS DPI·IME는 미실행입니다. 원격 CI와 최종 병합 상태는 이번 PR의 Checks에서 확인합니다. 기존 확장·실습 사이트 3.6.0·Mapper 0.3.1 배포는 보존합니다.

[변경·실제 Swing 화면](docs/RELEASE_0.37.1_KR.md) · [실행 검증](docs/VERIFICATION_0.37.1.json) · [다운로드](downloads/README.md) · [날짜별 대화](docs/chat-logs/2026-10-17/workbench-js-readable-0371.md)

이하 사이트 3.6.0·확장 0.37.0 기록은 해당 작업 당시의 검증 범위입니다.

## 관리자·사용자 전용 포털 — 사이트 3.6.0

`/admin`과 `/user`에 별도 로그인·메뉴·업무 화면을 추가했습니다. 관리자는 전체 회원·요청 검토·승인/반려·공지 설정, 일반 사용자는 본인 요청 작성·조회·취소와 프로필을 사용합니다. 기본 수정 API는 실제 DB 역할·객체 소유자·계정 CSRF를 검증하며, 별도 취약 API는 로그인 후 역할·소유자 검증 누락을 비교하는 대조군입니다. 동일 Origin에서 admin·alice·bob 세션을 브라우저 프로필별로 유지해 비교합니다.

현재 소스를 컴파일한 별도 세션의 실제 loopback HTTP **101개**, 정식 `python dev.py release` **2,442개**(Workbench Java 1,943·HTTP 132+101+266), 별도 Mapper Java 94·Node 7과 패키징·배포 보존 회귀 6개를 통과했습니다. 새 3.6.0 Docker ZIP의 역할별 소스·Windows BOM, 다운로드 해시·소스 지문도 확인했습니다. Workbench 0.37.0·Mapper 0.3.1과 기존 배포는 유지합니다. 브라우저 DOM·실제 Burp·Windows Docker Desktop 전체 구동은 미실행입니다. 현재 소스의 [원격 Docker/Oracle/Nginx 실습 스택](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/38996673370)과 loopback 포트·TLS·HTTP/2 검사도 성공했습니다. 역할별 101개 HTTP 검사는 이 스택 검사와 별도의 H2 결과입니다. 배포 내구성 검사의 구버전 고정 경로를 현재 POM 기준으로 보완했으며 원격 최종 CI·main 병합 상태는 [PR #41](https://github.com/ohjun1998/burp-workbench-kr/pull/41) Checks에서 확인합니다.

[사용법·역할별 기대 코드](docs/LAB_3.6.0_KR.md) · [실행 검증](docs/VERIFICATION_LAB_3.6.0.json) · [Docker 다운로드](downloads/docker/3.6.0/community-security-lab-3.6.0-docker.zip) · [날짜별 대화](docs/chat-logs/2026-10-17/community-role-portals-360.md)

이하 확장 0.37.0의 변경과 이전 사이트 기록은 당시 범위로 보존합니다.

## 쿠키 식별·세션 신원 검사 0.37.0

계정 권한 비교에 세션 검사 창을 추가했습니다. 계정별 사용자 확인 GET URL·JSON 경로·기대 사용자 값을 설정하고 전체 인증과 비로그인 대조, 쿠키 제외·단독·순차 조합 축소를 실행합니다. HTTP 200만으로 로그인 성공을 판정하지 않으며, 여러 쿠키가 필요한 경우와 Bearer 등 다른 인증 헤더의 기여를 구분합니다.

검증한 조합은 명시적 적용으로 해당 계정에만 반영하고, 비교 전 신원 검사를 켜면 각 비교 직전에 신원과 비로그인 대조를 다시 확인합니다. 실패 시 해당 계정의 비교 요청을 보내지 않고 재검토로 남깁니다. 같은 Origin/SCOPE GET, 전송 직전 범위 확인, 자동 리다이렉트 금지와 취소·변경 시 늦은 결과 차단을 적용합니다.

로컬 정식 release 2,442개(쿠키·세션 50개 포함), 운영 화면 266개와 패키징 4개를 통과했습니다.

[사용법·한계·화면](docs/RELEASE_0.37.0_KR.md) · [실제 검증](docs/VERIFICATION_0.37.0.json) · [다운로드](downloads/README.md) · [대화](docs/chat-logs/2026-10-17/workbench-cookie-session-0370.md)

최신 main 98538d0의 JS 분석 고도화와 배포 0.36.0을 보존해 재적용했습니다. [PR #40](https://github.com/ohjun1998/burp-workbench-kr/pull/40)이 기존 구 main 기반의 미병합 후보 PR #39를 대체합니다.

0.37.0 코드 head `0add0a7`의 원격 Build and verify/check, Linux 격리 디스크 고갈·복구와 180초 연속 수집, Windows 전체 Java 회귀·저장/캐시 10회 반복·Mapper·180초 수집·PowerShell 검사가 모두 성공했습니다. [빌드·화면·패키징](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/38990649436) · [Linux/Windows 내구성](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/38990649479). 이어가기에서 배포 소스·파일 203개의 Git blob 일치와 다운로드 해시·소스 지문을 재확인했습니다. 이 확인은 새 제품 회귀 검사로 합산하지 않습니다. 실제 Burp GUI·OS DPI·IME는 미실행입니다.

최종 문서 커밋·병합 및 main 자동 검사 상태는 PR #40을 기준으로 확인합니다. [이번 이어가기 대화](docs/chat-logs/2026-10-17/workbench-cookie-session-0370-continuation.md)를 함께 보존합니다.

이하 0.36.0 기록은 이전 배포로 보존합니다.

## JS 전체 고도화 0.36.0

2026-10-17 / account-a / Issue #35 / [PR #36](https://github.com/ohjun1998/burp-workbench-kr/pull/36). 현재 배포 기준은 0.36.0입니다. PR에서 코드 검증과 main 반영 이력을 확인할 수 있습니다. 기존 레이아웃·표 열·연속 스크롤을 유지한 API 해석, Axios/래퍼, 큰 JS/압축, 소스맵, 디스크 이력, 실제 패킷 연결, GraphQL·검토 우선순위·중복 통합을 구현했습니다. [사용법·한계](docs/RELEASE_0.36.0_KR.md)와 [실행 검증](docs/VERIFICATION_0.36.0.json)을 기준으로 확인하세요.

기준: 2026-10-17 (한국 시간) / Workbench 0.36.0 / 실습 사이트 3.5.2 / 별도 Feature Mapper KR 0.3.1.

0.36.0 코드의 GitHub Build and verify와 Linux·Windows 내구성 검사가 모두 통과했습니다. 회귀·HTTP 2,392개, 화면·패키징·배포 지문, 격리 디스크 고갈·복구, 180초 수집과 Windows 반복 저장·PowerShell 검사를 포함합니다. 아래 0.35.1 기록은 이전 배포로 보존합니다.

## JS 버튼 정돈·2×2 빨간 집계 0.35.1

승인된 최종 시안을 반영해 분석 ON·대상 설정·분석 목록/상세를 첫 줄에, 요청 설정·원본 위치·추가 도구·더보기를 둘째 줄에 배치했습니다. 분석 목록과 상세는 공통 프레임으로 묶고 앞 구분선은 짧고 옅게 유지합니다. 기존 URL·메서드 확인은 요청 설정으로 줄였으며 동작과 Ctrl+R·Ctrl+Space는 유지합니다.

우측 상단 집계는 주요 경로·민감한 정보 / 도메인·클라우드의 2×2 배열, 11px 이름·13px 숫자와 빨간 표시등으로 정리했습니다. 긴 원래 명칭은 툴팁에 남기고 전체 보관 분석의 중복 제외 계산을 유지합니다. 분석 ON 버튼은 초록색, OFF 버튼은 빨간색입니다.

로컬 정식 release 2,348개, 두 테마 운영 화면 258개와 패키징 4개를 통과했습니다. 현재 검증 범위와 실행 결과는 [0.35.1 검증 기록](docs/VERIFICATION_0.35.1.json), 원격 최종 검사는 관련 PR의 Checks에서 확인합니다.

[변경·사용법](docs/RELEASE_0.35.1_KR.md) · [다운로드](downloads/README.md) · [대화](docs/chat-logs/2026-10-17/workbench-js-toolbar-red-0351.md)

이하 0.35.0 기록은 이전 배포로 보존합니다.

## 분석 상세 창·전체 집계·패킷 스타일 0.35.0 / Mapper 0.3.1

분석 상세를 분석 목록 바로 오른쪽 버튼으로 옮기고 요약 카드·항목별 읽기 전용 표를 별도 창으로 제공합니다. 상단 오른쪽의 초록 불빛 집계는 전체 보관 분석의 URL/API·토큰/API 키 후보·도메인·클라우드 URL을 중복 제외해 표시하며 선택 변경과 독립적으로 갱신합니다.

분석 원문과 Workbench·Feature Mapper의 패킷 영역에 Burp 스타일 구문 색상과 왼쪽 줄 번호를 적용했습니다. 문서·패킷 원본과 감지 위치·비교 강조를 유지하고 호스트 테마 변경 뒤 스타일을 복원합니다.

Repeater에서 Ctrl+Space는 Send와 동일하게 동작합니다. 0.33.0은 미확정 쿼리·본문의 REPLACE_ME를 보낼 수 있었으며 편집 후 이전 응답이 남았습니다. 이제 확인된 소스 상수·URL 쿼리·params·중첩 JSON 값을 초안에 반영하고 자료형을 보존합니다. 미해결 메서드·경로·값 및 REPLACE_PARAMETER 별칭도 전송을 차단합니다. 편집과 전송 시작·실패 시 이전 응답을 비워 현재 요청과 혼동하지 않게 합니다. 원문 위치별 값을 연결하고 URL·메서드 수동 확인에서도 기존 값을 유지합니다.

인증 헤더는 자동 복사하지 않고 이름으로 식별한 민감 필드는 미확정으로 남깁니다. 지원하지 않는 직렬화·실행 중 값은 Raw 확인이 필요합니다. 풍부한 초안은 기존 바이트 예산 안의 메모리 캐시에만 두고 가림 보고서·내보내기에 추가하지 않습니다.

로컬 정식 release 2,348개(56그룹 Java 1,849·HTTP 132+101+266), Mapper Java 94·Node 7, 운영 화면 288개와 패키징 4개를 통과했습니다. 실제 Burp GUI·OS DPI·IME 키 전달은 별도 범위입니다. 원격 최종 검사와 main 반영은 관련 PR의 Checks를 기준으로 확인합니다.

[변경·사용법](docs/RELEASE_0.35.0_KR.md) · [실제 검증 기록](docs/VERIFICATION_0.35.0.json) · [다운로드](downloads/README.md) · [대화](docs/chat-logs/2026-10-17/workbench-packet-draft-0350.md)

이하 0.33.0 기록은 이전 배포로 보존합니다.

## JS 작업 영역·Repeater 창 0.33.0

확정한 시안의 분석 상태등·대상 설정·분석 목록과 도구를 한 줄로 배치했습니다. 분석 목록 뒤에는 작은 옅은 구분선을 두고, 상태·대기/캐시/절단 문구는 오른쪽 하단 11px로 옮겼습니다. 큰 배너와 원문 안쪽 설정 행을 제거해 원문·결과 높이를 늘렸습니다. 결과 탭 오른쪽은 좌·우 화살표를 함께 표시합니다.

Ctrl+R는 별도의 모델리스 Repeater 창을 바로 엽니다. 원문에서 얻은 메서드·URL·쿼리 이름·본문 키로 초안을 준비하고 실제 값은 REPLACE_ME로 둡니다. 동적 경로/미확정 메서드는 REPLACE_PATH/REPLACE_METHOD를 표시합니다. Raw 편집, 표시 전용 Pretty/Hex, 독립 Search, 명시적 Send, 실제 응답/시간/크기와 요청·응답 기록을 제공합니다. Send 직전 Scope를 다시 확인하며 자동 리다이렉트는 따르지 않습니다. 발견한 인증 정보는 자동 복사하지 않습니다.

로컬 release 2,230개, 운영 화면 246개와 패키징 4개를 통과했습니다. 실제 실행 결과는 [0.33.0 검증 기록](docs/VERIFICATION_0.33.0.json)과 관련 PR의 최종 Checks를 확인합니다. 새 회귀는 별도 loopback 서버가 받은 쿼리와 UTF-8 JSON 본문을 검사합니다. 실제 Burp GUI·DPI는 별도 범위입니다.

[변경·사용법·요청 조합 설명](docs/RELEASE_0.33.0_KR.md) · [다운로드](downloads/README.md) · [날짜별 대화](docs/chat-logs/2026-10-17/workbench-js-layout-repeater-0330.md)

이하 0.32.1 기록은 이전 배포로 보존합니다.

## JS·텍스트 분석과 목록 팝업 0.32.1

선택 URL의 Repeater 버튼은 Ctrl+R로 바꾸고 정렬된 행을 연결합니다. 경로나 메서드가 불명확하면 확인 창에서 값을 정한 뒤 초안을 준비하며 직접 전송하지 않습니다. 상시 요청·응답 패킷 영역을 제거하고 분석 원문·결과 표를 넓혔습니다. 결과 표의 더블클릭/Enter로 발견 출처의 요청·응답을 모델리스 창에 표시하며, 열린 스냅샷은 다른 분석으로 바뀌지 않습니다.

최근 분석 드롭다운·History 탭은 ‘분석 목록’ 창으로 통합했습니다. 출처 URL 열은 넓게 유지하고 가로 스크롤을 제공합니다. 수동 분석·선택 응답 가져오기·수집 파일 저장 버튼/메뉴와 API 필터를 제거하고 전체 API를 표시합니다. 분석 ON은 초록색, OFF는 빨간색이며 OFF가 대기 작업과 늦은 결과 적용을 중지합니다. ON은 History를 다시 확인합니다.

JS AST를 유지하면서 JSON·XML·HTML·일반 텍스트의 URL·토큰·API 키 후보를 분석합니다. 원문 위치·키 가림·쿼리 값 제외와 Scope 조건을 유지하고 바이너리는 제외합니다. XML 외부 엔티티를 해석하지 않습니다. 원본 수집/보관 제한은 새로 두지 않습니다.

최종 로컬 release 2,191개(Java 52그룹 1,692·HTTP 132+101+266), Mapper Java89·Node7, 운영 화면 238개와 패키징 4개를 통과했습니다. 새 텍스트 분석23·사용 흐름48 검사를 포함합니다. 실제 Burp GUI·DPI는 별도 범위이며 원격 최종 검사와 main 반영 상태는 관련 PR을 기준으로 합니다.

[변경·화면·사용법](docs/RELEASE_0.32.1_KR.md) · [검증](docs/VERIFICATION_0.32.1.json) · [다운로드](downloads/README.md) · [날짜별 대화](docs/chat-logs/2026-10-16/workbench-js-response-windows-0320.md)

이하 0.31.2 기록은 이전 배포로 보존합니다.

## Pretty·Raw·Hex·Search 0.31.2

승인한 시안의 탭·줄 번호·구문 강조·하단 Search를 운영 PacketInspector로 적용했습니다. 요청·응답은 독립된 검색어·보기·옵션을 유지하며 결과 강조·이전/다음 순환·현재 위치/개수·Aa·정규식·단축키·줄바꿈을 지원합니다. ⋯ 버튼은 Burp 기본 읽기 전용 편집기를 별도 창에 열어 전체 원본과 추가 보기를 유지합니다.

Raw 줄바꿈·원본 바이트·큰 정수 보존, Hex 오프셋·ASCII, 검색 취소·오래된 결과 차단·정규식 오류/시간 예산·해제 정리를 검사합니다. 미리보기 64 KiB·검색어 512자·강조 2,000개는 화면 범위이며 수집·저장 원본의 개수/본문 제한은 아닙니다.

정식 release 2,120개(Java 50그룹 1,621·페이로드 HTTP 132·전체 기능 HTTP 101·모의 진단 266), Mapper Java 89·Node 7, 화면 214개·패키징 4개를 통과했습니다. 패킷 전용 41개에는 Windows 줄바꿈과 검색 위치 회귀를 포함합니다. 최종 검사 결과는 [0.31.2 검증 기록](docs/VERIFICATION_0.31.2.json)을 기준으로 합니다. 0.31.0 후보는 원격 Windows 줄바꿈 회귀에서, 0.31.1 후보는 첫 비동기 검색 검사의 대기 시점에서 실패하여 main에 반영하지 않았고 원본 기록을 보존합니다. 0.31.2는 실제 결과 적용까지 기다리며 정규식 컴파일과 매칭 시간 예산을 분리합니다. 원격 최종 결과는 PR #27을 기준으로 합니다. 실제 Burp GUI·OS DPI는 별도 범위입니다.

[사용법·화면·인수인계](docs/RELEASE_0.31.2_KR.md) · [검증](docs/VERIFICATION_0.31.2.json) · [다운로드](downloads/README.md) · [작업 대화](docs/chat-logs/2026-10-16/workbench-packet-tabs-search-0310.md)

이하 0.30.0 기록은 이전 배포로 보존합니다.

## SCOPE 수정·조밀한 트래픽·Burp 패킷 보기 0.30.0

SCOPE 목록에서 체크한 수동 URL도 SCOPE 수집과 History 가져오기에 즉시 적용합니다. 수동 행 선택 → 입력 URL 변경 → URL 수정으로 편집하며 체크 상태·순서를 유지합니다. 이전 URL을 교체하고 중복·잘못된 입력은 원자적으로 거부합니다. Burp Scope 자동 반영·네이티브 실시간 포함 확인·OFF 수집 금지를 유지하며 Burp 전역 Scope를 바꾸지 않습니다. 수동 규칙에는 내보낸 활성 제외 규칙을 적용하고 내보내기 미지원 시의 한계도 안내합니다.

트래픽 표를 24px 행·13px 나눔고딕·28px 머리글로 줄여 같은 영역에 약 50% 더 많은 행을 표시합니다. 요청·응답에 Burp READ_ONLY 메시지 편집기의 실제 UI를 연결합니다. 원본 객체를 전달하고 호스트 편집기 내부 스타일을 보존합니다.

정식 release 2,078개(Java 49그룹 1,579개·페이로드 HTTP 132개·전체 기능 HTTP 101개·모의 진단 266개), Mapper Java 89개·Node 7개, 운영 화면 150개·안내 48개·SCOPE 창 4개와 패키징 4개를 통과했습니다. 실제 Burp GUI·운영체제 DPI는 별도 범위이며, 원격 검사·병합 상태는 관련 PR을 기준으로 합니다.

[0.30.0 사용법·화면·인수인계](docs/RELEASE_0.30.0_KR.md) · [실행한 검사](docs/VERIFICATION_0.30.0.json) · [다운로드](downloads/README.md) · [작업 대화](docs/chat-logs/2026-10-16/workbench-scope-traffic-native-0300.md)

이하 0.29.0 기록은 이전 배포로 보존합니다.

## 작업 영역 정리·로그 0.29.0 / Mapper 0.3.0

확정된 이미지 시안을 운영 코드에 적용했습니다. Workbench 10개 도구의 큰 제목·반복 안내·상시 하단 요약을 정리하고 검색·목록·패킷 영역을 넓혔습니다. 상단 **기능 안내 → 설정 → 로그**는 도구를 바꿔도 유지됩니다. OFF/SCOPE 네이티브 수집 조건은 그대로입니다.

로그는 처리한 오류의 시각·단계·예외 종류·확장 코드 위치를 최근 1,000개까지 보관합니다. 예외 원문 메시지나 HTTP 패킷을 담지 않으며 복사·비우기·확장 해제 시 정리를 지원합니다. Feature Mapper에도 포함 한국어 글꼴·같은 테마·폴더/선택 강조·상단 안내/설정/로그와 넓어진 패킷 영역을 적용했습니다.

로컬 정식 release 2,044개(49그룹 Java 1,545개 포함), 별도 Mapper Java 89개·Node 7개, 운영 Swing 150화면·안내 48화면·Mapper 30화면과 패키징 4개를 통과했습니다. 연속 수집 327개는 누락·처리 오류 0건입니다. 원격 검사·병합 상태는 관련 PR에서 확인합니다. 실제 Burp GUI·운영체제 DPI는 별도 확인 범위입니다.

[0.29.0 적용 화면과 사용법](docs/RELEASE_0.29.0_KR.md) · [실행한 검사](docs/VERIFICATION_0.29.0.json) · [다운로드](downloads/README.md) · [대화](docs/chat-logs/2026-10-16/workbench-clean-layout-0290.md)

이하 0.28.1 기록은 이전 배포 상태로 보존합니다.

## 네이티브 Scope 전용 수집 0.28.1

전체 수집 ON을 제거하고 기본 OFF ↔ SCOPE 두 모드만 제공합니다. OFF는 새 패킷·수동 가져오기·트래픽 자동 관찰을 차단합니다. SCOPE는 Burp HTTP history → Add to scope로 등록된 네이티브 범위에 해당하고 Workbench에서 체크된 항목만 허용합니다. Burp 제외 규칙·삭제·조회 실패도 수집을 차단하며 Workbench 수동 URL 규칙이 수집을 허용하지 않습니다. 개별 도구의 명시적 실행과 이미 시작한 계정 비교의 응답 처리는 별도입니다.

큰 제목·검색 라벨 삭제, 검색창 상단 이동, 목록·패킷 영역 확장은 이미지 시안으로만 제시했습니다. 운영 코드의 제목·검색 라벨·분할 배치·크기와 테마는 유지했습니다.

로컬 정식 release 2,016개, 운영 Swing 144화면·안내 48화면과 패키징 4개를 통과했습니다. 로컬 연속 수집 321개는 누락·오류 0건입니다. 원격 Linux/Windows 검사는 관련 PR의 Checks에서 별도로 확인합니다.

[0.28.1 변경과 검증](docs/RELEASE_0.28.1_KR.md) · [실행 기록](docs/VERIFICATION_0.28.1.json) · [다운로드](downloads/README.md) · [대화 기록](docs/chat-logs/2026-10-16/workbench-native-scope-only-0281.md)

이하 0.28.0 기록은 이전 배포의 검증 경과로 보존합니다.

## 시안 기반 전체 화면 0.28.0

왼쪽 메뉴 외에 전체 색상 체계·포함 고딕 글꼴·트래픽 제목과 검색창·표·카드·분할선을 정리했습니다. ON/OFF/SCOPE는 초록/빨강/주황 상태등과 글자로 표시하며 수집 조건은 그대로입니다. 첫 업그레이드 때 목록 위·요청/응답 아래 배치로 전환하고 이후 사용자 배치를 유지합니다.

호스트가 등록 뒤 UI·렌더러·행 높이를 바꾼 상황을 재현해 메뉴 아이콘·상태등·제목·입력값 복원과 비동기 예외를 검사합니다. 실제 Burp GUI 실행으로 내부 호출을 추적한 결과는 아닙니다. 테마 변경 시 탭 뷰포트 이벤트 정리, 작은 화면 권한 비교의 요청·응답 영역도 보완했습니다.

[0.28.0 변경과 실제 코드 화면](docs/RELEASE_0.28.0_KR.md) · [실행한 검증](docs/VERIFICATION_0.28.0.json) · [다운로드](downloads/README.md) · [날짜별 대화](docs/chat-logs/2026-10-16/workbench-reference-ui-0280.md)

이하 0.27.1 기록은 이전 배포의 검증 경과로 보존합니다.

## 폴더 아이콘·선택 강조 0.27.1

첨부 시안에 맞춰 주황색 폴더·문서 벡터 아이콘과 선택 행 전체의 배경색·왼쪽 강조선을 적용했습니다. 메뉴를 14px 글자·36px 행으로 정리하고 호버·키보드 포커스와 행 빈 공간의 선택을 지원합니다. 오른쪽 작업 영역에 포커스가 있어도 현재 도구의 강조를 유지합니다. 기존 폭 조절·숨김·설정 저장·입력값 유지와 10개 도구 구성을 보존합니다.

[0.27.1 변경 안내·실제 화면](docs/RELEASE_0.27.1_KR.md) · [실행한 검증](docs/VERIFICATION_0.27.1.json) · [다운로드](downloads/README.md) · [날짜별 대화](docs/chat-logs/2026-10-16/workbench-folder-design-0271.md)

이하 0.27.0 기록은 이전 배포의 검증 경과로 보존합니다.

## 왼쪽 폴더 메뉴 0.27.0

기존 10개 도구를 트래픽 / 탐색·정보 수집 / 보안 분석·검증 / 데이터 도구 / 점검 관리·증거의 5개 폴더로 표시합니다. 기본 220px의 메뉴 폭 조절, 메뉴 숨기기·다시 보기, Ctrl+Shift+M과 Burp 환경설정의 폭·표시 상태 저장을 추가했습니다. 기존 페이지 인스턴스를 유지하며 우클릭·기능 안내 등의 이동도 폴더 선택과 동기화합니다. SCOPE는 요청·응답 목록 내부 기능입니다.

0.27.0 로컬 정식 release는 Java 47그룹 1,490개·페이로드 HTTP 132개·전체 기능 HTTP 100개·모의 진단 266개로 **1,988개**를 통과했습니다. 새 메뉴 40개는 Java 합계에 포함합니다. 별도 Mapper Java 75개·Node 7개, 운영 Swing 126화면·안내 48화면과 패키징 4개도 통과했습니다. 실제 Burp GUI·운영체제별 DPI·물리 마우스 조작은 별도 확인 범위입니다. 원격 검사와 main 반영 결과는 관련 PR의 Checks·설명에서 확인합니다.

[0.27.0 사용법·실제 화면·인수인계](docs/RELEASE_0.27.0_KR.md) · [검증 기록](docs/VERIFICATION_0.27.0.json) · [다운로드](downloads/README.md) · [날짜별 대화](docs/chat-logs/2026-10-16/workbench-folder-navigation-0270.md)

이하 0.26.x 기록은 당시 검증 경과로 보존합니다.

## Windows 동시 저장 보완 0.26.1

0.26.0 병합 뒤 Windows 재검사에서 동시 수집·조회 중 색인 읽기 오류를 발견했습니다. 읽기 채널이 쓰기 RandomAccessFile과 포인터를 공유하던 구조를 독립 READ 핸들로 분리하고 생성 실패·unload 정리를 보완했습니다. 기존 JAR에서 포인터 공유를 재현했고 5개 회귀를 추가했습니다. 0.26.1 로컬 release는 Java1,450·페이로드132·전체기능100·모의진단266으로1,948개를 통과했습니다. 저장소 회귀 두 그룹의10회 반복도 통과했습니다. 원격 Windows 반복 회귀·180초 수집과 PR/main 검사 결과는 해당 PR의 Checks에서 확인합니다. Mapper0.2.0·실습3.5.2와 과거 다운로드는 보존합니다. [수정과 인수인계](docs/RELEASE_0.26.1_KR.md) · [검증](docs/VERIFICATION_0.26.1.json).

이하의0.26.0 결과는 보완 전 검증 경과입니다.

## 최신 수정

소스 검토에서 제안한 P1·P2 여섯 항목을 적용했습니다.

- 수집: 원본·메타데이터 디스크 색인, 256행 메타데이터 캐시, 가상 테이블과 묶음 행 갱신, 한 패킷씩 백그라운드 관찰을 적용했습니다. 원본 개수·본문 제한을 다시 두지 않습니다.
- Mapper: 기능별 패킷 및 브라우저 이벤트 후보 인덱스를 추가하고, 저장 시점 스냅샷의 긴 복사·압축을 수집 잠금 밖에서 수행합니다. UI 증분 갱신과 선택 보존을 적용했습니다.
- 증거: 전체 문자열 대신 전용 디스크 참조를 보관하고 원본 복사·해시·ZIP 출력을 스트리밍합니다. 형식 2에서 원본·미리보기·가림 처리 출력의 해시와 크기를 구분합니다.
- JS: 24MiB 추정 캐시 LRU, 16MiB 대기/실행 스냅샷 예산, 늦은 작업 결과의 선택·초기화 검증을 적용했습니다. 최소 창 크기에서 상태줄이 미리보기 영역을 줄이던 문제도 수정했습니다.
- Activity·Assessment: 변경 행 갱신, 통계 캐시와 무효화, 숨은 탭 갱신 지연을 적용했습니다. 역할·조직·중요 입력·증거 변경의 재검토 처리를 유지합니다.
- 구조·릴리스: 저장·관찰·테이블·캐시 책임을 분리하고 수정한 생산 코드의 형식을 정리했습니다. 각 확장의 BuildInfo를 버전 기준으로 사용하며 실제 검사 건수를 집계합니다. 작업 브랜치 push와 PR의 중복 CI를 줄였습니다.

[최신 변경 안내](docs/RELEASE_0.26.0_KR.md) · [검증 기록](docs/VERIFICATION_0.26.0.json) · [성능 비교](docs/PERFORMANCE_0.26.0_KR.md) · [다운로드](downloads/README.md)

## 실행한 검증

2026-10-16 복구 환경에서 `python dev.py release --port 18569 --bounty-port 18669`를 다시 실행하여 통과했습니다. Workbench Java 46그룹·1,445개, 페이로드 HTTP 132개, 전체 기능 HTTP 100개, 모의 진단 266개로 총 1,943개입니다. Mapper Java 75개와 브라우저 도우미 Node 7개는 별도이며 Workbench 합계에 섞지 않습니다. 생산 Swing 구성요소의 108화면과 안내 48화면도 모두 통과했습니다. JAR·소스 ZIP의 해시, 현재 소스 지문, 업로드 대상 Git 트리와 소스 입력의 일치, ZIP의 임시 실행 파일 제외를 확인했습니다.

동일 하네스의 변경 전/후 비교에서 수집 4개 시나리오의 3,900건 전량과 원본 샘플을 보존했고 누락·처리 오류는 0입니다. 500건/초·1,500건 후 화면 배출은 5.993→0.304초, Mapper 15,000패킷의 기능별 조회 3,000회는 1,091.47→20.82ms였습니다. 색인 저장 비용(+3.43%, 해당 작은 응답 시나리오)과 순수 입력 시간 증가도 관측했습니다. 단일 공유 호스트의 API 어댑터 결과이므로 실제 Burp 처리량 보증으로 해석하지 않습니다. 계측 뒤 JS 상태문구 배치만 조정했으며 측정 소스 지문과 최종 릴리스 지문을 구분해 기록합니다.

재개 검증 기록은 [2026-10-16 검증](docs/VERIFICATION_RESUME_2026-10-16.json)과 [인수인계](docs/HANDOFF_RESUME_2026-10-16_KR.md)에 있습니다. 제품 소스 커밋 `0b0efef`의 원격 build·화면·패키징, Mapper Java75·Node7·Chromium13, Linux 실제 ENOSPC13, Linux/Windows 180초 수집과 PowerShell6 검사가 모두 성공했습니다. 연속 수집은 Linux 3,617건·Windows 3,639건이며 각각 누락·처리 오류 0건입니다. 수집은 Montoya 호스트 어댑터, 브라우저 검사는 실제 Chromium과 production Mapper/Bridge fixture입니다. 마지막 문서 커밋과 병합 상태는 [PR #19](https://github.com/ohjun1998/burp-workbench-kr/pull/19)에서 확인할 수 있습니다. 기존 0.25.5의 Linux/Windows 내구성·Docker 결과는 [과거 검증](docs/VERIFICATION_DURABILITY_2026-10-15.json)에 보존하며 새 버전 결과에 합산하지 않습니다. 이전 다운로드와 Mapper 0.1.0 파일은 보존합니다.

## 남은 범위

사용자 전체 검색·정렬은 메타데이터 전체를 검사하며 Mapper 저장의 메타데이터 스냅샷 복사는 잠금 안의 O(N) 작업입니다. JS 24MiB는 캐시 추정 예산이지 JVM 전체 힙 상한은 아닙니다. Workbench의 영속 프로젝트 저장·재개, Workbench–Mapper 공통 패킷 ID 연결은 우선순위 표와 별도로 제안했던 후속 고도화 후보입니다. 이번 증거 내부 packetId와 구분합니다.

실제 Burp GUI 우클릭 전체 흐름, Scope 목록을 내보내지 않는 버전의 관측 전 URL·고급 규칙 전체 열거, 다시간 수집, Pro Collaborator·외부 BApp, Windows Docker Desktop 전체 구동은 별도 검증 범위입니다. 수동 workflow의 2·3시간 옵션은 실제 실행한 시간과 구분합니다.

main에는 병합한 변경을 기록하고 진행 중인 소유권과 검토는 PR에서 관리합니다. GitHub 변경과 관련된 사용자 요청·응답은 [한국 날짜별 대화 기록](docs/chat-logs/README.md)에 함께 남깁니다. [변경 전 전체 소스 검토](docs/SOURCE_REVIEW_2026-10-15_KR.md)는 당시 진단의 역사 기록으로 보존합니다.

