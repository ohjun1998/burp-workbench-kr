# 게시 상태

작업 브랜치는 `account-a/strix-simple-1.4.21`이며 기존 Workbench Strix1.0.0의 `abca73a511ddbd7d2708b0915b01065e1c846a06`에서 시작했습니다. 사용자가 지정한 Simple1.4.21 배포 소스 커밋 `03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d`로 기본 화면을 맞추고 Strix 연결부를 추가했습니다.

새 산출물은 `simple/downloads/strix/1.0.1/`, 그림 가이드는 `downloads/guides/strix-1.0.1/`에 별도 보관합니다. 기존 Simple/Strix 배포·문서·다른 브랜치는 덮어쓰지 않습니다.

현재 클라우드의 GitHub REST API는 Forbidden으로 차단되어 PR 생성/원격 Actions 조회를 확인하지 못합니다. Git HTTPS 브랜치 게시와 공개 raw 다운로드는 별도로 검증합니다. main 병합이나 PR 생성 완료를 뜻하지 않습니다.

## 게시 확인

구현·배포·캡처 게시 커밋은 `515adbd5dad09f84fed25817e4522e644e639ce5`입니다. 작업 브랜치 push를 완료했고 해당 커밋의 공개 raw URL에서 JAR·설치가이드ZIP·실제창캡처ZIP의 전체 다운로드HTTP200, 로컬SHA일치 및ZIP CRC를 확인했습니다. [공개 다운로드 검증](public-download-verification.json). main병합과 PR생성은 하지 않았습니다.
