# Simple 1.3.0 → 1.3.1 실제 앱 재시험

공유 대화에서 이름을 확인한 7개 앱의 실제 원본 소스/배포판을 로컬에 실행하고, 보존한 배포 JAR의 `ScopeInspectionRunner`와 공식 Katana 1.8.0 프로세스를 연결한다. 응답을 가짜 서버로 대체하지 않는다. 실제 Burp GUI 조작이나 TCP 원시 캡처를 검증하는 도구는 아니다.

Java `HttpClient`가 실제 앱의 응답 payload를 수신한다. HTTP/1.1을 지정하며, 전송 계층의 chunk framing을 제거하고 정규화한 HTTP 헤더와 수신 body를 Montoya 호환 객체로 재구성한다. `.response.http`는 이 재구성 응답의 정확한 저장본이며 TCP 원시 바이트라는 의미가 아니다. 별도의 `.body.bin`은 실제로 수신한 payload, `.decoded.txt`는 압축/문자 복원 결과다. `Content-Length` 불일치 등 수신 실패는 완료 응답 수에서 제외하고 `transportErrors`로 남긴다.

## 대상 원본

| 대상 | 고정 원본 | 로컬 URL |
|---|---|---|
| Juice Shop | 공식 20.3.0 Node24 Linux x64, SHA256 `47420604f215eb412cc02890973ee208a63fc0c62fe7da1d64ac5743b12c1186` | `http://127.0.0.1:24800/` |
| BenchmarkJava | `bc5ffeb09fb18ab039806d84ca1f718c2e332c54`, 전체 앱/2,766개 Java 소스 | `http://127.0.0.1:24801/benchmark/` |
| VulnerableApp | 2.1.0, `668ab14704b63d286bc73f5ec302885a5f192c2e`, 전체 legacy UI/bootJar | `http://127.0.0.1:24803/VulnerableApp/` |
| OopsSec Store | 2.22.0, `ed7ae9b8b17dc17fd8617e9c79bd7222ca842374`, 원본 Next/Prisma/seed | `http://127.0.0.1:24802/` |
| DVBank Lab | `cdcd3174a5712d58aaa99b059b76ef2504a75aee`, 원본 Flask API/CRA build | `http://127.0.0.1:5000/` |
| VulnBank | `4bb7cd5f46921959f034455e5615782481966177`, 실제 PostgreSQL13 | `http://127.0.0.1:24804/` |
| LOOP Community | 저장소 원본 3.10.0 `ROOT.war`, SHA256 `6b325934133adac02ef28c1745b1cfb48a598985171ed3393f6f6df0af491301` | `http://127.0.0.1:24808/` |

실제 source/archive/image SHA, 원본 파일 변경 여부, 실행 명령, DB/배포 차이는 `/workspace/simple-sites/*/metadata.json` 또는 `*-metadata.json`에 기록한다. Java 앱의 재현 파일은 [java-sites](java-sites/)에 있다. 원본 전체 앱을 빌드했다는 사실과 모든 취약점 사례를 시험했다는 주장은 구분한다. 한도 내 GET/HEAD 수집이며 공격 payload를 자동 실행하는 시험이 아니다.

Juice Shop은 원본을 수정하지 않고 기존 `audit/oss-lab/offline-loopback.cjs`를 preload해 외부 연결을 차단하고 listener를 loopback에 고정한다. VulnBank도 외부 실행 wrapper에서 loopback PostgreSQL/HTTP만 허용한다. LOOP은 원본 WAR의 `DevServer`와 H2 Oracle 호환 모드를 사용한다. Oops/DVBank 및 Java 앱의 원본과 다른 배포 설정은 해당 metadata의 제한 사항을 보고서에 그대로 옮긴다.

### Juice/VulnBank/LOOP 원본 준비

Linux, Node24, Python3.12, Java17 이상 및 Docker가 필요하다. 아래 준비 스크립트는 원본 archive/commit과 기존 WAR를 사용하고 서비스를 시작하지 않는다. 현재 실행에서는 같은 준비 단계를 개별 수행했으며, 새 폴더에서 이 통합 준비 스크립트 전체를 다시 실행했다고 주장하지 않는다.

```sh
python audit/simple-retest/prepare_owned_sources.py --root /workspace/simple-sites
python -m venv /workspace/simple-sites/vulnbank/venv
/workspace/simple-sites/vulnbank/venv/bin/pip install \
  -r /workspace/simple-sites/vulnbank/source/requirements.txt Jinja2==3.0.3
```

Juice Shop은 `source`를 작업 폴더로 하여 `PORT=24800`, `NODE_OPTIONS=--require=/절대/저장소/audit/oss-lab/offline-loopback.cjs`와 `node build/app.js`로 실행한다. LOOP은 `PORT=24808`, `DB_URL=jdbc:h2:mem:simple-retest;MODE=Oracle;DB_CLOSE_DELAY=-1`과 classpath `loop/war/WEB-INF/classes:loop/war/WEB-INF/lib/*:loop/deps/*`로 `java lab.enterprise.DevServer`를 실행한다.

VulnBank의 DB는 `postgres@sha256:fb9065b6e3e213bdc07edd372a5b2a26245840b7fb65d1fd8b6700106d51805c`를 `127.0.0.1:24814:5432`에만 publish하며 DB/user 이름을 `vulnbank`로 지정한다. 별도 로컬 테스트 암호를 `SIMPLE_RETEST_DB_PASSWORD`에 준비하고 동일 암호를 DB의 `POSTGRES_PASSWORD`로 전달한다. 애플리케이션은 그 venv Python으로 `audit/simple-retest/launch_vulnbank.py --source /workspace/simple-sites/vulnbank/source`를 실행한다. upstream Flask2.0.1의 Jinja API 호환성을 위해 Jinja2를3.0.3으로 고정하며 원본 앱 파일을 수정하지 않는다.

## 실행

실행 중인 원본 앱과 고정 Katana 바이너리, 정상 로그인으로 준비한 별도 로컬 config가 필요하다. 각 버전은 새 JVM과 새 원본 저장소를 사용한다. 클래스 어댑터는 별도 고정 복사본(`/workspace/simple-sites/harness-adapters`)이므로 제품의 `build/tests` 재생성 중에도 섞이지 않는다. 완전한 JDK17을 사용한다.

```sh
python audit/simple-retest/run_site.py \
  --jar simple/downloads/1.3.0/burp-workbench-simple-1.3.0.jar \
  --config /workspace/simple-sites/configs/juice-shop-root.json \
  --out /workspace/simple-sites/results/1.3.0/juice-shop/root \
  --java /workspace/tools/jdk17/bin/java
```

1.3.1도 검증 후 고정한 새 JAR 경로와 `results/1.3.1` 출력 폴더를 지정한다. 같은 앱·계정·Scope·예산·깊이·시간 한도를 유지한다. config의 첫 선택 계정이 Katana의 수집 계정이 되도록 하네스가 순서를 맞춘다. 루트 자동 발견(`scenario=root`)과 보호 API/알려진 직접 seed 대조(`scenario=protected` 또는 `direct`)를 별도 실행한다. 직접 지정한 경로가 응답했다는 사실을 자동 발견 성공으로 합산하지 않는다.

`run.json`은 엔진의 실제 `PAUSED/STOPPED/COMPLETED` 상태, 이유, 전송 시도 수, 완료 응답 수, 전송 오류, 대기/부분/제외, 원본 ID, 제품 findings와 Katana metadata를 보존한다. `COMPLETED`는 발견 대기열 처리가 끝났다는 뜻이며 미확인·부분·실패가 없는 전체 사이트 검증을 의미하지 않는다. 새로운 제품 exporter가 있으면 reflection으로 호출해 `product-export` ZIP을 저장한다. 1.3.0에 없는 exporter는 없는 것으로 표시하며 후속 보고서 parser와 혼동하지 않는다.

## 원문 보고서와 파일 경계

생성물은 `/workspace/simple-sites/results`와 `/workspace/simple-retest-1.3.1-report`에만 보관한다. 응답에서 파싱한 값은 제품 탐지(`productDetected`), 보고서 보충 파싱(`reportSupplemental`), 테스트 로그인 인증(`testLoginAuth`)으로 구분한다. 원문 위치/원본 ID/응답 SHA와 실제 span이 확인되지 않으면 정확한 제품 탐지값으로 추측해 넣지 않는다. 코드 표현식, 공개/예제 토큰, 내부 설정, 실제 로컬 로그인 값을 구별한다.

로그인 config와 `private-auth.json`, HTTP 요청/응답 corpus에는 실행 중 생성한 로컬 인증값이 들어간다. Git에 추가하지 않는다. 공개 저장소에는 이 하네스·재현 설명·인증값 없는 실행 요약만 보관하며, 사용자 요청의 원문 값 보고서와 증거는 별도 로컬 첨부물로 생성한다. 이전 1.3.0 파일과 보고서는 덮어쓰지 않는다.

공유 대화의 이전 5,042개 검사나 1,001개 요청 수는 이번 실행 수치로 재사용하지 않는다. 원문이 제공되지 않은 기존 개선안 9개의 누락 항목도 만들지 않는다. 이번에 재현·수정·재검증한 내용과 남은 한계를 실제 결과로 적는다.
