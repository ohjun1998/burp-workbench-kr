# Simple 1.3.2 main 병합 기록

사용자의 명시적 “main에 병합해줘” 요청에 따라 [PR #100](https://github.com/ohjun1998/burp-workbench-kr/pull/100)을 **2026-10-28T10:11:02Z / 한국 시간19:11:02**에 merge commit으로 병합했습니다.

- 이전 main: `0cef0c5dc97f5824d1fe4a7b298b855994e69983`
- 실제 merge: `70a9476998764211b886fa392742ec11d3acf83c`
- PR head: `80beac1337f8788b4cb606d11d5136b69a45ce29`
- 검증된 제품 소스: `9fe30b6965b9c6074476f0233cbd6c30de82e3ca`; 이후 head에는 공개 병합 요청 기록만 추가
- 원본 Next 파일 수정·삭제0, 선행 Simple7개 커밋·배포 보존

[Simple CI40669107190](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40669107190)는 기본5,184개·실제Katana102개·보고서25개·112뷰/2,244컨트롤/120상태의 게이트0·배포 지문 검증·artifact업로드를 통과했습니다. artifact는12807143903입니다. 플랫폼·집중·중첩 검사 수는 합산하지 않습니다.

CI는 retarget 전 예약된 synchronize 실행으로, 실제 checkout `17b19ded22706776ccecf2944d661949734b6432`는 이전 stack base7113e79와 head80beac1의 merge입니다. 해당 트리 `ca97be5c877530a2bc4147fdc728830e7515bc35`는 head80beac1·main 예상 merge223b8d80·실제 main merge70a9476과 정확히 일치합니다. 따라서 검사한 전체 파일과 main 결과가 동일함을 확인했으며, 별도 main checkout 기능 검사로 오인하지 않도록 기록합니다. 병합 직전 원본 Build·Windows 작업은 진행 중이었고 실패는 없었습니다. 보호 설정·검사를 변경하지 않았고 GitHub의 expected head 검증을 사용했습니다.

병합 직후 원격 main을 새로 받아 head80beac1 대비 차이0 및 `python simple/dev.py verify` 통과를 확인했습니다. JAR SHA-256 `a8dd345b44b4893a301bf3092817335585c15ebd67cb5e6508bd3847fee49400`, 소스 ZIP `a0a1dab3daef3a9bc2495a8888a05bfbb279f0fbb850376dfd01fe0e7bcc7580`와 guide/helper·이전 배포 지문은 그대로입니다. `simple/` 패키징 입력을 변경하지 않습니다.

실제 사이트 본28회·직접2회와 자동 미관측 review, native Burp/MV3 미검증, 일시500·부분 범위는 [제품 검증](../../../simple/docs/VERIFICATION_SIMPLE_1.3.2.json)에 유지합니다. 원문 민감값·인증 자료를 공개 Git에 올리지 않았습니다.

[설치·가이드](../../../simple/README_KR.md) · [배포 파일](../../../simple/downloads/1.3.2/) · [사용자 요청·공개 작업 기록](../../chat-logs/2026-10-28/simple-main-merge.md)

이후 루트 상태·설치 안내 문서 정리는 생산 코드·검증 입력을 바꾸지 않는 별도 PR로 반영합니다. 이 문서 정리를 기능 검사 재실행이나 새 버전 배포로 계산하지 않습니다.
