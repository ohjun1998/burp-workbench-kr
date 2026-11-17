# Strix 네이티브 대화 연결 근거와 검증 범위

검토일은 2026-11-17이며, 공식 저장소 [usestrix/strix](https://github.com/usestrix/strix)의 커밋 [`1152d53579d5b3af37ada8c798f197ab093b99c6`](https://github.com/usestrix/strix/tree/1152d53579d5b3af37ada8c798f197ab093b99c6)을 기준으로 확인했다. 해당 소스의 패키지 버전은 1.7.1이다. 사용자의 설치 버전을 확인했다는 의미는 아니며, 이 내부 연동 규약은 향후 Strix 변경에 따라 달라질 수 있다.

Workbench는 설치된 공식 `~/.strix/bin/strix`를 별도 소유 프로세스로 실행한다. Strix의 Python 패키지를 시스템 Python에서 가져오거나, 별도 모델 대화로 진단 내용을 흉내 내지 않는다. 대화는 실행 중인 동일 Strix 프로세스의 코디네이터와 에이전트 세션으로 전달한다. 실시간 연결을 지원하지 않는 버전에서는 연결 실패를 표시하고 진단을 계속하며, 다른 모델이나 새 진단으로 자동 전환하지 않는다.

## 공식 소스 근거

| 확인 내용 | 고정된 공식 소스 |
| --- | --- |
| `--non-interactive`, `--resume`, 대상이 있을 때의 자동 시작, 실제 TTY 검사 | [interface/cli_args.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/cli_args.py) |
| TTY 부재 시 headless 전환, 준비·모델 확인 후 TUI 진입, 업데이트 질문 | [interface/main.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/main.py) |
| `STRIX_NO_UPDATE_CHECK` 지원 | [interface/update_check.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/update_check.py) |
| TUI가 소유한 코디네이터·이벤트 루프·진단 실행 및 종료 | [interface/tui/runtime.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/tui/runtime.py) |
| Ctrl+O의 `viewer.open`, Ctrl+Q의 기본 취소 종료 확인 | [interface/tui/internal/app/update.go](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/tui/internal/app/update.go) |
| 네이티브 `agent.send_message`, 실행 중 viewer와 코디네이터 연결 | [interface/tui/backend/controller.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/tui/backend/controller.py), [backend/messages.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/tui/backend/messages.py) |
| HTTP 인증·기능 확인·대화 전송·길이 제한·응답 규약 | [interface/viewer/server.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/viewer/server.py) |
| 저장된 실행·에이전트·대화 기록의 HTTP 표현 | [interface/viewer/transcript.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/viewer/transcript.py), [interface/tui/live_view.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/tui/live_view.py), [interface/tui/history.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/tui/history.py) |
| 사용자 메시지 큐, 같은 SDK 세션에 추가, 실행 중 스트림 중단·깨우기 | [core/agents.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/core/agents.py) |
| interactive 에이전트가 완료 후에도 후속 메시지를 기다리는 생명주기 | [core/execution.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/core/execution.py), [core/runner.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/core/runner.py) |
| 단독 `strix view`의 viewer 실행과 인자 | [interface/viewer/cli.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/interface/viewer/cli.py) |
| ChatGPT 구독 모델의 Strix 자체 인증 경로 | [config/models.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/config/models.py), [config/codex.py](https://github.com/usestrix/strix/blob/1152d53579d5b3af37ada8c798f197ab093b99c6/strix/config/codex.py) |

소스에서 임의의 `--chat`, `--web-port` 또는 일반 stdin JSON 대화 규약을 가정하지 않았다. `agent.send_message`는 Strix TUI 내부의 실제 명령이며, Workbench가 해당 비공개 IPC를 직접 구현하는 것은 아니다. 공식 TUI의 Ctrl+O로 생성한 HTTP viewer를 이용한다.

## 시작과 소유권

실시간 실행은 명시적인 실행 승인 후 POSIX PTY에서 시작한다. Java는 번들된 표준 라이브러리 전용 Python 도구를 `python3 -I`로 실행하고, 도구가 PTY를 만들어 기존 Strix 실행 파일을 호출한다. Python은 `/opt/homebrew/bin/python3`, `/usr/local/bin/python3`, `/usr/bin/python3` 순으로 확인한다. Windows 또는 POSIX PTY를 사용할 수 없는 환경의 실시간 대화는 명확하게 거부한다. 기존 대화 없는 실행 경로를 유지하지만, 이 작업에서 Windows의 실제 Strix 실행을 검증한 것은 아니다.

네이티브 실행 인자는 기존 `--config`, `--target`, `--instruction-file`, `--scan-mode`, `--max-turns`를 유지하고 `--non-interactive`를 넣지 않는다. 승인된 대상이 있으므로 고정한 upstream에서 `needs_setup`은 false이며, 준비·모델 확인을 마치면 진단을 자동 시작한다. 별도 시작 화면의 Enter 입력을 가정하지 않는다. PTY의 stdin/stdout과 `TERM=xterm-256color`가 실제 TTY 조건을 충족한다.

실행 자식 환경에만 `STRIX_NO_UPDATE_CHECK=1`을 설정하여 숨겨진 업데이트 질문이 대기를 막지 않게 한다. Docker 준비나 모델 확인이 길어져도 소유 실행이 살아 있는 동안 Ctrl+O를 재시도한다. Java의 승인된 실행 시간 상한은 그대로 적용된다.

자식의 `BROWSER`는 Workbench가 만든 비공개 캡처 도구로 한정한다. 캡처 도구는 Strix가 직접 전달한 `http://127.0.0.1:<포트>/?token=<값>`만 허용한다. 임의 수동 URL, 외부 호스트, `localhost` 별칭, 사용자정보, 다른 경로, 추가 쿼리, 조각은 허용하지 않는다. 연결 후에도 HTTP 실행 이름·대상 Origin을 이번 작업 디렉터리의 실제 `strix_runs/<실행>/run.json`과 대조한다. 다른 실행을 고르는 `?run=` 쿼리는 사용하지 않는다.

## 네이티브 HTTP 규약

| 요청 | 인증 및 의미 |
| --- | --- |
| `GET /?token=<네이티브 값>` | Strix가 발급한 실행 프로세스별 capability로 최초 인증한다. 응답의 `strix_viewer_session_<포트>` 쿠키가 같은 값인지 확인한다. |
| `GET /api/capabilities` | `can_steer: true`인지 확인한다. upstream에서 이 기능 표시는 공개이지만, 진단 데이터 접근 권한을 주지는 않는다. |
| `GET /api/run` | 이번 실행의 쿠키로 현재 실행 정보를 읽고 소유 기록 및 승인한 단일 Origin과 대조한다. |
| `GET /api/transcript` | 인증된 현재 실행의 `{agents, events}` 기록을 읽는다. |
| `POST /api/agents/steer` | 같은 쿠키와 JSON `{agent_id, message}`를 전달한다. HTTP 200이며 본문 `ok`가 정확히 true일 때만 접수로 처리한다. |

최초 쿠키는 upstream에서 `HttpOnly; SameSite=Strict; Path=/`로 발급된다. 이 값은 네이티브 viewer의 임시 접근 권한이며 ChatGPT OAuth 토큰과 다르다. Workbench는 OAuth 저장 파일을 열거나 값을 복사하지 않는다. 모델 호출과 인증 갱신은 Strix 자체 코드에 맡긴다. 기존 모델 선택에 실패했을 때 다른 인증 방식이나 모델로 자동 전환하지 않는다.

native 전송은 존재하는 에이전트에 사용자 지시를 큐에 넣고 깨울 수 있다. `ok: true`는 답변 완료나 진단 성공을 의미하지 않는다. `ok: false`, 인증 실패, 시간 초과는 성공으로 표시하지 않으며 자동 재전송하지 않는다. 전송이 도착했는지 불확실한 시간 초과 후 자동으로 다시 보내면 중복 지시가 될 수 있기 때문이다.

대화는 약 1.2초 간격으로 조회한다. 이 API는 네이티브 SQLite/WAL에 반영된 기록의 표현이며 SSE 또는 토큰 단위 스트리밍을 제공한다고 주장하지 않는다. 에이전트의 `parent_id: null`을 주 에이전트로 구분하며, 실제 `type: chat` 이벤트의 사용자·assistant 텍스트를 표시한다. 도구 이벤트를 assistant 답변으로 만들어 넣지 않는다.

## 생명주기와 실행 한도

interactive 에이전트는 완료·대기 상태가 되어도 소유 프로세스가 살아 있으면 같은 세션에서 후속 지시를 받을 수 있다. 전송은 실행 중 모델 스트림을 중단시키고 새 작업이나 진단 도구 실행을 유발할 수도 있다. 따라서 분석 전용 채팅 또는 대상 요청이 없는 질문이라고 보장하지 않는다. Workbench의 메시지별 기본 취소 확인과 현재 원본·프로젝트·SCOPE 검사를 적용한다.

`max-turns`는 네이티브 에이전트 루프 설정이며 HTTP 요청 수 예산이나 모든 후속 대화의 누적 총량 제한이 아니다. 실행 시간 상한은 대기 및 대화를 포함한 소유 프로세스의 전체 경과 시간에 적용하며, 메시지를 보냈다고 갱신하지 않는다. Strix 자체 예산 정지·사용자 일시정지·재개 정책도 별도로 존재한다.

대화 없는 `strix -n` 실행에는 이 실시간 viewer 연결이 없다. 단독 `strix view <실행>`에는 살아 있는 코디네이터의 steer handler가 없어 `can_steer`가 false이며, 진단 대화 전송 용도로는 읽기 전용이다. 프로세스가 끝난 뒤 `--resume <실행>`으로 실행 상태를 복원하는 것은 별도 실행이다. Workbench는 대화 전송을 위해 이를 자동 수행하지 않는다.

중지하면 기존 세대의 전송 권한을 즉시 무효화한다. 소유 PTY에는 Ctrl+Q, 확인 선택 전환, Enter를 보내 정상 종료를 요청한다. 네이티브 종료 질문의 기본값이 취소라는 점을 반영했다. 이후 소유 프로세스 그룹과 추적한 자식만 대상으로 제한된 종료 대기와 강제 종료 보완을 적용한다. 관련 없는 사용자 프로세스는 중지 대상으로 삼지 않는다. Docker 컨테이너나 잔여 자원의 정리가 확인되었다고 표시하지 않는다.

## Workbench 전송 격리와 표시 제한

Java HTTP 전송은 DNS를 사용하지 않는 리터럴 `127.0.0.1` 소켓과 `Proxy.NO_PROXY`를 사용한다. JVM 전역 `CookieHandler`, `Authenticator`, `ProxySelector`를 사용하지 않으며 이번 viewer의 쿠키만 직접 보낸다. 리디렉션을 따라가지 않는다. 고정한 upstream의 HTTP/1.x `Content-Length` 응답만 수신하고, 중복 헤더·전송 인코딩·잘못된 길이는 거부한다.

연결 시간 제한은 1.5초이고 응답 읽기에도 전체 1.5초 기한을 적용한다. 헤더는 32 KiB, JSON 응답은 4 MiB, 최초 HTML 응답은 256 KiB로 제한한다. 화면에는 최대 에이전트 256개, 최근 대화 200개·합계 128 KiB, 메시지당 16 KiB를 표시한다. 표시 전에 HTTP 크기로 제한된 전체 텍스트에서 알려진 인증값을 먼저 가린 뒤 길이를 자른다. 인증값이 16 KiB 표시 경계에 걸려도 일부 문자열을 노출하지 않도록 회귀 검사를 추가했다.

전송 메시지는 네이티브 규약에 맞춰 1~4,000 Unicode 문자로 제한하며, Java UTF-16 코드 단위 수와 구분한다. 보내기 직전에 현재 소유 세대와 에이전트를 다시 확인한다. 연결 취소는 HTTP 작업 완료를 기다리지 않는 플래그로 처리하여 UI 중지가 네트워크 응답을 기다리지 않게 한다.

PTY의 원시 화면은 공개 로그로 복사하지 않고 배출한다. 네이티브 viewer 주소에 포함된 임시 capability가 TUI 화면에서 로그로 흘러갈 수 있기 때문이다. 실행 디렉터리는 0700, 런타임 설정·지시문·도구·연결 인계 파일은 0600으로 만들고 심볼릭 링크를 거부한다. 프로세스 종료가 확인되면 비공개 입력과 연결 파일을 삭제한다. 종료가 확인되지 않으면 파일을 보존하고 새 실행을 막는 기존 정리 계약을 유지한다. 저장된 진단 보고서는 별도의 소유 경로 검사와 가림 미리보기 대상으로 남긴다.

## 검증한 것과 남은 것

`SimpleStrixChatClientTest`의 최신 독립 실행에서 **73개 검사**가 통과했다. 실제 POSIX PTY와 로컬 HTTP 모의 서버, 임시 설치 경로의 합성 Strix 실행 파일을 사용했다. 네이티브 인자·TTY·Ctrl+O·종료 확인 규약, 같은 실행의 질문과 응답, 쿠키 인증, 잘못된 URL·실행·대상·리디렉션 거부, 전송 접수 실패, Unicode 한도, 취소·이전 세대 차단, JVM 전역 쿠키·프록시 미사용, 표시 경계의 인증값 가림을 검증했다. 모의 서버 규약은 위 고정 upstream의 실제 소스와 대조했다.

이 검사는 설치된 실제 Strix 바이너리, 실제 모델, 실제 OAuth 로그인·갱신, 진단 대상 또는 Docker를 호출하지 않았다. 따라서 실제 모델 가용성이나 사용자의 Strix 버전 호환성, Burp 내부 진단 성공, Docker 정리를 입증하지 않는다. 전체 릴리스 검사와 네이티브 Swing 캡처의 최종 기록은 같은 보고서 디렉터리의 릴리스 증거를 따른다.
