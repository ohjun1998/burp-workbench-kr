# Workbench Strix 1.0.1

사용자가 지정한 Simple **1.4.21** 배포 커밋 `03c6204747ef4a0a0052e4d7fe86c1f65ff9bc6d`의 소스와 레이아웃을 기준으로 Strix 진단 기능만 연결했습니다.

- 기존 디자인·색상·폰트·입력칸·카드·원문·상단 46px 헤더·190px 메뉴·42px 통합 점검/대시보드 강조형을 보존합니다.
- 기존 13개 도구, 계정·기능·원문 ID·버튼·필터·문서·레이아웃 설정과 Mapper0.3.21/helper0.4.1을 유지합니다.
- 선택 원본의 우클릭 **선택 원본으로 Strix 진단 준비**, 공통 설정의 **Strix 진단 열기**가 추가됩니다. 기존 화면을 다른 디자인으로 재구성하지 않습니다.
- Strix는 별도 창에서 기대 정책·제공자·모드·턴·시간·실행계획을 준비합니다. 기본 취소인 실행 확인, ID·해시·SCOPE·프로젝트 검증, 중지와 보고서 미리보기를 유지합니다.
- 제품명은 Workbench Strix이며 기존 Simple/Strix 설정과 진단 기록을 유지하는 업데이트입니다. `simple.*`와 `.burp-workbench-simple`을 이어 쓰며 같은 저장 공간의 이전 확장은 언로드합니다.

[설치·소스·체크섬](../downloads/strix/1.0.1/) · [가이드](../../docs/reports/2026-11-17-workbench-strix-1.0.1/guide/index.html) · [비교·검증·캡처](../../docs/reports/2026-11-17-workbench-strix-1.0.1/README_KR.md)

Simple1.4.22의 A 그룹형과 기능 중심 HTML 시안은 이 버전에 포함하지 않습니다. Workbench Strix1.0.0과 모든 기존 배포는 보존합니다. 실제 Burp 본체와 모델 추론·실제 Strix 대상 진단은 자동 패널 검사/합성 API 어댑터와 구분해서 기록합니다. 최대 에이전트 턴은 HTTP 요청 예산이 아니며 실행 전 SCOPE 확인은 전체 Strix 네트워크 격리를 뜻하지 않습니다.
