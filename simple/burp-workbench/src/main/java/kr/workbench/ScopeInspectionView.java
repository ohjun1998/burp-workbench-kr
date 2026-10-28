package kr.workbench;

import java.util.Locale;
import java.util.Set;

/** Semantic views over already-masked results. Filtering never reads packets or sends requests. */
final class ScopeInspectionView {
  enum Filter {
    ALL("전체 결과"), IMPORTANT("주요정보"), EVIDENCE("원본 응답 연결"),
    DECLARED("명세·경로 문자열"), UNRESOLVED("변수·서버 미해결"), EXAMPLE("예시·공개 정보"), METADATA("쿠키·인증 형식");
    private final String label;
    Filter(String label) { this.label=label; }
    @Override public String toString() { return label; }
  }
  private ScopeInspectionView() {}
  static boolean matches(Filter filter, ScopeInspection.Finding finding) {
    return switch(filter) {
      case ALL -> true;
      case IMPORTANT -> Set.of("SECRET","SENSITIVE","KEY","TOKEN","ENVIRONMENT","DATABASE","INTERNAL","API","ACCESS","ACCOUNT","PERMISSION").contains(finding.category()) && !example(finding) && !metadata(finding);
      case EVIDENCE -> !finding.originalId().isBlank();
      case DECLARED -> declared(finding);
      case UNRESOLVED -> unresolved(finding);
      case EXAMPLE -> example(finding);
      case METADATA -> metadata(finding);
    };
  }
  static boolean search(ScopeInspection.Finding finding,String query) {
    return text(finding).contains(normalize(query));
  }
  static boolean example(ScopeInspection.Finding finding) {
    String value=text(finding);
    return contains(value,"example_or_placeholder","public_identifier","auth_scheme_metadata","예제값","예시값","데모","공개 식별자","공개 연락처");
  }
  static boolean unresolved(ScopeInspection.Finding finding) {
    String value=text(finding);
    return contains(value,"dynamic_unresolved","static_fragment_unresolved","동적 미해결","동적값 미확정","필수값 필요","변수 미해결","서버/기본 경로 미확정","정적 경로 조각","서버 미확정","브라우저 확인 필요");
  }
  static boolean metadata(ScopeInspection.Finding finding) {
    return contains(text(finding),"cookie_value","auth_scheme_metadata","쿠키 값·인증 용도 미확인");
  }
  /** Display only. A masked relative path/template must never become an executable absolute URL here. */
  static String routeDisplay(String value) {
    if(value==null||value.length()>8192)return "";
    String absolute=ScopeInspectionAnalysis.safeUrl(value);if(!absolute.isEmpty())return absolute;
    String path=value.split("[?#]",2)[0];
    boolean relative=path.startsWith("/")&&!path.startsWith("//")||path.startsWith("./")||path.startsWith("../");
    boolean template=path.startsWith("${")||path.matches("(?i)^https?://.*\\$\\{.*");
    if(!relative&&!template)return "";
    return ScopeInspectionAnalysis.display(path.replaceAll("(?i)(/(?:token|api[_-]?key|secret|password|authorization)/)[^/]+","$1%5BREDACTED%5D"),4096).replace('\n',' ');
  }
  static boolean declared(ScopeInspection.Finding finding) {
    String value=text(finding);
    return (Set.of("API","PATH","MENU","INVENTORY").contains(finding.category()))
        && contains(value,"문서 선언","명세","문자열 관측","경로 후보","경로 조각","리터럴","템플릿","실제 도달 여부 미검증","도달 별도");
  }
  static String meaning(ScopeInspection.Finding finding) {
    if(metadata(finding)) return "쿠키·인증 형식 관측 · 인증 용도·유효 토큰 유출 확정 아님";
    if(example(finding)) return "예시·공개 후보 · 유효 비밀값 확정 아님";
    if(unresolved(finding)) return "변수·서버·필수값 미해결 · 대상 요청 성공 아님";
    if(declared(finding)) return "명세·경로 문자열 관측 · 대상 접근 여부는 탐색 커버리지에서 확인";
    if(Set.of("SECRET","SENSITIVE","KEY","TOKEN").contains(finding.category()))
      return "응답에서 관측한 후보 · 비인가 노출·외부 유효성 확정 아님";
    return finding.originalId().isEmpty()?"원본 미연결 요약 · 현재 응답으로 대신 열지 않음":"현재 응답 원본 연결 · 탐지 근거와 응답 내용을 함께 확인";
  }
  /** Keep useful discoveries ahead of bounded progress/coverage diagnostics in masked history. */
  static int historyPriority(ScopeInspection.Finding finding) {
    return switch(finding.category()) {
      case "SECRET","KEY","TOKEN" -> example(finding)||metadata(finding)?5:0;
      case "SENSITIVE" -> 1;
      case "ACCESS","ACCOUNT","PERMISSION" -> 2;
      case "ENVIRONMENT","DATABASE","API","INTERNAL","PATH","INVENTORY" -> 3;
      case "MENU","RESPONSE" -> 4;
      default -> 6;
    };
  }
  static boolean historyRelevant(ScopeInspection.Finding finding) {
    return !Set.of("COVERAGE","KATANA","CHANGE").contains(finding.category());
  }
  private static String text(ScopeInspection.Finding finding) {
    return normalize(String.join(" ",finding.category(),finding.title(),finding.url(),finding.method(),finding.account(),finding.state(),finding.confidence(),finding.severity(),finding.detail(),finding.originalId()));
  }
  static String normalize(String value) { return value==null?"":value.strip().toLowerCase(Locale.ROOT); }
  private static boolean contains(String text,String... values) {
    for(String value:values) if(text.contains(value)) return true;
    return false;
  }
}
