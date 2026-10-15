# 수집 내구성·디스크 고갈·Windows 검증

Windows 검증에서 확인한 문제를 Workbench 0.25.5와 실습 3.5.2로 수정합니다. 이전 버전의 배포 파일은 보존합니다.

## 실제 수정

- `PayloadHttp`의 FutureTask는 결과가 다른 스레드에 전달된 뒤 run-finally에서 busy를 해제했습니다. 호출자가 그 사이 다음 요청을 보내면 완료된 요청을 아직 사용 중이라고 판단했습니다. 콜백의 반환/실패 처리에서 먼저 해제하고 요청마다 한 번만 해제해 다음 요청의 busy를 이전 작업이 지우지 않도록 수정했습니다.
- 수정 전 독립 순차 전송 재현에서 성공 459건 중 잘못된 busy 거절 5건을 확인했습니다. 5,000회 연속 전송·완료 시 idle·콜백 비중첩·예외 후 해제/재개를 회귀 검사에 추가했습니다. 기존 취소·시간 초과 중 실제 실행 중인 콜백의 잠금 검사는 유지합니다.
- 처음에는 UI 대기를 의심해 별도 테스트 복사본을 사용했으나 그것으로 해결되지 않았습니다. 임시 복사본 방식은 제거하고 Windows에서도 원본 테스트 전체를 실행합니다. 기존 assertion을 줄이지 않습니다.
- 한글이 있는 실습 `start.ps1`은 UTF-8 BOM을 포함해 Windows PowerShell 5.1에서 읽을 수 있도록 수정했습니다. 실습 버전·API 버전과 새 Docker ZIP은 3.5.2입니다.

## 검증 경로

- `run_capture.py`: Montoya 어댑터에서 실제 운영 수집 콜백·Swing 타이머·DiskTrafficStore 실행. 기본 3분이며 수동 workflow에서 2·3시간 선택 가능.
- 독립 16 MiB tmpfs의 실제 ENOSPC·부분 기록 롤백·기존 원본 보존·공간 확보 후 재개·unload 정리.
- Windows JDK 17의 원본 Java 회귀·페이로드 HTTP 검사, 3분 수집.
- Windows PowerShell 5.1 파서와 실제 start.ps1의 성공·실패 분기. Docker·브라우저 명령은 제어된 어댑터이며 Docker Desktop 전체 구동으로 집계하지 않습니다.
- `run_release.py`: 다운로드를 보존한 새 출력 폴더에서 `python dev.py release`와 무결성 검사. 새 Docker ZIP이 커밋한 3.5.2와 일치하는지 확인하고 원래 모든 다운로드 바이트 복원.

[현재 검증 기록](../../docs/VERIFICATION_DURABILITY_2026-10-15.json)을 확인하세요. 실제 Burp GUI·다시간 native 수집·Pro Collaborator·외부 BApp·Windows Docker Desktop 전체 구동은 별도 확인 범위입니다.
