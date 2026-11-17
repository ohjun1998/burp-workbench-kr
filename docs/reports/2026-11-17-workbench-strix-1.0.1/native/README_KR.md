# 실제 Linux 창 캡처 · Simple1.4.21 디자인 비교

동일한 production JAR·합성 Burp API 어댑터·합성 요청·프레임 크기·테마에서 실제 Linux Xorg/xfwm4 창을 실행하고 Java Robot으로 픽셀을 캡처했다. 이미지는 후편집하지 않았다. 실제 Burp 본체는 설치되어 있지 않으며 사용자 Burp 캡처·실제 Strix 진단·모델 추론 성공을 의미하지 않는다. 대상 HTTP·Strix 실행·모델 호출은 모두0회다.

## 완료한 비교

- Simple1.4.21 baseline과 WorkbenchStrix1.0.1을 라이트/다크 ×1600×1000/1280×800에서 실행했다. baseline2화면과 current5화면씩 총28장이다.
- 기존 메인·대시보드의 이름 있는 표시 컴포넌트464개의 x/y/width/height가 일치했다. 누락·추가·좌표 차이는0건이다. 통합 점검·대시보드 강조 행 높이는 모든 조건에서42px다.
- 이는 명시한 두 화면의 컴포넌트 배치 비교이며 모든 픽셀 동일, 모든 도구 레이아웃 또는 실제 Windows Burp 검증을 뜻하지 않는다. 제목·버전·타임스탬프·텍스트 래스터는 다를 수 있다.
- 별도 popup-flow의 current1600×1000 다크 실행에서는 실제 우클릭 메뉴에 wb.simple.strix.prepare 항목이 표시되고 활성화되는지 확인한 뒤 Robot으로 클릭해 production Strix 진단 창을 열었다. 팝업까지6장으로 전체 원본은34장이다. 주 비교의 Strix창 준비는 같은 production 연결 메서드 호출이며 추가 팝업 검증이 실제 항목 진입을 확인한다.

## 파일

- native-comparison.json:8개 조건/화면 비교·기준/후보 JAR SHA
- baseline-simple-1.4.21/:기준 제품의28장 중8장
- strix/:새 제품의28장 중20장
- popup-flow/:실제 우클릭 진입 추가 검증6장
- png-verification.json:모든34개 PNG의 CRC·SHA·치수 검증
- support/:재현용 harness와runner. 제품 source나배포는 수정하지 않는다.

원본 첫 시도는 Robot.waitForIdle에서 지속적인 AWT repaint를 기다려 지연됐다. 완료된 비교는 Toolkit.sync와 제한된 대기·EDT 배리어를 사용한다. 취소된 첫 시도 이미지는 이 통과 수에 포함하지 않았다.
