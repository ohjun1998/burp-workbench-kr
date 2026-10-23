# Workbench Next 0.52.0 화면 검증

2026-10-24에 최종 JAR의 운영 Swing 구성 요소를 합성 계정·요청·실행 메타데이터와 테스트 API 어댑터로 렌더링했습니다. 실제 Burp GUI, 브라우저 조작, 실서버 취약점 진단 결과가 아닙니다. 임시 프로필에서 내장 Mapper의 loopback 수신부를 초기화했으며 대상 서버로 HTTP 요청을 보내지 않았습니다.

## 기준과 결과

- JAR SHA-256: `72b97318aca25fd48986f4165b1cea28210cb5f5e7f9ff7e8e0e08b17e079995`.
- 작업 화면: 1024×720, 1280×800, 1440×920; 밝은 테마와 어두운 테마.
- 공통 창: 기본 760×480, 최소 560×300. 동일 창이 각 작업 화면 조건에서 반복 검증됩니다.
- 렌더링 180개, 측정한 입력·버튼 1122개, 탭·제목 252개.
- 합성 흐름 상태 검사 78개 통과: GET·JSON 후보 대기/완료, 설정 묶음, 쓰기 규칙, 준비 화면, Mapper 업무 검토, 실행·증거 연결, JS 페이지·검색·보존.
- 스크롤 영역 밖 제어 요소 잘림, 최소 크기 미달 제어 요소, 핵심 작업 영역 축소, 선택된 탭·제목 잘림: 모두 0건.
- 560×300 실행 연결 창의 분할 비율을 조정했습니다. 실행 표는 38px에서 41px로 넓어졌고, 상세 영역은 58px를 유지합니다. 검사 기준은 낮추지 않았습니다.

정확한 기준·수치·실패 목록은 [layout-report.json](layout-report.json)을 확인하세요. 아래 8개는 전체 180개 중 대표 화면이며, 스크롤 가능한 설정 창은 현재 보이는 구간을 보여 줍니다.

## 대표 화면

| 파일 | 확인할 기능 | 테마 | 이미지 크기 |
|---|---|---|---|
| [dark-workspace.png](dark-workspace.png) | 공통 원본과 8개 주요 메뉴 | 어두움 | 1280×800 |
| [light-setup-bundles.png](light-setup-bundles.png) | 권한 설정 묶음 검토 | 밝음 | 760×480 |
| [light-write-rules.png](light-write-rules.png) | 쓰기 요청별 조회 규칙 | 밝음 | 760×480 |
| [light-comparison-readiness.png](light-comparison-readiness.png) | 비교 전 설정과 행별 수정 | 밝음 | 760×480 |
| [dark-mapper-review.png](dark-mapper-review.png) | Mapper 기능을 업무로 연결하기 전 검토 | 어두움 | 760×480 |
| [light-execution-links-minimum.png](light-execution-links-minimum.png) | 최소 창에서 실행 목록과 증거 연결 상세 | 밝음 | 560×300 |
| [dark-js-history-page2.png](dark-js-history-page2.png) | JS 디스크 이력의 두 번째 페이지 | 어두움 | 760×480 |
| [light-response-retries.png](light-response-retries.png) | 응답 분석 상태와 원본 재시도 | 밝음 | 1280×800 |

## 재현 명령

최종 소스로 JAR와 테스트 어댑터를 빌드한 뒤 저장소 루트에서 실행합니다.

```sh
python tools/render_ui_layout.py --next-only --check --out tmp/upload-0520/ui-final
```

각 렌더링 전후의 JAR 해시를 대조합니다. 빌드 도중 JAR가 변경되면 검증은 실패합니다.
