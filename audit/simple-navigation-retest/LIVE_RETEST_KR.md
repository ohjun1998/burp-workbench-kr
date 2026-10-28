# Simple 1.3.1 → 1.3.2 새 원본 앱 재시험

이전 실행 수치를 재사용하지 않고 보존한 Simple 1.3.1과 새 1.3.2를 현재 같은 앱·계정 조건에서 비교합니다. 원본 7개 앱·archive·소스·Docker image 고정값과 재현 차이는 [이전 준비 설명](../simple-retest/README_KR.md)에 보존합니다. 기존 앱 준비 metadata의 소스 검사 수는 당시 setup 근거이며 이번에 다시 수행한 검사 수에 더하지 않습니다.

## 현재 실행 준비

원본 앱은 `/workspace/simple-sites`의 보존된 소스·배포·DB와 기존 전용 Docker 컨테이너 3개를 재사용합니다. 별도 실행 폴더 `/workspace/simple-navigation-retest-runtime`에 새 로그·프로세스 기록·인증·config·결과를 저장하며 이전 config·1.3.0/1.3.1 결과·원문 보고서를 덮어쓰지 않습니다. 사용자의 전역 Burp·브라우저·프록시 설정은 변경하지 않습니다.

7개 앱의 HTTP 루트 200을 새로 확인했습니다. Java 앱의 기존 internal Docker network와 loopback relay, Juice Shop의 외부연결 차단 preload, VulnBank의 loopback PostgreSQL 실행 조건을 유지합니다. OopsSec·DVBank는 원본 앱과 기존 외부 실행기를 사용합니다. 시작·로그인·상태 확인 요청은 아래 엔진 전송 수에 포함하지 않습니다.

LOOP·VulnerableApp의 휘발성 H2는 원본 seed에서 재생성됩니다. LOOP의 이전 메모리 cookie는 401이어서 정상 로그인으로 갱신했습니다. Juice Shop은 재시작 시 이전 임시 계정이 사라져 이전 테스트 계정으로 정상 등록·로그인했으며 직접 대조의 basket ID도 새 로그인 계정의 실제 basket으로 맞췄습니다. 다른 앱도 정상 seed 로그인으로 인증을 갱신하고 기대 신원 JSON 값과 HTTP 200을 대조했습니다. 원문 실행 인증값은 공개 Git에 넣지 않습니다.

## 실제 실행

각 버전은 새 JVM·새 원본 저장소·별도 결과 폴더를 사용합니다. 루트 자동 발견과 보호 API 직접 seed 대조를 구분합니다. 아래 기존 하네스는 버전별 JAR를 입력받아 실행하므로 수정하지 않고 재사용합니다.

```sh
python audit/simple-retest/run_site.py \
  --jar simple/downloads/1.3.1/burp-workbench-simple-1.3.1.jar \
  --config /workspace/simple-navigation-retest-runtime/configs/juice-shop-root.json \
  --out /workspace/simple-navigation-retest-runtime/results/1.3.1/juice-shop/root \
  --java /workspace/tools/jdk17/bin/java \
  --katana /workspace/tools/katana-installer-check/1.8.0/katana
```

7개 앱 × 두 시나리오 × 두 버전은 28회입니다. 같은 config 바이트·계정 인증·Scope·200회 공통 요청 예산·깊이2·Katana 30초/50페이지·감사 하네스105초 조건을 두 버전에 적용합니다. 익명만 제공하는 BenchmarkJava·VulnerableApp은 `direct`로 기록하며 나머지는 `protected`입니다. 새 코드가 신원 재대조 등 추가 읽기 요청을 수행하면 그 요청도 동일한 실제 전송·예산에 포함합니다. 전송 수 자체가 동일해야 한다는 뜻은 아닙니다.

최초 1.3.2 후보의 진행 중 화면 검수에서 helper 버전 표시 문자열 두 곳을 정정했습니다. 그 후보의 완료 8회와 중간 응답은 `setup-attempts/display-candidate-a92dc92e`에 보존하고 최종 집계에서 제외했으며, 고정한 새 최종 JAR로 수정 후 14회를 처음부터 다시 실행합니다. 개발 서버 캐시·응답 시각·비동기 대기열 순서는 실행마다 달라질 수 있으므로 전송 수를 정확도나 성능 점수로 해석하지 않습니다.

최종 JAR의 첫 OopsSec 루트 실행은 신원 확인 URL의 실제 HTTP 500에서 일시정지했습니다. 원본 Next 개발 서버 로그에는 해당 `/api/user`의 JSON 파싱 오류와 webpack 오류가 있었고, 같은 고정 인증정보로 나중에 신원 200과 기대값 일치를 확인했습니다. 정확한 부하·오류 인과관계는 확정하지 않았습니다. 첫 Oops 네 실행(before/after × 두 시나리오)은 `retry-exclusions/oops-transient-development-500`에 보존하고, 동일 config로 두 버전·두 시나리오의 새 paired 세트를 한 번만 순차 실행합니다. 이 재시도에서도 남는 실패·부분 결과는 더 반복하지 않고 그대로 기록합니다. 공개 `retry-observations.json`에 최초 상태·응답 지문·회복 확인·제한한 재시도와 결과를 남깁니다.

요약기는 두 버전의 고정 JAR SHA를 명시적으로 받고 28회 존재·strict Java17·실제 전송/예산/메서드·설정과 인증 헤더·버전별 원본 exporter·paired private config의 바이트 일치를 확인합니다. 새 실행이 끝난 뒤 다음처럼 실행합니다.

```sh
python audit/simple-navigation-retest/summarize_runs.py \
  --results /workspace/simple-navigation-retest-runtime/results \
  --metadata-root /workspace/simple-sites \
  --before 1.3.1 --after 1.3.2 \
  --before-sha <보존한1.3.1JAR_SHA256> --after-sha <고정한1.3.2JAR_SHA256> \
  --out audit/simple-navigation-retest/results-summary.json
```

## 실제로 확인한 추가 사례

새 1.3.1 기준 VulnBank 루트 탐색은 일반 user 계정으로 `/api/v1/merchants/me`의 별도 merchant 인증 401을 받으면서 전체 세션 확인 상태로 일시정지했습니다. 같은 고정 계정의 configured user 신원을 별도로 대조하면 **기대 신원 200 → merchant 401 → 기대 신원 200**이었습니다. 새 수정은 이처럼 유효한 일반 신원과 다른 인증 영역의 거부를 구별하되 실제 신원 만료·불일치·재인증 요구를 계속 구분해야 합니다. 전후 동작과 추가 신원 조회는 실제 새 실행 결과로 확인하며 과거의 “자동 중단 0개”를 이번 결과로 재사용하지 않습니다.

Benchmark의 요청 예산 도달, OopsSec의 감사 시간 한도, Juice Shop 일부 응답의 원본 Content-Length/EOF 오류는 부분·실패·미확인으로 남깁니다. HTTP 저장본은 Java HttpClient 수신 payload와 정규화한 헤더를 재구성한 자료이며 TCP 원시 프레이밍 캡처·실제 Burp GUI 검증이 아닙니다. 마지막에는 이번에 소유한 7개 앱·컨테이너 3개·relay만 종료하고 DB·원본·응답·보고서를 보존합니다.
