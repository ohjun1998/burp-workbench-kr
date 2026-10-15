# 소스 검토와 개선 우선순위 — 2026-10-15

검토 기준은 Workbench 0.25.5, Feature Mapper KR 0.1.0, 실습 3.5.2입니다. 자체 작성한 Workbench·Mapper·브라우저 도우미·실습 소스와 빌드·패키징·CI의 구조 및 주요 실행 경로를 검토했습니다. 아래 성능 항목은 코드에서 확인한 개선 후보이며, 고부하 벤치마크로 처리량·지연·절감량을 측정한 결과는 아닙니다. 실습의 의도적인 취약 경로는 제품 결함으로 집계하지 않습니다.

## 이번 업데이트에서 해결한 문제

Legacy 전송의 FutureTask 완료 공개와 busy 해제 순서 경쟁을 수정했습니다. 앞선 요청이 완료된 직후 다음 요청이 잘못 거절되던 문제가 독립 재현에서 확인됐으며, 수정 후 100,000회 순차 전송에서 잘못된 busy 거절은 없었습니다. 5,000회 순차 전송·비중첩·예외 후 복구 회귀를 추가했습니다.

Windows PowerShell 5.1의 한글 시작 스크립트 파싱 오류를 UTF-8 BOM으로 수정하고 실습 3.5.2로 배포했습니다. 검증 하네스의 의도적인 실패 종료 코드가 최종 성공을 실패로 만들던 부분과 0.25.4로 고정된 통합 검사도 보완했습니다. 상세 결과와 실제 실행 범위는 [검증 기록](VERIFICATION_DURABILITY_2026-10-15.json)을 기준으로 합니다.

## 우선순위

| 우선순위 | 코드에서 확인한 구조 | 개선 방향과 기대 효과 |
|---|---|---|
| P1 수집·화면·분석 분리 | `WorkbenchExtension`은 모든 메타데이터와 화면 행을 메모리에 유지합니다. 300ms 타이머에서 최대 50건을 표시하고 화면 스레드에서 원본을 다시 읽어 두 관찰자에 전달합니다. 디스크 읽기·쓰기는 같은 저장소 잠금을 사용합니다. | 전체 원본 디스크 보관을 유지하면서 페이지/가상 테이블, 묶음 행 갱신, 백그라운드 분석을 적용합니다. 패킷을 한 번 읽어 공유하고 대기 건수는 별도 카운터로 관리해 화면 지연과 저장 경합을 줄입니다. |
| P1 Mapper 탐색·저장 | 기능별 패킷 조회가 전체 패킷을 순회하고, 화면 갱신은 기능마다 이를 반복합니다. 브라우저 이벤트 연결도 최근 패킷·이벤트를 반복 탐색합니다. 프로젝트 ZIP 저장이 수집과 같은 잠금을 장시간 잡습니다. | 기능→패킷과 URL·메서드·본문 해시·시간 후보 인덱스를 추가합니다. 일관된 저장 스냅샷을 확보한 뒤 긴 복사·압축을 수집 잠금 밖에서 실행합니다. 애매한 이벤트 연결의 보수적인 판정은 유지합니다. |
| P1 큰 증거의 메모리 중복 | `BountySupportPanel.remember`는 최대 32개 증거의 전체 요청·응답 문자열을 보유합니다. 128KiB 텍스트 절단은 미리보기·출력 시점이며, ZIP 해시는 전체 파일을 다시 메모리에 읽습니다. | 패킷 ID/디스크 참조로 증거를 연결하고 미리보기만 제한합니다. 원본은 보존하며 해시·내보내기는 스트리밍합니다. 큰 응답을 문자열로 중복 보유하는 비용을 줄일 수 있습니다. |
| P2 JS 캐시 총량 | 대기열·최근 결과·원본·미리보기·복원 소스의 개수/개별 크기 제한은 있지만 여러 캐시가 별도 관리됩니다. | 총 바이트 예산과 통합 LRU 퇴출, 원본 지연 로딩을 추가합니다. 대기 바이트·절단 여부를 표시해 메모리 사용을 예측하기 쉽게 만듭니다. |
| P2 화면 갱신 범위 | Activity는 revision 변경 시 전체 행을 다시 만들고, Assessment는 계정·API·업무 목록을 재계산합니다. | 변경 행 갱신과 통계 캐시를 사용하고 보이지 않는 탭의 갱신을 지연합니다. 동일 데이터를 반복 계산하는 시간을 줄입니다. |
| P2 유지보수·릴리스 일관성 | 큰 UI 클래스에 상태·전송·판정 로직과 긴 한 줄 메서드가 섞여 있습니다. 버전 문자열이 빌드·화면·테스트에 분산되어 실제로 이전 JAR를 선택하는 검사가 있었습니다. | 포맷을 통일한 뒤 캡처·전송·판정·화면 책임을 단계적으로 분리합니다. BuildInfo를 버전의 기준으로 삼고 증거 schemaVersion은 별도로 관리합니다. 현재 배포 파일을 검증하는 패키징 검사는 유지합니다. |

50건/300ms ≈ 167건/초는 현재 타이머의 표시 처리 예산입니다. 실측 수집 처리량이나 손실률을 뜻하지 않습니다. 원본 수집에 예전의 개수·본문 제한을 다시 도입하는 개선안도 아닙니다.

## 근거 파일

- 수집: [WorkbenchExtension.java](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java/kr/workbench/WorkbenchExtension.java)의 `incoming`, `drain`, `addTraffic`, [DiskTrafficStore.java](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java/kr/workbench/DiskTrafficStore.java)의 동기화 읽기·쓰기.
- Mapper: [Mapper.java](../feature-mapper/src/main/java/kr/featuremapper/Mapper.java)의 `applyBrowserEvents`, `packetList`, [FeatureMapperExtension.java](../feature-mapper/src/main/java/kr/featuremapper/FeatureMapperExtension.java)의 `refresh`, `chooseFile`, [ProjectIO.java](../feature-mapper/src/main/java/kr/featuremapper/ProjectIO.java)의 `save`.
- 증거: [BountySupportPanel.java](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java/kr/workbench/BountySupportPanel.java)의 `remember`, `writeEvidence`, [BountyEvidence.java](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java/kr/workbench/BountyEvidence.java)의 출력·해시 처리.
- 분석·화면: [JsAnalysisPanel.java](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java/kr/workbench/JsAnalysisPanel.java), [ActivityPanel.java](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java/kr/workbench/ActivityPanel.java), [AssessmentPanel.java](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java/kr/workbench/AssessmentPanel.java).

## 고도화 방향

1. **Workbench 프로젝트 저장·재개:** 현재 임시 원본 저장소를 프로젝트 단위로 이어서 사용할 수 있도록 세그먼트·색인·무결성 manifest를 설계합니다. Mapper의 기존 `.fmkr` 저장 기능과 겹치는 부분은 재사용 가능성을 먼저 검토합니다.
2. **기능·계정 점검·증거의 공통 패킷 ID:** Workbench와 Mapper에서 같은 원본을 다시 선택하는 작업을 줄이고, 어떤 계정·기능·판정의 근거인지 추적할 수 있게 합니다. 가림 처리한 텍스트 증거와 실제 원본 바이트의 구분은 유지합니다.
3. **성능 기준의 자동 검증:** 수집량·본문 크기·계정/기능 수를 조합해 콜백 지연, EDT 지연, 화면 반영 대기시간, 힙, 디스크 증가량을 측정합니다. 기능 회귀와 별도로 비교해 최적화 전후 효과를 확인합니다. push와 PR에서 중복되는 무거운 검사는 검증 공백이 생기지 않도록 실행 구조를 정리할 수 있습니다.

이미 있는 보호·최적화는 유지해야 합니다. JS의 대기열 100개·최근 결과 100개·512KiB 분석 원본·2MiB 수신 제한, 증거의 민감값 가림과 텍스트/이미지 제한, Mapper의 원자적 저장·가져오기 항목 허용목록·참조 검증, 계정 역할·조직 변경 시 재검토 처리는 구현되어 있습니다.

먼저 P1 수집 경로를 계측·분리하고, Mapper 인덱스/저장 잠금과 증거 참조를 개선한 뒤 P2 화면·캐시·구조 정리로 진행하는 순서를 권장합니다. 이 문서는 개선 설명이며 해당 구조 변경을 이번 0.25.5에 추가했다는 의미는 아닙니다.
