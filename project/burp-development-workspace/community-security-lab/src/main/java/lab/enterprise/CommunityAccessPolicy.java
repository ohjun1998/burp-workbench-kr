package lab.enterprise;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** RBAC and object relationships shared by the normal community workflows. */
public final class CommunityAccessPolicy {
    public enum Permission {
        PUBLIC("public", "공개 정보 조회"), MEMBER("member:read", "회원 서비스 이용"),
        POST_WRITE("post:write", "내 게시글 작성·관리"), COMMENT_WRITE("comment:write", "내 댓글 작성·관리"),
        MESSAGE_USE("message:use", "내 쪽지 이용"), PROFILE_EDIT("profile:edit", "내 정보 수정"),
        REPORT_CREATE("report:create", "신고 접수·내역 조회"), COURSE_APPLY("course:apply", "강의 신청·내역 조회"),
        REQUEST_USE("request:use", "내 서비스 요청 관리"), BOARD_MODERATE("board:moderate", "담당 보안 게시판 관리"),
        USER_MANAGE("user:manage", "사용자 관리"), CONTENT_MANAGE("content:manage", "전체 게시글·댓글 관리"),
        REPORT_REVIEW("report:review", "신고 처리"), COURSE_MANAGE("course:manage", "강의 관리"),
        COURSE_REVIEW("course:review", "수강 신청 검토"), REQUEST_REVIEW("request:review", "서비스 요청 검토"),
        SETTINGS_MANAGE("settings:manage", "공지 관리"), LAB_RESET("lab:reset", "실습 데이터 복원"),
        POLICY_READ("policy:read", "권한 정책·접근 기록 조회");

        public final String id, label;
        Permission(String id, String label) { this.id=id; this.label=label; }
    }
    public record Principal(int id, String username, String role, Set<Permission> permissions) {}
    private static final String PRINCIPAL=CommunityAccessPolicy.class.getName()+".principal";
    static final String DECLARED=CommunityAccessPolicy.class.getName()+".declared";
    private static final Set<Permission> COMMON=Collections.unmodifiableSet(EnumSet.range(Permission.MEMBER,Permission.REQUEST_USE));
    private final JdbcTemplate db;
    private final ArrayDeque<Map<String,Object>> audit=new ArrayDeque<>();
    public CommunityAccessPolicy(JdbcTemplate db) { this.db=db; }
    static void deny(HttpStatus status,String reason) { throw new ResponseStatusException(status,reason); }
    public static boolean knownRole(String role) { return role!=null&&Set.of("USER","MODERATOR","ADMIN").contains(role); }
    public Set<Permission> forRole(String role) {
        if("ADMIN".equals(role)) { var all=EnumSet.allOf(Permission.class);all.remove(Permission.PUBLIC);return Collections.unmodifiableSet(all); }
        if("MODERATOR".equals(role)) { var permissions=EnumSet.copyOf(COMMON);permissions.add(Permission.BOARD_MODERATE);return Collections.unmodifiableSet(permissions); }
        return "USER".equals(role)?COMMON:Set.of();
    }
    public Principal current(HttpServletRequest request) {
        if(request.getAttribute(PRINCIPAL) instanceof Principal principal)return principal;
        var session=request.getSession(false);
        if(session==null||!(session.getAttribute("uid") instanceof Integer id))return remember(request,new Principal(0,"","GUEST",Set.of()));
        var rows=db.queryForList("SELECT u.id,u.username,u.role,COALESCE(s.state,'ACTIVE') AS state FROM lab_users u LEFT JOIN community_account_states s ON s.user_id=u.id WHERE u.id=?",id);
        if(rows.isEmpty()){session.invalidate();deny(HttpStatus.UNAUTHORIZED,"다시 로그인하세요.");}
        var row=rows.get(0);
        if("SUSPENDED".equals(row.get("STATE"))&&!path(request).equals("/api/auth/logout")) {session.invalidate();deny(HttpStatus.FORBIDDEN,"정지된 계정입니다.");}
        String role=(String)row.get("ROLE");
        if(!knownRole(role)){session.invalidate();deny(HttpStatus.FORBIDDEN,"허용되지 않은 계정 권한입니다.");}
        return remember(request,new Principal(id,(String)row.get("USERNAME"),role,forRole(role)));
    }
    private Principal remember(HttpServletRequest request,Principal principal) {request.setAttribute(PRINCIPAL,principal);return principal;}
    public Principal require(HttpServletRequest request,Permission permission) {
        var principal=current(request);
        if(principal.id()==0)deny(HttpStatus.UNAUTHORIZED,"로그인 후 이용하세요.");
        if(!principal.permissions().contains(permission))deny(HttpStatus.FORBIDDEN,"이 기능을 사용할 권한이 없습니다.");
        return principal;
    }
    public void declared(HttpServletRequest request) {
        if(!(request.getAttribute(DECLARED) instanceof Permission))deny(HttpStatus.FORBIDDEN,"권한 정책이 등록되지 않은 기능입니다.");
        require(request,(Permission)request.getAttribute(DECLARED));
    }
    public void csrf(HttpServletRequest request) {
        require(request,Permission.MEMBER);
        String token=request.getHeader("X-CSRF-Token");
        if(token==null&&path(request).equals("/api/auth/logout"))token=request.getParameter("csrf");
        if(token==null||!Objects.equals(token,request.getSession(false).getAttribute("csrf")))deny(HttpStatus.FORBIDDEN,"CSRF 토큰 불일치");
    }
    public String role(int id) {
        if(id==0)return "GUEST";
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes&&attributes.getRequest().getAttribute(PRINCIPAL) instanceof Principal principal&&principal.id()==id)return principal.role();
        return db.queryForObject("SELECT role FROM lab_users WHERE id=?",String.class,id);
    }
    public boolean has(int id,Permission permission) {return id!=0&&forRole(role(id)).contains(permission);}
    public boolean ownOrManage(int id,Object owner,Permission management) {
        return id!=0&&(((Number)owner).intValue()==id||has(id,management));
    }
    public boolean readPost(Map<String,Object> post,int id) {
        return "PUBLIC".equals(post.get("VISIBILITY"))||("MEMBERS".equals(post.get("VISIBILITY"))&&has(id,Permission.MEMBER))||ownOrManage(id,post.get("OWNER_ID"),Permission.CONTENT_MANAGE);
    }
    public boolean managePost(Map<String,Object> post,int id) {
        return ownOrManage(id,post.get("OWNER_ID"),Permission.CONTENT_MANAGE)||(has(id,Permission.BOARD_MODERATE)&&"security".equals(post.get("CATEGORY"))&&!"PRIVATE".equals(post.get("VISIBILITY")));
    }
    public List<String> permissionIds(int id) {return forRole(role(id)).stream().map(p->p.id).toList();}
    public Map<String,String> labels() {
        var labels=new LinkedHashMap<String,String>();for(var permission:Permission.values())if(permission!=Permission.PUBLIC)labels.put(permission.id,permission.label);return labels;
    }
    public Map<String,Object> matrix() {
        return Map.of("roles",List.of("USER","MODERATOR","ADMIN").stream().map(role->Map.of("role",role,"permissions",forRole(role).stream().map(p->p.id).toList())).toList(),"labels",labels(),"defaultDeny",true);
    }
    public static String path(HttpServletRequest request) {return request.getRequestURI().substring(request.getContextPath().length());}
    public synchronized void record(HttpServletRequest request,String outcome,int status) {
        var principal=request.getAttribute(PRINCIPAL) instanceof Principal p?p:new Principal(0,"","GUEST",Set.of());
        String path=path(request);if(path.length()>300)path=path.substring(0,300);
        audit.addFirst(Map.of("time",Instant.now().toString(),"userId",principal.id(),"username",principal.username(),"role",principal.role(),"method",request.getMethod(),"path",path,"outcome",outcome,"status",status));
        while(audit.size()>200)audit.removeLast();
    }
    public synchronized Map<String,Object> audit() {return Map.of("items",List.copyOf(audit),"capacity",200,"persistent",false);}
}
