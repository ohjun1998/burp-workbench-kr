# Workbench Strix 1.0.0 · 제품명 분리

기존 Simple 1.4.13 Strix 진단 연동 후보를 **Workbench Strix**라는 이름으로 구분합니다. 일반 Workbench Simple과 다른 설치 파일명·확장명·탭·창 제목·가이드를 사용합니다. 일반 Simple 1.4.22의 A 그룹형 변경이나 기능 중심 HTML 시안은 이 배포판에 합쳐지지 않았습니다.

[단계별 그림 가이드](guide/index.html) · [사용 안내](USAGE_KR.md) · [설치 JAR](../../../simple/downloads/strix/1.0.0/burp-workbench-strix-1.0.0.jar) · [독립 소스 ZIP](../../../simple/downloads/strix/1.0.0/burp-workbench-strix-1.0.0-source.zip) · [체크섬](../../../simple/downloads/strix/1.0.0/SHA256SUMS)

[단일 HTML 가이드](../../../downloads/guides/strix-1.0.0/Workbench-Strix-1.0.0-사용가이드.html) · [설치와 가이드 ZIP](../../../downloads/guides/strix-1.0.0/Workbench-Strix-1.0.0-설치와가이드.zip) · [화면 이미지 ZIP](../../../downloads/guides/strix-1.0.0/Workbench-Strix-1.0.0-화면이미지.zip)

## 이름과 데이터 호환

| 항목 | 이름 변경 전 | 이름 변경 후 |
| --- | --- | --- |
| 제품 이름 | Workbench Simple 1.4.13 Strix 연동 후보 | Workbench Strix 1.0.0 |
| 설치 파일 | burp-workbench-simple-1.4.13.jar | burp-workbench-strix-1.0.0.jar |
| 배포 위치 | simple/downloads/1.4.13 | simple/downloads/strix/1.0.0 |
| 설정 키 | simple.* | 기존 키 유지 |
| 기록 폴더 | .burp-workbench-simple | 기존 폴더 유지 |

이 제품의 버전 1.0.0은 별도 명칭의 시작 번호이며 Simple의 버전 번호와 비교하는 업그레이드 순서가 아닙니다. 이전 연동판을 언로드한 뒤 새 JAR를 로드합니다. 기존 설정·프로젝트·진단 기록을 지우거나 새 경로로 이동하지 않으며 일반 Simple과 동시에 로드하지 않습니다.

## 진단 흐름

1. 현재 프로젝트에서 원본 1~24개를 선택하고 우클릭 **선택 원본으로 Strix 진단 준비**를 누릅니다. 공통 설정의 **Strix 진단 열기**로도 진입합니다.
2. **모델·Strix 설정**에서 Halogen + Qwen 3.8 또는 Strix 자체 ChatGPT 구독 경로를 선택합니다.
3. 범위·기대 정책·모드·턴·시간을 확인하고 실행계획 확인창에서 승인합니다. 선택·준비만으로 실행하지 않습니다.
4. 진행 로그·중지·이번 실행 보고서를 검토합니다.

원래 `~/.strix/bin/strix` 실행 래퍼와 Strix 전역 설정은 변경하지 않습니다. 보고서는 기존 호환 경로 `~/.burp-workbench-simple/strix-runs/run-*/strix_runs/` 아래에 저장합니다. 원본 본문·쿠키·토큰은 자동 전달하지 않습니다.

## 이번 작업의 검증 상태

최종 전체 검사는 JDK 17.0.21 / `--release 17`로 완료했습니다. 이 클라우드에서는 가짜 프로세스 시험의 고아 자식 회수를 위해 테스트 명령의 부모에만 Linux subreaper를 사용했습니다. 제품 프로세스 코드와 중지 판정은 변경하지 않았습니다. 첫 실패와 재검사 기록은 [prior-attempts/](prior-attempts/)에 구분했습니다.

| 검사 | 이번 결과 |
| --- | --- |
| Workbench 회귀 | 159개 그룹 · 6,851개 통과 |
| payload loopback HTTP | 136개 통과 |
| Mapper Java | 147개 통과 |
| 브라우저 helper Node | 29개 통과 |
| 전체 자동 검사 | 7,163개 통과 |
| Strix 연결부 | Client63 + Integration26 통과, 위 전체 수에 포함 |
| 실제 production 패널의 합성 UI 검사 | Strix28 / 기존 작업84 / 통합 점검68, 배치 위반0 |
| 새 그림 가이드 브라우저 | 9개 그룹 통과 · 7장 이미지 일치 · 390/1024/1280px · JS 오류0·외부 요청0 |
| 독립 소스 ZIP 빌드 | 새 JAR와 SHA-256 일치 |
| 배포·가이드 | manifest·소스 지문·ZIP CRC·체크섬 검증 통과 |
| 이전 파일 보존 | 기존 Strix1.4.13 JAR·소스·helper 해시 일치, 이전 가이드·Next 변경0 |

자동 검사와 UI/가이드 검사의 수치를 고유 검사 수로 합산하지 않습니다. 보고서에 보관한 CSV의 줄끝은 LF로, 복사한 로그는 줄끝 공백만 정규화했으며 PNG·JAR·검사 결과 값은 바꾸지 않았습니다. Strix 패널과 가이드는 가짜 Runner 및 합성 자료이며 실제 Strix·모델·대상 호출은0회입니다.

JAR SHA-256: `a50f961d5b558d46d2c34ba0d60e03f1189f1d9f52e149289ba6507c5ea84f96`. [전체 검사](check-summary.json) · [Strix UI](ui-release/report.json) · [작업 UI](workspace-ui/layout-report.json) · [통합 점검 UI](scope-ui/scope-layout-report.json) · [독립 빌드](independent-source-verification.json) · [가이드 검증](browser-guide/verification.json) · [이전 파일 보존](prior-preservation.json).

재현: Linux에서 `support/test-subreaper.c`를 `gcc -O2 ... -o /tmp/strix-test-subreaper`로 빌드한 뒤 `/tmp/strix-test-subreaper python3 simple/dev.py release --java /path/to/jdk17/bin/java`를 사용했습니다. 적절한 init이 자식을 회수하는 일반 개발 환경에서는 기존 `python simple/dev.py release` 명령을 사용합니다. 불변 배포 확인은 `python simple/dev.py verify`입니다. 실사용 Strix의 컨테이너 정리를 검증한 것은 아닙니다.


## 제한

진단 연동의 실행 승인·제공자 선택·SCOPE와 원본 검사·중지 기능은 유지합니다. 최대 에이전트 턴은 HTTP 요청 예산이 아니며, 실행 전 범위 확인과 에이전트 지시문은 전체 네트워크 격리나 무영향 보장이 아닙니다. 구독 경로는 Strix 자체 OAuth이며 Codex CLI 인증과 별도입니다. OpenAI 공식 지원 구독 API로 설명하지 않습니다.

이번 이름 변경 작업은 새 모델 추론·사용자 Burp·실제 대상 진단 성공을 입증하지 않습니다. 그림은 정확한 배포 JAR의 production 패널을 합성 원본·가짜 Runner로 렌더링한 화면이며 실제 CLI·모델·대상 호출을 하지 않습니다. 별도 컨테이너 종료 확인과 이미 대상에서 시작한 작업의 되돌리기는 제공하지 않습니다.

이전 연동판과 기존 Simple 배포·가이드·검증 기록은 보존합니다.
