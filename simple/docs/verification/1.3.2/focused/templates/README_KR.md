# 실제 보존 번들의 backtick 보고서 추출 대조

공식 배포 JAR 1.3.1과 최종 고정 JAR 1.3.2를 각각 classpath 맨 앞에 놓고, 보존한 동일 Juice Shop 20.3.0 main 번들을 파서 격리용 합성 HTTP 패킷에 담아 보고서를 생성했습니다. 대상 앱에 새 요청을 전송하지 않은 오프라인 분석이며 새 실제 사이트 재시험·Katana·브라우저·Burp GUI 검증과 구분합니다.

- 동일 디코딩 본문: 1,241,893 UTF-16 문자, SHA-256 `a029a0ee1822c924fe7eef8aa9316e4c196ff54f3279c0286abb472971b375a4`
- 1.3.1: 전체 17행, backtick 0행, 동적 템플릿 0행
- 1.3.2: 전체 115행, backtick 64행, 동적 API 템플릿 4행
- backtick 64행은 모두 API라는 뜻이 아닙니다. API 4행과 PATH 60행이며 실제 경로 도달·고유 기능·취약점 수와 구분합니다.
- 두 보고서의 모든 본문 원문 span을 실제 UTF-16 substring과 대조했습니다. 동적 4행은 모두 `RAW_TEMPLATE_INTERPOLATED` / `DYNAMIC_UNRESOLVED`이고 `resolvedEndpoint`를 만들지 않았습니다.
- 동일 최종 JAR의 `ScopeInspectionReportTest` 119개를 통과했습니다. 전체 회귀 검사와 겹치므로 합산하지 않습니다.

정적 backtick은 `STRING`으로 cooked 문자열과 원문 표기를 따로 보존합니다. 동적 API는 `${...}` 식을 실행하지 않고 backtick 내부 원문을 value에, 전체 backtick 표기를 rawText에 둡니다. 태그 함수 결과·동적 시크릿·연결식 접두사는 실제 값으로 출력하지 않습니다. 정규식/나눗셈이 포함된 모호한 interpolation 경계 등은 이후 템플릿·패턴을 미검사로 남깁니다.

전체 minified 번들은 여전히 구문/노드·경계 한계가 있어 부분 결과입니다. 기존 독립 보고서 보충 파서의 294행을 제품이 모두 복원했다고 주장하지 않습니다. 한도와 개별 페이지 note는 `actual-bundle-1.3.2.json`과 `evidence.json`을 참고하세요. 소스·값 원문·실행 인증정보는 이 공개 증거 폴더에 넣지 않았습니다.

`ActualTemplateProof.java.txt`는 실행한 파서 대조 하네스입니다. `build/tests`의 `ScopeInspectionReportTest`·`WorkbenchTest`와 Montoya 의존성을 함께 사용하고, 검증할 JAR를 classpath 최우선에 둡니다. 첫 번째 인수는 디코딩 본문 파일, 두 번째는 값 원문을 포함하지 않는 증거 JSON 출력 경로입니다. Java 17 `--release 17`로 컴파일했습니다.

최종 검증 JAR SHA-256: `a8dd345b44b4893a301bf3092817335585c15ebd67cb5e6508bd3847fee49400`, 4536359 bytes. 이 JAR로 오프라인 after와 집중 119개 검사를 실행했습니다.
