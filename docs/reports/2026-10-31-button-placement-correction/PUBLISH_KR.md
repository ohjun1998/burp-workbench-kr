# 배포 상태

구현·배포 커밋`96e38a79864697bbcc4534236daf81b1b55b04e4`; 작업브랜치`account-a/button-placement-correction`.

공개39개 파일(적용 화면32장·전후 미리보기3장·PDF·이미지ZIP·JAR·소스ZIP)을 재다운로드해HTTP200·크기·SHA-256 일치를 확인했습니다. [공개 다운로드 검증](public-download-verification.json).

1.4.14 대비14개 화면의 대표 버튼이 최소32px 이상 세로 이동했으며 실제 작업 부모 영역도 변경했습니다. 요청 재현·인증 후보 연결2화면은 유지했습니다. [좌표 검증](button-movement-verification.json).

[새 설치JAR](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/96e38a79864697bbcc4534236daf81b1b55b04e4/simple/downloads/1.4.15/burp-workbench-simple-1.4.15.jar) · [1.4.14 전후 비교PDF](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/96e38a79864697bbcc4534236daf81b1b55b04e4/docs/reports/2026-10-31-button-placement-correction/workbench-simple-1.4.15-before-after.pdf) · [이미지32장ZIP](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/96e38a79864697bbcc4534236daf81b1b55b04e4/docs/reports/2026-10-31-button-placement-correction/workbench-simple-1.4.15-images.zip)

초안PR 생성은`Post "https://api.github.com/graphql": Forbidden`으로 실패했습니다. PR 생성·main 병합은 미완료입니다. [변경 비교](https://github.com/ohjun1998/burp-workbench-kr/compare/main...account-a/button-placement-correction). 이전 패키지와 원본Next는 보존했으며 패키지 생성 이후Simple 입력을 수정하지 않았습니다.
