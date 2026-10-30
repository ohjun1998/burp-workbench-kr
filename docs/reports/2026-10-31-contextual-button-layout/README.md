# Simple 1.4.12 · 버튼 배치 구현 화면

승인한 버튼 배치를 소스에 적용한 **운영 Swing 컴포넌트의 합성 예시 캡처**입니다. 기본 도구 화면13개와 보조 화면3개를 밝음·어두움으로 제공하며, 현재 화면은32개 PNG입니다. 이전 비교26개와 승인 시안4개를 함께 포함해 기본 비교 갤러리는62개입니다. 실제 Linux 창·가이드29개와 좁은 CVE 화면2개를 더해 전체 PNG는93개입니다. 내장 Mapper는0.3.13입니다.

**Windows Burp 본체를 직접 조작한 캡처가 아닙니다.** 예시 계정·패킷·CVE·집계 숫자는 실제 대상의 점검 결과를 의미하지 않습니다. 생성한 승인 시안과 실제 구현 화면을 구분합니다.

[테마 선택·이전/시안 비교 갤러리](index.html) · [승인 시안](../2026-10-31-button-layout-proposal/README.md) · [이전1.4.11 화면](../2026-10-31-numbered-guides/README.md)

| 화면 | 어두운 테마 | 밝은 테마 | 이전1.4.11 | 승인 시안 |
|---|---|---|---|---|
| 요청·응답 목록 | [보기](images/actual/dark/traffic.png) | [보기](images/actual/light/traffic.png) | [어두움](images/before/dark/traffic.png) · [밝음](images/before/light/traffic.png) | [보기](images/proposals/01-traffic-layout.png) |
| 기능 자동 매핑 | [보기](images/actual/dark/mapper.png) | [보기](images/actual/light/mapper.png) | [어두움](images/before/dark/mapper.png) · [밝음](images/before/light/mapper.png) | [보기](images/proposals/03-analysis-tools.png) |
| JS에서 API 찾기 | [보기](images/actual/dark/js.png) | [보기](images/actual/light/js.png) | [어두움](images/before/dark/js.png) · [밝음](images/before/light/js.png) | [보기](images/proposals/03-analysis-tools.png) |
| 서버·경로 탐색 | [보기](images/actual/dark/environment.png) | [보기](images/actual/light/environment.png) | [어두움](images/before/dark/environment.png) · [밝음](images/before/light/environment.png) | [보기](images/proposals/02-execution-tools.png) |
| 외부 노출 조사 · 검색 노출 | [보기](images/actual/dark/exposure-search.png) | [보기](images/actual/light/exposure-search.png) | [어두움](images/before/dark/exposure-search.png) · [밝음](images/before/light/exposure-search.png) | [보기](images/proposals/04-management-tools.png) |
| 외부 노출 조사 · CVE 후보 | [보기](images/actual/dark/exposure-cve.png) | [보기](images/actual/light/exposure-cve.png) | [어두움](images/before/dark/exposure-cve.png) · [밝음](images/before/light/exposure-cve.png) | [보기](images/proposals/02-execution-tools.png) |
| 계정 권한 비교 | [보기](images/actual/dark/auth.png) | [보기](images/actual/light/auth.png) | [어두움](images/before/dark/auth.png) · [밝음](images/before/light/auth.png) | [보기](images/proposals/02-execution-tools.png) |
| 응답 보안 분석 | [보기](images/actual/dark/response-recon.png) | [보기](images/actual/light/response-recon.png) | [어두움](images/before/dark/response-recon.png) · [밝음](images/before/light/response-recon.png) | [보기](images/proposals/03-analysis-tools.png) |
| 입력값 보안 검사 · 요청 준비 | [보기](images/actual/dark/payload.png) | [보기](images/actual/light/payload.png) | [어두움](images/before/dark/payload.png) · [밝음](images/before/light/payload.png) | [보기](images/proposals/02-execution-tools.png) |
| 데이터 변환·비교 | [보기](images/actual/dark/data.png) | [보기](images/actual/light/data.png) | [어두움](images/before/dark/data.png) · [밝음](images/before/light/data.png) | [보기](images/proposals/03-analysis-tools.png) |
| 점검 진행 현황 | [보기](images/actual/dark/activity.png) | [보기](images/actual/light/activity.png) | [어두움](images/before/dark/activity.png) · [밝음](images/before/light/activity.png) | [보기](images/proposals/04-management-tools.png) |
| 재현·증거 정리 | [보기](images/actual/dark/bounty.png) | [보기](images/actual/light/bounty.png) | [어두움](images/before/dark/bounty.png) · [밝음](images/before/light/bounty.png) | [보기](images/proposals/04-management-tools.png) |
| 점검 대시보드 | [보기](images/actual/dark/dashboard.png) | [보기](images/actual/light/dashboard.png) | [어두움](images/before/dark/dashboard.png) · [밝음](images/before/light/dashboard.png) | [보기](images/proposals/04-management-tools.png) |
| 입력값 보안 검사 · 실행·결과 | [보기](images/actual/dark/payload-results.png) | [보기](images/actual/light/payload-results.png) | — | [보기](images/proposals/02-execution-tools.png) |
| 재현·증거 정리 · 요청 재현 | [보기](images/actual/dark/bounty-replay.png) | [보기](images/actual/light/bounty-replay.png) | — | [보기](images/proposals/04-management-tools.png) |
| 계정 권한 비교 · 인증 후보 연결 | [보기](images/actual/dark/auth-accounts.png) | [보기](images/actual/light/auth-accounts.png) | — | [보기](images/proposals/02-execution-tools.png) |

상단은 대상·검색·설정 중심으로 정리하고 실행·중지는 함께 유지합니다. 선택 항목의 Repeater·증거·복사 버튼은 해당 목록·상세 제목줄로 옮겼습니다. 단계별 버튼은 준비/결과 화면에 맞춰 표시합니다. 갤러리의 설명과 비교 표시는 제품 화면 바깥에 있으며 PNG에는 주석을 덧그리지 않았습니다. 현재·이전·승인 시안 PNG를 모두 이 폴더 안에 포함해 비교 갤러리는 오프라인에서도 열립니다. 상단의 원본 문서 링크는 저장소의 별도 자료입니다.

검사 수치와 배포 완료 여부는 최종 검증 기록에서 별도로 확인합니다. 이 이미지 목록만으로 실제 Windows·Burp GUI 또는 외부 CVE 서비스 접속 검증을 주장하지 않습니다.

## 실제 Linux 창 · 가이드

아래 자료는 격리한 **Linux Swing/창 관리자와 합성 Burp API·패킷**으로 연 실제 창의 이미지입니다. 가이드 본문은 JAR에 포함한 합성 예시 화면입니다. 실제 Windows Burp 본체·DPI/IME 또는 외부 서비스 실접속 검증과 구분합니다.

### Guide 화면별 이미지

| Guide 화면 | 어두운 테마 | 밝은 테마 |
|---|---|---|
| 요청·응답 목록 | [보기](images/guides/dark/guide-traffic.png) | [보기](images/guides/light/guide-traffic.png) |
| 기능 자동 매핑 | [보기](images/guides/dark/guide-mapper.png) | — |
| JS에서 API 찾기 | [보기](images/guides/dark/guide-js.png) | — |
| 서버·경로 탐색 | [보기](images/guides/dark/guide-environment.png) | — |
| 검색 노출 조사 | [보기](images/guides/dark/guide-exposure-search.png) | — |
| CVE 후보 | [보기](images/guides/dark/guide-exposure-cve.png) | [보기](images/guides/light/guide-exposure-cve.png) |
| 계정 권한 비교 | [보기](images/guides/dark/guide-auth.png) | [보기](images/guides/light/guide-auth.png) |
| 응답 보안 분석 | [보기](images/guides/dark/guide-response-recon.png) | — |
| 입력값 검사 · 요청 준비 | [보기](images/guides/dark/guide-payload.png) | — |
| 데이터 변환·비교 | [보기](images/guides/dark/guide-data.png) | — |
| 점검 진행 현황 | [보기](images/guides/dark/guide-activity.png) | — |
| 재현·증거 정리 | [보기](images/guides/dark/guide-bounty.png) | — |
| 점검 대시보드 | [보기](images/guides/dark/guide-dashboard.png) | — |
| 입력값 검사 · 실행·결과 | [보기](images/guides/dark/guide-payload-results.png) | — |
| 요청 재현 | [보기](images/guides/dark/guide-bounty-replay.png) | — |
| 인증 후보 연결 | [보기](images/guides/dark/guide-auth-accounts.png) | — |

[가이드 일반 창 · 어두움](images/guides/dark/01-guide-window.png) · [최대화 · 어두움](images/guides/dark/02-guide-maximized.png) · [가이드 일반 창 · 밝음](images/guides/light/01-guide-window.png) · [최대화 · 밝음](images/guides/light/02-guide-maximized.png)

### 버튼을 배치한 실제 창 예시

| 화면 | 어두운 테마 | 밝은 테마 |
|---|---|---|
| 요청·응답 목록 · 설정 메뉴 | [보기](images/native-buttons/dark/01-traffic-settings.png) | [보기](images/native-buttons/light/01-traffic-settings.png) |
| 입력값 검사 · 요청 준비 | [보기](images/native-buttons/dark/02-payload-prepare.png) | [보기](images/native-buttons/light/02-payload-prepare.png) |
| 입력값 검사 · 실행·결과 | [보기](images/native-buttons/dark/03-payload-results.png) | [보기](images/native-buttons/light/03-payload-results.png) |

### CVE · 1024px 좁은 창

[어두운 테마](images/cve-narrow/dark.png) · [밝은 테마](images/cve-narrow/light.png)

조회·취소 버튼과 필터가 좁은 창에서도 함께 표시되는 실제 Linux 창 예시입니다. 예시 CVE를 실제 취약점 조회 결과로 읽지 마세요.
