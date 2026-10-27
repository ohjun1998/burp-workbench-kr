# 0.53.4 안정화 CI 검증

고정한 8개 사용자 편의성 개선의 소스 `937c42defbc4d76187f1d189b36b84f4272dd76a`를 [후보 CI](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40451792261)에서 검사했습니다. Linux·Windows 전체 검사와 모든 화면 모드가 통과한 뒤 배포 파일을 게시합니다. 아직 main 미병합입니다.

- [Linux 검사](linux-check-summary.json) · [Windows 검사](windows-check-summary.json)
- [전체 화면](full-layout-report.json) · [집중 화면](focused-layout-report.json): 두 검사는 겹치므로 합산하지 않습니다.
- [150% 트래픽](ui/light-stabilization-traffic-150.png) · [150% 증거 작성](ui/light-stabilization-evidence-150.png) · [150% 비교](ui/dark-stabilization-comparison-150.png)

화면 검사 JAR와 게시 JAR의 SHA-256이 일치합니다. 자동 Swing/API/격리 HTTP 검사이며 실제 Burp GUI·DPI/IME·외부 서비스 수동 검증을 뜻하지 않습니다. 이전 버전 파일과 당시 검증 기록은 보존합니다.
