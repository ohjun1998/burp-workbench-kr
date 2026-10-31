# Simple 1.4.17 게시와 다운로드 확인

- 구현·검증·패키지 커밋: `9de662f1e3b9b3ea564931aa7a3535d81dd8d6b7`.
- 게시 브랜치: [`account-a/simple-1.4.17-completion`](https://github.com/ohjun1998/burp-workbench-kr/tree/account-a/simple-1.4.17-completion).
- 해당 브랜치를 새로 push했고 강제 push·main 병합은 수행하지 않았습니다.
- 실제 공개 URL9개의 응답은 HTTP200이며, 전체135,828,455바이트를 읽어 로컬 크기·SHA-256과 같음을 확인했습니다. [다운로드 확인](PUBLIC_DOWNLOAD_VERIFICATION.json).
- 설치 JAR·독립 소스 ZIP·체크섬 목록·비교 PDF·이미지 ZIP·실제 Linux 창 ZIP·라이트/다크 실제 화면·인증 준비 창 첫 탭을 확인했습니다.
- 소스 ZIP을 새 폴더에 풀어 JDK17로 빌드한 JAR는 설치 JAR와 바이트 단위로 동일합니다. [재빌드 기록](source-rebuild-verification.json).
- 최종 전체7,474개, 두 테마/세 크기258화면, 실제 Linux 창180개, 실제 Katana102개, Guide192위치 검증을 통과했습니다. [실행 결과](VERIFICATION.json).

## 다운로드

- [설치 JAR](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/9de662f1e3b9b3ea564931aa7a3535d81dd8d6b7/simple/downloads/1.4.17/burp-workbench-simple-1.4.17.jar)
- [독립 소스 ZIP](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/9de662f1e3b9b3ea564931aa7a3535d81dd8d6b7/simple/downloads/1.4.17/burp-workbench-simple-1.4.17-source.zip)
- [시안/실제 비교38페이지 PDF](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/9de662f1e3b9b3ea564931aa7a3535d81dd8d6b7/docs/reports/2026-10-31-pdf-alignment/1.4.17/workbench-simple-1.4.17-pdf-alignment.pdf)
- [원본 PNG·갤러리 ZIP](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/9de662f1e3b9b3ea564931aa7a3535d81dd8d6b7/docs/reports/2026-10-31-pdf-alignment/1.4.17/workbench-simple-1.4.17-pdf-alignment-images.zip)
- [실제 Linux 창48장·검사 기록 ZIP](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/9de662f1e3b9b3ea564931aa7a3535d81dd8d6b7/docs/reports/2026-10-31-pdf-alignment/1.4.17/workbench-simple-1.4.17-native-linux.zip)

JAR SHA-256: `b34ed19605de1178b1a21d5b70686eb3a834abf276e3b2e74bebda3bb3e6144d`.

소스 ZIP SHA-256: `12dcb5235d38318aa57e7e648381182489f55c61d9ab907d1cafa136857bad9a`.

## PR 상태와 한계

검증 내용과 사용자 대화 기록을 포함한 draft PR 생성을 시도했으나 `Post https://api.github.com/graphql: Forbidden`으로 거부되었습니다. PR 생성·main 병합이 완료됐다고 주장하지 않습니다. 검토 본문은 [PR_BODY_KR.md](PR_BODY_KR.md)에 남겼고 [브랜치 비교](https://github.com/ohjun1998/burp-workbench-kr/compare/main...account-a/simple-1.4.17-completion)에서 확인할 수 있습니다. 토큰·인증 설정을 변경하거나 추출하지 않았습니다.

실제 Windows Burp GUI·DPI/IME와 외부 서비스 검증은 수행하지 않았습니다. 공개 파일 확인·독립 소스 재빌드·합성 운영 Swing·실제 Linux 창 검사는 각각의 범위로 구분합니다.
