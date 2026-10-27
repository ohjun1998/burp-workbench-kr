package kr.workbench;

import java.util.*;

/** Fixed, local purpose aliases. Searches feature metadata, never project or packet contents. */
final class NavigationTargets {
  record Target(String id, String title, String detail, String aliases) {
    @Override public String toString() { return title; }
  }
  private static final Map<String, String> ALIASES = Map.ofEntries(
      Map.entry("traffic", "요청 응답 트래픽 HTTP history 검색 패킷 scope 수집"),
      Map.entry("auth", "권한 비교 인증 인가 로그인 계정 세션 우회"),
      Map.entry("data", "데이터 변환 인코딩 디코딩 base64 hex 두 응답 차이"),
      Map.entry("js", "자바스크립트 javascript API URL 키 분석"),
      Map.entry("environment", "서버 경로 환경 GET 확인"),
      Map.entry("passive", "URL 주소 후보 수집 아카이브 passive"),
      Map.entry("activity", "업무 점검 현황 완료 남은 점검 계정"),
      Map.entry("response.recon", "응답 헤더 보안 분석 문서 저장 조회"),
      Map.entry("payload", "입력값 페이로드 payload 시험"),
      Map.entry("bounty", "재현 업무 흐름 순차 실행 OAST 외부 연결"),
      Map.entry("mapper", "기능 자동 매핑 매퍼 브라우저 mapping"),
      Map.entry("settings", "설정 화면 배치 테마 글자 크기"));
  static final List<Target> ALL;
  static {
    List<Target> targets = new ArrayList<>();
    for (FeatureGuide.Feature feature : FeatureGuide.all()) {
      if (feature.id().equals("start")) continue;
      targets.add(new Target(feature.id(), feature.title(), FeatureGuide.location(feature.id()),
          ALIASES.getOrDefault(feature.id(), "")));
    }
    targets.add(new Target("jwt", "JWT 내용 확인", "요청·응답 목록 → 변환·비교 → JWT",
        "jwt 토큰 내용 헤더 페이로드 token 디코딩"));
    targets.add(new Target("evidence", "증거 저장", "재현·증거 정리 → 증거 묶음",
        "증거 저장 zip 가림 미리보기 제출 보고서"));
    ALL = List.copyOf(targets);
  }
  static Target target(String id) { return ALL.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null); }
  static List<Target> search(String text, List<String> recent) {
    if (text != null && text.length() > 256) return List.of();
    String query = normalize(text);
    if (query.isEmpty()) {
      LinkedHashMap<String, Target> ordered = new LinkedHashMap<>();
      for (String id : recent) { Target target = target(id); if (target != null) ordered.put(id, target); }
      for (Target target : ALL) ordered.putIfAbsent(target.id(), target);
      return List.copyOf(ordered.values());
    }
    return ALL.stream().filter(t -> score(t, query) > 0)
        .sorted(Comparator.<Target>comparingInt(t -> score(t, query)).reversed()
            .thenComparing(Target::title)).toList();
  }
  private static int score(Target target, String query) {
    String title = normalize(target.title()), aliases = normalize(target.aliases());
    String haystack = title + " " + normalize(target.detail()) + " " + aliases + " " + target.id();
    for (String token : query.split("\\s+")) if (!haystack.contains(token)) return 0;
    if (title.equals(query)) return 1000;
    if (target.id().equals(query)) return 950;
    if (Arrays.asList(aliases.split("\\s+")).contains(query)) return 900;
    return title.contains(query) ? 800 : 500;
  }
  private static String normalize(String text) {
    return Objects.toString(text, "").strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }
}
