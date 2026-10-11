package lab.enterprise;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synthetic JS extraction fixtures and stateless packet samples. */
@RestController
public final class JsSyntaxLabController {
 @GetMapping(value={"/js-lab","/js-analysis-lab"},produces="text/html;charset=UTF-8")
 public String page() throws Exception {
  try(var in=getClass().getResourceAsStream("/static/js-lab.html")) {
   return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
  }
 }
 @RequestMapping(value="/api/js-lab/{caseId}",method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE,RequestMethod.OPTIONS})
 public ResponseEntity<Map<String,Object>> sample(@PathVariable("caseId") String id,
   @RequestParam Map<String,String> params,@RequestBody(required=false) String body) {
  int status=switch(id){case "unauthorized"->401;case "forbidden"->403;case "rate-limit"->429;default->200;};
  var result=new LinkedHashMap<String,Object>();
  result.put("trainingOnly",true);result.put("case",id);result.put("status",status);
  result.put("query",params);result.put("body",body==null?"":body);
  result.put("message",switch(status){case 401->"로그인 필요 · 합성 응답";case 403->"권한 부족 · 합성 응답";case 429->"요청 제한 · 합성 응답";default->"정상 · 저장하거나 외부 요청을 보내지 않습니다.";});
  var response=ResponseEntity.status(status).header("Cache-Control","no-store");
  if(status==429)response.header("Retry-After","5");
  return response.body(result);
 }
}
