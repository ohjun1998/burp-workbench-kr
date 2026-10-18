# 개발 환경과 검증 명령

확장 **0.44.0**, Feature Mapper **0.3.1**과 실습 사이트 **3.10.0**의 개발 안내입니다. 처음 복원한 0.21.0 환경의 원문과 당시 Burp·브라우저 검증 기록은 [이전 개발 환경 문서](docs/archive/DEVELOPMENT_SETUP_0.21.0_KR.md)에 보존합니다. 현재 실행 결과는 [0.44.0 검증 기록](docs/VERIFICATION_0.44.0.json)과 해당 PR의 Actions 결과를 확인합니다.

[저장소 안내](docs/REPOSITORY_MAP_KR.md) · [다운로드](downloads/README.md) · [모의 진단 절차](docs/TEST_WORKFLOW_KR.md) · [협업 안내](docs/COLLABORATION_KR.md)

## 준비

저장소 전체를 체크아웃하고 루트 폴더에서 실행합니다. Python 3.10+, JDK 17+·Java 컴파일러와 Mapper 브라우저 도우미 검사용 Node 22+가 필요합니다. Windows에서는 `python`, Linux/macOS에서는 설치에 따라 `python3`를 사용합니다.

```sh
python dev.py doctor
python dev.py check
```

`doctor`는 Java·컴파일러·Python과 선택 도구의 설치 상태를 보여 줍니다. Docker나 실제 Burp가 설치되어 있지 않아도 Java 회귀·HTTP 어댑터 검사는 실행할 수 있습니다. HTTP 하네스가 요구하는 런타임 의존성이 준비되지 않았다면 첫 실행에 다운로드가 필요하며, 포함한 런타임 캐시는 고정 SHA-256과 일치할 때만 사용합니다.

Java가 PATH에 없으면 절대 경로를 지정합니다.

```text
python dev.py check --java "C:/Program Files/Java/jdk-17/bin/java.exe" --javac "C:/Program Files/Java/jdk-17/bin/javac.exe"
```

Linux/macOS에서는 `javac` 명령이 없어도 `java com.sun.tools.javac.Main`을 사용할 수 있으면 저장소 안에 컴파일러 래퍼를 만듭니다. Windows HTTP 검사에는 JDK의 `javac.exe`가 필요합니다.

## 명령별 역할

| 명령 | 수행 내용 |
|---|---|
| `python dev.py doctor` | 현재 도구와 경로 확인 |
| `python dev.py build` | 현재 소스로 확장 JAR 재빌드 |
| `python dev.py test` | 확장 재빌드, 회귀·API·헤드리스 Swing 및 페이로드 HTTP fixture 검사 |
| `python dev.py http-test` | 현재 빌드 JAR와 실습 사이트 소스로 전체 기능 HTTP 검사 |
| `python dev.py bounty-test` | 현재 빌드와 준비된 HTTP 런타임으로 커뮤니티 모의 진단 시나리오 검사 |
| `python dev.py check` | 재빌드·회귀 → 전체 기능 HTTP → 커뮤니티 모의 진단 순서로 검사 |
| `python dev.py release` | `check`와 같은 검사를 통과한 뒤 버전별 다운로드 생성 |
| `python tools/package_downloads.py --verify` | 현재 소스와 배포 파일의 해시·소스 대응 확인 |

`http-test`와 `bounty-test`는 확장을 자동 재빌드하지 않습니다. 코드 변경 뒤에는 `check`를 사용하는 것이 간단합니다. 모의 진단만 별도로 반복할 때는 먼저 `build`와 `http-test`로 최신 JAR·HTTP 런타임을 준비합니다.

```sh
python dev.py build
python dev.py http-test --port 18199
python dev.py bounty-test --port 18399
```

각 HTTP 검사는 전용 loopback 서버를 생성하고 종료합니다. 전체 `check`/`release`는 첫 HTTP 검사에 `--port`(기본 18099), 후속 모의 진단에 `--bounty-port`(기본 18399)를 사용합니다. 포트 재사용 대기를 피하도록 서로 다른 값을 지정합니다. 단독 `bounty-test`는 `--port`를 사용합니다. 이미 사용 중인 포트의 다른 서버에 임의로 연결하지 않습니다. 검사끼리 같은 빌드 폴더를 사용하므로 위 명령은 순서대로 실행합니다. 사용자의 기존 Burp·브라우저·프록시 설정은 변경하지 않습니다.

## 빌드와 배포 파일

공식 확장 빌드 경로는 `dev.py` → `project/burp-development-workspace/burp-workbench-kr/burp-workbench/build_all.py`입니다. 확장 폴더의 과거 Maven POM은 공식 확장 배포 경로가 아닙니다. 실습 사이트 Docker 빌드가 사용하는 Maven 설정과 구분합니다.

| 산출물 | 위치 |
|---|---|
| 개발 중 빌드 JAR | `project/burp-development-workspace/burp-workbench-kr/burp-workbench/dist/` |
| 버전별 공유 JAR·소스 ZIP | `downloads/extensions/<확장 버전>/` |
| 버전별 Docker 실행 ZIP | `downloads/docker/<사이트 버전>/` |
| 버전별 PDF 가이드 | `downloads/guides/<확장 버전>/` |

다운로드 소스 ZIP은 확장 구현과 독립 빌드에 필요한 파일을 묶습니다. 확장 폴더에서 `python build_all.py --test`를 실행할 수 있습니다. 전체 `dev.py check`의 사이트·모의 진단까지 실행하려면 실습 사이트와 `audit/`을 포함한 저장소 전체가 필요합니다.

기존 버전 파일은 덮어쓰지 않습니다. 기능이 바뀌면 확장 또는 사이트 버전을 올리고 `release`로 새 폴더를 생성합니다. 생성한 파일과 소스를 함께 PR에 올립니다. CI는 소스가 바뀌었는데 배포 파일이 갱신되지 않은 경우 무결성 검사에서 실패합니다. 확장과 사이트는 버전을 독립적으로 관리합니다.

## 화면 배치 검사

먼저 `python dev.py test`로 운영 클래스와 테스트 어댑터를 빌드합니다. 그다음 실제 Swing 구성 요소를 서로 다른 크기·테마로 렌더링합니다.

```sh
python tools/render_ui_layout.py --out tmp/ui-layout --check
python tools/render_ui_layout.py --guide-only --out tmp/ui-guide --check
python tools/render_ui_layout.py --scope-only --out tmp/ui-scope --check
```

기본 조건은 확장 작업 영역 1024×720, 1280×800, 1440×920과 밝은/어두운 테마입니다. JS의 대체 배치와 메뉴 숨김·폭 조절·복원, OFF/SCOPE 상태등, 로그 창과 호스트 테마 재설정 후 복원을 포함해 조건별 25개, 총 150개 화면을 렌더링합니다. `controls.csv`는 버튼·입력 크기, `content.csv`는 본문·표의 실제 노출 영역, `text.csv`는 탭·제목의 글자 표시 영역, `layout-report.json`은 잘림·작은 입력·핵심 영역 붕괴 검사 결과입니다. `--check`가 실패하면 해당 PNG와 영역을 확인합니다. 통과한 수치만으로 사용성을 모두 판단하지 않고 그림도 검토합니다.

안내 창은 `--guide-only`로 별도 확인합니다. 실제 설정 창과 같은 구성에서 기본 760×480·최소 560×300, 밝은/어두운 테마, 12개 안내의 총 48개 화면을 검사합니다. 본문 줄바꿈·수평 스크롤·기능 선택·이동 버튼을 확인합니다.

Feature Mapper는 `python feature-mapper/build.py --test` 뒤 `python feature-mapper/tests/render_ui.py --check --out tmp/mapper-ui`로 확인합니다. 같은 크기·테마에서 작업 화면·안내·설정·로그·호스트 테마 복원 30개 화면을 렌더링하고 배포 JAR 해시와 비교합니다.

로컬에서는 이 검사를 `dev.py check`와 별도로 실행합니다. GitHub CI는 `dev.py check` 다음 단계로 같은 화면 배치 게이트를 실행합니다. 이전 JAR와 비교할 때는 `--jar downloads/extensions/0.23.0/burp-workbench-kr-0.23.0.jar`를 지정합니다. [화면·버튼 개선 문서](docs/UI_0.25.0_KR.md)에 재현 방법과 해석 범위를 기록합니다. 합성 데이터와 API 어댑터를 쓰는 헤드리스 렌더링이며 실제 Burp GUI나 운영체제별 DPI 검증이 아닙니다.

## PDF 가이드 재생성

0.25.0용 쉬운 한국어 개정판은 `docs/GUIDE_0.25.0_EASY_KR.md`를 원고로 사용합니다.

```sh
python tools/build_guide.py --edition easy-ko
```

결과는 `downloads/guides/0.25.0-easy-ko/Burp_Workbench_KR_0.25.0_easy_guide.pdf`입니다. 아래의 기존 생성 명령은 개정 전 안내서용입니다. 문서만 고칠 때도 새 개정판 폴더를 사용해 이전 PDF를 보존합니다.

[이전 0.25.0 Markdown 원고](docs/GUIDE_0.25.0_KR.md)와 `docs/guide-assets/0.25.0/`의 검토한 화면을 사용합니다. Python 환경에 `reportlab`이 필요합니다.

```sh
python tools/build_guide.py
```

운영 Swing 코드의 화면도 갱신하려면 먼저 `python dev.py test`로 클래스와 테스트 어댑터를 빌드한 다음 실행합니다.

```sh
python tools/build_guide.py --refresh-previews
```

결과는 `downloads/guides/0.25.0/Burp_Workbench_KR_0.25.0_guide.pdf`입니다. 생성 후 페이지를 렌더해 글꼴·잘림·그림·내용을 확인하고 배포합니다. 화면은 합성 데이터를 넣은 운영 Swing 구성 요소의 헤드리스 미리보기이며 실제 Burp를 직접 조작한 캡처가 아닙니다. 이미 배포한 버전의 PDF를 수정할 때도 새 버전으로 관리합니다.

## 현재 검증과 이전 기록 구분

자동 검사는 Java 구현·API 어댑터·헤드리스 Swing, 격리된 Spring/Tomcat/H2 HTTP 서버를 사용합니다. `check` 통과가 실제 Burp 설치·GUI 조작·브라우저 JS 실행·전체 Docker/Oracle/Nginx 스택·실제 Pro Collaborator·외부 BApp의 실행 완료를 뜻하지는 않습니다.

실제 Burp 검증은 별도 설치와 임시 프로필로 구성합니다. `validation/native/`와 `reproduction/`의 이전 하네스를 사용할 때는 Burp·Java·프로젝트 경로와 포트, 종료 처리를 현재 환경에 맞춥니다. 이전 Windows 절대 경로 런처를 그대로 실행하지 않습니다.

이번 검사 수와 실패·재실행·미검증 범위는 [검증 기록](docs/VERIFICATION_0.37.4.json)을 기준으로 읽습니다. `history/`, `release/`, `setup/` 및 [이전 개발 환경 문서](docs/archive/DEVELOPMENT_SETUP_0.21.0_KR.md)의 통과 수를 이번 결과에 합산하지 않습니다.

## GitHub에 변경 전달

1. 최신 `main`과 공통 지침, 현재 PR 상태를 확인합니다.
2. 담당 계정의 새 작업 브랜치에서 변경합니다. 기존 미병합 작업을 이어받았다면 출발 커밋과 관계를 PR에 기록합니다.
3. 적절한 검사를 실행하고 필요한 버전별 JAR·Docker ZIP·가이드를 갱신합니다.
4. 변경 전후 동작, 실제 검증 결과, 남은 환경 제약을 PR과 인수인계에 적습니다.
5. 검토와 CI를 확인한 뒤 합칩니다. `PROJECT_STATUS_KR.md`에는 합쳐진 상태만 기록합니다.

GitHub 연결이 로컬 터미널의 `git push` 인증을 자동으로 제공하지는 않습니다. 제공된 GitHub 도구 또는 인증된 체크아웃을 사용하며 자격 증명을 소스·로그·ZIP에 넣지 않습니다.

## 0.25.4 추가 확인

최신 PDF는 `python tools/build_readable_guide.py`로 생성합니다. 이전 안내서는 덮어쓰지 않습니다. `audit/0.25.4/run_native.py`는 별도 설치한 Burp Community와 Java 21+를 사용하고 임시 프로필을 생성·삭제합니다. 공식 Scope API와 실제 loopback HTTP 검증이며 GUI 클릭 검증은 아닙니다. `--soak-seconds 300`으로 5분 수집도 확인할 수 있습니다.

날짜별 작업 대화는 `docs/chat-logs/`에 함께 기록합니다. Docker 전체 검증은 GitHub Actions의 **Isolated Docker lab verification** 또는 실습 폴더의 `verify-stack.sh`를 사용합니다. 외부 BApp/Pro Collaborator 검증은 별도 환경이 필요합니다.


## 수집 내구성·디스크 고갈·Windows 추가 검증

[audit/durability](audit/durability/README_KR.md)의 추가 하네스와 **Capture durability and Windows verification** workflow를 사용합니다. 먼저 확장 폴더에서 `python build_all.py --test`를 통과한 뒤 저장소 루트에서 실행합니다.

```sh
python audit/durability/run_capture.py --seconds 180 --output tmp/durability/capture.json
```

이 하네스는 Montoya 어댑터와 실제 운영 콜백·Swing 타이머·디스크 보관을 사용합니다. 기존 Burp 프로필·브라우저·프록시 설정을 변경하지 않습니다. 실제 ENOSPC 검사는 workflow가 만든 별도 16 MiB tmpfs 안에서만 실행하며, 하네스도 파일시스템 종류·전체 용량을 먼저 확인합니다. 사용 중인 디스크를 채우는 방식으로 실행하지 마세요.

workflow 수동 실행의 7200·10800초 옵션은 2·3시간 어댑터 수집입니다. Windows job은 JDK 17 회귀·3분 수집 및 Windows PowerShell 5.1 시작 스크립트의 제어된 명령 실행을 포함합니다. 실제 Docker Desktop 전체 실행, native Burp GUI·다시간 native 수집은 이 결과와 구분합니다. [추가 검증 기록](docs/VERIFICATION_DURABILITY_2026-10-15.json)을 확인하세요.

## 0.26.0 성능·버전 관리

각 확장의 BuildInfo.java가 릴리스 버전의 기준입니다. Python 빌드·HTTP·화면 하네스와 패키징은 release_info.py를 통해 같은 값을 읽습니다. 증거 schemaVersion은 별도 관리합니다. dev.py check/release는 Mapper Java·Node 회귀도 포함하며 실제 검사 건수는 build/check-summary.json에 저장합니다. [성능 비교 실행](audit/performance/README_KR.md)은 전용 출력 디렉터리를 사용합니다.

0.37.0은 트래픽 표의 24px 행·13px 나눔고딕을 유지하고 기본 패킷 보기를 Pretty·Raw·Hex·Search가 있는 운영 PacketInspector로 표시합니다. ⋯ 버튼은 READ_ONLY Burp 메시지 편집기의 실제 uiComponent를 별도 창에 호스팅합니다. 네이티브 편집기 내부는 Workbench 테마 재적용 대상에서 제외합니다.

## 0.37.0 패킷 UI 검사

요청·응답 목록의 기본 보기는 운영 PacketInspector입니다. Pretty·Raw·Hex·구문 강조·Search를 직접 렌더링하고 ⋯ 버튼으로 Burp 기본 편집기를 별도 창에서 엽니다. 다음 명령은 합성 HTTP를 넣어 두 크기·두 테마·세 보기의 12개 운영 화면을 검사합니다.

```sh
python tools/render_ui_layout.py --packet-only --sizes 1024x720 1280x800 --check --out tmp/ui-packet
```

큰 패킷의 원본은 그대로 보관하며 미리보기는 앞 64 KiB를 복사합니다. 검색은 백그라운드·취소 세대·정규식 시간 예산을 사용합니다. 실제 Burp GUI·DPI는 별도 확인 범위입니다.

## 0.37.0 JS·텍스트 분석 창 검사

```sh
python tools/render_ui_layout.py --js-only --sizes 1024x720 1280x800 --check --out tmp/ui-js
```

밝은/어두운 테마의 운영 화면·분석 OFF 상태와 분석 목록·패킷 창의 기본 760×480·최소 560×300을 확인합니다. 합성 출처 패킷을 사용하며 화면 렌더링 자체는 HTTP 요청이나 실제 Burp GUI 조작을 하지 않습니다. 새 Repeater 창의 기본 1120×720·최소 780×440도 포함합니다. JS 결과의 단축키·정렬된 행 연결·팝업 스냅샷·분석 중지/재개와 JSON/XML/HTML/일반 텍스트 추출은 새 회귀 두 그룹에서 검사합니다.

## 0.37.0 요청 편집·전송 검사

`JsRepeaterWorkflowTest`는 별도 loopback HTTP 서버를 만들고 Ctrl+R 창의 Raw 편집, Pretty/Hex 전환 후 보존, Send의 실제 쿼리·UTF-8 본문, 요청/응답 기록과 Scope 차단을 검사합니다. `dev.py check/release`에 포함되며 외부 서버·사용자 Burp/브라우저 설정은 사용하지 않습니다. 화면 생성은 같은 클래스의 합성 패킷을 사용하고 HTTP 전송은 하지 않습니다.

## 0.37.0 쿠키·세션 검사

`CookieSessionProbeTest`는 실제 loopback 서버로 쿠키 단독·조합·대체 인증, 만료/잘못된 신원, 공개 API 대조, Burp 전송 변경·SCOPE 변경·취소·출력 가림과 계정 비교 전 차단을 검사합니다.

```sh
python tools/render_ui_layout.py --session-only --check --out tmp/ui-session
```

두 테마에서 기본 1024×720·최소 760×480의 운영 검사 창과 두 결과 탭을 렌더링합니다. 합성 자료를 쓰며 HTTP를 전송하지 않습니다.

## 사이트 3.6.0 역할별 포털 검사

`python tools/check_role_portals.py`는 현재 사이트를 별도 loopback 포트에 컴파일·실행하고 admin·alice·bob·moderator·비로그인으로 페이지·API·객체 소유권·CSRF·상태 변경을 검사합니다. 기본 포트는 18736이며 `--port`와 `--out`으로 변경할 수 있습니다. 실행 후 소유한 서버를 종료합니다. 브라우저 DOM·Burp GUI·Docker 실행 결과와 구분합니다.

## 0.37.1 JS 분석 가독성 개선

전체 집계의 이름 13px·굵은 숫자 16px, 행 높이 40px와 내부 여백을 적용했습니다. 결과 탭·분석 목록·분석 상세의 주요 경로/민감한 정보 명칭을 맞추고 클라우드 탭을 집계와 통일했습니다. 두 테마와 1024×720·1280×800에서 큰 집계(50,000)까지 운영 Swing 레이아웃 검사에 포함합니다. [변경 안내](docs/RELEASE_0.37.1_KR.md)와 [이번 검증](docs/VERIFICATION_0.37.1.json)을 참고하세요. 앞의 0.37.0 절은 기능 도입 시점의 설명입니다.

## 0.37.2 Repeater 전송 확인과 창 조절

첨부 재현 화면의 REPLACE_METHOD는 전송 전 차단 사유입니다. Repeater에서 실제 HTTP 메서드를 선택·적용한 뒤 Send 또는 Ctrl+Space로 전송합니다. 미확정 경로·값과 SCOPE 검증은 계속 적용합니다. 창 안에 포커스가 있을 때만 동작하는 키 이벤트 처리와 최대화·이전 크기 버튼을 추가했습니다. [변경·확인 순서](docs/RELEASE_0.37.2_KR.md)와 [이번 실행 검증](docs/VERIFICATION_0.37.2.json)을 참고하세요.

## 사이트 3.7.0 통합 커뮤니티 검사

`python tools/check_role_portals.py`가 현재 통합 페이지·신고·강의·계정 관리와 취약/수정 API를 실세션으로 검사합니다. 내부 `community_workspace_checks.py`는 별도 CLI가 아니라 같은 H2 서버에 붙는 수락 기준입니다. `/admin`, `/user`는 통합 페이지 별칭이며 관리자도 공통 요청·강의 신청을 사용합니다. [사용법](docs/LAB_3.7.0_KR.md)을 확인하세요.

## 0.37.3 메서드 자동 선택과 기본 창 버튼

JS의 확정된 메서드를 우선하고, 미확정 항목은 활동 추적의 실제 관찰 메타데이터에 같은 Origin·경로의 메서드가 하나만 있을 때 자동 선택합니다. 발견만 한 항목·충돌·불완전한 관찰 메타데이터는 수동 선택으로 남깁니다. 기존 본문 최대화·이전 크기를 제거하고 OS 제목 표시줄의 표준 □/X를 쓰는 JFrame으로 변경했습니다. Windows CI의 별도 headful 창 검사는 Burp를 실행하지 않고 표준 프레임의 최대화·복원·닫기를 검사합니다. 해당 실행 여부는 이번 PR과 [검증 기록](docs/VERIFICATION_0.37.3.json)을 구분해 확인하세요.

## 사이트 3.7.1 단독 로그인 검사

`python tools/check_role_portals.py`는 단독 로그인·유형별 연결·실제 권한·세션 교체와 기존 통합 기능을 검사합니다. `node --test tools/test_community_login.mjs`는 배포 스크립트의 폼·오류·계정 전환·회원가입 흐름을 DOM 어댑터로 실행합니다. [사용법](docs/LAB_3.7.1_KR.md) · [검증](docs/VERIFICATION_LAB_3.7.1.json).


## 0.37.4 미확정 메서드 기본 GET

JS에서 확인한 메서드 → 같은 Origin·경로의 단일 관찰 메서드 → GET 기본값 순서로 선택합니다. 근거 없음·충돌은 기본값으로 표시하며, 수동 변경과 미확정 경로·값·SCOPE 검증은 유지합니다. Repeater 회귀는 기본 GET과 수동 변경의 실제 loopback 응답, 기본값 표시와 자동 전송 없음도 확인합니다. JS 화면 검사에는 기본 GET 창의 기본·최소 크기와 두 테마를 포함합니다. [현재 사용법](docs/RELEASE_0.37.4_KR.md)과 [이번 검증](docs/VERIFICATION_0.37.4.json)을 확인하세요.

## 사이트 3.8.0 권한·실제 브라우저 검사

`python tools/check_role_portals.py`는 공통 RBAC·객체 권한·미선언 정책 기본 거부·권한 변경·접근 기록과 기존 실습을 검사합니다. 별도 `python tools/check_community_ui.py`는 현재 소스를 컴파일하고 독립 H2에서 Chromium으로 실제 UI 흐름과 1440/1024/390px 레이아웃을 확인합니다. Playwright 설치·실행 설정은 [3.8.0 사용법](docs/LAB_3.8.0_KR.md), 실행 결과는 [검증 기록](docs/VERIFICATION_LAB_3.8.0.json)을 확인하세요.

## 0.38.0 계정 비교 응답 판정

`AuthResponseWorkflowTest`는 인증값 교체·동일 본문·200 차단 응답·문자열/정규식·입력 제한·정규식 예산·기대 권한 충돌·동일 경로 JSON OR·잘못된 기준·설정 스냅샷·정렬된 행의 색상·탭 아이콘을 검사합니다. 실제 서버 응답은 기존 격리 HTTP 묶음에서 별도로 검사합니다.

```sh
python tools/render_ui_layout.py --auth-only --sizes 1024x720 1280x800 --check --out tmp/ui-auth
```

전체 빌드/회귀 종료 후 순서대로 실행하세요. 회귀 빌드 중 테스트 클래스 디렉터리가 초기화되므로 동시에 실행하지 않습니다. 두 테마에서 결과 화면·설정 상단·설정 하단 12개 화면을 생성합니다. 스크롤 설정의 화면 밖 제목과 경계에 걸친 제목은 선택된 제목으로 세지 않으며, 하단을 별도로 렌더링해 새 규칙 영역의 실제 노출을 확인합니다.

## 0.39.0 계정 비교 화면

인증 탐지·요청 비교를 각각 ON/OFF로 제어합니다. 사용자 매핑과 상세 창, Ctrl+Space 전달은 [변경 안내](docs/RELEASE_0.39.0_KR.md)를 확인하세요. 인증 탐지는 Scope와 독립하며 실제 비교 요청은 기존 범위 검사를 유지합니다.

## 인증 조합·계정 권한 비교 검사

`python tools/check_auth_lab.py --mode adapter`는 격리된 H2 서버와 운영 비교 컨트롤러를 사용합니다. `python tools/check_community_ui.py --auth-lab`은 일곱 로그인 페이지와 실제 브라우저 조작을 검사합니다. `--auth-smoke`는 Docker·Oracle 검증에서 사용하는 동일한 인증 경계 대조를 H2에서 실행합니다.

실제 Burp 검사는 `check_auth_lab.py --mode native --burp-jar <별도 Burp JAR> --burp-java <Java 21 이상> --accept-community-license`로 실행합니다. 임시 프로필·별도 DB·loopback 서버를 만들며 기존 Burp·브라우저·프록시를 변경하지 않습니다. `--browser`는 native 검사 후 웹 페이지도 검증합니다. 브라우저 런타임은 위의 기존 Community UI 설정을 사용합니다. 보고서는 `tools/build_auth_lab_report.py`가 저장된 성공 근거를 읽어 생성합니다.
