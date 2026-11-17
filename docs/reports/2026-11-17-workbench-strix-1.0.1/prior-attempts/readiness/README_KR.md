# 첫 전체 검사: 검색 준비 시험의 gate 시점

SimpleTrafficReadinessTest의1000회 교차스레드 idle 관측 assertion이 한 번 실패했다. TrafficBrowser 제품 소스는 Simple1.4.21 기준과 바이트단위 동일하며 변경하지 않았다.

시험은 identities supplier를 EDT밖에서 먼저 설치한 뒤 search.setText와 begin을 호출했다. 동일 supplier는 begin뿐 아니라 refresh/stillCurrent에서도 읽으므로 텍스트 변경 관측자가 의도한 준비 단계보다 먼저 gate를 소비할 수 있다. 원래 실패 시점의 호출 stack은 없으므로 최초 실패의 정확한 호출 위치는 확정하지 않았다. 별도 계측 프로브에서는 begin:156/preparing=true인 상태의18개 검사가 통과했다.

기존18개 assertion과 worker latch는 유지하고 supplier 설치만 동일 EDT의 search.setText 이후, begin직전으로 옮겨 준비 단계에 대기 지점을 고정했다. 보정한 동일 시험을 원본Simple1.4.21 JAR와 새Strix1.0.1 JAR에 각각 실행해18개씩 통과했다. 제품 검색/준비 코드나 idle assertion을 완화하지 않았다.

최종 전체 검사는 보정된 시험으로 다시 수행한다. 첫실패·집중 검사·재실행 수치를 고유 검사 수로 합산하지 않는다.1000회 읽기는 하나의 차단 구간 표본이지1000개의 독립 시험이 아니다.
