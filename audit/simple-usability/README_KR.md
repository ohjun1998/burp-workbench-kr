# Simple 사용성 측정 재현

이 폴더는 개별 불편 사항의 **사전 조사 하네스**와 전체 Workbench 작업 흐름 검증을 보존합니다. 기존 다섯 개 개선 사례를 다섯 라운드로 계산하지 않습니다. 한 라운드는 **전체 8개 기능 흐름을 확인 → 개선 적용 → 전체 8개 흐름을 다시 확인**하는 작업입니다. 제품 JAR를 다시 빌드하거나 저장소의 빌드 결과를 변경하지 않습니다. 네이티브 Burp 화면, 운영체제 마우스·키보드 입력, 브라우저 DOM을 조작한 검증은 아닙니다.

`InspectionUpdateProbe.java`, `PassiveImportBenchmark.java`, `DashboardNavigationProbe.java`, `LoopWorkflowAudit.java`는 외부 작업 폴더의 원본을 바이트 그대로 복사했습니다. SHA-256과 당시 원본 경로는 [harnesses.json](harnesses.json)에 있습니다. 실행기는 복사본의 지문도 다시 확인합니다. 읽기·대상 변경 프로브는 저장소의 `ScopeInspectionReadingTest.java`, `ScopeInspectionTargetFlowTest.java`를 `--probe` 모드로 컴파일합니다.

`WholeWorkbenchWorkflowAudit.java`는 전체 흐름 라운드 검증을 위해 **새로 작성한 추가 하네스**입니다. 기존 외부 원본의 바이트 복사본이 아닙니다. 최종 소스를 `harnesses.json`의 `additional_new_harnesses`에 별도 SHA로 등록한 뒤 실행합니다. 등록 전이나 등록 지문과 달라진 상태에서는 `--probe whole` 실행을 거부합니다. 한 라운드 계열의 전후 실행에서는 같은 최종 하네스와 어댑터를 사용합니다.

## 준비

- Python 3.10 이상과 **JDK 17**이 필요합니다. 실행기는 `--release 17`로 하네스만 컴파일하고 생성 클래스의 major version 61을 확인합니다. JRE만 설치된 환경에는 컴파일러가 없어 실행할 수 없습니다.
- 검사할 제품 JAR, `simple/burp-workbench/deps/`의 기존 API·의존성 JAR, 이미 컴파일한 `simple/burp-workbench/build/tests/`가 필요합니다. 없으면 저장소의 정상 빌드·검증 절차로 먼저 준비하세요. 이 실행기는 제품이나 테스트 어댑터를 빌드하지 않습니다.
- `--java`를 생략하면 저장소 `.dev-tools/jdk-17*/bin/java`, 이어서 PATH의 Java를 찾습니다. Java 17이 아니면 실행을 중단합니다.
- `--out`은 **아직 존재하지 않는 새 경로**여야 합니다. 빈 폴더를 포함해 기존 경로는 덮어쓰지 않습니다. 전후 측정은 각각 새 경로를 지정합니다.
- 테스트 어댑터를 다른 곳에 보관했다면 `--adapters /path/to/compiled-tests`를 지정합니다. 실행 시작 시 `.class` 파일만 출력 폴더의 `.adapters/`로 고정 복사하며, 복사 도중 빌드 결과가 달라지면 재시도하거나 실패합니다.

## 실행

저장소 루트에서 실행하는 예입니다. 제품 JAR 경로와 JDK 경로는 검사할 파일에 맞춥니다. 아래의 `/tmp/...-001` 경로가 이미 있다면 새 번호를 사용하세요.

```sh
python audit/simple-usability/run_probes.py --probe reading \
  --jar simple/downloads/1.4.0/burp-workbench-simple-1.4.0.jar \
  --java /path/to/jdk-17/bin/java --out /tmp/simple-reading-001 --phase before

python audit/simple-usability/run_probes.py --probe target \
  --jar /path/to/product.jar --java /path/to/jdk-17/bin/java \
  --out /tmp/simple-target-001 --phase after

python audit/simple-usability/run_probes.py --probe tables \
  --jar /path/to/product.jar --java /path/to/jdk-17/bin/java \
  --out /tmp/simple-tables-001 --phase after

python audit/simple-usability/run_probes.py --probe urls \
  --jar /path/to/product.jar --java /path/to/jdk-17/bin/java \
  --out /tmp/simple-urls-001 --phase after

python audit/simple-usability/run_probes.py --probe dashboard \
  --jar /path/to/product.jar --java /path/to/jdk-17/bin/java \
  --out /tmp/simple-dashboard-001 --phase after
```

`loop`와 `whole`은 **원래 LOOP 3.10.0 실습 서버**를 `http://127.0.0.1:18888`에 별도로 준비한 뒤 실행합니다. 서버의 버전·기본 실습 계정이 맞는지 먼저 확인하세요. [기존 실습 안내](../../docs/LAB_3.10.0_KR.md)를 참고하되 이 하네스의 고정 주소·포트는 `18888`입니다. 실행기는 서버를 설치·시작·초기화하거나 포트를 바꾸지 않습니다. 포트 접속 확인은 버전 확인을 대신하지 않습니다.

```sh
python audit/simple-usability/run_probes.py --probe loop \
  --jar /path/to/product.jar --java /path/to/jdk-17/bin/java \
  --out /tmp/simple-loop-001 --phase after
```

`loop`를 선택하면 원본 하네스가 실습 계정의 정상 로그인 요청을 보내고, 제한된 SCOPE 조회·일시정지·재개·중지·원본·증거·보고서 흐름을 실행합니다. Katana, Docker 실행, 실제 파일 선택 창, 브라우저 확장 조작은 포함하지 않습니다. 원본 하네스의 `sourceCommit` 고정값은 작성 당시 문맥이며 실행 제품의 커밋을 증명하지 않습니다. **실제 실행 제품은 `metadata.json`의 제품 JAR SHA-256으로 구분**합니다. 저장소 HEAD 기록도 JAR와 소스가 일치한다는 주장이 아닙니다.

## 전체 흐름 5라운드

`whole`은 요청·응답(`traffic`), 기능 매핑(`mapper`), 계정 비교(`auth`), API 찾기(`js`), 서버·경로(`environment`), 응답 분석(`response.recon`), 입력값 검사(`payload`), 재현·증거(`bounty`)의 8개 운영 화면 흐름을 한 번에 확인합니다. SCOPE·통합 점검·대시보드·보조 기능·종료도 함께 기록합니다. 실제 HTTP에서 얻은 원본과 원본 ID를 사용하며, 파싱·탐색·도구 간 전달만으로 새 요청을 보내지 않는 흐름은 HTTP 차이가 0일 수 있습니다.

초기 1.4.0을 A0, 개선을 순서대로 누적한 제품을 A1~A5라고 하면 다음 **10회**를 순차 실행합니다.

| 라운드 | 전체 흐름 사전 확인 | 개선 후 전체 재확인 |
|---|---|---|
| 1 | A0 | A1 |
| 2 | A1 | A2 |
| 3 | A2 | A3 |
| 4 | A3 | A4 |
| 5 | A4 | A5 |

이 실행기는 개선을 적용하거나 6개 제품을 생성하지 않습니다. 각 제품을 별도로 준비한 뒤 해당 JAR를 지정합니다. `--phase`는 구분용 라벨이며 라운드가 실제로 수행됐다는 자동 주장이 아닙니다. 각 전후 `metadata.json`의 제품 SHA, 같은 하네스 SHA, 같은 어댑터 SHA와 `results/summary.json`의 8개 모듈별 액션·원본·실제 HTTP 조건을 대조하세요. 서버의 사전 상태와 실행 중 변경된 실습 데이터도 결과 해석에 포함해야 합니다.

```sh
python audit/simple-usability/run_probes.py --probe whole \
  --jar /path/to/A0.jar --java /path/to/jdk-17/bin/java \
  --adapters /path/to/frozen-test-adapters \
  --out /tmp/whole-round-1-before-001 --phase round-1-before

python audit/simple-usability/run_probes.py --probe whole \
  --jar /path/to/A1.jar --java /path/to/jdk-17/bin/java \
  --adapters /path/to/frozen-test-adapters \
  --out /tmp/whole-round-1-after-001 --phase round-1-after
```

라운드 2~5도 각각 새 출력 경로로 실행합니다. 전체 흐름 요약에는 8개 모듈, 실행 JAR SHA, `18888` origin, 실제 엔진 HTTP 요청, `nativeBurp=false`가 있어야 합니다. 실행기가 이 기본 계약을 검사하지만, 세부 assertion의 성공 여부와 실제 액션 수는 원본 결과에서 직접 확인합니다. 기존 `reading`·`target`·`tables`·`urls`·`dashboard` 수치는 개선 원인을 조사하는 보조 근거이며 전체 라운드를 대신하지 않습니다.

## 프로브별 범위

| 프로브 | 사용하는 데이터와 확인하는 것 |
|---|---|
| `reading` | 실제 격리 loopback HTTP 응답에서 선택한 근거를 읽고 복사하는 동안 반복 갱신합니다. 텍스트 선택·문서 이벤트·원본 ID·불필요한 추가 요청을 기록합니다. 네이티브 클립보드나 OS 입력 검증은 아닙니다. |
| `target` | 서로 다른 두 격리 loopback origin에서 대상 입력 완료·계정 갱신·명시적 계정 선택·정확한 인증 원본을 확인합니다. 테스트가 Swing 액션·포커스 콜백을 호출합니다. |
| `tables` | 실제 loopback 요청 **2개**에서 얻은 원본을 연결한 **합성 Finding 2,000개·Page 2,000개**로 갱신합니다. 서로 다른 URL 2,000개를 실제 탐색했다는 뜻이 아닙니다. 동일 갱신·한 항목 변경·정렬 중 변경·행 역순·정렬만을 각 2회 예열 뒤 5회 측정합니다. 예열은 통계에서 제외합니다. |
| `urls` | 합성 URL 1,000개를 운영 URL 가져오기 화면에 넣습니다. 원본 쿼리 구분·필터 비용·정렬 이벤트·EDT 할당량을 기록합니다. 네트워크 요청은 보내지 않습니다. 원본 하네스 그대로 1회 예열·5회 표본입니다. |
| `dashboard` | 합성 요청·응답을 운영 DiskTrafficStore와 대시보드에 넣고 light/dark × 1024×720/1280×800의 4가지 화면을 렌더링합니다. 명시적 버튼 액션 1회 뒤 목록·상세·선택 행이 보이는지 확인합니다. 마우스 휠 횟수를 추정하지 않습니다. 실제 사이트 응답이나 native Burp 캡처가 아닙니다. |
| `loop` | 이미 실행 중인 원래 LOOP 3.10.0 서버에 실제 HTTP를 보냅니다. 정상 로그인·요청 예산·계정별 원본·원본 ID 전달·증거 및 로컬 보고서·종료 후 무전송을 기록합니다. 단계 시간은 해당 머신의 단일 자동 관측이며 버전 간 성능 벤치마크가 아닙니다. |
| `whole` | 같은 원래 LOOP 서버의 실제 HTTP 원본으로 전체 8개 핵심 기능과 공통 흐름을 확인합니다. 각 모듈의 coverage·액션·assertion·HTTP 차이·원본 ID를 기록하며, 한 개선 라운드의 전후에 모두 실행합니다. 새로 작성한 하네스이며 기존 4개 외부 복사 하네스와 구분합니다. |

## 결과와 해석

- `metadata.json`: 실행 인자, JDK 17·`--release 17` 적용, 하네스/제품/어댑터/의존성 지문, 입력 JAR 불변 여부, 비공개 런타임 정리 결과를 담습니다. 환경변수 값은 기록하지 않습니다. 외부 JVM 옵션을 주입하는 `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `CLASSPATH`는 자식 프로세스에서 제거하고 이름만 기록합니다.
- `results/`: 원본 JSON과 대시보드 PNG입니다. `dashboard`는 네 가지 화면의 JSON·이미지 및 합산 `summary.json`을 만듭니다. 전후 버전에서 예상되는 `false` 값도 바꾸지 않고 보존합니다. 실행기의 `completed`는 프로브 실행이 끝났다는 뜻이며 모든 결과 항목이 성공했다는 뜻은 아닙니다.
- `logs/`: 문자열 점검을 통과한 컴파일·프로브 로그만 보관합니다. 자격증명 형태가 발견되면 해당 파일을 내보내지 않고 메타데이터에 보류 사유만 기록합니다.
- `.product.jar`, `.deps/`, `.adapters/`, `.sources/`, `.classes/`: 그 실행의 고정 입력·소스와 독립 하네스 산출물입니다. 제품 JAR를 클래스패스 맨 앞에 두므로 공유 제품 build/classes와 섞이지 않습니다. 어댑터에 동명 테스트 클래스가 있어도 새 하네스 클래스가 먼저 선택됩니다. 실제 컴파일한 소스는 `.sources/` 복사본이며 원본·복사본 SHA를 실행 전후 확인합니다.

`ThreadMXBean` 할당량은 **측정 구간의 EDT 누적 할당 바이트**입니다. 유지 힙, RSS, 최고 메모리, 백그라운드 스레드 할당량과 다릅니다. EDT 작업 시간은 repaint 완료·화면 제시·이벤트 큐 대기·사용자의 체감 지연 전체를 포함하지 않습니다. 작은 표본의 중앙값·최솟값·최댓값 진단이며 절대 시간 합격 기준이나 현장 Burp의 보장 수치로 사용하지 않습니다. `sort_only` 같은 대조 구간에서 개선이 없으면 그대로 보고합니다.

Java의 `user.home`, Preferences, `java.io.tmpdir`와 작업 디렉터리는 매 실행의 별도 임시 폴더로 지정하고 종료 시 제거합니다. 실제 환경의 `HOME`은 재정의하지 않습니다. 원문 보고서 ZIP·원본 패킷·런타임 설정은 결과로 복사하지 않으며 실패·시간 초과 때도 임시 폴더 정리를 시도합니다. 정리에 실패하면 메타데이터를 실패로 기록하고 남은 폴더 위치를 제공합니다.

텍스트의 자동 자격증명 검사는 휴리스틱입니다. 공유 전 JSON·로그를 직접 확인하세요. PNG에는 문자열 검사나 OCR을 적용하지 않으므로 합성 fixture 캡처임을 확인하고 별도로 육안 검토해야 합니다. 계정 쿠키·인증 헤더·응답 원문·보고서 ZIP을 공개 결과에 추가하지 마세요.
