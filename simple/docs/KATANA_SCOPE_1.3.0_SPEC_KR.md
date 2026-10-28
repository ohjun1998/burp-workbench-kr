# Simple 1.3.0 Katana 기반 SCOPE 수집의 적용 범위

지정한 SCOPE에서 Katana로 발견한 메뉴·경로·API를 기존 환경·민감정보·계정 비교·이력·커버리지 흐름에 연결하는 요구사항입니다. 기존 8개 도구와 [1.2.0 통합 점검 기준](history/1.2.0/SCOPE_INSPECTION_1.2.0_SPEC_KR.md)을 유지합니다. **이 연결표 자체는 구현 완료나 검사 통과 근거가 아닙니다.** 실제 결과는 [1.3.0 검증 기록](VERIFICATION_SIMPLE_1.3.0.json)을 확인합니다.

## 공통 흐름

Katana 별도 설치·버전 확인 → SCOPE URL·HTTP 계정·공통 예산 선택 → Katana·통합 점검 실행 → 환경·경로·API·민감정보·차이·미확인 검토 → 같은 원본에서 기존 도구·증거로 연결합니다. 표준 Katana와 브라우저 helper의 실행 주체·예산·근거를 구분합니다.

## 요구사항·구현·검사·가이드 연결표

| ID | 적용할 요구사항 | 구현 연결 | 확인할 경계 | 가이드·캡처 |
|---|---|---|---|---|
| K01 | 기존 8개 도구·Simple 흐름 유지 | `SimpleNavigation`, `WorkbenchExtension`, `ScopeInspectionPanel` | 별도 9번째 도구 없이 통합 점검의 선택 기능·결과 탭으로 제공; 화면 열기만으로 대상 전송 없음 | 기존14장·통합 설정 `12` |
| K02 | 공식 외부 Katana 1.8.0 설치·버전 확인 | `tools/install_katana.py`, `docs/KATANA_DEPENDENCY.json`, `ScopeKatana.probe` | 공식 고정 SHA·플랫폼 검증; 외부 절대 경로; 실행 파일 미번들; 대상 요청 없는 설치 확인 | 설치 안내·`23-katana-settings.png` |
| K03 | 필수 인증 loopback HTTP/HTTPS 통로 | `ScopeKatana`, `ScopeKatanaProxy` | 인증 없는 프록시 요청 거부; HTTPS CONNECT·실행용 임시 TLS; 사용자/시스템 CA 설치 없음 | Katana 준비·`23`·`24` |
| K04 | 같은 Workbench 전송·SCOPE·계정·예산 적용 | `ScopeInspectionRunner.fetchKatana`, `ScopeInspectionPanel.katanaResponse`, `WorkbenchScope` | 조회 메서드·현재 Scope·Origin·포트·경로·제외/리디렉션 확인; 선택 HTTP 프로필 사용; 기준·신원 요청까지 공통 예산 | 설정·경로·커버리지 `12`·`15`·`20` |
| K05 | 대상 인증값의 외부 프로세스 분리 | `ScopeKatana.command`, `ScopeKatanaProxy`, `ScopeInspection.Profile` | 대상 Cookie·Authorization을 CLI/설정 파일에 전달하지 않음; 프록시 인증은 실행 전용; 원문 출력·비밀값의 로그/이력 보존 없음 | 설치·판정 기준·원본 `22` |
| K06 | HTML·JS·폼 메타데이터·기술 단서 수집 | `ScopeKatana`, `ScopeInspectionRunner.offerKatana`, `ScopeInspectionAnalysis` | 발견 출처와 실제 응답 분리; JSONL 메타데이터 재전송 없음; 폼 메서드·필수값 미확정은 후보; 표준 모드의 JS는 정적 분석 | Katana·API `24`·`16` |
| K07 | 환경·DB·버전 근거와 주요 경로 분석 | `ScopeInspectionAnalysis`, `EnvironmentCatalog`, `EnvironmentContent` | 제품·숫자 버전·헤더/본문 출처; DB 추정·충돌·불충분 근거; soft404·로그인·WAF·429·접근거부·대용량은 별도 | 환경·경로 `14`·`15` |
| K08 | API 문서·JS·실제 관측 대조 | `ScopeInspectionAnalysis.compareInventory`, `ScopeInspectionRunner` | 문서만 있음/미문서 관측·메서드/필수값 불명 구분; 추측 전송 없음 | API `16` |
| K09 | 키·민감정보와 원문 위치 | `ScopeInspectionAnalysis`, `SecretCandidates`, `DiskTrafficStore.PacketRef` | 마스킹·지문·공개/예제/비밀키 후보·중요도/확실성/유효성; 분석 본문 줄/문자 위치; 키 자동 검증·재사용 없음 | 민감정보·원본 `17`·`22` |
| K10 | 계정별 노출 차이와 조건별 이전 비교 | `ScopeInspectionRunner`, `ScopeInspectionHistory`, `ScopeInspection.Options.katanaProfileId()` | 선택 Katana 수집 계정까지 같은 조건·옵션/버전일 때 비교; 비로그인/Alice 수집 기준 분리; 인증 revision 혼합·불완전 실행의 비교/해결 보류 | 차이·이력 `18`·`19` |
| K11 | 수집 상태·제외·실패·부분·미확인 표시 | `ScopeKatana.Snapshot`, `ScopeInspectionPanel`, `ScopeInspection.Snapshot` | 수집 큐 완료와 사이트 전체 확인 구분; 버전/실행/출력/시간/페이지/예산 문제의 미완료 표시; Katana 실패 때 일반 HTTP 결과와 부분 사유를 각각 유지 | 진행·Katana·커버리지 `13`·`24`·`20` |
| K12 | 중지/재개·프로젝트/범위 변경·종료 정리 | `ScopeKatana`, `ScopeInspectionRunner`, `ScopeInspectionPanel` | 프로세스·프록시·reader·임시 파일 정리; 재개 시 절대 시한/공통 예산 유지; 오래된 이벤트의 새 실행 혼입 없음 | 실행·Katana `13`·`24` |
| K13 | 자동 쓰기·로그인·키 검증·업데이트 제외 | `ScopeKatana.command`, `ScopeKatanaProxy`, `ScopeInspectionRunner` | 표준 조회 모드; 폼 자동 제출·키 검증/공급자 API·업데이트 없음; 쓰기·알려진 변경 경로·범위 밖 이동 차단 | 설치·판정 기준·Katana `23`·`24` |
| K14 | 기존 SPA helper의 선택적 병행 | `ScopeInspectionBrowser`, `BrowserBridge`, helper 0.4.0 | Katana headless 미사용; 별도 브라우저 로그인 세션; HTTP 프로필로 쿠키 복사 없음; URL 규칙으로 표현할 수 없는 범위는 브라우저 미지원 | 브라우저 `21` |
| K15 | 같은 원본의 기존 도구·증거 연결 | `DiskTrafficStore.PacketRef`, `ScopeInspectionPanel`, `WorkbenchExtension` | 불변 패킷·앞64 KiB 미리보기·Burp 기본 읽기 전용 전체 보기; 원본 없는 과거 요약 구분; 연결 준비는 전송 없음 | 원본·기존14장·증거 `22` |
| K16 | 버전별 산출물·가이드·검증 보존 | 버전별 `downloads/`, `docs/history/`, `tools/check_simple_guide.py` | 1.0.0/1.1.0/1.2.0 해시 보존; 새 JAR·소스·가이드 캡처 지문 일치; 실제 실행 범위 분리 | 설치·파일·검증 |

## 검사 연결

| 검사 소스·명령 | 요구사항 | 검사 범위 |
|---|---|---|
| `burp-workbench/src/test/java/kr/workbench/ScopeKatanaTest.java` | K02·K05·K06·K11·K12·K13 | 버전·명령·출력 파싱·한도·프로세스 수명주기 |
| `burp-workbench/src/test/java/kr/workbench/ScopeKatanaProxyTest.java` | K03·K04·K05·K12·K13 | loopback HTTP·HTTPS CONNECT/TLS·인증·메서드·Scope·중지 |
| `burp-workbench/src/test/java/kr/workbench/ScopeKatanaUiTest.java` | K01·K02·K11·K12·K15 | Katana 선택·설치 확인·설정·결과/원본 연결·종료 |
| `ScopeInspectionRunnerTest`·`ScopeInspectionAnalysisTest`·`ScopeInspectionHistoryTest`·`ScopeInspectionUiTest` | K01·K04·K06~K12·K15 | 공통 전송/예산·분석·원본·마스킹 이력·UI 연결 |
| `tools/verify_katana.py --katana /경로/katana` · `ScopeKatanaNativeHarness` | K02~K13·K15 | 실제 고정 Katana 프로세스와 격리 HTTP/HTTPS fixture·선택 계정·공통 예산·원본·중지·이력. 실제 Burp GUI와 구분 |
| `tools/render_simple.py --check`·`tools/render_scope_inspection.py --check` | K01·K11·K15 | 밝은/어두운 테마·작은/기본 창·실제 운영 컴포넌트 합성 렌더 |
| `dev.py verify`·`tools/check_simple_guide.py` | K16 | 배포 해시·소스 지문·캡처 JAR·PDF 구조·이전 가이드 보존 |

실제 외부 Katana 검사와 어댑터·단위 검사를 구분합니다. 표에 적힌 명령은 검사 연결이며 실행 결과는 검증 JSON에서 확인합니다. 관리 브라우저 정책으로 실행할 수 없는 helper 설치, 실제 Burp GUI, 운영 대상·Windows DPI/IME를 실행한 것으로 표시하지 않습니다.

## 판정·데이터 범위

Katana JSONL의 후보·폼·기술 정보는 발견 메타데이터입니다. 실제 HTTP 원본은 Workbench 전송에서 얻은 패킷 ID로 연결합니다. 본문 줄/문자 위치는 분석한 텍스트 본문 기준이며 압축/인코딩된 HTTP raw 바이트 위치와 다릅니다. 대형 덤프·아카이브 자동 제외와 본문 분석 한도는 실제 다운로드 강제 중단 한도가 아닙니다.

폼·쓰기·외부 리디렉션 차단과 GET/HEAD 정책은 서버의 모든 GET 기능에 부작용이 없음을 보장하지 않습니다. 기술 이름·DB 단서·키 형식·응답 차이를 취약점 확정으로 바꾸지 않으며, 미검사·부분 실행을 정상이나 해결로 합산하지 않습니다.

Katana 공급자 버전 확인은 기본 사용자 설정 위치의 10일 지난 resume 자료를 정리할 수 있습니다. 설치기의 HOME 재지정 없음과 공급자 파일 효과를 구분하고 [의존성 기록](KATANA_DEPENDENCY.json)에 출처를 보존합니다.

## 공식 의존성 근거

- [v1.8.0 표준/브라우저 엔진 선택](https://github.com/projectdiscovery/katana/blob/v1.8.0/internal/runner/runner.go#L95)
- [v1.8.0 CLI 옵션과 기본값](https://github.com/projectdiscovery/katana/blob/v1.8.0/cmd/katana/main.go#L120) · [폼·기술 JSON 응답 스키마](https://github.com/projectdiscovery/katana/blob/v1.8.0/pkg/navigation/response.go#L30)
- [공급자 키의 실제 검증을 선택하는 기능](https://github.com/projectdiscovery/katana/blob/v1.8.0/pkg/knowledgebase/extractors/secrets/secrets.go#L1) — 이 통합에서는 활성화하지 않습니다.
- [시작 시 오래된 resume 파일 정리](https://github.com/projectdiscovery/katana/blob/v1.8.0/cmd/katana/main.go#L305) · [버전 확인 흐름](https://github.com/projectdiscovery/katana/blob/v1.8.0/internal/runner/runner.go#L61)

실제로 사용하는 실행 옵션은 `ScopeKatana.command`와 해당 회귀 검사에서 확인합니다. 공식 옵션의 존재와 이 통합에서 실행한 범위를 구분합니다.
