# Workbench KR 0.6.0 JS Miner 고도화 검증

검증일: 2026-10-09 (한국 시간).
실제 실행: Ubuntu 24, Burp Community 2026.3.3-47134, Java 21.0.12.1. 확장 컴파일 대상 Java 17.

최종 자동 검사 **169개 통과 / 실패 0개**, 별도 실제 Burp 검사 **32개 통과 / 실패 0개**.

## 실제 검증 방법

제품 JAR를 Burp의 네이티브 Java 확장 로더로 설치했습니다. 제품 JAR는 실행 클래스패스에 넣지 않았습니다. 별도 검증 보조 확장은 제품의 Swing 화면을 조작하고 실제 결과를 확인했습니다. 제품 클래스 출처는 Burp의 임시 확장 로더 경로로 확인했습니다.

Spring/Tomcat/H2 커뮤니티 사이트에 JsMinerController와 `/js-miner-lab`을 추가했습니다. 실습 경로를 실제 Burp Proxy로 요청해 History에 쌓았고 제품의 자동 감시로 분석했습니다. 추가 소스맵 및 레지스트리 조회는 제품의 버튼으로 실행했으며 실제 Montoya HTTP API를 사용했습니다. 네이티브 파일 저장 대화상자로 수집 파일을 저장했습니다.

| 기능 | 실제 Burp/사이트에서 확인한 결과 |
|---|---|
| History 자동 분석 | 실제 Proxy 응답으로 JS·인라인 map·package.json 분석. 제품 offer 메서드 직접 호출 없음 |
| 도메인 | JS에 포함된 호스트 후보와 출처 관계 표시 |
| 클라우드 | AWS·Azure·Google·CloudFront·DigitalOcean·Oracle·Alibaba·Firebase·Rackspace·DreamHost 10개 분류 |
| 외부 소스맵 | sourceMappingURL 탐색 후 2개 원본 추출 |
| 추정 소스맵 | 참조 없는 JS에서 `.js.map` 위치 추정 및 원본 추출 |
| 인라인 소스맵 | 실제 Proxy 응답의 base64 JSON map 자동 추출 |
| 원본 재분석 | 숨겨진 POST API·합성 Google 키·클라우드 URL·NPM 패키지를 결과에 추가 |
| 시크릿 | 키 값 마스킹, 지문, Shannon 엔트로피 표시. 실제 키 유효성 검증 없음 |
| NPM | 로컬 레지스트리 모의 경로의 200 name 일치·404·429·302·200 soft-error를 실제 HTTP로 구분 |
| 오류 map | 잘못된 JSON을 오류로 표시하고 원본을 만들어내지 않음 |
| 외부 주소/이동 | 다른 Origin의 map 참조를 요청하지 않고 302를 따라가지 않음. 별도 수신 서버에 요청 0회 |
| 일시정지·재분석 | History 감시 중지/재개와 기존 기록 재분석 |
| 파일 저장 | 실제 chooser로 원본·정적 파일·SHA-256 manifest 저장. `../../outside` 파일명도 저장 폴더 안에 유지 |
| 확장 종료 | 네이티브 Installed 화면에서 제거하여 JS History 작업 종료 확인 |

32개 상세 검사와 클래스 출처는 `validation/actual-js-miner/verified/js-runtime-tests.txt` 및 JSON에 있습니다. 실제 Burp 화면과 저장 파일도 동일 폴더에 포함했습니다.

## 자동 검사

WorkbenchTest 43, EnvironmentTest 27, EnvironmentPanelTest 6, JsAnalysisTest 31, JsAnalysisPanelTest 11, JsHistoryTest 22, JsMinerTest 29 = 총 169개입니다.

원래 기능의 요청/응답·인가 비교·세션·범위·압축·분석 큐 회귀와 새 소스맵/클라우드/의존성/저장 검사를 수행했습니다. 새 검사에는 percent-encoded inline map, 내장 sections, 원본 없는 map, 중복 JSON 키, 다른 포트 map, 클라우드 접미사 위장, 상대/builtin 의존성 제외, 등록 여부 조회 오류, 저장 경로 탈출 방지도 포함합니다.

`python3 test_js.py`로 실행합니다. 이 검사들의 Burp 인터페이스는 모의 호스트이므로 실제 Burp 32개와 별도로 집계했습니다. Swing 테스트 종료를 명시하는 TestMain 실행기를 추가하여 성공/실패 후 프로세스가 남지 않게 했습니다.

## 범위와 제한

원본 Pro 전용 JS Miner 엔진을 고친 것이 아니라 Workbench 자체 JS 분석에 기능을 구현했습니다. 기존 9개 원본 엔진은 변경하지 않았고 이번 실행에서 모든 원본 엔진 기능을 다시 실행하지 않았습니다. Professional과 Windows는 미검증입니다.

소스맵은 v3의 sourcesContent에 포함된 원본을 추출합니다. 내용이 없는 map, URL로 연결된 section, 모든 난독화/번들러 문법을 완전히 복원하지 않습니다. 동적 API·상대 URL의 문서 기준은 추가 확인이 필요합니다.

NPM은 이번 실행에서 공개 레지스트리를 조회하지 않았고 로컬 합성 레지스트리로 검증했습니다. 조직 scope 이름은 추출하지만 조직의 등록/소유권은 조회하지 않습니다. scoped 패키지 `%2F` 경로는 기본 Tomcat에서 거절될 수 있어 로컬 조회가 미판정이 될 수 있습니다. 404는 사설·비공개·인증·레지스트리 설정 영향일 수 있으며 Dependency Confusion 성공이나 공격자 등록 가능성을 뜻하지 않습니다.

클라우드 URL·키는 실습 문자열입니다. 실제 클라우드 접근, 키 검증·사용, NPM 패키지 등록, 임의 JavaScript 실행은 수행하지 않았습니다. UI/복사 결과는 값과 URL 쿼리를 가리지만 명시적으로 저장한 원본 파일에는 합성 키 문자열이 포함됩니다.

최근 분석 100개, JS 분석/캡처 512 KiB, map 응답 2 MiB, 복원 원본 합계 1 MiB/100개 제한입니다. 파일 덤프는 이 한도 내 수집된 텍스트를 보관하며 전체 웹사이트의 모든 정적 파일을 크롤링하는 기능은 아닙니다.

실습 프로세스는 검증 후 종료했습니다. 격리된 Burp 프로젝트와 설정을 사용했습니다.

## 참고한 기능/형식

- JS Miner 기능 명세: https://github.com/PortSwigger/js-miner/blob/main/BappDescription.html
- 소스맵 형식: https://tc39.es/ecma426/
- NPM 레지스트리: https://docs.npmjs.com/cli/v11/using-npm/registry/

JAR SHA-256: `2c2f93b7f0fdba1f8e5bbe372228717fc4027067650a5ae0d0ab05941ea06ef0`

복원 결과 합계 표시와 실습 페이지의 합성 코드 비실행 수집도 최종 확인했습니다.
