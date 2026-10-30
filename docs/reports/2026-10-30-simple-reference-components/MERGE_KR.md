# Simple 1.4.8 · main 병합 확인

- 구현·검증 커밋: `d23adc511612ed6a7a2333a5182b2910b1bfb1d9`
- main 병합 커밋: `8d45e6b98ae646a92d6680cc03cd54721c383aaa`
- 원격 main push 및 공개 JAR 확인: 2026-10-30T21:17:19.590876+09:00
- 방법: 작업 브랜치 push → 최신 main 확인 → 일반 `--no-ff` 병합 → main push. PR 도구/API 접근이 없어 PR 생성으로 기록하지 않습니다. force-push나 보호 설정 변경은 없었습니다.
- 병합한 `simple/` 트리는 검증한 작업 브랜치와 같습니다. 패키징 이후 Simple 소스·문서·이미지·배포 파일은 변경하지 않았습니다.
- 설치 JAR: 6,264,139 bytes / SHA-256 `9a0d497ef23b6cc479f9ac7889175599795391258dca452f1f76bf12cbe88465`
- 독립 소스 ZIP: 92,863,480 bytes / SHA-256 `f14c3808938f1c783a7b44a489df9d79be0f426dd64ab4672ad2c32311f4d3f1`
- `package`와 별도 `verify` 성공: ZIP CRC·파일별 해시·소스 fingerprint 일치.
- 공개 main JAR를 내려받아 로컬 파일과 동일한 SHA-256 확인: [기록](PUBLIC_ARTIFACT.json).

전체 5,901개 검사(150개 Java 그룹 5,589 · payload HTTP 136 · Mapper 147 · Node 29), 실제 Swing 164개 화면, Katana 102개를 통과했습니다. 잘린 컨트롤과 필수 영역 붕괴는 0건입니다.

[변경 검토](REVIEW_KR.md) · [실제 전체 화면](../../../simple/docs/images/1.4.8/README.md) · [시안 비교](../../../simple/docs/design/1.4.8/COMPARE.html) · [검증과 초기 수정 이력](../../../simple/docs/VERIFICATION_SIMPLE_1.4.8.json).

캡처는 합성 데이터를 사용하는 실제 운영 Swing 컴포넌트입니다. 네이티브 Burp GUI·Windows DPI/IME·수동 브라우저·픽셀 동일성은 미검증이며 GitHub CI 통과를 주장하지 않습니다. 원래 Next와 이전 배포 파일은 보존했습니다.
