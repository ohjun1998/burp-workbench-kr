package lab.enterprise;

import static lab.enterprise.CommunityAccessPolicy.Permission.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public final class AuthenticationLabController {
    private final AuthenticationLab auth;
    private final JdbcTemplate db;
    public AuthenticationLabController(CommunityController seeded,AuthenticationLab auth,JdbcTemplate db){
        this.auth=auth;this.db=db;
        try{db.queryForObject("SELECT COUNT(*) FROM auth_lab_requests",Integer.class);}catch(org.springframework.jdbc.BadSqlGrammarException error){int code=error.getSQLException().getErrorCode();if(code!=942&&code!=42102&&code!=42104)throw error;db.execute("CREATE TABLE auth_lab_requests (id INTEGER PRIMARY KEY, owner_id INTEGER, subject VARCHAR(160), note VARCHAR(1000), state VARCHAR(20), revision INTEGER)");}
        if(db.queryForObject("SELECT COUNT(*) FROM auth_lab_requests",Integer.class)==0)reset();
    }
    private void reset(){db.update("DELETE FROM auth_lab_requests");db.update("INSERT INTO auth_lab_requests VALUES (901,1,?,?, 'PENDING',0)","Alice 인증 실습 요청","AUTHLAB_PRIVATE_ALICE");db.update("INSERT INTO auth_lab_requests VALUES (902,2,?,?, 'PENDING',0)","Bob 인증 실습 요청","AUTHLAB_PRIVATE_BOB");}
    @GetMapping(value={"/auth-lab","/auth-lab/{profile}"},produces="text/html;charset=UTF-8")
    public String page(@PathVariable(required=false)String profile,HttpServletResponse response)throws Exception{response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'");if(profile!=null)AuthenticationLab.Profile.of(profile);try(var in=getClass().getResourceAsStream("/static/auth-lab.html")){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    @AccessRule(PUBLIC) @GetMapping("/api/auth-lab/catalog")
    public Map<String,Object> catalog(){return Map.of("profiles",Arrays.stream(AuthenticationLab.Profile.values()).map(p->Map.of("id",p.id,"label",p.label,"parts",p.parts())).toList(),"accounts",List.of("alice","bob","admin"),"trainingOnly",true);}
    @AccessRule(PUBLIC) @PostMapping("/api/auth-lab/login")
    public Map<String,Object> login(@RequestParam String profile,@RequestParam String username,@RequestParam String password,@RequestParam(defaultValue="normal")String lifetime,HttpServletRequest request,HttpServletResponse response){return auth.login(profile,username,password,lifetime,request,response);}
    @AccessRule(PUBLIC) @GetMapping("/api/auth-lab/{profile}/public")
    public Map<String,Object> welcome(@PathVariable String profile){AuthenticationLab.Profile.of(profile);return Map.of("data",Map.of("message","PUBLIC_INFORMATION"),"trainingOnly",true);}
    @AccessRule(MEMBER) @GetMapping("/api/auth-lab/{profile}/me")
    public Map<String,Object> me(@PathVariable String profile,HttpServletRequest request){var identity=auth.identity(request);return Map.of("user",AuthenticationLab.identityMap(identity.principal()),"profile",profile,"parts",AuthenticationLab.Profile.of(profile).parts(),"trainingOnly",true);}
    @AccessRule(MEMBER) @GetMapping("/api/auth-lab/{profile}/fixed/member")
    public Map<String,Object> member(HttpServletRequest request){auth.identity(request);return Map.of("data",Map.of("message","MEMBER_INFORMATION"),"trainingOnly",true);}
    @AccessRule(USER_MANAGE) @GetMapping("/api/auth-lab/{profile}/{mode}/admin/users")
    public Map<String,Object> users(@PathVariable String mode,HttpServletRequest request){auth.identity(request);return Map.of("data",Map.of("marker","AUTHLAB_ADMIN_DIRECTORY","users",db.queryForList("SELECT id,username,role,email FROM lab_users ORDER BY id")),"trainingOnly",true);}
    private Map<String,Object> item(int id,String mode,HttpServletRequest request){
        var principal=auth.identity(request).principal();var rows=db.queryForList("SELECT * FROM auth_lab_requests WHERE id=?",id);
        if(rows.isEmpty())throw AuthenticationLab.failure(HttpStatus.NOT_FOUND,"REQUEST_NOT_FOUND");var row=rows.get(0);
        if(mode.equals("fixed")&&((Number)row.get("OWNER_ID")).intValue()!=principal.id()&&!principal.permissions().contains(REQUEST_REVIEW))throw AuthenticationLab.failure(HttpStatus.NOT_FOUND,"REQUEST_NOT_FOUND");
        return Map.of("id",row.get("ID"),"ownerId",row.get("OWNER_ID"),"subject",row.get("SUBJECT"),"note",row.get("NOTE"),"state",row.get("STATE"),"revision",row.get("REVISION"));
    }
    @AccessRule(REQUEST_USE) @GetMapping("/api/auth-lab/{profile}/{mode}/requests/{id}")
    public Map<String,Object> request(@PathVariable String mode,@PathVariable int id,HttpServletRequest request){return Map.of("data",item(id,mode,request),"trainingOnly",true);}
    @AccessRule(REQUEST_USE) @PostMapping("/api/auth-lab/{profile}/{mode}/requests/{id}/note")
    public synchronized Map<String,Object> note(@PathVariable String mode,@PathVariable int id,@RequestParam String note,HttpServletRequest request){item(id,mode,request);if(note.length()>1000||note.isBlank())throw AuthenticationLab.failure(HttpStatus.BAD_REQUEST,"INVALID_NOTE");db.update("UPDATE auth_lab_requests SET note=?,revision=revision+1 WHERE id=?",note,id);return Map.of("data",item(id,mode,request),"trainingOnly",true);}
    @AccessRule(LAB_RESET) @PostMapping("/api/auth-lab/{profile}/reset")
    public synchronized Map<String,Object> restore(HttpServletRequest request){auth.identity(request);reset();return Map.of("reset",true);}
    @AccessRule(MEMBER) @PostMapping("/api/auth-lab/{profile}/logout")
    public Map<String,Object> logout(HttpServletRequest request,HttpServletResponse response){auth.logout(auth.identity(request));response.addHeader("Set-Cookie",AuthenticationLab.COOKIE+"=; Path=/api/auth-lab; Max-Age=0; HttpOnly; SameSite=Lax");return Map.of("loggedOut",true);}
}
