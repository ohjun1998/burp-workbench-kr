package kr.featuremapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** A read-only projection of feature metadata. Mapping keys and stored labels stay unchanged. */
final class FeaturePresentation {
  record Source(String key, boolean renamed) {}

  record Entry(
      String id, String label, String state, int packets, String originalName, String origin) {}

  record Group(String key, String label, boolean initiallyExpanded, List<Entry> entries) {}

  private record Category(String key, String label, boolean expanded) {}

  private record Signature(String path, String operation, String browserLabel, boolean common) {}

  private record Draft(Mapper.FeatureSummary summary, Source source, Category category, String label) {}

  private static final Category BUSINESS = new Category("business", "핵심 업무", true);
  private static final Category ACCOUNT = new Category("account", "계정·인증", true);
  private static final Category PAGES = new Category("pages", "화면·안내", false);
  private static final Category DOCS = new Category("docs", "API·문서 탐색", false);
  private static final Category OPS = new Category("ops", "환경·운영 탐색", false);
  private static final Category INPUTS = new Category("inputs", "입력 변형 관측", false);
  private static final Category CONTROL = new Category("control", "대조 경로 관측", false);
  private static final Category COMMON = new Category("common", "공통 요청", false);
  private static final Category OTHER = new Category("other", "기타 관측", false);
  private static final List<Category> CATEGORIES =
      List.of(BUSINESS, ACCOUNT, PAGES, DOCS, OPS, INPUTS, CONTROL, COMMON, OTHER);
  private static final Set<String> DEFAULT_OPERATIONS =
      Set.of("목록·조회", "상세 조회", "생성", "수정", "삭제", "업로드", "로그인", "로그아웃");
  private static final Set<String> ACCOUNT_SEGMENTS =
      Set.of("auth", "login", "logout", "signin", "signout", "signup", "register", "registration",
          "password", "users", "user", "members", "member", "profile", "session");
  private static final Set<String> BUSINESS_SEGMENTS =
      Set.of("dashboard", "balance", "check_balance", "account_balance", "admin", "orders", "order",
          "payments", "payment", "transfer", "transfers", "posts", "post", "boards", "board",
          "articles", "files", "file", "attachments", "upload", "products", "product", "cart");
  private static final Set<String> DOC_SEGMENTS =
      Set.of("docs", "apidocs", "api-docs", "swagger", "swagger-ui", "swagger-ui.html", "redoc",
          "openapi.json", "openapi.yaml", "openapi.yml", "swagger.json", "swagger.yaml", "swagger.yml");
  private static final Set<String> OPS_SEGMENTS =
      Set.of(".env", ".git", ".well-known", "health", "healthz", "heartbeat", "metrics", "status",
          "actuator", "robots.txt", "sitemap.xml", "config", "configuration");
  private static final Set<String> PAGE_SEGMENTS =
      Set.of("careers", "blog", "privacy", "terms", "compliance", "about", "contact", "help", "faq");
  private static final Map<String, String> ENTITIES = Map.ofEntries(
      Map.entry("users", "회원"), Map.entry("user", "회원"), Map.entry("members", "회원"),
      Map.entry("member", "회원"), Map.entry("profile", "프로필"), Map.entry("orders", "주문"),
      Map.entry("order", "주문"), Map.entry("payments", "결제"), Map.entry("payment", "결제"),
      Map.entry("transfer", "이체"), Map.entry("transfers", "이체"), Map.entry("posts", "게시글"),
      Map.entry("post", "게시글"), Map.entry("boards", "게시판"), Map.entry("board", "게시판"),
      Map.entry("articles", "게시글"), Map.entry("files", "파일"), Map.entry("file", "파일"),
      Map.entry("attachments", "첨부파일"), Map.entry("upload", "파일"), Map.entry("products", "상품"),
      Map.entry("product", "상품"), Map.entry("cart", "장바구니"));
  private static final Pattern INPUT_SHAPE = Pattern.compile(
      "(?i)(?:^|/)['\"](?:$|/)|['\"]\\s*(?:and\\b|or\\b|union\\b|--|#)|"
          + "(?:^|/)\\.\\.(?:/|$)|<\\s*(?:script|svg|img)\\b|\\bunion\\s+(?:all\\s+)?select\\b");

  private FeaturePresentation() {}

  static List<Group> groups(List<Mapper.FeatureSummary> summaries, Map<String, Source> sources) {
    List<Draft> drafts = new ArrayList<>();
    Map<String, Integer> duplicates = new HashMap<>();
    for (Mapper.FeatureSummary summary : summaries) {
      Source source = sources.getOrDefault(summary.id(), new Source("", false));
      Signature signature = signature(summary, source);
      Category category = category(summary, signature);
      String label = label(summary, source, signature);
      Draft draft = new Draft(summary, source, category, label);
      drafts.add(draft);
      duplicates.merge(category.key() + "\n" + label, 1, Integer::sum);
    }
    Map<String, List<Entry>> byCategory = new LinkedHashMap<>();
    for (Draft draft : drafts) {
      Mapper.FeatureSummary summary = draft.summary();
      String label = draft.label();
      if (!draft.source().renamed()
          && duplicates.getOrDefault(draft.category().key() + "\n" + label, 0) > 1) {
        // A stable suffix avoids renumbering existing rows as new observations arrive.
        label += " · " + Signals.digest(summary.origin() + "|" + draft.source().key() + "|" + summary.id())
            .substring(0, 6);
      }
      byCategory.computeIfAbsent(draft.category().key(), ignored -> new ArrayList<>())
          .add(new Entry(summary.id(), label, summary.state(), summary.packets(), summary.name(), summary.origin()));
    }
    List<Group> result = new ArrayList<>();
    for (Category category : CATEGORIES) {
      List<Entry> entries = byCategory.get(category.key());
      if (entries == null) continue;
      // Summary insertion order is retained within each group, including authored labels.
      result.add(new Group(category.key(), category.label(), category.expanded(), List.copyOf(entries)));
    }
    return List.copyOf(result);
  }

  private static Signature signature(Mapper.FeatureSummary summary, Source source) {
    String key = source.key();
    String prefix = summary.origin() + "|";
    String tail = key.startsWith(prefix) ? key.substring(prefix.length()) : "";
    if (tail.equals("공통 요청") || summary.entity().equals("공통 요청"))
      return new Signature("", "", "", true);
    if (tail.startsWith("browser|")) {
      int separator = tail.indexOf('|', "browser|".length());
      if (separator >= 0)
        return new Signature(tail.substring("browser|".length(), separator), "",
            tail.substring(separator + 1), false);
    }
    int operation = tail.indexOf(" | ");
    int method = tail.indexOf(' ');
    if (method >= 1 && operation > method)
      return new Signature(tail.substring(method + 1, operation), tail.substring(operation + 3), "", false);
    return new Signature("", "", "", false);
  }

  private static Category category(Mapper.FeatureSummary summary, Signature signature) {
    String path = decoded(signature.path()).toLowerCase(Locale.ROOT);
    Set<String> segments = segments(path);
    if (signature.common()) return COMMON;
    if (segments.stream().anyMatch(s -> s.startsWith("wb-missing-") || s.startsWith("wb-bank-missing-")))
      return CONTROL;
    if (INPUT_SHAPE.matcher(path).find()) return INPUTS;
    if (contains(segments, DOC_SEGMENTS)) return DOCS;
    if (contains(segments, OPS_SEGMENTS)) return OPS;
    if (contains(segments, ACCOUNT_SEGMENTS)) return ACCOUNT;
    if (contains(segments, BUSINESS_SEGMENTS) || segments.contains("graphql")) return BUSINESS;
    if (!signature.browserLabel().isEmpty() || path.equals("/") || contains(segments, PAGE_SEGMENTS)) return PAGES;
    if (path.matches("/api(?:/v[0-9]+)?/?")) return OPS;
    return OTHER;
  }

  private static String label(Mapper.FeatureSummary summary, Source source, Signature signature) {
    String original = summary.name();
    if (source.renamed()) return original;
    if (signature.common()) return "공통 요청";
    if (!signature.browserLabel().isEmpty()) {
      String generated = Signals.clean(signature.browserLabel() + " · " + signature.path(), 240);
      return original.equals(generated) ? signature.browserLabel() : original;
    }
    String generated = Signals.clean(summary.entity() + " / " + signature.operation() + " · " + signature.path(), 240);
    if (signature.path().isEmpty() || !original.equals(generated)) {
      // OpenAPI or browser titles are already meaningful. Only translate the approved exact captions.
      return switch (original.toLowerCase(Locale.ROOT)) {
        case "check account balance" -> "잔액 조회";
        case "admin panel" -> "관리자 화면";
        default -> original;
      };
    }
    String path = decoded(signature.path()).toLowerCase(Locale.ROOT);
    Set<String> segments = segments(path);
    if (segments.stream().anyMatch(s -> s.startsWith("wb-missing-") || s.startsWith("wb-bank-missing-")))
      return "대조 경로";
    if (INPUT_SHAPE.matcher(path).find())
      return (segments.contains("check_balance") || segments.contains("balance") ? "잔액 " : "") + "입력 변형";
    if (path.equals("/")) return "홈 화면";
    if (segments.contains("oauth2-redirect.html")) return "API 인증 연결 화면";
    if (segments.contains("openapi.json") || segments.contains("openapi.yaml") || segments.contains("openapi.yml"))
      return segments.contains("static") ? "정적 API 명세" : "OpenAPI 명세 경로";
    if (segments.contains("swagger.json") || segments.contains("swagger.yaml") || segments.contains("swagger.yml"))
      return "Swagger 명세 경로";
    if (contains(segments, Set.of("swagger", "swagger-ui", "swagger-ui.html"))) return "Swagger 문서 경로";
    if (segments.contains("redoc")) return "ReDoc 문서 경로";
    if (contains(segments, DOC_SEGMENTS)) return "API 문서 경로";
    if (contains(segments, Set.of("health", "healthz", "heartbeat"))) return "연결 확인";
    if (segments.contains(".env")) return "환경 설정 파일";
    if (segments.contains(".git")) return "버전 관리 경로 관측";
    if (segments.contains("robots.txt")) return "검색 로봇 규칙 경로";
    if (segments.contains("sitemap.xml")) return "사이트맵 경로";
    if (segments.contains("security.txt")) return "보안 연락처 경로";
    if (segments.contains("metrics")) return "지표 경로 관측";
    if (segments.contains("status")) return "상태 경로 관측";
    if (contains(segments, Set.of("config", "configuration", "actuator"))) return "설정 경로 관측";
    if (path.matches("/api(?:/v[0-9]+)?/?")) return "API 진입점 관측";
    if (segments.contains("dashboard")) return "대시보드";
    if (signature.operation().equals("로그인") || contains(segments, Set.of("login", "signin")))
      return (segments.contains("merchant") ? "가맹점 " : "") + "로그인";
    if (signature.operation().equals("로그아웃") || contains(segments, Set.of("logout", "signout"))) return "로그아웃";
    if (contains(segments, Set.of("register", "registration", "signup")))
      return (segments.contains("merchant") ? "가맹점 " : "회원") + "가입 화면";
    if (contains(segments, Set.of("check_balance", "balance", "account_balance")))
      return "잔액 " + operationLabel(signature.operation());
    if (segments.contains("admin")) return "관리자 경로 관측";
    if (segments.contains("ai")) return "AI 경로 관측";
    for (String page : List.of("careers", "blog", "privacy", "terms", "compliance", "about", "contact", "help", "faq")) {
      if (segments.contains(page)) return switch (page) {
        case "careers" -> "채용 안내";
        case "blog" -> "블로그";
        case "privacy" -> "개인정보 안내";
        case "terms" -> "이용약관";
        case "compliance" -> "준법 안내";
        case "about" -> "서비스 소개";
        case "contact" -> "연락처 안내";
        case "help" -> "도움말";
        default -> "자주 묻는 질문";
      };
    }
    if (segments.contains("graphql")) return "GraphQL · " + operationLabel(signature.operation());
    String entity = summary.entity();
    String translated = ENTITIES.getOrDefault(entity.toLowerCase(Locale.ROOT), entity);
    if (translated.isBlank() || translated.equals("서비스") || translated.length() > 32
        || translated.matches(".*[%/\\\\?&=#<>].*") || translated.matches("[0-9a-fA-F-]{24,}"))
      translated = "기능";
    return translated + " " + operationLabel(signature.operation());
  }

  private static String operationLabel(String operation) {
    return operation.equals("목록·조회") || operation.equals("상세 조회") ? "조회"
        : DEFAULT_OPERATIONS.contains(operation) ? operation : operation.isBlank() ? "관측" : operation;
  }

  private static Set<String> segments(String path) {
    return Set.copyOf(List.of(path.split("/")));
  }

  private static boolean contains(Set<String> values, Set<String> candidates) {
    return values.stream().anyMatch(candidates::contains);
  }

  private static String decoded(String path) {
    try {
      // '+' in a path segment is literal; URLDecoder otherwise applies form semantics.
      return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException invalidEncoding) {
      return path;
    }
  }
}
