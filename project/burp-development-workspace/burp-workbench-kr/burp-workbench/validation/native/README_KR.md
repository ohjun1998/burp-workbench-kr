# 실제 Burp 네이티브 회귀 하네스 0.21.0

RuntimeUserAudit/RuntimeWholeAudit/RuntimeAssessmentAudit는 실제 Montoya API와 0.21.0 JAR 컨트롤러를 호출합니다. standalone main은 제공하지 않으며 Burp 개발 확장에서 static run을 호출하세요. 계정/업무 하네스는 전체 하네스 다음에 실행합니다.

별도 Burp 설치와 임시 프로필, loopback 실습 서버 18299, 가로채기 OFF 임시 프록시 18288, 실습 콜백 19112가 필요합니다. 사용 중인 포트나 기존 Burp를 사용하지 마세요. 전용 실습 서버에서 데이터를 생성/삭제하므로 프로세스 정리 런처와 함께 실행하세요.

전체 하네스의 additionalGaps 문구는 과거 개선 제안입니다. 현재 상태는 README_0.21.0_KR.md와 RELEASE_VERIFICATION_0.21.0.json을 우선합니다. 브라우저/외부 BApp/실제 Pro Collaborator 검증은 포함되지 않습니다.
