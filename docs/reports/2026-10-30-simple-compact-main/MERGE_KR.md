# Simple 1.4.5 main 병합 인수인계

- 원격 main 병합 확인: 2026-10-30T18:16:06.383383+09:00
- main 기준: `019aa1442bb522fe5f5172a34e7e89ca0ce870c8`
- 구현: `c16fa61` (`account-a/simple-compact-design`)
- 병합: `8971308b2f1f40ce1dfccb7e790d17e55d30fd3f`
- 방법: 사용자 명시적 main 병합 승인에 따라 일반 Git merge/push. API 접근 제한으로 PR 미생성. 보호 우회·force push 없음.
- 병합 트리의 `simple/`는 검증한 구현 커밋과 동일합니다. 후속 프로젝트 상태·대화 기록만 갱신하며 패키지 입력은 수정하지 않습니다.

[릴리스 안내](../../../simple/docs/RELEASE_SIMPLE_1.4.5_KR.md) · [검증 JSON](../../../simple/docs/VERIFICATION_SIMPLE_1.4.5.json) · [실제 화면 18장](../../../simple/docs/images/1.4.5/README.md)

JAR SHA-256: `8b59ccbf803a92367a923e55364bc8e7df35fc3a5e060ab82cc6610c7d0a5e4c`. 전체 검사 5,912개, 화면 132개, Katana 102개 통과. 화면 잘림·본문 붕괴 0건. 새 상세 보기 검사 22개와 기존 HttpServer 제한 환경 119개는 전체 검사에 포함됩니다.

실제 사용자 Burp GUI, Windows DPI/IME, 수동 브라우저·외부 대상 검사는 실행하지 않았습니다. 합성 운영 Swing 캡처를 실제 Burp GUI로 해석하지 않습니다. GitHub CI 상태는 API 제한으로 확인하지 않았습니다.

기존 Simple 1.4.2에서 이번 변경까지 정상 조상 관계이며 1.4.3·1.4.4의 변경과 버전별 아티팩트도 함께 main에 반영했습니다. 이전 파일과 원본 Next 소스를 덮어쓰지 않았습니다.
