# Simple 1.3.1 개선·재시험 인수인계

Simple 1.3.0의 정상 세션 오인 중단, `.env`·큰 JS의 대표 API 누락, 표현식·소스맵 오탐과 분류 불일치를 수정하고 원문 값 분류 보고서 저장을 추가했습니다. 기존 Next와 8개 도구·과거 배포를 보존합니다. 공개 공유 본문에서 확인한 개선 사항을 처리했으며, 별도 Library 첨부의 기존 “9개 개선안” 전체를 복원하거나 전부 확인했다고 주장하지 않습니다.

기반은 `5d09640e918755174a450580ca05e209970ce71d`이며 `account-a/simple-retest-values`에서 `account-a/simple-katana` 대상 초안 PR로 전달합니다. main 병합은 수행하지 않았습니다. 기존 merged 상태 문서는 변경하지 않습니다.

## 실행·결과

- 기본 검사: strict Java 17, 134개 그룹 / Workbench 4,799 + payload HTTP 136 + Mapper Java 145 + Node 24 = **5,104개 통과**. 집중 검사는 이 수치에 더하지 않습니다.
- 별도 실제 Katana 1.8.0: **102개 통과**. 보고서 형식 단위검사 10개와 HTML 검색·분류·다운로드 검사 10개도 별도입니다.
- 실제 Swing 컴포넌트·합성 데이터: 112뷰 / 2,244개 컨트롤 / 120개 상태, 배치 실패 0. 가이드 35쪽 / 28개 캡처, 구조·시각 검수 완료.
- 실제 원본 7개 앱: 수정 전·후 × 루트/직접 대조 **28회**. 전송 시도/완료 응답/전송 오류는 1.3.0 **1,080/1,076/4**, 1.3.1 **1,203/1,195/8**입니다.
- 정상 폼 오인 자동 중단 3개 루트 앱 → 0개, Juice Shop 대표 API 3종 복원, VulnBank `.env` 자동 수집(계정별 200 응답 2개)을 확인했습니다. 이 숫자는 고유 파일 수가 아닙니다.
- 독립 소스 ZIP 재빌드가 배포 JAR와 바이트 단위로 일치합니다. [재빌드 증거](source-rebuild-1.3.1.json), [개선 대조](improvement-checks.json), [공개 보고서 요약](public-summary.json)을 확인하세요.

전체 검사의 첫 시도에서 JsArchivePagingTest 5초 대기 시간 초과가 발생했습니다. 동일 소스·JAR 단독 검사와 앱 종료 뒤 전체 검사는 통과했으며 한도 완화는 하지 않았습니다. 인과관계는 확정하지 않았습니다. [최종 검증 JSON](../../../simple/docs/VERIFICATION_SIMPLE_1.3.1.json)에 재시도와 실제 한계를 보존합니다.

## 원문 보고서

20,281개 위치별 기록을 API·민감정보·토큰·키·환경·DB·경로·내부·기타로 분류했습니다. 제품 탐지 1,415 / 제품 보고서 보충 파싱 15,437 / 독립 보고서 보충 파싱 3,418 / 테스트 로그인 인증 11로 출처를 분리합니다. 같은 값의 반복과 공개·예제 값도 포함하므로 취약점 수나 고유 시크릿 수가 아닙니다. 파싱값·원문 표기·원본 ID·응답 SHA·UTF16 위치·계정·revision과 미확정 상태를 제공합니다.

원문 포함 PDF·검색 HTML·JSON·CSV·ZIP은 `/workspace/simple-retest-1.3.1-report`에 있으며 공개 Git에 넣지 않습니다. `parsed-values.zip`은 전체 값 목록·서식·요약 PDF를 포함하는 전달용 묶음이고 `evidence.zip`은 상세 HTTP corpus까지 포함하는 선택 자료입니다. 제품 UI의 **분류 보고서 저장**은 현재 결과 원본에서 별도 로컬 ZIP을 만듭니다. 정상 화면·기본 이력의 가림은 유지합니다.

## 배포·보존

- [Simple 1.3.1 JAR·소스 ZIP·체크섬](../../../simple/downloads/1.3.1/)
- [35쪽 가이드 PDF](../../../simple/downloads/guides/1.3.1/Workbench_Simple_1.3.1_Guide_KR.pdf)
- [변경·사용 안내](../../../simple/docs/RELEASE_SIMPLE_1.3.1_KR.md)
- [실제 앱 하네스·재현 조건](../../../audit/simple-retest/README_KR.md)
- [사용자에게 보인 작업 대화](../../chat-logs/2026-10-28/simple-retest-values.md)

JAR SHA-256: `9b50905cebb5485274e986445aefb198dbc56c3d0d9cb1bfeef69a0c4d0f969d`. 소스 ZIP SHA-256: `3eb792a1d8884e19c49db201240a826f61203bac87cdf110374d110aca095578`. `simple/` 배포 입력은 고정했으므로 원격 검사 기록은 이 루트 보고서 폴더나 PR에 추가합니다. 기존 Simple 배포·PDF 22개와 과거 가이드 보존 해시는 모두 유지했습니다.

## 한계와 종료 상태

Benchmark는 200회 예산에서, OopsSec는 별도 감사 하네스 105초에서 중단된 부분 결과입니다. Juice Shop 일부 응답의 잘못된 Content-Length와 OopsSec 취소·I/O는 완료 응답에 넣지 않았습니다. 실제 Burp GUI·수동 브라우저 전체 메뉴·모든 앱 취약점 정확도·Windows DPI/IME는 검증하지 않았습니다. HTTP 원본은 Java HttpClient가 수신한 payload와 정규화 헤더를 재구성한 저장본이며 TCP framing 원시 캡처가 아닙니다.

실행한 7개 사이트·소유 Docker 컨테이너 3개·relay는 종료했고 원본 소스·가능한 DB 백업·응답 근거는 보존했습니다. 사용자 전역 Burp·브라우저·프록시 설정을 바꾸지 않았습니다. 원격 CI 결과는 PR의 해당 commit 검사에서 확인하며 로컬 통과를 원격 통과로 표현하지 않습니다.
