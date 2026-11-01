# Native 사전 검증 이력

이 폴더는 최종 검증 전 하네스 준비·진단 이력입니다. 아래 실행을 최종 통과 수에 합산하지 않습니다. 실패 PNG·개인 런타임 폴더·캐시는 복사하지 않았으며 원본은 작업환경 tmp/compact-primary-native-preflight-*에 보존했습니다.

- preflight1/2: 활성화되고 올바른 크기로 표시된 Swing 창에 대해 외부 JFrame.isValid 플래그까지 요구한 준비 조건이 부적절했습니다. 실제 활성 상태·안정된 화면 좌표를 기다리도록 바꾸고, 이후42px/6px/부모 내부 완전 표시 검사는 그대로 유지했습니다.
- preflight3/4: fixture의 JFrame.requestFocus 때문에 키 입력 초점이 Swing 자식이 아닌 외부 AWT frame에 남았습니다. history에는 traffic→dashboard→traffic가 정상 기록됐고 ancestor binding도 존재했습니다. 실제 Tab으로 primary 버튼에 초점을 옮긴 후 shortcut을 검증하도록 교정했습니다. 상태 JSON을 함께 보존합니다.
- preflight5/6: 페이지 복원 직후 초점 이동과 전역 Robot.waitForIdle 대기가 실제 상태 검증을 방해했습니다. 두 shortcut 전 모두 실제 Tab 초점을 안정화하고, 지속적인 Swing 작업이 있는 UI에서는 기존 native 하네스처럼 Toolkit.sync·EDT barrier와 명시적인 최종 상태 await를 사용했습니다. 필요한 입력/선택/경로 assert를 제거하지 않았습니다. main-thread 진단 발췌를 함께 보존합니다.
- preflight7: 마지막 일반 메뉴는 실제로 정상 선택됐지만 기존 bounty의 증거 하위 화면을 currentNavigationRoute가 evidence로 표현하는 정상 계약을 하네스가 허용하지 않았습니다. 이 명시적 alias와 실제 tree node 선택을 함께 검사하도록 교정했습니다.

최신 source의 수평 scrollRectToVisible 보정이 포함된 JAR(9d3d20af946add3498da79a021be31fafe8d84e046352463c4a559616187301c)로 preflight8을 통과한 뒤, 같은 JAR의 새8구성 전체 검증을 완료했습니다. 최종 결과는 ../native/primary/verification.json이며808개 검사/48개 원본 Robot PNG입니다. 실제 Linux Swing 및 합성 Burp API 검증이고 native Windows Burp 검증이 아닙니다.

## 최초 집중 검사와 실제 스크롤 결함

`focused-initial.log`는 최초 집중 실행의 두 실패를 보존합니다. 트래픽 선택 복원의 wrapper 객체는 원래 선택 시 새로 만들어지므로 불변 PacketRef ID·요청/응답 바이트·실제 행/모델/문서가 복원되는지 검증하도록 고쳤습니다. 기존 60ms 비동기 복원을 EDT 밖에서 제한 시간까지 기다리며 실제 조건을 유지합니다.

두 줄의 고정 헤더 때문에 작은 창에서 모든 하단 행이 한 번에 보이지 않을 수 있어, 실제 스크롤로 마지막 행이 완전히 드러나고 헤더 두 버튼의 화면 좌표가 변하지 않는지 검사했습니다. 이 강한 검사가 실제 수평 이동 결함을 발견했습니다. `sidebar-scroll-before-fix.log`와 `sidebar-scroll-probe.log`에서 viewport x=15와 왼쪽 6px 잘림을 확인할 수 있습니다. classic 트리의 scrollRectToVisible 요청을 viewport 폭 안의 수직 이동으로 제한해 수정했습니다. 최종 집중 12그룹 673개와 기본 전체 178그룹은 새 JAR로 다시 통과했습니다.
