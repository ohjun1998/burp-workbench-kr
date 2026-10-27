# 7개 로컬 원본 앱 재시험 보고서

`build_report.py`는 `run_site.py`가 저장한 실제 실행과 응답 원본을 읽습니다. HTTP 요청을 추가로 보내거나 키·토큰을 인증에 재사용하지 않습니다. 보고서의 원문 값은 저장소 밖의 명시한 폴더에만 생성합니다.

```sh
python audit/simple-retest/test_build_report.py
python audit/simple-retest/build_report.py \
  --results /workspace/simple-sites/results \
  --metadata-root /workspace/simple-sites \
  --checks /path/to/current-improvement-checks.json \
  --site-validation audit/simple-retest/results-summary.json \
  --regression-validation simple/docs/VERIFICATION_SIMPLE_1.3.1.json \
  --out /workspace/simple-retest-1.3.1-report \
  --browser-check \
  --require-complete-matrix \
  --public-summary docs/reports/2026-10-28-simple-retest/public-summary.json
```

필요한 Python 패키지는 `reportlab`, `PyMuPDF`, `Pillow`입니다. 한국어 PDF 글꼴은 저장소의 `NanumGothic-Regular.ttf`를 사용합니다. HTML은 외부 글꼴·스크립트·서버에 의존하지 않습니다.

선택적인 `--browser-check`는 Node·Playwright와 Chromium이 있을 때 로컬 HTML 문자열만 렌더링해 검색·필터·축약 없는 표시·전체 선택 결과 JSON 다운로드·오류·외부 요청 0건을 확인합니다. 대상 사이트 브라우저 탐색이나 Burp GUI 검사와는 별도이며, 보고서 HTML 조작 검사 수로만 기록합니다.

## 입력과 출처

- 입력: `<version>/<siteId>/<scenario>/run.json`, 같은 실행 폴더의 `transport/`, `originals/`, 선택적 `product-export` ZIP.
- 제품 탐지: 새 제품 보고서의 `provenance=PRODUCT_FINDING`.
- 제품 보고서 보충 파싱: 제품 보고서의 `REPORT_SUPPLEMENT`. 제품 자동 탐지 성과와 구분합니다.
- 재시험 보고서 추가 파싱: 실제 응답의 JSON scalar·인용한 코드 리터럴·API 문자열·응답 헤더를 별도 파싱한 값입니다.
- 테스트 로그인 인증값: 로컬 원본 앱의 seed 계정으로 생성해 명시적으로 저장한 `private-auth.json`의 인증값입니다. 탐지 성과에 넣지 않습니다.

제품 값은 불변 `OriginalID`, 계정·프로필 revision, 응답 SHA-256, UTF-16 offset·length와 `rawText`의 일치를 모두 확인합니다. 일치하지 않으면 보고서 생성을 실패시킵니다. 추가 파싱의 offset은 디코딩 텍스트의 Unicode 문자 기준이며 제품의 UTF-16 좌표와 구분합니다. 헤더는 각 파서의 명시한 재구성 헤더 블록 기준입니다. HTTP wire 바이트 위치를 의미하지 않습니다. 원본 ID가 하나로 연결되지 않으면 미확정으로 기록합니다.

같은 원문 위치에서 제품이 이미 제공한 값을 추가 파싱 성과로 중복 등록하지 않습니다. JSON 값과 API 정규식이 같은 값을 겹치는 위치에서 찾은 경우도 같은 출처의 중복을 제거합니다. 서로 다른 응답·계정·버전의 관측 위치는 보존하므로 값 수는 고유 취약점 수가 아닙니다.

## 결과

- `Workbench_Simple_1.3.1_Retest_KR.pdf`: 사이트별 전후 결과·배포 차이·개선 사항과 대표 원문 값. 대표 값은 축약하지 않으며 PDF에 싣지 않은 전체 값의 수를 표시합니다.
- `report.html`: 사이트·버전·분류·출처·원문·근거 검색, 원문 복사와 선택 JSON 저장. 전체 값을 축약하지 않습니다.
- `raw-values.json`, `raw-values.csv`: 전체 파싱 값·JSON 원문 표기·출처·정확한 위치와 해시. CSV도 원래 문자열을 유지합니다.
- `report.json`: 현재 실제 실행·값 전체·조건·한계.
- `evidence.zip`, `SHA256SUMS.json`: 보고서와 실제 실행 원본, 로컬 테스트 인증값의 출처 파일. 요청·응답에 인증값이 포함될 수 있는 로컬 원문 증거입니다.
- `parsed-values.zip`: PDF·HTML·전체 값 JSON/CSV·메타데이터·검증·안내만 묶은 작은 다운로드. 전체 HTTP corpus는 별도 `evidence.zip`에 있습니다.
- `report-validation.json`, `pdf-contact-sheet.png`: CSV·JSON·HTML 데이터 동등성, PDF 글꼴·텍스트 경계 검사와 시각 검토용 화면.

`--public-summary`는 엄격한 허용 필드만 남깁니다. 원문 값·패킷·인증값·URL·로그·메시지·로컬 계정 설정을 포함하지 않으며 공개 저장소에는 이 요약과 재현 코드만 반영합니다. 원문 보고서의 테스트 로그인 인증값을 공개 Git에 업로드하지 않습니다.

동일 버전의 실행에 서로 다른 후보 JAR 해시가 섞여 있으면 생성을 거부합니다. 최종 생성의 `--require-complete-matrix`는 두 버전·7개 사이트·사이트별 동일 조건의 시나리오 2개가 모두 있어야 통과합니다. 중단·부분·미확인이 있는 실제 결과도 그대로 포함하며 실행 누락을 완료 결과로 꾸미지 않습니다.

## 해석

실제 응답에는 HTTP 401·404·500도 포함됩니다. 이 응답과 I/O 전송 실패를 구분하며 `transportResponses`·`transportErrors`를 별도 집계합니다. 원본 앱의 EOF/Content-Length 불일치에 대한 Java HTTP 어댑터 제한은 실제 Burp TCP 처리 결과와 구분합니다. 초기 하네스 설정 실패가 별도 `setup-attempts`에 보존된 경우 최종 결과 입력으로 사용하지 않습니다.

큐 종료는 전체 사이트 완료가 아닙니다. 미확인·부분·실패·자동 접근하지 않은 쓰기 기능을 함께 표시합니다. 발견한 키·토큰의 외부 유효성을 검사하지 않습니다. 공유 대화에 접근할 수 없는 이전 보고서의 개선안 9개 전체를 복원했다고 주장하지 않습니다.

제품 보고서 자체의 페이지·원본·디코딩·구문 분석·값 개수/크기 제한은 제품 manifest와 각 원본 페이지의 `complete`·`note`에 기록됩니다. 값이 제한을 넘으면 해당 값은 제외/부분으로 남고 잘린 값을 원문 값으로 내보내지 않습니다. 재시험 보고서는 전체 원본 위치·출처를 유지하고 소스맵 `sourcesContent` 코드 전체를 비밀값으로 분류하지 않습니다.

이번 수치는 현재 실제 실행에서만 얻습니다. 기존 공유 대화의 5,042개 등 과거 검증 수를 재사용하지 않습니다. 실제 Burp GUI·수동 브라우저 실행·모든 권한 정책 검증을 의미하지 않습니다.
