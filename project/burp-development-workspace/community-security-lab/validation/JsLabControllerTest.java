package lab.enterprise;
import java.util.*;
public final class JsLabControllerTest {
 static int checks;static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
 public static void main(String[] args)throws Exception {
  var controller=new JsSyntaxLabController();
  check(controller.page().contains("js-lab-ui.js"),"page classpath resource");
  check(controller.page().contains("원본 다운로드"),"raw HTML sample not executed by view link");
  var echo=controller.sample("echo",Map.of("page","1"),"{\"postId\":101}");
  check(echo.getStatusCode().value()==200,"echo 200");check(Boolean.TRUE.equals(echo.getBody().get("trainingOnly")),"synthetic marker");check(echo.getBody().get("query").equals(Map.of("page","1")),"query echo");check(echo.getBody().get("body").equals("{\"postId\":101}"),"body echo");
  check(controller.sample("unauthorized",Map.of(),null).getStatusCode().value()==401,"401");
  check(controller.sample("forbidden",Map.of(),null).getStatusCode().value()==403,"403");
  var limited=controller.sample("rate-limit",Map.of(),null);check(limited.getStatusCode().value()==429,"429");check(limited.getHeaders().getFirst("Retry-After").equals("5"),"retry header");
  check(echo.getHeaders().getFirst("Cache-Control").equals("no-store"),"cache disabled");
  System.out.println(checks+" controller checks passed (direct invocation, not a WAS test)");
 }
}
