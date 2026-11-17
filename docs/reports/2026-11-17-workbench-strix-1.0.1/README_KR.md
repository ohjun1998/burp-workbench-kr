# Workbench Strix 1.0.1 · Simple 1.4.21 디자인 유지

**Simple 1.4.21의 배포 소스와 기존 디자인·레이아웃을 기준으로 Strix 진단 에이전트 연결 기능을 추가합니다.** 기준 커밋은 `03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d`입니다. 제품 이름은 Workbench Strix로 유지하며, 이전 Workbench Strix 1.0.0과 기존 Simple 배포 파일은 보존합니다. Simple 1.4.22의 A 그룹형이나 기능 중심 HTML 시안은 이 업데이트의 대상이 아닙니다.

[단계별 그림 가이드](guide/index.html) · [사용 안내](USAGE_KR.md) · [설치 JAR](../../../simple/downloads/strix/1.0.1/burp-workbench-strix-1.0.1.jar) · [독립 소스 ZIP](../../../simple/downloads/strix/1.0.1/burp-workbench-strix-1.0.1-source.zip) · [체크섬](../../../simple/downloads/strix/1.0.1/SHA256SUMS)

[단일 HTML 가이드](../../../downloads/guides/strix-1.0.1/Workbench-Strix-1.0.1-사용가이드.html) · [설치와 가이드 ZIP](../../../downloads/guides/strix-1.0.1/Workbench-Strix-1.0.1-설치와가이드.zip) · [그림 가이드 이미지 ZIP](../../../downloads/guides/strix-1.0.1/Workbench-Strix-1.0.1-화면이미지.zip)

## 이번 업데이트의 범위

| 항목 | 적용 기준 |
| --- | --- |
| 기존 메인 화면 | Simple 1.4.21의 디자인·레이아웃 |
| 왼쪽 강조 메뉴 | 통합 점검·대시보드의 고정 42px 행 |
| 기존 메뉴·도구 | 원래 경로·배치·원본·작성 내용 유지 |
| Strix 진입점 | 선택 원본의 우클릭 메뉴 및 공통 설정 |
| Strix 작업 | 별도 창에서 모델·진단 조건·실행계획·로그·중지·보고서 처리 |
| 제품 이름·버전 | Workbench Strix 1.0.1 |
| 저장 호환 | 기존 `simple.*` 및 `.burp-workbench-simple` 유지 |

Strix 연결부는 기존 `~/.strix/bin/strix` 실행 래퍼를 사용합니다. Halogen + Qwen 3.8 또는 Strix 자체 ChatGPT 구독 인증 경로를 실행마다 선택하며 전역 설정·인증 파일·설치 래퍼는 변경하지 않습니다. 원본 본문·쿠키·토큰은 자동 전달하지 않습니다.

이전 Simple 또는 Workbench Strix 확장을 언로드한 뒤 새 JAR를 로드합니다. 기존 설정과 진단 기록은 유지하므로 일반 Simple과 동시에 로드하지 않습니다.

## 진단 흐름

1. 현재 프로젝트의 원본 1~24개를 선택하고 우클릭 **선택 원본으로 Strix 진단 준비**를 누릅니다. 공통 설정의 **Strix 진단 열기**로도 별도 창에 진입합니다.
2. **모델·Strix 설정**에서 제공자·모델을 확인하고 대상·SCOPE·기대 정책·모드·최대 에이전트 턴·시간 상한을 확인합니다.
3. 실행계획 확인창에서 승인해야 시작합니다. 준비·창 열기·모델 선택만으로 진단을 실행하지 않습니다.
4. 진행 로그·중지와 이번 실행 보고서를 검토합니다. 보고서는 기존 호환 경로 `~/.burp-workbench-simple/strix-runs/run-*/strix_runs/` 아래에 저장합니다.

## 실제 창 캡처

Simple 1.4.21 기준 JAR와 새 배포 JAR를 동일한 합성 자료·라이트/다크 테마·1600×1000 및1280×800 창으로 실행했습니다. 메인·대시보드의 이름 있는 컨트롤 배치 비교464항목에서 누락·추가·좌표/크기 차이가0개였고 통합 점검·대시보드 강조 행은 모두42px를 유지했습니다. 이는 해당 화면의 배치 일치이며 제목·버전·시각 등이 달라 픽셀 동일을 뜻하지 않습니다.

실제 우클릭 메뉴에서 Strix 진단 준비 항목을 Robot으로 클릭해 선택한 원본으로 별도 창이 열리는 흐름도 확인했습니다. 실제 창 캡처34개를 보존했습니다.

- [새 메인 · 다크](native/strix/dark/1600x1000/01-main.png) · [새 대시보드](native/strix/dark/1600x1000/02-dashboard.png)
- [Strix 진단 준비](native/strix/dark/1600x1000/03-strix-prepare.png) · [모델 선택](native/strix/dark/1600x1000/04-strix-model.png)
- [실제 우클릭 진입](native/popup-flow/strix/dark/1600x1000/06-strix-popup.png)
- [원본 Simple1.4.21 메인](native/baseline-simple-1.4.21/dark/1600x1000/01-main.png) · [464항목 비교](native/native-comparison.json) · [원본 PNG 검증](native/png-verification.json)
- [실제 창 캡처 ZIP](../../../downloads/guides/strix-1.0.1/Workbench-Strix-1.0.1-실제창캡처.zip)

기존 디자인 소스와 자산280개가 기준 커밋과 바이트단위 동일합니다. 변경된 런타임7파일은 제품명·Strix 연결부이며 Strix2파일이 추가되었습니다. [전체 소스 비교](base-source-preservation.json).


이 캡처는 실제 Xorg/창 관리자에서 구동한 production UI의 화면 픽셀을 얻는 방식입니다. 현재 환경에서는 Burp 본체 대신 테스트 API 어댑터와 합성 패킷을 사용하므로 **실제 사용자 Burp에 확장을 로드한 캡처는 아닙니다.** 캡처를 위해 실제 Strix·모델·진단 대상에 요청하지 않습니다.

그림 가이드의 7단계 이미지는 같은 배포 JAR의 production 패널을 가짜 Runner로 렌더링한 별도 자료입니다. 로그인·진행·중지·완료·보고서 상태는 합성 예시이며 실제 진단 성공을 뜻하지 않습니다.

## 이번 작업의 검증 상태

JDK17.0.21 / `--release 17`로 최종 전체 검사를 완료했습니다. 제품 중지 코드는 유지하며, 이 클라우드PID1의 가짜 자식 회수를 위해 테스트 명령 부모에만 기존 subreaper를 사용했습니다.

| 이번 검사 | 결과 |
| --- | --- |
| Workbench 회귀 | 180그룹 · 7,879개 통과 |
| payload loopback HTTP | 136개 통과 |
| Mapper Java | 147개 통과 |
| helper Node | 29개 통과 |
| 전체 자동 검사 | 8,191개 통과 |
| Strix 연결부 | Client63 · Integration26, 위 전체 수에 포함 |
| headless production 패널 | 기존 작업104 · 통합 점검72 · Strix28, 배치 위반0 |
| 실제 Linux 창 | 34개 PNG · 메인/대시보드 배치464항목 차이0 ·42px행 유지 |
| 그림 가이드 브라우저 | 10개 그룹 ·7개 이미지 일치 ·390/1024/1280px ·JS 오류0·외부 요청0 |
| 독립 소스 ZIP 빌드 | 배포 JAR와 SHA-256 동일 |
| 패키지 | 소스 지문·manifest·ZIP CRC·체크섬 통과 |

전체/집중/화면 검사 수를 고유 검사 수로 합산하지 않습니다. 첫 전체 실행의 검색 준비 시험 실패와 준비 단계에 gate를 고정한 시험 보정은 [이전 시도](prior-attempts/readiness/README_KR.md)에 구분했습니다. 제품 TrafficBrowser는 원본 그대로이며, 보정한 시험은 원본1.4.21과 새 Strix에서 각각18개를 통과했습니다. 최종 통과 수는 보정 후 전체 재실행의 수치입니다.

JAR SHA-256: `1d2bf029b5ea50f91a25459afed4d06acaeff2d8fd4f7e2a43b8feb595a645cf`. [전체 결과](check-summary.json) · [독립 소스 빌드](independent-source-verification.json) · [이전 배포 보존](prior-preservation.json) · [가이드 검증](browser-guide/verification.json).

보고서 CSV 줄끝은LF로, 복사 로그의 줄끝 공백만 정규화했습니다. 검사 값·원본 PNG·배포 JAR는 바꾸지 않았습니다. 로컬 재현은 `python simple/dev.py release`와 `python simple/dev.py verify`이며 적절한 init이 없는 동일 클라우드에서는 기존 외부 subreaper로 release 명령을 감쌌습니다. 실제 사용자 Burp/Windows DPI·IME·실제 모델 추론은 이번 자동 검사에 포함하지 않았습니다.


## 제한

최대 에이전트 턴은 HTTP 요청 예산이 아닙니다. 실행 전 SCOPE·원본 검사는 전체 Strix 네트워크 통신을 격리하는 장치가 아니며, 전달한 지시문은 요청 수나 가용성 무영향을 기술적으로 보장하지 않습니다. 실행계획은 매 실행마다 기본 취소인 확인을 거칩니다.

Strix 구독 인증은 Codex CLI 로그인과 별개입니다. Strix 자체 OAuth를 OpenAI 공식 지원 구독 API로 설명하지 않습니다. 오류·거절·한도 초과 때 다른 제공자로 자동 전환하지 않습니다.

이번 업데이트의 자동 검사·합성 화면은 실제 모델 추론·사용자 Burp·실제 대상 진단 성공이나 별도 Docker 컨테이너의 완전한 종료를 입증하지 않습니다. 중지는 이 실행이 소유한 프로세스에 한정하며 이미 서버에서 시작한 작업을 되돌리지 않습니다.
