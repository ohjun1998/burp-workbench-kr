package kr.workbench;

import java.util.List;

/** Stable feature IDs keep names, navigation and the beginner guide in agreement. */
public final class FeatureGuide {
  public static final String EXTENSION_NAME = BuildInfo.DISPLAY_NAME;
  public static final String SUITE_TAB = "Workbench KR · 웹 보안 점검";

  public record Feature(String id, String title, String summary, String steps, String traffic) {
    @Override
    public String toString() {
      return title;
    }
  }

  private static final List<Feature> FEATURES =
      List.of(
          new Feature(
              "start",
              "처음 시작하기",
              "사이트의 정상 요청을 모으고, 필요한 검사만 직접 실행합니다.",
              "1. Burp 브라우저로 사이트를 사용하고 ‘요청·응답 목록’에서 요청을 선택합니다. 기존 History는 Burp 우클릭 메뉴에서 가져옵니다.\n"
                  + "2. Burp HTTP history의 Add to scope 또는 Workbench SCOPE 목록의 URL 추가로 등록하고 체크 상태를"
                  + " 확인합니다.\n"
                  + "3. 이 안내에서 필요한 기능을 고르고 ‘이 기능 열기’를 누릅니다. 검사 후 실제 요청·응답을 비교하고 증거를 저장합니다.",
              "SCOPE 등록만으로 모든 검사를 실행하지 않습니다. Repeater 준비는 전송이 아닙니다. 실행·GET 확인·자동 비교 ON은 추가 요청을 보낼 수"
                  + " 있습니다.\n"
                  + "Workbench의 체크·삭제 상태는 별도로 저장됩니다. 수집 버튼은 기본 OFF이며 OFF ↔ SCOPE로 순환합니다. SCOPE에서는 목록에서"
                  + " 체크한 Burp Scope와 수동 URL을 수집합니다. Burp의 Add to scope는 자동 반영됩니다. 수동 URL은 행을 선택하고 아래 입력값을 바꿔"
                  + " URL 수정을 누릅니다. 수정 시 체크 상태를 유지하며 Burp 전역 Scope는 바꾸지 않습니다."),
          new Feature(
              "traffic",
              "요청·응답 목록",
              "사이트에서 발생한 요청을 고르고 다른 점검 도구로 연결합니다.",
              "1. Burp 브라우저로 사이트를 사용합니다. 기존 History는 Burp에서 요청 우클릭 → Workbench KR → 요청·응답 목록에 가져오기를"
                  + " 선택합니다.\n"
                  + "2. SCOPE 목록에서 URL을 추가하거나 Burp HTTP history → Add to scope로 등록합니다. 수집할 범위를 체크하고 OFF를 SCOPE로 바꿉니다."
                  + " 수동 URL은 행 선택 → 아래 URL 변경 → URL 수정으로 편집합니다. OFF에서는 수동 가져오기도 수집하지 않습니다.\n"
                  + "3. 선택 요청의 메뉴에서 계정 비교, 입력값 검사, JS 분석 또는 증거 정리로 연결합니다.",
              "패킷의 Pretty는 정렬 보기, Raw는 원문, Hex는 원본 바이트입니다. 아래 Search에서 검색하고 ↑·↓ 또는 Enter·Shift+Enter로 이동합니다. Aa는 대소문자, .*는 정규식이며 ⋯로 Burp 기본 편집기를 엽니다. 미리보기·검색은 앞 64 KiB 기준이며 원본은 전체 보관합니다.\n"
                  + "수집과 원본 보기는 추가 요청을 보내지 않습니다. 우클릭의 ‘계정 비교 실행’은 재전송하고, ‘URL 후보 수집 시작’은 설정에 따라 외부 아카이브를"
                  + " 조회합니다. Repeater 준비는 전송하지 않습니다."),
          new Feature(
              "auth",
              "계정 권한 비교",
              "같은 요청을 다른 계정으로 보내 접근 권한 차이를 확인합니다.",
              "1. 정상 로그인 상태의 요청을 수집하고 인증 후보를 비교 계정에 지정합니다.\n"
                  + "2. 역할·조직·예상 허용 정책을 확인한 뒤 요청·응답 목록에서 기준 요청을 선택합니다.\n"
                  + "3. ‘선택 요청 비교’를 눌러 응답 내용과 판정 근거를 검토합니다. 200 응답만으로 권한 문제를 확정하지 않습니다.",
              "선택 요청 비교는 서버에 재전송합니다. ‘새 요청 자동 비교 ON’은 이후 조건에 맞는 Proxy·Repeater 요청도 계정별로 재전송합니다."
                  + " 처음에는 OFF로 두고 한 요청씩 비교하세요."),
          new Feature(
              "data",
              "데이터 변환·비교",
              "인코딩을 풀거나 JWT 내용을 읽고 두 응답의 차이를 비교합니다.",
              "1. 인코딩·변환에서 입력과 변환 방식을 고르거나 JWT 내용 확인에서 토큰을 확인합니다.\n"
                  + "2. 응답·데이터 비교에서 기준·대조 요청을 각각 선택해 왼쪽과 오른쪽으로 가져옵니다.\n"
                  + "3. 비교 실행 후 차이를 읽고, 원본이 연결되어 있으면 ‘원본 → 증거’로 전달합니다.",
              "로컬 계산만 합니다. JWT 내용 확인은 서명 검증이 아니며, 응답 차이는 취약점 확정이 아닙니다. 입력을 편집하면 해당 원본 증거 연결이"
                  + " 해제됩니다."),
          new Feature(
              "js",
              "JS에서 API 찾기",
              "JavaScript에서 API 경로·입력 이름·민감정보 후보를 찾습니다.",
              "1. SCOPE 내 사이트를 사용해 JS 응답을 모읍니다. History 자동 분석 또는 선택 응답 가져오기를 사용합니다.\n"
                  + "2. 최근 분석에서 파일을 고르고 URL·API, 토큰·API 키 후보, 원문 위치를 확인합니다.\n"
                  + "3. 동적 값을 확인한 뒤 요청 초안을 Repeater에 준비합니다. 실제 요청은 직접 전송합니다.",
              "코드 분석은 JS를 실행하거나 대상에 요청하지 않습니다. ‘소스맵 탐색’은 대상 URL을 요청하고 ‘NPM 존재 조회’는 설정한 레지스트리를"
                  + " 조회합니다. 발견 경로가 실제 기능이라는 보장은 없습니다."),
          new Feature(
              "environment",
              "서버·경로 탐색",
              "응답에서 서버 환경을 추정하고 관련 경로 후보를 확인합니다.",
              "1. SCOPE 내 응답을 수집하고 대상 설정에서 관측된 사이트를 고릅니다. 후보 설정에서 경로 범위·검증 계정을 확인합니다.\n"
                  + "2. 관찰한 환경과 추천 경로에서 필요한 후보를 고릅니다.\n"
                  + "3. ‘선택 경로 GET 확인’ 또는 ‘후보 목록 GET 확인’을 눌러 실제 응답과 없는 경로의 응답을 비교합니다.",
              "환경 관찰은 추가 요청이 없습니다. GET 확인은 대상에 요청하며 설정에 따라 없는 경로의 기준 응답도 추가로 요청합니다. 후보 하나가 요청 한 번을"
                  + " 뜻하지 않습니다."),
          new Feature(
              "passive",
              "URL 후보 수집",
              "기존 트래픽·JS·API 문서·공개 아카이브에서 경로를 모읍니다.",
              "1. 대상 설정에서 사이트를 열고 수집 출처를 고릅니다. 로컬 기록만 보려면 ‘Burp History / JS’를 선택합니다.\n"
                  + "2. URL 수집을 시작한 뒤 출처·메서드·분류로 후보를 좁힙니다.\n"
                  + "3. 필요한 URL에 ‘선택 URL GET 확인’ 또는 ‘표시 URL GET 확인’을 실행하고 실제 응답을 검토합니다.",
              "수집은 대상 URL에 GET을 보내지 않지만 자동·Wayback·gau 모드는 외부 서비스에 도메인을 조회할 수 있습니다. GET 확인은 별도 서버"
                  + " 요청입니다. 수집된 경로는 존재·권한·취약점이 확인된 결과가 아닙니다."),
          new Feature(
              "activity",
              "점검 진행 현황",
              "발견한 URL·입력·계정·업무에서 아직 확인하지 않은 항목을 찾습니다.",
              "1. URL 점검 현황에서 관측한 요청과 JS 발견 후보를 구분합니다.\n"
                  + "2. 계정별 점검에서 실제 로그인 계정을 연결하고 업무별 현황에서 필요한 계정·API를 정합니다.\n"
                  + "3. 검증과 증거 저장 후 입력·URL·업무를 직접 완료 처리합니다. 재검토 이유가 생기면 다시 확인합니다.",
              "현황 수집·완료 표시·파일 확인은 대상 요청을 보내지 않습니다. Repeater 초안도 준비만 합니다. 요청 관측이나 검사 실행이 자동 점검 완료를"
                  + " 뜻하지 않습니다."),
          new Feature(
              "response.recon",
              "응답 보안 분석",
              "관측 응답의 오류·보안 헤더·API 문서·저장 후 조회 흐름을 살펴봅니다.",
              "1. 응답 관찰에서 후보를 선택해 원본과 근거 위치를 읽습니다.\n"
                  + "2. API 문서의 경로를 확인하거나 저장/조회 연결에 고유 마커를 등록합니다.\n"
                  + "3. 실제 영향과 브라우저 실행은 직접 검증하고 ‘선택 관찰 → 증거’ 또는 ‘선택 흐름 → 증거’로 전달합니다.",
              "관측된 응답을 분석하며 추가 요청을 보내지 않습니다. Repeater 초안은 직접 전송해야 합니다. 마커 재출력·오류 노출·헤더 관찰만으로 취약점이"
                  + " 확정되지는 않습니다."),
          new Feature(
              "payload",
              "입력값 보안 검사",
              "선택한 입력에 시험 문자열을 넣어 기준·변경·대조 응답을 비교합니다.",
              "1. 요청·응답 목록 또는 Burp 우클릭에서 요청을 연결합니다. 바꿀 값을 선택하고 §PAYLOAD§ 위치를 지정합니다.\n"
                  + "2. 검사 설정에서 유형·기준값·간격·총 요청 한도와 SCOPE를 확인합니다.\n"
                  + "3. ‘입력값 검사 시작’을 누른 뒤 실제 요청·응답과 판단 근거를 검토하고 결과를 증거로 전달합니다.",
              "요청 연결·위치 지정·설정은 전송하지 않습니다. 검사 시작은 시험 요청을 서버에 보내며 유형과 설정에 따라 여러 대조 요청이 추가됩니다. 후보를"
                  + " 발견해도 수동 재현과 영향 확인이 필요합니다."),
          new Feature(
              "bounty",
              "재현·증거 정리",
              "여러 요청을 순서대로 재현하고 제출할 근거를 묶습니다.",
              "1. 여러 요청 재현에서 요청을 단계로 추가하고 대상·변수·기대 응답을 확인합니다.\n"
                  + "2. 직접 순차 실행한 결과, 계정 비교, 입력값 검사 또는 연결 원본을 증거 묶음에 추가합니다.\n"
                  + "3. 영향과 재현 절차를 적고 가림 미리보기를 확인한 뒤 증거 ZIP을 저장합니다.",
              "증거 편집·저장은 대상 요청을 보내지 않습니다. 순차 실행은 서버에 요청합니다. 외부 연결 확인(OAST)의 주소 발급·조회는 해당 서비스에 접속하며"
                  + " 대상 검증 요청은 별도로 실행해야 합니다. 외부 도구 안내가 해당 BApp을 대신 실행하지는 않습니다."),
          new Feature(
              "settings",
              "설정",
              "모든 도구의 요청·응답 배치를 읽기 편한 방향으로 바꿉니다.",
              "1. 화면 배치를 선택합니다.\n"
                  + "2. 요청·응답과 목록 위치가 바뀐 것을 확인합니다.\n"
                  + "3. 필요하면 분할선을 드래그해 현재 작업의 읽기 공간을 조정합니다.",
              "화면 설정만 바꾸며 요청을 전송하지 않습니다. 기존 점검 결과와 SCOPE는 그대로 유지됩니다."));

  private FeatureGuide() {}

  public static List<Feature> all() {
    return FEATURES;
  }

  public static Feature feature(String id) {
    return FEATURES.stream()
        .filter(f -> f.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("알 수 없는 기능: " + id));
  }

  public static String title(String id) {
    return feature(id).title();
  }

  public static String helpText(String id) {
    Feature f = feature(id);
    return f.title()
        + "\n\n어떤 기능인가요?\n"
        + f.summary()
        + "\n\n시작 순서\n"
        + f.steps()
        + "\n\n추가 요청과 결과 해석\n"
        + f.traffic()
        + "\n\n단축키: Ctrl+F 요청 검색 · Ctrl+R Repeater 준비 · F1 기능 안내";
  }
}
