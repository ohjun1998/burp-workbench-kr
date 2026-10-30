# Simple 1.4.11 main 병합 확인

- 작업 브랜치: `account-a/numbered-visual-guides`
- 구현 커밋: `312a765732be25fda1c24644cefb01a0bf55e3d0`
- 일반 main 병합: `7694fb5e194961f8f0a60701420449330eb96a3c`
- 원격 main push: 성공. 강제 push는 없습니다.
- GitHub CLI 토큰이 유효하지 않아 PR 대신 기존 사용자의 main 반영 승인에 따른 일반 병합을 사용했습니다.
- 공개 main JAR 재다운로드 SHA-256: `634c8a67afc53c69a0e21acf453ed51723b423bb499521ae8cbac968a04b4847`.
- 공개 이미지 ZIP 재다운로드 SHA-256: `27a23c3b9b24a1bb854d9d732eefe4391b8c1cddf1f0a577782e1b2bb5827554`.
- 두 공개 파일은 로컬 검증 파일과 일치하며 공개 이미지 ZIP CRC도 통과했습니다.
- 병합 후 `python simple/dev.py verify`가 불변 패키지·소스 fingerprint·CRC를 통과했습니다.
- Simple 패키지 입력은 동결 상태입니다. 원본 Next와 이전 버전 파일을 보존했습니다.
- 전체 검사6964개 후 창 제목/어두운 설명색 후속 수정은 집중1057개로 검증했습니다. 최종 JAR의 실제 가이드 창50개, Swing194개 화면, Katana102개 검사를 통과했습니다.
- 실제 Linux Swing/WM와 합성 Burp 어댑터 검증입니다. Windows Burp 본체·DPI/IME는 미검증입니다.

[이미지 목록](README.md) · [검증 범위](VALIDATION_SCOPE_KR.md) · [공개 다운로드 검증](MERGE_VERIFICATION.json)
