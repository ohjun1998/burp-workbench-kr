# 고정한 원본 Java 사이트 2종 준비

`setup_java_sites.py`는 저장소 밖의 지정 폴더에 두 원본 앱을 내려받고 전체 앱을 빌드·실행하는 재현 도구입니다. 24개 servlet만 올리는 이전 Benchmark 하네스와 구분합니다. 이번 준비에서는 BenchmarkJava 전체 WAR와 VulnerableApp 전체 Spring Boot JAR를 실제 기동했고 각각 루트와 대표 servlet/API의 HTTP 200을 확인했습니다. 새 폴더에서 이 통합 스크립트 전체를 반복 실행한 것으로 기록하지는 않습니다. 실제 재시험 결과는 상위 `audit/simple-retest`의 버전별 근거를 확인합니다.

| 앱 | 원본 고정 커밋 | 실행 주소 | 실행 범위 |
|---|---|---|---|
| OWASP BenchmarkJava | `bc5ffeb09fb18ab039806d84ca1f718c2e332c54` | `http://127.0.0.1:24801/benchmark/` | 원본 2,766 Java 소스의 전체 WAR, HSQLDB·LDAP helper, Tomcat 9.0.98 |
| OWASP VulnerableApp | `668ab14704b63d286bc73f5ec302885a5f192c2e` | `http://127.0.0.1:24803/VulnerableApp/` | 원본 전체 bootJar·legacy UI·H2·LDAP. 별도 modern facade는 제외 |

`targets.json`에는 실제 준비에서 확인한 소스 archive·빌드 산출물·실행 이미지 SHA, 원본 파일 대조 범위와 응답 상태만 기록합니다. 인증 헤더·환경 변수·프록시 인증값·응답 본문은 포함하지 않습니다. 앱의 전체 검증 완료나 취약점 판정 기록으로 사용하지 않습니다.

Linux amd64, Python 3.12+, 로컬 Docker daemon과 빌드 의존성 다운로드가 필요합니다. 저장소 루트에서 다음처럼 실행합니다. 목적지는 저장소 밖의 쓰기 가능한 절대 경로입니다.

```sh
python audit/simple-retest/java-sites/setup_java_sites.py --dest /workspace/java-site-lab
```

관리형 HTTPS 프록시가 별도 CA를 제공하면 해당 공개 인증서 경로를 `--ca-file`로 지정합니다. 예를 들어 이 실행 환경에서는 `--ca-file "$CODEX_PROXY_CERT"`를 추가할 수 있습니다. TLS 검증은 유지하며 신뢰 저장소·Maven 프록시 설정·의존성 캐시는 지정 폴더에 생성합니다. 기존 HOME·시스템 Java·Docker 인증 설정을 바꾸지 않습니다. 생성한 `java-runtime/maven-settings.xml`과 작업 폴더의 인증·환경 파일은 공개 자료에 복사하지 않습니다.

이미지는 소스에 기록한 digest로 고정합니다. 앱은 public egress 없는 Docker internal network에서 실행하고 `java-sites-loopback.py`가 지정한 두 포트만 executor의 `127.0.0.1`에 연결합니다. 전송 바이트는 변경하지 않습니다. 별도 관리자 설치나 host networking을 사용하지 않습니다.

정상 종료는 두 전용 컨테이너와 기록한 relay PID만 대상으로 합니다. 공유 daemon의 다른 컨테이너·이미지·볼륨을 제거하지 않습니다.

```sh
docker --host=unix:///var/run/docker.sock stop simple-benchmarkjava simple-vulnerableapp
```

두 앱은 전역 로그인 없이 루트와 대표 GET 경로를 제공합니다. 재시험의 `protected` 파일 이름을 사용해도 이 두 앱의 시나리오는 `direct`로 기록하며 인증된 점검이라고 표시하지 않습니다. 각 취약점 모듈의 별도 인증·쓰기 흐름은 이 준비 확인의 실행 범위에 포함하지 않았습니다.
