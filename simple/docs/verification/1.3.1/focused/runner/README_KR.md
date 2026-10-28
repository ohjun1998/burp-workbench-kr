# Runner 집중 검사

Java 17의 `--release 17`로 최종 Runner·EnvironmentContent·ScopeInspectionAnalysis와 fixture/test를 임시 디렉터리에 컴파일했습니다. 수정된 생산 클래스 overlay를 기존 1.3.1 JAR보다 먼저 배치하여 격리된 실제 loopback HTTP를 통한 **104개 검사**를 통과했습니다.

정상 비밀번호 폼의 세션 오탐, 실제 인증 차단, SPA shell 자동 발견, 경로 우선순위, 12개 기술 추정 경로 상한, 단일 `.env` 확인 및 fake 200 대조, 미확정 서버/POST의 GET 재추정 방지와 기존 원본·예산·중지/재개 흐름을 확인했습니다.

이 증거는 overlay 검사입니다. 실행 전 기반 JAR 해시는 고정하지 않았고 해당 경로는 뒤에 재빌드됐으므로 최종 배포 JAR 검증으로 해석하지 않습니다. 최종 JAR·7개 대상 AFTER 결과는 부모가 작성하는 별도 검증 근거를 사용합니다. 실제 Burp·native Katana·외부 대상 실행을 주장하지 않습니다.

검사 로그와 실행한 overlay 클래스·소스 해시는 `evidence.json`에 기록했습니다.
