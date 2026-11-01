# 고정된 Simple 1.4.21에서 확인한 표시·연결 한계

은행 실제 실행 `native-runs/run-002`의 핵심 13개 검사는 통과했습니다. 아래 두 항목은 그 사실과 별도로 남은 제품 한계입니다. 배포 JAR나 Simple 소스를 수정하지 않았습니다.

## 기준 응답의 계정 이름 표시

- **조건:** 실제 기준 계정이 첫 번째 슬롯이 아닌 경우입니다. 은행 잔액 비교의 기준은 Alice(slot1, `REFERENCE`)인데 첫 슬롯은 admin입니다.
- **확인:** `AuthInlinePreview.refreshApprovedHeaders()`는 왼쪽 헤더에 `slot=i==0?0:selectedSlot`을 적용해 항상 `labels[0]`·`roles[0]`과 계정1 배지를 사용합니다. 반면 왼쪽 응답 본문은 `run.baseline()`을 그대로 읽습니다. [run-002 실제 사진](images/issues/auth-caption.png)에서도 이력의 Alice에 `200 · 기준`이 표시되지만 아래 기준 헤더는 admin으로 표시됩니다. 응답·배열·판정이 admin으로 바뀌었다는 증거는 없습니다.
- **영향·현재 확인 방법:** 기준 계정의 캡션·배지가 잘못되어 비교 해석에 혼동을 줍니다. 기존 **계정 간 차이** 탭의 일반적인 ‘기준 응답’ 표시와 **판정 근거** 탭의 계정별 결과를 함께 확인합니다. 최종 캡처 helper는 실제 Alice 기준/Bob 비교 기록을 선택해 이 두 기존 탭을 캡처하며 계정 값이나 판정을 덮어쓰지 않습니다.
- **소스 근거:** [본문의 실제 기준 응답](https://github.com/ohjun1998/burp-workbench-kr/blob/03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d/simple/burp-workbench/src/main/java/kr/workbench/AuthInlinePreview.java#L59), [고정 slot0 캡션·배지](https://github.com/ohjun1998/burp-workbench-kr/blob/03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d/simple/burp-workbench/src/main/java/kr/workbench/AuthInlinePreview.java#L79), [계정별 판정 표시](https://github.com/ohjun1998/burp-workbench-kr/blob/03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d/simple/burp-workbench/src/main/java/kr/workbench/AuthEvidencePanel.java#L16). `Context`에 `baseline` 필드가 있는 것은 아니며 실제 기준은 실행의 `REFERENCE` 계정과 `run.baseline()`으로 확인했습니다.

## 익명 원본 ZIP 저장 후 계정·업무 연결

- **조건:** 이번 ZIP은 인증 헤더가 없는 잔액 IDOR 응답 한 건을 담습니다. 실제 ZIP의 `evidence.json`에서 그 항목의 `accountId`가 빈 문자열임을 읽어 확인했습니다. 연결된 점검 계정으로 식별되지 않은 원본입니다.
- **확인:** `BountySupportPanel.writeEvidence()`는 파일 저장과 SHA 계산을 마친 뒤 `activity.evidenceSaved(...)`를 호출합니다. `AssessmentTracker.evidenceSaved()`는 유효한 계정·origin에 연결되는 refs가 0개이면 artifact를 만들지 않고 반환합니다. 이어서 `ActivityPanel.evidenceSaved()`는 파일이 있으면 `attachEvidenceFile(path)`를 호출하고, 해당 SHA의 artifact가 없으므로 `AssessmentTracker.java:1281`에서 예외가 발생합니다. 실제 run-002 `diagnostics.json`의 `점검 진행 현황 / IllegalArgumentException` 호출 위치가 이 체인과 일치합니다.
- **영향·현재 확인 방법:** ZIP 한 건의 생성·가림·CRC 검증 성공은 유지되지만 **계정·업무 현황으로의 자동 증거 연결은 미완료**입니다. 이미 저장된 익명 원본 ZIP을 별도 파일로 보존·제출하고 현황의 증거 완료로 집계하지 않습니다. artifact 기록이 없는 상태에서는 같은 ZIP 파일을 다시 선택하는 것만으로 이 연결 문제가 해결되지 않습니다. 별도의 계정 연결 저장 경로는 이번 실행에서 재검증하지 않았습니다.
- **소스 근거:** [ZIP 저장 후 연결 호출](https://github.com/ohjun1998/burp-workbench-kr/blob/03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d/simple/burp-workbench/src/main/java/kr/workbench/BountySupportPanel.java#L1457), [refs 0개일 때 반환](https://github.com/ohjun1998/burp-workbench-kr/blob/03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d/simple/burp-workbench/src/main/java/kr/workbench/AssessmentTracker.java#L1185), [후속 파일 연결 및 오류 메시지](https://github.com/ohjun1998/burp-workbench-kr/blob/03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d/simple/burp-workbench/src/main/java/kr/workbench/ActivityPanel.java#L735), [artifact SHA 확인](https://github.com/ohjun1998/burp-workbench-kr/blob/03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d/simple/burp-workbench/src/main/java/kr/workbench/AssessmentTracker.java#L1277).

첫 항목은 표시 문제, 둘째는 저장 후 연결 문제입니다. 이를 근거로 실제 HTTP 수신·계정 비교·ZIP 저장 전체가 실패했다고 해석하거나, 반대로 핵심 검사 통과만으로 이 두 경로까지 완료됐다고 해석하지 않습니다.

최종 core003의 동일 진단도 [진단 JSON](results/diagnostics.json)에 있습니다. 캡처 준비에서 올바른 기존 탭으로 이동한 [최종 Alice·Bob 비교](images/workbench/light/auth.png)와 [판정 근거](images/workbench/light/auth-judgement.png)를 함께 확인할 수 있습니다.
