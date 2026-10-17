package lab.enterprise;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Role-separated local training portals. Only explicit /vuln/ APIs omit authorization. */
@RestController
public class RolePortalController {
    private final JdbcTemplate db;

    public RolePortalController(CommunityController community, JdbcTemplate db) {
        this.db = db;
        ensure("portal_requests", "CREATE TABLE portal_requests (id INTEGER PRIMARY KEY, owner_id INTEGER, subject VARCHAR(160), body VARCHAR(2000), state VARCHAR(20))");
        ensure("portal_settings", "CREATE TABLE portal_settings (id INTEGER PRIMARY KEY, notice VARCHAR(500))");
        if (db.queryForObject("SELECT COUNT(*) FROM portal_requests", Integer.class) == 0) seed();
        if (db.queryForObject("SELECT COUNT(*) FROM portal_settings", Integer.class) == 0)
            db.update("INSERT INTO portal_settings VALUES (1,?)", "LOOP 서비스 요청 센터에 오신 것을 환영합니다.");
    }

    private void ensure(String table, String ddl) {
        try { db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        catch (org.springframework.jdbc.BadSqlGrammarException error) {
            int code = error.getSQLException().getErrorCode();
            if (code != 942 && code != 42102 && code != 42104) throw error;
            db.execute(ddl);
        }
    }

    private void seed() {
        db.update("INSERT INTO portal_requests VALUES (501,1,?,?, 'PENDING')", "개발 환경 접근 신청", "ALICE_REQUEST_PRIVATE: Alice의 개발 환경 신청 사유");
        db.update("INSERT INTO portal_requests VALUES (502,2,?,?, 'PENDING')", "프로젝트 자료 열람 신청", "BOB_REQUEST_PRIVATE: Bob의 프로젝트 신청 사유");
        db.update("INSERT INTO portal_requests VALUES (503,1,?,?, 'APPROVED')", "교육 수강 신청", "ALICE_APPROVED_PRIVATE: 승인된 교육 신청");
    }

    private static void deny(HttpStatus status, String message) { throw new ResponseStatusException(status, message); }
    private static boolean fixed(String mode) {
        if (!Set.of("fixed", "vuln").contains(mode)) deny(HttpStatus.NOT_FOUND, "실습 모드 없음");
        return mode.equals("fixed");
    }
    private int uid(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute("uid") instanceof Integer)) deny(HttpStatus.UNAUTHORIZED, "로그인 필요");
        int id = (Integer) session.getAttribute("uid");
        if (db.queryForObject("SELECT COUNT(*) FROM lab_users WHERE id=?", Integer.class, id) != 1)
            deny(HttpStatus.UNAUTHORIZED, "다시 로그인하세요.");
        return id;
    }
    private String role(int id) { return db.queryForObject("SELECT role FROM lab_users WHERE id=?", String.class, id); }
    private int access(String mode, String expectedRole, HttpServletRequest request) {
        boolean secure = fixed(mode);
        int id = uid(request);
        if (secure && !expectedRole.equals(role(id))) deny(HttpStatus.FORBIDDEN, expectedRole.equals("ADMIN") ? "관리자 전용 기능" : "일반 사용자 전용 기능");
        return id;
    }
    private void csrf(String mode, HttpServletRequest request) {
        if (fixed(mode) && !Objects.equals(request.getSession().getAttribute("csrf"), request.getHeader("X-CSRF-Token")))
            deny(HttpStatus.FORBIDDEN, "CSRF 토큰 불일치");
    }
    private Map<String, Object> item(int id) {
        var rows = db.queryForList("SELECT r.*,u.username FROM portal_requests r JOIN lab_users u ON r.owner_id=u.id WHERE r.id=?", id);
        if (rows.isEmpty()) deny(HttpStatus.NOT_FOUND, "요청 없음");
        return rows.get(0);
    }
    private static String resource(String name) throws IOException {
        try (var stream = RolePortalController.class.getResourceAsStream("/static/" + name)) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private ResponseEntity<String> page(String area, boolean login, HttpServletRequest request) throws IOException {
        if (!login) {
            var session = request.getSession(false);
            if (session == null || session.getAttribute("uid") == null)
                return ResponseEntity.status(302).header("Location", "/" + area + "/login").header("Cache-Control", "no-store").build();
            access("fixed", area.equals("admin") ? "ADMIN" : "USER", request);
        }
        String title = area.equals("admin") ? "관리자 센터" : "사용자 센터";
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")
            .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
            .body(resource(login ? "role-login.html" : "role-portal.html").replace("__AREA__", area).replace("__TITLE__", title));
    }
    @GetMapping({"/admin", "/admin/"}) public ResponseEntity<String> adminPage(HttpServletRequest r) throws IOException { return page("admin", false, r); }
    @GetMapping({"/user", "/user/"}) public ResponseEntity<String> userPage(HttpServletRequest r) throws IOException { return page("user", false, r); }
    @GetMapping("/admin/login") public ResponseEntity<String> adminLogin(HttpServletRequest r) throws IOException { return page("admin", true, r); }
    @GetMapping("/user/login") public ResponseEntity<String> userLogin(HttpServletRequest r) throws IOException { return page("user", true, r); }

    @GetMapping("/api/access/me") public Map<String, Object> me(HttpServletRequest r) {
        int id = uid(r);
        return Map.of("user", db.queryForMap("SELECT id,username,role,email FROM lab_users WHERE id=?", id), "csrf", r.getSession().getAttribute("csrf"), "trainingOnly", true);
    }
    @GetMapping("/api/access/{mode}/user/dashboard") public Map<String, Object> dashboard(@PathVariable String mode, HttpServletRequest r) {
        int id = access(mode, "USER", r);
        return Map.of("requests", db.queryForList("SELECT id,owner_id,subject,state FROM portal_requests WHERE owner_id=? ORDER BY id DESC", id),
            "notice", db.queryForObject("SELECT notice FROM portal_settings WHERE id=1", String.class));
    }
    @GetMapping("/api/access/{mode}/user/requests/{id}") public Map<String, Object> detail(@PathVariable String mode, @PathVariable int id, HttpServletRequest r) {
        int owner = access(mode, "USER", r);
        var row = item(id);
        if (fixed(mode) && ((Number) row.get("OWNER_ID")).intValue() != owner) deny(HttpStatus.NOT_FOUND, "요청 없음");
        return row;
    }
    @PostMapping("/api/access/{mode}/user/requests") public synchronized Map<String, Object> create(@PathVariable String mode, @RequestParam String subject, @RequestParam String body, HttpServletRequest r) {
        int owner = access(mode, "USER", r); csrf(mode, r);
        if (subject.isBlank() || subject.length() > 160 || body.isBlank() || body.length() > 2000) deny(HttpStatus.BAD_REQUEST, "제목 1~160자·내용 1~2000자를 입력하세요.");
        int id = db.queryForObject("SELECT COALESCE(MAX(id),500) FROM portal_requests", Integer.class) + 1;
        db.update("INSERT INTO portal_requests VALUES (?,?,?,?, 'PENDING')", id, owner, subject, body);
        return Map.of("id", id, "state", "PENDING");
    }
    @PostMapping("/api/access/{mode}/user/requests/{id}/cancel") public synchronized Map<String, Object> cancel(@PathVariable String mode, @PathVariable int id, HttpServletRequest r) {
        int owner = access(mode, "USER", r); csrf(mode, r);
        var row = item(id);
        if (fixed(mode) && ((Number) row.get("OWNER_ID")).intValue() != owner) deny(HttpStatus.NOT_FOUND, "요청 없음");
        if (db.update("UPDATE portal_requests SET state='CANCELLED' WHERE id=? AND state='PENDING'", id) != 1) deny(HttpStatus.CONFLICT, "대기 중인 요청만 취소할 수 있습니다.");
        return Map.of("id", id, "state", "CANCELLED");
    }
    @GetMapping("/api/access/{mode}/admin/members") public List<Map<String, Object>> members(@PathVariable String mode, HttpServletRequest r) {
        access(mode, "ADMIN", r);
        return db.queryForList("SELECT id,username,role,email FROM lab_users ORDER BY id");
    }
    @GetMapping("/api/access/{mode}/admin/review-queue") public List<Map<String, Object>> queue(@PathVariable String mode, HttpServletRequest r) {
        access(mode, "ADMIN", r);
        return db.queryForList("SELECT r.*,u.username FROM portal_requests r JOIN lab_users u ON u.id=r.owner_id ORDER BY r.id DESC");
    }
    @PostMapping("/api/access/{mode}/admin/requests/{id}/review") public synchronized Map<String, Object> review(@PathVariable String mode, @PathVariable int id, @RequestParam String decision, HttpServletRequest r) {
        access(mode, "ADMIN", r); csrf(mode, r);
        if (!Set.of("APPROVED", "REJECTED").contains(decision)) deny(HttpStatus.BAD_REQUEST, "승인·반려 중 선택하세요.");
        item(id);
        if (db.update("UPDATE portal_requests SET state=? WHERE id=? AND state='PENDING'", decision, id) != 1) deny(HttpStatus.CONFLICT, "이미 처리된 요청입니다.");
        return Map.of("id", id, "state", decision);
    }
    @GetMapping("/api/access/{mode}/admin/settings") public Map<String, Object> settings(@PathVariable String mode, HttpServletRequest r) {
        access(mode, "ADMIN", r);
        return Map.of("notice", db.queryForObject("SELECT notice FROM portal_settings WHERE id=1", String.class), "policy", "ADMIN_ONLY", "trainingOnly", true);
    }
    @PostMapping("/api/access/{mode}/admin/settings") public Map<String, Object> settingsWrite(@PathVariable String mode, @RequestParam String notice, HttpServletRequest r) {
        access(mode, "ADMIN", r); csrf(mode, r);
        if (notice.isBlank() || notice.length() > 500) deny(HttpStatus.BAD_REQUEST, "공지 1~500자를 입력하세요.");
        db.update("UPDATE portal_settings SET notice=? WHERE id=1", notice);
        return Map.of("saved", true);
    }
    @PostMapping("/api/access/reset") public synchronized Map<String, Object> reset(HttpServletRequest r) {
        access("fixed", "ADMIN", r); csrf("fixed", r);
        db.update("DELETE FROM portal_requests"); seed();
        db.update("UPDATE portal_settings SET notice=? WHERE id=1", "LOOP 서비스 요청 센터에 오신 것을 환영합니다.");
        return Map.of("reset", true);
    }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String, Object>> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).header("Cache-Control", "no-store")
            .body(Map.of("error", Objects.requireNonNullElse(error.getReason(), "요청 오류"), "trainingOnly", true));
    }
}
