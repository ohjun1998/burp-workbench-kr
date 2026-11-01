# Katana · 이번 은행 시험은 NOT_RUN

공식 Katana 1.8.0 바이너리의 SHA-256은 `3214c11a0548667b37ec17bbb691706b7e0de1f0f24b3ead2ee12c301ebc1bbc`이며 고정 설치 metadata와 일치합니다. 바이너리·HOME·프로덕션 Simple 1.4.21 파일은 바꾸지 않았습니다.

은행 `127.0.0.1:24904`만 대상으로 writable workspace의 `-config`와 `-fc`를 모두 지정한 native 명령을 **한 번** 실행했습니다. 깊이1·최대2페이지·초당1개·crawl duration5초로 제한하고 상위 프로세스 제한7초를 두었습니다. 실제 native 프로세스는 **0.064초 / exit0**, stdout·stderr 각0바이트, JSONL0건이었습니다. 별도 `strace -f -e trace=network`에서 connect와 send 호출이 모두0이므로 대상 HTTP 요청도0입니다. **수집 성공이나 전체 경로 검증으로 계산하지 않습니다.** 프로덕션 `ScopeKatana` wrapper로 완료한 실행도 아닙니다.

현재 확인한 환경 제약은 `/home/agent` mount가 읽기 전용(ST_RDONLY)이고 필수 기본 파일 `/home/agent/.config/katana/form-config.yaml`이 없다는 점입니다. [upstream v1.8.0 options.go](https://github.com/projectdiscovery/katana/blob/v1.8.0/internal/runner/options.go)의 initializer는 `os.UserHomeDir()`에 고정한 이 경로를 읽거나 생성합니다. [runner.go](https://github.com/projectdiscovery/katana/blob/v1.8.0/internal/runner/runner.go)는 custom FormConfig를 읽기 전에 그 initializer를 무조건 호출하므로 `-config`·`-fc`·XDG 경로로 초기 위치를 바꿀 수 없습니다. 이 정적 원인과 현재 mount/파일 부재 증거는 실행 stdout/stderr에서 read-only 에러를 관측한 것과 구분합니다. **이번 명령은 `-silent`였으므로 해당 에러 로그를 관측하지 않았습니다.**

[upstream main.go](https://github.com/projectdiscovery/katana/blob/v1.8.0/cmd/katana/main.go)는 runner 생성 오류 때도 `os.Exit(0)`으로 반환합니다. 따라서 exit0만으로 native 수집 성공을 판단할 수 없습니다. 과거 별도 verbose 진단에는 `could not init default config` / `read-only file system`이 기록됐지만 그 기록을 이번 은행 실행 결과로 재사용하지 않았습니다.

네트워크 trace와 비어 있는 두 출력 파일, [실제 명령·계측 JSON](diagnostic.json)을 보존합니다. HOME 변경·재마운트·권한 승인·바이너리 패치나 동일 명령 재실행은 하지 않았습니다. Workbench의 기본 `ScopeInspectionRunner` 수집, JS·응답·권한·입력값 검사는 별도 실제 시험 대상이며 이 native 제약과 구분합니다.
