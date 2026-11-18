# Workbench Simple 1.4.23 검증

Workbench에 포함된 Strix 설정·우클릭 메뉴·채팅창·프로세스 실행 코드를 제거했습니다. 기존 Strix 배포 파일은 보존했습니다. 새 JAR에는 Strix 클래스와 도우미가 없으며, 직전 JAR와 비교해 삭제된 항목 28개가 모두 Strix 항목입니다.

JDK 17에서 전체 release와 패키지 verify를 통과했습니다. 자동 검사 8,105개는 Workbench 178개 그룹의 7,793개, payload loopback 136개, Mapper Java 147개, 브라우저 도우미 29개로 구성됩니다. 실제 제품 Swing을 합성 Burp API로 실행하여 설정·선택 원본 메뉴의 Strix 항목 제거를 확인했습니다. 사용자 Burp에 직접 설치하거나 독립 Strix에 연결하지 않았습니다.

[검증 집계](verification.json) · [설치 및 변경 안내](../../../simple/docs/RELEASE_SIMPLE_1.4.23_KR.md)
