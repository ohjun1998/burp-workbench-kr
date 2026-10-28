package kr.workbench;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, local response analysis. No target or credential validation requests are made here. */
final class ScopeInspectionAnalysis {
  static final int BODY_LIMIT = 2 * 1024 * 1024, HEADER_LIMIT = 64 * 1024, FINDING_LIMIT = 500;
  record Result(List<ScopeInspection.Finding> findings, List<ScopeInspection.Candidate> candidates,
                boolean complete, String note) {
    Result { findings = List.copyOf(findings); candidates = List.copyOf(candidates); }
  }
  private record Db(String name, Pattern pattern) {}
  private static final List<Db> DATABASES = List.of(
      db("Oracle", "ORA-[0-9]{5}|oracle\\.jdbc\\.[A-Za-z.]+|jdbc:oracle:"),
      db("PostgreSQL", "org\\.postgresql\\.[A-Za-z.]+|PSQLException|jdbc:postgresql:|postgres(?:ql)?://"),
      db("MySQL / MariaDB", "com\\.(?:mysql|mariadb)\\.[A-Za-z.]+|jdbc:(?:mysql|mariadb):|You have an error in your SQL syntax"),
      db("SQL Server", "com\\.microsoft\\.sqlserver\\.[A-Za-z.]+|jdbc:sqlserver:|System\\.Data\\.SqlClient\\.[A-Za-z.]+"),
      db("SQLite", "SQLITE_(?:ERROR|MISUSE|CONSTRAINT)|org\\.sqlite\\.[A-Za-z.]+|jdbc:sqlite:"),
      db("H2", "org\\.h2\\.jdbc\\.[A-Za-z.]+|jdbc:h2:"),
      db("MongoDB", "com\\.mongodb\\.[A-Za-z.]+|Mongo(?:Server|Query|Write)Exception|mongodb(?:\\+srv)?://"),
      db("Redis", "redis\\.clients\\.jedis\\.[A-Za-z.]+|redis(?:s)?://|WRONGTYPE Operation against a key"));
  private static final Pattern LINK = Pattern.compile("(?is)<(?:a|area)\\b[^>]{0,4096}?\\bhref\\s*=\\s*([\"'])([^\"'<>]{1,2048})\\1[^>]{0,4096}>([^<]{0,160})");
  private static final Pattern FIELD = Pattern.compile("(?im)([A-Za-z_$][A-Za-z0-9_.$-]{0,79})[\"']?\\s{0,8}[:=]\\s{0,8}[\"']?([^\\s\"'<>;,]{1,256})");
  private static final Pattern CONNECTION = Pattern.compile("(?i)(?:jdbc:(?:oracle|postgresql|mysql|mariadb|sqlserver|sqlite|h2):|(?:mongodb(?:\\+srv)?|postgres(?:ql)?|mysql|redis(?:s)?)://)[^\\s\"'<>]{1,1024}");
  private static final Pattern PRIVATE_IP = Pattern.compile("(?<![0-9.])(?:10\\.(?:[0-9]{1,3}\\.){2}[0-9]{1,3}|192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}|172\\.(?:1[6-9]|2[0-9]|3[01])\\.[0-9]{1,3}\\.[0-9]{1,3})(?![0-9.])");
  private static final Pattern HOST = Pattern.compile("(?i)\\b[a-z0-9][a-z0-9.-]{0,120}\\.(?:internal|local|corp|lan)\\b");
  private static final Pattern FILE_PATH = Pattern.compile("(?i)(?:[A-Z]:\\\\(?:[^\\s\"'<>]{1,160})|/(?:var|etc|opt|home|usr|srv)/(?:[^\\s\"'<>]{1,160}))");
  private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]{1,64}@[A-Z0-9.-]{1,128}\\.[A-Z]{2,20}\\b");
  private static final Pattern PROVIDER = Pattern.compile("(?:AKIA|ASIA)[A-Z0-9]{16}|AIza[0-9A-Za-z_-]{35}|(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{40,255})|xox[baprs]-[0-9A-Za-z-]{10,200}|sk_(?:live|test)_[0-9A-Za-z]{16,200}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
  private static final Pattern CREDENTIAL_VALUE = Pattern.compile("(?im)([A-Za-z_$][A-Za-z0-9_.$-]{0,79})[\"']?\\s{0,8}[:=]\\s{0,8}(?:\"((?:\\\\.|[^\"\\r\\n]){1,16384}+)\"|'((?:\\\\.|[^'\\r\\n]){1,16384}+)'|([^\\s\"'<>;,]{1,16384}+))");
  private ScopeInspectionAnalysis() {}
  private static Db db(String name, String pattern) { return new Db(name, Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)); }

  static Result analyze(String url, int status, String headers, String body, String account, String originalId) {
    String all = Objects.requireNonNullElse(body, ""), head = Objects.requireNonNullElse(headers, "");
    String text = all.substring(0, Math.min(all.length(), BODY_LIMIT));
    boolean complete = all.length() <= BODY_LIMIT && head.length() <= HEADER_LIMIT;
    head = head.substring(0, Math.min(head.length(), HEADER_LIMIT));
    String safe = safeUrl(url), who = display(account, 160), original = display(originalId, 160);
    if (safe.isEmpty()) return new Result(List.of(), List.of(), false, "URL 형식 확인 실패 · 분석하지 않음");
    List<ScopeInspection.Finding> found = new ArrayList<>();
    Map<String, ScopeInspection.Candidate> candidates = new LinkedHashMap<>();
    Set<String> sensitiveValues = new LinkedHashSet<>();
    String type = EnvironmentResponse.header(head, "Content-Type");

    EnvironmentCatalog catalog = new EnvironmentCatalog();
    EnvironmentCatalog.Site site = catalog.observe(url, head, text);
    for (var detection : site.detections.values()) {
      String basis = detection.confidence().startsWith("헤더") ? "헤더 표식 관측" : "본문·리소스 표식 관측";
      add(found, "ENVIRONMENT", detection.category() + " · " + detection.name(), safe, "GET", who,
          "추정", "정보", "검토 필요", basis + " · 프록시/오류 페이지 가능 · 실제 구성 확정 아님", original, 0, -1,
          hash(detection.category() + "|" + detection.name()));
    }
    String prefix = "";
    for (var candidate : catalog.candidates(site, prefix, true, 160))
      candidate(candidates, candidate.url(), "GET", "환경 경로", candidate.source(), true);
    environmentExtras(head, text, safe, who, original, found);

    int databases = 0;
    for (Db db : DATABASES) {
      Matcher evidence = db.pattern().matcher(text);
      if (evidence.find()) {
        databases++;
        add(found, "DATABASE", db.name(), safe, "GET", who, "추정", "정보", "검토 필요",
            "관측 구문: " + display(evidence.group(), 100) + " · DB 고유 오류/드라이버/연결 형식 단서 · 제품/버전·취약점 확정 아님", original,
            line(text, evidence.start()), evidence.start(), hash(db.name()));
      }
    }
    if (databases > 1) add(found, "DATABASE", "DB 단서 복수 관측", safe, "GET", who, "미확인", "정보", "검토 필요",
        "서로 다른 DB 제품 표식 " + databases + "개 · 문서/중계 오류/여러 저장소 가능 · 실제 구성 수동 확인", original, 0, -1, hash("db-conflict"));

    String jsText = text;
    int marker = -1;
    for (int count = 0; count < 17; count++) {
      marker = text.indexOf("-----BEGIN ", marker + 1);
      if (marker < 0) break;
      if (count == 16) { jsText = text.substring(0, marker); complete = false; }
    }
    JsAnalysis.Result js = jsText.length()>JsAnalysis.SOURCE_LIMIT
        ? JsBatchAnalysis.analyze(jsText,url,type,url) : JsAnalysis.analyze(jsText, url, type);
    complete &= !js.truncated();
    for (var endpoint : js.endpoints()) {
      boolean method = Set.of("GET", "HEAD").contains(endpoint.method());
      boolean resolved = endpoint.complete() && !unresolved(endpoint.url()) && endpoint.parameters().isBlank();
      String relative=relativeApi(endpoint.url());
      if (!method&&!scriptSource(endpoint,text)) {
        // A quoted URL alone does not establish GET when the parser found another or unknown method.
        var generic = candidates.get("GET " + endpoint.url());
        if (generic != null && generic.source().equals("환경 경로") && generic.label().equals("실제 응답 참조"))
          candidates.remove("GET " + endpoint.url());
      }
      candidate(candidates, endpoint.url(), endpoint.method(), "JS API", endpoint.kind()+(relative.isEmpty()?"":" · 서버/기본 경로 미확정"), relative.isEmpty()&&method && resolved);
      add(found, "API", "JS/API 경로 후보", relative.isEmpty()?safeUrl(endpoint.url()):safe, endpoint.method(), who,
          endpoint.complete() ? "추정" : "미확인", "정보", method && resolved ? "미관측" : "필수값 필요",
          (relative.isEmpty()?"":"선언 경로: "+display(relative,300)+" · 서버/기본 경로 미확정 · ")+display(endpoint.kind(), 120) + " · 메서드 " + display(endpoint.method(), 16) + " · 원본 위치에서 확인 · 실제 도달 여부 미검증",
          original, endpoint.line(), endpoint.start(), hash(endpoint.method() + " " + (relative.isEmpty()?safeUrl(endpoint.url()):relative)));
    }
    for (var secret : js.secrets()) {
      boolean example = secret.confidence().contains("예제");
      boolean secretProvider = Set.of("GitHub token", "Slack token", "Stripe secret key", "Private key").contains(secret.rule());
      boolean publicId = !secretProvider && secret.confidence().contains("공개 식별자");
      String state = example ? "예제값 가능" : publicId ? "공개 식별자 가능" : "유효성 미검증";
      add(found, "SECRET", secret.rule(), safe, "GET", who, "형식 후보", example || publicId ? "낮음" : "높음", state,
          secret.masked() + " · " + display(secret.confidence(), 240) + " · 발견한 값을 인증에 사용하거나 외부 검증하지 않음",
          original, secret.line(), secret.start(), secret.fingerprint());
    }
    complete &= namedFields(text, type, safe, who, original, found, sensitiveValues);
    complete &= sensitive(text, CONNECTION, "DB 연결 문자열 후보", "중간", safe, who, original, found, sensitiveValues);
    complete &= sensitive(text, PRIVATE_IP, "사설 IP 주소", "낮음", safe, who, original, found, sensitiveValues);
    complete &= sensitive(text, HOST, "내부 호스트 이름", "낮음", safe, who, original, found, sensitiveValues);
    complete &= sensitive(text, FILE_PATH, "서버 내부 경로", "낮음", safe, who, original, found, sensitiveValues);
    complete &= sensitive(text, EMAIL, "이메일 형식 정보", "낮음", safe, who, original, found, sensitiveValues);

    LinkScan linkScan = links(text);
    complete &= linkScan.complete();
    for (var link : linkScan.links()) {
      try {
        String reference = link.reference().replace("&amp;", "&");
        URI target = URI.create(url).resolve(reference);
        if (!Set.of("http", "https").contains(target.getScheme()) || target.getUserInfo() != null) continue;
        boolean route = target.getRawFragment() != null;
        String label = display(link.label().strip(), 120);
        candidate(candidates, target.toString(), "GET", route ? "메뉴 라우트" : "관측 링크", label, !route);
        if (!label.isEmpty() || route) add(found, "MENU", label.isEmpty() ? "동적 메뉴 라우트" : label,
            safeUrl(target.toString()), "GET", who, "관측", "정보", route ? "브라우저 확인 필요" : "미관측",
            route ? "HTML 메뉴·해시 라우트 관측 · HTTP 조회만으로 화면 실행 확인 불가" : "HTML 메뉴 링크 관측 · 연결된 요청 확인 필요",
            original, line(text, link.offset()), link.offset(), hash("menu|" + safeUrl(target.toString())));
      } catch (IllegalArgumentException ignored) { }
    }

    Object document = null;
    try { document = MinerJson.parse(text, BODY_LIMIT, 60000); } catch (RuntimeException ignored) { }
    List<ApiInventory.Endpoint> declared = ApiInventory.parse(text, url);
    for (var endpoint : declared) {
      boolean required = requiredInput(document, endpoint);
      boolean executable = Set.of("GET", "HEAD").contains(endpoint.method()) && !endpoint.template() && !required;
      candidate(candidates, endpoint.url(), endpoint.method(), "API 문서", "OpenAPI 선언", executable);
      add(found, "API", "API 문서 선언", safeUrl(endpoint.url()), endpoint.method(), who, "관측", "정보",
          executable ? "문서 선언" : "필수값 필요", "OpenAPI 메서드·경로 선언 · " + (executable ? "조회 후보" : "변수/필수 입력 또는 직접 실행할 메서드 확인")
              + " · 인증·실제 구현 미검증", original, 0, -1, hash(endpoint.method() + " " + safeUrl(endpoint.url())));
    }
    if (declared.size() >= 500) complete = false;

    var passive = PassiveFindings.analyzeDetailed(url, status, head, text);
    complete &= passive.complete();
    for (var observation : passive.findings()) add(found, "RESPONSE", observation.rule(), safe, "GET", who,
        "관측", observation.rule().contains("비밀") ? "중간" : "정보", "검토 필요",
        observation.location() + " · " + observation.evidence(), original, 0, offset(observation.location()),
        hash(observation.rule() + "|" + observation.location()));
    cors(head, safe, who, original, found);
    if (found.size() >= FINDING_LIMIT || candidates.size() >= 400) complete = false;
    String note = "본문 " + text.length() + " / " + all.length() + "자 · 환경 표식은 앞 64 KiB · " + js.status() + " · " + passive.note();
    if (!complete) add(found, "COVERAGE", "부분 분석·미검사 구간", safe, "GET", who, "관측", "정보", "미검사",
        "입력·시간·구조·결과 한도에 도달한 분석기가 있음 · 미발견을 안전으로 해석하지 않음", original, 0, -1, hash("partial"));
    List<ScopeInspection.Finding> masked = new ArrayList<>();
    Set<String> duplicate = new HashSet<>();
    for (var value : found) {
      var clean = new ScopeInspection.Finding(value.category(), clean(value.title(), sensitiveValues), maskedKnownUrl(value.url(), sensitiveValues),
          value.method(), clean(value.account(), sensitiveValues), value.confidence(), value.severity(), value.state(),
          clean(value.detail(), sensitiveValues), value.originalId(), value.line(), value.offset(), value.fingerprint());
      String key = clean.category() + "|" + (clean.category().equals("SECRET")?"":clean.title()) + "|" + clean.url() + "|" + clean.account() + "|" + clean.fingerprint();
      if (duplicate.add(key)) masked.add(clean);
    }
    List<ScopeInspection.Candidate> cleanCandidates = candidates.values().stream().map(c -> {
      boolean sensitiveInput = containsKnownValue(c.url(), sensitiveValues);
      return new ScopeInspection.Candidate(sensitiveInput ? maskedKnownUrl(c.url(), sensitiveValues) : c.url(), c.method(),
          clean(c.source(), sensitiveValues), clean(c.label(), sensitiveValues) + (sensitiveInput ? " · 비밀값 포함 입력 가림·직접 검토" : ""), c.executable() && !sensitiveInput);
    }).toList();
    return new Result(masked, cleanCandidates, complete, display(note, 1200));
  }


  /** Raw values exist only during explicit evidence handoff; no DTO/history retains this set. */
  static Set<String> redactionValues(String headers, String body) {
    String head = Objects.requireNonNullElse(headers, ""), text = Objects.requireNonNullElse(body, "");
    if (head.length() > HEADER_LIMIT || text.length() > BODY_LIMIT)
      throw new IllegalArgumentException("민감정보 가림 분석 한도 초과 · 원본을 직접 검토하거나 출력 본문 범위를 줄이세요.");
    LinkedHashSet<String> values = new LinkedHashSet<>();
    for (Pattern pattern : List.of(PROVIDER, CONNECTION)) {
      Matcher match = pattern.matcher(head + "\n" + text);
      while (match.find()) addRedaction(values, match.group());
    }
    String keySource = head + "\n" + text;
    int pemStart = 0, pemCount = 0;
    while ((pemStart = keySource.indexOf("-----BEGIN ", pemStart)) >= 0) {
      if (++pemCount > 256) throw new IllegalArgumentException("PEM 가림 후보 한도 초과 · 출력 본문을 직접 검토하세요.");
      int headerEnd = keySource.indexOf("-----", pemStart + 11);
      if (headerEnd < 0 || headerEnd - pemStart > 100) { pemStart += 11; continue; }
      String header = keySource.substring(pemStart + 11, headerEnd);
      if (!header.matches("(?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY")) { pemStart = headerEnd + 5; continue; }
      String ending = "-----END " + header + "-----";
      int pemEnd = keySource.indexOf(ending, headerEnd + 5);
      if (pemEnd >= 0 && pemEnd - pemStart <= 16384) addRedaction(values, keySource.substring(pemStart, pemEnd + ending.length()));
      else throw new IllegalArgumentException("PEM 원문 가림 범위를 확정하지 못했습니다. 출력 본문을 직접 검토하세요.");
      pemStart = pemEnd + ending.length();
    }
    Matcher fields = CREDENTIAL_VALUE.matcher(text); int scanned = 0;
    while (fields.find()) {
      if (++scanned > 20000) throw new IllegalArgumentException("가림 필드 검사 한도 초과 · 출력 본문을 직접 검토하세요.");
      String name = fields.group(1).replaceAll("[_-]", "").toLowerCase(Locale.ROOT);
      if (!SecretCandidates.named(fields.group(1)) && !name.matches(".*(?:publickey|publishable|clientid|sitekey|measurementid).*")
          && !name.matches(".*(?:databaseurl|dburl|connectionstring).*") ) continue;
      String value = fields.group(2) != null ? fields.group(2) : fields.group(3) != null ? fields.group(3) : fields.group(4);
      if (fields.group(4) != null && (value.startsWith("{") || value.startsWith("[") || value.matches("(?i)true|false|null|undefined"))) continue;
      if (value.length() < 4 && SecretCandidates.named(fields.group(1)))
        throw new IllegalArgumentException("짧은 인증 필드 값은 전역 가림으로 안전하게 처리할 수 없습니다. 해당 본문을 직접 가리거나 제외하세요.");
      addRedaction(values, value);
    }
    for (String line : head.split("\\r?\\n")) {
      int colon = line.indexOf(':'); if (colon < 0) continue;
      String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT), value = line.substring(colon + 1).strip();
      if (Set.of("authorization", "proxy-authorization", "x-api-key", "x-auth-token").contains(name)) {
        addRedaction(values, value);
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) addRedaction(values, value.substring(7).strip());
      } else if (Set.of("cookie", "set-cookie").contains(name)) {
        for (String pair : value.split(";")) {
          int equals = pair.indexOf('='); if (equals < 0) continue;
          String cookie = pair.substring(equals + 1).strip();
          if (cookie.length() >= 4 && !pair.substring(0, equals).strip().matches("(?i)path|domain|max-age|expires|samesite")) addRedaction(values, cookie);
          if (name.equals("set-cookie")) break;
        }
      }
    }
    List<String> immutable = List.copyOf(values);
    return new java.util.AbstractSet<>() {
      public java.util.Iterator<String> iterator() { return immutable.iterator(); }
      public int size() { return immutable.size(); }
      @Override public String toString() { return "[가림 값 " + immutable.size() + "개 · 원문 표시 안 함]"; }
    };
  }
  private static void addRedaction(Set<String> values, String value) {
    if (value == null || value.isEmpty() || value.equals("[REDACTED]") || value.equals("[가림]")) return;
    values.add(value);
    int bytes = 0; for (String current : values) bytes += current.getBytes(StandardCharsets.UTF_8).length;
    if (values.size() > 256 || bytes > 256 * 1024)
      throw new IllegalArgumentException("가림 값 개수/용량 한도 초과 · 원본을 직접 검토하고 출력 범위를 줄이세요.");
  }

  private record Link(String reference, String label, int offset) {}
  private record LinkScan(List<Link> links, boolean complete) {}
  private static LinkScan links(String text) {
    List<Link> links = new ArrayList<>(); int cursor = 0, tags = 0;
    while (cursor < text.length()) {
      int start = text.indexOf('<', cursor); if (start < 0) break;
      int end = text.indexOf('>', start + 1);
      if (end < 0) return new LinkScan(links, false);
      cursor = end + 1;
      if (++tags > 5000) return new LinkScan(links, false);
      if (end - start > 4096) continue;
      int labelEnd = text.indexOf('<', cursor); if (labelEnd < 0) labelEnd = text.length();
      String fragment = text.substring(start, Math.min(labelEnd, Math.min(text.length(), end + 161)));
      Matcher matcher = LINK.matcher(fragment);
      if (matcher.lookingAt()) {
        if (links.size() >= 200) return new LinkScan(links, false);
        links.add(new Link(matcher.group(2), matcher.group(3), start));
      }
    }
    return new LinkScan(links, true);
  }

  private static void environmentExtras(String headers, String text, String url, String account, String original, List<ScopeInspection.Finding> out) {
    String[][] observations = {{"CDN / WAF", "Cloudflare", "(?im)^(?:cf-ray|cf-cache-status):|^server:.*cloudflare"},
        {"CDN / 프록시", "CloudFront", "(?im)^(?:x-amz-cf-id|x-amz-cf-pop):"},
        {"프록시", "Via / X-Cache", "(?im)^(?:via|x-cache|x-served-by):"}};
    for (String[] observation : observations) if (Pattern.compile(observation[2]).matcher(headers).find())
      add(out, "ENVIRONMENT", observation[0] + " · " + observation[1], url, "GET", account, "추정", "정보", "검토 필요",
          "중계 계층 헤더 표식 · 원본 서버/WAS 구성과 구분", original, 0, -1, hash(observation[1]));
    versions(headers, true, url, account, original, out);
    versions(text.substring(0, Math.min(text.length(), 256 * 1024)), false, url, account, original, out);
    if (text.contains("react-dom") || text.contains("data-reactroot")) add(out, "ENVIRONMENT", "프런트엔드 · React", url,
        "GET", account, "추정", "정보", "검토 필요", "리소스/DOM 표식 · 실행·버전 미확인", original, 0, -1, hash("react"));
  }

  private static void versions(String source, boolean header, String url, String account, String original, List<ScopeInspection.Finding> out) {
    String[][] products = {{"nginx", "nginx"}, {"Apache HTTPD", "Apache"}, {"Tomcat", "(?:Apache[ \t]+)?Tomcat"},
        {"Jetty", "Jetty"}, {"PHP", "PHP"}, {"ASP.NET", "ASP\\.NET"}, {"Spring Boot", "Spring[ \t]+Boot"},
        {"PostgreSQL", "PostgreSQL"}, {"MySQL", "MySQL"}, {"MariaDB", "MariaDB"}, {"Redis", "Redis"}};
    for (String[] product : products) {
      Matcher version = Pattern.compile("(?i)\\b" + product[1] + "[/ \t(]{1,4}([0-9]{1,4}(?:\\.[0-9]{1,6}){1,3})(?![0-9.])").matcher(source);
      int matches = 0;
      while (version.find() && matches++ < 3) {
        String category = Set.of("PostgreSQL", "MySQL", "MariaDB", "Redis").contains(product[0]) ? "DATABASE" : "ENVIRONMENT";
        String where = "본문";
        if (header) {
          int lineStart = source.lastIndexOf('\n', version.start()) + 1;
          int colon = source.indexOf(':', lineStart);
          if (colon < lineStart || colon > version.start()) continue;
          String name = source.substring(lineStart, colon).strip();
          if (!Set.of("server", "x-powered-by").contains(name.toLowerCase(Locale.ROOT))) continue;
          where = name;
        }
        add(out, category, product[0] + " 버전 표식 " + version.group(1), url, "GET", account, "추정", "정보", "검토 필요",
            where + "의 제품·숫자 버전 표식 관측 · 실제 배포 제품/버전·취약점 확정 아님", original,
            header ? 0 : line(source, version.start()), header ? -1 : version.start(), hash(product[0] + "|" + version.group(1)));
      }
    }
    if (header) for (String name : List.of("X-AspNet-Version", "X-AspNetMvc-Version")) {
      String value = EnvironmentResponse.header(source, name).strip();
      if (value.matches("[0-9]{1,4}(?:\\.[0-9]{1,6}){1,3}")) add(out, "ENVIRONMENT", "ASP.NET 버전 표식 " + value, url, "GET", account,
          "추정", "정보", "검토 필요", name + " 숫자 버전 헤더 관측 · 실제 구성 확인 필요", original, 0, -1, hash(name + "|" + value));
    }
  }

  private static boolean namedFields(String body, String type, String url, String account, String original, List<ScopeInspection.Finding> out, Set<String> raw) {
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    TextCandidates.Scan scan=TextCandidates.scan(body,type,url,deadline);
    if(!scan.dataOnly()&&scan.values().isEmpty())scan=TextCandidates.fallback(body,deadline);
    int count=0;
    for(var item:scan.values()) {
      if(++count>2000||out.size()>=FINDING_LIMIT)break;
      if(!item.literal())continue;
      String key=item.key(),value=item.text();
      boolean privateProvider=value.matches("(?s)(?:gh[pousr]_|github_pat_|xox[baprs]-|sk_(?:live|test)_|-----BEGIN .*PRIVATE KEY-----).*");
      boolean publicId = !privateProvider&&SecretCandidates.publicIdentifier(key);
      if (!SecretCandidates.named(key) && !publicId) continue;
      if (value.length() < 8 || value.equals("[REDACTED]")) continue;
      boolean example = SecretCandidates.example(key,value);
      if (!publicId && !example && !SecretCandidates.plausible(value)) continue;
      raw.add(value);
      add(out, "SECRET", publicId ? "공개 식별자/키 형식" : "이름 문맥 기반 키 후보", url, "GET", account,
          publicId || example ? "미확인" : "형식 후보", publicId || example ? "낮음" : "중간",
          publicId ? "공개 식별자 가능" : example ? "예제값 가능" : "유효성 미검증",
          display(key, 100) + " = [가림 · " + value.length() + "자] · 이름 문맥으로 분류 · 실제 비밀/유효성 미검증",
          original, line(body, item.start()), item.start(), hash(value).substring(0, 16));
    }
    return !scan.limited()&&count <= 2000 && out.size() < FINDING_LIMIT;
  }

  private static boolean sensitive(String body, Pattern pattern, String title, String severity, String url, String account,
                                String original, List<ScopeInspection.Finding> out, Set<String> raw) {
    Matcher matcher = pattern.matcher(body); int count = 0;
    while (matcher.find() && count++ < 32 && out.size() < FINDING_LIMIT) {
      if (title.contains("연결")) raw.add(matcher.group());
      add(out, title.contains("연결") ? "SECRET" : "INTERNAL", title, url, "GET", account, "형식 후보", severity, "검토 필요",
          "[가림 · " + matcher.group().length() + "자] · 원본 위치에서 개인정보/배포 의도 확인", original,
          line(body, matcher.start()), matcher.start(), hash(matcher.group()).substring(0, 16));
    }
    return count <= 32 && out.size() < FINDING_LIMIT;
  }

  private static void cors(String headers, String url, String account, String original, List<ScopeInspection.Finding> out) {
    String origin = EnvironmentResponse.header(headers, "Access-Control-Allow-Origin"), credentials = EnvironmentResponse.header(headers, "Access-Control-Allow-Credentials");
    if (!origin.isBlank()) add(out, "RESPONSE", "CORS 응답 정책 관측", url, "GET", account, "관측", "정보", "검토 필요",
        "Allow-Origin " + (origin.equals("*") ? "와일드카드" : "지정 출처") + " · credentials=" + (credentials.equalsIgnoreCase("true") ? "true" : "미관측/false")
            + " · 브라우저 교차 출처 동작/실제 권한은 별도 확인", original, 0, -1, hash("cors|" + origin + "|" + credentials));
  }

  private static boolean requiredInput(Object parsed, ApiInventory.Endpoint endpoint) {
    if (!(parsed instanceof Map<?, ?> document) || !(document.get("paths") instanceof Map<?, ?> paths)) return true;
    Map<?, ?> best = null; int longest = -1;
    for (var entry : paths.entrySet()) if (entry.getKey() instanceof String path && endpoint.url().endsWith(path) && path.length() > longest && entry.getValue() instanceof Map<?, ?> row) {
      best = row; longest = path.length();
    }
    if (best == null) return true;
    best = resolve(best, document, 0);
    if (!(best.get(endpoint.method().toLowerCase(Locale.ROOT)) instanceof Map<?, ?> operation)) return true;
    boolean operationReference = operation.containsKey("$ref");
    operation = resolve(operation, document, 0);
    if (operationReference && operation.isEmpty()) return true;
    boolean bodyRequired = false;
    if (operation.get("requestBody") instanceof Map<?, ?> request) {
      Map<?, ?> resolvedRequest = resolve(request, document, 0);
      bodyRequired = request.containsKey("$ref") && resolvedRequest.isEmpty() || Boolean.TRUE.equals(resolvedRequest.get("required"));
    } else if (operation.containsKey("requestBody")) bodyRequired = true;
    return requiredParameters(best.get("parameters"), document) || requiredParameters(operation.get("parameters"), document)
        || bodyRequired;
  }
  private static boolean requiredParameters(Object value, Map<?, ?> document) {
    if (value == null) return false;
    if (!(value instanceof List<?> list)) return true;
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> row)) return true;
      Map<?, ?> parameter = resolve(row, document, 0);
      if (parameter.isEmpty() || Boolean.TRUE.equals(parameter.get("required"))) return true;
    }
    return false;
  }
  private static Map<?, ?> resolve(Map<?, ?> value, Map<?, ?> document, int depth) {
    if (!(value.get("$ref") instanceof String ref)) return value;
    if (depth >= 8 || !ref.startsWith("#/")) return Map.of();
    Object node = document;
    for (String segment : ref.substring(2).split("/")) {
      if (!(node instanceof Map<?, ?> map)) return Map.of();
      node = map.get(segment.replace("~1", "/").replace("~0", "~"));
    }
    return node instanceof Map<?, ?> map ? resolve(map, document, depth + 1) : Map.of();
  }

  static List<ScopeInspection.Finding> compareInventory(List<ScopeInspection.Finding> findings,
      List<ScopeInspection.Candidate> candidates, List<ScopeInspection.Page> pages) {
    List<ScopeInspection.Finding> declared = findings.stream().filter(f -> f.category().equals("API") && f.title().equals("API 문서 선언")).toList();
    List<ScopeInspection.Finding> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (var page : pages) {
      boolean api = page.url().matches("(?i).*(?:/api(?:/|$)|/graphql(?:/|$)).*")
          || candidates.stream().anyMatch(c -> Set.of("API 문서", "JS API").contains(c.source()) && matches(c.url(), c.method(), page.url(), page.method()));
      if (!api || !seen.add(page.account() + "|" + page.method() + "|" + safeUrl(page.url()))) continue;
      boolean documented = declared.stream().anyMatch(d -> matches(d.url(), d.method(), page.url(), page.method()));
      if (!documented) add(out, "INVENTORY", declared.isEmpty() ? "API 문서 미확인" : "문서와 대조되지 않은 관측 API", safeUrl(page.url()), page.method(), page.account(),
          "관측", "정보", "검토 필요", "요청/응답 원본은 관측됨 · 문서 누락·숨은 API·취약점 확정 아님", page.original() == null ? "" : page.original().id(), 0, -1,
          hash("undocumented|" + page.method() + "|" + safeUrl(page.url())));
    }
    for (var endpoint : declared) if (pages.stream().noneMatch(page -> matches(endpoint.url(), endpoint.method(), page.url(), page.method())))
      add(out, "INVENTORY", "문서 선언 · 실행 미관측", endpoint.url(), endpoint.method(), endpoint.account(), "관측", "정보", "미관측",
          "문서에는 있지만 이번 실행에서 요청을 확인하지 않음 · 필수값/범위/예산/메서드 확인", endpoint.originalId(), endpoint.line(), endpoint.offset(), endpoint.fingerprint());
    return List.copyOf(out);
  }
  private static boolean matches(String declared, String method, String actual, String actualMethod) {
    if (!method.equals(actualMethod)) return false;
    String safe = safeUrl(actual), template = safeUrl(declared);
    if (template.isEmpty() || safe.isEmpty()) return false;
    StringBuilder regex = new StringBuilder();
    Matcher parameter = Pattern.compile("\\{[^{}]{1,128}\\}").matcher(template); int previous = 0;
    while (parameter.find()) { regex.append(Pattern.quote(template.substring(previous, parameter.start()))).append("[^/?#]+"); previous = parameter.end(); }
    regex.append(Pattern.quote(template.substring(previous))); return safe.matches(regex.toString());
  }

  static String safeUrl(String raw) {
    if (raw == null || raw.length() > 8192) return "";
    try {
      URI uri = URI.create(raw.replace("{", "%7B").replace("}", "%7D"));
      if (uri.getUserInfo() != null || uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())) return "";
      String base = EnvironmentCatalog.origin(uri), path = Objects.requireNonNullElse(uri.getRawPath(), "/");
      if (path.isEmpty()) path = "/";
      path = path.replaceAll("(?i)(/(?:token|api[_-]?key|secret|password|authorization)/)[^/]+", "$1%5BREDACTED%5D");
      String fragment = uri.getRawFragment();
      String route = fragment != null && (fragment.startsWith("/") || fragment.startsWith("!"))
          ? "#" + fragment.split("[?&]", 2)[0] : "";
      return display(base + path.replace("%7B", "{").replace("%7D", "}") + route, 4096).replace("[가림]", "%5BREDACTED%5D");
    } catch (RuntimeException error) { return ""; }
  }
  private static boolean containsKnownValue(String url, Set<String> values) {
    String decoded = Objects.requireNonNullElse(url, "");
    try { decoded = java.net.URLDecoder.decode(decoded, StandardCharsets.UTF_8); } catch (IllegalArgumentException ignored) { }
    if (PROVIDER.matcher(decoded).find()) return true;
    for (String value : values) if (!value.isEmpty() && (decoded.contains(value) || url != null && url.contains(value))) return true;
    return false;
  }
  private static String maskedKnownUrl(String url, Set<String> values) {
    if (!containsKnownValue(url, values)) return safeUrl(url);
    try { return safeUrl(EnvironmentCatalog.origin(URI.create(url.replace("{", "%7B").replace("}", "%7D"))) + "/%5BREDACTED%5D"); }
    catch (RuntimeException ignored) { return ""; }
  }
  static String display(String value, int limit) {
    String text = Objects.requireNonNullElse(value, "").replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
    text = PROVIDER.matcher(text).replaceAll("[가림]");
    text = text.replaceAll("(?im)^(?:authorization|cookie|set-cookie|x-api-key)\\s*:[^\\r\\n]*", "[인증 헤더 가림]");
    text = CONNECTION.matcher(text).replaceAll("[DB 연결정보 가림]");
    text = text.replaceAll("(?i)((?:password|secret|token|api[_-]?key|access[_-]?key)\\s{0,8}[:=]\\s{0,8})[^\\s,;\\\"']{1,256}", "$1[가림]");
    return text.substring(0, Math.min(limit, text.length()));
  }
  private static String clean(String value, Set<String> raw) {
    String text = Objects.requireNonNullElse(value, "");
    for (String secret : raw) if (!secret.isEmpty()) text = text.replace(secret, "[가림]");
    return display(text, value == null ? 0 : Math.min(4096, value.length() + 64));
  }
  private static int line(String body, int position) { int line = 1; for (int i = 0; i < Math.min(position, body.length()); i++) if (body.charAt(i) == '\n') line++; return line; }
  private static int offset(String location) { try { return location.startsWith("Body: ") ? Integer.parseInt(location.substring(6).strip()) : -1; } catch (RuntimeException ignored) { return -1; } }
  private static boolean unresolved(String url) { return url == null || url.contains("{") || url.contains("}") || url.contains("REPLACE_") || url.contains("[동적"); }
  private static boolean scriptSource(JsAnalysis.Endpoint endpoint,String body){
    if(!endpoint.kind().equalsIgnoreCase("HTML · src")||endpoint.start()<0)return false;
    int position=endpoint.start();boolean inside=false;
    Matcher tags=Pattern.compile("(?is)</?script\\b[^>]{0,4096}>").matcher(body);
    while(tags.find()&&tags.start()<=position){
      if(body.lastIndexOf("<!--",tags.start())>body.lastIndexOf("-->",tags.start()))continue;
      boolean closing=body.charAt(tags.start()+1)=='/';
      if(!closing&&!inside&&position<tags.end())return true;
      if(closing)inside=false;else if(!inside)inside=true;
    }
    return false;
  }
  private static String relativeApi(String value){
    if(value==null||value.length()>4096||!value.startsWith("/")||value.startsWith("//")||value.matches("(?s).*[\\r\\n\\t\\\\].*"))return "";
    try{URI uri=URI.create(value.replace("{dynamic}","DYNAMIC"));if(uri.isAbsolute()||uri.getRawAuthority()!=null)return "";String path=value.split("[?#]",2)[0];return display(path,4096);}catch(RuntimeException invalid){return "";}
  }
  private static void candidate(Map<String, ScopeInspection.Candidate> out, String url, String method, String source, String label, boolean executable) {
    if (out.size() >= 400 || url == null || url.length() > 8192) return;
    if (!unresolved(url)&&!(source.equals("JS API")&&!executable&&!relativeApi(url).isEmpty())) try { URI uri = URI.create(url); if (uri.getUserInfo() != null || uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())) return; } catch (RuntimeException error) { return; }
    String key = method + " " + url;
    var candidate = new ScopeInspection.Candidate(url, display(method, 16), source, display(label, 160), executable && !unresolved(url));
    var old = out.get(key);
    if (old == null || candidatePriority(candidate.source()) < candidatePriority(old.source())
        || candidatePriority(candidate.source()) == candidatePriority(old.source()) && !old.executable() && candidate.executable()) out.put(key, candidate);
  }
  private static int candidatePriority(String source) {
    return switch (source) { case "API 문서" -> 0; case "관측 링크", "메뉴 라우트" -> 1; case "JS API" -> 2; default -> 3; };
  }
  private static void add(List<ScopeInspection.Finding> out, String category, String title, String url, String method, String account,
      String confidence, String severity, String state, String detail, String original, int line, int offset, String fingerprint) {
    if (out.size() < FINDING_LIMIT + 1) out.add(new ScopeInspection.Finding(category, display(title, 200), safeUrl(url), display(method, 16), display(account, 160), confidence,
        severity, state, display(detail, 1000), original, line, offset, fingerprint));
  }
  static String hash(String text) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }
}
