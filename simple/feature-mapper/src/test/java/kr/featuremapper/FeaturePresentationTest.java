package kr.featuremapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Presentation preserves mapping identity and authored data while shortening automatic captions. */
public final class FeaturePresentationTest {
  private static int checks;
  private static final String ORIGIN = "https://presentation.test";

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
    System.out.println("PASS " + message);
  }

  private static Mapper.FeatureSummary auto(String id, String path, String entity, String operation, int packets) {
    return new Mapper.FeatureSummary(id, ORIGIN, entity, entity + " / " + operation + " · " + path, "미점검", packets);
  }

  private static FeaturePresentation.Entry entry(List<FeaturePresentation.Group> groups, String id) {
    return groups.stream().flatMap(group -> group.entries().stream()).filter(e -> e.id().equals(id)).findFirst().orElseThrow();
  }

  private static String category(List<FeaturePresentation.Group> groups, String id) {
    return groups.stream().filter(g -> g.entries().stream().anyMatch(e -> e.id().equals(id))).findFirst().orElseThrow().key();
  }

  public static void main(String[] args) throws Exception {
    Map<String, FeaturePresentation.Source> sources = new HashMap<>();
    List<Mapper.FeatureSummary> summaries = new ArrayList<>();
    add(summaries, sources, "balance", "/check_balance/{id}", "check_balance", "목록·조회", 13);
    add(summaries, sources, "login", "/login", "인증", "로그인", 2);
    add(summaries, sources, "merchant", "/merchant/login", "merchant", "로그인", 2);
    add(summaries, sources, "docsA", "/api/docs/", "docs", "목록·조회", 2);
    add(summaries, sources, "docsB", "/v3/api-docs", "api-docs", "목록·조회", 2);
    add(summaries, sources, "quote", "/check_balance/%27", "check_balance", "목록·조회", 1);
    add(summaries, sources, "sql", "/check_balance/1%27%20AND%20%271%27%3D%271%27%20--%20", "check_balance", "목록·조회", 1);
    add(summaries, sources, "control", "/wb-missing-c55adce8-9cf0-4388-822b-ae16f84f5735", "wb-missing-c55adce8-9cf0-4388-822b-ae16f84f5735", "목록·조회", 2);
    add(summaries, sources, "home", "/", "서비스", "목록·조회", 3);
    add(summaries, sources, "env", "/.env", ".env", "목록·조회", 3);
    add(summaries, sources, "unfamiliar", "/api/custom_resource", "custom_resource", "목록·조회", 4);
    Mapper.FeatureSummary common = new Mapper.FeatureSummary("common", ORIGIN, "공통 요청", "공통 요청", "완료", 28);
    summaries.add(common);
    sources.put(common.id(), new FeaturePresentation.Source(ORIGIN + "|공통 요청", false));
    var groups = FeaturePresentation.groups(summaries, sources);
    check(entry(groups, "balance").label().equals("잔액 조회"), "automatic balance captions omit repeated operation and URL");
    check(entry(groups, "balance").packets() == 13 && entry(groups, "balance").state().equals("미점검"), "counts and assessment state are retained exactly");
    check(entry(groups, "merchant").label().equals("가맹점 로그인") && entry(groups, "login").label().equals("로그인"), "short authentication names remain distinguishable");
    check(category(groups, "balance").equals("business") && category(groups, "login").equals("account"), "business and authentication groups use metadata paths");
    check(category(groups, "quote").equals("inputs") && category(groups, "sql").equals("inputs"), "encoded quote and SQL input variations are folded separately");
    check(category(groups, "control").equals("control") && entry(groups, "control").label().equals("대조 경로"), "generated comparison paths omit random UUIDs");
    check(category(groups, "unfamiliar").equals("other"), "unknown application paths are not assigned an invented business meaning");
    check(groups.stream().filter(g -> g.key().equals("docs") || g.key().equals("inputs") || g.key().equals("control")).noneMatch(FeaturePresentation.Group::initiallyExpanded), "exploration and variation groups start folded");
    check(groups.stream().flatMap(g -> g.entries().stream()).map(FeaturePresentation.Entry::id).distinct().count() == summaries.size(), "projection retains every feature identity once");
    check(groups.stream().flatMap(g -> g.entries().stream()).mapToInt(FeaturePresentation.Entry::packets).sum() == summaries.stream().mapToInt(Mapper.FeatureSummary::packets).sum(), "projection retains all original feature observation counts");
    check(entry(groups, "docsA").label().startsWith("API 문서 경로 · ") && !entry(groups, "docsA").label().equals(entry(groups, "docsB").label()), "duplicate shortened names receive stable distinct suffixes");
    String before = entry(groups, "docsA").label();
    add(summaries, sources, "docsC", "/api-docs", "api-docs", "목록·조회", 2);
    check(entry(FeaturePresentation.groups(summaries, sources), "docsA").label().equals(before), "new duplicate observations do not renumber an existing suffix");
    var secondOrigin = new Mapper.FeatureSummary("other-site", "https://another.test", "docs", "docs / 목록·조회 · /api/docs/", "미점검", 4);
    summaries.add(secondOrigin);
    sources.put(secondOrigin.id(), new FeaturePresentation.Source("https://another.test|GET /api/docs/ | 목록·조회", false));
    var multipleOrigins = FeaturePresentation.groups(summaries, sources);
    check(multipleOrigins.stream().filter(g -> g.key().equals("docs")).count() == 1
        && !entry(multipleOrigins, "docsA").label().equals(entry(multipleOrigins, "other-site").label())
        && entry(multipleOrigins, "other-site").origin().equals("https://another.test"), "same short caption from different origins stays in one group with distinct identity and retained origin");

    String authored = "내가 정한 / 원문 · 보존 [13건]";
    var edited = new Mapper.FeatureSummary("edited", ORIGIN, "check_balance", authored, "진행 중", 9);
    var authoredSource = Map.of("edited", new FeaturePresentation.Source(ORIGIN + "|GET /check_balance/{id} | 목록·조회", true));
    var authoredEntry = entry(FeaturePresentation.groups(List.of(edited), authoredSource), "edited");
    check(authoredEntry.label().equals(authored) && authoredEntry.originalName().equals(authored), "user-authored names are preserved literally, including punctuation");
    var browser = new Mapper.FeatureSummary("browser", ORIGIN, "화면 기능", "프로필 저장 · /profile/edit", "미점검", 3);
    var browserSource = Map.of("browser", new FeaturePresentation.Source(ORIGIN + "|browser|/profile/edit|프로필 저장", false));
    check(entry(FeaturePresentation.groups(List.of(browser), browserSource), "browser").label().equals("프로필 저장"), "browser action labels omit only their generated route suffix");
    var api = new Mapper.FeatureSummary("api", ORIGIN, "주문", "주문 상세 확인", "완료", 2);
    check(entry(FeaturePresentation.groups(List.of(api), Map.of("api", new FeaturePresentation.Source(ORIGIN + "|GET /api/orders/{id} | 상세 조회", false))), "api").label().equals("주문 상세 확인"), "meaningful OpenAPI captions remain intact");
    var english = new Mapper.FeatureSummary("english", ORIGIN, "check_balance", "Check account balance", "미점검", 13);
    check(entry(FeaturePresentation.groups(List.of(english), Map.of()), "english").label().equals("잔액 조회"), "approved exact bank caption has a concise Korean presentation");
    add(summaries, sources, "apostrophe", "/api/users/O%27Reilly", "회원", "목록·조회", 1);
    check(!category(FeaturePresentation.groups(summaries, sources), "apostrophe").equals("inputs"), "a legitimate encoded apostrophe is not treated as an input probe");
    add(summaries, sources, "badEncoding", "/api/reports/%ZZ", "reports", "목록·조회", 1);
    check(entry(FeaturePresentation.groups(summaries, sources), "badEncoding").id().equals("badEncoding"), "malformed imported encoding cannot break presentation");
    add(summaries, sources, "namedOperation", "/api/action", "action", "export | archive", 1);
    check(entry(FeaturePresentation.groups(summaries, sources), "namedOperation").label().equals("action export | archive"), "named body actions retain their full meaningful operation without exposing the route");
    noMutation();
    System.out.println("FeaturePresentationTest: " + checks + " checks passed");
  }

  private static void add(List<Mapper.FeatureSummary> summaries, Map<String, FeaturePresentation.Source> sources,
      String id, String path, String entity, String operation, int packets) {
    summaries.add(auto(id, path, entity, operation, packets));
    sources.put(id, new FeaturePresentation.Source(ORIGIN + "|GET " + path + " | " + operation, false));
  }

  private static void noMutation() throws Exception {
    try (Mapper mapper = new Mapper()) {
      byte[] request = "GET /api/orders/72 HTTP/1.1\r\nHost: presentation.test\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
      byte[] response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"id\":72}".getBytes(StandardCharsets.US_ASCII);
      var packet = mapper.accept(new Mapper.Input(1, "PROXY", "GET", ORIGIN + "/api/orders/72", 200,
          request, response, new byte[0], "{\"id\":72}".getBytes(StandardCharsets.US_ASCII), Map.of(), Map.of("content-type", "application/json")));
      mapper.edit(packet.primary, "주문 원문 이름", "완료", "작성한 메모");
      List<Mapper.FeatureSummary> before = mapper.featureSummaries();
      String primary = packet.primary;
      Map<String, String> links = Map.copyOf(packet.links);
      Map<String, FeaturePresentation.Source> sources = new HashMap<>();
      mapper.featureList().forEach(f -> sources.put(f.id, new FeaturePresentation.Source(f.key, f.renamed)));
      FeaturePresentation.groups(before, sources);
      check(mapper.featureSummaries().equals(before) && packet.primary.equals(primary) && packet.links.equals(links), "presentation does not mutate feature names, states, IDs, assignments or links");
      byte[][] raw = mapper.raw(packet.id);
      check(Arrays.equals(raw[0], request) && Arrays.equals(raw[1], response), "presentation preserves complete original request and response bytes");
      check(mapper.features.get(primary).note.equals("작성한 메모"), "presentation preserves authored feature notes");
    }
  }
}
