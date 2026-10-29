package kr.workbench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only projections of the current inspection; collecting dashboard data never sends requests. */
final class ScopeInspectionDashboard {
  enum Group { PATHS, API, ENVIRONMENT, SECRETS, ACCESS, COVERAGE, OBSERVATIONS }

  record Filter(Group group, String account, String severity) {
    Filter {
      Objects.requireNonNull(group, "집계 그룹을 선택하세요.");
      account = text(account);
      severity = text(severity).isBlank() ? "" : ScopeInspectionDashboard.severity(severity);
    }
  }

  record Row(String id, String category, String title, String account, String severity,
             String state, String confidence, String method, String url, String detail,
             String originalId, int line, int offset) {}

  private record PathKey(String method, String exactUrl) {}
  private static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW", "INFO");
  private static final Set<String> CANDIDATE_COVERAGE = Set.of("자동 요청 제외", "발견했지만 자동 방문하지 않음");

  static List<Row> rows(ScopeInspection.Snapshot snapshot, Filter filter) {
    Objects.requireNonNull(filter, "집계 조건을 선택하세요.");
    if (snapshot == null) return List.of();
    List<Row> result = filter.group() == Group.PATHS ? paths(snapshot, filter.account())
        : findings(snapshot, filter.group(), filter.account());
    if (filter.severity().isEmpty()) return List.copyOf(result);
    return result.stream().filter(row -> row.severity().equals(filter.severity())).toList();
  }

  static int count(ScopeInspection.Snapshot snapshot, Group group, String account) {
    return rows(snapshot, new Filter(group, account, "")).size();
  }

  /** Severity totals use exactly the same secret rows as the corresponding card and detail list. */
  static Map<String, Integer> severityCounts(ScopeInspection.Snapshot snapshot, String account) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String value : SEVERITIES) counts.put(value, 0);
    for (Row row : rows(snapshot, new Filter(Group.SECRETS, account, "")))
      counts.put(row.severity(), counts.get(row.severity()) + 1);
    return java.util.Collections.unmodifiableMap(counts);
  }

  static List<String> accounts(ScopeInspection.Snapshot snapshot) {
    if (snapshot == null) return List.of();
    Set<String> values = new java.util.TreeSet<>();
    for (var page : snapshot.pages()) addAccount(values, page.account());
    for (var finding : snapshot.findings()) addAccount(values, finding.account());
    return List.copyOf(values);
  }

  static String severity(String raw) {
    return switch (text(raw).strip().toUpperCase(Locale.ROOT)) {
      case "HIGH", "CRITICAL", "높음", "상", "심각", "치명적", "치명", "매우 높음" -> "HIGH";
      case "MEDIUM", "MODERATE", "MID", "중간", "보통", "중" -> "MEDIUM";
      case "LOW", "낮음", "하" -> "LOW";
      default -> "INFO";
    };
  }

  static String severityLabel(String normalized) {
    return switch (severity(normalized)) {
      case "HIGH" -> "높음";
      case "MEDIUM" -> "중간";
      case "LOW" -> "낮음";
      default -> "정보";
    };
  }

  static String label(Group group) {
    return switch (Objects.requireNonNull(group)) {
      case PATHS -> "관측 경로";
      case API -> "API·명세";
      case ENVIRONMENT -> "환경·DB 단서";
      case SECRETS -> "키·민감정보 후보";
      case ACCESS -> "계정·권한 검토";
      case COVERAGE -> "미확인·제외";
      case OBSERVATIONS -> "응답·내부정보";
    };
  }

  private static List<Row> findings(ScopeInspection.Snapshot snapshot, Group group, String account) {
    List<Row> result = new ArrayList<>();
    Set<ScopeInspection.Finding> seen = new LinkedHashSet<>();
    Set<PathKey> coveredCandidates = new LinkedHashSet<>();
    Map<String, List<ScopeInspection.Candidate>> relativeLinks = new LinkedHashMap<>();
    if (group == Group.COVERAGE) for (var candidate : snapshot.candidates()) if (!candidate.executable()) {
      String relative = relativeCandidateUrl(candidate.url());
      if (!relative.isEmpty()) {
        String runnerKey = method(candidate.method()) + "|" + relative;
        for (String prefix : List.of("candidate-excluded:", "limited:"))
          relativeLinks.computeIfAbsent(EnvironmentProject.hash(prefix + runnerKey), ignored -> new ArrayList<>()).add(candidate);
      }
    }
    for (var finding : snapshot.findings()) {
      List<ScopeInspection.Candidate> linked = List.of();
      if (group == Group.COVERAGE && "COVERAGE".equals(finding.category())
          && globalAccount(finding.account()) && CANDIDATE_COVERAGE.contains(text(finding.title()))) {
        if (!ScopeInspectionAnalysis.safeUrl(finding.url()).isEmpty())
          coveredCandidates.add(path(finding.method(), finding.url()));
        // The runner cannot put an unresolved relative URL into Finding.url. Only its exact
        // candidate-key fingerprint connects that placeholder finding back to a relative path.
        linked = relativeLinks.getOrDefault(text(finding.fingerprint()), List.of()).stream()
            .filter(candidate -> method(candidate.method()).equals(method(finding.method()))).toList();
        for (var candidate : linked) coveredCandidates.add(path(candidate.method(), candidate.url()));
      }
      if (!belongs(group, finding.category()) || !accountMatches(account, finding.account()) || !seen.add(finding)) continue;
      String url = candidateUrl(finding.url()), detail = display(finding.detail(), 1600);
      if (!linked.isEmpty()) {
        url = relativeCandidateUrl(linked.get(0).url());
        detail += "\n발견 후보 경로 (origin 미확정): " + url + "\n발견 출처: " + candidateSources(linked);
      }
      result.add(new Row("finding:" + ScopeInspectionAnalysis.hash(key(finding.category(), finding.title(), finding.url(),
          finding.method(), finding.account(), finding.confidence(), finding.severity(), finding.state(), finding.detail(),
          finding.originalId(), finding.line(), finding.offset(), finding.fingerprint())),
          display(finding.category(), 32), display(finding.title(), 200), display(finding.account(), 160),
          severity(finding.severity()), display(finding.state(), 80), display(finding.confidence(), 120),
          method(finding.method()), url, detail,
          text(finding.originalId()), finding.line(), finding.offset()));
    }
    // Candidates have no account identity. Keep them global instead of attributing them to the selected account.
    if (group == Group.COVERAGE && account.isEmpty()) {
      Map<PathKey, List<ScopeInspection.Candidate>> candidates = new LinkedHashMap<>();
      for (var candidate : snapshot.candidates()) if (!candidate.executable())
        candidates.computeIfAbsent(path(candidate.method(), candidate.url()), ignored -> new ArrayList<>()).add(candidate);
      for (var entry : candidates.entrySet()) {
        if (coveredCandidates.contains(entry.getKey())) continue;
        var candidate = entry.getValue().get(0);
        String url = candidateUrl(candidate.url());
        result.add(new Row("candidate:" + ScopeInspectionAnalysis.hash(key(entry.getKey().method(), entry.getKey().exactUrl())),
            "COVERAGE", "자동 실행 제외 후보", "전체", "INFO", "미검사", "발견 후보", method(candidate.method()),
            url,
            "자동 실행 대상이 아닌 발견 후보입니다. 계정 정보가 없는 전역 후보이며 취약점 확정이 아닙니다.\n발견 후보 경로: " + url
                + (relativeCandidateUrl(candidate.url()).isEmpty() ? "" : " · origin 미확정")
                + "\n발견 출처: " + candidateSources(entry.getValue()), "", 0, -1));
      }
    }
    return result;
  }

  private static List<Row> paths(ScopeInspection.Snapshot snapshot, String account) {
    Map<PathKey, List<ScopeInspection.Page>> grouped = new LinkedHashMap<>();
    for (var page : snapshot.pages()) if (accountMatches(account, page.account()))
      // Page URLs may already be masked by the runner. PacketRef keeps the exact URL as metadata;
      // reading it does not reconstruct a packet, send a request or expose query values in the UI.
      grouped.computeIfAbsent(path(page.method(), page.original() == null ? page.url() : page.original().url()),
          ignored -> new ArrayList<>()).add(page);
    List<Row> result = new ArrayList<>();
    Comparator<ScopeInspection.Page> representative = Comparator
        .comparing((ScopeInspection.Page page) -> page.original() == null)
        .thenComparing(page -> !page.complete())
        .thenComparing(page -> text(page.account()))
        .thenComparing(page -> page.original() == null ? "" : page.original().id())
        .thenComparingInt(ScopeInspection.Page::status)
        .thenComparing(page -> text(page.classification()))
        .thenComparing(page -> text(page.digest()));
    for (var entry : grouped.entrySet()) {
      var page = entry.getValue().stream().min(representative).orElseThrow();
      Set<String> observedAccounts = new java.util.TreeSet<>();
      for (var observation : entry.getValue()) observedAccounts.add(accountLabel(observation.account()));
      String original = page.original() == null ? "" : page.original().id();
      String detail = "관측 계정: " + String.join(", ", observedAccounts)
          + "\n대표 원본 계정: " + accountLabel(page.account())
          + "\nHTTP " + page.status() + " · " + display(page.classification(), 120)
          + (page.complete() ? " · 응답 분석 완료" : " · 부분 검사")
          + (original.isEmpty() ? "\n대표 원본이 없어 원본 요청·응답을 열 수 없습니다." : "\n상세 원본 열기는 위 대표 계정의 패킷을 사용합니다.")
          + (original.isEmpty() ? "\n원본 URL이 없어 보관된 Page URL·메서드 범위에서만 경로를 구분합니다."
              : "\n원본 메타데이터의 쿼리를 포함한 정확한 URL·메서드로 경로를 구분하며 표시 URL은 가림 처리합니다.")
          + (text(page.note()).isBlank() ? "" : "\n" + display(page.note(), 1000));
      result.add(new Row("path:" + ScopeInspectionAnalysis.hash(key(entry.getKey().method(), entry.getKey().exactUrl())),
          "PATH", "관측 경로 · HTTP " + page.status(), display(page.account(), 160), "INFO",
          page.complete() ? "관측" : "부분 검사", "응답 관측", method(page.method()),
          ScopeInspectionAnalysis.safeUrl(page.url()), detail, original, 0, -1));
    }
    return result;
  }

  private static boolean belongs(Group group, String category) {
    return switch (group) {
      case PATHS -> false;
      case API -> Set.of("API", "INVENTORY").contains(text(category));
      case ENVIRONMENT -> Set.of("ENVIRONMENT", "DATABASE").contains(text(category));
      case SECRETS -> "SECRET".equals(category);
      case ACCESS -> Set.of("ACCESS", "ACCOUNT", "PERMISSION").contains(text(category));
      case COVERAGE -> "COVERAGE".equals(category);
      case OBSERVATIONS -> Set.of("INTERNAL", "RESPONSE").contains(text(category));
    };
  }

  private static boolean accountMatches(String filter, String account) { return filter.isEmpty() || filter.equals(text(account)); }
  private static boolean globalAccount(String account) { return text(account).isEmpty() || "전체".equals(account); }
  private static void addAccount(Set<String> values, String account) { if (!globalAccount(account)) values.add(text(account)); }
  private static String accountLabel(String account) { return text(account).isBlank() ? "계정 미지정" : display(account, 160); }
  private static String text(String value) { return Objects.requireNonNullElse(value, ""); }
  private static String display(String value, int limit) { return ScopeInspectionAnalysis.display(value, limit); }
  private static String candidateSources(List<ScopeInspection.Candidate> candidates) {
    Set<String> sources = new LinkedHashSet<>();
    for (var candidate : candidates) sources.add(display(candidate.source(), 120) + " · " + display(candidate.label(), 160));
    return display(String.join(" / ", sources), 1200);
  }
  private static String candidateUrl(String raw) {
    String absolute = ScopeInspectionAnalysis.safeUrl(raw);
    if (!absolute.isEmpty()) return absolute;
    String relative = relativeCandidateUrl(raw);
    return relative.isEmpty() ? "URL 확인 필요" : relative;
  }
  /** Preserve a path without inventing its origin or displaying query/fragment or credential path values. */
  private static String relativeCandidateUrl(String raw) {
    if (raw == null || raw.length() > 2048 || !raw.startsWith("/") || raw.startsWith("//")) return "";
    try {
      var uri = java.net.URI.create(raw.replace("{", "%7B").replace("}", "%7D"));
      if (uri.getScheme() != null || uri.getRawAuthority() != null) return "";
      String path = text(uri.getRawPath()).replace("%7B", "{").replace("%7D", "}")
          .replaceAll("(?i)(/(?:token|api[_-]?key|secret|password|authorization)/)[^/]+", "$1%5BREDACTED%5D");
      return display(path, 2048);
    } catch (RuntimeException invalid) { return ""; }
  }
  private static String method(String value) { return display(value, 32).strip().toUpperCase(Locale.ROOT); }
  private static PathKey path(String method, String url) { return new PathKey(method(method), text(url)); }
  private static String key(Object... fields) {
    StringBuilder key = new StringBuilder();
    for (Object field : fields) {
      if (field == null) key.append("-1:");
      else {
        String value = field.toString();
        key.append(value.length()).append(':').append(value);
      }
    }
    return key.toString();
  }

  private ScopeInspectionDashboard() {}
}
