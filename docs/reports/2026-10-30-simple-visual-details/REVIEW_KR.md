# Simple 1.4.7 · 전체 세부 디자인 검토

승인 이미지의 세부 스타일을 계정 카드·탭·트리와 8개 도구의 공통 컨트롤에 적용합니다. 기능·원본·명시적 실행을 유지하며 기존 Next와 모든 이전 배포는 변경하지 않습니다.

- 기준 main: `33efd3df7f23ca3451dd4ae3b5d3ddae1e86b5ae`
- 작업 브랜치: `account-a/simple-visual-details`
- 내장 Mapper: 0.3.10 / helper: 0.4.1
- 실제 검사 5,903개 / Swing 164개 / Katana 102개 통과
- JAR SHA-256: `b52ec4a7edc194271387843b28673a1eb81c7fcd2d1ad806291b4c478dd87982`

[상세 변경](../../../simple/docs/RELEASE_SIMPLE_1.4.7_KR.md) · [실제 화면](../../../simple/docs/images/1.4.7/README.md) · [검증 및 초기 수정 이력](../../../simple/docs/VERIFICATION_SIMPLE_1.4.7.json) · [대화 기록](../../chat-logs/2026-10-30/simple-visual-details.md)

테스트의 기존 행 높이/배지/구문 RGB 기대값만 새 디자인에 맞춰 바꿨습니다. 원문·검색 오프셋·계정/원본 식별·저장/전송 경계 검사는 유지합니다. 계정 카드 실제 정보, 기능 트리 가시성, Render의 텍스트 복사 비활성 검사를 추가했습니다.

실제 Burp GUI·Windows DPI/IME·수동 브라우저 검증은 미실행입니다. 픽셀 동일성이나 사용자 PC에서의 외관 일치를 보장한다고 기록하지 않습니다.

현재 환경에는 PR 생성 도구가 없고 GitHub API는 네트워크 허용 목록 밖입니다. 사용자가 승인한 main 병합은 작업 브랜치 push 후 일반 merge commit으로 진행하며 force-push하지 않습니다. 실제 병합 사실은 별도 MERGE_KR.md에 기록합니다.
