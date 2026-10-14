# 0.25.3 인수인계

- 기준 브랜치: `account-a/0.25.2-unlimited-traffic`, 기준 커밋 `97da5217243eb88f2b3357c090352c8bee105dda`.
- 작업: 수집 ON/OFF/SCOPE 순환, 실시간 검색, Burp Add to scope 자동 연동, SCOPE 체크·삭제·URL 추가.
- 주요 구현: `WorkbenchExtension`, `WorkbenchScope`, `BurpScopeConfig`. 별도 수집 모드 enum으로 상태를 하나로 관리하고 `captureLock`으로 콜백과 모드 변경을 직렬화합니다.
- Burp 연동: 로드 시 프로젝트 `target.scope`를 읽고 `registerScopeChangeHandler`에서 변경 후 재동기화. 체크 상태와 삭제한 Burp 규칙은 확장 저장소에서 유지합니다. 확장 해제 시 알림 등록과 임시 디스크를 정리합니다.
- 체크 해제한 항목과 URL로 표현할 수 없는 Burp 고급 패턴은 대상 URL 선택 드롭다운에서 제외해 잘못된 URL 입력을 방지합니다. 고급 패턴은 SCOPE 목록과 실제 수집 판정에 유지합니다.
- 기존 v1 SCOPE 설정을 읽고 v2 형식에 체크·삭제 상태를 함께 저장합니다. 공유 Scope 판정은 기존 도구와 legacy Payload Runner에 계속 적용됩니다.
- 새로운 `TrafficScopeModesTest`: 모드, 실제 HTTP handler 입력, 신규 native Scope 알림, 체크 유지, 정렬과 선택 삭제, 재로드, 일괄 삭제, 제거 후 재등록, prefix/고급 규칙 경계 검사.
- 기존 수집 복구 검사: 601건 burst와 2MiB 바이너리 응답 복원·해제 정리를 유지. 제거된 UI 버튼에 의존한 테스트는 내부 정리 기능 검사로 변경.
- `ScopeUiPreview`는 실제 SCOPE 내용을 기본/최소 창 크기에서 렌더링합니다. 모의 API 호스트이므로 실제 Burp GUI 검증은 아닙니다.
- 다음 확인: 사용자 Burp GUI에서 Add to scope → 자동 목록 표시 → 체크 해제/재체크 → SCOPE 수집 확인. 이번 환경에서 실제 Burp GUI 및 Docker 구동은 미실행.

## 이어서 완료한 검증

2026-10-14 재개 환경에서 `python dev.py release` 종료 코드 0. 회귀 1290개/41그룹, 페이로드 HTTP 127개, 전체 기능 HTTP 100개, 모의 진단 266개 통과. 수집 모드 20개와 601건·2MiB 원본 보존 회귀 포함. 기본 화면 108개·안내 48개·SCOPE 창 4개 렌더 검사 통과. 패키징 4개와 다운로드/소스 지문 검증 통과. 자세한 근거는 `VERIFICATION_0.25.3.json` 및 `audit/0.25.3/` 참조.

0.25.2 커밋을 그대로 계승하고 기존 다운로드 및 다른 작업 브랜치를 보존해 PR로 전달합니다. 사용자 요청에 따라 검증 성공 후 main으로 병합합니다.
