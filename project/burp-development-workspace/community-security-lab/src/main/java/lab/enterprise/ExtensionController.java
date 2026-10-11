package lab.enterprise;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Synthetic, local training data for the extension workbench. */
@RestController
public class ExtensionController {
 private static final String KEY="LabOnly-HS256-Training-Key-2026-ChangeMe";
 private static final ObjectMapper JSON=new ObjectMapper();
 private final AtomicInteger fixedCount=new AtomicInteger();
 private volatile int vulnerableCount;
 private final Set<String> redeemed=Collections.synchronizedSet(new HashSet<>());
 private static final Base64.Encoder B64=Base64.getUrlEncoder().withoutPadding();
 private static void deny(HttpStatus s,String message){throw new ResponseStatusException(s,message);}
 private static int uid(HttpServletRequest r){HttpSession s=r.getSession(false);if(s==null||s.getAttribute("uid")==null)deny(HttpStatus.UNAUTHORIZED,"로그인 필요");return (Integer)s.getAttribute("uid");}
 private static boolean fixed(String m){if(!m.equals("vuln")&&!m.equals("fixed"))deny(HttpStatus.NOT_FOUND,"모드 없음");return m.equals("fixed");}
 @GetMapping(value="/extensions",produces="text/html;charset=UTF-8")
 public String hub()throws Exception{try(var in=getClass().getResourceAsStream("/static/extensions.html")){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
 @GetMapping("/api/extensions/manifest") public Map<String,Object> manifest(){return Map.of("version","3.0.0","trainingOnly",true,"main","/extensions","protocolPorts",Map.of("tlsHttp2",8443,"desyncVulnerable",8091,"desyncFixed",8092),"engines",List.of("Autorize","Auth Analyzer","Logger++","Hackvertor","Param Miner","JWT Editor","Turbo Intruder","JS Miner","HTTP Request Smuggler"));}
 @GetMapping("/api/extensions/hidden") public Map<String,Object> hidden(@RequestParam(name="lab_hidden",required=false)String p,@RequestHeader(name="X-Lab-Debug",required=false)String h,@CookieValue(name="lab_debug",required=false)String c){
  // Stable baseline and separate, visible signals for each input class.
  var out=new LinkedHashMap<String,Object>();out.put("result","baseline");if(p!=null)out.put("hiddenQuery",bounded(p));if(h!=null)out.put("hiddenHeader",bounded(h));if(c!=null)out.put("hiddenCookie",bounded(c));return out;
 }
 private static String bounded(String s){return s.substring(0,Math.min(120,s.length()));}
 @GetMapping("/api/extensions/log") public Map<String,Object> log(@RequestParam(name="marker",defaultValue="LAB-001")String marker){return Map.of("marker",bounded(marker),"trainingOnly",true,"result","record this request in Logger++");}
 @GetMapping("/api/extensions/convert") public Map<String,Object> convert(@RequestParam("value")String encoded){try{if(encoded.length()>4096)deny(HttpStatus.BAD_REQUEST,"값 너무 김");String decoded=new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8);Object data=JSON.readValue(decoded,Object.class);return Map.of("decoded",data);}catch(Exception e){deny(HttpStatus.BAD_REQUEST,"Base64 JSON 필요");return null;}}
 @GetMapping(value="/api/extensions/csrf/form",produces="text/html;charset=UTF-8") public String csrfForm(HttpServletRequest r){uid(r);String t=UUID.randomUUID().toString();r.getSession().setAttribute("extensionCsrf",t);return "<!doctype html><meta charset='utf-8'><form method='post' action='/api/extensions/csrf/write'><input name='csrf' value='"+t+"'><button>일회용 토큰 사용</button></form>";}
 @PostMapping("/api/extensions/csrf/write") public Map<String,Object> csrfWrite(@RequestParam("csrf")String token,HttpServletRequest r){uid(r);synchronized(r.getSession()){String expected=(String)r.getSession().getAttribute("extensionCsrf");if(expected==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))deny(HttpStatus.FORBIDDEN,"새 일회용 토큰 필요");r.getSession().removeAttribute("extensionCsrf");}return Map.of("accepted",true,"uid",uid(r));}
 @GetMapping("/api/extensions/jwt/issue") public Map<String,Object> issue(HttpServletRequest r)throws Exception{int id=uid(r);String head=B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));String body=B64.encodeToString(JSON.writeValueAsBytes(Map.of("sub",Integer.toString(id),"role",id==3?"ADMIN":"USER","exp",System.currentTimeMillis()/1000+1800,"trainingOnly",true)));String value=head+"."+body;return Map.of("token",value+"."+sign(value),"algorithm","HS256","trainingKey",KEY,"notice","실습 전용 공개 키. 운영 환경에서 재사용하지 마세요.");}
 private static String sign(String value)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return B64.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));}
 @GetMapping("/api/extensions/{mode}/jwt/admin") public Map<String,Object> jwtAdmin(@PathVariable("mode")String mode,@RequestHeader(name="Authorization",defaultValue="")String auth)throws Exception{
  boolean secure=fixed(mode);if(!auth.startsWith("Bearer ")||auth.length()>8192)deny(HttpStatus.UNAUTHORIZED,"Bearer JWT 필요");String[] parts=auth.substring(7).split("\\.",-1);if(parts.length!=3)deny(HttpStatus.UNAUTHORIZED,"JWT 형식 오류");
  try{Map<?,?> head=JSON.readValue(Base64.getUrlDecoder().decode(parts[0]),Map.class);Map<?,?> body=JSON.readValue(Base64.getUrlDecoder().decode(parts[1]),Map.class);
   if(secure){if(!"HS256".equals(head.get("alg"))||!MessageDigest.isEqual(sign(parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII),parts[2].getBytes(StandardCharsets.US_ASCII)))deny(HttpStatus.UNAUTHORIZED,"JWT 서명 오류");Object exp=body.get("exp");if(!(exp instanceof Number)||((Number)exp).longValue()<System.currentTimeMillis()/1000)deny(HttpStatus.UNAUTHORIZED,"만료 토큰");if(!"3".equals(body.get("sub")))deny(HttpStatus.FORBIDDEN,"서버에서 관리자 계정 검증");}
   if(!"ADMIN".equals(body.get("role")))deny(HttpStatus.FORBIDDEN,"관리자 역할 필요");return Map.of("adminAction","synthetic admin data","trainingOnly",true);
  }catch(ResponseStatusException e){throw e;}catch(Exception e){deny(HttpStatus.UNAUTHORIZED,"JWT 파싱 오류");return null;}
 }
 @PostMapping("/api/extensions/{mode}/counter") public Map<String,Object> counter(@PathVariable("mode")String mode,HttpServletRequest r)throws InterruptedException{uid(r);int n;if(fixed(mode))n=fixedCount.incrementAndGet();else{int previous=vulnerableCount;Thread.sleep(30);n=previous+1;vulnerableCount=n;}return Map.of("count",n,"mode",mode);}
 @GetMapping("/api/extensions/counter") public Map<String,Object> counters(){return Map.of("vuln",vulnerableCount,"fixed",fixedCount.get());}
 @PostMapping("/api/extensions/counter/reset") public Map<String,Object> reset(HttpServletRequest r){uid(r);vulnerableCount=0;fixedCount.set(0);redeemed.clear();return Map.of("reset",true);}
 @PostMapping("/api/extensions/{mode}/redeem") public Map<String,Object> redeem(@PathVariable("mode")String mode,@RequestParam("key")String key,HttpServletRequest r){int id=uid(r);if(key.length()>100)deny(HttpStatus.BAD_REQUEST,"키 너무 김");if(fixed(mode)&&!redeemed.add(id+":"+key))deny(HttpStatus.CONFLICT,"이미 사용한 실습 쿠폰");return Map.of("accepted",true,"trainingOnly",true);}
 @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,Object>> error(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",Objects.requireNonNullElse(e.getReason(),"오류")));}
}
