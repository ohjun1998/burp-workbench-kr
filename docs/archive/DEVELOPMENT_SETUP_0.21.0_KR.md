# Burp Workbench KR 개발 재개 환경

첨부한 0.21.0 이어가기 패키지를 기준으로 소스, 실습 사이트 3.4.0, 재현 코드와 이전 검증 자료를 복원했습니다. 이번 작업은 개발 환경 준비이며 확장 기능을 변경하지 않았습니다. 재현된 테스트 불안정성을 해결하기 위해 `JsHistoryTest`의 UI 전용 스레드 처리와 공통 HTTP 요청 모의 객체를 수정했습니다.

## 바로 사용하기

이 문서와 `dev.py`가 있는 폴더를 터미널에서 열고 실행합니다. Windows에서는 `python`, Linux에서는 필요에 따라 `python3`를 사용합니다. Python 3.10 이상과 Java 17 이상 및 컴파일러가 필요합니다.

```sh
python dev.py doctor
python dev.py build
python dev.py test
python dev.py http-test
python dev.py check
```

- `doctor`: Java/컴파일러/Python 확인.
- `build`: 소스에서 독립 실행형 확장 JAR 생성.
- `test`: 재빌드 후 기존 회귀 테스트 및 페이로드 HTTP fixture 실행.
- `http-test`: 현재 빌드 JAR로 격리 실습 서버를 생성하고 전체 기능 HTTP 테스트 실행 후 종료. 소스 수정 후에는 먼저 빌드합니다.
- `check`: 재빌드·회귀·HTTP 테스트를 순서대로 실행.

Java가 PATH에 없으면 절대 경로를 전달합니다.

```text
python dev.py check --java "C:/Program Files/Java/jdk-17/bin/java.exe" --javac "C:/Program Files/Java/jdk-17/bin/javac.exe"
```

Linux에서는 `javac` 명령이 없어도 Java의 `com.sun.tools.javac.Main`이 제공되면 작업 폴더 안에 래퍼를 생성합니다. Windows HTTP 테스트에는 JDK의 `javac.exe`가 필요합니다. 임시 서버는 127.0.0.1만 사용하며 기본 포트는 18099입니다. 포트가 사용 중이면 자동 연결하지 않고 실패합니다. 다른 포트는 `--port 18199`처럼 지정합니다. 기존 Burp/브라우저/프록시 설정을 변경하지 않습니다.

## 주요 위치

| 용도 | 이 폴더 기준 경로 |
|---|---|
| 확장 소스 | `project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java/` |
| 회귀 테스트 | 위 확장 폴더의 `src/test/java/` |
| 빌드 JAR | 위 확장 폴더의 `dist/burp-workbench-kr-0.21.0.jar` |
| 이전 배포 원본 | `release/burp-workbench-kr-0.21.0.jar` |
| 실습 사이트 | `project/burp-development-workspace/community-security-lab/` |
| 현재 세팅 검증 | `setup/verification.json`, `setup/build-tests.log`, `setup/http-tests.log` |
| 이전 작업 인수인계 | `이어가기_인수인계.md` |
| 0.21 사용법 | `release/계정별_업무별_사용법_및_검증_0.21.0.md` |

포함된 `pom.xml` 버전은 0.18.0으로 남아 있습니다. 이번 환경은 기존 공식 빌드인 `build_all.py`를 사용하며 Maven이 필요하지 않습니다. HTTP 테스트 JSON의 `version: 0.19.0`도 기존 하네스의 고정 문자열입니다. 실제 테스트 대상은 `dist`의 재빌드된 0.21.0 JAR이며 `setup/verification.json`의 해시로 구분합니다.

## 검증과 한계

패키지 복원 직후 원본 매니페스트의 428개 파일 크기·SHA-256을 모두 검사했습니다. 이번 빌드 산출물은 JDK/압축 시각 등 빌드 조건 때문에 이전 배포 JAR와 바이트가 같지 않습니다. 이전 배포 JAR는 `release/`에 유지하며 이번 빌드 해시는 별도로 기록합니다.

이전 기록의 1,312개 통과는 이전 환경에서 수행한 결과입니다. 이번 환경에서 실제 수행한 검사와 초기 실패·최종 재실행 결과는 `setup/verification.json`을 기준으로 읽습니다. 초기 동시 실행에서 `JsHistoryTest`의 빠른 응답 대기열 검사가 한 번 실패했고, 단독 재실행에서는 UI 스레드 밖 초기화로 Swing 예외가 발생했습니다. 테스트의 완료 판정을 UI 스레드에서 원자적으로 확인하고 초기화도 같은 스레드에서 실행하도록 수정했습니다. 이후 검사는 종료 코드 0이었지만 공통 요청 모의 객체의 `body()`가 null이라 UI 백그라운드 예외가 기록됐습니다. `WorkbenchTest.request`가 실제 API처럼 빈 ByteArray와 빈 본문 문자열을 반환하도록 보완했습니다. 모든 초기 로그를 보존하고 확장 구현 소스는 변경하지 않았습니다.

추가 작업에서 공식 Burp 2026.7.3과 가상 X 화면을 준비해 실제 Burp GUI 및 네이티브 검증을 수행했습니다. 결과는 `setup/실제Burp_화면검증_KR.md`를 확인하세요. Docker 실행 권한은 확보하지 못했으며 브라우저 DOM/Pro Collaborator/외부 BApp/전체 Docker·Oracle·Nginx/TLS·HTTP2는 미검증입니다. 실제 Burp 검증 재개 시 `validation/native/`와 `reproduction/native-observer/`를 참고해 Burp 경로·임시 프로필·포트를 현 환경에 맞춰 구성해야 합니다. 이전 Windows 절대 경로 런처를 그대로 실행하지 마세요.

## 다음 개발 후보

1. 실제 Burp 환경에서 JS 응답 대기열 및 장시간 수집 상태 추가 확인.
2. 계정별 입력 값 변화·완료 당시 인증/역할 버전 기록 보강.
3. 업무의 계정/API 조합별 증거 조건 및 증거 파일 상태 확인 강화.
4. Passive·데이터 비교 결과의 원본 패킷과 증거 연결 개선.
5. 최신 0.21 화면을 반영한 사용 가이드 갱신.

기능 개발은 후속 요청으로 지정합니다. 이번 작업에서 위 후보를 구현 완료로 취급하지 않습니다. 현재 작업 폴더에는 변경 비교를 위한 로컬 Git 기준 커밋을 준비했습니다. 다운로드 ZIP은 Git 내부 데이터를 제외하므로 필요한 경우 압축을 푼 뒤 `git init`으로 새 기록을 시작할 수 있습니다.

## 이번 환경 최종 실행 결과

회귀/API/Swing 905개(31묶음), 페이로드 HTTP fixture 127개, 전체 기능 HTTP 100개(199건 요청, 16개 프로필), 총 1,132개 검사 통과. 최종 순차 실행 종료 코드 0, 로그의 미처리 예외 0건, 실습 서버 포트 18099 종료 확인. 실제 Burp 검사 180개는 이전 환경 기록이며 이번 합계에 넣지 않았습니다.

## 추가 실제 Burp 검증

실제 GUI 12개 화면 확인 및 네이티브 API 180개 검사 통과. 이번 환경에서 수행한 전체 검사 합계는 1,312개입니다. Docker는 원격 실행 환경 연결 대기이며, 권한 확보·실행 완료로 간주하지 않습니다.

## 공유 대화의 Docker 기록 확인

공유 링크의 32개 턴을 확인했습니다. 이전 검증은 Java loopback 서버와 실제 Burp API를 사용했고 전체 Docker/Oracle/Nginx는 실행하지 않았다고 명시합니다. Docker 실행 권한을 확보한 절차는 확인되지 않았습니다. 근거와 현재 환경 비교는 `setup/Docker_공유대화_확인_KR.md`에 기록했습니다.

## 후속 브라우저·프로토콜 검증

실제 Chromium 23개, 프로토콜 fixture 11개, 실제 Burp TLS/HTTP2 6개를 추가 검증해 현재 기능 검사 합계는 1,352개입니다. Docker Compose 설정·배포 입력 14개 사전 검사는 별도로 기록합니다. 커뮤니티 모바일 검색란의 가로 넘침을 수정하고 소스와 ROOT.war에 반영했습니다. 확장 Java 운영 소스/JAR는 변경하지 않았습니다. 자세한 결과와 미완료 조건은 `setup/추가검증_및_남은단계_KR.md`, 재실행 절차는 `validation-local/README_KR.md`를 확인하세요. 초기 Burp GUI 검증 기록의 브라우저·TLS/HTTP2 미검증 상태는 당시 시점이며, 후속 완료 결과는 `setup/additional/verification.json`이 기준입니다.
