# Simple 개발 검사 런타임

Simple 1.0.0의 개발 검사는 **완전한 JDK 17 이상, Python 3.10 이상, Node 24 이상**을 사용합니다. 설치 JAR 실행에는 Node가 필요하지 않습니다. 기존 Workbench 0.53.4의 소스·개발 검사 설정은 그대로 유지합니다.

첫 Simple 원격 검사 [40507161265](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40507161265)는 완전한 JDK 17에서 `--release 17` 컴파일과 122개 그룹·4,231개 회귀, payload HTTP 136개, Mapper Java 134개를 통과했습니다. 이후 Node 22.23.3이 `--test-isolation=none` 옵션을 지원하지 않아 도우미 검사 실행 전에 종료했습니다. 화면·배포 무결성 단계는 건너뛰었으며 당시 전체 CI를 통과했다고 표시하지 않습니다.

후속 CI는 Simple 1.0.0의 로컬 검증에 사용한 Node 24로 맞춥니다. 설치 JAR·독립 소스 ZIP·PDF·해시·manifest는 변경하지 않습니다. 브라우저 도우미의 15개 하위 검사와 화면·배포 무결성 검사를 실제 실행합니다. 이미 생성한 1.0.0 파일을 덮어쓰지 않습니다.

독립 소스 ZIP을 개발할 때 Node 24를 PATH에 준비한 뒤 `python dev.py check`를 사용합니다. 로컬 Java 21의 `ct.sym` 누락으로 수행한 명시적 `--source-target` 검사와, 원격 완전한 JDK 17의 엄격한 API 검사는 별도 근거입니다. 실제 Burp GUI·Windows DPI/IME 검증을 의미하지 않습니다.
