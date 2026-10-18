package lab.enterprise;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded, in-memory, resettable exercises. This controller is intentionally vulnerable in vuln mode. */
@RestController
public final class BountyScenarioController {
    private static final int MAX_ITEMS = 256, MAX_OWNER_ITEMS = 64, MAX_BYTES = 65536;
    private record Entry(String id, int owner, String mode, String kind, String name, String type, byte[] bytes) {}
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private static void fail(HttpStatus status, String message) { throw new ResponseStatusException(status, message); }
    private static boolean fixed(String mode) {
        if (!Set.of("vuln", "fixed").contains(mode)) fail(HttpStatus.NOT_FOUND, "mode");
        return mode.equals("fixed");
    }
    private static int uid(HttpServletRequest request) {
        var session = request.getSession(false);
        return session == null || !(session.getAttribute("uid") instanceof Integer) ? 0 : (Integer) session.getAttribute("uid");
    }
    private static int login(HttpServletRequest request) {
        int user = uid(request); if (user == 0) fail(HttpStatus.UNAUTHORIZED, "login required"); return user;
    }
    private static void csrf(HttpServletRequest request) {
        login(request);
        if (!Objects.equals(request.getHeader("X-CSRF-Token"), request.getSession(false).getAttribute("csrf")))
            fail(HttpStatus.FORBIDDEN, "csrf required");
    }
    private static String tenant(int user) { return user == 1 || user == 4 ? "A" : user == 2 ? "B" : user == 3 ? "ROOT" : "OTHER"; }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }

    @GetMapping(value="/bounty-lab", produces="text/html;charset=UTF-8")
    public String page() throws IOException {
        try (var in = getClass().getResourceAsStream("/static/bounty-lab.html")) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @GetMapping("/api/bounty/manifest")
    public Map<String, Object> manifest() {
        return Map.of("version", "3.10.0", "trainingOnly", true, "accounts", Map.of("alice", "A", "bob", "B", "moderator", "A", "admin", "ROOT"),
            "objects", List.of(Map.of("id", 201, "policy", "PUBLIC", "owner", "alice"), Map.of("id", 202, "policy", "OWNER_ONLY", "owner", "bob"),
                Map.of("id", 203, "policy", "TENANT", "tenant", "A"), Map.of("id", 204, "policy", "SHARED", "members", "alice,bob"),
                Map.of("id", 205, "policy", "TENANT", "tenant", "B"), Map.of("id", 206, "policy", "OWNER_ONLY", "owner", "alice")),
            "limits", Map.of("globalEntries", MAX_ITEMS, "entriesPerOwner", MAX_OWNER_ITEMS, "bytesPerUpload", MAX_BYTES, "storedCharacters", 4096),
            "flows", List.of("POST /api/bounty/{mode}/stored -> GET /api/bounty/{mode}/stored/{id}", "POST /api/bounty/{mode}/uploads?name=... -> GET /api/bounty/{mode}/uploads/{id}", "POST /api/bounty/{mode}/{kind}/{id}/delete", "POST /api/bounty/reset"),
            "notes", List.of("Objects share route patterns; do not infer authorization from HTTP 200 alone.", "Fixed writes require X-CSRF-Token.", "Reset only deletes the logged-in account's newly created bounty fixtures.", "HTML marker observation is not browser execution proof."));
    }

    @GetMapping("/api/bounty/{mode}/objects/{id}")
    public ResponseEntity<Map<String, Object>> object(@PathVariable String mode, @PathVariable int id, HttpServletRequest request) {
        boolean safe = fixed(mode); int user = uid(request);
        if (id < 201 || id > 206) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        int owner = id == 202 || id == 205 ? 2 : 1;
        String policy = id == 201 ? "PUBLIC" : id == 203 || id == 205 ? "TENANT" : id == 204 ? "SHARED" : "OWNER_ONLY";
        boolean allowed = user == 3 || id == 201 || id == 204 && (user == 1 || user == 2) || (id == 203 || id == 205) && tenant(user).equals(tenant(owner)) || (id == 202 || id == 206) && user == owner;
        if (safe && !allowed || !safe && id != 201 && user == 0) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        return ResponseEntity.ok().header("X-Lab-Policy", policy).body(Map.of("id", id, "ownerId", owner, "tenant", tenant(owner), "policy", policy, "data", "BOUNTY_OBJECT_" + id, "trainingOnly", true));
    }

    private synchronized Map<String, Object> add(int owner, String mode, String kind, String name, String type, byte[] bytes) {
        if (entries.size() >= MAX_ITEMS || entries.values().stream().filter(e -> e.owner == owner).count() >= MAX_OWNER_ITEMS)
            fail(HttpStatus.TOO_MANY_REQUESTS, "fixture limit; reset your fixtures");
        String id = UUID.randomUUID().toString();
        entries.put(id, new Entry(id, owner, mode, kind, name, type, bytes));
        return Map.of("id", id, "url", "/api/bounty/" + mode + "/" + kind + "/" + id, "trainingOnly", true);
    }

    @PostMapping("/api/bounty/{mode}/stored")
    public Map<String, Object> store(@PathVariable String mode, @RequestParam String value, HttpServletRequest request) {
        boolean safe = fixed(mode); int user = login(request); if (safe) csrf(request);
        if (value.length() > 4096) fail(HttpStatus.BAD_REQUEST, "stored value limit");
        return add(user, mode, "stored", "", "text/html;charset=UTF-8", value.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/api/bounty/{mode}/uploads")
    public Map<String, Object> upload(@PathVariable String mode, @RequestParam String name, HttpServletRequest request) throws IOException {
        boolean safe = fixed(mode); int user = login(request); if (safe) csrf(request);
        if (name.isBlank() || name.length() > 120 || name.contains("\r") || name.contains("\n")) fail(HttpStatus.BAD_REQUEST, "filename limit");
        if (safe && !name.toLowerCase(Locale.ROOT).endsWith(".txt")) fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "fixed mode accepts .txt only");
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length == 0 || body.length > MAX_BYTES) fail(HttpStatus.BAD_REQUEST, "upload must be 1..65536 bytes");
        String type = safe ? "text/plain;charset=UTF-8" : "text/html".equalsIgnoreCase(request.getContentType()) ? "text/html;charset=UTF-8" : "application/octet-stream";
        return add(user, mode, "uploads", name, type, body);
    }

    private synchronized Entry readable(String mode, String kind, String id, HttpServletRequest request) {
        boolean safe = fixed(mode); Entry entry = entries.get(id);
        if (entry == null || !entry.mode.equals(mode) || !entry.kind.equals(kind) || safe && entry.owner != uid(request)) fail(HttpStatus.NOT_FOUND, "not_found");
        return entry;
    }

    @GetMapping("/api/bounty/{mode}/stored/{id}")
    public ResponseEntity<String> read(@PathVariable String mode, @PathVariable String id, HttpServletRequest request) {
        Entry entry = readable(mode, "stored", id, request); String value = new String(entry.bytes, StandardCharsets.UTF_8);
        var result = ResponseEntity.ok().header("Content-Type", "text/html;charset=UTF-8").header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff");
        if (fixed(mode)) result.header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        return result.body("<!doctype html><meta charset='utf-8'><title>Stored fixture</title><div>" + (fixed(mode) ? escape(value) : value) + "</div>");
    }

    @GetMapping("/api/bounty/{mode}/uploads/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String mode, @PathVariable String id, HttpServletRequest request) {
        Entry entry = readable(mode, "uploads", id, request);
        return ResponseEntity.ok().header("Content-Type", entry.type).header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", fixed(mode) ? "attachment; filename=fixture.txt" : "inline").body(entry.bytes);
    }

    @PostMapping("/api/bounty/{mode}/{kind}/{id}/delete")
    public synchronized Map<String, Object> delete(@PathVariable String mode, @PathVariable String kind, @PathVariable String id, HttpServletRequest request) {
        fixed(mode); int user = login(request); csrf(request); Entry entry = entries.get(id);
        if (entry == null || entry.owner != user || !entry.mode.equals(mode) || !entry.kind.equals(kind)) fail(HttpStatus.NOT_FOUND, "not_found");
        entries.remove(id); return Map.of("deleted", true, "trainingOnly", true);
    }

    @PostMapping("/api/bounty/reset")
    public synchronized Map<String, Object> reset(HttpServletRequest request) {
        int user = login(request); csrf(request); int before = entries.size(); entries.values().removeIf(e -> e.owner == user);
        return Map.of("deleted", before - entries.size(), "trainingOnly", true);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> error(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("error", Objects.requireNonNullElse(exception.getReason(), "request error"), "trainingOnly", true));
    }
}
