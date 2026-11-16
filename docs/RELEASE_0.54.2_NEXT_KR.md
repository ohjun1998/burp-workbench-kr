# Workbench Next 0.54.2 · 구독 CLI 호환성 수정과 이미지 가이드

현재 상태는 **전체 CI 검증 완료·PR 검토 대기**입니다. [PR #112](https://github.com/ohjun1998/burp-workbench-kr/pull/112)의 운영 소스 `44d267489864a3bd8f0d12f653d804bd52d2b7eb`에서 전체 5,199개 검사·화면·패키징 및 Windows/Linux 내구성 검사가 통과했습니다. main 병합을 뜻하지 않습니다. 이후 가이드·점검 도구 정리는 배포 입력 소스 399개를 바꾸지 않습니다.

0.54.1의 실제 연결 검사에서 Codex CLI 0.169.0이 `tools.view_image=false`를 인식하지 않아 strict-config 설정 로딩 단계에서 구독 분석이 중단되는 문제를 발견했습니다. 0.54.2는 해당 미지원 옵션을 제거합니다. 기존 `features.view_image=false`와 명령·브라우저·연결 도구 비활성화, 구독 로그인 확인, 읽기 전용·비영구 세션, 원본 ID·SCOPE·가림·취소 및 자동 전송 확인을 유지합니다.

0.54.1과 이전 버전은 덮어쓰지 않습니다. 사용자가 적용할 새 버전은 **0.54.2**이며 Custom AI Agent 내장 0.56.0 후보 및 Simple과는 별도입니다.

[단계별 이미지 가이드](guides/next-0.54.2/index.html) · [설치 JAR](../downloads/extensions/0.54.2/burp-workbench-kr-0.54.2.jar) · [검증 범위와 제한](reports/2026-11-16-0542-user-guide/README_KR.md) · [작업 브랜치](https://github.com/ohjun1998/burp-workbench-kr/tree/account-a/next-0542-runtime-guide)

## 설치와 첫 사용

1. 기존 JAR·Burp 프로젝트·설정을 보관하고, 이전 Workbench 확장을 언로드합니다.
2. Burp의 **Extensions → Installed → Add → Java**에서 `burp-workbench-kr-0.54.2.jar`를 로드하고 화면 버전을 확인합니다.
3. Workbench **SCOPE 목록**에서 허가된 범위를 확인하고 **SCOPE** 수집을 켭니다. 사이트 기능 하나를 직접 사용해 원본 요청·응답을 모읍니다.
4. **재현·증거 정리 → AI 검토 → AI 설정**에서 사용할 실행 경로를 설정합니다. 같은 PC의 Halogen 기본 주소는 `http://127.0.0.1:8731/v1`, 모델은 `halogen-qwen3.8-flash-next`입니다. 실제 모델 목록과 다르면 수정합니다.
5. 구독 경로는 공식 Codex CLI의 ChatGPT 로그인을 사용합니다. `codex login status`를 확인하고 실행 파일에 `codex` 또는 절대 경로를 지정합니다. **구독 로그인 확인** 성공은 추론 성공과 별도입니다.
6. 원본 1~24개를 선택해 **AI 보안 검토 / 기능별 분류**로 준비하고 기대 정책·기능 설명을 입력합니다. **전송 내용 미리보기**에서 경로와 가림 내용을 읽은 뒤 **AI 분석**을 실행합니다.
7. 제안을 **원본 위치**로 대조합니다. **원본 → Repeater**는 준비만 수행하며 Send는 직접 실행합니다. 검토한 제안은 **선택 제안 → 증거**, 보고서는 **AI 초안 → 보고서 초안 추가**로 정리합니다.

작업별 자동 경로는 기능 분류·보고서 초안에 ChatGPT 구독을, 보안·계정 비교·업무 로직·JS 검토에 로컬 Halogen을 사용합니다. 사용자가 경로를 바꿀 수 있으며 오류·거절 시 자동으로 다른 서비스에 보내지 않습니다. AI 분석 자체에는 대상 HTTP 요청이 없고, 별도 자동 검사 요청은 실행 계획을 확인받습니다.

## 실제 PC 연결 점검

```sh
python3 ~/workbench-next-0542/tools/check_next_0542_runtime.py --live --provider both --timeout 180
```

한 경로만 점검하려면 `--provider local` 또는 `--provider subscription`을 지정합니다. 도구는 배포 JAR의 실제 클라이언트를 합성 자료로 호출하고 대상 사이트에는 요청하지 않습니다. 모델 응답 원문이나 인증값은 결과 파일에 저장하지 않습니다. 실제 모델 호출은 해당 서버·구독의 사용량을 사용합니다.

이번 환경에서는 실제 구독 로그인 확인과 미지원 설정 오류가 사라진 것까지 확인했습니다. 다음 CLI 초기화는 읽기 전용 파일시스템에 막혔고 Halogen은 포트 접근 제한으로 연결하지 못했습니다. native Burp GUI와 실제 모델 응답·품질·속도는 미완료이며, 도구 검사만으로 Burp 전체 사용 흐름을 확인했다고 판단하지 않습니다.

후보 JAR SHA-256: `d6332965eb841b839feae2053c76d140bd9fee7f7f2691a5a47711ca958551af`
