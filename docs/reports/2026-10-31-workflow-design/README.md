# Workbench · 16개 화면 작업 흐름 설계안

**[전체 설계PDF](workbench-ux-flow-design.pdf)** · **[32장 이미지·클릭 시안ZIP](workbench-ux-flow-design.zip)** · [한곳에서 보는 갤러리](gallery.html)

사용자의 선택에 따라 **설계 시안부터** 작성했습니다. 직전 제공한1.4.15 화면과 비교해 작업 흐름·기능 우선순위·그룹·메뉴 통합·상세 정보 노출을 재설계한 HTML 검토 시안입니다. 확장 소스와JAR에는 적용하지 않았습니다. 새 릴리스가 아닙니다. [소스 보존 확인](PRODUCTION_UNCHANGED.json).

## 설계 기준

- 각 화면의 현재 단계에서 필요한 주 동작 하나를 파란색으로 강조합니다.
- 선택 항목·문서·프로젝트 작업을 구분하고 같은 범위의 보조 기능을 이름 있는 메뉴로 묶습니다.
- 기본 화면에는 필수 조건을 보여주고, 고급 설정은 접되 현재 적용 상태를 요약합니다.
- 반복 제목·항상 열린 검색줄·중복 분포를 줄이고 원문·결과·작성 영역을 확보합니다.
- 같은 하단 버튼줄을 모든 도구에 강제하지 않고, 도구별로 자연스러운 진행 방향을 사용합니다.

우선순위는 작업 순서와 선택 대상의 범위에 따른 설계 판단입니다. 실제 사용 빈도나 완료 시간 개선을 측정한 결과는 아닙니다. 모든 데이터는 합성 예시입니다. 생산 확장처럼 실제 전송·외부조회·저장을 수행하지 않습니다.

## 주요 변화

| 화면 | 중심이 되는 흐름 | 통합·축소한 부분 |
|---|---|---|
| 트래픽 | 요청 찾기→원문 확인→Repeater 준비 | 선택 문맥·도구 연결을 한 줄로, 다른 요청 작업은 메뉴로 |
| Mapper | 기능 선택→요청 확인→분류 수정 | 프로젝트/기능 편집/선택 요청 작업을 구분 |
| JS | 경로 목록→옆의 호출 소스→요청 준비 | 반복 URL 사실표와 보조 유형 탭 축소 |
| 계정 비교 | 역할 연결→비교 준비→응답 차이 검토 | 큰 신원 카드를 역할 칩으로, 응답 리더 확대 |
| 입력값 검사 | 요청 표시→실행 조건 확인→시작 | 중첩 준비 탭 제거, 후보·고급 조건 접기 |
| 검색·CVE | 검색/관측 제품→후보 선택→근거 검토 | 제품 반복과 동등한 상세 박스를 정리 |
| 증거 | 항목 선택→영향·재현 작성→가림 확인→저장 | 항목 편집과 문서 작업 분리, 원문을 작성 옆에 |
| 대시보드 | 분류 요약→결과 선택→원본 근거 | 중복 분포 접기, 결과 목록을 중심에 |

## 16개 화면

| 화면 | 주 작업 | 라이트 | 다크 |
|---|---|---|---|
| 요청·응답 목록 | Repeater 준비 | [보기](images/light/traffic.png) | [보기](images/dark/traffic.png) |
| 기능 자동 매핑 | 분류 수정 | [보기](images/light/mapper.png) | [보기](images/dark/mapper.png) |
| JS에서 API 찾기 | Repeater 준비 | [보기](images/light/js.png) | [보기](images/dark/js.png) |
| 서버·경로 탐색 | 선택 경로 GET 확인 | [보기](images/light/environment.png) | [보기](images/dark/environment.png) |
| 외부 노출 조사 · 검색 노출 | Google에서 열기 | [보기](images/light/exposure.search.png) | [보기](images/dark/exposure.search.png) |
| 외부 노출 조사 · CVE 후보 | CVE 후보 조회 | [보기](images/light/exposure.cve.png) | [보기](images/dark/exposure.cve.png) |
| 계정 권한 비교 | 비교 준비 | [보기](images/light/auth.png) | [보기](images/dark/auth.png) |
| 응답 보안 분석 | 증거로 정리 | [보기](images/light/response.recon.png) | [보기](images/dark/response.recon.png) |
| 입력값 보안 검사 · 요청 준비 | 검사 시작 | [보기](images/light/payload.png) | [보기](images/dark/payload.png) |
| 데이터 변환·비교 | 변환 | [보기](images/light/data.png) | [보기](images/dark/data.png) |
| 점검 진행 현황 | 완료 표시 | [보기](images/light/activity.png) | [보기](images/dark/activity.png) |
| 증거 정리 | 증거 ZIP 저장 | [보기](images/light/bounty.png) | [보기](images/dark/bounty.png) |
| 점검 대시보드 | 원본 보기 | [보기](images/light/dashboard.png) | [보기](images/dark/dashboard.png) |
| 입력값 보안 검사 · 실행·결과 | 결과를 증거로 | [보기](images/light/payload.results.png) | [보기](images/dark/payload.results.png) |
| 요청 재현 | 순차 실행 | [보기](images/light/bounty.replay.png) | [보기](images/dark/bounty.replay.png) |
| 계정 권한 비교 · 인증 후보 연결 | 닫기 | [보기](images/light/auth.accounts.png) | [보기](images/dark/auth.accounts.png) |

## 검토 자료

PDF는라이트·다크32개 화면과 핵심5개 전후 비교, 총37페이지입니다. 화면별 주 작업과 설계 이유3개를 각 페이지에 적었습니다. 캡처42개는 원본 픽셀과 일치하며PDF의 글자·이미지 경계를 확인했습니다. [PDF 검사](pdf-verification.json).

[렌더 확인](render-report.json):16화면×2테마×2창크기(1600×1000/1280×800),64개 화면에서런타임오류·기본컨트롤잘림·가로넘침0건, 주 동작1개씩 확인.

[클릭 흐름](interaction-verification.json):메뉴·접힘·계정 연결·단계 이동·CVE근거·로컬변환 예시 등20개 확인. 실제 확장 엔진 검증 수치가 아닙니다. 관리형Chromium의file:// 열기는 정책으로 차단되어 검증하지 않았고, 로컬HTTP로 확인했습니다. ZIP의 [미리보기 안내](prototype/README_KR.md)를 따라 실행할 수 있습니다. 외부CDN은 사용하지 않습니다.

실제 Burp/Swing 화면에서 같은 밀도·크기·동작을 보장하는 구현 결과가 아닙니다. 사용자 검토 후 구현할 설계 자료이며 기존 기능을 삭제했다는 의미도 아닙니다. 원본Next와 기존Simple 패키지·소스를 유지합니다.

## 게시 결과

[다운로드 기록](PUBLISH_KR.md): 공개39개 파일의HTTP200·크기·SHA-256 일치를 확인했습니다. 설계 자료만 추가했으며 확장 소스와JAR은 변경하지 않았습니다.
