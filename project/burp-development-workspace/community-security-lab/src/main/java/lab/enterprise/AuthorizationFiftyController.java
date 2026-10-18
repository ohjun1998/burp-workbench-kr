package lab.enterprise;

import static lab.enterprise.CommunityAccessPolicy.Permission.PUBLIC;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Fifty real fixture operations. Authentication and per-operation policy are checked centrally here. */
@RestController
public final class AuthorizationFiftyController {
    enum Policy { PUBLIC, MEMBER, ADMIN, ALICE, BOB, PRIVATE_ALICE, PRIVATE_BOB, TEAM_A, TEAM_B, PROJECT }
    record Feature(int id,String group,String name,String method,String path,Policy policy,String field,String value) {
        boolean write(){return !method.equals("GET");}
        String key(){if(id==7||id==8||id==15)return "post-701";return path;}
        int owner(){return switch(policy){case ALICE,PRIVATE_ALICE,TEAM_A->1;case BOB,PRIVATE_BOB,TEAM_B->2;default->3;};}
    }
    private static Feature f(int id,String group,String name,String method,String path,Policy policy,String field,String value){return new Feature(id,group,name,method,path,policy,field,value);}
    // Business operations are deliberately declared independently of audit/auth50/expected-policy.csv.
    private static final List<Feature> FEATURES=List.of(
      f(1,"커뮤니티","공개 게시글 목록","GET","feed",Policy.PUBLIC,"posts","PUBLIC_POST_LIST"),
      f(2,"커뮤니티","공지사항 조회","GET","announcements",Policy.PUBLIC,"announcements","PUBLIC_NOTICE"),
      f(3,"커뮤니티","공개 도움말 조회","GET","help/articles/101",Policy.PUBLIC,"article","PUBLIC_HELP_ARTICLE"),
      f(4,"커뮤니티","공개 주제 검색","GET","search/topics",Policy.PUBLIC,"topics","PUBLIC_SEARCH_RESULT"),
      f(5,"커뮤니티","회원 디렉터리 조회","GET","members/directory",Policy.MEMBER,"profiles","REDACTED_MEMBER_DIRECTORY"),
      f(6,"커뮤니티","게시글 작성","POST","posts",Policy.MEMBER,"createdPosts","increment"),
      f(7,"커뮤니티","Alice 비공개 글 조회","GET","posts/701",Policy.ALICE,"content","ALICE_PRIVATE_POST"),
      f(8,"커뮤니티","Alice 임시 글 편집","PATCH","posts/701",Policy.ALICE,"content","EDITED_DRAFT"),
      f(9,"커뮤니티","Bob 게시글 삭제","DELETE","posts/702",Policy.BOB,"deleted","true"),
      f(10,"운영","신고 게시글 숨김","POST","moderation/posts/703/hide",Policy.ADMIN,"visibility","HIDDEN"),
      f(11,"댓글","Bob 비공개 댓글 조회","GET","comments/801",Policy.BOB,"comment","BOB_PRIVATE_COMMENT"),
      f(12,"댓글","Alice 댓글 편집","PATCH","comments/802",Policy.ALICE,"comment","EDITED_COMMENT"),
      f(13,"댓글","Bob 댓글 삭제","DELETE","comments/803",Policy.BOB,"deleted","true"),
      f(14,"운영","토론 상단 고정","POST","discussions/704/pin",Policy.ADMIN,"pinned","true"),
      f(15,"커뮤니티","Alice 글 수정 이력 조회","GET","posts/701/history",Policy.ALICE,"history","ALICE_POST_HISTORY"),
      f(16,"개인정보","Alice 비공개 프로필 조회","GET","profiles/alice/private",Policy.ALICE,"profile","ALICE_PRIVATE_PROFILE"),
      f(17,"개인정보","Bob 연락처 변경","PATCH","profiles/bob/contact",Policy.BOB,"email","bob.changed@example.test"),
      f(18,"개인정보","Alice 개인정보 내보내기","GET","privacy/alice/export",Policy.PRIVATE_ALICE,"personalData","ALICE_PERSONAL_EXPORT"),
      f(19,"보안","Bob 등록 기기 해제","POST","devices/bob/revoke",Policy.PRIVATE_BOB,"revokedDevices","increment"),
      f(20,"보안","Alice API 키 재발급","POST","keys/alice/rotate",Policy.PRIVATE_ALICE,"keyVersion","increment"),
      f(21,"개인정보","Bob 알림함 조회","GET","notifications/bob",Policy.PRIVATE_BOB,"notifications","BOB_PRIVATE_MESSAGES"),
      f(22,"개인정보","Alice 알림 읽음 처리","POST","notifications/alice/read",Policy.PRIVATE_ALICE,"readNotifications","increment"),
      f(23,"개인정보","Bob 저장 검색 삭제","DELETE","saved-searches/bob/901",Policy.PRIVATE_BOB,"deleted","true"),
      f(24,"보안","Alice 보안 이력 조회","GET","security/alice/audit",Policy.PRIVATE_ALICE,"securityEvents","ALICE_SECURITY_HISTORY"),
      f(25,"운영","사용자 이용 제한","POST","administration/accounts/42/lock",Policy.ADMIN,"accountState","LOCKED"),
      f(26,"교육","공개 강의 목록","GET","courses/catalog",Policy.PUBLIC,"courses","PUBLIC_COURSE_CATALOG"),
      f(27,"교육","팀 A 강의 자료 조회","GET","courses/team-a/lesson/11",Policy.TEAM_A,"lesson","TEAM_A_PRIVATE_LESSON"),
      f(28,"교육","강의 수강 신청","POST","enrollments",Policy.MEMBER,"enrollments","increment"),
      f(29,"교육","Alice 수강 신청 취소","POST","enrollments/alice/31/cancel",Policy.ALICE,"enrollmentState","CANCELLED"),
      f(30,"교육","Bob 수강 승인","POST","enrollments/bob/32/approve",Policy.ADMIN,"enrollmentState","APPROVED"),
      f(31,"교육","Bob 수료증 조회","GET","certificates/bob/41",Policy.BOB,"certificate","BOB_CERTIFICATE"),
      f(32,"행사","Alice 행사 참가 등록","POST","events/alice/51/register",Policy.ALICE,"registrations","increment"),
      f(33,"행사","Bob 행사 예약 취소","DELETE","events/bob/52/reservation",Policy.BOB,"reservationState","CANCELLED"),
      f(34,"결제","Alice 결제 환불","POST","orders/alice/61/refund",Policy.ALICE,"refundRequests","increment"),
      f(35,"결제","Bob 결제 청구서 조회","GET","invoices/bob/62",Policy.BOB,"invoice","BOB_PRIVATE_INVOICE"),
      f(36,"조직","팀 A 비공개 문서 조회","GET","teams/a/documents/71",Policy.TEAM_A,"document","TEAM_A_PRIVATE_DOCUMENT"),
      f(37,"조직","팀 B 위키 편집","PATCH","teams/b/wiki/72",Policy.TEAM_B,"wiki","EDITED_TEAM_B_WIKI"),
      f(38,"조직","팀 A 구성원 초대","POST","teams/a/invitations",Policy.TEAM_A,"invitations","increment"),
      f(39,"조직","팀 B 구성원 내보내기","DELETE","teams/b/members/73",Policy.TEAM_B,"membershipState","REMOVED"),
      f(40,"조직","팀 A 보고서 내보내기","GET","teams/a/reports/export",Policy.TEAM_A,"report","TEAM_A_PRIVATE_REPORT"),
      f(41,"파일","Alice 첨부파일 등록","POST","attachments/alice",Policy.ALICE,"attachments","increment"),
      f(42,"파일","Bob 비공개 파일 다운로드","GET","files/bob/82/download",Policy.BOB,"fileContent","BOB_PRIVATE_FILE"),
      f(43,"파일","Alice 파일 공유 링크 생성","POST","files/alice/83/share",Policy.ALICE,"shareLinkVersion","increment"),
      f(44,"파일","Bob 파일 공유 링크 폐기","DELETE","files/bob/84/share",Policy.BOB,"shareState","REVOKED"),
      f(45,"협업","공동 프로젝트 보드 조회","GET","projects/shared/board",Policy.PROJECT,"board","MEMBER_SHARED_BOARD"),
      f(46,"운영","관리자 사용 현황 조회","GET","administration/usage",Policy.ADMIN,"usage","ADMIN_USAGE_STATISTICS"),
      f(47,"운영","전체 회원 정보 내보내기","GET","administration/members/export",Policy.ADMIN,"members","PRIVATE_MEMBER_EXPORT"),
      f(48,"운영","게시판 운영 역할 부여","PUT","administration/roles/43",Policy.ADMIN,"assignedRole","MODERATOR"),
      f(49,"지원","Alice 문의 내용 조회","GET","support/alice/91",Policy.ALICE,"ticket","ALICE_PRIVATE_SUPPORT_TICKET"),
      f(50,"지원","Bob 문의 완료 처리","POST","support/bob/92/close",Policy.BOB,"ticketState","CLOSED")
    );
    private final AuthenticationLab auth;
    private final JdbcTemplate db;
    private final ObjectMapper json=new ObjectMapper();
    public AuthorizationFiftyController(CommunityController seeded,AuthenticationLab auth,JdbcTemplate db){
        this.auth=auth;this.db=db;
        try{db.queryForObject("SELECT COUNT(*) FROM auth50_state",Integer.class);}catch(org.springframework.jdbc.BadSqlGrammarException e){int code=e.getSQLException().getErrorCode();if(code!=942&&code!=42102&&code!=42104)throw e;db.execute("CREATE TABLE auth50_state (profile VARCHAR(30), mode_name VARCHAR(10), resource_key VARCHAR(180), payload CLOB, revision INTEGER, PRIMARY KEY(profile,mode_name,resource_key))");}
    }
    private static void mode(String mode){if(!Set.of("fixed","vuln").contains(mode))throw AuthenticationLab.failure(HttpStatus.NOT_FOUND,"MODE_NOT_FOUND");}
    private static boolean allowed(Feature f,AuthenticationLab.Identity identity){
        if(f.policy()==Policy.PUBLIC)return true;if(identity==null)return false;
        var p=identity.principal();int user=p.id();boolean admin=p.role().equals("ADMIN");
        return switch(f.policy()){case PUBLIC,MEMBER->true;case ADMIN->admin;case ALICE,TEAM_A->admin||user==1;case BOB,TEAM_B->admin||user==2;case PRIVATE_ALICE->user==1;case PRIVATE_BOB->user==2;case PROJECT->user==1||user==2;};
    }
    private AuthenticationLab.Identity identity(String profile,HttpServletRequest request,boolean optional){
        AuthenticationLab.Profile.of(profile);
        boolean supplied=request.getHeader("Authorization")!=null||request.getHeader("X-Auth-Token")!=null||Collections.list(request.getHeaders("Cookie")).stream().anyMatch(c->c.contains(AuthenticationLab.COOKIE+"="));
        if(optional&&!supplied)return null;var identity=auth.authenticate(profile,request);request.setAttribute(AuthenticationLab.IDENTITY,identity);return identity;
    }
    private Map<String,Object> read(String profile,String mode,Feature f)throws Exception{
        var values=db.queryForList("SELECT revision FROM auth50_state WHERE profile=? AND mode_name=? AND resource_key=?",profile,mode,f.key());
        if(values.isEmpty()){
            Map<String,Object> initial=new LinkedHashMap<>();initial.put("ownerId",f.owner());initial.put("content","INITIAL_PRIVATE_RECORD");initial.put("state","PENDING");initial.put("createdPosts",0);initial.put("eventCount",0);
            db.update("INSERT INTO auth50_state VALUES (?,?,?,?,0)",profile,mode,f.key(),json.writeValueAsString(initial));
        }
        String body=db.queryForObject("SELECT payload FROM auth50_state WHERE profile=? AND mode_name=? AND resource_key=?",String.class,profile,mode,f.key());
        Map<String,Object> value=json.readValue(body,new TypeReference<LinkedHashMap<String,Object>>(){});value.put("revision",db.queryForObject("SELECT revision FROM auth50_state WHERE profile=? AND mode_name=? AND resource_key=?",Integer.class,profile,mode,f.key()));return value;
    }
    private static Feature byId(int id){return FEATURES.stream().filter(f->f.id()==id).findFirst().orElseThrow(()->AuthenticationLab.failure(HttpStatus.NOT_FOUND,"FEATURE_NOT_FOUND"));}
    @GetMapping(value="/auth-50",produces="text/html;charset=UTF-8")
    public String page(HttpServletResponse response)throws Exception{
        response.setHeader("Cache-Control","no-store");response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'");try(var in=getClass().getResourceAsStream("/static/auth-50.html")){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}
    }
    @AccessRule(PUBLIC) @GetMapping("/api/auth-lab/bench/catalog")
    public Map<String,Object> catalog(){return Map.of("version","3.10.0","trainingOnly",true,"features",FEATURES.stream().map(f->Map.of("id",f.id(),"group",f.group(),"name",f.name(),"method",f.method(),"path",f.path(),"policy",f.policy().name(),"write",f.write())).toList());}
    @AccessRule(PUBLIC) @GetMapping("/api/auth-lab/{profile}/bench/inspect/{id}")
    public synchronized Map<String,Object> inspect(@PathVariable String profile,@PathVariable int id,@RequestParam String mode,HttpServletRequest request)throws Exception{
        mode(mode);var identity=identity(profile,request,false);auth.require(identity,CommunityAccessPolicy.Permission.USER_MANAGE);return Map.of("data",read(profile,mode,byId(id)),"trainingOnly",true);
    }
    @AccessRule(PUBLIC) @GetMapping("/api/auth-lab/{profile}/{mode}/bench-state/{id}")
    public synchronized Map<String,Object> state(@PathVariable String profile,@PathVariable String mode,@PathVariable int id,HttpServletRequest request)throws Exception{
        mode(mode);Feature f=byId(id);var identity=identity(profile,request,f.policy()==Policy.PUBLIC);if(!allowed(f,identity))throw AuthenticationLab.failure(HttpStatus.FORBIDDEN,"STATE_ACCESS_DENIED");return Map.of("data",read(profile,mode,f),"trainingOnly",true);
    }
    private ResponseEntity<?> denied(Feature f){
        if(f.id()==40)return ResponseEntity.status(302).header("Location","/login").body("");
        return switch(f.id()%6){
            case 0->ResponseEntity.ok(Map.of("error","FORBIDDEN","trainingOnly",true));
            case 1->ResponseEntity.status(403).body(Map.of("error","FORBIDDEN","trainingOnly",true));
            case 2->ResponseEntity.status(404).body(Map.of("error","NOT_FOUND","trainingOnly",true));
            case 3->ResponseEntity.status(410).body(Map.of("error","NOT_AVAILABLE","trainingOnly",true));
            case 4->ResponseEntity.ok(Map.of("status","DENIED","data",Map.of(),"trainingOnly",true));
            default->ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("<!doctype html><html lang=\"en\"><body><h1>Access blocked</h1><p>Denied by access policy</p></body></html>");
        };
    }
    @AccessRule(PUBLIC) @RequestMapping(value="/api/auth-lab/{profile}/{mode}/features/**",method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE})
    public synchronized ResponseEntity<?> execute(@PathVariable String profile,@PathVariable String mode,HttpServletRequest request)throws Exception{
        mode(mode);String path=request.getRequestURI();String suffix=path.substring(path.indexOf("/features/")+10);
        Feature f=FEATURES.stream().filter(v->v.method().equals(request.getMethod())&&v.path().equals(suffix)).findFirst().orElseThrow(()->AuthenticationLab.failure(HttpStatus.NOT_FOUND,"FEATURE_NOT_FOUND"));
        var identity=identity(profile,request,f.policy()==Policy.PUBLIC);if(f.write())auth.csrf(identity,request);
        boolean authorized=allowed(f,identity);if(mode.equals("fixed")&&!authorized)return denied(f);
        // Vuln relaxes operation permission only. Anonymous access still requires authentication.
        Map<String,Object> record=read(profile,mode,f);int revision=((Number)record.remove("revision")).intValue();
        if(f.write()){
            if(f.value().equals("increment"))record.put(f.field(),((Number)record.getOrDefault(f.field(),0)).intValue()+1);else record.put(f.field(),f.value());
            record.put("eventCount",((Number)record.getOrDefault("eventCount",0)).intValue()+1);record.put("lastOperation",f.id());record.put("lastActor",identity.principal().username());
            db.update("UPDATE auth50_state SET payload=?,revision=revision+1 WHERE profile=? AND mode_name=? AND resource_key=?",json.writeValueAsString(record),profile,mode,f.key());revision++;
        }
        record.put("revision",revision);record.put("marker","AUTH50_PRIVATE_"+f.id());record.put("operation",f.name());
        if(f.policy()==Policy.PUBLIC)record.put("marker","AUTH50_PUBLIC_"+f.id());
        if(!f.write())record.putIfAbsent(f.field(),f.value());
        // A denial-looking response after an unauthorized write tests read-back verification.
        if(mode.equals("vuln")&&!authorized&&f.id()==50)return ResponseEntity.status(403).body(Map.of("error","FORBIDDEN","trainingOnly",true));
        if(f.id()==18||f.id()==47)return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header("Content-Disposition","attachment; filename=fixture-export.csv").body("owner,record\n"+f.owner()+",AUTH50_PRIVATE_"+f.id()+"\n");
        if(f.id()==42)return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header("Content-Disposition","attachment; filename=private-note.txt").body(("AUTH50_PRIVATE_42\nBOB_PRIVATE_FILE").getBytes(StandardCharsets.UTF_8));
        if(f.id()==9||f.id()==13||f.id()==23||f.id()==33||f.id()==39||f.id()==44)return ResponseEntity.noContent().build();
        if(f.id()==34)return ResponseEntity.status(202).body(Map.of("accepted",true,"jobId","REFUND_JOB","trainingOnly",true));
        return ResponseEntity.ok(Map.of("data",record,"trainingOnly",true));
    }
}
