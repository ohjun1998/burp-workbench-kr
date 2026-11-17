# 게시 상태

이름 분리 작업은 `account-a/workbench-strix-identity` 브랜치에서 진행합니다. 출발점은 `account-a/simple-strix-local`의 `d46c32a8103c2a020d21ec602a843e87a8436399`입니다. 이전 Strix 후보와 일반 Simple 작업 브랜치를 덮어쓰거나 main에 병합하지 않습니다.

GitHub REST API의 읽기 요청은 현재 클라우드에서 Forbidden으로 차단되었습니다. 따라서 PR 자동 생성과 원격 Actions 상태 조회는 확인하지 못하며, 브랜치 게시와 다운로드 검증은 Git HTTPS 및 공개 raw 다운로드로 별도 확인합니다. main 병합이나 PR 생성 완료를 의미하지 않습니다.

## 게시·공개 다운로드 확인

구현·설치 파일·소스·가이드 게시 커밋: `2c031952ff0569d8d282bee4777db89e39d53e9c`. 새 작업 브랜치의 push를 완료했습니다. 이 커밋의 공개 raw URL에서 JAR와 설치·가이드 ZIP을 전체 다운로드해 HTTP200, 로컬과 SHA-256 일치 및 ZIP CRC를 확인했습니다. [다운로드 검증](public-download-verification.json).

main 병합과 PR 생성은 수행하지 않았습니다. 원래 Strix 후보 브랜치와 Simple1.4.22 브랜치는 변경하지 않았습니다.
