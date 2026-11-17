# 실제 Swing 창 캡처 재현

`Strix1421Native.java`는 이전 Simple 1.4.21과 현재 Strix의 메인·대시보드에 같은 합성 Burp API/패킷을 넣어 창을 엽니다. 두 버전의 이름 있는 컴포넌트 위치·크기를 비교하며 통합 점검·대시보드 메뉴의 42px 높이도 확인합니다.

`StrixChatNative.java`는 현재 릴리스 JAR의 `SimpleStrixPanel`에 합성 세션 어댑터를 주입합니다. 연결 전 화면, 대화 이력, 하위 에이전트 선택, 실제 질문 전송 확인창을 캡처합니다. 확인창은 Escape로 취소하며 메시지 전송 함수가 호출되면 실패합니다. 그림에 표시된 답변은 합성 예시이며 실제 Strix·모델·취약점 진단 결과가 아닙니다.

두 하네스 모두 실제 Xorg/xfwm4 창을 `Robot.createScreenCapture`로 캡처합니다. 이미지 편집, `printAll` 렌더링, 브라우저 목업을 사용하지 않습니다. 실제 Burp 런타임을 사용하는 검증은 아닙니다.

```bash
bash /workspace/tools/burp-workbench/native-setup/start.sh
python3 docs/reports/2026-11-17-workbench-strix-chat/native/support/run_capture.py
bash /workspace/tools/burp-workbench/native-setup/stop.sh
```

현재 재현 스크립트의 외부 JDK·이전 JAR 경로는 관리형 클라우드 환경을 기준으로 합니다. 다른 환경에서는 스크립트 위쪽의 `ROOT`, `WORK`, `JAVA`, `BASELINE`을 해당 위치에 맞춥니다. 현재 후보 JAR은 `--candidate`, 결과 경로는 `--out`으로 지정할 수 있습니다. 하네스 컴파일에는 Simple 테스트에서 빌드한 `build/tests`와 `deps/*.jar`도 필요합니다.

결과 이미지의 PNG 크기·CRC·SHA-256과 로드한 JAR의 SHA-256을 확인합니다. 대화 화면은 최소 940×720을 포함해 두 테마·세 크기에서 읽기 영역 80px, 입력 영역 50px 이상과 버튼 잘림 여부를 측정합니다. 최종 사용 가이드의 이미지 목록은 `../guide-captures.json`에 기록합니다.

Xorg의 창 장식이 붙는 시점에 요청한 높이보다 4px 커지는 현상이 한 번 관찰됐습니다. 하네스는 창 장식 연결 후 같은 요청 크기가 연속해서 유지될 때까지 제한된 횟수로 위치·크기를 맞추고, 결과에서 실제 창 크기도 다시 검사합니다. 이전 실패는 `../../prior-attempts/native-layout/wm-reparent-boundary.json`에 보존했습니다. 제품 코드나 위치·크기 비교 기준을 완화하지 않았습니다.
