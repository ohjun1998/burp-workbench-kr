package kr.workbench;

import kr.payloadrunner.Engine;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real loopback HTTP, using the selected release JAR's production engine; no Burp host. */
public class AssessmentReplay {
  static final HttpClient CLIENT=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
  static byte[] send(String origin,byte[] raw) throws Exception {
    String request=new String(raw,StandardCharsets.ISO_8859_1);int split=request.indexOf("\r\n\r\n");
    String[] lines=request.substring(0,split).split("\r\n"),first=lines[0].split(" ");
    URI url=URI.create(origin+first[1]);if(!url.getHost().equals("127.0.0.1"))throw new IllegalArgumentException("Loopback only");
    var builder=java.net.http.HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30));
    for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');String name=lines[i].substring(0,colon);if(!Set.of("host","content-length","connection").contains(name.toLowerCase(Locale.ROOT)))builder.header(name,lines[i].substring(colon+1).strip());}
    builder.method(first[0],java.net.http.HttpRequest.BodyPublishers.ofByteArray(Arrays.copyOfRange(raw,split+4,raw.length)));
    var response=CLIENT.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
    var header=new StringBuilder("HTTP/1.1 "+response.statusCode()+" Result\r\n");
    // HttpClient already removes transfer chunk framing from the body.
    response.headers().map().forEach((key,values)->{if(!key.equalsIgnoreCase("transfer-encoding"))values.forEach(value->header.append(key).append(": ").append(value).append("\r\n"));});
    return (header+"\r\n"+new String(response.body(),StandardCharsets.ISO_8859_1)).getBytes(StandardCharsets.ISO_8859_1);
  }
  public static void main(String[] args) throws Exception {
    var spec=(List<?>)MinerJson.parse(Files.readString(Path.of(args[1])),16*1024*1024,200000);var results=new ArrayList<Object>();
    for(Object item:spec){var test=(Map<?,?>)item;String id=test.get("id").toString();String template=test.get("template").toString().replace("HOST_PORT",URI.create(args[0]).getAuthority());
      if(test.get("mode").equals("response")){
        byte[] response=send(args[0],template.getBytes(StandardCharsets.ISO_8859_1));var parsed=Engine.parse(response);String headers=new String(response,StandardCharsets.ISO_8859_1).split("\r\n\r\n",2)[0];
        var findings=PassiveFindings.analyze(args[0]+test.get("path"),parsed.status(),headers,parsed.text());
        var record=new LinkedHashMap<String,Object>();record.put("id",id);record.put("status",parsed.status());record.put("rules",findings.stream().map(f->Map.of("rule",f.rule(),"location",f.location(),"evidence",f.evidence())).toList());
        record.put("body",parsed.text().replaceAll("(?i)(\"(?:streamKey|authToken|password)\"\\s*:\\s*\")[^\"]*","$1[REDACTED]"));results.add(record);continue;
      }
      List<Engine.Row> rows=new ArrayList<>();var config=new Engine.Config(Engine.Profile.XSS,Engine.Encoding.URL,template,"baseline",Engine.defaults(Engine.Profile.XSS),0,50,"");
      var summary=Engine.run(config,raw->send(args[0],raw),new AtomicBoolean(),rows::add);
      var record=new LinkedHashMap<String,Object>();record.put("id",id);record.put("candidate",rows.stream().anyMatch(row->row.verdict().equals("XSS 반사 후보")));record.put("requests",summary.requests());record.put("sourceTruth",test.get("truth"));
      record.put("rows",rows.stream().map(row->Map.of("payload",row.payload(),"status",row.response()==null?0:row.response().status(),"verdict",row.verdict(),"evidence",row.evidence(),"request",new String(row.request(),StandardCharsets.ISO_8859_1),"body",row.response()==null?"":row.response().text())).toList());results.add(record);
    }
    Files.writeString(Path.of(args[2]),MetadataExport.json(results));System.out.println("Completed "+results.size()+" real HTTP cases using Workbench "+BuildInfo.VERSION);
  }
}
