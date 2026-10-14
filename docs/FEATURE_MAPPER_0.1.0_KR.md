# Feature Mapper KR 0.1.0 인수인계

사용자 요청: 수동 분류를 기본으로 하지 않고, 기능별 패킷을 자동으로 묶고 기능 이름·실행 사례에 매핑하는 새 확장 생성.

출발: main `747187431077fab7657af24b08f7dd24df2a08fc`. 작업: account-a/feature-mapper-0.1.0. 관련 Issue #14. 기존 Workbench의 릴리스와 독립적인 모듈이므로 기존 0.25.x/0.26.0 브랜치의 변경을 합치지 않습니다.

`feature-mapper/`에 독립 Montoya JAR와 Manifest V3 보조 확장을 추가했습니다. 메서드·URL·동작 필드·GraphQL·응답 ID 연결, 브라우저 화면·버튼·탭/프레임 연동, 세션 지문과 실행 사례, 원본 디스크 보관, 프로젝트 저장/복원, 분류 수정 규칙을 구현했습니다.

현재 환경에서 Java 자동 검사 48개와 Node 검사 6개 통과. 실제 Burp Community 2026.3.3-47134 / Java 21 / 별도 임시 프로필의 headless 검증을 수행했습니다. 업로드→게시글 작성→조회 연결, GraphQL 분리, 다른 세션 분리, 2MiB 요청·응답 원본 일치, late bridge 메타데이터 반영, 프로젝트 복원, 300건 초과 수집, 언로드 원본/리스너 정리를 확인했습니다.

실제 Chrome 프로필 실행은 로컬 환경의 Unix socket 생성 제한으로 차단되었습니다. Chrome 보조 확장의 실제 버튼 클릭·콘텐츠 스크립트·네트워크 관측·Mapper 연동 검증은 별도 GitHub CI에서 수행합니다. 해당 브라우저 검증은 실제 Burp 실행이 아니라 로컬 HTTP fixture와 프로덕션 Mapper/Bridge를 연결합니다. Burp 네이티브 검증과 구분합니다.

매핑 정확도를 정량 평가한 자료는 없습니다. 압축/multipart/WebSocket/SPA hash 경로 등의 지원 범위와 저장 파일의 원본 포함 여부는 [사용법과 현재 범위](../feature-mapper/README_KR.md)를 참고하세요. 실제 GUI 클릭 및 Pro 서비스는 검증하지 않았습니다.

설치 파일과 소스: [feature-mapper/dist](../feature-mapper/dist).
