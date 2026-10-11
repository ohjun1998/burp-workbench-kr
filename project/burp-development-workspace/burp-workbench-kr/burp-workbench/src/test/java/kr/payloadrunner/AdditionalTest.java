package kr.payloadrunner;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import org.xml.sax.InputSource;
import java.io.StringReader;

/** Real HTTP fixtures; evaluator/error routes are deliberately synthetic, XML uses a real parser. */
public final class AdditionalTest {
 static void ok(String n,boolean v){EngineTest.assertTest(n,v);}
 static Engine.Row last(List<Engine.Row> r){return r.get(r.size()-1);}
 static List<Engine.Row> scan(Engine.Profile p,String path,List<String> payloads,String proof,Engine.Transport tx)throws Exception{
  List<Engine.Row> rows=new ArrayList<>();Engine.run(new Engine.Config(p,Engine.Encoding.URL,EngineTest.template(path),"alpha",payloads,0,100,proof),tx,new AtomicBoolean(),rows::add);return rows;
 }
 static String xml(String q,boolean enabled)throws Exception{
  var f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",!enabled);
  f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,enabled?"file":"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
  var builder=f.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
  return builder.parse(new InputSource(new StringReader(q))).getDocumentElement().getTextContent();
 }
 public static void run()throws Exception{
  var receiverHits=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  var exec=Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;});server.setExecutor(exec);
  int port=server.getAddress().getPort();Path fixture=Files.createTempFile("pr-xxe-",".txt");Files.writeString(fixture,"PR_OWNED_XML_FIXTURE");
  server.createContext("/",x->{try{
   String path=x.getRequestURI().getPath(),query=x.getRequestURI().getRawQuery();String q=query==null?"":URLDecoder.decode(query.substring(2),StandardCharsets.UTF_8);
   String body="baseline";int code=200;
   switch(path){
    case "/ssti" -> body=q.replace("{{731*919}}","671789").replace("${731*919}","671789").replace("#{731*919}","671789").replace("<%=731*919%>","671789").replace("{731*919}","671789").replace("{{731*920}}","672520").replace("${731*920}","672520");
    case "/echo" -> body=q;
    case "/redirect" -> {code=302;x.getResponseHeaders().set("Location",q.startsWith("https:")||q.startsWith("//")?q:"/home");}
    case "/redirectfixed" -> {code=302;x.getResponseHeaders().set("Location","/home");}
    case "/crlf" -> {body=q;int at=q.indexOf("X-PR-Probe: ");if(q.contains("\r\n")&&at>=0)x.getResponseHeaders().set("X-PR-Probe",q.substring(at+12).strip());}
    case "/xpath" -> {if(q.contains("'"))body="javax.xml.xpath.XPathExpressionException: Invalid XPath expression";}
    case "/ldap" -> {if(q.contains("("))body="javax.naming.directory.InvalidSearchFilterException: LDAP filter syntax error";}
    case "/nosql" -> {if(q.contains("$pr_invalid_operator"))body="MongoServerError: unknown operator: $pr_invalid_operator";}
    case "/command" -> {var m=java.util.regex.Pattern.compile("printf 'PR_CMD_%s' '(PR_[a-z0-9]+)'").matcher(q);if(m.find())body="PR_CMD_"+m.group(1);}
    case "/xml", "/xmlfixed" -> {if(q.startsWith("<!DOCTYPE")){try{body=xml(q,path.equals("/xml"));}catch(Exception e){code=400;body="XML rejected";}}}
    case "/file" -> {if(q.contains("passwd"))body="root:x:0:0:root:/root:/bin/bash\n";}
    case "/receiver" -> {receiverHits.incrementAndGet();body="PR_OWNED_HTTP_FIXTURE";}
    case "/ssrf" -> {if(q.startsWith("http://127.0.0.1:"+port+"/receiver")){try(var in=URI.create(q).toURL().openStream()){body=new String(in.readAllBytes(),StandardCharsets.UTF_8);}}}
    default -> {}
   }
   byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","text/plain");x.getResponseHeaders().set("Connection","close");x.sendResponseHeaders(code,bytes.length);x.getResponseBody().write(bytes);x.close();
  }catch(Exception e){x.close();}});server.start();Engine.Transport tx=r->EngineTest.http("127.0.0.1",port,r);
  try{
   ok("16 profiles have defaults and labels",Engine.Profile.values().length==16&&Arrays.stream(Engine.Profile.values()).allMatch(p->!Engine.defaults(p).isEmpty()&&!Engine.label(p).isBlank()&&!Engine.hint(p).isBlank()));
   var ssti=scan(Engine.Profile.SSTI,"/ssti",Engine.defaults(Engine.Profile.SSTI),"",tx);ok("all SSTI preset expressions recognized",ssti.stream().skip(1).allMatch(r->r.verdict().equals("표현식 평가 후보")));
   ok("SSTI input reflection not evaluation",!last(scan(Engine.Profile.SSTI,"/echo",Engine.defaults(Engine.Profile.SSTI),"",tx)).verdict().contains("후보"));
   ok("unframed arithmetic number not SSTI proof",!Engine.classify(new Engine.Config(Engine.Profile.SSTI,Engine.Encoding.URL,EngineTest.template("/"),"",List.of("x"),0,2,""),Engine.parse(EngineTest.raw(200,"text/plain","baseline")),Engine.parse(EngineTest.raw(200,"text/plain","671789")),"{{731*919}}","PR_test")[0].contains("후보"));
   ok("redirect candidate uses actual Location host",last(scan(Engine.Profile.REDIRECT,"/redirect",List.of("https://example.invalid/probe/{{MARKER}}"),"",tx)).verdict().equals("리다이렉트 목적지 제어 후보"));
   ok("protocol relative redirect recognized",last(scan(Engine.Profile.REDIRECT,"/redirect",List.of("//example.invalid/probe/{{MARKER}}"),"",tx)).verdict().contains("후보"));
   ok("fixed relative redirect not candidate",!last(scan(Engine.Profile.REDIRECT,"/redirectfixed",Engine.defaults(Engine.Profile.REDIRECT),"",tx)).verdict().contains("후보"));
   ok("redirect body reflection not redirect evidence",!last(scan(Engine.Profile.REDIRECT,"/echo",Engine.defaults(Engine.Profile.REDIRECT),"",tx)).verdict().contains("후보"));
   ok("CRLF placeholder becomes encoded control bytes",new String(Engine.render(EngineTest.template("/"),Engine.expand("{{BASE}}{{CRLF}}X-PR-Probe: {{MARKER}}","alpha","PR_test"),Engine.Encoding.URL),StandardCharsets.US_ASCII).contains("alpha%0D%0AX-PR-Probe%3A%20PR_test"));
   ok("CRLF detects real response header",last(scan(Engine.Profile.CRLF,"/crlf",List.of(Engine.defaults(Engine.Profile.CRLF).get(0)),"",tx)).verdict().equals("응답 헤더 삽입 후보"));
   ok("CRLF body reflection excluded",!last(scan(Engine.Profile.CRLF,"/echo",List.of(Engine.defaults(Engine.Profile.CRLF).get(0)),"",tx)).verdict().contains("후보"));
   ok("XPath specific error candidate",last(scan(Engine.Profile.XPATH,"/xpath",List.of("'"),"",tx)).verdict().contains("오류 후보"));
   ok("XPath normal reflection excluded",!last(scan(Engine.Profile.XPATH,"/echo",List.of("'"),"",tx)).verdict().contains("후보"));
   ok("LDAP specific error candidate",last(scan(Engine.Profile.LDAP,"/ldap",List.of("("),"",tx)).verdict().contains("오류 후보"));
   ok("LDAP normal reflection excluded",!last(scan(Engine.Profile.LDAP,"/echo",List.of("("),"",tx)).verdict().contains("후보"));
   ok("NoSQL Mongo error candidate",last(scan(Engine.Profile.NOSQL,"/nosql",List.of("{\"$pr_invalid_operator\":1}"),"",tx)).verdict().contains("오류 후보"));
   ok("NoSQL JSON reflection excluded",!last(scan(Engine.Profile.NOSQL,"/echo",Engine.defaults(Engine.Profile.NOSQL),"",tx)).verdict().contains("후보"));
   ok("reflected error signature itself excluded",!last(scan(Engine.Profile.NOSQL,"/echo",List.of("MongoServerError"),"",tx)).verdict().contains("후보"));
   ok("command composed output candidate",last(scan(Engine.Profile.COMMAND,"/command",Engine.defaults(Engine.Profile.COMMAND),"",tx)).verdict().equals("명령 출력 후보"));
   ok("command input reflection excluded",!last(scan(Engine.Profile.COMMAND,"/echo",Engine.defaults(Engine.Profile.COMMAND),"",tx)).verdict().contains("후보"));
   String internal=Engine.defaults(Engine.Profile.XXE).get(0);
   ok("real XML internal entity expansion observed",last(scan(Engine.Profile.XXE,"/xml",List.of(internal),"",tx)).verdict().equals("XML 엔티티 확장 관찰"));
   ok("DTD-disabled XML route rejected",!last(scan(Engine.Profile.XXE,"/xmlfixed",List.of(internal),"",tx)).verdict().equals("XML 엔티티 확장 관찰"));
   ok("raw XML reflection is not entity expansion",!last(scan(Engine.Profile.XXE,"/echo",List.of(internal),"",tx)).verdict().equals("XML 엔티티 확장 관찰"));
   String external="<!DOCTYPE r [<!ENTITY pr SYSTEM \""+fixture.toUri()+"\">]><r>&pr;</r>";
   ok("real XML owned file entity proof",last(scan(Engine.Profile.XXE,"/xml",List.of(external),"PR_OWNED_XML_FIXTURE",tx)).verdict().equals("지정 파일 내용 발견"));
   ok("XXE fixed route no file proof",!last(scan(Engine.Profile.XXE,"/xmlfixed",List.of(external),"PR_OWNED_XML_FIXTURE",tx)).verdict().equals("지정 파일 내용 발견"));
   ok("path traversal file signature candidate",last(scan(Engine.Profile.TRAVERSAL,"/file",List.of("../../etc/passwd"),"",tx)).verdict().equals("파일 내용 후보"));
   ok("path traversal reflection excluded",!last(scan(Engine.Profile.TRAVERSAL,"/echo",List.of("../../etc/passwd"),"",tx)).verdict().contains("후보"));
   String owned="http://127.0.0.1:"+port+"/receiver?m={{MARKER}}";
   ok("SSRF owned receiver fetched over HTTP",last(scan(Engine.Profile.SSRF,"/ssrf",List.of(owned),"PR_OWNED_HTTP_FIXTURE",tx)).verdict().equals("지정 응답 문자열 후보")&&receiverHits.get()==1);
   ok("SSRF URL acceptance alone is not proof",!last(scan(Engine.Profile.SSRF,"/echo",List.of(owned),"",tx)).verdict().contains("후보"));
   ok("custom expectation cannot be its own reflected payload",!last(scan(Engine.Profile.CUSTOM,"/echo",List.of("EXPECTED_PROOF"),"EXPECTED_PROOF",tx)).verdict().contains("후보"));
  }finally{server.stop(0);exec.shutdownNow();Files.deleteIfExists(fixture);}
 }
}
