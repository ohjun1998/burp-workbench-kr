# Workbench Simple 1.4.13 · Strix 진단 연결

Simple에서 선택한 관찰 자료를 기존 Strix CLI의 진단 입력으로 준비하고, **Halogen + Qwen 3.8** 또는 **Strix 자체 ChatGPT 구독 인증**을 선택합니다. 실행계획을 확인한 뒤 시작하고, 진행 로그·중지·이번 실행 보고서를 같은 창에서 확인합니다.

[단계별 이미지 가이드](guide/index.html) · [설치 JAR](../../../simple/downloads/1.4.13/burp-workbench-simple-1.4.13.jar) · [소스 ZIP](../../../simple/downloads/1.4.13/burp-workbench-simple-1.4.13-source.zip) · [배포 체크섬](../../../simple/downloads/1.4.13/SHA256SUMS) · [사용 안내](../../../simple/docs/RELEASE_SIMPLE_1.4.13_KR.md)

[단일 HTML 다운로드](../../../downloads/guides/simple-1.4.13/Workbench-Simple-1.4.13-Strix-사용가이드.html) · [설치 JAR·가이드 묶음](../../../downloads/guides/simple-1.4.13/Workbench-Simple-1.4.13-Strix-설치와가이드.zip) · [화면 이미지 ZIP](../../../downloads/guides/simple-1.4.13/Workbench-Simple-1.4.13-Strix-화면이미지.zip). 단일 HTML에는 7장 이미지·CSS·JS가 포함되어 오프라인에서 읽을 수 있습니다.

## 사용 순서

1. Burp에서 이전 Simple 확장을 언로드하고 1.4.13 JAR를 로드합니다.
2. 허가된 대상의 원본 1~24개를 선택해 우클릭 **선택 원본으로 Strix 진단 준비**를 누릅니다. 공통 설정의 **Strix 진단 열기**로도 창을 열 수 있습니다.
3. **모델·Strix 설정**에서 제공자와 모델을 확인합니다. 구독은 터미널의 `strix auth login chatgpt`로 별도 인증합니다.
4. 대상·SCOPE·계정 역할·기대 정책·모드·최대 에이전트 턴·시간을 확인하고 **실행 계획 확인·진단 시작**에서 승인합니다.
5. 진행 로그와 중지를 사용하고 **이번 실행 보고서**에서 경로·종료 상태·가림 텍스트 미리보기를 검토합니다.

기존 `~/.strix/bin/strix` 실행 래퍼만 사용합니다. Strix 설치·래퍼·모니터·전역 모델 설정을 교체하는 작업은 포함하지 않습니다. 결과는 Simple 전용 `~/.burp-workbench-simple/strix-runs/run-*/strix_runs/` 아래에 보관합니다. 기존 `~/strix_runs`는 별도입니다.

## 검증한 내용

검증 소스는 `609c252a9543f0e5ef8db800f28e09f147efe62d`, 배포 파일 게시 커밋은 `0656650bd7e9304babb909846d2b325fc0eb3bc4`입니다. [Actions 43874202784](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/43874202784)의 전체 단계가 성공했습니다.

| 검사 | 결과 |
| --- | --- |
| 전체 Simple 검사 | 7,162개 통과: Java 159개 그룹 6,850 · payload HTTP 136 · Mapper Java 147 · Node 29 |
| 새 Strix 회귀 | Client 63 · Integration 26 통과, 위 전체 검사에 포함 |
| 기존 Simple / Scope UI | 각각 84 / 68개 화면, 배치 위반 0 |
| Strix UI | 밝음·어두움 × 1024×720·1280×800 × 7단계 = 28개 화면, 배치 위반 0 |
| 실제 Katana | 격리 fixture 102개 통과, 전체 Simple 수와 별도 |
| 불변 패키징 | JAR·소스 ZIP·helper 생성 및 manifest·체크섬 검증 통과 |
| 로컬 최종 JAR | GitHub 공개 파일의 SHA-256 일치, 같은 JAR로 코어·UI 통합 89개와 UI 28개 재검사 통과 |

JAR SHA-256: `a61e81a4f4131f4226a8057d3b7974678c4941856370fdf1b79dbe470d374fd2`.

전체 결과는 [candidate-check.json](candidate-check.json), 최종 JAR 화면 검사는 [ui-release/report.json](ui-release/report.json)에 기록합니다. 가이드의 7장은 이 최종 JAR의 실제 패널을 **합성 원본과 가짜 Runner로 만든 캡처**입니다. 실제 사용자 Burp나 실제 진단 성공 화면이 아닙니다.

## 실제 실행의 제한

실제 사용자 Burp 로드, Halogen 연결·추론, Strix OAuth 로그인 완료와 실제 대상 진단은 이번 자동 검증에 포함하지 않았습니다. [읽기 전용 로컬 검사](local-preflight.json)에서는 `openai/halogen-qwen3.8-flash-next` · `http://127.0.0.1:8731/v1` · `chat_completions` 설정을 확인했고, 2026-11-16 22:25(KST) Strix 구독 인증은 미로그인이었습니다. 설정·로그인 상태 검사는 실제 모델 추론과 구분합니다. Strix 자체의 ChatGPT 연동은 OpenAI 공식 지원 구독 API가 아니며 Codex CLI 인증과 별개입니다.

최대 에이전트 턴은 HTTP 요청 횟수가 아닙니다. 이 CLI 연결에는 강제 HTTP 요청 예산이 없고, 실행 전 SCOPE 검사와 지시문은 Strix의 개별 요청을 자동으로 통제하는 네트워크 격리가 아닙니다. 가용성 영향 검사가 필요하면 중단하고 추가 확인을 요청하도록 지시하지만 무영향을 보장하지 않습니다.

중지는 이 실행이 소유한 프로세스에 한정합니다. 이미 서버에서 시작한 작업은 되돌리지 않으며 별도 Docker 컨테이너 정리는 미확인으로 표시합니다. 다른 진단 프로세스나 컨테이너를 일괄 종료하지 않습니다. 모델 오류·거절·한도 초과 때 다른 제공자로 자동 전환하지 않습니다.

원본 인증값·본문을 자동 전달하지 않습니다. 따라서 선택한 Burp 세션이 Strix 진단의 로그인 상태로 이어지지 않습니다. 미리보기는 이번 실행 소유의 128 KiB 이하 텍스트 보고서에 한정하며, 큰 파일과 과거 실행은 저장 폴더에서 확인합니다.
