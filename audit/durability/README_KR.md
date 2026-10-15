# 수집 내구성과 Windows 잔여 검증

0.25.4 구현은 main PR #17에 반영되어 있습니다. 이번 작업은 운영 소스와 배포 버전을 바꾸지 않고 남아 있던 검증을 추가합니다.

- 실제 수집 콜백·Swing 타이머·DiskTrafficStore를 실행하는 Montoya 어댑터 하네스.
- 독립 16 MiB tmpfs를 채워 실제 ENOSPC, 부분 기록 롤백, 기존 원본 보존, 공간 확보 후 수집 재개와 unload 정리 확인.
- Windows JDK 17의 전체 Java 회귀 묶음 및 3분 수집.
- Windows PowerShell 5.1 파서와 실제 start.ps1의 성공·실패 분기. Docker·브라우저 명령은 제어된 어댑터이므로 Docker Desktop 전체 구동으로 집계하지 않음.
- 수동 실행에서 2시간·3시간 어댑터 수집을 선택 가능.

로컬 사전 확인: 15초 524건, drop·processing error 0, 정확한 바이트 복원과 unload 정리 통과. 첫 하네스 실행의 누락된 messageId 어댑터를 수정해 재검사했습니다. GitHub Windows·ENOSPC 결과는 실행 후 기록합니다. 기존 0.25.4 검증과 새 검증을 구분하고 날짜별 작업 대화를 포함합니다.

실제 Burp GUI 클릭·다시간 native Burp·Pro Collaborator·외부 BApp과 Windows Docker Desktop 전체 구동은 완료로 표시하지 않습니다.

