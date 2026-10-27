package kr.workbench;

import java.util.List;

/** Simple keeps the shared engine IDs while describing only its visible workflow. */
public final class FeatureGuide {
  public static final String EXTENSION_NAME = BuildInfo.DISPLAY_NAME;
  public static final String SUITE_TAB = "Workbench Simple";
  public record Feature(String id, String title, String summary, String steps, String traffic) {
    @Override public String toString() { return title; }
  }
  private static final List<Feature> FEATURES = List.of(
      new Feature("start", "처음 시작하기", "기존 8개 도구를 유지하고 Scope 통합 점검으로 탐색·환경·API·민감정보를 한 번에 확인합니다.",
          "1. SCOPE 목록에 URL을 등록하고 상단 ‘Scope 통합 점검’을 엽니다. URL·계정을 선택해 통합 점검 시작을 누르면 조회·분석을 함께 실행합니다. 일반 트래픽 수집은 별도 OFF/SCOPE로 제어합니다.\n"
          + "2. 환경·DB, 경로·응답, API·명세 대조, 키·민감정보, 계정 차이와 커버리지를 확인합니다. SPA 메뉴 탐색은 브라우저 도우미 0.4.0을 연결하고 허용한 뒤 사용합니다.\n"
          + "3. 결과에서 원본·본문 위치를 확인하고 Repeater·계정 비교·입력값 검사로 연결합니다. 키의 유효성과 취약점은 수동 검증하며 발견한 키를 자동 사용하지 않습니다.\n"
          + "4. ‘증거에 추가’ 후 ‘재현·증거 → 증거 정리’에서 제목·재현·영향을 작성하고 가림 미리보기 → ZIP 저장으로 마칩니다.",
          "화면 이동과 Repeater 준비는 대상 요청을 보내지 않습니다. 비교·GET 확인·검사 시작·Send는 요청을 전송합니다. 기존 Workbench와 Simple의 설정·저장 공간은 분리됩니다."),
      new Feature("traffic", "요청·응답 목록", "SCOPE를 정하고 수집한 요청·응답 원본을 선택합니다.",
          "1. SCOPE 목록 → URL 추가 → 수집할 범위 체크 → OFF를 SCOPE로 전환합니다.\n"
          + "2. 사이트를 이용하거나 Burp History 우클릭 → Workbench Simple → 요청·응답 목록에 가져오기를 선택합니다.\n"
          + "3. 요청을 선택해 계정 비교 준비·Repeater 준비·증거에 추가로 이어갑니다. 우클릭에서 입력값 검사와 API 찾기도 연결합니다.\n"
          + "4. 검색창으로 좁히고 Pretty·Raw·Hex에서 원본을 확인합니다. 목록 고정은 화면만 멈추며 수집은 유지합니다.",
          "OFF는 새 수집과 수동 가져오기를 차단합니다. SCOPE는 체크한 Burp Scope·수동 URL에만 적용합니다. 수집과 원본 보기는 추가 요청이 없습니다. 검색·필터는 검색창 옆 설정에서 조정합니다."),
      new Feature("auth", "계정 권한 비교", "선택 요청을 다른 계정으로 직접 비교하고 근거를 확인합니다.",
          "1. 인증 탐지를 켜고 로그인 요청을 관측한 뒤 인증 후보를 계정에 지정합니다.\n"
          + "2. ‘선택 요청 비교’를 눌러 준비한 원본·계정·권한·차단 규칙을 확인합니다. 계정·판정 설정에서 필요한 조건을 보완합니다.\n"
          + "3. 준비 화면에서 직접 실행합니다. 결과를 선택해 계정별 응답과 종합 근거를 검토합니다.\n"
          + "4. ‘재현·증거’의 증거 추가 → 권한 비교 추가로 선택 결과를 연결합니다.",
          "인증 탐지는 수집 OFF/SCOPE와 별도입니다. Simple은 신규 요청 자동 비교 토글을 제공하지 않습니다. 비교는 서버에 재전송하며 GET·HEAD 기준 재조회와 설정한 세션/쓰기 전후 조회가 추가될 수 있습니다. 200 또는 색상만으로 취약점을 확정하지 않습니다."),
      new Feature("data", "데이터 변환·비교", "로컬에서 인코딩·JWT 내용을 읽고 두 응답을 비교합니다.",
          "1. ‘요청·응답 → 변환·비교’를 엽니다.\n2. 인코딩·JWT 또는 응답·데이터 비교를 고릅니다.\n"
          + "3. 비교할 원본을 연결해 차이를 확인하고 필요한 원본을 증거로 전달합니다.",
          "로컬 계산입니다. JWT 내용 확인은 서명 검증이 아니며 입력을 편집하면 원본 증거 연결이 해제됩니다."),
      new Feature("js", "JS에서 API 찾기", "관측한 텍스트 응답에서 API·키 후보와 출처를 찾습니다.",
          "1. 수집한 응답을 우클릭 → 선택 응답에서 API 찾기로 연결하거나 분석 ON 상태로 SCOPE 트래픽을 수집합니다.\n"
          + "2. ‘JS에서 API 찾기’에서 출처와 API 후보를 확인합니다.\n"
          + "3. 필요한 API를 선택하고 Ctrl+R로 요청을 준비한 뒤 메서드·인증·값을 확인합니다.",
          "시작 시 기존 History를 자동 순회하지 않습니다. 코드 분석은 JS 실행이나 요청 전송을 하지 않습니다. Repeater Send는 대상에 요청을 보냅니다. 외부 소스맵 탐색·NPM 조회 보조 메뉴는 Simple에서 제공하지 않습니다."),
      new Feature("environment", "서버·경로 탐색", "관측 환경과 경로 후보를 확인하고 필요한 GET만 직접 실행합니다.",
          "1. ‘서버·경로 탐색’에서 관측 사이트를 선택합니다.\n"
          + "2. 서버·경로 확인 또는 URL 후보 수집에서 필요한 후보를 고릅니다.\n"
          + "3. SCOPE·계정·요청 한도를 확인한 뒤 선택 경로 GET 확인을 직접 실행합니다.",
          "관찰은 추가 요청이 없습니다. GET 확인은 대상에 요청하며 공통 응답 대조 등 여러 요청이 추가될 수 있습니다. 후보는 존재나 취약점이 확정된 결과가 아닙니다."),
      new Feature("passive", "URL 후보 수집", "기존 기록에서 다음에 확인할 경로를 모읍니다.",
          "1. ‘서버·경로 탐색 → URL 후보 수집’을 엽니다.\n"
          + "2. 대상과 출처를 고릅니다. 로컬 기록은 Burp History / JS를 선택합니다.\n3. 필요한 URL만 GET으로 직접 확인합니다.",
          "자동·Wayback·gau 출처는 외부 서비스에 도메인을 조회할 수 있습니다. 대상 GET 확인은 별도 실행입니다."),
      new Feature("activity", "점검 진행 현황", "매핑한 요청의 URL·계정·업무 검토 상태를 확인합니다.",
          "1. ‘기능 자동 매핑 → 점검 진행 현황’을 엽니다.\n2. 필요한 계정과 요청을 연결합니다.\n"
          + "3. 실제 검증·증거 저장 뒤 완료 여부를 직접 판단합니다.",
          "기록·완료 표시는 대상 요청을 보내지 않습니다. 자동 분류와 점검 완료는 별개입니다."),
      new Feature("response.recon", "응답 보안 분석", "관측 응답에서 보안 단서와 원본 위치를 읽습니다.",
          "1. ‘응답 보안 분석’에서 후보를 선택합니다.\n2. 원본 위치와 근거를 확인합니다.\n"
          + "3. 필요한 관찰·흐름을 증거로 전달하고 실제 영향을 직접 검증합니다.",
          "관측 응답을 분석하며 추가 요청을 보내지 않습니다. 재출력·오류·헤더 관찰만으로 취약점을 확정하지 않습니다."),
      new Feature("payload", "입력값 보안 검사", "선택한 입력의 기준·변경·대조 응답을 비교합니다.",
          "1. 수집한 요청 우클릭 → 입력값 검사에 요청 연결을 선택합니다.\n2. 바꿀 값과 §PAYLOAD§ 위치를 지정합니다.\n"
          + "3. 유형·간격·총 요청 한도·SCOPE를 확인한 뒤 입력값 검사 시작을 직접 누릅니다. 결과를 검토해 증거로 전달합니다.",
          "요청 연결과 설정은 전송하지 않습니다. 검사 시작은 여러 시험·대조 요청을 전송할 수 있습니다. 후보는 수동 재현과 영향 확인이 필요합니다."),
      new Feature("bounty", "재현·증거 정리", "여러 요청을 순서대로 재현하고 원본·실행 결과를 증거 ZIP으로 묶습니다.",
          "1. ‘재현·증거 → 요청 재현’에서 선택한 원본을 단계로 추가합니다. 변수·기대 결과·범위·쓰기 허용·요청 한도를 확인한 뒤 직접 순차 실행하고 결과를 증거로 연결합니다.\n"
          + "2. ‘증거 정리’에서 원본·권한 비교·요청 재현 결과를 추가합니다.\n"
          + "3. 제목·영향·재현 절차와 각 증거의 설명을 작성합니다. 항목 설명은 적용 버튼을 눌러 반영합니다.\n"
          + "4. 순서를 정리하고 가림 미리보기에서 보고서·패킷을 확인한 뒤 ZIP 저장을 직접 실행합니다.",
          "순차 재현은 직접 시작할 때 대상에 요청을 보냅니다. 증거 편집·저장은 대상 요청을 보내지 않습니다. 제목·영향·절차 초안은 저장되지만 HTTP 원본은 재시작 후 자동 복원되지 않습니다. 직접 작성한 문장·이미지의 개인정보는 별도로 확인하세요."),
      new Feature("mapper", "기능 자동 매핑", "수집한 패킷을 기능과 실행 사례별로 묶습니다.",
          "1. ‘요청·응답’의 SCOPE를 설정하고 수집을 켠 뒤 사이트를 사용합니다.\n2. ‘기능 자동 매핑’에서 기능·실행·세션과 분류 근거를 확인합니다.\n"
          + "3. 필요한 이름·메모를 정리하고 .fmkr 프로젝트를 저장하거나 엽니다. Chrome/Edge 도우미 연결은 선택 사항입니다.",
          "내장 Mapper를 그대로 사용합니다. OFF/SCOPE는 수집 화면과 공유합니다. 프로젝트에는 원본 쿠키·토큰·본문이 포함됩니다. 분류는 추정이며 검증 완료가 아닙니다."),
      new Feature("settings", "설정", "화면 크기와 목록·패킷 배치를 조정합니다.",
          "1. 상단 도움말·설정 → 화면 설정을 엽니다.\n2. 글자 크기·목록 간격·배치를 선택합니다.\n3. 필요하면 배치·화면 설정을 초기화합니다.",
          "화면 설정만 바꾸며 대상 요청을 전송하지 않습니다. Simple 설정은 기존 Workbench와 분리됩니다."));
  private FeatureGuide() {}
  public static List<Feature> all() { return FEATURES; }
  public static Feature feature(String id) {
    return FEATURES.stream().filter(f -> f.id().equals(id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("알 수 없는 기능: " + id));
  }
  public static String title(String id) { return feature(id).title(); }
  public static String location(String id) {
    return switch (id) {
      case "traffic" -> "요청·응답";
      case "data" -> "요청·응답 → 변환·비교";
      case "mapper", "js", "response.recon", "environment" -> "" + title(id);
      case "passive" -> "서버·경로 탐색 → URL 후보 수집";
      case "activity" -> "기능 자동 매핑 → 점검 진행 현황";
      case "auth", "payload" -> "" + title(id);
      case "bounty" -> "재현·증거";
      case "settings" -> "도움말·설정 → 화면 설정";
      default -> "도움말·설정 → 현재 도구 안내 · F1";
    };
  }
  public static String helpText(String id) {
    Feature f = feature(id);
    return f.title() + "\n위치: " + location(id) + "\n\n" + f.summary()
        + "\n\n사용 순서\n" + f.steps() + "\n\n실행과 결과 확인\n" + f.traffic()
        + "\n\n단축키: Alt+1~8 도구 이동 · Ctrl+F 요청 검색 · Ctrl+R Repeater 준비 · F1 현재 도구 안내";
  }
}
