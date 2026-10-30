# Simple 1.4.7 · main 병합 확인

- 구현·검증 커밋: `5c7c072e426fc26b023b5749725026745a2388e6`
- main 병합 커밋: `39eae232cbc5e7092130f5cb928a7ff6c1bd51c6`
- 원격 main push 완료: 2026-10-30 (Asia/Seoul)
- 방법: 작업 브랜치 push → 변경 없는 최신 main 확인 → 일반 `--no-ff` 병합 → main push. PR 도구/API 접근이 없어 PR을 생성했다고 주장하지 않습니다. 보호 설정 변경이나 force-push는 하지 않았습니다.
- 병합된 `simple/` 트리는 검증한 작업 브랜치와 동일합니다. 배포 후 Simple 소스·문서·이미지·패키지는 변경하지 않았습니다.
- JAR: 6,243,358 bytes / SHA-256 `b52ec4a7edc194271387843b28673a1eb81c7fcd2d1ad806291b4c478dd87982`
- 소스 ZIP: 91,199,111 bytes. `package`와 별도 `verify` 모두 성공; ZIP CRC·각 파일 해시·소스 fingerprint 일치.
- 공개 main JAR를 실제 내려받아 동일 해시를 확인: [기록](PUBLIC_ARTIFACT.json).

검사 5,903개, 실제 Swing 164개 화면, Katana 102개 통과. [변경 검토](REVIEW_KR.md) · [실제 8개 도구 화면](../../../simple/docs/images/1.4.7/README.md) · [검증](../../../simple/docs/VERIFICATION_SIMPLE_1.4.7.json).

합성 데이터로 운영 컴포넌트를 캡처했으며 네이티브 Burp GUI·Windows DPI/IME·수동 브라우저는 미실행입니다. 이전 Next와 배포 파일은 보존합니다.
