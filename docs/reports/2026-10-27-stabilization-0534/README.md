# 0.53.4 안정화 CI 검증

고정한 8개 사용자 편의성 개선의 소스 `937c42defbc4d76187f1d189b36b84f4272dd76a`를 [후보 CI](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40451792261)에서 검사했습니다. Linux·Windows 전체 검사와 모든 화면 모드가 통과한 배포 파일을 게시했습니다. [PR #94](https://github.com/ohjun1998/burp-workbench-kr/pull/94)로 main에 병합했습니다.

- [Linux 검사](linux-check-summary.json) · [Windows 검사](windows-check-summary.json)
- [전체 화면](full-layout-report.json) · [집중 화면](focused-layout-report.json): 두 검사는 겹치므로 합산하지 않습니다.
- [150% 트래픽](ui/light-stabilization-traffic-150.png) · [150% 증거 작성](ui/light-stabilization-evidence-150.png) · [150% 비교](ui/dark-stabilization-comparison-150.png)

화면 검사 JAR와 게시 JAR의 SHA-256이 일치합니다. 자동 Swing/API/격리 HTTP 검사이며 실제 Burp GUI·DPI/IME·외부 서비스 수동 검증을 뜻하지 않습니다. 이전 버전 파일과 당시 검증 기록은 보존합니다.

## 게시 파일·전체 화면 모드 확인

Linux·Windows 각각 123그룹·4,270개 회귀와 HTTP136/102/266개로 총4,774개를 통과했습니다. 게시 JAR SHA-256은 `795e6566bcc1f5f7d059383b82d60e3efeb00d873310681c896b45eeddacef9d`, 소스 ZIP은 `276e6be72f185f67a9f1ace2135e53ed80870803c741249b0c0541f5897004ec`이며 소스382개 항목의 CRC·현재 소스 지문을 확인했습니다. 이전 버전 파일은 변경하지 않았습니다.

- [traffic 화면 검사](layout-traffic.json)
- [guide 화면 검사](layout-guide.json)
- [scope 화면 검사](layout-scope.json)
- [packet 화면 검사](layout-packet.json)
- [js 화면 검사](layout-js.json)
- [session 화면 검사](layout-session.json)
- [auth 화면 검사](layout-auth.json)
- [environment 화면 검사](layout-environment.json)

전체10개 모드에서 게시 JAR와 화면 검사 JAR의 해시가 같습니다. 전체·집중 화면의 중첩 상태와 플랫폼 반복은 고유 검사 수에 합산하지 않습니다.

## 최종 PR 검사·main 병합

최종 head `64cc557bab399ae27d239ea928c266e810daf85d`의 [Build40453183311](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40453183311)과 [Windows·디스크/수집 내구성40453183309](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/40453183309)이 모두 통과한 뒤 PR #94를 main에 병합했습니다. 실제 병합은 **2026-10-27 14:19:48(한국 시간)**, 커밋은 `56b56e961d0d73e777228e60fdfc1a1e544496fd`입니다. 병합 트리 `20d28400ef783fa699a5682a3113789e20e0cf6e`는 검증한 PR head와 같으며 동일 트리의 중복 main 검사만 생략했습니다.

- [실제 디스크 부족·복구](durability/disk-full.json)
- [Linux180초 수집](durability/linux-soak.json) · [Windows180초 수집](durability/windows-capture.json)
- [Windows 저장소 반복](durability/storage-repeat.json) · [PowerShell 제어 검사](durability/windows-scripts.json)
- [새 출력 릴리스·기존 배포 보존](durability/release.json)

후속 문서 정리는 생산 소스·빌드 입력·불변 배포 파일을 바꾸지 않습니다. 실제 Burp GUI·DPI/IME·외부 서비스 수동 검증 미실행 범위는 유지합니다.

내구성 JSON은 저장소에서 UTF-8/LF로 정규화했으며 원본 CI 아카이브와 파싱한 값이 같습니다. 원본·정규화 파일의 해시는 검증 JSON에 함께 기록합니다.
