# Workbench Strix 1.1.0 · 진단 중 대화

현재 진단 세션의 Strix 에이전트에게 후속 질문을 보내고 같은 세션의 대화 기록을 읽는 기능을 추가했습니다. 기존 Simple1.4.21 메인 디자인·13개 도구·저장 위치는 유지합니다. 별도 Strix 창은 대화·로그·보고서·원본·테스트 정책 중 선택한 탭이 중앙을 사용하며, 기존 정책 입력과 미리보기는 네 번째 탭에 유지합니다.

[설치 JAR](../../../simple/downloads/strix/1.1.0/burp-workbench-strix-1.1.0.jar) · [독립 소스 ZIP](../../../simple/downloads/strix/1.1.0/burp-workbench-strix-1.1.0-source.zip) · [체크섬](../../../simple/downloads/strix/1.1.0/SHA256SUMS) · [변경 안내](../../../simple/docs/RELEASE_STRIX_1.1.0_KR.md) · [사용 안내](USAGE_KR.md)

[단일 HTML 가이드](../../../downloads/guides/strix-1.1.0/Workbench-Strix-1.1.0-사용가이드.html) · [설치와 가이드 ZIP](../../../downloads/guides/strix-1.1.0/Workbench-Strix-1.1.0-설치와가이드.zip) · [가이드 이미지 ZIP](../../../downloads/guides/strix-1.1.0/Workbench-Strix-1.1.0-화면이미지.zip)

## 같은 진단 세션으로 연결

기존 `~/.strix/bin/strix` 래퍼의 interactive 실행을 이번 작업이 소유하는 POSIX PTY에 연결합니다. 공식 Strix live viewer의 실행별 인증으로 capability·현재 실행·기록을 확인하고 `POST /api/agents/steer`로 같은 에이전트에 메시지를 전송합니다. Workbench의 대화 HTTP 연결은 이번 실행이 소유한 리터럴 `127.0.0.1` 주소만 사용하며 OAuth 자격 증명을 추출하지 않습니다. Strix 자체의 모델·진단 대상 네트워크 통신은 이 로컬 대화 연결과 별개입니다.

질문마다 독립 LLM이나 새 진단을 생성하지 않습니다. 기본 취소인 전송 확인에서 현재 대상·에이전트·실제 전달할 내용을 검토합니다. `ok:true`로 수락된 전송과 실제 답변 도착을 구분하며 자동 재전송하지 않습니다. 원본·프로젝트·SCOPE·세션이 바뀌면 이전 실행으로 질문하지 않습니다.

화면은 모든 에이전트를 합한 최근 최대200개·128Ki자의 대화 안에서 선택한 에이전트의 메시지를 표시합니다. 원본 Strix 이력은 유지하며 조회 HTTP 응답 한도는4MiB입니다. 초기 진단이 완료된 뒤에도 소유 프로세스가 살아 있고 실행 상한 안이면 후속 질문을 이어갈 수 있습니다. 질문으로 에이전트의 초기 턴 예산이 늘어날 수 있지만 처음 정한 실행 시간 상한은 초기화되지 않습니다.

공식 프로토콜 확인 기준은 usestrix/strix 커밋 `1152d53579d5b3af37ada8c798f197ab093b99c6`입니다. 이 기준의 standalone `strix view`는 읽기 전용이며 비대화형 실행에는 쓰기 가능한 live viewer가 없습니다. 존재하지 않는 `--chat`/`--web-port` 플래그를 사용하지 않습니다. [프로토콜·구현 근거](UPSTREAM_PROTOCOL_KR.md).

## 최종 자동 검사

JDK17.0.21 / `--release 17`로 최종 release를 순서대로 실행하고 불변 배포 verify를 통과했습니다. 실제 전체 결과의 `subset`은 false이며 실패 그룹은 없습니다.

| 검사 | 최종 결과 |
| --- | --- |
| Workbench 회귀 | 182그룹 ·7,988개 통과 |
| payload loopback HTTP | 136개 통과 |
| Mapper Java | 147개 통과 |
| helper Node | 29개 통과 |
| 전체 자동 검사 | **8,300개 통과** |
| 새 대화 Client / Panel | 73개 /36개 통과, 위 전체 수에 포함 |
| 기존 Strix Client / Integration | 63개 /26개 통과, 위 전체 수에 포함 |
| 독립 소스 ZIP 빌드 | 배포 JAR와 바이트 일치 |
| 불변 패키지 검증 | manifest·소스 지문·체크섬·ZIP CRC 통과 |

[전체 검사 집계](check-summary.json) · [그룹별 집계](test-summary.json) · [release 로그](release.log) · [대화 전송 시험](test-SimpleStrixChatClientTest.txt) · [production 패널 시험](test-SimpleStrixChatPanelTest.txt) · [독립 소스 빌드](independent-source-build.json).

저장소 공백 검사를 위해 `release.log`의 행 끝 공백만 제거했습니다. 변경 전 원본은 [압축 로그](release-original.log.gz)와 [체크섬 기록](release-log-provenance.json)에서 확인할 수 있습니다.

대화 시험은 가짜 Strix PTY와 실제 loopback HTTP fixture를 사용합니다. 같은 세션의 주·하위 에이전트, 전송 수락/실패, 인증·URL·세대 검증, 가림 처리와 경계에서 잘리는 비밀값, 승인 취소·SCOPE/프로젝트 변경·중지·늦은 결과 폐기를 확인합니다. 기존 제품 중지 코드는 유지하며 이 환경의 가짜 자식 프로세스 회수에 외부 테스트 subreaper를 사용했습니다.

앞선 release는 최종 상태 문구 수정 중 별도 빌드와 출력 폴더를 공유해 `NoClassDefFoundError`로 실패했습니다. 이 시도는 최종 통과 수에 합산하지 않았으며 [배포 전 재실행 기록](prior-attempts/release-concurrency/README_KR.md)에 구분했습니다. 최종 검사는 단일 release를 완주한 결과입니다.

## 실제 Swing 창과 기존 배치

최종 배포 JAR와 바이트가 같은 제품 클래스를 실제 Xorg/xfwm4 창에 로드하고 Java Robot으로 PNG52개를 캡처했습니다. Simple1.4.21 기준과 메인·대시보드의 이름 있는 컴포넌트464개를 비교해 위치·크기 차이0개, 통합 점검·대시보드 행42px 유지, 모든 대화창 레이아웃 위반0개를 확인했습니다. 두 테마의940×720 /1280×800 /1600×1000에서 질문 입력·전송 버튼과 대화 이력 영역을 확인했습니다.

- [대화 준비](native/chat/dark/1600x1000/01-chat-ready.png) · [주 에이전트 대화](native/chat/dark/1600x1000/02-chat-conversation.png) · [하위 에이전트](native/chat/dark/1600x1000/03-chat-subagent.png) · [전송 확인](native/chat/dark/1600x1000/04-chat-confirm.png)
- [실제 창 상세 검증](native/README_KR.md) · [464항목 비교](native/native-comparison.json) · [불변 JAR 바이트 일치](native/immutable-artifact-match.json) · [가이드 PNG 체크섬](native/guide-captures.json)

합성 Burp API·패킷·대화 세션 어댑터를 사용했습니다. 실제 사용자 Burp에 로드하거나 실제 Strix·모델·대상 진단을 성공한 화면이 아닙니다. 그림에도 합성 예시를 표시했으며 원본 PNG를 수정하지 않았습니다. 대화가 접혔던 초기 레이아웃과 작은 창의67px 이력 실패는 [이전 시도](prior-attempts/native-layout)에 보존했습니다.

## 오프라인 HTML 가이드

최종 JAR 체크섬과 같은 배포의 PNG4장을 검증해 단일 HTML에 포함했습니다. 이미지마다 **원래 크기로 보기**를 선택하면100% 크기로 읽고, 해제하면 화면 폭에 맞춥니다. 설치·가이드 ZIP과 이미지 ZIP의 CRC·체크섬을 확인했습니다.

실제 Chromium에서9개 그룹을 통과했습니다. 내장 이미지4장의 바이트 일치·원래 크기 확대·내부 링크·390/768/1280px 배치·JS 오류0·외부 요청0을 확인했습니다. 관리 정책이 `file://` 열기를 문서 로드 전에 차단해, 같은 HTML 바이트를 전용 loopback으로 한 번 제공한 후 오프라인으로 전환해 검사했습니다. 사용자 OS의 저장창이나 로컬 파일 열기를 직접 검증했다고 설명하지 않습니다.

[가이드 패키지 검증](guide-verification.json) · [실제 브라우저 검사](browser-guide/verification.json) · [로컬 파일 정책 제한 기록](prior-attempts/guide-file-policy/README_KR.md) · [브라우저 재현 스크립트](browser-guide/support/check_offline.cjs).

## 배포 식별과 남은 범위

JAR SHA-256: `1984ee6014de648a6308ae8e0b0ee4d8a55c3ae38ddc4d5830beb0fdd5175f5b` ·9,315,380바이트.

소스 ZIP SHA-256: `541ffeb8d68c31ed6029223b20daa82cd6095946999d6f5e0f3489a985ef3a58` ·100,078,181바이트. 독립 폴더에서 빌드한 JAR와 배포 JAR가 바이트 단위로 일치합니다. 이전 Simple/Strix 배포는 보존합니다.

현재 환경에는 실제 Strix CLI·Burp 본체·모델이 설치되어 있지 않습니다. 따라서 이 기록의 가짜 PTY·loopback HTTP·production Swing 성공을 실제 모델 추론·대상 진단·사용자 Burp 검증으로 합산하지 않습니다. Linux/macOS의 Python3와 호환 Strix가 필요하며 macOS 실사용·Windows·DPI/IME는 이번 검증에 포함하지 않았습니다.

에이전트는 질문을 받으면 도구·대상 요청을 실행할 수 있습니다. 매 전송의 확인과 SCOPE 검사는 Strix 전체 네트워크 격리나 가용성 무영향을 보장하지 않습니다. 최대 턴·시간은 HTTP 요청 예산이 아니며 중지 후 별도 Docker 작업 정리는 미확인일 수 있습니다. 이 작업 브랜치의 배포·검증은 main 병합 완료 선언이 아닙니다.
