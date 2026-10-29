# Workbench Simple 1.4.0 화면별 사용 가이드

공유 대화에서 요청한 **화면별 번호·화살표·박스와 이미지 아래 조작 설명**을 적용한 별도 문서 개정판입니다. 기존 Simple 가이드·JAR·소스·설정 파일은 변경하지 않습니다.

- [PDF 가이드북](Workbench_Simple_1.4.0_Visual_Guide_20261029_KR.pdf): 화면별 조작·정상 결과·문제 해결, 목차와 북마크.
- [오프라인 HTML](Workbench_Simple_1.4.0_Visual_Guide_20261029_KR.html): 검색, 화면 확대, 중요 영역 확대, 이전·다음, 다크 모드, 단계 확인 표시.
- [다운로드 묶음](Workbench_Simple_1.4.0_Visual_Guide_20261029_KR_Bundle.zip): PDF·HTML·이 안내·출처 지문. 압축을 풀고 HTML을 브라우저에서 엽니다.
- [체크섬](SHA256SUMS) · [문서 검사](validation.json) · [원본 출처·번호 좌표](source-manifest.json).

HTML에는 이미지·한글 폰트·스타일·스크립트가 포함되어 있습니다. PDF를 같은 폴더에 두면 상단 PDF 링크도 사용할 수 있습니다. 설치 파일·제품 검증·공유 대화의 외부 링크를 열 때는 인터넷이 필요합니다. 단계를 확인하는 체크박스는 해당 브라우저에만 저장되며 실제 사이트 점검 완료 판정이 아닙니다.

관리형 브라우저가 HTML 파일 직접 열기를 차단하는 환경에서는 정책을 변경하지 않고 허용된 문서 서버를 사용합니다. 가이드 폴더 안에서 아래 명령으로 **문서 폴더만** loopback에 제공할 수 있습니다.

```text
python -m http.server 8765 --bind 127.0.0.1
```

브라우저에서 `http://127.0.0.1:8765/Workbench_Simple_1.4.0_Visual_Guide_20261029_KR.html`을 엽니다. 종료는 터미널의 Ctrl+C입니다. 이번 환경의 관리형 Chrome에서는 파일 직접 열기가 차단되어 같은 HTML 바이트를 loopback에서 검증했습니다. 외부 리소스 요청 없음과 파일 직접 열기 성공을 구분합니다.

## 읽는 순서

1. 설치·준비에서 Simple JAR, Scope, HTTP 계정과 외부 Katana를 준비합니다.
2. 01–08단계에서 원터치 점검과 선택적인 브라우저·SPA 연결을 익힙니다.
3. 09–21단계에서 환경·API·민감정보·미확인·권한 차이·이력·원본 보고서를 읽습니다.
4. 22–31단계에서 기존 8개 도구의 개별 작업을 연결합니다.
5. 32–33단계에서 가림을 확인하고 제출용 증거를 저장합니다.

## 캡처와 제품 기준

제품 기준은 [PR #102](https://github.com/ohjun1998/burp-workbench-kr/pull/102)의 Simple 1.4.0, Mapper 0.3.7, helper 0.5.0, 외부 Katana 1.8.0입니다. 문서 개정은 **2026-10-29**이며 제품 버전 변경이나 main 병합 상태를 뜻하지 않습니다.

그림은 운영 Swing 컴포넌트에 합성 예제를 넣은 기존 캡처와 실제 Chromium에서 Chrome API·연결 fixture로 렌더링한 운영 helper popup입니다. 실제 Burp GUI·native MV3/DNR 설치 성공으로 표현하지 않습니다. 원본 PNG의 픽셀을 변경하지 않고 PDF·HTML 문서에 벡터 주석을 겹쳤습니다.

기존 제품 검사 5,476개·Katana 102개·Chromium 어댑터 14개·7개 앱 전후 28회·선별 79항목 비교는 보존된 제품 검증을 인용합니다. 이번 작업에서 사이트를 새로 진단한 결과가 아닙니다. 이번 문서 검사 범위는 `validation.json`을 확인합니다.

## 재생성

저장소 루트에서 ReportLab·Pillow가 설치된 Python으로 실행합니다.

```text
python docs/guides/simple-1.4.0-visual-20261029/build_guide.py
```

입력은 같은 폴더의 `content.json`, `assets/`의 원본 캡처, 저장소에 보존된 NanumGothic 폰트입니다. 제품 코드·버전 배포 폴더에 쓰지 않습니다. HTML은 JavaScript 없이도 전체 본문·번호·조작 설명을 읽을 수 있습니다.

문서 검사는 `check_guide.py`(PyMuPDF·Pillow·ReportLab)와 `check_browser.mjs`(Playwright·Chromium)를 사용합니다. 현재 검증된 PDF·HTML을 다시 쓰지 않고 검증 기록까지 묶으려면 `build_guide.py --bundle-only`를 사용합니다. HTML에 포함한 NanumGothic 폰트의 라이선스는 `OFL_NanumGothic.txt`에 보관합니다.
