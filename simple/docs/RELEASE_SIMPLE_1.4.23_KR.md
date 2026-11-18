# Workbench Simple 1.4.23

Workbench Strix 1.1.0에 있던 Strix 설정, 요청·응답 우클릭 메뉴, 진단·대화 창, CLI 실행 연결을 제거했습니다. 새 JAR는 **Workbench Simple**로 표시되며 Simple 1.4.21의 화면 배치와 13개 도구를 사용합니다.

Burp의 Extensions에서 사용 중인 Workbench Strix를 언로드한 뒤 새 JAR를 로드하세요. 트래픽과 설정은 기존 `.burp-workbench-simple` 폴더와 `simple.*` 설정 키를 사용합니다. 이 업데이트는 이전 진단 파일과 독립 Strix 설치·인증을 삭제하거나 변경하지 않습니다.

Burp가 수집한 현재 Proxy 기록은 **Proxy → HTTP history**에서 확인할 수 있습니다. Simple의 **요청·응답 목록**은 Workbench 수집 조건을 통과한 기록을 표시합니다. 이 두 목록은 수집 조건과 시작 시점에 따라 다를 수 있습니다.

독립 Strix에 Burp 실시간 기록을 공급하는 연결은 적용하지 않았습니다. 확인한 Strix의 외부 조회 도구 제한은 Strix 자체의 실행 도구에는 적용되지 않아, 해당 버전에서 요청을 차단한 답변 전용 연동을 확인할 수 없었습니다. [확인한 Strix 소스](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/agents/factory.py#L633-L675)

[설치 JAR](../downloads/1.4.23/burp-workbench-simple-1.4.23.jar) · [소스 ZIP](../downloads/1.4.23/burp-workbench-simple-1.4.23-source.zip) · [체크섬](../downloads/1.4.23/SHA256SUMS)
