# Simple 1.4.0 원터치 고도화 검증 계획

새 기능은 원문 값 증가와 실제 탐색 개선을 나누어 확인합니다. 이전 Simple 1.3.2 배포·가이드·봉인 보고서를 변경하지 않습니다. 과거 검증 수를 새 실행 수로 합산하지 않습니다.

| 영역 | 입력·대조 | 확인할 근거 |
|---|---|---|
| 제품 분석·공통 파서 | 보존한 실제 7개 로컬 앱의 동일 응답을 1.3.2와 1.4.0에 오프라인 입력 | 선별 원문 항목의 위치/값 지문과 실제 제품 finding 연결, 원문 exporter 값·소스 좌표 일치 |
| 분류 정확도 | 실제 응답의 CSS 아이콘, DOMTokenList, Swagger 코드 조각, 자리표시자·데모·테스트 쿠키 | 잘못된 DB/유효 비밀값 승격 여부와 보고서의 예시·공개·미해결 분류 |
| 경로 처리 상태 | 격리 loopback의 링크·정적 API·템플릿·Scope 제외·실패·한도 | 발견과 실제 요청·응답·분석 상태를 분리하고 미방문 사유를 보존 |
| 계정·재점검 | 동일 URL·다른 제공 계정·인증 revision·401/403·세션 만료·예산 | 실제 신원 실패 시 정지, 별도 영역 거부와 접근 성공 구분, 선택한 안전한 항목만 재점검 |
| SPA·통신 연결 | 실제 Chromium의 지연 DOM·메뉴·Fetch/XHR·실패·iframe fixture | 실제 요청/응답 상태와 메뉴 연결, Scope·메서드 제한, 브라우저와 HTTP 계정 분리 |
| UI·가이드 | 운영 Swing에 합성 증거를 넣은 화면 | 원터치 흐름·필터·원본·미확인 선택 재점검과 최소 창 배치 |
| 배포 | 새 JAR·소스 ZIP·helper ZIP·PDF | 현재 소스 지문·CRC·SHA, 이전 버전 바이트 보존 |

## 오프라인 원본 응답 비교

`prepare_corpus.py`는 기존 검토 목록의 고정 증거 ID에서 응답·디코딩 텍스트를 선택합니다. 원문 위치와 SHA를 검증한 뒤 인증·값을 포함할 수 있는 private 입력을 저장소 외부 폴더에 보관합니다. 공개 요약은 값 SHA와 증거 ID·집계만 포함합니다.

```sh
python audit/simple-onetouch-140/prepare_corpus.py \
  --raw-values /workspace/simple-navigation-1.3.2-report/raw-values.json \
  --results /workspace/simple-navigation-retest-runtime/results/1.3.2 \
  --out /workspace/simple-onetouch-140/corpus

python audit/simple-onetouch-140/run_offline.py \
  --jar simple/downloads/1.3.2/burp-workbench-simple-1.3.2.jar \
  --corpus /workspace/simple-onetouch-140/corpus/private-corpus.json \
  --out /workspace/simple-onetouch-140/offline/1.3.2
```

같은 private corpus를 새 버전에도 입력합니다. 이 검사는 **새로운 크롤링·실시간 계정 검증·서비스 전체 탐지율 측정이 아닙니다.** 위치 기반 제품 연결이 되지 않은 원문 항목, 출력 제한, 부분 분석은 그대로 공개 집계에 남깁니다. UI masking과 명시적 로컬 원문 export는 서로 다른 출력 경로입니다.

현재 고정 선택은 7개 사이트·38개 응답의 주요 원문 항목79개와 분류 대조17개입니다. 주요 항목은 사이트·값 기준78종이며, 같은 VulnBank DB 호스트를 필드와 내부 호스트로 각각 검토한 두 행을 유지합니다. 위치/값 지문 연결 행수와 고유 사이트·값 수를 별도 출력하며 취약점·고유 탐지 개수로 합산하지 않습니다.

```sh
python audit/simple-onetouch-140/compare_offline.py \
  --before /workspace/simple-onetouch-140/offline/contract-v3/1.3.2 \
  --after /workspace/simple-onetouch-140/offline/final/1.4.0 \
  --corpus audit/simple-onetouch-140/corpus-summary.json \
  --out /workspace/simple-onetouch-140/offline/final/comparison.json

python -m unittest discover -s audit/simple-onetouch-140 -p 'test_*.py' -v
```

대조기는 입력·하네스·응답 SHA, producer 버전, 전체 truth ID의 일치를 요구합니다. 예시/공개/쿠키/개인정보/동적값을 실제 비밀값 후보로 승격했는지는 runtime category뿐 아니라 의미 분류와 보고서 분류로 함께 확인합니다. 초기 개발 후보의 결과와 최종 배포 SHA의 결과는 다른 폴더에 보존합니다.

새 exporter의 모든 `rawText`가 실제 원문의 UTF-16 `offset`·`length` 부분 문자열과 일치하고 원본 응답 SHA도 같은지 검증합니다. 이전 독립 보충 파서는 백틱 내부만 원문 리터럴로 보존한 항목이 있어 현재의 백틱 전체 근거와 문자 그대로 다를 수 있습니다. 이 기존 리터럴 비교와 현재 실제 소스 좌표 검증을 별도 지표로 출력합니다.

## 환경 제약

확인 시점의 `/workspace/tools`에는 JDK 17·Katana 1.8.0이 있으며 `/opt`와 도구 폴더에서 native Burp 설치를 확인하지 못했습니다. 파일명 검색만으로 시스템 전체 부재를 단정하지 않습니다. 실제 Burp GUI·native MV3/DNR 검증은 실행 증거가 생기기 전에는 미검증으로 둡니다. 관리형 Chrome 정책을 우회하지 않으며 DOM/API adapter 검증과 native extension 설치 검증을 구분합니다.
