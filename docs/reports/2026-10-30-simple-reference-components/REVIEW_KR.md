# Simple 1.4.8 · 시안 구성 요소 적용 검토

사용자가 지적한 기본 사각 단계 버튼을 원형 번호·주황 밑줄과 분리된 정보 줄로 바꾸고, 8개 도구의 카드·표·탭·상세 정보·패킷·증거 작성 요소에 적용했습니다.

- 기준 main: `d133f621af8ef8a402cf49336bcd04f576e68267`
- 작업 브랜치: `account-a/simple-reference-components`
- Simple 1.4.8 / Mapper 0.3.11 / helper 0.4.1
- 전체 검사 5,901개 / 실제 Swing 164개 / Katana 102개 통과
- JAR SHA-256: `9a0d497ef23b6cc479f9ac7889175599795391258dca452f1f76bf12cbe88465`

[변경](../../../simple/docs/RELEASE_SIMPLE_1.4.8_KR.md) · [전체 실제 화면](../../../simple/docs/images/1.4.8/README.md) · [시안 비교](../../../simple/docs/design/1.4.8/COMPARE.html) · [검증·초기 수정 이력](../../../simple/docs/VERIFICATION_SIMPLE_1.4.8.json) · [사용자 대화 기록](../../chat-logs/2026-10-30/simple-reference-components.md)

기존 테스트에서 변경된 표시 열과 실제 상세 뷰 위치를 갱신했습니다. 9개 계정 모델 필드·선택 ID·원문·전송·저장 계약은 유지하며, 필터 후 증거 삭제/서식, 세 번째 계정 열 복원, 차이 표시 유지, 긴 값·첫 표시 위치 검사를 추가했습니다. 화면 높이 기준은 낮추지 않고 증거 패킷 최소 높이를 추가했습니다.

자료는 운영 Swing 컴포넌트의 합성 예시 캡처입니다. 실제 Burp GUI·Windows DPI/IME·수동 브라우저는 미실행이며 픽셀 동일성을 주장하지 않습니다. 원래 Next와 이전 버전 배포 파일은 변경하지 않습니다.

현재 세션에는 PR 생성 도구가 없고 GitHub API는 허용 목록 밖입니다. 사용자가 승인한 main 반영은 작업 브랜치 push와 일반 merge commit으로 수행했습니다. [병합 확인](MERGE_KR.md)에 실제 원격 반영과 공개 파일 검증을 기록했습니다. force-push하지 않습니다.
