# 계정 비교 레이아웃 0.39.1 마무리 — 2026-10-17

날짜는 Asia/Seoul 기준입니다. 사용자에게 보이는 대화만 기록합니다.

## 사용자 요청 원문

> 남은 단계 계속 진행해줘

첨부 화면은 “레이아웃 안 맞는데 확인해서 고도화 좀 시켜줘” 요청 이후 0.39.1 수정본 검증과 PR #55의 원격 검사·main 병합에서 멈춘 대화를 보여 줍니다.

## 답변 및 진행 안내 요약

- 첨부 이미지를 실제로 열어 중단 지점이 0.39.1 PR #55의 원격 검사 확인과 main 병합임을 확인했습니다.
- PR의 빌드·Windows·Linux 내구성 검사 3개 성공과 충돌 없는 상태를 확인한 뒤 main에 병합했습니다.
- 병합된 파일 트리가 검사에 통과한 PR head와 동일함을 확인했습니다.
- GitHub의 병합 커밋에서 JAR·전체 소스 ZIP을 내려받아 배포 목록과 SHA-256, 압축 CRC를 확인했습니다. 소스 ZIP 내부 211개 파일의 지문도 일치했습니다.
- 저장된 전체 화면·최소 크기 계정 상세·최소 크기 비교 상세의 Swing 렌더링 3장을 직접 확인했습니다. 이 작업에서 새로 실행한 네이티브 Burp GUI 검증을 의미하지 않습니다.
- main 자동 검증 결과를 확인하고 프로젝트 상태·검증 기록·이 대화 기록을 갱신했습니다.

## 병합과 배포

- 기능 PR: https://github.com/ohjun1998/burp-workbench-kr/pull/55
- 검사 PR head: 0d86b3afaa338d6cd54938154140dff979bcec38
- main 기능 병합: 8092b39d980d5ea757a3c99e239ee1e3098a5d3e
- 동일 파일 트리: b69e53ccf7302e1f66e926579c1ad8086bfb75eb
- JAR SHA-256: bb6141eaf4bf503b3c239c8e156b58ffc714c1a7bc2191444114074fcbd49809
- 소스 ZIP SHA-256: cb084dfa7055ff7b9a4228e49705e04b8e91934c30980dc418ff9ffe6954159c

main의 [Build and verify](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39036078185)와 [Windows·Linux 내구성 검사](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39036078202)가 모두 성공했습니다. 이 결과는 기능 병합 커밋 `8092b39d980d5ea757a3c99e239ee1e3098a5d3e`에 대한 원격 검사입니다.

기존 release 2,630개와 레이아웃 회귀 98개는 이전 작업의 기록이며 이번에 로컬에서 다시 수행한 검사로 합산하지 않습니다. 실제 네이티브 Burp GUI·Windows DPI/IME는 미수행입니다. 이번 후속 문서 변경에서 제품 소스·배포 파일·이전 버전은 수정하지 않습니다. 원시 도구 출력·숨겨진 추론·인증정보는 기록하지 않습니다.
