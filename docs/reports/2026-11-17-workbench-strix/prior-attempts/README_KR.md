# 첫 검사 · 클라우드 PID 1의 고아 자식 정리 제한

전체 검사에서 SimpleStrixClientTest의 pending.get(6s)가 시간 초과했다. 검사 종료 후 가짜 Python 자식은 PID1에 입양된 Z(좀비) 상태였다. 운영 SimpleStrixClient의 종료 확인은 ProcessHandle.isAlive를 사용하므로 이 환경에서 자식 종료 확인을 끝내지 못할 수 있다. 제품 소스와 중지 기준을 완화하지 않았다.

테스트 명령의 부모에 Linux PR_SET_CHILD_SUBREAPER를 적용한 별도 로컬 실행기를 두고, 가짜 프로세스만 사용하는 동일한 Client63개 검사를 다시 실행해 통과했다. 실행기는 테스트의 고아 자식을 waitpid로 회수하며 Strix CLI·전역 설정·사용자 프로세스를 변경하지 않는다. support/test-subreaper.c를 gcc로 빌드해 사용할 수 있다.

최종 전체 검사는 같은 실행기 아래에서 수행한다. 첫 실패와 최종 통과를 합산하지 않는다. 이 환경 보완은 실제 Strix 컨테이너의 종료나 사용자 시스템의 동작을 보장하지 않는다.

## 두 번째 전체 실행 · 닫힌 loopback 소켓 시험

Client63개는 통과했으나 ScopeKatanaProxyTest의 닫힌 포트 재연결 거부 assertion이 실패했다. 이전 A 그룹형 브랜치에서 검증한 Linux TCP self-connect 방지용 테스트 보정을 가져왔다. 테스트 연결의 소스 포트를 명시적으로 bind하고 목적지 포트와 다르게 할당하며 실패 시 endpoint를 기록한다. 운영 프록시와 assertion은 유지한다. 이전 실패 당시에는 endpoint 로그가 없어 실제 연결의 정확한 원인은 확정하지 않았다.

보정 소스: account-a/bank-simple-1.4.21-results의 ScopeKatanaProxyTest.java. 기능·레이아웃 구현을 병합한 것이 아니다. 공개되지 않은 이 작업의 생성 후보 패키지만 제거하고 변경된 시험 입력을 포함해 새로 패키징한다. 이전 공개 배포는 보존한다.
