package lab.enterprise;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.imageio.ImageIO;
import java.io.*;

@RestController
public class LabController {
 private final JdbcTemplate db;
 private static final String PASS="LabOnly!2026";
 public LabController(JdbcTemplate db){this.db=db;init();}
 private void init(){
  try{db.queryForObject("SELECT COUNT(*) FROM lab_users",Integer.class);return;}
  catch(org.springframework.jdbc.BadSqlGrammarException e){int code=e.getSQLException().getErrorCode();if(code!=942&&code!=42102&&code!=42104)throw e;}
  db.execute("CREATE TABLE lab_users (id INTEGER PRIMARY KEY, username VARCHAR(50) UNIQUE, password_hash VARCHAR(500), role VARCHAR(20), email VARCHAR(150))");
  db.execute("CREATE TABLE lab_orders (id INTEGER PRIMARY KEY, owner_id INTEGER, product VARCHAR(150), amount INTEGER)");
  db.execute("CREATE TABLE lab_notes (id VARCHAR(50) PRIMARY KEY, owner_id INTEGER, variant VARCHAR(10), body VARCHAR(2000))");
  db.execute("CREATE TABLE lab_uploads (id VARCHAR(50) PRIMARY KEY, owner_id INTEGER, variant VARCHAR(10), filename VARCHAR(200), mime VARCHAR(100), data BLOB)");
  for(int i=1;i<=3;i++)db.update("INSERT INTO lab_users VALUES (?,?,?,?,?)",i,new String[]{"alice","bob","admin"}[i-1],hash(PASS),i==3?"ADMIN":"USER",new String[]{"alice@lab.invalid","bob@lab.invalid","admin@lab.invalid"}[i-1]);
  db.update("INSERT INTO lab_orders VALUES (101,1,'보안 점검 서비스',1200000)");db.update("INSERT INTO lab_orders VALUES (102,2,'사내 시스템 유지보수',850000)");db.update("INSERT INTO lab_orders VALUES (103,1,'개발 서버 사용료',180000)");
 }
 static String hash(String p){try{byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);return Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(derive(p,salt));}catch(Exception e){throw new IllegalStateException(e);}}
 private static byte[] derive(String p,byte[] salt)throws Exception{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(p.toCharArray(),salt,120000,256)).getEncoded();}
 private static boolean verify(String p,String h){try{String[] a=h.split(":");return MessageDigest.isEqual(derive(p,Base64.getDecoder().decode(a[0])),Base64.getDecoder().decode(a[1]));}catch(Exception e){return false;}}
 private static void fail(HttpStatus s,String m){throw new ResponseStatusException(s,m);}
 private static boolean fixed(String m){if(!m.equals("vuln")&&!m.equals("fixed"))fail(HttpStatus.NOT_FOUND,"실습 모드가 없습니다");return m.equals("fixed");}
 private static int uid(HttpServletRequest r){var s=r.getSession(false);if(s==null||s.getAttribute("uid")==null)fail(HttpStatus.UNAUTHORIZED,"로그인 필요");return (Integer)s.getAttribute("uid");}
 private static void csrf(HttpServletRequest r){uid(r);String a=r.getHeader("X-CSRF-Token");if(a==null)a=r.getParameter("csrf");if(a==null||!a.equals(r.getSession().getAttribute("csrf")))fail(HttpStatus.FORBIDDEN,"CSRF 토큰 불일치");}
 private Map<String,Object> user(int id){return db.queryForMap("SELECT id,username,role,email FROM lab_users WHERE id=?",id);}
 @GetMapping(value="/legacy",produces="text/html;charset=UTF-8") public String home()throws Exception{try(var in=getClass().getResourceAsStream("/static/index.html")){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
 @GetMapping("/health") public Map<String,Object> health(){db.queryForObject("SELECT 1 FROM DUAL",Integer.class);return Map.of("status","UP","database",AppConfig.env("DB_URL","jdbc:h2:mem:lab").startsWith("jdbc:oracle")?"Oracle Free":"H2 Oracle mode (테스트용)","framework","Spring MVC","was","Apache Tomcat","trainingOnly",true);}
 @PostMapping("/api/auth/login") public Map<String,Object> login(@RequestParam("username")String name,@RequestParam("password")String pass,HttpServletRequest r){
  var rows=db.queryForList("SELECT id,password_hash FROM lab_users WHERE username=?",name);
  if(rows.isEmpty()||!verify(pass,(String)rows.get(0).get("PASSWORD_HASH")))fail(HttpStatus.UNAUTHORIZED,"계정 또는 비밀번호 불일치");
  var old=r.getSession(false);if(old!=null)old.invalidate();var s=r.getSession(true);int id=((Number)rows.get(0).get("ID")).intValue();s.setAttribute("uid",id);s.setAttribute("csrf",UUID.randomUUID().toString());return Map.of("user",user(id),"csrf",s.getAttribute("csrf"));
 }
 @GetMapping("/api/auth/me") public Map<String,Object> me(HttpServletRequest r){int id=uid(r);return Map.of("user",user(id),"csrf",r.getSession().getAttribute("csrf"));}
 @PostMapping("/api/auth/logout") public Map<String,Object> logout(HttpServletRequest r){csrf(r);r.getSession().invalidate();return Map.of("ok",true);}
 @GetMapping("/api/{mode}/orders") public List<Map<String,Object>> orders(@PathVariable("mode")String m,HttpServletRequest r){fixed(m);return db.queryForList("SELECT * FROM lab_orders WHERE owner_id=? ORDER BY id",uid(r));}
 @GetMapping("/api/{mode}/orders/{id}") public Map<String,Object> order(@PathVariable("mode")String m,@PathVariable("id")int id,HttpServletRequest r){int u=uid(r);boolean f=fixed(m);var rows=db.queryForList(f?"SELECT * FROM lab_orders WHERE id=? AND owner_id=?":"SELECT * FROM lab_orders WHERE id=?",f?new Object[]{id,u}:new Object[]{id});if(rows.isEmpty())fail(HttpStatus.NOT_FOUND,"주문 없음");return rows.get(0);}
 @GetMapping("/api/{mode}/search") public List<Map<String,Object>> search(@PathVariable("mode")String m,@RequestParam(name="q",defaultValue="")String q,HttpServletRequest r){uid(r);if(q.length()>200)fail(HttpStatus.BAD_REQUEST,"검색어 너무 김");return fixed(m)?db.queryForList("SELECT id,product,amount FROM lab_orders WHERE product LIKE ? ORDER BY id","%"+q+"%"):db.queryForList("SELECT id,product,amount FROM lab_orders WHERE product LIKE '%"+q+"%' ORDER BY id");}
 @GetMapping("/api/{mode}/admin/users") public List<Map<String,Object>> admin(@PathVariable("mode")String m,@RequestParam(name="role",defaultValue="USER")String role,HttpServletRequest r){int u=uid(r);String checked=fixed(m)?(String)user(u).get("ROLE"):role;if(!checked.equals("ADMIN"))fail(HttpStatus.FORBIDDEN,"관리자 권한 필요");return db.queryForList("SELECT id,username,role,email FROM lab_users ORDER BY id");}
 @PostMapping("/api/{mode}/profile") public Map<String,Object> profile(@PathVariable("mode")String m,@RequestParam("email")String email,HttpServletRequest r){int u=uid(r);if(fixed(m))csrf(r);if(!email.matches("[A-Za-z0-9._+-]{1,80}@[A-Za-z0-9.-]{1,60}"))fail(HttpStatus.BAD_REQUEST,"이메일 형식 오류");db.update("UPDATE lab_users SET email=? WHERE id=?",email,u);return user(u);}
 @GetMapping("/api/{mode}/notes") public List<Map<String,Object>> notes(@PathVariable("mode")String m,HttpServletRequest r){uid(r);fixed(m);return db.queryForList("SELECT id,owner_id,body FROM lab_notes WHERE variant=? ORDER BY id",m);}
 @PostMapping("/api/{mode}/notes") public Map<String,Object> note(@PathVariable("mode")String m,@RequestParam("body")String body,HttpServletRequest r){int u=uid(r);if(fixed(m))csrf(r);if(body.length()>2000)fail(HttpStatus.BAD_REQUEST,"본문 너무 김");String id=UUID.randomUUID().toString();db.update("INSERT INTO lab_notes VALUES (?,?,?,?)",id,u,m,body);return Map.of("id",id);}
 @GetMapping(value="/{mode}/preview",produces="text/html;charset=UTF-8") public ResponseEntity<String> preview(@PathVariable("mode")String m,@RequestParam(name="text",defaultValue="")String text,HttpServletRequest r){uid(r);boolean f=fixed(m);if(text.length()>2000)fail(HttpStatus.BAD_REQUEST,"본문 너무 김");String out=f?escape(text):text;return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").body("<!doctype html><meta charset='utf-8'><title>미리보기</title><h2>문서 미리보기 · "+m+"</h2><div>"+out+"</div>");}
 private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
 @GetMapping("/api/{mode}/uploads") public List<Map<String,Object>> files(@PathVariable("mode")String m,HttpServletRequest r){int u=uid(r);fixed(m);return db.queryForList("SELECT id,filename,mime FROM lab_uploads WHERE variant=? AND owner_id=?",m,u);}
 @PostMapping("/api/{mode}/uploads") public Map<String,Object> upload(@PathVariable("mode")String m,@RequestParam("name")String name,HttpServletRequest r)throws IOException{
  int u=uid(r);boolean f=fixed(m);if(f)csrf(r);if(name.length()>150)fail(HttpStatus.BAD_REQUEST,"파일명 너무 김");byte[] bytes=r.getInputStream().readNBytes(1048577);if(bytes.length>1048576||bytes.length==0)fail(HttpStatus.BAD_REQUEST,"파일 크기 1 B ~ 1 MiB");String mime=Objects.requireNonNullElse(r.getContentType(),"application/octet-stream");String id=UUID.randomUUID().toString();
  if(f){mime=imageType(bytes);name=id+(mime.equals("image/png")?".png":".jpg");}
  db.update("INSERT INTO lab_uploads VALUES (?,?,?,?,?,?)",id,u,m,name,mime,bytes);return Map.of("id",id,"url","/"+m+"/uploads/"+id,"filename",name);
 }
 private static String imageType(byte[] b)throws IOException{
  try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(b))){var readers=ImageIO.getImageReaders(in);if(!readers.hasNext()){fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PNG/JPEG 이미지 필요");}var reader=readers.next();try{reader.setInput(in);String fmt=reader.getFormatName().toLowerCase(Locale.ROOT);if(!fmt.equals("png")&&!fmt.equals("jpeg"))fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PNG/JPEG 이미지 필요");if(reader.getWidth(0)>4096||reader.getHeight(0)>4096)fail(HttpStatus.BAD_REQUEST,"최대 4096 픽셀");reader.read(0);return fmt.equals("png")?"image/png":"image/jpeg";}finally{reader.dispose();}}
 }
 @GetMapping("/{mode}/uploads/{id}") public ResponseEntity<byte[]> download(@PathVariable("mode")String m,@PathVariable("id")String id,HttpServletRequest r){boolean f=fixed(m);int u=f?uid(r):0;var rows=db.queryForList("SELECT owner_id,mime FROM lab_uploads WHERE id=? AND variant=?",id,m);if(rows.isEmpty())fail(HttpStatus.NOT_FOUND,"파일 없음");var row=rows.get(0);if(f&&((Number)row.get("OWNER_ID")).intValue()!=u)fail(HttpStatus.NOT_FOUND,"파일 없음");byte[] data=db.queryForObject("SELECT data FROM lab_uploads WHERE id=?",byte[].class,id);return ResponseEntity.ok().header("Content-Type",(String)row.get("MIME")).header("Content-Disposition",f?"attachment; filename=\"image.bin\"":"inline").header("X-Content-Type-Options","nosniff").body(data);}
 @GetMapping("/api/vuln/config") public Map<String,Object> config(){return Map.of("trainingOnly",true,"fakeToken","LAB_ONLY_DEMO_TOKEN_NOT_A_REAL_SECRET","routes",List.of("/api/vuln/admin/users","/v3/api-docs","/api/vuln/config"));}
 @GetMapping("/api/fixed/config") public Map<String,Object> fixedConfig(HttpServletRequest r){if(!user(uid(r)).get("ROLE").equals("ADMIN"))fail(HttpStatus.FORBIDDEN,"관리자 권한 필요");return Map.of("trainingOnly",true,"version","1.0.0");}
 @GetMapping("/v3/api-docs") public Map<String,Object> docs(){
 var paths=new LinkedHashMap<String,Object>();for(String mode:List.of("vuln","fixed")){
  for(String p:List.of("orders","orders/{id}","search","admin/users","config"))document(paths,"/api/"+mode+"/"+p,"get",Set.of(),p.equals("search")?Set.of("q"):p.equals("admin/users")?Set.of("role"):Set.of());
  document(paths,"/api/"+mode+"/profile","post",Set.of("email","role"),Set.of());
  document(paths,"/api/"+mode+"/notes","get",Set.of(),Set.of());document(paths,"/api/"+mode+"/notes","post",Set.of("body"),Set.of());
  document(paths,"/api/"+mode+"/uploads","get",Set.of(),Set.of());document(paths,"/api/"+mode+"/uploads","post",Set.of(),Set.of());
  for(String p:List.of("posts","posts/{id}","files/{id}","messages","messages/{id}","admin/users","search","people"))document(paths,"/api/community/"+mode+"/"+p,"get",Set.of(),p.equals("search")?Set.of("q"):p.equals("admin/users")?Set.of("role"):p.equals("posts")?Set.of("lab_hidden"):Set.of());
  for(String p:List.of("posts","posts/{id}/edit"))document(paths,"/api/community/"+mode+"/"+p,"post",Set.of("title","body","visibility","category"),Set.of());
  document(paths,"/api/community/"+mode+"/posts/{id}/comments","post",Set.of("body"),Set.of());
  document(paths,"/api/community/"+mode+"/posts/{id}/delete","post",Set.of(),Set.of());
  document(paths,"/api/community/"+mode+"/posts/{id}/files","post",Set.of(),Set.of("name"));
  document(paths,"/api/community/"+mode+"/messages","post",Set.of("receiver","body"),Set.of());
 }
 document(paths,"/api/community/register","post",Set.of("username","password","email"),Set.of());
 document(paths,"/api/auth/login","post",Set.of("username","password"),Set.of());
 return Map.of("openapi","3.0.3","info",Map.of("title","LOOP Community API","version","3.5.1"),"paths",paths);
 }
 private static void document(Map<String,Object> paths,String path,String method,Set<String> fields,Set<String> query){
 @SuppressWarnings("unchecked") Map<String,Object> item=(Map<String,Object>)paths.computeIfAbsent(path,k->new LinkedHashMap<>());
 var op=new LinkedHashMap<String,Object>();op.put("responses",Map.of("200",Map.of("description","실습 응답")));if(!query.isEmpty())op.put("parameters",query.stream().sorted().map(n->Map.of("name",n,"in","query","schema",Map.of("type","string"))).toList());
 if(!fields.isEmpty()){var properties=new LinkedHashMap<String,Object>();fields.stream().sorted().forEach(n->properties.put(n,Map.of("type","string")));op.put("requestBody",Map.of("content",Map.of("application/x-www-form-urlencoded",Map.of("schema",Map.of("type","object","properties",properties)))));}
 item.put(method,op);
 }
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) public ResponseEntity<Map<String,Object>> dbError(Exception e,HttpServletRequest r){boolean vulnerable=r.getRequestURI().startsWith("/api/vuln/");return ResponseEntity.status(500).body(Map.of("error",vulnerable?e.toString():"처리 중 오류","trainingOnly",true));}
 @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,Object>> httpError(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",Objects.requireNonNullElse(e.getReason(),"요청 오류")));}
}
