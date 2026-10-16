# Workbench 0.26.1 · Windows 동시 I/O 보완

0.26.0의 PR 검사는 통과했지만 main 병합 후 Windows 재검사에서 601건 수집 뒤 목록을 읽을 때 `Invalid metadata string` 오류가 발생했습니다. 수집 저장소의 읽기 FileChannel이 쓰기 RandomAccessFile과 파일 포인터를 공유하는 구조를 독립적인 READ 핸들 두 개로 분리했습니다. 생성 실패와 unload에서도 모든 읽기·쓰기 핸들을 닫고 임시 파일을 정리합니다.

0.26.0 JAR에 새 회귀를 적용하면 읽기 채널의 위치 변경이 쓰기 포인터를 변경하는 문제가 재현됩니다. 0.26.1은 원본·색인의 포인터 분리, 두 읽기 스레드의 캐시 퇴출과 1,200건 추가 수집, 총1,800건의 메타데이터·바이너리 응답 전량 검증, 독립 채널 정리의 회귀5개를 추가했습니다. 기존 실패 검사를 유지하며 재현 가능한 동시성 검증을 추가했습니다.

## 검증과 인수인계

- 로컬 정식 release: Java46그룹1,450개·페이로드 HTTP132개·전체 기능 HTTP100개·모의 진단266개, 합계1,948개 통과.
- 저장소·수집 복구 두 그룹을 각각10회, 총20회 실행해 통과했습니다. 반복 실행을 고유 검사 건수에 더하지 않습니다.
- Windows CI에도 같은10회 반복을 추가합니다. 최종 원격 release·Windows 반복·180초 수집·ENOSPC·화면·패키징 결과는 작업 PR의 Checks와 설명에서 확인합니다.
- JAR·소스 ZIP의 해시·소스 지문을 확인하고 새0.26.1 경로로 배포합니다. 기존0.26.0·0.21.0과 이전 다운로드는 보존합니다.

[검증 기록](VERIFICATION_0.26.1.json) · [0.26.0 최적화 내용](RELEASE_0.26.0_KR.md) · [다운로드](../downloads/README.md) · [날짜별 작업 대화](chat-logs/2026-10-16/workbench-windows-0261.md)

수집과 회귀 검사는 Montoya 호스트 어댑터와 실제 운영 저장소·Swing을 사용합니다. 실제 Burp runtime/GUI, 다시간 수집, Pro·외부 BApp, Windows Docker Desktop 전체 구동은 별도 검증 범위입니다. Mapper0.2.0·실습3.5.2는 변경하지 않습니다. 0.26.0의 수집·Mapper·증거·캐시·화면 최적화는 유지합니다.

Java의 파일 포인터·채널 관계는 [RandomAccessFile 공식 문서](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/io/RandomAccessFile.html#getChannel())를 참고하세요. Windows에서 관측한 오류와 기존 JAR의 포인터 공유 재현, 새 버전의 검증 결과는 위 기록에 구분했습니다.
