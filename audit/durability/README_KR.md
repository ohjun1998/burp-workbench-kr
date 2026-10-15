# 수집 내구성과 Windows 잔여 검증

0.25.4 구현은 main PR #17에 반영되어 있습니다. 이번 작업은 확장 0.25.4의 운영 소스·배포 파일을 유지하면서 남은 검증을 추가합니다. Windows에서 발견한 실습 시작 스크립트 인코딩 문제는 실습 3.5.2로 수정합니다.

- 실제 수집 콜백·Swing 타이머·DiskTrafficStore를 실행하는 Montoya 어댑터 하네스.
- 독립 16 MiB tmpfs를 채워 실제 ENOSPC, 부분 기록 롤백, 기존 원본 보존, 공간 확보 후 수집 재개와 unload 정리 확인.
- Windows JDK 17의 전체 Java 회귀 묶음 및 3분 수집.
- Windows PowerShell 5.1 파서와 실제 start.ps1의 성공·실패 분기. Docker·브라우저 명령은 제어된 어댑터이므로 Docker Desktop 전체 구동으로 집계하지 않음.
- 수동 실행에서 2시간·3시간 어댑터 수집을 선택 가능.

로컬 사전 확인: 15초 524건, drop·processing error 0, 정확한 바이트 복원과 unload 정리 통과. 첫 하네스 실행의 누락된 messageId 어댑터를 수정해 재검사했습니다. GitHub Windows·ENOSPC 결과는 실행 후 기록합니다. 기존 0.25.4 검증과 새 검증을 구분하고 날짜별 작업 대화를 포함합니다.

실제 Burp GUI 클릭·다시간 native Burp·Pro Collaborator·외부 BApp과 Windows Docker Desktop 전체 구동은 완료로 표시하지 않습니다.


## Windows에서 발견한 두 문제

- Windows PowerShell 5.1은 BOM 없는 한글 `start.ps1`을 시스템 코드 페이지로 읽어 파싱에 실패했습니다. 운영 시작 스크립트를 UTF-8 BOM으로 저장하고 실습 버전·새 Docker ZIP을 3.5.2로 올렸습니다. 3.5.1 다운로드는 보존합니다.
- 기존 `ExtenderTest`는 `running=false` 뒤 지연 게시되는 SwingWorker 결과 행을 기다리지 않아 Windows 한 실행에서 실패했습니다. `run_windows_regression.py`는 독립 복사본에서 해당 대기 조건만 worker 종료와 두 행의 게시 완료로 보완합니다. 10초 제한과 기존 assertion 전체를 유지하며 원래 소스 파일은 수정하지 않습니다. 그 복사본으로 전체 Java 41묶음·페이로드 HTTP 검사를 실행한 뒤 실제 운영 클래스와 API 어댑터로 Windows 수집을 검사합니다.

Linux job의 `run_release.py`는 기존 다운로드를 잠시 별도 폴더에 보관한 격리 checkout에서 **python dev.py release**를 실행합니다. 생성한 새 Docker ZIP이 커밋한 3.5.2와 일치하는지 확인하고, 보관했던 모든 다운로드 바이트를 복원·비교합니다. 기존 버전 파일을 덮어써 배포하지 않습니다.

최종 원격 결과는 [별도 검증 기록](../../docs/VERIFICATION_DURABILITY_2026-10-15.json)을 확인하세요.
