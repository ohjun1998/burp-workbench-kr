# 0.25.3 — 수집 모드와 SCOPE 관리

개발 기준은 `account-a/0.25.2-unlimited-traffic`의 `97da5217243eb88f2b3357c090352c8bee105dda`입니다. 0.21.0 소스를 수정한 배포가 아닙니다. 0.25.2의 무제한 건수·큰 패킷 원본 디스크 보관 및 오류 복구를 유지합니다. 기존 배포는 보존하고 0.25.3 JAR와 소스 ZIP을 별도 제공합니다.

## 변경된 화면

| 기능 | 동작 |
|---|---|
| 수집 버튼 | 클릭할 때 ON → OFF → SCOPE → ON 순환. 기본 ON |
| ON | 범위와 무관하게 새 트래픽 수집 |
| OFF | 신규 트래픽 수집 중지. 기존 보관 항목은 유지 |
| SCOPE | SCOPE 목록에서 체크된 규칙에 맞는 신규 트래픽만 수집 |
| 검색 입력 | 메서드·URL·상태에 키워드가 포함된 항목을 즉시 표시. 내용을 지우면 전체 표시 |
| Burp Add to scope | 기존 등록 범위는 확장 로드 시, 이후 변경은 Scope 알림으로 자동 반영 |
| SCOPE 체크 | 체크/해제 즉시 신규 SCOPE 수집에 적용. 체크 상태는 프로젝트 확장 설정에 저장 |
| 선택 삭제 | 강조한 행과 무관하게 체크된 규칙만 삭제 |
| 일괄 삭제 | 체크 여부와 무관하게 Workbench SCOPE 목록 전체 삭제 |
| URL 추가 | HTTP(S) URL을 입력해 추가. 같은 경로와 하위 경로 포함. 쿼리·프래그먼트 제외 |

요청·응답 목록에서 기존 수집 ON 체크박스, Scope만, 검색 초기화, 목록 비우기, 내보내기를 제거했습니다. 별도 검색 버튼 없이 입력만으로 검색합니다. SCOPE 목록의 규칙 내보내기와 규칙 불러오기·교체도 제거했습니다.

## 사용 순서

1. 기존 확장을 해제하고 [0.25.3 JAR](../downloads/extensions/0.25.3/burp-workbench-kr-0.25.3.jar)를 Extensions → Add → Java로 등록합니다.
2. Burp HTTP history에서 대상 요청을 우클릭해 **Add to scope**를 선택합니다.
3. Workbench **요청·응답 목록 → SCOPE 목록**에서 URL과 체크 상태를 확인합니다. 아직 트래픽이 없는 등록 URL도 목록에 표시됩니다.
4. 수집 버튼을 ON → OFF → **SCOPE**로 바꾸고 사이트를 사용합니다. 체크한 대상의 새 요청·응답이 수집됩니다.
5. 필요하면 체크 해제, URL 추가, 선택 삭제 또는 일괄 삭제를 사용합니다.

## 범위와 저장 동작

Burp에서 가져온 범위는 Burp의 URL prefix 또는 고급 규칙을 유지하고, Burp의 제외 규칙도 적용합니다. Workbench의 직접 추가 URL은 기존 프로토콜·호스트·포트·경로 경계 규칙을 사용합니다. 체크는 규칙 단위이며 여러 규칙이 겹치면 하나라도 체크된 일치 규칙이 수집을 허용합니다.

Workbench 체크·삭제는 Burp Target Scope를 변경하지 않습니다. 삭제한 Burp 항목은 동일한 설정의 재동기화나 재로드에서 즉시 되살아나지 않습니다. URL 추가로 직접 다시 등록하거나 Burp에서 해당 규칙을 제거한 뒤 다시 Add to scope하면 새 항목으로 등록됩니다.

OFF/SCOPE 변경은 이후 수신되는 트래픽에 적용합니다. 변경 전에 이미 수집 대기열에 들어간 항목은 목록으로 반영됩니다. 기존 트래픽은 체크/검색/모드 변경으로 삭제하지 않습니다. Burp 우클릭의 수동 가져오기도 현재 수집 모드를 따릅니다. 기존 History 전체를 자동 이관하지 않으며, 기존 방식처럼 응답이 수신된 요청을 자동 보관합니다.

트래픽 원본은 실행 중 임시 디스크에 보관하고 확장 해제 시 정리합니다. 체크 상태와 규칙은 Burp 프로젝트의 확장 설정에 저장하며, 임시 프로젝트는 종료 시 영구 저장되지 않을 수 있습니다. 수집 모드 버튼은 재로드 시 ON으로 시작합니다. 다른 분석·검사 도구의 자동 실행 설정은 이번 트래픽 목록 버튼과 별개입니다.

## 검증

[현재 실행 기록](VERIFICATION_0.25.3.json)과 [작업 인수인계](HANDOFF_0.25.3_KR.md)를 확인하세요. 운영 클래스·Swing 구성 요소와 모의 Montoya 호스트를 사용한 회귀, 격리된 loopback HTTP 검사를 수행합니다. 실제 Burp GUI에서 Add to scope를 클릭하거나 Docker를 실행한 검증과는 구분합니다.

연동 API는 PortSwigger의 [BurpSuite 프로젝트 설정 내보내기](https://portswigger.github.io/burp-extensions-montoya-api/javadoc/burp/api/montoya/burpsuite/BurpSuite.html)와 [Scope 변경 알림](https://portswigger.github.io/burp-extensions-montoya-api/javadoc/burp/api/montoya/scope/Scope.html)을 사용합니다.
