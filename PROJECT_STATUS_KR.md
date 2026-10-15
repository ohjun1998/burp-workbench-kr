# 현재 개발 상태

기준: 2026-10-15 (한국 시간) / Workbench 0.25.5 / 실습 사이트 3.5.2 / 별도 Feature Mapper KR 0.1.0.

## 최신 수정

- Legacy 페이로드 응답 완료 직후에도 다음 요청을 사용 중으로 거절하던 FutureTask 경쟁을 수정했습니다. 콜백 반환/실패 처리에서 먼저 해제하고 작업마다 한 번만 해제하여 새 요청의 잠금을 이전 작업이 지우지 않도록 합니다.
- 5,000회 순차 전송, 완료 시 idle, 콜백 비중첩, 예외 후 해제·재개 회귀 5개를 추가했습니다. 독립 재현은 수정 전 정상 459건 중 잘못된 거절 5건, 수정 후 100,000건 중 잘못된 거절 0건입니다.
- 실습 3.5.2는 한글 start.ps1의 UTF-8 BOM으로 Windows PowerShell 5.1 파싱 오류를 수정합니다. 이전 확장·Docker 다운로드는 보존합니다.
- 0.25.2 계보의 수집 제한 제거·임시 디스크 보관, ON/OFF/SCOPE·실시간 검색·체크 관리, Burp Scope 보완, Mapper 통합과 26쪽 PDF는 유지합니다.
- 독립 tmpfs ENOSPC·수집 내구성·Windows 원본 회귀·PowerShell 검사를 추가하고 Docker 검사를 현재 작업 브랜치와 main에서도 실행하도록 연결했습니다.

[최신 변경 안내](docs/RELEASE_0.25.5_KR.md) · [검증과 잔여 범위](docs/VERIFICATION_DURABILITY_2026-10-15.json) · [인수인계](audit/durability/README_KR.md) · [다운로드](downloads/README.md)

## 실행한 검증

0.25.5 로컬 release는 Java 회귀 1,298개·페이로드 HTTP 132개·전체 기능 HTTP 100개·모의 진단 266개, 총 1,796개를 통과했습니다. 현재 실습 소스를 컴파일해 검증했으며 JAR·소스 ZIP·Docker ZIP의 소스 대응과 해시를 확인했습니다. 새 커밋의 원격 Windows·수집 검사는 별도 검증 기록과 Actions 상태를 기준으로 읽습니다.

추가 하네스에서 16 MiB 독립 tmpfs의 실제 ENOSPC·부분 기록 롤백·기존 원본 보존·공간 확보 후 재개 13개와 Linux 3분 수집 3,626건(drop·처리 오류 0)을 확인했습니다. 실습 3.5.2의 Oracle HTTP 54개·Nginx·네 포트 loopback 게시·TLS·HTTP/2를 원격에서 확인했습니다. PowerShell 검사는 실제 Windows PowerShell 5.1 파서와 시작 스크립트 분기를 실행하되 Docker·브라우저 명령은 제어된 어댑터입니다.

0.25.4에서 실행했던 실제 Burp API·loopback 15개와 5분 수집, Mapper Java 48개·Node 7개·실제 Burp 12개·Chromium fixture 13개와 Swing 156화면의 기록은 [이전 검증](docs/VERIFICATION_0.25.4.json)에 보존합니다. 이를 이번 새 버전의 재실행으로 합산하지 않습니다.

## 남은 범위

실제 Burp GUI 우클릭 전체 흐름, Scope 목록을 내보내지 않는 버전의 관측 전 URL·고급 규칙 전체 열거, 다시간 수집, Pro Collaborator·외부 BApp, Windows Docker Desktop 전체 구동은 남아 있습니다. 수동 workflow의 2·3시간 어댑터 수집 옵션은 실행한 시간과 구분합니다.

## 이전 개선안과 기록

역할·조직 변경 재검토·중요 입력 감지·계정×API 증거 요건·파일 재연결은 0.22.1, 저장/조회 단계·Passive 및 데이터 비교 원본 연결·증거 미리보기는 0.23.0에서 구현했습니다. 이를 새 미완료 결함으로 중복 취급하지 않습니다. 자동 신원 확인·정책 태그별 대표 URL·일반 바이너리 증거 첨부는 추가 개선 후보입니다.

main에는 병합한 변경을 기록하고 진행 중인 소유권과 검토는 PR에서 관리합니다. GitHub 변경과 관련된 사용자 요청·응답은 [한국 날짜별 대화 기록](docs/chat-logs/README.md)에 함께 남깁니다.
