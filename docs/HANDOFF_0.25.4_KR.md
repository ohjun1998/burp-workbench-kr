# 0.25.4 인수인계

출발 main: `6acffd3db131af595c84c3902f727f6392a1ecd5`. Feature Mapper 계승 커밋: `e70b1a395c13fde16a6a6b5be72e085fe67d25cc`. 최신 main을 기본으로 두고 Mapper의 독립 모듈과 workflow·다운로드를 추가합니다. 공유 README는 Workbench 최신 설명을 유지하며 Mapper 진입점을 추가합니다. 다른 계정의 작업 브랜치는 변경하지 않습니다.

## 변경 이유와 구현

- main Actions의 실패는 `JsHistoryTest.done()`이 worker 완료와 EDT render 완료를 같은 시점으로 보던 대기 순서 문제입니다. FIFO 완료 신호를 worker 큐에 넣고 완료를 기다린 뒤 EDT barrier를 통과합니다. 큐가 가득 찬 경우도 timed `offer`로 완료 신호를 넣어 압력 검사 자체가 신호 추가를 거부하지 않게 했습니다.
- 실제 Burp Community 2026.3.3에서는 `isInScope()`가 참이어도 project options에 `target`이 없고 `exportProjectOptionsAsJson("target.scope")`는 `{}`였습니다. 이를 모의 호스트의 지원 여부로 대신 판단하지 않고 실제 실행에서 확인했습니다.
- `WorkbenchScope.syncObserved`와 `WorkbenchExtension.syncBurpScope`에 공개 API 기반 보완을 추가했습니다. 설정이 있으면 정확한 원래 규칙을 읽고, 없으면 History/Site Map/선택 요청에서 관측 URL과 상위 경로를 Scope 판정에 질의합니다. 체크·삭제한 부모는 새로운 자식 요청으로 다시 등록하지 않습니다. 캡처는 실제 Scope 제외 규칙도 확인합니다.
- 이 방식은 관측할 수 없는 기존 URL·고급 규칙 전체의 열거를 보장하지 않습니다. 이 한계와 URL 추가 방법을 현재 가이드에 반영했습니다. 내부 Burp 클래스나 비공개 필드를 생산 코드에서 사용하지 않습니다.
- 새 PDF는 기존 쉬운 한국어 문장과 페이지 참조를 유지하되, 설치 버전·현재 Scope 절차를 고치고 25~26쪽을 추가했습니다. 본문 글자를 검정 계열로 유지하고 번호·배경·상자로 단계를 구분합니다. 지원하지 않는 드롭다운 기호는 ‘메뉴’로 풀었습니다.
- `tools/record_chat.py`와 AGENTS 규칙으로 관련 사용자 요청/응답을 한국 날짜별로 기록합니다. 원문·요약을 구분하고 전체 계정 채팅 자동 내보내기와 구분합니다.

## 실제 검증

정확한 명령·횟수·해시는 `VERIFICATION_0.25.4.json`과 `audit/0.25.4/`를 기준으로 읽습니다. 현재 릴리스 검사는 회귀 1,298개/41그룹, 페이로드 HTTP 127개, 전 기능 HTTP 100개, 모의 진단 266개를 통과했습니다. 이전 버전의 검사 수는 합산하지 않습니다.

별도 Burp 설치와 Java 21, 임시 사용자/데이터 디렉터리에서 Scope API·ON/OFF/SCOPE·체크 해제·제외 규칙·2MiB 바이트 복원·601건 추가 수집·unload 정리를 확인했습니다. 사용자 Burp/프록시 설정은 변경하지 않았습니다. JS 검사 20회 반복과 실제 Scope export 생략 회귀 8개를 확인했습니다. 기능 Mapper는 Java 48개·Node 7개와 실제 Burp 12개를 다시 실행했습니다.

소스만 복원한 이 환경에서는 실습 WAR 대신 고정 SHA-256이 일치하는 Maven 런타임 JAR만 담은 로컬 의존성 아카이브를 준비했습니다. HTTP 검사는 현재 실습 소스를 다시 컴파일했습니다. 이 임시 아카이브는 GitHub에 올리거나 기존 WAR를 대체하지 않습니다. 기존 Docker 3.5.0 ZIP/manifest/SHA256SUMS는 보존합니다. 원격 전체 검사에서 컨테이너가 정상이어도 internal 전용망에는 호스트 포트가 게시되지 않아 8088 접속이 실패했습니다. 실습 3.5.1에서 웹·프로토콜 서비스의 접속용 bridge와 Nginx HTTP healthcheck를 추가하고 DB·WAS 내부망과 127.0.0.1 바인딩을 유지합니다. Docker 공식 [Compose 네트워크 설명](https://docs.docker.com/compose/how-tos/networking/)과 원격 실제 포트 상태를 함께 확인했습니다.

초기 확인에서 Scope export 전제 오류와 테스트의 큐 포화 신호 추가 실패를 발견해 수정했습니다. 새 context 후보 읽기가 잘못된 선택 항목에서 메뉴 작성을 막던 문제도 기존 오류 복구 검사로 찾아 격리했습니다. 초기 환경의 WAR 부재·GUI 초기화·브라우저 Unix 소켓 제한은 제품 검사 성공으로 합산하지 않습니다.

## 0.25.4 릴리스 시점의 남은 확인 범위

- 실제 사용자의 Burp GUI에서 HTTP history 우클릭 **Add to scope**를 누르는 전체 흐름.
- Scope JSON을 내보내지 않는 버전의 관측 전 URL/고급 정규식 규칙 전체 열거.
- 다시간 수집 및 실제 디스크 고갈. 제한된 시간의 수집 검사와 구분합니다.
- Pro Collaborator와 외부 BApp은 별도 설치·권한이 있어야 실행할 수 있습니다.
- Windows PowerShell 스크립트의 실제 실행은 별도 확인 범위입니다.

## GitHub 실제 실행 결과

구현·배포 자료 기준 커밋 `0a46c418f4c3e6ac05742e728ad18bba3c1d69b2`에서 [Build and verify](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/38672861876), [Feature Mapper verification](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/38672861849), [Docker 전체 스택](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/38672857576)이 모두 성공했습니다.

Docker Engine 28.0.4 / Compose 2.38.2에서 Oracle HTTP 54개, Oracle health·Nginx·stub_status·실습 manifest·소스맵과 호스트 네 포트의 127.0.0.1 게시, DB/WAS 비공개, TLS HTTP/1.1·HTTP/2 및 CSD 두 엔드포인트의 기본 응답을 확인했습니다. 이 검사가 외부 Smuggler의 desync 탐지를 실행한 것은 아닙니다. 검증 뒤 이 job의 컨테이너와 DB 볼륨을 제거했습니다.

실제 Chromium 검사는 보조 확장 8개와 production Mapper/Bridge fixture 5개를 통과했습니다. 해당 브라우저 fixture는 Burp 인스턴스가 아니며, 실제 Burp 12개 검사는 별도로 실행했습니다. 26쪽 PDF의 실습 3.5.0 설명은 3.5.1에도 적용되며 이번 실습 변경은 네트워크와 버전 정보입니다.

이후 문서 기록 커밋과 병합 커밋의 최종 상태는 [PR #17](https://github.com/ohjun1998/burp-workbench-kr/pull/17) 및 [main Actions](https://github.com/ohjun1998/burp-workbench-kr/actions?query=branch%3Amain)를 기준으로 확인합니다.


## 2026-10-15 추가 잔여 검증

위 미확인 목록은 최초 릴리스 시점입니다. 추가 수집 내구성·디스크 고갈·Windows 검증의 현재 결과는 [별도 기록](VERIFICATION_DURABILITY_2026-10-15.json)과 [현재 상태](../PROJECT_STATUS_KR.md)를 우선합니다. 운영 소스와 0.25.4 배포 파일을 바꾸지 않고 검증 하네스·workflow·결과·작업 대화를 추가합니다.

새 ENOSPC 검사는 독립 16 MiB tmpfs의 실제 용량 부족을 사용합니다. Montoya 호스트는 어댑터이며 실제 운영 수집 콜백·Swing 타이머·DiskTrafficStore를 실행합니다. 부분 쓰기 실패의 롤백, 기존 원본의 정확한 복원, 공간 확보 후 재개와 unload 정리를 확인합니다. Burp native 호스트의 디스크 고갈 검사나 GUI 클릭으로 표현하지 않습니다.

Windows에서는 JDK 17 Java 회귀와 수집을 실행하고 Windows PowerShell 5.1 파서 및 start.ps1의 성공·실패 분기를 확인합니다. Docker·브라우저 명령은 제어된 어댑터이며 Docker Desktop 전체 실행은 남아 있습니다. 새 workflow의 7200·10800초 수동 실행 옵션은 다시간 어댑터 검사 경로이며 실행하지 않은 시간을 완료로 집계하지 않습니다.

추가 Windows 실행에서 BOM 없는 한글 `start.ps1`의 파싱 실패가 확인되어 실습 3.5.2로 수정했습니다. 기존 확장 0.25.4와 Docker 3.5.1 다운로드는 보존합니다. Windows의 기존 페이로드 UI 테스트는 worker 종료 후 결과 행 게시까지 기다리는 독립 테스트 복사본으로 실행하며 기존 assertion과 10초 제한을 유지합니다.
