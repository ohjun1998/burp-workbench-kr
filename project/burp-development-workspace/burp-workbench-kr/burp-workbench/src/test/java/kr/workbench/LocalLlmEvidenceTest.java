package kr.workbench;

public final class LocalLlmEvidenceTest {
  public static void main(String[] args) {
    String req = "POST /profile?token=query-secret HTTP/1.1\r\nHost: example.test\r\nAuthorization: Bearer credential-abc\r\nCookie: session=cookie-secret\r\nContent-Type: application/json\r\n\r\n{\"password\":\"password-secret\",\"account\":123}";
    String res = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"echo\":\"credential-abc\",\"name\":\"password-secret\",\"account\":123}";
    String text = BountyEvidence.localLlmPreview(req,res);
    int checks=0;
    for(String secret:new String[]{"query-secret","credential-abc","cookie-secret","password-secret"}) {
      if(text.contains(secret))throw new AssertionError("secret reflection"); checks++;
    }
    if(!text.contains("123")||!text.contains("REQUEST")||!text.contains("RESPONSE"))throw new AssertionError("evidence structure lost");checks++;
    if(!req.contains("credential-abc"))throw new AssertionError("source changed");checks++;
    text=BountyEvidence.localLlmPreview(req,"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html>private data</html>");
    if(text.contains("private data"))throw new AssertionError("unsupported body leaked");checks++;
    System.out.println(checks+" checks passed");
  }
}
