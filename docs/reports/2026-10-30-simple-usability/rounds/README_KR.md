# 전체 8개 도구를 확인한 5라운드 실행 증거

이 폴더는 **전체 8개 도구 실행 → 한 가지 누적 개선 적용·빌드 → 전체 8개 도구 재실행**을 5회 수행한 기록입니다. 앞서 수행한 5개 집중 개선 사례는 사전 조사이며, 이 5라운드의 전체 실행 횟수에 포함하지 않습니다. 각 라운드의 집중 검사도 전체 기능 실행을 대신하지 않습니다.

원본은 `/workspace/usability-five-rounds/`입니다. `execution-v1/`와 `changes/`의 상대 구조를 유지해 복사했습니다. 실행 시간은 2026-10-29 20:31:05–20:36:01 UTC이며, 한국 시간으로는 **2026-10-30 05:31:05–05:36:01**입니다. 준비 기록이나 controller의 과거 내용을 사후 수정하지 않았습니다.

## 실제로 실행한 범위

각 라운드의 순서는 `before-whole → before-focus → 적용 → 빌드 → after-focus → after-whole`입니다. 전체 기능은 독립된 실행 **10회**, 집중 검사는 **10회**입니다. 앞 라운드의 after 결과를 다음 라운드의 before 결과로 재사용하지 않았습니다. 같은 누적 제품을 다시 실행하므로 제품 SHA는 연결되지만 실행 시간·결과 파일은 별개입니다.

전체 실행 10회 모두 아래 8개 도구의 실제 패널 동작·검증을 포함하며, 각 실행에서 **115개 검사 통과·실패 0개**를 기록했습니다. 반복한 검사를 고유 검사 수로 합산하지 않습니다. 원본 LOOP 3.10.0의 `http://127.0.0.1:18888`에 실행당 engine HTTP 42회와 로그인 준비 POST 2회를 보냈습니다. 실제 Burp 설치 화면, OS 마우스 클릭·파일 선택기, 브라우저 DOM 조작을 수행한 기록은 아닙니다.

| 도구 | 결과의 module ID |
|---|---|
| 요청·응답 목록 | `traffic` |
| 기능 자동 매핑 | `mapper` |
| 계정 권한 비교 | `auth` |
| JS에서 API 찾기 | `js` |
| 서버·경로 탐색 | `environment` |
| 응답 보안 분석 | `response.recon` |
| 입력값 보안 검사 | `payload` |
| 재현·증거 정리 | `bounty` |

전체 실행 10회의 하네스 SHA-256은 모두 `f3cfa5f91fd2435e0446d1e2da96919537df1eef706231b96821dc711f05aed4`입니다. adapter 클래스 트리 SHA도 동일합니다. 실행 metadata에 제품·하네스·adapter·의존성 해시, strict Java 17, 격리 런타임 종료와 입력 파일 불변 여부가 남아 있습니다.

| 라운드 | 제품 연결 | 집중 확인한 개선 |
|---|---|---|
| 1 | A0 → A1 | 동일 상세 갱신에서 읽기·복사 위치 유지 |
| 2 | A1 → A2 | 대상 변경 완료와 해당 계정 선택 |
| 3 | A2 → A3 | 달라진 통합점검 표만 일괄 갱신 |
| 4 | A3 → A4 | URL 후보 가져오기와 필터 일괄 처리 |
| 5 | A4 → A5 | 명시한 대시보드 결과를 보이게 이동 |

제품 6개의 SHA와 변경 파일 해시 연결은 [source-provenance.json](source-provenance.json)에 정리했습니다. 이 파일은 보관 시점에 원본들을 대조한 **새 설명 자료**이며, 원래 실행 출력이나 전체 소스 트리를 증명하는 별도 빌드 증명서가 아닙니다.

## 준비 manifest와 실제 적용 기록의 차이

`changes/round-N/manifest.json`의 `preparedFromPreliminaryInvestigation:true`와 `applied:false`는 **개선 파일을 준비했을 당시의 기록**입니다. 이 값을 그대로 보존했습니다. 개선안은 사전 조사에서 준비했고, 교정한 5라운드에서 기준 제품부터 순서대로 적용·빌드·재검증했습니다. 5라운드 실행 중 처음 개선안을 발굴했다고 주장하지 않습니다.

실제 적용 여부는 다음 자료를 함께 확인합니다.

- `execution-v1/round-N/apply.log`: 해당 라운드 적용과 변경 파일 목록
- `execution-v1/round-N/build.log`: 적용 이후 빌드 출력
- 같은 라운드의 네 실행 `metadata.json`: 실제 제품 SHA와 실행 완료 상태
- `round-summary.json`, `execution-v1/state.json`: 5라운드·독립 전체 실행 10회 완료
- `staging.json`: 마지막 적용이 5회차이고 다음 순서가 6인 최종 staging 상태

`round-summary.json`의 `appliedPatch`는 검토할 diff 경로입니다. 실제 [apply_round.py](controller/apply_round.py)는 patch 명령을 실행하지 않았습니다. 이전 파일과 준비 파일의 SHA를 검사한 뒤, 준비한 전체 파일 바이트를 staging으로 복사했습니다. 이 보관본에는 중복된 전체 Java 파일 대신 manifest와 diff만 포함합니다.

[prepared-source-provenance.json](prepared-source-provenance.json)은 2·3회차의 재구성 소스가 앞선 사전 조사 후보와 일치했다는 제한된 기록입니다. 모든 소스·의존성의 provenance로 확대해서 읽지 않습니다. 중간 누적 제품마다 별도 Git 커밋을 만든 것은 아니며, metadata의 `repositoryHeadAtLaunch`만으로 실행 JAR의 전체 소스를 증명하지 않습니다.

## 보관한 파일과 제외한 파일

원본 81개를 바이트 그대로 복사했습니다: 실행 metadata 20개, 결과 JSON 28개, 라운드 요약 5개, 적용·빌드 로그 10개, state·staging·준비 provenance 각 1개, manifest·diff 5쌍, controller 2개, controller 완료 로그 1개, 합성 화면 2개입니다. 여기에 이 README, 설명용 source provenance와 [ARCHIVE_INDEX.json](ARCHIVE_INDEX.json)을 새로 작성했습니다. 총 **84개 파일**입니다. index에는 복사한 각 파일의 원본 상대 경로·바이트 수·SHA-256이 있습니다.

숨긴 런타임, 제품 JAR·네이티브 바이너리, 의존성·클래스·adapter, 원본 패킷 저장소, 프로젝트·원문 보고서 ZIP과 중복된 전체 Java 파일은 포함하지 않았습니다. probe/launcher 로그와 대부분의 화면도 제외했습니다. 따라서 metadata의 `resultFiles`나 실행 명령이 가리키는 모든 파일이 이 보관본에 있는 것은 아닙니다. 실행 당시 metadata는 생략 목록에 맞춰 다시 쓰지 않았습니다.

결과 JSON·diff·적용/빌드 로그와 controller를 비밀값·원문 HTTP 관점에서 검토했고, 내용을 가리지 않고 복사했습니다. Whole 결과의 transport는 메서드·가린 URL·상태·계정·시간 등의 요약이며 쿼리는 `[query omitted]`로 남습니다. 패턴 검토가 모든 비밀값의 부재를 보장한다는 의미는 아닙니다. 합성 화면 두 장도 직접 확인했습니다.

| 5회차 1024×720 light, API 카드 클릭 직후 | 화면 |
|---|---|
| 개선 전: 목록을 보려면 추가 이동 필요 | [before PNG](execution-v1/round-5/before-focus/results/light-1024x720/01-api-card-after-click.png) |
| 개선 후: 목록·근거가 보이도록 이동 | [after PNG](execution-v1/round-5/after-focus/results/light-1024x720/01-api-card-after-click.png) |

화면은 합성 fixture를 Swing으로 렌더링한 이미지이며 실제 Burp 캡처가 아닙니다. 집중 검사의 EDT 시간은 해당 동작 구간의 측정값입니다. 스레드 할당량은 RSS·잔존 heap이 아니며, 명시한 추가 이동 횟수를 실제 OS 휠·클릭 횟수로 해석하지 않습니다.

## controller 보존과 재현 인터페이스

[run_rounds.py](controller/run_rounds.py)와 [apply_round.py](controller/apply_round.py)는 당시 controller 원문입니다. SHA-256은 각각 다음과 같습니다.

- `run_rounds.py`: `ffa15033f55f28962768625756bb1adff7eedc3edd463aa4a66ef1fe2d714de1`
- `apply_round.py`: `5ff0121c5739affc4b4f20be21d00a82ca2091cb74758a30d7c0aa2a052d272b`

이들은 당시 `/workspace/burp-workbench-kr`와 JDK 경로, `staging/`, 준비된 전체 파일과 고정 최종 제품 SHA에 의존합니다. 복사된 위치에서는 `ROOT`도 달라지므로 **역사 확인용 참조 소스**입니다. 이 폴더에서 controller를 실행하는 것을 재현 방법으로 안내하지 않습니다.

이식 가능한 실행 인터페이스는 [audit/simple-usability/run_probes.py](../../../../audit/simple-usability/run_probes.py)이며, 준비 사항·fixture 범위는 [audit README](../../../../audit/simple-usability/README_KR.md)에 있습니다. 준비한 JAR와 고정된 adapter 클래스 복사본, JDK 17을 사용하고, 실행마다 새 출력 경로를 지정합니다. 원본 LOOP 서버를 18888에서 먼저 준비해야 하며 runner는 서버를 시작하지 않습니다.

```sh
python audit/simple-usability/run_probes.py --probe whole \
  --jar <검증할-JAR> --out <새-출력-경로> \
  --java <JDK17>/bin/java --adapters <고정-adapter-클래스-경로> \
  --phase round-1-before
```

집중 검사는 해당 라운드에 맞춰 `reading`, `target`, `tables`, `urls`, `dashboard`를 지정합니다. 같은 source SHA와 같은 adapter를 유지하고, 적용·빌드 전후의 실제 제품 SHA를 기록해야 이 보관본과 같은 기준으로 비교할 수 있습니다. 여기에는 재배포용 중간 제품 JAR를 중복 보관하지 않았습니다.
