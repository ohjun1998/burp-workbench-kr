# Simple 1.4.12 main 반영 확인

- 작업 브랜치: `account-a/contextual-button-layout`
- 검증 구현: `2c448f726c25d5225f7031fd2b8ba85e778cca8b`
- 일반 main 병합: `7635cd282f8f57dc928722d3fe35d6597d991bff`
- 원격 main push 성공. 강제 push는 없습니다.
- GitHub CLI 토큰이 유효하지 않아 PR 생성 대신 기존 사용자의 main 반영 요청 범위에서 일반 Git 병합을 사용했습니다.
- 최종 전체7073개·Swing194화면·실제 창112개·Katana102개 통과. 가이드16화면·96번호의 두 테마192개 위치를 확인했습니다.
- JAR·소스 ZIP·이미지 ZIP·실제 화면2장을 공개 main에서 인증 없이 다시 내려받아 HTTP200/크기/체크섬이 로컬과 같은지 확인했습니다.
- JAR SHA-256: `0e8476e05fe63213069b08f1324184f1f78383a7e24cae561ec6395eb1b16370`.
- 소스 ZIP SHA-256: `0c6e3306267d4395db87401863c92b6a0e5ca3fb1ce5dd1d07c705bfcc67814b`.
- 이미지 ZIP SHA-256: `a3c4fef53cb5356e49de2311d5c8aecf7596e4d1875254a0cbf7493e6104e3b5`.
- 불변 Simple 패키지·소스 fingerprint·ZIP CRC 검증을 통과했습니다. 패키징 후 Simple 입력을 수정하지 않았습니다.
- 실제 Linux 창과 합성 Burp API/패킷을 검증했습니다. Windows Burp 본체·DPI/IME는 직접 검증하지 않았습니다.

[실제 화면·비교](README.md) · [검증](VERIFICATION.json) · [공개 다운로드 확인](PUBLIC_DOWNLOAD_VERIFICATION.json)
