# Workbench Simple 1.4.3 · Burp 확장 로드 복구

Simple 1.4.2를 Burp에 추가할 때 `NoClassDefFoundError: com/sun/net/httpserver/HttpServer`로 전체 확장 초기화가 중단되는 문제를 수정합니다. 내장 Mapper는 0.3.7, 브라우저 도우미는 기존 0.4.1입니다. 이전 Simple·Next 배포 파일은 보존합니다.

- [수정 JAR](../downloads/1.4.3/burp-workbench-simple-1.4.3.jar)
- [독립 빌드 소스 ZIP](../downloads/1.4.3/burp-workbench-simple-1.4.3-source.zip)
- [SHA-256](../downloads/1.4.3/SHA256SUMS)

## 달라진 동작

브라우저 도우미용 인증 loopback 서버를 시작할 수 없어도 8개 도구와 HTTP 수집·자동 매핑을 초기화합니다. 매핑 설정의 브라우저 연결 상태와 확장 출력에 연결 불가 이유·복구 방법을 표시합니다. 연결 주소·토큰을 제공할 수 없는 상태를 정상 연결처럼 표시하지 않습니다.

브리지에 필요한 JDK HTTP 서버 클래스 누락과 로컬 서버 시작의 I/O 실패만 선택 기능 실패로 처리합니다. 다른 클래스 누락은 그대로 실패시키므로 관계없는 코드·의존성 오류를 숨기지 않습니다. 정상 Java 환경에서는 기존 인증·loopback·브라우저 이벤트 전송 경로를 유지합니다.

서버를 만든 뒤 다른 HTTP 서버 클래스 접근이 실패하는 경우에도 생성 중인 소켓·서버 타이머와 실행기를 정리합니다. 초기화가 끝나지 않은 서버가 확장 종료 처리 밖에 남지 않도록 합니다.

## Burp Community 2026.9.2에서 확인할 점

사용자가 제공한 오류는 Burp가 사용하는 Java 또는 확장 클래스 로더에서 `jdk.httpserver`의 클래스를 불러오지 못했다는 뜻입니다. 이 스택만으로 모듈 자체 누락과 클래스 로더의 접근 문제를 구별할 수는 없습니다. 클라우드 개발 환경의 JDK를 설치해도 PC에서 실행 중인 Burp의 Java는 바뀌지 않습니다.

1. 기존 1.4.2 파일·프로젝트를 보관하고 확장의 로드를 해제한 뒤 1.4.3 JAR를 추가합니다. 모듈에 접근할 수 없는 상태에서도 일반 HTTP 작업을 사용할 수 있습니다.
2. 브라우저 도우미·SPA 기능까지 사용하려면, 사용 중인 Burp 버전이 지원하는 완전한 JDK와 공식 Burp Community JAR로 실행을 확인합니다. 다른 Java 대신 해당 JDK 실행 파일의 절대 경로를 사용합니다.

```text
"<JDK 경로>/bin/java" --list-modules
"<JDK 경로>/bin/java" --add-modules jdk.httpserver -jar burpsuite_community_v2026.9.2.jar
```

Windows에서는 실행 파일을 `bin\java.exe`로 지정합니다. `--list-modules` 결과에 `jdk.httpserver`가 없으면 그 Java에 해당 모듈이 없는 것이므로 완전한 JDK가 필요합니다. `--add-modules`는 없는 모듈을 설치하거나 Burp의 클래스 로더 위임을 변경하지 않습니다. 모듈이 있는데도 같은 메시지가 남으면 확장 출력과 실제 Burp 실행 Java를 확인해야 합니다. 다른 JDK의 `jdk.httpserver.jmod`를 JAR로 바꾸어 확장에 섞지 않습니다.

현재 Java에서 브리지가 사용 불가인 동안 브라우저 도우미·SPA 연결은 복구되지 않습니다. HTTP 수집·HTTP 점검 경로와 구분해서 사용합니다. 실제 사용자 PC의 Burp GUI에서 로드 성공을 확인한 결과는 별도로 받아야 합니다.

## 개발 검증

저장소 루트에서 완전한 JDK 17 이상을 활성화한 뒤 실행합니다.

```sh
python simple/dev.py check
python simple/tools/render_simple.py --out tmp/simple-layout-1.4.3 --check
python simple/tools/render_scope_inspection.py --out tmp/simple-inspection-layout-1.4.3 --check
python simple/tools/verify_katana.py --katana /absolute/path/to/katana --out tmp/katana-1.4.3
python simple/dev.py verify
```

새 회귀는 실제 확장 진입점에 대해 별도 JVM에서 모듈 누락과 클래스 로더의 접근 거부를 검사합니다. 합성 Montoya·Swing 검증이며 사용자 PC의 실제 Burp GUI·브라우저 DOM 검증과 다릅니다. 이번 실행 결과는 [검증 기록](VERIFICATION_SIMPLE_1.4.3.json)에 구분합니다.
