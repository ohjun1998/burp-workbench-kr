# 0.48.0 재검증 하네스

`AssessmentReplay.java`는 지정한 배포 JAR의 운영 Engine/PassiveFindings를 실제 loopback HTTP 응답에 적용합니다. Burp를 에뮬레이션하거나 실제 Burp 런타임을 시작하는 하네스는 아닙니다. HTTP 클라이언트가 해제한 chunk framing을 제거해 엔진 입력을 다시 만들므로 원본 wire 바이트 캡처라는 뜻도 아닙니다.

Benchmark 원본은 커밋 `bc5ffeb09fb18ab039806d84ca1f718c2e332c54`를 체크아웃합니다. 소스를 수정하지 않고 선택한24개 servlet과 원래 ThingFactory/Thing1/Thing2를 Tomcat9에 로드합니다. `benchmark-dependencies.json`의 고정 SHA-256을 확인합니다.

```sh
python audit/0.48.0/run_benchmark_xss.py \
  --benchmark /path/to/BenchmarkJava \
  --before /path/to/burp-workbench-kr-0.47.0.jar \
  --after downloads/extensions/0.48.0/burp-workbench-kr-0.48.0.jar \
  --java /path/to/jdk/bin/java --out tmp/assessment-048 \
  --browser /path/to/chrome-headless-shell
```

`--browser`는 선택 사항이며 Node에서 playwright 패키지를 찾을 수 있어야 합니다. 응답을 격리된 페이지에 렌더하고 외부 요청을 차단한 뒤 console 표식을 대조합니다. 새 대상 탐색·외부 키 검증·재귀 크롤링은 수행하지 않습니다. before/after의 기본 검사값 수 차이는 의도한 변경입니다.

OopsSec은 커밋 `ed7ae9b8b17dc17fd8617e9c79bd7222ca842374`, 원래 Next15.2.2/Prisma6.19.3/SQLite·seed와 정상 Alice 로그인으로 실행했습니다. `/api/user/export`의 잘못된 필드·정상 id/email·빈 fields 대조와 `/api/live/stream`을 두 JAR에서 확인했습니다. 현재 결과 JSON은 인증 쿠키·비밀 키 값을 제외합니다.

`AssessmentUiEvidence.java`는 기록된 결과를 운영 Swing 패널에 다시 표시합니다. 현재 테스트 어댑터와 같은 classpath로 컴파일·실행하며, 수동 Burp 조작이나 추가 HTTP 전송은 없습니다. 이미지 캡션에도 API 어댑터 사용을 표시합니다.

현재 근거: `docs/verification/assessment-0.48.0/`와 `docs/VERIFICATION_0.48.0.json`. 이전 보고서 전체 수치와 새 표본 결과를 합산하지 않습니다.
