package kr.payloadrunner;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.atomic.*;import java.util.zip.*;
import com.sun.net.httpserver.HttpServer;
public final class EngineTest {
 public static int passed;static void ok(String n,boolean v){if(!v)throw new AssertionError(n);passed++;System.out.println("PASS "+n);}
 public static byte[] http(String host,int port,byte[] request)throws Exception{try(Socket s=new Socket(host,port)){s.setSoTimeout(10000);s.getOutputStream().write(request);return s.getInputStream().readAllBytes();}}
 static String template(String path){return "GET "+path+"?q=§PAYLOAD§ HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";}
 static byte[] raw(int status,String mime,String body){byte[] b=body.getBytes(StandardCharsets.UTF_8);String h="HTTP/1.1 "+status+" OK\r\nContent-Type: "+mime+"\r\nContent-Length: "+b.length+"\r\n\r\n";var o=new ByteArrayOutputStream();try{o.write(h.getBytes(StandardCharsets.US_ASCII));o.write(b);}catch(Exception e){throw new RuntimeException(e);}return o.toByteArray();}
 static List<Engine.Row> scan(Engine.Profile p,String path,List<String> payloads,Engine.Transport t)throws Exception{List<Engine.Row> out=new ArrayList<>();Engine.run(new Engine.Config(p,Engine.Encoding.URL,template(path),"alpha",payloads,0,100,"PR_DOWNLOAD_FIXTURE"),t,new AtomicBoolean(),out::add);return out;}
 static Engine.Row last(List<Engine.Row> rows){return rows.get(rows.size()-1);}
 static void bad(String n,Runnable r){boolean rejected=false;try{r.run();}catch(IllegalArgumentException e){rejected=true;}ok(n,rejected);}
 public static void main(String[] args)throws Exception{
  byte[] rendered=Engine.render("POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\nX-Test: end\r\n\r\n§PAYLOAD§","한글🙂",Engine.Encoding.RAW);String header=new String(rendered,StandardCharsets.ISO_8859_1);ok("UTF-8 payload byte length recalculated",header.contains("Content-Length: 10\r\n\r\n"));ok("following header survives length replacement",header.contains("X-Test: end\r\nContent-Length"));
  rendered=Engine.render("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n§PAYLOAD§","hello",Engine.Encoding.RAW);ok("Content-Length last header has one boundary",new String(rendered,StandardCharsets.US_ASCII).endsWith("Content-Length: 5\r\n\r\nhello"));
  ok("URL percent encoding UTF8 and plus",Engine.urlEncode("a+b 한").equals("a%2Bb%20%ED%95%9C"));
  rendered=Engine.render("POST / HTTP/1.1\r\nHost: localhost\r\n\r\n{\"x\":\"§PAYLOAD§\"}","a\"b\n한",Engine.Encoding.JSON);ok("JSON escaping prevents broken string",new String(rendered,StandardCharsets.US_ASCII).contains("a\\\"b\\n\\ud55c"));
  bad("missing marker rejected",()->Engine.render("GET / HTTP/1.1\r\n\r\n","x",Engine.Encoding.URL));bad("duplicate markers rejected",()->Engine.render(template("/§PAYLOAD§"),"x",Engine.Encoding.URL));bad("chunked request rejected",()->Engine.render("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n§PAYLOAD§","x",Engine.Encoding.RAW));bad("compressed request rejected",()->Engine.render("POST / HTTP/1.1\r\nContent-Encoding: gzip\r\n\r\n§PAYLOAD§","x",Engine.Encoding.RAW));
  byte[] binary=new byte[]{0,(byte)255,1};String bs=new String(binary,StandardCharsets.ISO_8859_1);rendered=Engine.render("POST / HTTP/1.1\r\nHost: localhost\r\n\r\n"+bs+"§PAYLOAD§","x",Engine.Encoding.RAW);ok("multipart binary byte view preserved",Arrays.equals(binary,Arrays.copyOfRange(rendered,Engine.headerEnd(rendered)+4,Engine.headerEnd(rendered)+7)));
  var zipped=new ByteArrayOutputStream();try(var gz=new GZIPOutputStream(zipped)){gz.write("한글 compressed".getBytes(StandardCharsets.UTF_8));}var gzraw=new ByteArrayOutputStream();gzraw.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Encoding: gzip\r\n\r\n".getBytes());gzraw.write(zipped.toByteArray());ok("gzip response decoded",Engine.parse(gzraw.toByteArray()).text().equals("한글 compressed"));
  ok("chunked HTTP body decoded",Engine.parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n".getBytes()).text().equals("hello"));
  ok("response analysis and raw retention bounded",Engine.parse(raw(200,"text/plain","a".repeat(Engine.LIMIT+100))).truncated()&&Engine.parse(raw(200,"text/plain","a".repeat(Engine.LIMIT+100))).raw().length<=Engine.RETAIN);
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",x->{try{String path=x.getRequestURI().getPath(),query=x.getRequestURI().getRawQuery(),q=query==null?"":URLDecoder.decode(query.substring(2),StandardCharsets.UTF_8);String body="baseline";int code=200;String mime="text/plain";
   if(path.equals("/xss")){body="<html>"+q+"</html>";mime="text/html";}else if(path.equals("/xssfixed")){body="<html>"+q.replace("<","&lt;").replace(">","&gt;")+"</html>";mime="text/html";}else if(path.equals("/json")){body=q;mime="application/json";}else if(path.equals("/sql")){if(q.equals("'"))body="You have an error in your SQL syntax near quote";else if(q.contains("1=2"))body="no rows";else body="normal rows";}else if(path.equals("/sqlfixed"))body="normal rows";else if(path.equals("/echo"))body=q;else if(path.equals("/waf")){body=q.contains("1=2")?"blocked":"normal rows";if(q.contains("1=2"))code=403;}else if(path.equals("/download")){body=q.contains("passwd")?"root:x:0:0:root:/root:/bin/bash\n":q.contains("fixture")?"PR_DOWNLOAD_FIXTURE":"not found";}else if(path.equals("/blocked"))code=403;else if(path.equals("/upload"))body="{\"id\":\"training-upload\"}";
   byte[] out=body.getBytes(StandardCharsets.UTF_8);x.getRequestBody().readAllBytes();x.getResponseHeaders().set("Content-Type",mime);x.getResponseHeaders().set("Connection","close");x.sendResponseHeaders(code,out.length);x.getResponseBody().write(out);x.close();}catch(Exception e){x.close();}});server.start();int port=server.getAddress().getPort();Engine.Transport tx=r->http("127.0.0.1",port,r);
  try{
   var rows=scan(Engine.Profile.XSS,"/xss",List.of("<svg onload=console.log('{{MARKER}}')>"),tx);ok("real HTTP raw HTML reflection candidate",last(rows).verdict().equals("XSS 반사 후보"));
   ok("escaped HTML not reported as XSS",!last(scan(Engine.Profile.XSS,"/xssfixed",List.of("<svg onload=console.log('{{MARKER}}')>"),tx)).verdict().equals("XSS 반사 후보"));
   ok("JSON reflection not reported as HTML XSS",!last(scan(Engine.Profile.XSS,"/json",List.of("<svg onload=console.log('{{MARKER}}')>"),tx)).verdict().equals("XSS 반사 후보"));
   ok("generic database text is not SQL error evidence",!last(scan(Engine.Profile.SQLI,"/echo",List.of("database unavailable"),tx)).verdict().equals("SQL 오류 후보"));
   ok("reflected SQL error signature not treated as database error",!last(scan(Engine.Profile.SQLI,"/echo",List.of("SQLSTATE[42000]: reflected probe"),tx)).verdict().equals("SQL 오류 후보"));
   ok("new SQL error detected via HTTP",last(scan(Engine.Profile.SQLI,"/sql",List.of("'"),tx)).verdict().equals("SQL 오류 후보"));
   ok("SQL true false differential candidate",last(scan(Engine.Profile.SQLI,"/sql",List.of("{{BASE}} AND 1=1","{{BASE}} AND 1=2"),tx)).verdict().equals("SQL 조건 차이 후보"));
   ok("fixed SQL route no differential candidate",!last(scan(Engine.Profile.SQLI,"/sqlfixed",List.of("{{BASE}} AND 1=1","{{BASE}} AND 1=2"),tx)).verdict().contains("후보"));
   ok("reflected SQL pair not mistaken for SQL condition evidence",!last(scan(Engine.Profile.SQLI,"/echo",List.of("{{BASE}} AND 1=1","{{BASE}} AND 1=2"),tx)).verdict().equals("SQL 조건 차이 후보"));
   ok("WAF 403 remains inconclusive rather than SQL candidate",last(scan(Engine.Profile.SQLI,"/waf",List.of("{{BASE}} AND 1=1","{{BASE}} AND 1=2"),tx)).verdict().equals("인증/인가 응답"));
   ok("file content signature found",last(scan(Engine.Profile.DOWNLOAD,"/download",List.of("../../etc/passwd"),tx)).verdict().equals("파일 내용 후보"));
   ok("custom fixture proof found",last(scan(Engine.Profile.DOWNLOAD,"/download",List.of("../fixtures/download-marker.txt"),tx)).verdict().equals("지정 파일 내용 발견"));
   ok("proof reflection alone not accepted as file content",!last(scan(Engine.Profile.DOWNLOAD,"/echo",List.of("PR_DOWNLOAD_FIXTURE"),tx)).verdict().equals("지정 파일 내용 발견"));
   ok("403 not interpreted as successful download",last(scan(Engine.Profile.DOWNLOAD,"/blocked",List.of("../../etc/passwd"),tx)).verdict().equals("인증/인가 응답"));
   ok("upload 2xx only indicates acceptance",last(scan(Engine.Profile.UPLOAD_NAME,"/upload",List.of("probe.html"),tx)).verdict().equals("업로드 수락 후보"));
   AtomicInteger sends=new AtomicInteger();var stopped=new AtomicBoolean();List<Engine.Row> stoppedRows=new ArrayList<>();var summary=Engine.run(new Engine.Config(Engine.Profile.XSS,Engine.Encoding.URL,template("/xss"),"",List.of("a","b"),0,10,""),r->{sends.incrementAndGet();return tx.send(r);},stopped,r->{stoppedRows.add(r);stopped.set(true);});ok("Stop prevents additional request after in-flight finishes",sends.get()==1&&summary.stopped());
   sends.set(0);summary=Engine.run(new Engine.Config(Engine.Profile.SQLI,Engine.Encoding.URL,template("/sql"),"",List.of("a","b","c"),0,2,""),r->{sends.incrementAndGet();return tx.send(r);},new AtomicBoolean(),r->{});ok("budget includes baseline and SQL stability repeat",sends.get()==2&&summary.budgetReached());
   sends.set(0);summary=Engine.run(new Engine.Config(Engine.Profile.XSS,Engine.Encoding.URL,template("/xss"),"",List.of("a","b"),0,10,""),r->{if(sends.incrementAndGet()==2)throw new IOException("fixture transport error");return tx.send(r);},new AtomicBoolean(),stoppedRows::add);ok("payload transport error recorded and next payload continues",summary.completed()==2&&stoppedRows.stream().anyMatch(r->r.verdict().equals("전송/분석 오류")));
   kr.payloadrunner.ExtenderTest.run(port);
   LegacyTransportRaceTest.run();
   AdditionalTest.run();
   AdaptiveTest.run();
   EvidenceTest.run();
  }finally{server.stop(0);}
  if(args.length>0){CommunityTest.run(Integer.parseInt(args[0]));int lab=Integer.parseInt(args[0]);Engine.Transport real=r->http("127.0.0.1",lab,r);ok("community vulnerable search SQL error detected",last(scan(Engine.Profile.SQLI,"/api/community/vuln/search",List.of("'"),real)).verdict().equals("SQL 오류 후보"));ok("community fixed search no SQL error",!last(scan(Engine.Profile.SQLI,"/api/community/fixed/search",List.of("'"),real)).verdict().equals("SQL 오류 후보"));}
  System.out.println("TEST_SUMMARY passed="+passed+" failed=0 (HTTP fixture/API adapter; not actual Burp runtime)");
 }
 public static void assertTest(String n,boolean v){ok(n,v);}
}

