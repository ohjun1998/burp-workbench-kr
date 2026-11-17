# Workbench Strix 1.1.0 실제 Swing 화면 검증

최종 JAR SHA-256: `1984ee6014de648a6308ae8e0b0ee4d8a55c3ae38ddc4d5830beb0fdd5175f5b`.

실제 Xorg/xfwm4 창을 Java Robot으로 캡처한 PNG 52개입니다. 제품 클래스는 최종 DIST JAR에서 로드했고, 패키징 후 `simple/downloads/strix/1.1.0/burp-workbench-strix-1.1.0.jar`와 바이트가 같은지 확인했습니다. 원래 캡처 보고서의 로드 경로를 보존했으며 확인 결과는 [immutable-artifact-match.json](immutable-artifact-match.json)에 있습니다.

합성 Burp API·패킷·대화 세션 어댑터를 사용했습니다. 실제 Burp 런타임, Strix 실행, 모델 추론, 대상 서버 진단을 검증한 화면은 아닙니다. 그림에도 합성 예시임을 표시했습니다. 이미지 편집과 목업 렌더링을 사용하지 않았습니다.

메인·대시보드는 Simple 1.4.21과 같은 자료를 넣고 두 테마, 1280×800·1600×1000에서 비교했습니다. 이름 있는 컴포넌트 464개의 위치·크기가 같고 차이는 0개였습니다. 통합 점검·대시보드 메뉴는 42px 높이를 유지합니다. 상세 결과: [native-comparison.json](native-comparison.json).

대화창은 두 테마에서 연결 전, 주 에이전트 대화, 하위 에이전트 선택, 질문 전송 확인창을 캡처했습니다. 실제 확인창은 Escape로 취소했고, 전송 함수가 호출되면 하네스가 실패하도록 했습니다. 모든 실행에서 대상 요청·Strix 실행·모델 호출은 0회입니다.

| 창 크기 | 대화 이력 높이 | 질문 입력 높이 | 레이아웃 위반 |
|---|---:|---:|---:|
| 940×720 | 105px | 61px | 0 |
| 1280×800 | 185px | 61px | 0 |
| 1600×1000 | 385px | 61px | 0 |

두 테마에서 같은 결과입니다. 이력 영역 최소 80px, 질문 입력 최소 50px와 버튼 잘림 여부를 검사했습니다. 모든 PNG의 크기·CRC·SHA-256을 확인했습니다. 사용 가이드에서 사용하는 네 그림은 [guide-captures.json](guide-captures.json)에 있습니다.

초기 화면에서 대화 이력이 접히던 실패와 입력 영역을 옮긴 뒤 67px만 남던 실패를 보존했습니다. 최종 화면은 정책·미리보기를 `원본·테스트 정책` 탭에서 열고, 실행 조건 행에 채팅 선택을 함께 배치해 대화 공간을 확보합니다. 별도로 한 번 발생한 창 장식 연결 시점의 4px 크기 변경은 하네스에서 요청 크기 유지 여부를 검사하도록 수정했습니다. 비교 기준을 완화하지 않았습니다. 이전 실패 보고서는 [prior-attempts/native-layout](../prior-attempts/native-layout)에 있습니다.

재현 방법과 하네스: [support/README_KR.md](support/README_KR.md).
