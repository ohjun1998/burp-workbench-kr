# Burp 개발 작업공간 0.21.0

현재 JAR: burp-workbench-kr/burp-workbench/dist/burp-workbench-kr-0.21.0.jar
현재 사용법: README_0.21.0_KR.md
검증 결과: RELEASE_VERIFICATION_0.21.0.json
실습 사이트: community-security-lab/ (3.4.0)

JDK 17 이상 / Python 3.10 이상.

```text
cd burp-workbench-kr/burp-workbench
python build_all.py --test --java java --javac javac
python validation/all-features/run.py --java java --javac javac
```

Windows Java NIO 경로 오류가 나면 작업공간 내 짧은 임시 폴더를 지정하세요. 실제 Burp 하네스는 validation/native/에 포함되며 별도 Burp 경로/런처가 필요합니다. 현재 사용 중인 Burp 설정을 변경하지 말고 임시 프로필과 loopback 서버로 실행하세요.

0.20 이하 문서/기존 test-results/reference는 과거 기록입니다. 이번 계정·업무 탭은 README_0.21.0_KR.md를 확인하세요. 계정 세션 연결은 메모리에만 유지되므로 재로드 시 다시 지정합니다. 브라우저/실제 Pro Collaborator/외부 BApp/전체 Docker/TLS/HTTP2 검증은 미완료입니다.
