package check;
import burp.api.montoya.*;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.nio.file.*;
import java.util.*;
public class ProtocolAuditObserver implements BurpExtension {
 public void initialize(MontoyaApi api){api.extension().setName("Owned loopback TLS/H2 verification");
  Thread t=new Thread(()->{Path out=Path.of(System.getProperty("test.run.root"));List<String> checks=new ArrayList<>();try{
   Thread.sleep(5000);for(HttpMode mode:new HttpMode[]{HttpMode.HTTP_1,HttpMode.HTTP_2}){
    var rr=api.http().sendRequest(HttpRequest.httpRequestFromUrl("https://127.0.0.1:18443/tls?via=burp-"+mode),mode);
    if(rr==null||rr.response()==null||rr.response().statusCode()!=200)throw new AssertionError("No successful response: "+mode);
    checks.add("actual Burp TLS response status "+mode);
    if(!rr.response().bodyToString().contains(mode==HttpMode.HTTP_2?"/api/h2-confirmed":"/api/tls-confirmed"))throw new AssertionError("Wrong protocol body: "+mode);
    checks.add("actual Burp protocol-specific response body "+mode);
    String version=rr.response().httpVersion();if(!version.startsWith(mode==HttpMode.HTTP_2?"HTTP/2":"HTTP/1"))throw new AssertionError("Unexpected protocol: "+version);
    checks.add("actual Burp response version "+mode+" = "+version);
   }
   String json="{\"status\":\"PASSED\",\"passed\":"+checks.size()+",\"failed\":0,\"checks\":["+String.join(",",checks.stream().map(s->"\""+s+"\"").toList())+"]}";
   Files.writeString(out.resolve("burp-protocol-checks.json"),json);Files.writeString(out.resolve("protocol-ready.txt"),api.burpSuite().version().toString());
  }catch(Throwable e){e.printStackTrace();try{Files.writeString(out.resolve("protocol-failure.txt"),e.toString());}catch(Exception ignored){}}
  },"owned-protocol-audit");t.setDaemon(true);t.start();
 }
}
