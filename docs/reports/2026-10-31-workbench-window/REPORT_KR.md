# Simple 1.4.10 · 독립 창과 압축 메뉴 반영

Workbench 탭은 독립 창을 여는 실행 패널이 됩니다. 같은 JFrame/도구 인스턴스를 유지해 최대화, F11/Esc 전체화면, 숨기기/다시 열기, 우클릭 도구 준비 후 창 표시를 지원합니다. 전체화면 전환 시 peer를 dispose하지 않아 열린 설정·원본·Scope 대화상자의 입력과 소유 관계를 유지합니다. 로드 해제 시 화면 자원과 예약 콜백을 정리합니다.

공통 상단은36px 한 줄이며 중복 브랜드·긴 경로를 줄였습니다. 동작/단축키/접근성 이름과 툴팁을 갖춘 아이콘, 폴더/도구별 색상, 선택/마우스 오버 반응과 조밀한 메뉴 간격을 적용했습니다. 기존5그룹·11메뉴와 전체 도구·원본 ID·인증/SCOPE/명시적 전송을 유지합니다.

전체 **6,029개**(153개 Java 그룹5,717 · payload HTTP136 · Mapper147 · Node29), 헤드리스 Swing **194개 화면**, 실제 창 **50개**(밝음25/어두움25), Katana **102개** 검증 통과. 잘린 컨트롤/최소 높이 미달은0개입니다. 실제 창은 격리한 Linux Xorg/xfwm4에서 합성 Burp API/패킷으로 검사했습니다. Windows Burp GUI와 DPI/IME는 미검증입니다.

사용자의 추가 요청에 따라11개 메뉴와 CVE 두 탭, 대시보드를 포함해13개 도구 화면을 각각 밝음/어두움으로 업로드하고, 실제 일반/최대화/전체화면6장을 더해 **32개 PNG**를 제공합니다. ZIP CRC와 HTML JavaScript 문법/파일 대응을 확인했습니다. 전체 이미지 ZIP과 선택형 HTML 갤러리는 docs/images/1.4.10과 같은 원본 PNG를 사용합니다.

Simple 소스·문서·이미지를 동결한 후 불변1.4.10 패키지를 생성/verify했고 기존 버전은 유지했습니다. 이전 Next는 수정하지 않았습니다.

- JAR SHA-256: `e863f670a2ba95d57c1f2c74bf06e3b476e0c9451247d47e7e399d5d52422560`
- source ZIP SHA-256: `eb189f132228e668df1cdfcf0fa1538a5d770e06c67f4044afd64b9e5d80e7d4`
- 이미지 ZIP SHA-256: `814790173ac4b34d774384d8f192223d8d2875d94e92c9c90307c0af836404a0`

[설치](../../../simple/downloads/1.4.10/burp-workbench-simple-1.4.10.jar) · [모든 도구 이미지](../../../simple/docs/images/1.4.10/README.md) · [전체 이미지 ZIP](workbench-1.4.10-images.zip) · [상세 검증](VERIFICATION.json)

초기 컴파일/샌드박스/검증 하네스 초점 문제와 최종 통과는 INITIAL_CHECKS_KR.md에서 구분합니다. GitHub CLI 인증을 사용할 수 없어 이전 세션에서 승인한 main 반영에 따라 작업 브랜치를 일반 병합하며 force push를 사용하지 않습니다.
