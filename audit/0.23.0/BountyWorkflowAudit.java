package kr.workbench;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import kr.payloadrunner.Engine;

/** Current production algorithms + real loopback HTTP; the host is an API adapter, not Burp. */
public final class BountyWorkflowAudit extends CommunityExtensionAudit {
    private static final List<BountyEvidence.Item> evidence = new ArrayList<>();
    private static final List<Map<String,Object>> flows = new ArrayList<>();
    private static String form(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String url(Reply reply) throws Exception { return mapper.readValue(reply.body(), Map.class).get("url").toString(); }
    private static AuthJudgement.Rules rules() { return new AuthJudgement.Rules(Set.of(), Map.of(), "$.ownerId", AuthPolicy.Access.UNSPECIFIED); }
    private static void savePacket(String label, String method, String path, Account account, String body, String type, Reply reply, String note) {
        evidence.add(new BountyEvidence.Item(label, method, base + path, template(method, path, account, body, type), new String(reply.raw(), StandardCharsets.UTF_8), note));
    }
    private static boolean allows(int id, String user) {
        return user.equals("admin") || id == 201 || id == 202 && user.equals("bob") || id == 203 && Set.of("alice", "moderator").contains(user)
            || id == 204 && Set.of("alice", "bob").contains(user) || id == 205 && user.equals("bob") || id == 206 && user.equals("alice");
    }
    private static void policies(Account alice, Account bob, Account admin, Account moderator) throws Exception {
        for (String mode : List.of("vuln", "fixed")) for (int id = 201; id <= 206; id++) {
            String path = "/api/bounty/" + mode + "/objects/" + id;
            Account owner = id == 202 || id == 205 ? bob : alice;
            Reply baseline = get(path, owner);
            verify(mode + " owner baseline " + id, baseline.status() == 200 && baseline.body().contains("BOUNTY_OBJECT_" + id));
            for (Account target : List.of(alice, bob, moderator, admin, guest)) {
                Reply response = get(path, target), recheck = get(path, owner);
                boolean permitted = allows(id, target.name()), actualAllowed = mode.equals("fixed") ? permitted : id == 201 || target != guest;
                verify(mode + " object " + id + " account " + target.name(), response.status() == (actualAllowed ? 200 : 404));
                boolean exists = recheck.status() == 200 && baseline.body().equals(recheck.body());
                var expectation = permitted ? BountyMatrix.Expected.ALLOW : BountyMatrix.Expected.DENY;
                String note = BountyMatrix.parse("").assess(expectation, response.status(), response.body(), rules(), exists);
                verify(mode + " matrix policy " + id + " " + target.name(), permitted ? note.contains("기대 ALLOW · 성공") : actualAllowed ? note.contains("권한 위반 후보") : note.contains("차단 근거 일치"));
                if (id == 201) verify(mode + " PUBLIC no owner false positive " + target.name(), AuthJudgement.compare(baseline.status(), baseline.body(), response.status(), response.body(), new AuthJudgement.Rules(Set.of(), Map.of(), "$.ownerId", AuthPolicy.Access.PUBLIC), target.owner(), exists).state().equals("공개/공유 허용"));
                authRows.add(Map.of("mode", mode, "id", id, "account", target.name(), "expected", expectation.name(), "status", response.status(), "ownerRechecked", exists, "assessment", note));
                if (target == alice && (id == 202 || id == 205)) {
                    savePacket(mode + " owner baseline " + id, "GET", path, owner, "", "", baseline, "Same-object owner baseline; identical owner recheck=" + exists);
                    savePacket(mode + " alternate account " + id, "GET", path, target, "", "", response, note);
                }
            }
        }
        Reply absent = get("/api/bounty/fixed/objects/999", bob);
        verify("nonexistent object stays review without valid owner baseline", absent.status() == 404 && BountyMatrix.parse("").assess(BountyMatrix.Expected.DENY, 404, absent.body(), rules(), false).contains("재검토"));
        verify("invalid mode rejected", get("/api/bounty/invalid/objects/201", alice).status() == 404);
    }
    private static void storedAndUploads(Account alice, Account bob) throws Exception {
        for (String mode : List.of("vuln", "fixed")) {
            boolean safe = mode.equals("fixed"); String marker = safe ? "PR_0123456789abcdea" : "PR_0123456789abcdeb";
            String raw = "<b>" + marker + "</b>", requestBody = "value=" + form(raw), path = "/api/bounty/" + mode + "/stored";
            StoredFlows tracker = new StoredFlows(); tracker.register(marker);
            Reply write = post(path, alice, requestBody, "application/x-www-form-urlencoded");
            verify(mode + " stored creation accepted", write.status() == 200);
            tracker.observe(base + path, "POST", requestBody, write.status(), "application/json", write.body());
            verify(mode + " write stage does not prove readback", tracker.views().stream().anyMatch(f -> f.writeAccepted() && !f.readbackObserved() && f.browserEvidence().isEmpty()));
            String readPath = url(write); Reply read = get(readPath, alice);
            tracker.observe(base + readPath, "GET", "", read.status(), EnvironmentResponse.header(read.headers(), "Content-Type"), read.body());
            StoredFlows.Flow flow = tracker.views().stream().filter(StoredFlows.Flow::readbackObserved).findFirst().orElseThrow();
            verify(mode + " readback links HTML stage", flow.writeAccepted() && flow.htmlObserved() && flow.readUrl().equals(base + readPath));
            verify(mode + " escaping control classified without execution claim", flow.escapedCandidate() == safe && flow.browserEvidence().isEmpty());
            verify(mode + " stored actual bytes", read.body().contains(safe ? "&lt;b&gt;" + marker : "<b>" + marker));
            verify(mode + " stored cross-account policy", get(readPath, bob).status() == (safe ? 404 : 200));
            verify(mode + " stored anonymous policy", get(readPath, guest).status() == (safe ? 404 : 200));
            verify(mode + " cross-mode object isolation", get(readPath.replace("/" + mode + "/", safe ? "/vuln/" : "/fixed/"), alice).status() == 404);
            savePacket(mode + " stored write", "POST", path, alice, requestBody, "application/x-www-form-urlencoded", write, "Successful write; no browser execution claim");
            savePacket(mode + " stored read", "GET", readPath, alice, "", "", read, StoredFlows.describe(flow));
            flows.add(Map.of("mode", mode, "kind", "stored", "state", flow.state(), "writeAccepted", flow.writeAccepted(), "readbackObserved", flow.readbackObserved(), "htmlObserved", flow.htmlObserved(), "escapedCandidate", flow.escapedCandidate(), "browserEvidence", flow.browserEvidence()));
            verify(mode + " other account cannot delete fixtures", post(readPath + "/delete", bob, "", "").status() == 404);
            verify(mode + " owner deletion accepted", post(readPath + "/delete", alice, "", "").status() == 200);
            verify(mode + " owner recheck detects deleted data", get(readPath, alice).status() == 404);
            String uploadPath = "/api/bounty/" + mode + "/uploads?name=" + (safe ? "probe.txt" : "probe.html");
            Reply uploaded = post(uploadPath, alice, raw, "text/html"); verify(mode + " upload accepted", uploaded.status() == 200);
            String downloadPath = url(uploaded); Reply download = get(downloadPath, alice);
            verify(mode + " download body retains marker", download.status() == 200 && download.body().equals(raw));
            verify(mode + " download disposition control", download.headers().toLowerCase(Locale.ROOT).contains(safe ? "attachment" : "inline"));
            verify(mode + " download MIME control", EnvironmentResponse.header(download.headers(), "Content-Type").startsWith(safe ? "text/plain" : "text/html"));
            verify(mode + " download cross-account control", get(downloadPath, bob).status() == (safe ? 404 : 200));
            StoredFlows uploadTracker = new StoredFlows(); uploadTracker.register(marker);
            uploadTracker.observe(base + uploadPath, "POST", raw, uploaded.status(), "application/json", uploaded.body());
            uploadTracker.observe(base + downloadPath, "GET", "", download.status(), EnvironmentResponse.header(download.headers(), "Content-Type"), download.body());
            verify(mode + " upload readback stage honors MIME", uploadTracker.views().stream().anyMatch(f -> f.readbackObserved() && f.htmlObserved() == !safe && f.browserEvidence().isEmpty()));
            savePacket(mode + " upload download", "GET", downloadPath, alice, "", "", download, "HTML inline versus text attachment; browser execution not tested");
        }
        Account noCsrf = new Account(alice.name(), alice.cookie(), "", alice.owner());
        verify("fixed stored requires CSRF", post("/api/bounty/fixed/stored", noCsrf, "value=x", "application/x-www-form-urlencoded").status() == 403);
        verify("fixed HTML filename rejected", post("/api/bounty/fixed/uploads?name=probe.html", alice, "x", "text/html").status() == 415);
        verify("anonymous cannot create stored fixture", post("/api/bounty/vuln/stored", guest, "value=x", "application/x-www-form-urlencoded").status() == 401);
        verify("stored body bound", post("/api/bounty/vuln/stored", alice, "value=" + "x".repeat(4097), "application/x-www-form-urlencoded").status() == 400);
        verify("upload byte bound", post("/api/bounty/vuln/uploads?name=x.html", alice, "x".repeat(65537), "text/html").status() == 400);
        verify("reset requires CSRF", post("/api/bounty/reset", noCsrf, "", "").status() == 403);
        verify("reset requires login", post("/api/bounty/reset", guest, "", "").status() == 401);
        verify("owner reset succeeds", post("/api/bounty/reset", alice, "", "").status() == 200);
        String bobData = url(post("/api/bounty/fixed/stored", bob, "value=BOB_RESET_CONTROL", "application/x-www-form-urlencoded"));
        for (int i = 0; i < 64; i++) verify("bounded fixture allocation " + i, post("/api/bounty/vuln/stored", alice, "value=bounded-" + i, "application/x-www-form-urlencoded").status() == 200);
        verify("65th owner fixture rejected", post("/api/bounty/vuln/stored", alice, "value=over-limit", "application/x-www-form-urlencoded").status() == 429);
        Reply reset = post("/api/bounty/reset", alice, "", ""); verify("reset removes exactly owner's 64 items", reset.status() == 200 && mapper.readValue(reset.body(), Map.class).get("deleted").equals(64));
        verify("reset preserves another account data", get(bobData, bob).body().contains("BOB_RESET_CONTROL"));
        verify("reset makes capacity reusable", post("/api/bounty/vuln/stored", alice, "value=REUSABLE", "application/x-www-form-urlencoded").status() == 200);
        post("/api/bounty/reset", alice, "", ""); post("/api/bounty/reset", bob, "", "");
    }
    private static void payloadControls(Account alice) throws Exception {
        for (String mode : List.of("vuln", "fixed")) {
            var sql = scan(mode + "-bounty-sql-control", Engine.Profile.SQLI, template("GET", "/api/community/" + mode + "/search?q=§PAYLOAD§", alice, "", ""), "Burp", List.of("'"), "", true);
            verify(mode + " actual SQL positive/negative control", candidate(sql, "SQL 오류 후보") == mode.equals("vuln"));
            var xss = scan(mode + "-bounty-xss-control", Engine.Profile.XSS, template("GET", "/" + mode + "/preview?text=§PAYLOAD§", alice, "", ""), "normal", List.of("<b>{{MARKER}}</b>"), "", true);
            verify(mode + " actual reflected marker positive/negative control", candidate(xss, "XSS 반사 후보") == mode.equals("vuln"));
        }
    }
    public static void main(String[] args) throws Exception {
        port = Integer.parseInt(args[0]); base = "http://127.0.0.1:" + port; Path out = Path.of(args[1]); int code = 0;
        try {
            verify("new lab page served", get("/bounty-lab", guest).body().contains("버그바운티 흐름 실습"));
            verify("new lab manifest version", get("/api/bounty/manifest", guest).body().contains(args[3]));
            Account alice = login("alice", "1"), bob = login("bob", "2"), admin = login("admin", "3"), moderator = login("moderator", "4");
            policies(alice, bob, admin, moderator); storedAndUploads(alice, bob); payloadControls(alice);
            BountyEvidence.save(out.resolve("http-evidence.zip"), "Local bounty workflow audit", "Deliberate vulnerable/fixed controls", "Owner baseline, alternate account, owner recheck; staged stored/upload reads", evidence, Set.of(), List.of(alice.cookie(), bob.cookie(), alice.csrf(), bob.csrf()), true, "{}", null);
            try (ZipFile zip = new ZipFile(out.resolve("http-evidence.zip").toFile())) {
                StringBuilder content = new StringBuilder(); for (var entry : Collections.list(zip.entries())) content.append(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                verify("export strips live account secrets", !content.toString().contains(alice.cookie()) && !content.toString().contains(bob.cookie()) && !content.toString().contains(alice.csrf()) && !content.toString().contains(bob.csrf()));
                verify("export preserves observed stored marker", content.toString().contains("PR_0123456789abcdeb"));
            }
        } catch (Throwable failure) { failure.printStackTrace(); code = 1; }
        finally {
            Map<String,Object> result = new LinkedHashMap<>(); result.put("extensionVersion", args[2]); result.put("labVersion", args[3]);
            result.put("mode", "current release JAR + current lab Java source + loopback Spring/Tomcat/H2 HTTP; API adapters; no native Burp, Docker or browser DOM");
            result.put("httpRequests", sends); result.put("checks", assertions); result.put("passed", assertions.stream().filter(row -> Boolean.TRUE.equals(row.get("passed"))).count()); result.put("failed", assertions.stream().filter(row -> Boolean.FALSE.equals(row.get("passed"))).count());
            result.put("auth", authRows); result.put("storedFlows", flows); result.put("payload", payloadRows); mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("results.json").toFile(), result);
        }
        System.out.println("BOUNTY_WORKFLOW_SUMMARY checks=" + assertions.size() + " HTTP_requests=" + sends + " exit=" + code); System.exit(code);
    }
}
