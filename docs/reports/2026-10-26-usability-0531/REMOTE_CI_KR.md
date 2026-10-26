# Workbench Next 0.53.1 최종 원격 검증과 배포 인계

- 확인일: 2026-10-26 (Asia/Seoul)
- 검증 소스: `5704f48a9d12282ec3f7ea48ec7a78065aa78695`
- 작업 브랜치: `account-a/usability-0531-20261026`
- 최종 실행: [GitHub Actions 40334451367](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40334451367)
- 워크플로: `.github/workflows/usability-release-candidate.yml`

최종 실행의 release·windows·layout·browser 작업이 모두 성공했습니다. 이 결과로 0.53.1 후보판 JAR·소스 ZIP과 Mapper 0.3.5 배포를 제공하며, `main`의 병합 완료 버전은 여전히 0.52.0입니다. 0.53.0 작업 이력과 기존 0.52.0·0.50.0·0.49.1 배포 파일을 보존합니다.

## 실제 실행 결과

| 작업 | 결과와 범위 |
|---|---|
| [Linux release](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40334451367/job/121612160864) | Java 17 대상 빌드. 회귀 104개 그룹·3,638개, payload HTTP 136개, 전체 기능 HTTP 102개, 모의 진단 HTTP 266개로 총 4,142개 통과. 배포 생성·다운로드 해시·소스 지문 및 패키징 단위 검사 7개 통과 |
| [Windows](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40334451367/job/121612161074) | 같은 회귀·격리 HTTP 묶음 4,142개 통과 |
| [Swing layout](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40334451367/job/121612160831) | 준비·결과·검색·남은 점검·재개 등 합성 Swing 흐름 상태 108개 통과. 누락·실패 없음 |
| [실제 Chromium](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40334451367/job/121612160214) | Mapper Java 134개·Node 15개 통과. 격리한 실제 Chromium의 도우미·production Mapper/Bridge fixture에서 20개 PASS 항목, 관측 POST/PATCH raw 본문 연결 포함 |

Linux와 Windows는 같은 검사 묶음의 플랫폼별 반복입니다. 4,142개를 두 번 실행했다고 고유 검사 8,284개로 표시하지 않습니다. 기존 로컬 집중 검사 674개, Mapper·화면의 중첩 하위 묶음도 이 합계에 더하지 않습니다. 이전 후보 소스의 실패·수정 경과와 과거 버전의 검사 결과는 최종 소스 결과와 구분합니다.

## 배포 파일과 무결성

최종 release 작업이 생성한 `usability0531-candidate-packages` 아티팩트(ID `12656112683`)의 파일을 그대로 사용합니다. 원본 ZIP SHA-256은 `1475bdc865b61ae31e15c8a9d8168b866c88e778ab2c7ca0131b92644ef9344a`입니다.

| 파일 | SHA-256 |
|---|---|
| [Workbench 0.53.1 JAR](../../../downloads/extensions/0.53.1/burp-workbench-kr-0.53.1.jar) | `f67d2baed0ca1e0b1972b200673cd8b5dce57543694d87e002daff8331d87fa0` |
| [Workbench 0.53.1 소스 ZIP](../../../downloads/extensions/0.53.1/burp-workbench-kr-0.53.1-source.zip) | `36e152a028c92dc037bba2330820f86d75c40080c00de774593d8bd30b55b59d` |
| [Mapper 0.3.5 JAR](../../../downloads/feature-mapper/0.3.5/feature-mapper-kr-0.3.5.jar) | `e1269ee34f3d32023c4c39857c1f71a87623682c10202ba2be1788d4f67db484` |
| [Mapper 0.3.5 소스 ZIP](../../../downloads/feature-mapper/0.3.5/feature-mapper-kr-0.3.5-source.zip) | `423bad65487f28d0b5b597a409f1c1af47c6bc7f2e11ef5b880566311e4bc0a2` |
| [브라우저 도우미 0.3.5 ZIP](../../../downloads/feature-mapper/0.3.5/feature-mapper-browser-0.3.5.zip) | `cb35335ebec9e1e447607b74e8084772f4a44c94b52760989328939cacdbdd56` |

업로드 마무리에서는 읽기 전용으로 원본 아티팩트·개별 파일 해시, 패키지 버전과 Java 17 바이트코드를 확인했습니다. 소스 ZIP의 Workbench 349개·Mapper 42개·도우미 8개 파일을 검증 커밋의 Git blob과 대조해 일치를 확인했습니다. [로컬 무결성 확인 JSON](LOCAL_ARTIFACT_INTEGRITY.json)에 결과를 남깁니다. 이 작업에서 기능 검사 전체를 새로 실행한 것으로 표시하지 않습니다.

후속 반영은 문서·대화 기록과 동일 CI 산출물입니다. 검증한 생산 소스·빌드 스크립트·테스트 입력을 변경하지 않으며, 중복 CI를 생략한 게시 커밋의 검증 근거는 위 소스 커밋과 최종 실행입니다. [검증 JSON](../../VERIFICATION_0.53.1.json)과 버전별 `SHA256SUMS`·`manifest.json`에서 파일 목록과 근거를 확인할 수 있습니다.

## 인계와 남은 범위

설치에는 [0.53.1 단계별 안내](../../RELEASE_0.53.1_NEXT_KR.md)와 [다운로드 목록](../../../downloads/README.md)을 사용합니다. 기존 Next를 해제한 뒤 새 JAR를 로드하고, 브라우저 연동이 필요하면 같은 Mapper 0.3.5 도우미를 사용합니다. 내장 Mapper와 별도 Mapper JAR를 중복 로드할 필요는 없습니다.

HTTP 검사는 loopback fixture/API 어댑터이고 Swing 검사는 합성 데이터를 사용하는 자동 검사입니다. Chromium은 실제 브라우저 엔진으로 실행했지만 실제 Burp GUI 안의 연결을 검증한 것은 아닙니다. 실제 Burp GUI, Windows DPI/IME, Edge 수동 조작, Docker/Oracle/Nginx 전체 구동, 외부 대상 서비스와 장시간 native Burp 부하는 미실행입니다. 자동 검사 통과를 실제 서비스 탐지 성능이나 모든 운영 환경의 보증으로 일반화하지 않습니다.

이번 인계는 후보판의 자동 검증·배포 파일 제공이며 main 병합을 완료한 기록이 아닙니다.
