# 닫힌 loopback 포트 검사 신뢰성 보완

앞선 전체 검사에서 기존 `ScopeKatanaProxyTest`의 마지막 “닫힌 프록시가 새 연결을 받을 수 없다” 검사가 한 번 실패했다. 직전의 기존 유휴 소켓 종료 검사는 통과했다.

`ScopeKatanaProxy.close()`는 `closed=true` 뒤 원래 `ServerSocket.close()`를 동기 호출하며 acceptor도 종료 상태를 다시 확인한다. 이 애플리케이션 코드는 변경하지 않았다.

현재 Linux/OpenJDK17에서 별도 `LoopbackCloseProbe.java`를 실행하여, 이미 닫힌 listener 포트와 클라이언트 소스 포트를 같게 지정하면 TCP 연결이 성공하고 송신 바이트가 자기 입력으로 돌아오는 현상을 재현했다. 소스 포트가 다른 대조 연결은 `ConnectException`으로 거부됐다. 관측 출력은 `probe-observed.txt`에 그대로 기록했다. 이 프로브는 닫힌 포트의 TCP 연결 성공만으로 원래 프록시가 살아 있다고 단정할 수 없음을 보여준다.

**앞선 실패 당시에는 local/remote endpoint 로그가 없으므로 그 실패의 직접 원인이 self-connect였는지 다른 프로세스의 포트 재사용이었는지는 확정하지 못한다.**

검사 코드만 연결 전 loopback 소스 포트를 명시적으로 할당하고, 목적지 포트와 같으면 다른 소스 포트를 고르도록 보완했다. 마지막 검사가 실패할 경우 local/remote endpoint만 기록하며 프록시 인증 주소나 자격 증명은 출력하지 않는다. 기존 HTTP·CONNECT·TLS·Scope·인증·종료 관련 안전 검사는 유지했다.

수정된 검사 파일만 외부 classes 디렉터리에 OpenJDK17의 `--release 17`로 컴파일한 뒤 현재 1.4.22 JAR와 기존 검사 클래스를 사용하여 3회 직렬 실행했다. 세 번 모두 49개 검사를 통과했고 마지막 연결은 `ConnectException`으로 거부됐다. 각 로그를 보관했으며 이 검사는 실제 대상 네트워크 요청이나 시스템 CA 설치를 하지 않는다.

직렬 검사 종료 후 추가 포트 사용 프로브나 검사를 실행하지 않았다. 이 증거는 앞선 실패와 검사 신뢰성 보완을 설명하며 최종 전체 release 결과를 대신하지 않는다.
