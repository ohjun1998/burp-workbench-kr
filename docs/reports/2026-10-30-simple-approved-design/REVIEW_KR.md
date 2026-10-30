# 승인 시안과 달랐던 구현 수정

기준 main: `f83b356c40a3288717dc6c39c9c1318809b76d7c`. 작업 브랜치: `account-a/simple-approved-design`. 사용자는 시안 구현과 main 병합을 명시적으로 요청했고, 이전 1.4.5 화면이 첨부 시안과 다르다고 정정했습니다.

시안의 Workbench 바·메뉴·글꼴·아이콘·도구별 작업 배치를 실제 운영 컴포넌트에 반영했습니다. 계정 카드, JS 상세, 준비/결과 단계, 접을 때 폭을 되돌려 주는 기능 트리를 추가하고, 기존 데이터/선택/전송 경계를 유지합니다.

- [설치·변경 및 차이](../../../simple/docs/RELEASE_SIMPLE_1.4.6_KR.md)
- [승인 시안과 실제 구현 비교](../../../simple/docs/design/1.4.6/COMPARE.html)
- [실제 8개 화면](../../../simple/docs/images/1.4.6/README.md)
- [최종 검증](../../../simple/docs/VERIFICATION_SIMPLE_1.4.6.json)
- [요청·진행 대화](../../chat-logs/2026-10-30/simple-approved-design-correction.md)

전체 Simple check 명령은 최종 JAR에서 종료 코드 0입니다. 검사 5,897개, 화면 164개, Katana 102개가 통과했습니다. 실제 Burp GUI·Windows DPI/IME는 실행하지 않았습니다. 초기 UI 기대값/컴파일 실패와 중간 반복 검사를 최종 결과와 구분해 저장했습니다. 150개 회귀 그룹은 유지되며 UI 기대값 수의 변화는 검증 JSON에 기록합니다.

시안의 임의 데이터나 취약점 판정을 제품에 넣지 않았습니다. 생성 시안은 구현 증거가 아니며, 픽셀 완전 일치를 주장하지 않습니다. 기존 Next와 모든 이전 버전 산출물은 보존합니다. 실제 main 병합 결과는 MERGE_KR.md에 후속 기록합니다.
