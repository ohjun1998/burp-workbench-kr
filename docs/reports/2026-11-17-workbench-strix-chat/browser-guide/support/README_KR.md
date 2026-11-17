# HTML 가이드 브라우저 검사 재현

Node와 Playwright 및 Chromium이 설치된 환경에서 다음 스크립트에 HTML·PNG manifest·출력 폴더를 명시합니다. 기본값은 이 작업 환경의 경로이며 다른 환경에서는 세 인수를 모두 지정합니다. Chromium 실행 파일은 스크립트의 `/usr/bin/chromium`을 사용합니다.

```sh
node check_offline.cjs /path/Workbench-Strix-1.1.0-사용가이드.html /path/native/guide-captures.json /path/new-verification
```

입력 HTML의 정확한 바이트만 소유한 loopback HTTP 서버에서 제공하고 첫 문서 로드 후 브라우저를 오프라인으로 전환합니다. 실제 Strix·모델·대상 서버나 GitHub 다운로드 링크는 열지 않습니다. `file://` 열기는 현재 관리 정책으로 차단되어 해당 제한을 이전 시도 기록에 구분했습니다. 브라우저 정책·사용자 프로필은 바꾸지 않습니다.
