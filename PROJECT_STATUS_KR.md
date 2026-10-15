# 현재 개발 상태

기준: 2026-10-15 (한국 시간) / Workbench 0.25.4 / 실습 사이트 3.5.2 / 별도 Feature Mapper KR 0.1.0.

## 반영 내용

- 0.25.2 기반의 수집 제한 제거·임시 디스크 보관, 0.25.3 ON/OFF/SCOPE·실시간 검색·체크 관리 유지.
- JS 검사에서 worker 완료 후 EDT 반영까지 기다리도록 수정.
- 실제 Burp에서 Scope 설정 내보내기에 대상이 없는 경우 공개 Scope 판정과 관측 URL로 동기화 보완. 체크 해제·삭제와 Burp 제외 규칙 유지.
- Feature Mapper 독립 모듈·설치 파일·보조 확장·검증 workflow 통합.
- 0.25.4용 읽기 쉬운 26쪽 PDF와 최신 Scope 사용법 제공.
- GitHub 변경마다 한국 날짜별 관련 작업 대화 기록.

[변경 안내](docs/RELEASE_0.25.4_KR.md) · [검증](docs/VERIFICATION_0.25.4.json) · [인수인계](docs/HANDOFF_0.25.4_KR.md) · [다운로드](downloads/README.md)

## 실행한 검증과 남은 범위

현재 릴리스 회귀·HTTP 검사 1,791개와 패키징 4개, Swing 기본·안내 156화면을 확인했습니다. 실제 Burp API·loopback 15개와 5분 수집, Mapper Java 48개·Node 7개·실제 Burp 12개, 실제 Chromium/Mapper fixture 13개를 확인했습니다. Docker 3.5.1은 Oracle HTTP 54개와 Nginx·네 포트의 loopback 게시·TLS·HTTP/2 응답을 원격 Actions에서 통과했습니다. 실제 GUI 클릭은 별도 확인 범위입니다.

2026-10-15 추가 검증에서 **독립 16 MiB tmpfs의 실제 ENOSPC·부분 기록 롤백·기존 원본 보존·공간 확보 후 재개**를 확인했습니다. Windows JDK 17의 Java 회귀·수집 및 Windows PowerShell 5.1의 시작 스크립트 분기도 검증합니다. 확장 소스와 0.25.4 배포 파일은 유지합니다. Windows PowerShell 5.1에서 발견한 한글 start.ps1 인코딩 문제는 UTF-8 BOM과 새 실습 3.5.2 ZIP으로 수정했습니다. 새 실행 결과와 정확한 범위는 [잔여 검증 기록](docs/VERIFICATION_DURABILITY_2026-10-15.json), 재현 방법은 [수집 내구성 검증](audit/durability/README_KR.md)에 분리해 기록합니다.

남은 범위는 실제 Burp GUI 우클릭 전체 흐름, Scope 목록 내보내기를 지원하지 않는 버전의 관측 전 URL/고급 규칙 전체 열거, 다시간 수집, Pro Collaborator·외부 BApp 및 Windows Docker Desktop 전체 구동입니다. 새 Windows 시작 스크립트 검사는 Docker·브라우저 명령을 제어된 어댑터로 실행하며 실제 Docker Desktop 구동으로 집계하지 않습니다. 2·3시간 어댑터 수집은 새 workflow 수동 실행에서 선택할 수 있으나 이번에 실행한 시간과 구분합니다.

## 이전 개선안의 상태

과거 0.21.0 문서의 역할·조직 변경 재검토, 중요 입력 감지, 계정×API 증거 요건·파일 재연결은 0.22.1에서 구현했습니다. 저장/조회 단계와 Passive·데이터 비교 원본 연결, 증거 미리보기는 0.23.0에서 구현했습니다. 이를 새 미완료 결함으로 중복 취급하지 않습니다. 자동 신원 확인, 정책 태그별 대표 URL과 일반 바이너리 증거 첨부는 추가 개선 후보이며 이번 요청에서 새 기능으로 구현하지 않았습니다.

## 기록 원칙

main에는 병합한 변경을 기준으로 이 문서를 유지합니다. 진행 중인 소유권과 검토는 Issue/PR, 실제 실행한 명령과 미실행 범위는 버전별 검증·인수인계에 남깁니다. 이전 다운로드와 과거 검증 기록은 보존합니다. GitHub 변경에 관련된 사용자 요청과 응답은 [날짜별 대화 기록](docs/chat-logs/README.md)에 함께 남깁니다.

