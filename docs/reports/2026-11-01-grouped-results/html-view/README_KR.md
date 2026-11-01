# 도구별 A 그룹형 결과 HTML

[HTML 열기](Workbench_Simple_1.4.22_Grouped_Results_KR.html) · [저장용 ZIP](Workbench_Simple_1.4.22_Grouped_Results_KR.zip)

ZIP을 압축 해제한 뒤 HTML을 브라우저로 연다. 이미지·글꼴·저장 결과가 모두 파일 안에 있어 인터넷이나 별도 서버가 필요 없다.

- 왼쪽 메뉴에서 13개 도구를 전환한다.
- **결과 탐색**에서 그룹을 접고 펼치거나 기능별 건수를 선택해 저장된 결과를 읽는다. 행을 선택하면 기존 응답·판정 상세가 바뀐다.
- **원본 정보 보기**에서 URL·원본 ID·실제 저장 배열의 행을 확인한다. 원본 데이터는 줄이거나 합치지 않았다.
- 대시보드의 API·명세, 환경·DB 등 카드와 각 도구의 검색·계정·분류 조건을 사용할 수 있다.
- 상단의 **실제 확장 화면**은 공식 Simple 1.4.22 JAR의 실제 Swing 캡처를 보여 준다. 달/해 버튼으로 두 테마를 전환하고, 원본 크기로 확대할 수 있다.

## 데이터 출처

**결과 탐색**은 기존 Simple 1.4.21 은행 테스트의 실제 저장값을 1.4.22 그룹형 표시로 보여 주는 HTML이다. 새 1.4.22 은행 검사를 실행한 결과가 아니다. 원본 JSON 1,321,014바이트를 그대로 보존했고, 트래픽 89건·별도 업무 재현 5건·Mapper 관측 135건·Scope finding 525행을 포함한다. 공개 형식에서 동일해 보이는 finding도 각각의 저장 행으로 유지했다. 미점검·후보·미확인·미실행 판정은 원래 값이다.

**실제 확장 화면**은 Simple 1.4.22의 합성 검증 데이터로 촬영한 Linux Swing 이미지 26개다. 위 은행 데이터와 구분해서 표시한다. 이 이미지는 실제 Burp/Windows 캡처가 아니다.

경로 표시명과 Mapper 그룹은 공식 1.4.22 JAR의 `ResultLabels`와 `FeaturePresentation`을 읽기 전용으로 호출해 얻었다. JAR SHA-256은 `d5ac13a818d0fd641c72cd093cd86c21c3fea7793e01e3cc5b8da25900f0ff7c`다. 이 작업에서 Simple 소스나 배포 파일은 변경하지 않았다.

입력값 검사의 실제 SQL 입력, JSON 비교의 필드 경로, Raw 원문은 본래의 결과값으로 표시한다. 외부 조회/CVE는 기존 기록대로 미실행이다. 모든 금액은 실습용 달러($)다.

## 재생성과 검증

저장소 루트에서 검증된 JDK 17로 다음 명령을 실행한다. 브라우저 검증에는 Playwright와 `/usr/bin/chromium`이 설치된 환경이 필요하다. HTML을 여는 데에는 이 개발 도구가 필요 없다.

```sh
python3 audit/grouped-results-html/build_report.py --java /path/to/jdk17/bin/java
node audit/grouped-results-html/verify_grouped_html.mjs docs/reports/2026-11-01-grouped-results/html-view/Workbench_Simple_1.4.22_Grouped_Results_KR.html /tmp/grouped-html-check
```

검증 결과는 [browser/browser-verification.json](browser/browser-verification.json), 생성 입력과 HTML·ZIP의 지문은 [manifest.json](manifest.json)에 있다. 원본 은행 JSON과 캡처 PNG의 바이트 동일성, ZIP CRC, 실제 브라우저의 모든 메뉴·그룹·선택·검색·응답값과 두 테마/세 크기를 확인한다. 외부 자산 요청과 새 은행 HTTP 요청은 없다.

최종 브라우저 검증: **14개 검사 통과**, 배치 측정 **80개**, JavaScript 오류·추가 HTTP 요청 **0건**. 작은 화면의 분석 원본 선택과 실제 캡처 모드까지 확인했다.
