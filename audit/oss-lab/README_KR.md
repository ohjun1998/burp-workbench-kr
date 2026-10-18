# 공개 실습 앱을 이용한 Workbench 실제 검증

2026-10-18 KST에 Workbench 0.44.0 배포 JAR를 실제 Burp Community 2026.3.3에 로드하고, 개인용 로컬 Juice Shop 20.3.0을 Chromium 146으로 사용했다. 제품 소스와 기존 배포 파일은 변경하지 않았다.

결과는 `results/2026-10-18/summary.json`, `checks.csv`, `auth-comparisons.json` 순서로 읽는다. **33개 기능 조건 중 28개 충족, 5개 미충족**, 브라우저 흐름 5개 통과. 자체 로컬 실습 대상의 대표 흐름만 확인한 결과다.

- 기본 차단 규칙과 직접 지정한 권한표: 기준 6칸을 제외한 18칸에서 TP 6, FP 1, TN 9, REVIEW 2, FN 0.
- 대상 전용 응답 규칙: 6칸 재검사에서 TN 4, REVIEW 2. 빈 사용자 객체의 오탐은 해소되지만 HTTP 400 보류는 남는다. 둘 다 해소하는 것을 기대한 AU-08은 미충족으로 기록했다.
- F02: 실제 main.js의 로그인·장바구니·검색 경로 3개는 보관된 결과 버전을 합쳐도 미발견. 한 버전은 실패, 다른 버전은 25개 미확정 문자열 후보다. 파일 크기만으로 실패 원인을 단정하지 않는다.
- F03: SQLITE_ERROR가 응답 보안 분석과 기본 SQL 검사에서 누락. 대상 SQL 문맥에 맞춘 참·거짓 조건 대조는 탐지한다.
- F04: POST 302 뒤 실제 저장·조회가 이뤄져도 저장 흐름이 연결되지 않음. POST 201 / GET 200 대조 흐름은 연결된다.
- 수집 167건, URL 후보 50건, 수집 오류·누락 0건. `python dev.py check`의 기존 2,890개 검사도 통과했으며, 위 Juice Shop 검사와는 별도다.

## 재현

Node 24, JDK 17 컴파일러, Java 21 런타임, 설치된 Burp Community JAR, Chromium 및 Playwright, 공식 Juice Shop **20.3.0 Node24 Linux x64 배포판**이 필요하다. 실행 전 Burp Community 라이선스에 동의한 사용 환경을 전제로 한다. 새 임시 프로필을 만들고 전용 포트 24540·24541을 사용한다. 기존 Burp·브라우저 설정은 변경하지 않는다.

```sh
python audit/oss-lab/run_lab.py \
  --target /absolute/path/to/juice-shop-20.3.0 \
  --preload audit/oss-lab/offline-loopback.cjs \
  --out /absolute/path/to/fresh-results \
  --burp /absolute/path/to/burp-community.jar \
  --java21 /absolute/path/to/java21/bin/java \
  --chromium /absolute/path/to/chromium
```

`CODEX_PRIMARY_RUNTIME_NODE_MODULES`는 Playwright가 설치된 node_modules 경로를 가리킨다. 런타임 배포 환경 밖에서는 해당 변수를 명시한다. 새 결과 디렉터리를 사용한다. 이 하네스는 의도적으로 취약한 **자체 로컬 인스턴스**만 허용한다. `offline-loopback.cjs`는 서버 리스너를 loopback으로 제한하고 외부 fetch/socket을 거부한다. 애플리케이션 코드를 패치하지 않는다. 외부 AI·Web3는 검사 대상이 아니다.

비밀번호·토큰이 든 `private-state.json`, 임시 프로필, 서버 로그는 공개하거나 저장소에 추가하지 않는다. `export_results.py`는 허용한 파일만 복사하고 테스트 계정 토큰이 없는지 확인한다. Burp 화면은 headless로 실행했으므로 수동 GUI 클릭·레이아웃 검증을 주장하지 않는다. Chromium은 실제 private Burp proxy를 통과하며, Burp UI의 컨트롤러는 테스트 코드에서 호출한다.

## 해석 한계

crAPI는 Docker/Podman과 소켓 부재, 사용자 네임스페이스/PRoot 제한으로 실행하지 못했다. 공개 운영 데모를 공격하지 않았고 외부 OAST·BApps·아카이브·성능 시험은 수행하지 않았다. 권한 기대는 테스트 시 직접 지정했다. 특히 관리자도 타인 장바구니에는 접근하지 못한다는 실습 정책을 사용했다. REVIEW는 TN/FP/FN에 넣지 않는다.

기능 체크의 PASS는 동작 조건 충족이며 탐지 정확도가 아니다. 예를 들어 AU-07은 비교 실행 자체는 성공했지만 비로그인 빈 200 응답에 FP가 있었다. 배포 JAR와 대상 배포판의 해시는 `runtime-metadata.json`에 있다.
