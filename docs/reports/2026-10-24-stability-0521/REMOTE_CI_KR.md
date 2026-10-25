# 0.52.1 최종 원격 검증 · 2026-10-25

검증 소스: `e4b75694418091f51663557e766f002eeeb0fe17` · [PR #88](https://github.com/ohjun1998/burp-workbench-kr/pull/88). 2026-10-24의 로컬 보고서에 이어 2026-10-25(Asia/Seoul)에 원격 실행을 완료했습니다. 아래 세 워크플로는 모두 **첫 실행에서 성공**했습니다. 과거 0.52.0 실패 기록과 구분합니다.

| 필수 검사 | 결과·범위 | 실행 |
|---|---|---|
| Build and verify | 95개 Java 그룹 3,222개 + HTTP 504개 = 3,726개. 인증 조합 42회·50개 업무 1,400회 비교, 전체/세부 Swing 배치, 패키징 불변 조건과 다운로드 지문 확인 성공 | [40164444583](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40164444583) |
| Feature Mapper verification | Java 136개·Node 9개, 30개 화면, 배포 바이트 일치. 실제 Chrome 154.0.8037.57에서 MV3 설치·연결·클릭 전달·기능 매핑 및 본문 근거 확인 성공 | [40164444579](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40164444579) |
| Capture durability and Windows verification | Windows 전체 회귀·네이티브 Swing 창 제어 검사 6개·저장/복구 두 그룹 10회 반복·Mapper·180초 수집·PowerShell 제어 명령 6개 성공. Linux 새 출력 release·실제 ENOSPC·180초 수집 성공 | [40164444580](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40164444580) |

이번에 수정한 `TrafficIncrementalSearchTest` 47개·`ResponseBudgetTest` 30개가 Windows에서도 통과했습니다. 실제 브라우저는 POST raw SHA-256 일치를 유지하고, PATCH는 본문 미관측과 중간 확신을 명시한 유일 상관관계로 확인했습니다. 모호한 PATCH와 POST 해시 불일치는 별도 회귀에서 연결을 거부/철회합니다. 새 권한이나 페이지 요청 가로채기를 추가하지 않았습니다.

| 연속 수집 · 요청 180초 | 수신·보존 | 유실 | 처리 오류 |
|---|---:|---:|---:|
| Linux | 3,619 | 0 | 0 |
| Windows Server 2025 | 3,653 | 0 | 0 |

16MiB tmpfs에 실제 디스크 부족을 발생시킨 검사는 의도된 수집 실패 2건과 Mapper를 포함한 오류 집계 8건을 정확히 구분했고, 부분 쓰기 롤백·기존 원문 보존·공간 확보 후 수집/Mapper 저장 재개를 확인했습니다. 이 의도된 실패 수치를 정상 연속 수집의 0건과 합치지 않습니다.

계정 비교는 격리된 실습 사이트와 실제 비교 제어기의 API 어댑터로 수행했습니다. 권한/조회 설정을 적용한 2,100개 계정 셀에서 TP 392·FP 0·TN 1,708·FN 0, 확인 필요 91개가 유지됐습니다. 미설정 분기의 FN 392개도 예상대로 기록했습니다. 이는 설정 누락을 자동으로 보충하거나 실제 사이트의 정확도를 보장한 결과가 아닙니다. 반복된 플랫폼 실행과 저장 재검사를 고유 검사 수에 더하지 않습니다.

[전체 작업/단계/측정 JSON](remote-ci.json) · [브라우저 발췌](remote-browser-excerpt.txt) · [Windows 발췌](remote-windows-excerpt.txt) · [Linux 발췌](remote-linux-excerpt.txt) · [기본 CI 발췌](remote-check-excerpt.txt)

다섯 종료조건(재현 결함 해결, 기본/Windows 필수 검사, 실제 브라우저, 핵심 자료 흐름, 배포 지문·기존 버전 보존)을 충족했습니다. 0.52.1 안정화 범위를 마무리하고 새 기능 제안은 다음 버전에서 관리합니다. 후속 문서 커밋은 검증한 소스·빌드 스크립트·배포 파일을 변경하지 않습니다. main은 아직 0.52.0이며 이 PR의 병합은 수행하지 않았습니다.

3분 수집은 장시간 최대 처리량 보장이 아닙니다. Windows 네이티브 Swing 창 검사는 실제 Burp GUI·DPI/IME 검사가 아니며, PowerShell 검사는 제어 명령 어댑터로 수행해 Docker/Oracle/Nginx 백엔드를 기동하지 않았습니다. Edge 수동 조작·외부 운영 사이트·외부 BApp/OAST·장시간 native Burp 부하는 미실행입니다.
