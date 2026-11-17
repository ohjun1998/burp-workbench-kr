# Workbench Strix 1.0.0 · Strix 연동판 이름 분리

Strix 연동 Simple 1.4.13 후보를 Workbench Strix 1.0.0이라는 별도 제품명과 버전 체계로 구분합니다. 일반 Simple 1.4.22의 A 그룹형 변경을 포함한 통합판이 아닙니다.

| 항목 | 이전 연동판 | 새 연동판 |
| --- | --- | --- |
| 제품명 | Workbench Simple | Workbench Strix |
| 버전 | Simple 1.4.13 | Strix 1.0.0 |
| 설치 파일 | burp-workbench-simple-1.4.13.jar | burp-workbench-strix-1.0.0.jar |
| 배포 경로 | simple/downloads/1.4.13 | simple/downloads/strix/1.0.0 |
| 표시 | Simple 탭·메뉴·창 | Strix 탭·메뉴·창 |
| 데이터 호환 | simple.* / .burp-workbench-simple | 기존 경로·키 유지 |

기존 확장을 언로드하고 새 JAR를 로드합니다. Burp의 확장 목록에 `Burp Workbench Strix KR · Strix 진단 연동 (1.0.0)`, 탭과 메뉴에 `Workbench Strix`가 표시됩니다. 기본 엔진과 원본·SCOPE·모델 선택·실행 승인·중지 동작은 유지합니다.

[설치·소스·체크섬](../downloads/strix/1.0.0/) · [새 사용 가이드](../../docs/reports/2026-11-17-workbench-strix/guide/index.html) · [검증 기록](../../docs/reports/2026-11-17-workbench-strix/README_KR.md)

기존 Simple과 Strix1.4.13 배포 파일은 덮어쓰지 않습니다. 새 이름은 별도 설치 파일과 화면 식별을 위한 구분이며 데이터를 자동 분리하거나 이관하는 변경은 아닙니다. 이전 설정과 기록을 이어 쓰므로 같은 저장 공간을 쓰는 확장을 동시에 실행하지 않습니다.

최대 에이전트 턴은 HTTP 요청 예산이 아닙니다. 실제 진단은 사용자 실행 계획 확인 후 시작하며, 연결부의 SCOPE 검사는 전체 Strix 네트워크 통신 격리를 뜻하지 않습니다. 실제 모델·사용자 Burp 실행은 이번 이름 변경 검사 범위 밖입니다.
