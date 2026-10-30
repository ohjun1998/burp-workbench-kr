# Simple 1.4.12 · 도구별 적용 화면 PDF

[PDF 다운로드](workbench-simple-1.4.12-tool-screens.pdf)

대화에서 안내한 다크 테마 적용 화면 16개를 한 페이지에 하나씩 모았습니다. A3 가로형, 도구별 한글 제목과 책갈피 16개를 제공합니다. 1.4.12의 실제 Swing 구현 컴포넌트에 예시 데이터를 넣어 캡처한 화면입니다.

원본 PNG는 [기존 화면 갤러리](../2026-10-31-contextual-button-layout/README.md)의 `images/actual/dark/`에 있습니다. 스크린샷을 다시 생성하거나 수정하지 않았으며, 원본 해상도와 종횡비를 유지해 손실 없이 삽입했습니다. PDF에서 추출한 16개 이미지의 모든 RGB 픽셀이 원본과 같은지 확인했습니다.

PDF 검증: 16페이지, 올바른 순서의 한글 제목·책갈피 16개, 이미지 영역 내 배치·원본 픽셀 일치. [기계 판독 기록](verification.json).

재생성: 저장소 루트에서 `python docs/reports/2026-10-31-tool-screens-pdf/build_pdf.py`. PyMuPDF, Pillow, fontTools가 필요하며 저장소에 있는 NanumGothic 폰트를 읽어 PDF에 필요한 글자만 내장합니다. 제품 소스와 기존 Simple 패키지는 변경하지 않습니다.
