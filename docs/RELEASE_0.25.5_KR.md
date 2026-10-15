# Workbench 0.25.5 · 실습 3.5.2

빠르게 이어지는 Legacy 페이로드 요청에서 앞선 응답이 끝났어도 다음 요청이 사용 중이라는 오류로 거절될 수 있었습니다. FutureTask 결과 공개 전에 busy를 해제하고 각 작업이 한 번만 해제하도록 수정했습니다. 실제 실행 중인 취소·시간 초과 콜백은 계속 잠금 상태로 유지합니다.

- 수정 전 순차 전송 재현: 성공 459건 중 잘못된 busy 거절 5건.
- 새 회귀 검사: 5,000회 순차 전송, 완료 시 idle, 콜백 비중첩, 예외 후 해제·재개.
- 실습 3.5.2: 한글 start.ps1의 UTF-8 BOM으로 Windows PowerShell 5.1 파싱 오류 수정. API·이미지 버전도 함께 갱신.
- 수집 내구성: 실제 운영 수집 콜백·Swing 타이머·디스크 보관을 사용하는 별도 하네스와 독립 tmpfs ENOSPC 복구 검사, Windows 원본 회귀 및 PowerShell 검증 추가.

0.25.2 계보의 무제한 수집·임시 디스크 보관, ON/OFF/SCOPE·Scope 동기화와 Mapper는 유지합니다. 이전 확장·Docker 다운로드는 보존합니다. 화면·사용법은 0.25.4 PDF를 계속 참고할 수 있습니다.

[다운로드](../downloads/README.md) · [검증과 남은 범위](VERIFICATION_DURABILITY_2026-10-15.json) · [추가 인수인계](../audit/durability/README_KR.md)
