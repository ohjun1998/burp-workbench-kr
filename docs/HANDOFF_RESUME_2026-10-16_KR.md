# Workbench 0.26.0 재개 인수인계

2026-10-16 (한국 시간). Workbench 0.26.0·Feature Mapper 0.2.0·실습 3.5.2. 통합 PR은 [#19](https://github.com/ohjun1998/burp-workbench-kr/pull/19)입니다.

## 반영 내용

소스 검토의 P1·P2 항목인 수집·화면·관찰 분리, 디스크 메타데이터 색인, Mapper 후보 인덱스·잠금 밖 스냅샷 저장, 증거 디스크 참조·스트리밍 출력, JS 캐시·대기 바이트 예산, 화면 증분 갱신, BuildInfo 버전 통합을 복구·검증했습니다. `RELEASE_0.26.0_KR.md`와 기존 성능 계측의 범위·한계를 함께 읽으세요.

소스 커밋 `8d68cfe`에서 GitHub가 정식 release를 수행했고, 로컬 검증본과 SHA-256 다섯 개가 모두 일치할 때만 배포 커밋 `0672fb8`에 JAR·소스 ZIP·브라우저 보조 ZIP을 게시했습니다. 일회성 조립 workflow와 게시 스크립트는 제거했습니다. 0.21.0, 기존 배포 파일과 실습 3.5.2 파일은 보존합니다.

Mapper CI가 과거 0.1.0 명세와 새 0.2.0을 비교하던 부분을 현재 BuildInfo 버전의 다운로드 명세·소스 지문 검증으로 수정했습니다. 제품 소스·배포의 최종 검증 커밋은 `0b0efeff6746d5a531b6748488d3dca8dfc9a224`입니다. 이후 문서 커밋은 제품 파일을 바꾸지 않습니다.

## 이번에 실제로 통과한 검증

| 범위 | 결과 |
|---|---|
| Workbench 정식 release | Java 46그룹 1,445개·페이로드 HTTP132개·전체 기능 HTTP100개·모의 진단266개, 합계1,943개 |
| Mapper | Java75개·Node7개 |
| 실제 Chromium | 보조 확장8개·production Mapper/Bridge fixture5개 |
| 생산 Swing 구성요소 | 108화면·안내48화면, 잘림·본문 붕괴0 |
| 배포 | 패키징 단위4개·다운로드 해시·소스 지문 통과 |
| 실제 Linux 디스크 고갈 | 독립16MiB tmpfs의 ENOSPC·부분 기록 롤백·기존 원본 유지·공간 확보 후 재개13개 |
| 180초 연속 수집 | Linux3,617건·Windows3,639건, 각각 누락·처리 오류0 |
| Windows PowerShell5.1 | 파서·시작 성공/실패 분기6개, Docker·브라우저 명령은 제어된 어댑터 |

[빌드·화면·패키징](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/38845536661), [Linux/Windows 내구성](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/38845536690), [Mapper·Chromium](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/38845536917)이 모두 성공했습니다. 원격 조립은 [38845137204](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/38845137204)입니다. 이번 로컬 환경에서도 release·Mapper·화면·패키징을 다시 실행했습니다. 상세 수치와 원본 바이트/텍스트의 구분은 `VERIFICATION_RESUME_2026-10-16.json`에 있습니다.

수집은 Montoya 호스트 어댑터에서 실제 운영 콜백·디스크·Swing 타이머를 실행했습니다. Chromium도 production Mapper/Bridge fixture를 사용했으며 실제 Burp GUI 검사로 합산하지 않습니다. 과거 0.25.x 결과는 새 버전의 실행 결과에 포함하지 않습니다.

## 최종 병합과 후속 범위

사용자가 main 반영을 요청했으며 PR의 마지막 head 검사 성공 후 병합합니다. PR #19가 최신 병합 상태와 마지막 문서 커밋 검사의 기준입니다. GitHub 작업 대화는 `docs/chat-logs/2026-10-15/`와 `2026-10-16/`에 포함했습니다.

실제 Burp 0.26.0 runtime/GUI의 Add to scope 전체 클릭 흐름, 관측 전 Scope 고급 규칙 전체 열거, 다시간 수집, Pro Collaborator·외부 BApp, Windows Docker Desktop 전체 구동은 남은 검증입니다. 영속 프로젝트 저장·재개와 Workbench–Mapper 전체의 공통 패킷 ID는 별도 후속 고도화 후보입니다. 증거 내부 packetId와 구분합니다.
