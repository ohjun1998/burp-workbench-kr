# Workbench Next 0.53.3 최종 검증·배포 근거

검증 소스: `511845348ffdea146ee0a01f377c6e427c72390c` · [GitHub Actions 40414755887](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40414755887). release·windows·layout 작업이 모두 성공했습니다. 이후 배포·문서 게시에서는 이 생산 소스·빌드 입력을 바꾸지 않습니다.

| 범위 | 실제 결과 |
|---|---|
| Linux·Windows 각 플랫폼 | Java 회귀113그룹·3,972개 + payload HTTP136개 + 전체 기능 HTTP102개 + 모의 진단 HTTP266개 =4,476개 통과 |
| 새 편의성 회귀 | 검색46·증거58·입력52·탐색43·응답 요약49/21·작업37·시작 준비9·통합19개. 위 회귀에 포함된9그룹·334개 |
| Mapper | Java134개·Node15개 통과. 기존0.3.5 배포·소스 보존 |
| 전체 화면 흐름 | production Swing450화면·5,934컨트롤·162상태. 밝은/어두운 테마 및1024×720·1280×800·1440×920 |
| 새 기능 집중 화면 | 288화면·2,202컨트롤·162상태. 도우미760×480·560×300도 포함 |
| 배치 기준 | 각 모드에서 버튼 잘림·작은 컨트롤·핵심 영역 축소·선택 탭/제목 잘림0. 넓은285px메뉴·1024창의 남은 점검 설명은63px로 복구 |
| 배포 | 전체·집중 UI와 게시 JAR의 SHA256 일치. 소스 ZIP367파일 CRC·파일별 해시·현재 소스 지문·다운로드 해시와 패키징 단위검사7개 통과 |

전체 화면 모드는 집중 화면을 포함합니다. 화면·컨트롤·상태 수와 플랫폼 반복, 별도 집중 검사를 더해 고유 검사 수로 표시하지 않습니다. 로컬에서는10개 화면 모드(전체·집중·트래픽·안내·SCOPE·패킷·JS·세션·권한·서버)가 모두 통과했습니다. 실제 Burp GUI가 아닌 합성 데이터·API어댑터의 검증입니다.

- JAR SHA256: `b85c7ea6f4e492167747a4e1f78b6607a975e950aa9d05a670a8b694c2c6ec6c`
- 소스 ZIP SHA256: `bd48061ceee8b22868646d5bdab75b34216112ca13d1dcb43911a63f9c39f154`
- [Linux](linux-check-summary.json) · [Windows](windows-check-summary.json) · [전체 화면](full-layout-report.json) · [집중 화면](ui/layout-report.json) · [검증 메타데이터](../../VERIFICATION_0.53.3.json)

## 화면 근거

[넓은 메뉴의 남은 점검](ui/light-remaining-wide-menu.png) · [어두운 테마](ui/dark-remaining-wide-menu.png) · [증거 편집](ui/light-evidence-editor.png) · [응답 비교](ui/dark-response-summary.png) · [기능 찾기](ui/light-quick-navigation-minimum.png) · [작업 상태](ui/light-background-jobs-minimum.png) · [시작 준비](ui/light-getting-started-minimum.png) · [입력 오류](ui/light-inline-validation.png)

## 이전 후보와 미실행 범위

0.53.2의 초기 후보 검사 통과와 뒤이은 전체 화면 회귀 실패는 [당시 기록](../../VERIFICATION_0.53.2.json)에 보존합니다. 해당 배포 파일은 덮어쓰지 않으며0.53.3검증 근거로 대신 사용하지 않습니다.

실제 Burp GUI 조작, Windows DPI/IME, 브라우저 수동 조작, 외부 대상 서비스, Docker/Oracle/Nginx 기동과 장시간 native Burp 부하는 미실행입니다. 자동 Swing/API/격리 HTTP 검증을 이 범위의 완료로 표시하지 않습니다.
