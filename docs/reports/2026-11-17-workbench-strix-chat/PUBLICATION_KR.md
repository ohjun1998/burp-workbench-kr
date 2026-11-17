# Workbench Strix1.1.0 배포 확인

- 날짜: 2026-11-17T23:48:57.230963+09:00 (Asia/Seoul)
- 배포 소스·가이드·JAR 커밋: `d9d815811ff4827a84dff7bf16fabf402989c494`
- 브랜치: `account-a/strix-chat`, 원격 push 완료. main 병합은 수행하지 않았습니다.
- 자동 검사8,300개, 불변 패키지 verify, 독립 소스 ZIP 재빌드의 바이트 일치, native Swing52장·464컴포넌트 비교를 확인했습니다. 모델/Strix/Burp 실환경 실행은 포함하지 않았습니다.
- 공개 URL6개를 전체 다운로드해 HTTP200·크기·SHA-256 및 ZIP/JAR CRC를 확인했습니다. [기계 판독 기록](public-download-verification.json).
- PR 생성은 `gh pr create --draft`로 시도했으나 `https://api.github.com/graphql`의 Forbidden 응답으로 생성되지 않았습니다. Git push와 공개 파일 읽기는 성공했습니다. [변경 비교](https://github.com/ohjun1998/burp-workbench-kr/compare/main...account-a/strix-chat).

- [burp-workbench-strix-1.1.0.jar](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/d9d815811ff4827a84dff7bf16fabf402989c494/simple/downloads/strix/1.1.0/burp-workbench-strix-1.1.0.jar) · HTTP200 ·9315380바이트 · SHA-256 `1984ee6014de648a6308ae8e0b0ee4d8a55c3ae38ddc4d5830beb0fdd5175f5b`
- [burp-workbench-strix-1.1.0-source.zip](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/d9d815811ff4827a84dff7bf16fabf402989c494/simple/downloads/strix/1.1.0/burp-workbench-strix-1.1.0-source.zip) · HTTP200 ·100078181바이트 · SHA-256 `541ffeb8d68c31ed6029223b20daa82cd6095946999d6f5e0f3489a985ef3a58`
- [Workbench-Strix-1.1.0-사용가이드.html](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/d9d815811ff4827a84dff7bf16fabf402989c494/downloads/guides/strix-1.1.0/Workbench-Strix-1.1.0-%EC%82%AC%EC%9A%A9%EA%B0%80%EC%9D%B4%EB%93%9C.html) · HTTP200 ·376501바이트 · SHA-256 `aa1816c7f368f93a9d3832315c2b4afe328c8833b01863b73cad9c95b8381d62`
- [Workbench-Strix-1.1.0-설치와가이드.zip](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/d9d815811ff4827a84dff7bf16fabf402989c494/downloads/guides/strix-1.1.0/Workbench-Strix-1.1.0-%EC%84%A4%EC%B9%98%EC%99%80%EA%B0%80%EC%9D%B4%EB%93%9C.zip) · HTTP200 ·9324047바이트 · SHA-256 `fca5b64fad242f62cbc3d0d61469e6d54efdf4fb2c198b21f8ef2ec4e45b366f`
- [Workbench-Strix-1.1.0-화면이미지.zip](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/d9d815811ff4827a84dff7bf16fabf402989c494/downloads/guides/strix-1.1.0/Workbench-Strix-1.1.0-%ED%99%94%EB%A9%B4%EC%9D%B4%EB%AF%B8%EC%A7%80.zip) · HTTP200 ·249989바이트 · SHA-256 `59d5f94fda60cb04b21228caca60f0b94badfe48770b66674d0aba0b9f7ce7e6`
- [02-chat-conversation.png](https://raw.githubusercontent.com/ohjun1998/burp-workbench-kr/d9d815811ff4827a84dff7bf16fabf402989c494/docs/reports/2026-11-17-workbench-strix-chat/native/chat/dark/1600x1000/02-chat-conversation.png) · HTTP200 ·104672바이트 · SHA-256 `5150dc989bbf00539a4df6ff2a342b3a360f6b30a4971d0b914e6d42a5b4cbf9`

현재 대화의 공유 다운로드 복사본은 `/workspace/shared/downloads/workbench-strix-1.1.0/`에 있습니다. HTML에는 PNG4개를 포함하며 이미지 원래 크기 보기와 화면 맞춤을 제공합니다.
