# Workbench 0.47.0 · 기능 자동 매핑 통합

별도 Java 확장으로 설치하던 Feature Mapper를 Workbench의 **트래픽 → 기능 자동 매핑** 메뉴에 넣었습니다. Workbench JAR 하나에서 기존 10개 도구와 Mapper를 포함한 11개 도구를 사용합니다.

## 바뀐 사용 흐름

| 항목 | 변경 후 |
|---|---|
| 설치 | Workbench 0.47.0 JAR 하나. 이전 Workbench와 별도 Mapper JAR는 해제 |
| 메뉴 | 트래픽 폴더의 ‘기능 자동 매핑’. 기존 10개 메뉴와 입력은 유지 |
| 수집 | 요청·응답 목록과 OFF/SCOPE 공유, 기본 OFF. 어느 화면에서 바꿔도 함께 변경 |
| 패킷 전달 | Workbench가 수집한 디스크 원본을 기존 관찰 작업자가 Mapper에 한 번씩 전달 |
| 기능별 관리 | 기능·실행·세션·분류 근거·이름·상태·메모·분류 수정 유지 |
| 프로젝트 | 기존 `.fmkr` 저장/열기와 전체 원본 패킷 유지 |
| 브라우저 | ‘매핑 설정 → 브라우저 연결’. Chrome/Edge 보조 확장 0.3.2는 브라우저에 설치 |
| 종료 | Workbench 해제 시 Mapper의 임시 원본·브리지·작업자·타이머도 정리 |

수집을 OFF로 바꾸면 새 패킷과 브라우저 이벤트를 수집하지 않습니다. 이미 수집한 대기 원본은 분류할 수 있습니다. SCOPE는 Workbench의 체크된 범위와 Burp 제외 조건을 따릅니다. Repeater에 준비는 요청만 옮기며 Send는 직접 실행합니다.

브라우저의 버튼 이름은 같은 요청의 메서드·URL·시각·본문 해시와 대조합니다. 공통 디스크 흐름에서도 요청 시작 시각을 보존합니다. 동일 요청이 겹쳐 연결이 모호하면 기존 Mapper의 보류 동작을 유지합니다. 기능 분류와 세션 지문은 취약점 확정이나 실제 사람·역할 확인을 뜻하지 않습니다.

## 구현과 검증

독립 배포와 내장 모드가 `FeatureMapperWorkspace`를 공유합니다. Workbench JAR에는 별도 Montoya 진입점을 넣지 않고, Burp 탭·HTTP 수집 콜백·종료 흐름을 Workbench가 소유합니다. 원본 처리와 분류는 디스크 관찰 작업자에서 실행합니다. 내장 오류는 공통 로그에도 기록합니다.

새 통합 회귀는 공통 수집, Burp 제외 범위, History 가져오기, 요청 시작 시각, 실제 loopback 브라우저 메타데이터 연결, `.fmkr` 원본 복원과 종료 정리를 확인합니다. 전체 검사 수와 실행 범위는 [검증 기록](VERIFICATION_0.47.0.json)에 기록합니다. 자동 검사는 API 어댑터·헤드리스 Swing·격리 HTTP 서버이며, 실제 Burp GUI 및 Chrome/Edge 보조 확장의 실제 DOM 동작과 구분합니다.

[PR #78](https://github.com/ohjun1998/burp-workbench-kr/pull/78)의 Build·Mapper·Windows/Linux 검사가 모두 성공한 뒤 main에 병합했습니다. 원격 실제 Chromium 도우미 DOM 검사 13개는 운영 Mapper/Bridge를 사용하는 테스트 서버에서 통과했으며 실제 Burp 인스턴스는 아닙니다. 180초 수집은 Linux 3,624건·Windows 3,622건 모두 누락·처리 오류 0이고, 실제 디스크 부족 뒤 내장 Mapper의 저장 재개도 확인했습니다. 상세 근거는 검증 기록의 `remoteVerification`에 있습니다.

## 설치 파일

- [Workbench 0.47.0 JAR](../downloads/extensions/0.47.0/burp-workbench-kr-0.47.0.jar)
- [소스 ZIP](../downloads/extensions/0.47.0/burp-workbench-kr-0.47.0-source.zip)
- [Chrome/Edge 보조 확장 0.3.2](../downloads/feature-mapper/0.3.2/feature-mapper-browser-0.3.2.zip)
- [매핑 사용법](../feature-mapper/README_KR.md)

독립 Mapper 0.3.2는 호환용 배포입니다. 통합 Workbench를 사용할 때 별도 Mapper JAR를 함께 추가할 필요가 없습니다.
