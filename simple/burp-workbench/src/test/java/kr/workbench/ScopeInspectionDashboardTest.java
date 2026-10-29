package kr.workbench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Current-snapshot counts, identity-preserving detail navigation and masked dashboard projections. */
public final class ScopeInspectionDashboardTest extends WorkbenchTest {
  private static final String URL = "https://dashboard.test/api/data?item=1";

  private static ScopeInspection.Finding finding(String category, String account, String severity,
                                                 String original, int offset, String fingerprint) {
    return new ScopeInspection.Finding(category, category + " 후보", URL, "GET", account,
        "형식 후보", severity, "수동 검토", "Authorization: PRIVATE_AUTH_VALUE\nsecret=PRIVATE_DETAIL_VALUE",
        original, 3, offset, fingerprint);
  }

  private static ScopeInspection.Snapshot snapshot(List<ScopeInspection.Finding> findings,
                                                  List<ScopeInspection.Page> pages,
                                                  List<ScopeInspection.Candidate> candidates) {
    // completed includes a failed task; partial is an event count, not a second population of pages.
    return new ScopeInspection.Snapshot("dashboard-run", ScopeInspection.State.STOPPED, "실행 중지", 9, 7, 2,
        4, 2, 3, findings, pages, candidates, 100, 200);
  }

  private static List<ScopeInspectionDashboard.Row> rows(ScopeInspection.Snapshot snapshot,
                                                        ScopeInspectionDashboard.Group group) {
    return ScopeInspectionDashboard.rows(snapshot, new ScopeInspectionDashboard.Filter(group, "", ""));
  }

  public static void main(String[] args) {
    var empty = snapshot(List.of(), List.of(), List.of());
    for (var group : ScopeInspectionDashboard.Group.values()) {
      check(rows(empty, group).isEmpty(), "empty snapshot has no invented " + group + " results");
      check(ScopeInspectionDashboard.count(null, group, "") == 0, "no run has zero " + group + " results");
      check(!ScopeInspectionDashboard.label(group).isBlank(), "group has a readable dashboard label: " + group);
    }
    check(ScopeInspectionDashboard.accounts(null).isEmpty(), "no run has no invented account identities");
    check(ScopeInspectionDashboard.severityCounts(null, "").equals(Map.of("HIGH", 0, "MEDIUM", 0, "LOW", 0, "INFO", 0)),
        "no run still exposes all four zero severity buckets");
    check(ScopeInspectionDashboard.count(empty, ScopeInspectionDashboard.Group.PATHS, "") == 0,
        "completed and failed counters never invent observed endpoint rows");

    for (String value : List.of("HIGH", "high", " 높음 ", "CRITICAL", "치명적"))
      check(ScopeInspectionDashboard.severity(value).equals("HIGH"), "high severity normalization: " + value);
    for (String value : List.of("MEDIUM", "medium", "중간", "보통"))
      check(ScopeInspectionDashboard.severity(value).equals("MEDIUM"), "medium severity normalization: " + value);
    for (String value : List.of("LOW", "low", "낮음"))
      check(ScopeInspectionDashboard.severity(value).equals("LOW"), "low severity normalization: " + value);
    for (String value : List.of("INFO", "정보", "INFORMATIONAL", "", "unknown"))
      check(ScopeInspectionDashboard.severity(value).equals("INFO"), "nonseverity values remain informational: " + value);
    check(ScopeInspectionDashboard.severity(null).equals("INFO"), "missing severity remains informational");
    check(ScopeInspectionDashboard.severityLabel("HIGH").equals("높음")
        && ScopeInspectionDashboard.severityLabel("MEDIUM").equals("중간")
        && ScopeInspectionDashboard.severityLabel("LOW").equals("낮음")
        && ScopeInspectionDashboard.severityLabel("INFO").equals("정보"), "severity labels remain Korean");

    try (var store = new DiskTrafficStore()) {
      var request = request(URL, "GET", true, List.of());
      var response = response(200, "{\"example\":true}");
      registerTrafficPacket(request, response);
      var aliceOriginal = store.append("", "Alice 원본", request, response);
      var bobOriginal = store.append("", "Bob 원본", request, response);
      var laterOriginal = store.append("", "다른 원본", request, response);
      var queryOriginal = store.append("", "다른 쿼리 원본",
          request("https://dashboard.test/api/data?item=2", "GET", true, List.of()), response);
      var headOriginal = store.append("", "HEAD 원본", request(URL, "HEAD", true, List.of()), response);
      var alice = finding("SECRET", "Alice", "높음", aliceOriginal.id(), 15, "same-fingerprint");
      var bob = finding("SECRET", "Bob", "HIGH", bobOriginal.id(), 15, "same-fingerprint");
      var otherOriginal = finding("SECRET", "Alice", "HIGH", laterOriginal.id(), 15, "same-fingerprint");
      var otherOffset = finding("SECRET", "Alice", "HIGH", aliceOriginal.id(), 16, "same-fingerprint");
      var medium = finding("SECRET", "Alice", "MEDIUM", aliceOriginal.id(), 30, "medium-fingerprint");
      var low = finding("SECRET", "Bob", "낮음", bobOriginal.id(), 50, "low-fingerprint");
      var info = finding("SECRET", "Alice", "정보", aliceOriginal.id(), 70, "info-fingerprint");
      var findings = new ArrayList<>(List.of(alice, alice, bob, otherOriginal, otherOffset, medium, low, info,
          finding("ENVIRONMENT", "Alice", "정보", aliceOriginal.id(), 0, "environment"),
          finding("DATABASE", "Alice", "INFO", aliceOriginal.id(), 0, "database"),
          finding("API", "Alice", "INFO", aliceOriginal.id(), 10, "api"),
          finding("INVENTORY", "Bob", "INFO", bobOriginal.id(), 10, "inventory"),
          finding("ACCESS", "Alice", "MEDIUM", aliceOriginal.id(), 0, "access"),
          finding("ACCOUNT", "Bob", "중간", bobOriginal.id(), 0, "account"),
          finding("PERMISSION", "Bob", "INFO", bobOriginal.id(), 0, "permission"),
          finding("RESPONSE", "Alice", "INFO", aliceOriginal.id(), 0, "response"),
          finding("INTERNAL", "Bob", "낮음", bobOriginal.id(), 10, "internal"),
          finding("MENU", "Alice", "INFO", aliceOriginal.id(), 0, "menu-is-not-a-secret")));
      var alicePage = new ScopeInspection.Page(URL, "GET", "Alice", 200, "JSON", true, "", "digest-a", aliceOriginal);
      var bobPage = new ScopeInspection.Page(URL, "GET", "Bob", 403, "DENIED", true, "", "digest-b", bobOriginal);
      var noOriginal = new ScopeInspection.Page(URL, "GET", "0-no-original", 200, "JSON", true, "", "digest-no-original", null);
      var anotherQuery = new ScopeInspection.Page("https://dashboard.test/api/data?item=2", "GET", "Alice", 200, "JSON", false,
          "token=PRIVATE_PAGE_NOTE", "digest-c", queryOriginal);
      var head = new ScopeInspection.Page(URL, "HEAD", "Bob", 200, "HEAD", true, "", "digest-head", headOriginal);
      var snapshot = snapshot(findings, List.of(noOriginal, bobPage, alicePage, alicePage, anotherQuery, head), List.of());

      var secrets = rows(snapshot, ScopeInspectionDashboard.Group.SECRETS);
      check(secrets.size() == 7, "exact duplicate finding disappears while distinct account/source/offset evidence remains");
      check(secrets.stream().map(ScopeInspectionDashboard.Row::id).distinct().count() == secrets.size(),
          "different evidence locations have different stable row identities");
      check(secrets.stream().filter(row -> row.account().equals("Bob")).count() == 2,
          "shared fingerprints never merge evidence from another account");
      check(secrets.stream().anyMatch(row -> row.originalId().equals(laterOriginal.id()) && row.offset() == 15)
          && secrets.stream().anyMatch(row -> row.originalId().equals(aliceOriginal.id()) && row.offset() == 16),
          "detail rows retain their precise original ID and source position");
      check(ScopeInspectionDashboard.severityCounts(snapshot, "").equals(Map.of("HIGH", 4, "MEDIUM", 1, "LOW", 1, "INFO", 1)),
          "severity cards count secret evidence only and normalize Korean/English labels");
      check(ScopeInspectionDashboard.severityCounts(snapshot, "Alice").equals(Map.of("HIGH", 3, "MEDIUM", 1, "LOW", 0, "INFO", 1)),
          "account-specific severity totals retain zero buckets and correct evidence");
      var highAlice = ScopeInspectionDashboard.rows(snapshot, new ScopeInspectionDashboard.Filter(ScopeInspectionDashboard.Group.SECRETS, "Alice", "높음"));
      check(highAlice.size() == 3 && highAlice.stream().allMatch(row -> row.account().equals("Alice") && row.severity().equals("HIGH")),
          "combined account and severity filtering opens precisely the indicated card population");
      check(ScopeInspectionDashboard.rows(snapshot, new ScopeInspectionDashboard.Filter(ScopeInspectionDashboard.Group.SECRETS, "missing-account", "")).isEmpty(),
          "an absent account never falls back to another account's evidence");
      check(ScopeInspectionDashboard.count(snapshot, ScopeInspectionDashboard.Group.ENVIRONMENT, "") == 2
          && ScopeInspectionDashboard.count(snapshot, ScopeInspectionDashboard.Group.API, "") == 2
          && ScopeInspectionDashboard.count(snapshot, ScopeInspectionDashboard.Group.ACCESS, "") == 3
          && ScopeInspectionDashboard.count(snapshot, ScopeInspectionDashboard.Group.OBSERVATIONS, "") == 2,
          "environment, inventory, account/permission and response/internal aliases remain in their proper groups");
      check(ScopeInspectionDashboard.accounts(snapshot).equals(List.of("0-no-original", "Alice", "Bob")),
          "account options combine page and finding identities without duplicates");

      var paths = rows(snapshot, ScopeInspectionDashboard.Group.PATHS);
      check(paths.size() == 3, "paths deduplicate repeated observations and accounts but retain exact query URLs and distinct methods");
      var sharedPath = paths.stream().filter(row -> row.originalId().equals(aliceOriginal.id())).findFirst().orElseThrow();
      check(sharedPath.detail().contains("관측 계정: 0-no-original, Alice, Bob")
          && sharedPath.detail().contains("대표 원본 계정: Alice") && sharedPath.account().equals("Alice"),
          "a merged path discloses all observed accounts and the representative packet's actual identity");
      var bobPaths = ScopeInspectionDashboard.rows(snapshot, new ScopeInspectionDashboard.Filter(ScopeInspectionDashboard.Group.PATHS, "Bob", ""));
      check(bobPaths.size() == 2 && bobPaths.stream().allMatch(row -> row.originalId().equals(
          row.method().equals("HEAD") ? headOriginal.id() : bobOriginal.id())),
          "account filtering selects that account's own packet rather than the global representative");
      var reversedPages = new ArrayList<>(snapshot.pages());
      Collections.reverse(reversedPages);
      var reversed = rows(snapshot(findings, reversedPages, List.of()), ScopeInspectionDashboard.Group.PATHS);
      check(reversed.stream().filter(row -> row.id().equals(sharedPath.id())).findFirst().orElseThrow().originalId().equals(aliceOriginal.id()),
          "reordering source pages cannot silently switch the selected path's representative account");
      String maskedUrl = ScopeInspectionAnalysis.safeUrl(URL);
      var maskedFirst = new ScopeInspection.Page(maskedUrl, "GET", "Alice", 200, "JSON", true, "", "first-query", aliceOriginal);
      var maskedSecond = new ScopeInspection.Page(maskedUrl, "GET", "Alice", 200, "JSON", true, "", "second-query", queryOriginal);
      var maskedRun = snapshot(List.of(), List.of(maskedFirst, maskedSecond, maskedFirst), List.of());
      var maskedPaths = rows(maskedRun, ScopeInspectionDashboard.Group.PATHS);
      check(maskedPaths.size() == 2 && ScopeInspectionDashboard.count(maskedRun, ScopeInspectionDashboard.Group.PATHS, "") == 2,
          "runner-masked Page URLs still produce two card/detail paths for distinct exact original queries");
      check(maskedPaths.stream().map(ScopeInspectionDashboard.Row::originalId).collect(java.util.stream.Collectors.toSet())
          .equals(java.util.Set.of(aliceOriginal.id(), queryOriginal.id())),
          "distinct original-query paths retain both correct packet IDs after Page URL redaction");
      check(maskedPaths.stream().map(ScopeInspectionDashboard.Row::id).distinct().count() == 2
          && maskedPaths.stream().allMatch(row -> row.url().equals(maskedUrl) && !row.detail().contains("item=")),
          "exact-query grouping keeps distinct row identities without displaying either original query");
      var missingOriginal = new ScopeInspection.Page(maskedUrl, "GET", "Alice", 200, "JSON", true, "", "no-original", null);
      var missingOriginalPaths = rows(snapshot(List.of(), List.of(missingOriginal, missingOriginal), List.of()), ScopeInspectionDashboard.Group.PATHS);
      check(missingOriginalPaths.size() == 1 && missingOriginalPaths.get(0).originalId().isEmpty()
          && missingOriginalPaths.get(0).detail().contains("보관된 Page URL·메서드 범위"),
          "a Page without an original falls back to its retained URL and never invents exact query metadata");
      for (var group : ScopeInspectionDashboard.Group.values()) {
        var all = rows(snapshot, group);
        check(ScopeInspectionDashboard.count(snapshot, group, "") == all.size(), "card/list total agreement: " + group);
        check(ScopeInspectionDashboard.count(snapshot, group, "Bob")
            == ScopeInspectionDashboard.rows(snapshot, new ScopeInspectionDashboard.Filter(group, "Bob", "")).size(),
            "filtered card/list total agreement: " + group);
        check(all.stream().noneMatch(row -> row.url().contains("item=") || row.detail().contains("PRIVATE_")
            || row.url().contains("PRIVATE_")), "dashboard text applies existing URL/detail redaction: " + group);
      }
      check(snapshot.completed() == 7 && snapshot.failed() == 2 && snapshot.partial() == 3
          && snapshot.findings().size() == findings.size() && snapshot.pages().size() == 6,
          "dashboard projection leaves stopped/failed execution counters and source data untouched");
      try { secrets.add(secrets.get(0)); throw new AssertionError("mutable dashboard rows"); }
      catch (UnsupportedOperationException expected) { check(true, "dashboard detail rows are immutable snapshots"); }
    }

    String omitted = "https://dashboard.test/manual";
    var coverage = new ScopeInspection.Finding("COVERAGE", "자동 요청 제외", omitted, "POST", "전체", "관측", "INFO", "미검사",
        "필수 값 확인 필요", "", 0, -1, "coverage-global");
    var accountCoverage = new ScopeInspection.Finding("COVERAGE", "요청·분석 실패", omitted, "POST", "Alice", "미확인", "INFO", "실패",
        "원인 확인 필요", "", 0, -1, "coverage-alice");
    var candidates = List.of(new ScopeInspection.Candidate(omitted, "POST", "JS", "원본 확인", false),
        new ScopeInspection.Candidate(omitted, "POST", "JS", "원본 확인", false),
        new ScopeInspection.Candidate("https://dashboard.test/second", "POST", "JS", "원본 확인", false),
        new ScopeInspection.Candidate("https://dashboard.test/second", "POST", "OpenAPI", "필수값 필요", false),
        new ScopeInspection.Candidate("https://dashboard.test/executable", "GET", "링크", "실행 가능", true));
    var coverageRun = snapshot(List.of(coverage, coverage, accountCoverage), List.of(), candidates);
    var limitations = rows(coverageRun, ScopeInspectionDashboard.Group.COVERAGE);
    check(limitations.size() == 3, "coverage merges an already-explained candidate and repeated candidate conditions without losing account-specific failures");
    check(limitations.stream().filter(row -> row.title().equals("자동 실행 제외 후보")).count() == 1
        && limitations.stream().noneMatch(row -> row.url().endsWith("executable")),
        "nonexecuted candidates appear once while executable candidates never become invented limitations");
    var candidateRow = limitations.stream().filter(row -> row.title().equals("자동 실행 제외 후보")).findFirst().orElseThrow();
    check(candidateRow.originalId().isEmpty() && candidateRow.account().equals("전체")
        && candidateRow.detail().contains("JS") && candidateRow.detail().contains("OpenAPI"),
        "global candidate detail retains both discovery sources without inventing a packet or account");
    var aliceLimitations = ScopeInspectionDashboard.rows(coverageRun,
        new ScopeInspectionDashboard.Filter(ScopeInspectionDashboard.Group.COVERAGE, "Alice", ""));
    check(aliceLimitations.size() == 1 && aliceLimitations.get(0).state().equals("실패"),
        "account filtering never attributes account-free candidates to the selected identity");
    check(ScopeInspectionDashboard.accounts(coverageRun).equals(List.of("Alice")), "global coverage is not offered as an account identity");
    check(ScopeInspectionDashboard.severityCounts(coverageRun, "").values().stream().mapToInt(Integer::intValue).sum() == 0,
        "coverage failures do not inflate secret severity cards");

    String relative = "/app/api/not-own-host", limitedRelative = "/app/api/limited-host";
    var relativeCoverage = new ScopeInspection.Finding("COVERAGE", "자동 요청 제외", "URL 확인 필요", "GET", "전체", "관측", "INFO", "미검사",
        "URL·필수 값 미확정 · JS API", "", 0, -1, EnvironmentProject.hash("candidate-excluded:GET|" + relative));
    var limitedCoverage = new ScopeInspection.Finding("COVERAGE", "발견했지만 자동 방문하지 않음", "URL 확인 필요", "GET", "전체", "관측", "INFO", "미검사",
        "후보 한도 · 원본 확인 필요", "", 0, -1, EnvironmentProject.hash("limited:GET|" + limitedRelative));
    var unrelatedCoverage = new ScopeInspection.Finding("COVERAGE", "자동 요청 제외", "URL 확인 필요", "GET", "전체", "관측", "INFO", "미검사",
        "다른 후보의 미확정 URL", "", 0, -1, EnvironmentProject.hash("candidate-excluded:GET|/different-candidate"));
    var relativeRun = snapshot(List.of(relativeCoverage, limitedCoverage, unrelatedCoverage), List.of(),
        List.of(new ScopeInspection.Candidate(relative, "GET", "JS API", "서버/기본 경로 미확정", false),
            new ScopeInspection.Candidate(relative, "GET", "JS API", "서버/기본 경로 미확정", false),
            new ScopeInspection.Candidate(limitedRelative, "GET", "JS API", "원본 확인 필요", false),
            new ScopeInspection.Candidate("/app/api/unlinked-one", "GET", "JS API", "미연결 후보 1", false),
            new ScopeInspection.Candidate("/app/api/unlinked-two", "GET", "JS API", "미연결 후보 2", false)));
    var relativeRows = rows(relativeRun, ScopeInspectionDashboard.Group.COVERAGE);
    check(relativeRows.size() == 5 && ScopeInspectionDashboard.count(relativeRun, ScopeInspectionDashboard.Group.COVERAGE, "") == 5,
        "production-format candidate-excluded/limited fingerprints join unresolved relative coverage exactly once");
    check(relativeRows.stream().filter(row -> row.url().equals(relative)).count() == 1
        && relativeRows.stream().filter(row -> row.url().equals(limitedRelative)).count() == 1
        && relativeRows.stream().noneMatch(row -> row.title().equals("자동 실행 제외 후보")
            && (row.url().equals(relative) || row.url().equals(limitedRelative))),
        "linked relative paths survive the runner's placeholder Finding URL without becoming duplicate candidate rows");
    var linkedRelative = relativeRows.stream().filter(row -> row.url().equals(relative)).findFirst().orElseThrow();
    check(linkedRelative.title().equals("자동 요청 제외") && linkedRelative.detail().contains(relative)
        && linkedRelative.detail().contains("origin 미확정") && linkedRelative.originalId().isEmpty(),
        "fingerprint-linked coverage retains the unresolved relative path, its reason and absent original identity");
    check(relativeRows.stream().filter(row -> row.title().equals("자동 실행 제외 후보")).count() == 2
        && relativeRows.stream().anyMatch(row -> row.url().equals("/app/api/unlinked-one"))
        && relativeRows.stream().anyMatch(row -> row.url().equals("/app/api/unlinked-two")),
        "unlinked relative candidates remain separate even when unrelated coverage uses the same unknown-URL placeholder");
    check(relativeRows.stream().allMatch(row -> !row.url().isEmpty() && !row.url().contains("://"))
        && ScopeInspectionDashboard.count(relativeRun, ScopeInspectionDashboard.Group.COVERAGE, "Alice") == 0,
        "relative paths have visible identifiers without invented origins or account attribution");
    var relativeSafety = snapshot(List.of(), List.of(), List.of(
        new ScopeInspection.Candidate("/app/token/PRIVATE_PATH_TOKEN/{id}?secret=PRIVATE_QUERY#fragment", "GET", "JS API", "변수 경로", false),
        new ScopeInspection.Candidate("//untrusted.example.test/api?token=PRIVATE_QUERY", "GET", "JS API", "authority 미확정", false),
        new ScopeInspection.Candidate("/app/[invalid", "GET", "JS API", "잘못된 경로", false)));
    var safeRelativeRows = rows(relativeSafety, ScopeInspectionDashboard.Group.COVERAGE);
    check(safeRelativeRows.stream().anyMatch(row -> row.url().equals("/app/token/%5BREDACTED%5D/{id}"))
        && safeRelativeRows.stream().noneMatch(row -> row.url().contains("PRIVATE_") || row.url().contains("?")
            || row.url().contains("#") || row.detail().contains("PRIVATE_")),
        "relative display strips query/fragment and masks credential path values while retaining template identifiers");
    check(safeRelativeRows.size() == 3 && safeRelativeRows.stream().allMatch(row -> !row.url().isBlank())
        && safeRelativeRows.stream().noneMatch(row -> row.url().contains("untrusted.example.test"))
        && safeRelativeRows.stream().map(ScopeInspectionDashboard.Row::id).distinct().count() == 3,
        "invalid and authority-relative candidates keep separate nonempty placeholders without invented resolution");
    System.out.println("ScopeInspectionDashboardTest: " + checks + " checks passed (current snapshot only, no HTTP)");
  }
}
