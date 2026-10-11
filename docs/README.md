# 문서 목차

[저장소 첫 화면](../README.md) · [테스트 파일 다운로드](../downloads/README.md) · [PDF 가이드북](../downloads/guides/README.md)

## 사용과 이번 변경

| 문서 | 읽는 목적 |
|---|---|
| [커뮤니티 모의 진단 흐름](TEST_WORKFLOW_KR.md) | SCOPE·계정 설정부터 재현, 증거, 업무 마감까지 |
| [0.24.0 변경 안내](RELEASE_0.24.0_KR.md) | 이번 기능과 이전 버전의 동작 차이 |
| [0.24.0 화면·버튼 개선](UI_0.24.0_KR.md) | 이동한 버튼, 작은 화면 재현과 레이아웃 검사 |
| [0.24.0 검증 기록](VERIFICATION_0.24.0.json) | 실제 실행한 검사, 산출물, 환경상 남은 범위 |
| [0.24.0 인수인계](HANDOFF_0.24.0_KR.md) | 변경 범위, 재개 위치와 후속 작업 |
| [0.24.0 PDF 원고](GUIDE_0.24.0_KR.md) | 그림 가이드의 원문과 단계별 설명 |
| [저장소 안내](REPOSITORY_MAP_KR.md) | 파일 종류별 공식 위치와 과거 자료 구분 |

버전 문서는 해당 브랜치의 소스·산출물을 설명합니다. 현재 `main`의 합쳐진 상태는 [PROJECT_STATUS_KR.md](../PROJECT_STATUS_KR.md), 미병합 작업의 상태는 [Pull requests](https://github.com/ohjun1998/burp-workbench-kr/pulls)를 확인합니다.

## 개발과 협업

| 문서 | 읽는 목적 |
|---|---|
| [공통 작업 지침](../AGENTS.md) | 브랜치·검증·배포·보고 원칙 |
| [개발 환경 안내](../DEVELOPMENT_SETUP_KR.md) | Python·Java 준비와 `dev.py` 명령 |
| [두 ChatGPT 계정 협업](COLLABORATION_KR.md) | 역할 분담·PR·검토·충돌 처리 |
| [네이티브 검증 하네스](../project/burp-development-workspace/burp-workbench-kr/burp-workbench/validation/native/README_KR.md) | 실제 Burp 설치 후 별도로 구성하는 검증 |
| [사이트 3.5 모의 진단 시나리오](../project/burp-development-workspace/community-security-lab/BOUNTY_LAB_3.5_KR.md) | 객체별 권한 정책·저장·업로드·초기화 실습 |

## 이전 변경과 근거

| 자료 | 의미 |
|---|---|
| [0.23.0 변경 안내](RELEASE_0.23.0_KR.md) · [검증](VERIFICATION_0.23.0.json) · [인수인계](HANDOFF_0.23.0_KR.md) · [PDF 원고](GUIDE_0.23.0_KR.md) | 원본 증거 연결, 저장/조회 단계, 사이트 3.5.0 추가 |
| [0.22.0 / 0.22.1 변경 안내](RELEASE_0.22.0_KR.md) | 계정·업무 완료 조건, 중요 입력, 파일 확인 증거 |
| [0.22.0 인수인계](HANDOFF_0.22.0_KR.md) | 앞선 작업 범위와 남긴 과제 |
| [0.22.1 검증](VERIFICATION_0.22.1.json) · [0.22.0 검증](VERIFICATION_0.22.0.json) | 각 버전의 실행 결과; 버전별로 구분해 읽기 |
| [0.21.0 진단과 개선 후보](../audit/0.21.0/진단결과_및_개선안_KR.md) | 이전 실습에서 관찰한 문제와 우선순위 |
| [GitHub 협업 준비 검증](GITHUB_SETUP_VERIFICATION.json) | 저장소 초기 구성 때 확인한 결과 |
| [최초 인수인계](../이어가기_인수인계.md) | 0.21.0 패키지를 복원한 시점의 맥락 |
| [0.21.0 개발 환경 원문](archive/DEVELOPMENT_SETUP_0.21.0_KR.md) | 당시 Burp·브라우저·환경 복원 기록 |

`release/`는 이전 배포 원본, `history/`는 이전 문서·그림·PDF, `setup/`은 환경 복원 당시 기록, `reproduction/`은 재현 자료입니다. 여기에 적힌 “최신”이나 통과 수는 그 문서 작성 시점의 값입니다. 현재 버전의 실행 결과로 합산하지 않습니다.
