# Workbench Next 0.53.1 main 반영

- 기록 날짜: 2026-10-27 (Asia/Seoul)
- 관련 PR: https://github.com/ohjun1998/burp-workbench-kr/pull/89

## 사용자 요청 (원문)

> 메인에도 업로드 해줘

## 사용자에게 표시한 응답 (원문)

> PR #89의 현재 상태와 병합 조건을 확인한 뒤 main에 반영하겠습니다.

> PR #89는 충돌 없이 병합할 수 있고, 검증된 소스와 배포 파일도 그대로입니다. 병합 요청을 대화 기록에 남긴 뒤 main에 합치고, 안내 문서의 현재 버전도 0.53.1로 갱신하겠습니다.

## 작업 근거 (요약)

사용자가 0.53.1의 main 반영을 명시적으로 요청했습니다. PR #89의 출발 main은 `45d79f91f1cfd217c1e41b81b408001a8c62a04e`, 배포 게시 head는 `52bdb02302d711b200bc9abfb4ea34b91958109e`입니다. 검증 소스 `5704f48a9d12282ec3f7ea48ec7a78065aa78695`의 [CI 실행 40334451367](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40334451367)은 release·windows·layout·browser 작업이 모두 성공했습니다. 게시 커밋은 문서와 새 버전의 다운로드 파일만 추가·수정했으며 검증한 생산 소스·빌드 입력과 기존 배포 파일을 바꾸지 않았습니다.

이번 병합 작업은 해당 검증 결과를 확인하는 작업이며 기능 검사를 새로 실행한 것으로 표시하지 않습니다. 실제 Burp GUI·Windows DPI/IME·Edge 수동·Docker·외부 서비스는 기존과 같이 미실행 범위입니다. 병합 결과는 확인 후 이 기록과 현재 상태 문서에 추가합니다.
