# 초기 확인과 최종 검증의 구분

최초 컴파일에서 Box.createVerticalStrut 반환값의 선언 타입(Component/JComponent) 오류를 발견해 수정했습니다.

첫 집중 검사에서 기존 레이아웃·payload 검사는 통과했지만 SimpleBridgeRuntimeTest의 자식 프로세스 loopback bind가 샌드박스 권한으로 거절됐습니다. 이 실패는 제품 회귀로 해석하지 않으며 로그를 보존합니다. 이후 loopback 권한을 허용한 전체 검사를 사용합니다.

새 창·메뉴와 기존 compact 디자인/폴더를 확인하는 집중 4개 그룹은 모두 통과했습니다(31+54+52+49). 초기 native 후보에서는 디스플레이의 자식 대화상자로 초점이 옮겨져 Robot F11 검사 대기가 끝났습니다. 실제 부모 창을 활성화한 뒤 키를 보내도록 검증 하네스를 수정했습니다. 실제 native 테스트는 최종 JAR에서 다시 실행합니다.

창 peer를 dispose해서 전체화면을 만드는 초기 구현은 열린 자식 대화상자를 닫을 수 있어, 같은 JFrame의 GraphicsDevice 전체화면 API를 사용하도록 바꿨습니다. 제품의 편집 상태와 소유 관계는 유지합니다. 최종 증거는 별도 SUMMARY/VERIFICATION과 최종 로그로 확인합니다.
