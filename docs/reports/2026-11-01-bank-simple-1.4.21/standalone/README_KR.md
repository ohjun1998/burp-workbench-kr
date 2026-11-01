# 확장 도구별 실제 결과 · 단일 HTML

[Workbench_Simple_1.4.21_Bank_Results_KR.html](Workbench_Simple_1.4.21_Bank_Results_KR.html)을 저장하고 브라우저로 엽니다. ZIP 해제나 은행·서버 기동 없이 이 파일 하나로 결과를 볼 수 있습니다.

- 주요8개를 포함한13개 도구의 실제 결과·수치·후보 해석
- 라이트·다크 원본 화면과 은행·음수 이체·판정 근거 등 PNG35장
- 도구 검색·필터·테마 화면 전환
- 근거 JSON·문서의 내장 보기, CSV·JSON·은행 사이트 ZIP의 내장 다운로드

기존 검증 보고서의 공개 자료64개를 원래 바이트 그대로 넣었습니다. 자동 후보·독립 재현·미확인·미실행과 제품 한계2개를 유지했습니다. 통화는 가짜 실습 달러($)입니다. 새 은행 요청이나 제품 실행은 수행하지 않았습니다.

[입력·파일 SHA 명세](manifest.json) · [브라우저 확인](browser-verification.json)

모든 내장 자료64개의 SHA,13개 도구·주요8개 필터·검색·35개 원본 이미지의 로드·라이트/다크 전환·JSON/문서 보기·CSV 다운로드를 확인했습니다. 관리형 Chromium의 file URL 제한 때문에, 같은 HTML만 제공하고 자산 폴더가 없는 임시 loopback 서버로 검사했습니다. 문서1회 외 추가 리소스·은행 요청0, page error0입니다.

생성기는 저장소의 `audit/bank-simple-1.4.21/make_standalone_html.py`입니다. 기존 `RESULTS.html`·결과 ZIP·Simple 배포물은 변경하지 않았습니다.
