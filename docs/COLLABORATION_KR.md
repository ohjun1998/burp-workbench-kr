# 두 ChatGPT 계정 협업

공통 저장소: https://github.com/ohjun1998/burp-workbench-kr

## 연결

계정 A와 B에서 각각 GitHub 연결을 설정하고 이 저장소를 허용합니다. 두 ChatGPT 계정이 동일한 GitHub 계정 `ohjun1998`에 연결하는 구성이 가장 간단합니다. GitHub 설치 설정이 저장소 선택 방식이면 `burp-workbench-kr`가 선택되어 있어야 합니다.

서로 다른 GitHub 계정을 사용하면 저장소 소유자가 두 번째 GitHub 사용자를 collaborator로 초대하고 상대가 수락해야 합니다. ChatGPT 계정을 프로젝트에 초대하는 것과 GitHub 저장소 접근 권한은 별개입니다. 현재 구성에서 별도 GitHub 사용자를 임의로 초대하지 않습니다.

각 Work 대화에 아래 시작문을 붙여 넣고 작업 이름을 추가합니다. 연결된 환경에서 직접 Git 체크아웃이 가능하면 이 방식으로 시작합니다.

```sh
git clone https://github.com/ohjun1998/burp-workbench-kr.git
cd burp-workbench-kr
git switch main
git pull --ff-only origin main
git switch -c account-a/js-history-fix
python dev.py doctor
```

계정 B는 `account-b/<작업명>`을 사용합니다. GitHub 연결 도구만 제공되는 환경에서는 저장소 파일/커밋을 해당 도구로 가져옵니다. 자격 증명을 채팅, 소스 또는 ZIP에 넣지 않습니다. 연결만으로 로컬 `git push` 인증이 제공된다고 가정하지 않습니다.

## 계정 A 시작문

```text
우리는 https://github.com/ohjun1998/burp-workbench-kr 저장소의 Burp Workbench KR을 개발하고 있어.
나는 계정 A야. 최신 main의 커밋을 확인하고 AGENTS.md, README.md,
PROJECT_STATUS_KR.md, docs/COLLABORATION_KR.md 및 필수 인수인계 자료를 읽어줘.
account-a/<작업명> 새 브랜치에서 내가 지정한 기능을 구현하고 python dev.py check로 검증해줘.
main에 직접 쓰거나 다른 계정의 브랜치를 덮어쓰지 말고, 완료하면 PR을 만들어줘.
PR에 문제, 변경된 동작, 실제 검증 결과와 미완료 항목을 기록해줘.
마지막에 PR 주소와 다른 계정이 읽을 인수인계를 알려줘.
이번 작업: [여기에 기능 또는 결함을 적기]
```

## 계정 B 시작문

```text
우리는 https://github.com/ohjun1998/burp-workbench-kr 저장소의 Burp Workbench KR을 개발하고 있어.
나는 계정 B야. 최신 main과 지정한 PR의 출발/현재 커밋을 확인하고 AGENTS.md,
README.md, PROJECT_STATUS_KR.md, docs/COLLABORATION_KR.md를 읽어줘.
계정 A의 PR을 실제 코드와 테스트 결과로 검토해줘.
수정이 필요하면 account-b/<작업명> 브랜치에서 작업하고 별도 PR 또는 명시적으로 합의한 방식으로 반영해줘.
main에 직접 쓰거나 다른 계정의 브랜치를 덮어쓰지 말아줘.
실제로 실행한 검증과 수행하지 못한 검증을 구분하고 한국어로 설명해줘.
이번 작업 또는 검토할 PR: [여기에 작업 또는 PR 주소 적기]
```

## 일상 흐름

1. Issue에 기능/문제, 담당 A/B, 완료 조건 및 수정 범위를 적습니다.
2. 최신 `main`에서 작업별 브랜치를 새로 만듭니다. `account-a/ready`, `account-b/ready`는 연결 확인용 출발 브랜치입니다.
3. 구현 후 `python dev.py check`를 실행합니다. 기능 수정에서는 의미 있는 회귀 검사도 확인합니다.
4. 작업 브랜치를 push하고 `main` 대상 PR을 생성합니다. PR 자동 검증이 끝나면 다른 계정에서 검토합니다.
5. 검토와 검증을 확인한 뒤 merge합니다. 작업 시작 전에 `main`을 다시 가져옵니다.

GitHub가 저장하는 소스·커밋·Issue·PR은 공유됩니다. 각 계정의 대화 맥락과 사용량 한도는 합쳐지지 않으므로 인수인계를 저장소에 남깁니다. Work 환경의 네트워크나 GitHub 도구가 쓰기를 지원하지 않으면 변경 파일을 검증 가능한 패치로 준비하고 제한을 알립니다. 자동 동기화가 완료됐다고 말하지 않습니다.

## 충돌

작업 브랜치에서 최신 `main`을 merge 또는 rebase하고 충돌 파일을 확인합니다. 다른 계정의 변경을 버리지 않습니다. 충돌 해결 후 영향 검사를 다시 실행합니다. 자동 검증 실패 상태의 PR을 무조건 합치지 않습니다.

현재 계정 A/B는 동일한 GitHub 사용자로 활동할 수 있어 GitHub 리뷰가 별도 사람의 승인으로 집계되지는 않습니다. 담당 구분은 브랜치 접두사와 PR 설명을 사용합니다. 브랜치 보호 규칙이나 리뷰 강제 설정은 별도 적용 여부를 확인하며, 문서상의 절차가 서버에서 강제된다고 표현하지 않습니다.
