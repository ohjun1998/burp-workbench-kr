# Simple 1.4.6 main 병합 완료

- 확인 시각: 2026-10-30T19:18:48.035525+09:00 (Asia/Seoul)
- 기준 main: `f83b356c40a3288717dc6c39c9c1318809b76d7c`
- 검증·배포 작업 커밋: `a797e8d1cd03c05411974bc0de6b84629a26ff5e`
- main 병합 커밋: `15a4683e06ed75e70bd100f9ce944406760d078a`
- 방법: 사용자 명시 요청에 따른 일반 Git 병합·push. PR API는 환경 네트워크 정책에서 지원되지 않아 사용하지 않았습니다. 강제 push·보호 규칙 우회는 없습니다.
- 작업 브랜치와 main 병합 결과의 `simple/` 트리가 일치합니다. 원본 Next와 이전 버전 산출물은 변경하지 않았습니다.
- [공개 JAR](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/main/simple/downloads/1.4.6/burp-workbench-simple-1.4.6.jar)를 main에서 다시 다운로드해 SHA-256 `868b8f7369f5d71aa00b19635cfea83db8092b7e3dad501b5758c6ff0e6fd67b` 일치를 확인했습니다. [원격 파일 확인](PUBLIC_ARTIFACT.json)
- `python simple/dev.py check` 최종 종료 코드 0: 회귀 5,587 + payload HTTP 136 + Mapper Java 145 + 도우미 Node 29 = 5,897개. 41개 시안/선택 데이터 검사는 이 수에 포함됩니다.
- 실제 Swing 렌더링 164개(3크기·2테마의 기본 도구 96개 + 통합 점검 68개), 잘림·필수 영역 붕괴 0건. 고정 Katana 102개 검사 통과.
- `python simple/dev.py package`와 `verify` 통과: JAR·소스 ZIP·도우미 ZIP 체크섬, 소스 지문과 ZIP CRC 확인. 독립 소스 ZIP에 승인 시안·실제 캡처·비교 페이지 포함.

[실제 8개 화면](../../../simple/docs/images/1.4.6/README.md) · [시안 대조](../../../simple/docs/design/1.4.6/COMPARE.html) · [변경 안내](../../../simple/docs/RELEASE_SIMPLE_1.4.6_KR.md) · [검증](../../../simple/docs/VERIFICATION_SIMPLE_1.4.6.json)

1.4.5는 시안 전체 외형과 달랐고 사용자 정정 후 이번 버전에서 다시 구현했습니다. 생성 시안과 운영 Swing 캡처를 구분합니다. 실제 Burp Community 2026.9.2 GUI·Windows DPI/IME·수동 브라우저는 미실행이며 픽셀 완전 동일을 주장하지 않습니다.
