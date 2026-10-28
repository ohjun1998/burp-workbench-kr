# Simple 버전별 메뉴 탐색 재시험 보고서

새 생성기는 `audit/simple-retest/build_report.py`의 원문 검증·서식 로직을 별도 경로로 보존·확장한 도구입니다. 이전 1.3.0/1.3.1 생성기와 보고서 파일은 바꾸지 않습니다. 새 재시험은 재시작한 원본 앱·갱신한 동일 로컬 계정 조건으로 실행한 **1.3.1 → 1.3.2** 두 버전을 읽습니다.

```sh
python -m unittest discover -s audit/simple-navigation-retest -p 'test_*.py' -v
python audit/simple-navigation-retest/build_report.py \
  --before-version 1.3.1 --after-version 1.3.2 \
  --results /workspace/simple-navigation-retest-runtime/results \
  --metadata-root /workspace/simple-sites \
  --auth-root /workspace/simple-navigation-retest-runtime/auth \
  --supplemental-results /workspace/simple-navigation-retest-runtime/supplemental-realm \
  --supplemental-validation audit/simple-navigation-retest/realm-direct-verification.json \
  --checks /path/to/current-improvement-checks.json \
  --site-validation audit/simple-navigation-retest/results-summary.json \
  --regression-validation simple/docs/VERIFICATION_SIMPLE_1.3.2.json \
  --navigation-validation /path/to/current-navigation-validation.json \
  --navigation-screenshots /workspace/simple132-browser-after-sealed \
  --out /workspace/simple-navigation-1.3.2-report \
  --browser-check --require-complete-matrix \
  --public-summary docs/reports/2026-10-28-simple-navigation/public-summary.json
```

`--require-complete-matrix`는 고정한 7개 실제 사이트, 버전별 root 및 같은 direct/protected 시나리오, 총 28회, 같은 시작·Scope·요청 예산·전체 parameters/선택 프로필 ID를 요구합니다. 한 버전의 여러 후보 JAR·중복 실행·이전 버전으로 잘못 표기한 실행은 거부합니다. 완전한 행렬이 있어도 중단·실패·부분·미확인 결과는 유지하며 전체 사이트 점검 완료로 해석하지 않습니다. 자동 root 탐색에서 merchant 경로가 미관측인 기능 확인 게이트는 `review`로 보존할 수 있으나, 실행 누락·해시·입력·계정·예산 등 무결성 실패는 거부합니다.

추가 `--supplemental-results`와 `--supplemental-validation`은 merchant realm URL을 직접 seed로 지정한 별도 전후 2회의 실제 근거입니다. 본 28회와 합치지 않으며 두 버전의 JAR·조건·제품 exporter·검증을 별도로 대조합니다. 원문 값에는 `scenario=realm-direct`, `collectionMode=supplemental-direct-contrast`를 표시합니다. 직접 대조 성공을 자동 root 발견 성공으로 바꾸지 않습니다.

원본 앱 출처 metadata와 새 테스트 로그인 인증 파일은 독립 경로로 읽습니다. 이전 실행의 인증값을 새 실행에서 관측했다고 섞지 않습니다. 두 버전 모두 제품 exporter가 제공하는 OriginalID·계정 revision·응답 SHA·정확한 UTF16 span을 대조합니다. 제품 탐지 / 제품 보고서 보충 파싱 / 독립 보고서 추가 파싱 / 테스트 로그인 인증값을 나눕니다. 값 수는 원본 위치별 관측 수이며 고유 취약점·시크릿 수가 아닙니다.

결과 폴더는 Git 체크아웃 밖이어야 합니다. PDF는 대표 원문값과 전후 결과를 읽기 쉽게 선별하고, 전체 파싱값·원문 표기·분류·정확한 위치와 해시는 `report.html`, `raw-values.json`, `raw-values.csv`, `report.json`에 축약 없이 저장합니다. API 동적 템플릿의 `${...}`는 미확정 코드 표기이며 식 평가·실제 URL 추측·폼 제출·키 유효성 검사를 하지 않습니다.

`--navigation-validation`은 실제 Chromium의 격리된 동적 메뉴 fixture 증거를 별도 절에 포함합니다. 일곱 원본 앱의 GET/HEAD Runner/Katana 재시험과 합산하지 않으며 실제 Burp GUI·수동 전체 메뉴 검증으로 표현하지 않습니다. `--browser-check`는 보고서 HTML 자체의 검색·분류·다운로드·오류·외부 HTTP 0건을 확인하는 별도 서식 검사입니다.

원문 보고서·실행 인증·HTTP corpus는 공개 Git에 넣지 않습니다. `--public-summary`는 고정 허용 필드만 남깁니다. 전달용 `parsed-values.zip`과 상세 `evidence.zip`은 PDF·HTML 시각검토와 최종 metadata 확인 후 다시 봉인하며, `SEALED.json`이 있는 출력 폴더는 생성기가 덮어쓰지 않습니다. 새 결과에는 새 폴더를 사용합니다.

Python 3.10+, PDF용 `reportlab`·`PyMuPDF`·`Pillow`, 선택 HTML용 Node·Playwright·Chromium이 필요합니다. 표준 라이브러리 단위검사는 이 선택 도구들을 import하지 않습니다. 한국어 글꼴은 저장소의 NanumGothic 파일을 사용합니다. 상세 원문 좌표·HTTP 재구성·I/O 실패의 뜻은 이전 [서식 기준](../simple-retest/REPORT_FORMAT_KR.md)을 따르되 이전 검사 건수·보고서 값 수를 이번 실행 결과로 재사용하지 않습니다.
