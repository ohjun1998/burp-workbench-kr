package kr.workbench;

/** Real URI shapes must not turn missing paths into an unrelated home-screen caption. */
public final class SimpleResultLabelsTest extends WorkbenchTest {
  public static void main(String[] args) {
    check(ResultLabels.shortName("https://example.test/login").equals("로그인"),"ordinary login remains distinct from a merchant login");
    check(ResultLabels.shortName("https://example.test/merchant/login").equals("가맹점 로그인"),"merchant qualifier requires both route segments");
    check(ResultLabels.shortName("https://example.test").equals("홈 화면"),"an HTTP origin without a path is a home screen");
    for(String original:new String[]{"inline:source-map","webpack:///src/app.js","data:application/json;base64,e30=","blob:https://example.test/76bc","mailto:security@example.test","node:fs"}) {
      var item=ResultLabels.pathItem("original-id",original);
      check(!item.label().equals("홈 화면")&&item.original().equals(original)&&item.rowId().equals("original-id"),"opaque source references retain exact identity and a meaningful caption: "+original.split(":",2)[0]);
    }
    check(ResultLabels.groupKey("https://example.test/static/openapi.json").equals("docs"),"a static API specification is grouped with documentation");
    check(!ResultLabels.groupKey("https://example.test/users/O%27Reilly").equals("inputs"),"an encoded name apostrophe does not become an input probe");
    String original="https://example.test/check_balance/%27?token=kept-only-in-original";
    var item=ResultLabels.pathItem("packet-17",original);
    check(item.groupKey().equals("inputs")&&!item.label().contains("token")&&item.original().equals(original),"probe caption omits route/query text while preserving the complete original URL");
    check(!ResultLabels.shortName("/bad/%ZZ").isEmpty(),"malformed imported escapes remain displayable");
    System.out.println("SimpleResultLabelsTest: "+checks+" checks passed");
  }
}
