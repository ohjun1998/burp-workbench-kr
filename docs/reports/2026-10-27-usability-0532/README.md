# Workbench Next 0.53.2 최종 검증·배포 근거

검증 소스: `ba04e3ff96114bddbc14d83015c2cb920477ebbd` · [GitHub Actions 40409443813](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40409443813). release·windows·layout 작업이 모두 성공했습니다. 이후 문서·다운로드 게시에서는 이 생산 소스와 빌드 입력을 바꾸지 않습니다.

| 범위 | 실제 결과 |
|---|---|
| Linux·Windows 각 플랫폼 | Java 회귀 113그룹·3,970개 + payload HTTP136개 + 전체 기능 HTTP102개 + 모의 진단 HTTP266개 = 4,474개 통과 |
| 새 사용자 편의성 회귀 | 검색46·증거58·입력52·탐색41·응답 요약49/21·작업37·시작 준비9·실제 연결19개. 위 회귀 합계에 포함 |
| Mapper | Java134개·Node15개 통과. 기존0.3.5 배포와 소스 보존 |
| 화면 | 실제 production Swing 컴포넌트, 합성 데이터와 API 어댑터. 288화면·2,202컨트롤·162상태 검사 |
| 화면 배치 | 밝은/어두운 테마, 주 화면1024×720·1280×800·1440×920, 도우미760×480·560×300. 컨트롤 잘림·작은 컨트롤·핵심 영역 축소·선택 탭/제목 잘림0 |
| 배포 | 검사한 JAR와 게시 JAR 해시 일치. 소스 ZIP367파일 CRC·파일별 해시·현재 소스 지문, 다운로드 SHA256SUMS, 패키징 단위검사7개 통과 |

플랫폼 반복, 별도 집중 검사와 겹치는 하위 그룹을 고유 검사 수에 더하지 않습니다. 로컬 Java21의 Java17-target 전체 check도 같은4,474개를 통과했으며 원격 Java17 배포와 구분합니다.

- JAR SHA-256: `17f450d27dee4b5b996945729218132babc8e7f9d7c6f2b6c7d6cebd4628b290`
- 소스 ZIP SHA-256: `a35f23b4925a388514f846194bee49c29d59fde6931589a5f2ee8fc7e1b2b884`
- [Linux 요약](linux-check-summary.json) · [Windows 요약](windows-check-summary.json) · [화면 측정·JAR 해시](ui/layout-report.json) · [전체 검증 메타데이터](../../VERIFICATION_0.53.2.json)

## 화면 근거

[증거 편집](ui/light-evidence-editor.png) · [응답 항목 비교](ui/dark-response-summary.png) · [기능 찾기 최소 창](ui/light-quick-navigation-minimum.png) · [작업 상태 최소 창](ui/light-background-jobs-minimum.png) · [시작 준비 최소 창](ui/light-getting-started-minimum.png) · [입력 오류](ui/light-inline-validation.png)

## 미실행 범위

실제 Burp GUI 조작, Windows DPI/IME, 브라우저 수동 조작, 외부 대상 서비스, Docker/Oracle/Nginx 기동과 장시간 native Burp 부하는 실행하지 않았습니다. Swing/API/격리 HTTP의 성공을 이 범위의 완료로 표시하지 않습니다. 이전 배포·검증 기록은 그대로 보존합니다.
