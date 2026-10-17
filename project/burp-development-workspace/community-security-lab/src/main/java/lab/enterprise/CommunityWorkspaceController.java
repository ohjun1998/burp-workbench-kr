package lab.enterprise;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Shared community workflows. Only explicitly selected /vuln/ routes omit checks. */
@RestController
public class CommunityWorkspaceController {
    private final JdbcTemplate db;

    public CommunityWorkspaceController(CommunityController community, JdbcTemplate db) {
        this.db = db;
        ensure("community_account_states", "CREATE TABLE community_account_states (user_id INTEGER PRIMARY KEY, state VARCHAR(20))");
        ensure("community_workspace_seed", "CREATE TABLE community_workspace_seed (id INTEGER PRIMARY KEY)");
        ensure("community_reports", "CREATE TABLE community_reports (id INTEGER PRIMARY KEY, owner_id INTEGER, target_type VARCHAR(20), target_id VARCHAR(50), reason VARCHAR(1000), state VARCHAR(20), resolution VARCHAR(1000))");
        ensure("community_courses", "CREATE TABLE community_courses (id INTEGER PRIMARY KEY, title VARCHAR(160), description VARCHAR(2000), state VARCHAR(20))");
        ensure("community_applications", "CREATE TABLE community_applications (id INTEGER PRIMARY KEY, course_id INTEGER, owner_id INTEGER, reason VARCHAR(1000), state VARCHAR(20))");
        if (db.queryForObject("SELECT COUNT(*) FROM community_workspace_seed", Integer.class) == 0) {
            seed(); db.update("INSERT INTO community_workspace_seed VALUES (1)");
        }
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
        db.update("INSERT INTO community_courses VALUES (801,?,?, 'OPEN')", "웹 권한 검증 기초", "계정과 객체 소유권을 비교하는 실습 강의");
        db.update("INSERT INTO community_courses VALUES (802,?,?, 'OPEN')", "Burp 요청·응답 분석", "프록시와 Repeater를 사용하는 실습 강의");
        db.update("INSERT INTO community_applications VALUES (701,801,1,?, 'PENDING')", "ALICE_COURSE_PRIVATE: 수강 신청 사유");
        db.update("INSERT INTO community_applications VALUES (702,801,2,?, 'PENDING')", "BOB_COURSE_PRIVATE: 수강 신청 사유");
        db.update("INSERT INTO community_reports VALUES (601,1,'POST','105',?,'PENDING','')", "ALICE_REPORT_PRIVATE: 게시글 내용 검토 요청");
        db.update("INSERT INTO community_reports VALUES (602,2,'POST','101',?,'PENDING','')", "BOB_REPORT_PRIVATE: 게시글 내용 검토 요청");
    }
    private static void deny(HttpStatus status, String message) { throw new ResponseStatusException(status, message); }
    private static boolean fixed(String mode) {
        if (!Set.of("fixed", "vuln").contains(mode)) deny(HttpStatus.NOT_FOUND, "실습 모드 없음");
        return mode.equals("fixed");
    }
    private int uid(HttpServletRequest r) {
        var session = r.getSession(false);
        if (session == null || !(session.getAttribute("uid") instanceof Integer)) deny(HttpStatus.UNAUTHORIZED, "로그인 필요");
        int id = (Integer) session.getAttribute("uid");
        if (db.queryForObject("SELECT COUNT(*) FROM lab_users WHERE id=?", Integer.class, id) != 1) deny(HttpStatus.UNAUTHORIZED, "계정 없음");
        return id;
    }
    private boolean admin(int id) { return "ADMIN".equals(db.queryForObject("SELECT role FROM lab_users WHERE id=?", String.class, id)); }
    private int access(String mode, boolean management, HttpServletRequest r) {
        boolean secure = fixed(mode); int id = uid(r);
        if (secure && management && !admin(id)) deny(HttpStatus.FORBIDDEN, "관리자 권한 필요");
        return id;
    }
    private void csrf(String mode, HttpServletRequest r) {
        if (fixed(mode) && !Objects.equals(r.getSession().getAttribute("csrf"), r.getHeader("X-CSRF-Token"))) deny(HttpStatus.FORBIDDEN, "CSRF 토큰 불일치");
    }
    private static void text(String value, int max, String label) {
        if (value.isBlank() || value.length() > max) deny(HttpStatus.BAD_REQUEST, label + " 길이를 확인하세요.");
    }
    private int next(String table, int base) { return db.queryForObject("SELECT COALESCE(MAX(id),?) FROM " + table, Integer.class, base) + 1; }
    private Map<String,Object> row(String table, int id) {
        var rows = db.queryForList("SELECT * FROM " + table + " WHERE id=?", id);
        if (rows.isEmpty()) deny(HttpStatus.NOT_FOUND, "항목 없음");
        return rows.get(0);
    }
    private void owned(String mode, Map<String,Object> row, int user) {
        if (fixed(mode) && ((Number)row.get("OWNER_ID")).intValue() != user && !admin(user)) deny(HttpStatus.NOT_FOUND, "항목 없음");
    }

    @GetMapping("/api/workspace/{mode}/activity")
    public Map<String,Object> activity(@PathVariable String mode, HttpServletRequest r) {
        int id = access(mode, false, r);
        return Map.of("posts", db.queryForList("SELECT id,title,visibility FROM community_posts WHERE owner_id=? ORDER BY id DESC", id),
            "comments", db.queryForList("SELECT id,post_id,body FROM community_comments WHERE owner_id=? ORDER BY id", id),
            "reports", db.queryForList("SELECT * FROM community_reports WHERE owner_id=? ORDER BY id DESC", id),
            "applications", db.queryForList("SELECT a.*,c.title FROM community_applications a JOIN community_courses c ON c.id=a.course_id WHERE a.owner_id=? ORDER BY a.id DESC", id));
    }
    @GetMapping("/api/workspace/{mode}/reports/{id}")
    public Map<String,Object> report(@PathVariable String mode, @PathVariable int id, HttpServletRequest r) {
        int user = access(mode, false, r); var item = row("community_reports", id); owned(mode,item,user); return item;
    }
    @PostMapping("/api/workspace/{mode}/reports")
    public synchronized Map<String,Object> reportCreate(@PathVariable String mode, @RequestParam String targetType, @RequestParam String targetId, @RequestParam String reason, HttpServletRequest r) {
        int user = access(mode,false,r); csrf(mode,r); text(reason,1000,"신고 사유");
        if (!Set.of("POST","COMMENT").contains(targetType)) deny(HttpStatus.BAD_REQUEST,"신고 대상 종류 확인");
        int post;
        if (targetType.equals("POST")) {
            try { post = Integer.parseInt(targetId); } catch (NumberFormatException error) { deny(HttpStatus.BAD_REQUEST,"게시글 번호 확인"); return Map.of(); }
        } else {
            var comments = db.queryForList("SELECT post_id FROM community_comments WHERE id=?", targetId);
            if (comments.isEmpty()) deny(HttpStatus.NOT_FOUND,"댓글 없음");
            post = ((Number)comments.get(0).get("POST_ID")).intValue();
        }
        var item = row("community_posts",post);
        if (fixed(mode) && "PRIVATE".equals(item.get("VISIBILITY")) && ((Number)item.get("OWNER_ID")).intValue()!=user && !admin(user)) deny(HttpStatus.NOT_FOUND,"게시글 없음");
        int id=next("community_reports",600);
        db.update("INSERT INTO community_reports VALUES (?,?,?,?,?,'PENDING','')",id,user,targetType,targetId,reason);
        return Map.of("id",id,"state","PENDING");
    }
    @GetMapping("/api/workspace/{mode}/admin/reports")
    public List<Map<String,Object>> reports(@PathVariable String mode,HttpServletRequest r) {
        access(mode,true,r); return db.queryForList("SELECT r.*,u.username FROM community_reports r JOIN lab_users u ON u.id=r.owner_id ORDER BY r.id DESC");
    }
    @PostMapping("/api/workspace/{mode}/admin/reports/{id}/review")
    public synchronized Map<String,Object> reportReview(@PathVariable String mode,@PathVariable int id,@RequestParam String decision,@RequestParam String resolution,HttpServletRequest r) {
        access(mode,true,r); csrf(mode,r); text(resolution,1000,"처리 내용");
        if (!Set.of("RESOLVED","REJECTED").contains(decision)) deny(HttpStatus.BAD_REQUEST,"처리·반려 중 선택하세요.");
        row("community_reports",id);
        if(db.update("UPDATE community_reports SET state=?,resolution=? WHERE id=? AND state='PENDING'",decision,resolution,id)!=1) deny(HttpStatus.CONFLICT,"이미 처리된 신고입니다.");
        return Map.of("id",id,"state",decision);
    }
    @GetMapping("/api/workspace/{mode}/courses")
    public List<Map<String,Object>> courses(@PathVariable String mode) { fixed(mode); return db.queryForList("SELECT * FROM community_courses ORDER BY id DESC"); }
    @GetMapping("/api/workspace/{mode}/courses/{id}")
    public Map<String,Object> course(@PathVariable String mode,@PathVariable int id) {fixed(mode); return row("community_courses",id);}
    @PostMapping("/api/workspace/{mode}/admin/courses")
    public synchronized Map<String,Object> courseCreate(@PathVariable String mode,@RequestParam String title,@RequestParam String description,HttpServletRequest r) {
        access(mode,true,r); csrf(mode,r); text(title,160,"강의 제목");text(description,2000,"강의 소개");int id=next("community_courses",800);
        db.update("INSERT INTO community_courses VALUES (?,?,?,'OPEN')",id,title,description);return Map.of("id",id);
    }
    @PostMapping("/api/workspace/{mode}/admin/courses/{id}/edit")
    public synchronized Map<String,Object> courseEdit(@PathVariable String mode,@PathVariable int id,@RequestParam String title,@RequestParam String description,@RequestParam String state,HttpServletRequest r) {
        access(mode,true,r);csrf(mode,r);row("community_courses",id);text(title,160,"강의 제목");text(description,2000,"강의 소개");
        if(!Set.of("OPEN","CLOSED").contains(state))deny(HttpStatus.BAD_REQUEST,"강의 상태 확인");
        db.update("UPDATE community_courses SET title=?,description=?,state=? WHERE id=?",title,description,state,id);return Map.of("id",id);
    }
    @PostMapping("/api/workspace/{mode}/admin/courses/{id}/delete")
    public synchronized Map<String,Object> courseDelete(@PathVariable String mode,@PathVariable int id,HttpServletRequest r) {
        access(mode,true,r);csrf(mode,r);row("community_courses",id);
        db.update("DELETE FROM community_applications WHERE course_id=?",id);db.update("DELETE FROM community_courses WHERE id=?",id);return Map.of("deleted",id);
    }
    @PostMapping("/api/workspace/{mode}/courses/{id}/apply")
    public synchronized Map<String,Object> apply(@PathVariable String mode,@PathVariable int id,@RequestParam String reason,HttpServletRequest r) {
        int user=access(mode,false,r);csrf(mode,r);text(reason,1000,"신청 사유");var course=row("community_courses",id);
        if(!"OPEN".equals(course.get("STATE")))deny(HttpStatus.CONFLICT,"신청이 마감된 강의입니다.");
        if(db.queryForObject("SELECT COUNT(*) FROM community_applications WHERE course_id=? AND owner_id=? AND state IN ('PENDING','APPROVED')",Integer.class,id,user)>0)deny(HttpStatus.CONFLICT,"이미 신청한 강의입니다.");
        int key=next("community_applications",700);db.update("INSERT INTO community_applications VALUES (?,?,?,?,'PENDING')",key,id,user,reason);return Map.of("id",key);
    }
    @GetMapping("/api/workspace/{mode}/applications/{id}")
    public Map<String,Object> application(@PathVariable String mode,@PathVariable int id,HttpServletRequest r) {
        int user=access(mode,false,r);var item=row("community_applications",id);owned(mode,item,user);return item;
    }
    @PostMapping("/api/workspace/{mode}/applications/{id}/cancel")
    public synchronized Map<String,Object> cancel(@PathVariable String mode,@PathVariable int id,HttpServletRequest r) {
        int user=access(mode,false,r);csrf(mode,r);var item=row("community_applications",id);owned(mode,item,user);
        if(db.update("UPDATE community_applications SET state='CANCELLED' WHERE id=? AND state='PENDING'",id)!=1)deny(HttpStatus.CONFLICT,"대기 중인 신청만 취소할 수 있습니다.");return Map.of("id",id,"state","CANCELLED");
    }
    @GetMapping("/api/workspace/{mode}/admin/applications")
    public List<Map<String,Object>> applications(@PathVariable String mode,HttpServletRequest r) {
        access(mode,true,r);return db.queryForList("SELECT a.*,c.title,u.username FROM community_applications a JOIN community_courses c ON c.id=a.course_id JOIN lab_users u ON u.id=a.owner_id ORDER BY a.id DESC");
    }
    @PostMapping("/api/workspace/{mode}/admin/applications/{id}/review")
    public synchronized Map<String,Object> review(@PathVariable String mode,@PathVariable int id,@RequestParam String decision,HttpServletRequest r) {
        access(mode,true,r);csrf(mode,r);if(!Set.of("APPROVED","REJECTED").contains(decision))deny(HttpStatus.BAD_REQUEST,"수락·거절 중 선택하세요.");row("community_applications",id);
        if(db.update("UPDATE community_applications SET state=? WHERE id=? AND state='PENDING'",decision,id)!=1)deny(HttpStatus.CONFLICT,"이미 처리된 신청입니다.");return Map.of("id",id,"state",decision);
    }
    @GetMapping("/api/workspace/{mode}/admin/users")
    public List<Map<String,Object>> users(@PathVariable String mode,HttpServletRequest r) {
        access(mode,true,r);return db.queryForList("SELECT u.id,u.username,u.role,u.email,COALESCE(s.state,'ACTIVE') AS state FROM lab_users u LEFT JOIN community_account_states s ON s.user_id=u.id ORDER BY u.id");
    }
    @PostMapping("/api/workspace/{mode}/admin/users/{id}/edit")
    public synchronized Map<String,Object> userEdit(@PathVariable String mode,@PathVariable int id,@RequestParam String role,@RequestParam String state,@RequestParam(required=false) String email,HttpServletRequest r) {
        access(mode,true,r);csrf(mode,r);row("lab_users",id);
        if(!Set.of("USER","ADMIN","MODERATOR").contains(role)||!Set.of("ACTIVE","SUSPENDED").contains(state))deny(HttpStatus.BAD_REQUEST,"역할·계정 상태 확인");
        if(email!=null&&!email.matches("[A-Za-z0-9._+-]{1,80}@[A-Za-z0-9.-]{1,60}"))deny(HttpStatus.BAD_REQUEST,"이메일 형식 확인");
        if(id==3)deny(HttpStatus.CONFLICT,"실습 복구용 기본 관리자 계정은 유지합니다.");
        db.update("UPDATE lab_users SET role=? WHERE id=?",role,id);
        if(email!=null)db.update("UPDATE lab_users SET email=? WHERE id=?",email,id);
        db.update("DELETE FROM community_account_states WHERE user_id=?",id);db.update("INSERT INTO community_account_states VALUES (?,?)",id,state);return Map.of("id",id,"role",role,"state",state);
    }
    @GetMapping("/api/workspace/{mode}/admin/posts")
    public List<Map<String,Object>> posts(@PathVariable String mode,HttpServletRequest r) {access(mode,true,r);return db.queryForList("SELECT p.id,p.owner_id,p.title,p.visibility,p.category,u.username FROM community_posts p JOIN lab_users u ON u.id=p.owner_id ORDER BY p.id DESC");}
    @GetMapping("/api/workspace/{mode}/admin/comments")
    public List<Map<String,Object>> comments(@PathVariable String mode,HttpServletRequest r) {access(mode,true,r);return db.queryForList("SELECT c.*,u.username FROM community_comments c JOIN lab_users u ON u.id=c.owner_id ORDER BY c.id");}
    @GetMapping("/api/workspace/{mode}/debug/config")
    public Map<String,Object> config(@PathVariable String mode,HttpServletRequest r) {
        access(mode,false,r);if(fixed(mode))deny(HttpStatus.FORBIDDEN,"내부 설정은 공개하지 않습니다.");
        return Map.of("trainingOnly",true,"apiKey","LAB_FAKE_KEY_DO_NOT_USE_2026","token","LAB_FAKE_TOKEN_2026","database","lab-db.invalid","note","실제 서비스에 사용할 수 없는 합성 값입니다.");
    }
    @PostMapping("/api/workspace/reset")
    public synchronized Map<String,Object> reset(HttpServletRequest r) {
        access("fixed",true,r);csrf("fixed",r);
        for(String table:List.of("community_applications","community_reports","community_courses","community_account_states"))db.update("DELETE FROM "+table);
        db.update("UPDATE lab_users SET role='USER' WHERE id IN (1,2)");db.update("UPDATE lab_users SET role='ADMIN' WHERE id=3");db.update("UPDATE lab_users SET role='MODERATOR' WHERE id=4");seed();return Map.of("reset",true);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> error(ResponseStatusException error) {return ResponseEntity.status(error.getStatusCode()).header("Cache-Control","no-store").body(Map.of("error",Objects.requireNonNullElse(error.getReason(),"요청 오류"),"trainingOnly",true));}
}
