# 트래픽 검색 전후 측정

같은 합성 데이터와 운영 `DiskTrafficStore`·`TrafficBrowser`로 기준/개선 소스를 비교합니다. 메타데이터 일반 검색 10,000행, 정규식 검색 10,000행, 반복 본문 검색 500행을 각각 준비 운동 5회 후 30회 측정합니다. 결과 행·일치 수·부분 검사 수의 SHA-256이 두 버전에서 같아야 완료됩니다.

```sh
python audit/next053/search/run.py \
  --baseline /absolute/path/to/baseline \
  --candidate /absolute/path/to/final-source \
  --dependencies /absolute/path/to/repository-with-deps \
  --output /absolute/path/to/new-output-directory
```

소스 변경과 다른 빌드·부하 검사가 끝난 뒤 실행합니다. 컴파일 결과는 지정한 출력 디렉터리에만 기록하며 기존 클래스가 있으면 거부합니다. 컴파일 전후 소스 지문도 확인합니다. 별도 프로세스의 동일 JDK·512MiB heap 조건이며, 반복 실행할 때 새 출력 경로를 사용합니다.

`searchP50Ms`·`searchP95Ms`는 검색 자체의 벽시계 시간입니다. `threadCpuP50Ms`·`threadCpuP95Ms`는 지원되는 JVM에서 같은 작업 스레드의 CPU 시간을 기록하여 외부 부하·디스크 대기 영향과 구분합니다. 최대 heap은 10ms 간격의 표본이고 GC 횟수·누적 수거 시간은 준비 운동을 포함합니다. EDT 값은 작업 큐 대기 시간이며 화면 그리기나 native Burp 응답 시간을 나타내지 않습니다. 검색 p95에 디바운스·Swing 정렬 비용은 포함하지 않습니다.

속도 자체를 통과 조건으로 삼지 않습니다. 결과 동일성을 먼저 확인하고, 공유 호스트의 다른 작업·JIT·GC에 따른 편차를 함께 기록합니다. 실제 Burp·Windows·브라우저·외부 서버 검증이 아닙니다.

`--portable-compile`은 API 심볼이 제거된 JDK에서 임시 반복 검사를 위한 선택 사항입니다. `-source 17 -target 17`로 컴파일했다는 제한이 결과에 남으며, 정식 `--release 17` 또는 Java 17 환경 검증을 대체하지 않습니다.
