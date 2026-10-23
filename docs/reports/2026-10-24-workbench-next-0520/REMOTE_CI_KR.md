# 0.52.0 원격 검사 재확인

- 확인일: 2026-10-24 (Asia/Seoul)
- 소스·배포 커밋: `158bee07f6cfa4e900551d8e6c0e6162501c0589`
- PR: [#86](https://github.com/ohjun1998/burp-workbench-kr/pull/86), main 병합 없음

**0.52.0 JAR·소스·가이드는 게시되어 있으나, 모든 환경의 검증이 통과한 상태는 아닙니다.** 배포 파일의 해시·소스 지문은 재확인했습니다. 기존 0.49.1·Next 0.50.0 배포물, release/history와 main은 보존했습니다.

| 검사 | 결과 | 확인 내용 |
|---|---|---|
| [Build and verify](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39993644029) | 통과 | 원격 기본 빌드·검증 |
| [격리 디스크·부하](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39993644021) | 통과 | `isolated-disk-and-soak` 작업 |
| [Windows Java 회귀](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39993644021) | 2회 실패 | 첫 시도는 검색 그룹 펼치기, 재시도는 중요 표시 변경 후 검색 결과의 검사 실패 |
| [실제 브라우저 도우미 연동](https://github.com/ohjun1998/burp-workbench-kr/actions/runs/39993643920) | 2회 실패 | `BrowserFixture`의 POST/PATCH 본문 해시 일치 검사에서 `body hashes not verified` |

실패 작업만 한 번 재실행했습니다. Windows는 재시도에서 다른 검색 검사까지 진행했지만 다시 실패했습니다. 검사 타이밍 문제인지 제품 동작 문제인지 원인을 확정하지 않았으며, 검사를 생략하거나 통과 조건을 완화하지 않았습니다. 브라우저 검사도 같은 실패를 재현했습니다.

기존 배포 기록의 Workbench 3,707개, Mapper Java 114개·Node 8개, Swing 화면 180개·합성 상태 78개 통과는 해당 로컬·어댑터 환경의 결과입니다. 이를 Windows나 실제 브라우저 연동까지 통과했다는 의미로 사용하지 않습니다. 실제 Burp GUI도 검증 완료로 안내하지 않습니다.

이번 후속 변경은 검사 기록과 대화 기록만 추가합니다. 게시된 0.52.0 JAR·ZIP과 소스는 덮어쓰지 않았습니다. 다음 소스 수정은 새 버전에서 실패를 재현·수정하고 해당 환경을 재검증해야 합니다.

[기계 판독 결과](remote-ci-recheck.json) · [설치·기능별 가이드](../../RELEASE_0.52.0_NEXT_KR.md)
