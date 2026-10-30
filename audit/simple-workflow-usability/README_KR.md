# 반복 작업·설정·복구 검증 재현

게시된 Simple1.4.1과 후속1.4.2를 같은 고정 프로브로 비교합니다. [사용자 목표와 수행 계획](../../docs/reports/2026-10-30-simple-workflow-goals/PLAN_KR.md), [최종 실제 결과](../../docs/reports/2026-10-30-simple-workflow-goals/REPORT_KR.md)를 함께 읽습니다. 이전 `audit/simple-usability`의 프로브·실행기·기록은 변경하지 않습니다.

| 프로브 | 같은 작업에서 기록하는 것 |
|---|---|
| environment | 관측 대상과 계정 선택→경로 선택→직접 GET. 필요한 컨트롤 동작·별도 설정 창 진입, 실제 격리 loopback 응답의 원본 보존, 수신 중 읽기 선택, 표·문서 이벤트와 EDT 비용 |
| passive | 잘못된 대상·8탭 상한 오류를 같은 대상 창에서 확인·교정·재시도. Enter/버튼 준비, 아직 적용하지 않은 초안과 현재 원본 보존, 종료 뒤 남아 있던 동작 차단 |
| readiness | 검색 조건 준비를 latch로 잠시 보류하고 완료 상태를 교차 스레드에서 확인. 작업자 계산과 최종 화면 반영, 정확한 새 원본 선택, HTTP0 |

완료 상태를 1,000번 읽는 것은 **차단한 준비 구간 하나의 표본**이며 독립적인 1,000개 시험이나 사용자 조작 수가 아닙니다. 최초 새 whole 실행의 실패와 준비 상태 경합의 결정적 재현은 별도 근거입니다. 결정적 재현만으로 과거 실패의 인과관계를 확정하지 않습니다.

## 실행

JDK17과 기존 제품 JAR, 컴파일한 테스트 어댑터가 필요합니다. 현재 소스의 어댑터는 `python simple/dev.py test --group SimpleEnvironmentWorkflowTest --skip-payload` 등으로 준비할 수 있습니다. 실행기는 제품을 빌드하지 않습니다. 원본의 기본 Java나 사용자의 Burp·브라우저·proxy·HOME 설정을 바꾸지 않습니다.

```sh
python audit/simple-workflow-usability/run_probes.py \
  --probe environment \
  --jar /path/to/burp-workbench-simple-1.4.1.jar \
  --adapters /path/to/frozen-test-adapters \
  --java /path/to/jdk17/bin/java \
  --out /tmp/environment-before-001 --phase before
```

`--probe passive`와 `--probe readiness`도 같은 형식입니다. `--out`은 존재하지 않는 새 경로여야 합니다. 비교 후 실행은 새 제품 JAR와 새 출력 경로, `--phase after`를 사용합니다. 전후 비교에는 **동일한 프로브 SHA·어댑터 tree SHA·의존성 SHA·Java17**을 사용합니다. 예를 들어 before 출력의 `.adapters`를 after의 `--adapters`로 지정하면 공유 빌드를 다시 했어도 비교 어댑터를 고정할 수 있습니다. 환경 성능 프로브는 다른 빌드·검사와 겹치지 않게 순차 실행합니다.

`environment`는 임시 loopback 서버의 실제 HTTP 응답을 Montoya 어댑터로 전달합니다. `passive`와 `readiness`는 합성 원본·어댑터를 사용하며 HTTP를 보내지 않습니다. 별도로 준비한 원본 LOOP3.10.0에서 전체 8개 도구를 확인할 때는 기존 동결 실행기의 `--probe whole`을 사용합니다. 같은 프로그램을 반복했다고 새 개선 라운드나 고유 검사가 늘어난 것으로 합산하지 않습니다.

## 증거와 범위

실행기는 게시 제품·프로브·테스트 클래스·의존성의 개인 복사본을 만들고 제품 JAR를 클래스패스 맨 앞에 놓습니다. 테스트 어댑터의 제품 코드가 운영 클래스를 대신하지 않게 합니다. `metadata.json`은 실제 제품·프로브·실행기·어댑터·의존성 해시, Java 명령, 사후 불변 여부와 임시 공간 삭제 결과를 기록합니다. 기존 실행기의 스냅샷·텍스트 검토 유틸리티만 가져오며, 그 파일이 고정 SHA와 일치하는지 **실행 전에** 확인합니다.

`.product.jar`, `.sources`, `.adapters`, `.deps`, `.classes`는 해당 실행의 고정 입력입니다. 원문 결과 ZIP·패킷·인증 설정은 별도 임시 홈·prefs·tmp·작업 경로에서만 사용하고 정리합니다. 공개 결과로는 JSON과 문자열 점검을 통과한 로그만 승격합니다. 문자열 검사는 휴리스틱이므로 공유 전 직접 검토해야 합니다. 임시 파일 삭제·입력 불변·출력 검토 실패는 metadata에서 실패로 표시합니다.

프로브 실행의 `completed`는 관측이 완료됐다는 뜻입니다. before에서 기록한 결함 조건을 통과로 바꾸지 않습니다. JSON의 실제 조건과 전체 흐름의 `failed`·모듈별 결과를 직접 확인합니다. 최초 실패, 준비 과정의 실행과 최종 고정 프로토콜 실행을 구분해 보존합니다.

조작 수는 이미 입력한 초안에서 컨트롤을 작동시킨 횟수이며 텍스트 입력·선택적인 창 닫기·OS 클릭 수를 추정하지 않습니다. EDT 시간은 로컬 진단값이며 화면 표시 완료·사람의 작업 시간·실제 Burp 지연을 뜻하지 않습니다. `ThreadMXBean`의 EDT 할당량은 잔존 heap·RSS·최고 메모리와 다릅니다. native Burp GUI·Windows DPI/IME·브라우저 DOM·MV3·OS 파일 선택기는 이 프로브의 검증 범위에 포함하지 않습니다.
