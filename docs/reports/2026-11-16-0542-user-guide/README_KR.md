# Next 0.54.2 사용자 가이드·실행환경 점검

**수정 후보·전체 CI 검증 중.** 이 문서는 이번 로컬 작업의 실제 결과와 제한을 기록합니다. 이전 0.54.1의 전체 5,195개 통과 기록은 과거 기준이며 0.54.2의 새로운 전체 검사 결과로 합산하지 않습니다.

실환경 확인 중 0.54.1의 Codex 구독 실행이 설치 CLI 0.169.0의 미지원 설정 `tools.view_image`에서 중단됐습니다. 0.54.2에서 그 옵션을 제거한 뒤 동일한 CLI 명령이 설정 로딩을 지나 초기화에 도달함을 확인했습니다. 이후 단계는 이 관리 환경의 읽기 전용 파일시스템 오류로 중단됐고 모델 응답은 받지 못했습니다. 인증·네트워크 문제가 해결됐거나 모델 추론까지 성공했다고 주장하지 않습니다.

## 이번에 확인한 결과

| 항목 | 실제 결과 | 근거 |
|---|---|---|
| 후보 JAR | 0.54.2 버전·SHA-256·ZIP CRC 확인 | [runtime-check.json](runtime-check.json) |
| 구독 클라이언트 회귀 | 98개 통과 | [CodexSubscriptionClientTest.txt](CodexSubscriptionClientTest.txt) |
| 자동 실행 승인 회귀 | 53개 통과 | [ActiveRequestApprovalTest.txt](ActiveRequestApprovalTest.txt) |
| 새 배포 JAR의 실제 AI UI | 밝음/어두움·1024×720/1280×800의 총 28개 합성 화면, 배치 위반 0 | [UI 보고서](ui-isolated/report.json) |
| UI 렌더링 중 전송 | 모델·대상 호출 0회 | [UI 보고서](ui-isolated/report.json) |
| 운영 클라이언트의 구독 로그인 | ChatGPT 인증 확인 성공, API 키 대체 없음 | [runtime-check.json](runtime-check.json) |
| 수정된 구독 CLI 실행 | 미지원 설정 오류 없음, 읽기 전용 파일시스템에서 초기화 실패 | [cli-compatibility.json](cli-compatibility.json) |
| Halogen 연결·추론 | 포트 접근 제한으로 미완료 | [runtime-check.json](runtime-check.json) |
| 사용자 native Burp | 데스크톱 도구 접근 불가, 미완료 | 이 문서의 제한 설명 |

후보 JAR SHA-256:

```text
d6332965eb841b839feae2053c76d140bd9fee7f7f2691a5a47711ca958551af
```

## 제공한 가이드와 도구

- [오프라인 이미지 가이드](../../guides/next-0.54.2/index.html): 9단계, 실제 운영 UI의 합성 이미지 7장, 목차·이미지 확대·진행 체크·모바일·인쇄 지원.
- [짧은 사용 안내](../../RELEASE_0.54.2_NEXT_KR.md): 설치·실행 경로·첫 분석 순서.
- `tools/check_next_0542_runtime.py` 및 `tools/Next0542RuntimeProof.java`: 실제 배포 클라이언트를 사용하는 점검 도구. 합성 자료만 입력하며 대상 HTTP는 0회입니다.

HTML은 외부 CSS·JS·폰트·이미지 CDN을 사용하지 않습니다. 자체 스크립트는 목차·진행 체크·이미지 확대·명령 복사·인쇄만 처리하며 외부 fetch나 모델 호출을 하지 않습니다. 외부 문서·GitHub 링크는 사용자가 선택했을 때 열립니다.

## 실제 PC에서 이어서 확인

```sh
python3 ~/workbench-next-0542/tools/check_next_0542_runtime.py --live --provider both --timeout 180
```

1. Burp에서 이전 확장을 언로드하고 0.54.2 JAR를 로드해 버전·오류를 확인합니다.
2. 실제 사용할 경로로 합성 모델 분석을 완료하고 점검 결과 JSON을 확인합니다.
3. 허가된 원본 한 개를 골라 준비 → 가림 미리보기 → 분석 → 원본 위치를 확인합니다.
4. Repeater 준비에 원본·인증값이 유지되는지 확인하고 증거·초안 추가 동작을 검토합니다.

이미지는 사용자 Burp의 실사용 캡처가 아닙니다. 독립 AI 패널을 합성 Burp API·자료로 렌더링한 결과이며 full Workbench/native Burp 검증을 뜻하지 않습니다. 기존 전체 Workbench 하네스의 Mapper 초기화는 loopback 소켓 권한에서 차단되어, 가이드용 독립 패널 하네스로 화면을 만들었습니다. 실제 모델 응답·취약점 분석 정확도·품질·속도와 Windows DPI/IME·외부 대상 검사는 수행하지 못했습니다. 결과 원문·인증값·비공개 패킷은 가이드에 포함하지 않습니다.
