package kr.workbench;

import java.io.*;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ast.*;

/** Explicit local export only. Exact response values never enter the normal snapshot or history. */
final class ScopeInspectionReport {
  static final int MAX_BODY = 5 * 1024 * 1024, MAX_PACKET = 8 * 1024 * 1024, MAX_HEADERS = 64 * 1024;
  static final int MAX_VALUES = 10_000, MAX_VALUE = 64 * 1024, MAX_TOTAL_VALUES = 16 * 1024 * 1024, MAX_TOTAL_SOURCE = 64 * 1024 * 1024;
  record Summary(int pages, int values, int partialPages, boolean complete, String note) {}
  record Source(String originalId, String account, String profileRevision, String url, String method, int status,
                String responseSha256, String area, String locator, int line, int offset, int length) {}
  record Value(String category, String name, String value, String rawText, String valueType, String classification,
               String provenance, String confidence, Source source, Map<String,String> attributes) {
    Value { attributes = Map.copyOf(attributes); }
    @Override public String toString() { return "Value[category="+category+", chars="+value.length()+", original="+source.originalId()+", value hidden]"; }
  }
  record Page(String originalId, String account, String profileRevision, String url, String method, int status,
              String responseSha256, int responseBytes, int decodedCharacters, boolean complete, String note) {}
  record Document(String runId, String state, List<Value> values, List<Page> pages, boolean complete, List<String> notes) {
    Document { values=List.copyOf(values);pages=List.copyOf(pages);notes=List.copyOf(notes); }
    @Override public String toString() { return "Document[pages="+pages.size()+", values="+values.size()+", complete="+complete+", values hidden]"; }
  }
  private record Provider(String name, String category, Pattern pattern) {}
  private static final List<Provider> PROVIDERS=List.of(
      provider("AWS access key ID","KEY","(?:AKIA|ASIA)[A-Z0-9]{16}"),provider("Google API key","KEY","AIza[0-9A-Za-z_-]{35}"),
      provider("GitHub token","TOKEN","(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{40,255})"),
      provider("Slack token","TOKEN","xox[baprs]-[0-9A-Za-z-]{10,200}"),provider("Stripe key","KEY","(?:sk|pk)_(?:live|test)_[A-Za-z0-9]{16,200}"),
      provider("Groq key","KEY","gsk_[A-Za-z0-9]{20,200}"),provider("JWT / JWS","TOKEN","eyJ[A-Za-z0-9_-]{4,8192}\\.[A-Za-z0-9_-]{1,8192}\\.[A-Za-z0-9_-]{0,8192}"),
      provider("DB connection string","DATABASE","(?i)(?:jdbc:(?:oracle|postgresql|mysql|mariadb|sqlserver|sqlite|h2):|(?:mongodb(?:\\+srv)?|postgres(?:ql)?|mysql|redis(?:s)?)://)[^\\s\"'<>]{1,4096}"),
      provider("Private IP","INTERNAL","(?<![0-9.])(?:10\\.(?:[0-9]{1,3}\\.){2}[0-9]{1,3}|192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}|172\\.(?:1[6-9]|2[0-9]|3[01])\\.[0-9]{1,3}\\.[0-9]{1,3})(?![0-9.])"),
      provider("Internal host","INTERNAL","(?i)\\b[a-z0-9][a-z0-9.-]{0,120}\\.(?:internal|local|corp|lan)\\b"),
      provider("Server path","PATH","(?i)(?:[A-Z]:\\\\[^\\s\"'<>]{1,512}|/(?:var|etc|opt|home|usr|srv)/[^\\s\"'<>]{1,512})"),
      provider("Email","SENSITIVE","(?i)\\b[A-Z0-9._%+-]{1,64}@[A-Z0-9.-]{1,128}\\.[A-Z]{2,20}\\b"),
      provider("Error implementation marker","OTHER","(?:ORA-[0-9]{5}|SQLSTATE\\[[A-Z0-9]{1,12}\\]|(?:java|org|com)\\.[A-Za-z0-9_.$]{1,160}(?:Exception|Error))"));
  private ScopeInspectionReport() {}
  private static Provider provider(String name,String category,String regex){return new Provider(name,category,Pattern.compile(regex));}

  static Summary write(Path destinationZip, ScopeInspection.Snapshot snapshot) throws IOException {
    Objects.requireNonNull(destinationZip); Objects.requireNonNull(snapshot); interrupted();
    Path destination=destinationZip.toAbsolutePath().normalize(),parent=destination.getParent();
    if(parent==null||!Files.isDirectory(parent))throw new IOException("보고서 저장 폴더가 없습니다.");
    if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(destination.toString());
    Document document=collect(snapshot); interrupted();
    Path temporary=Files.createTempFile(parent,".workbench-response-report-",".tmp");
    try {
      privateFile(temporary);
      try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temporary))){
        entry(zip,"report.json",json(documentMap(document))); entry(zip,"report.html",html(document)); entry(zip,"values.csv",csv(document));
        entry(zip,"manifest.json",json(Map.of("schemaVersion",1,"producerVersion",BuildInfo.VERSION,"createdAt",Instant.now().toString(),
            "containsUnmaskedResponseValues",true,"containsRequestHeaders",false,"coordinateBasis","UTF-16 characters; decoded response body or normalized response header block; not wire-byte offsets",
            "machineValueAuthority","report.json preserves parsed value and rawText separately; CSV may prefix an apostrophe to prevent formulas",
            "complete",document.complete(),"notes",document.notes())));
      }
      try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);} interrupted();
      // Same-directory hard-link publication is atomic create-new: an existing file is never replaced.
      try { Files.createLink(destination,temporary); }
      catch(UnsupportedOperationException error){throw new IOException("이 파일시스템은 원자적 새 보고서 게시를 지원하지 않습니다. 다른 로컬 폴더를 선택하세요.",error);}
      try { interrupted(); } catch(InterruptedIOException cancelled){if(Files.isSameFile(destination,temporary))Files.deleteIfExists(destination);throw cancelled;}
      int partial=(int)document.pages().stream().filter(page->!page.complete()).count();
      return new Summary(document.pages().size(),document.values().size(),partial,document.complete(),document.complete()?"응답 실제값 보고서 저장 완료":"응답 실제값 보고서 저장 · 부분/제외 범위는 manifest와 페이지 기록 확인");
    } finally {Files.deleteIfExists(temporary);}
  }

  static Document collect(ScopeInspection.Snapshot snapshot) throws IOException {
    Collector collector=new Collector(snapshot); Set<String> seen=new HashSet<>(); int scanned=0;
    for(var page:snapshot.pages()){
      interrupted(); var packet=page.original(); String id=packet==null?"":packet.id();
      if(!seen.add(id+"|"+page.account()+"|"+page.profileRevision()+"|"+page.url()+"|"+page.method()))continue;
      if(collector.pages.size()>=500){collector.note("페이지 500개 제한 · 나머지 원본은 보고서 값 추출 미검사");break;}
      if(packet==null||!packet.hasResponse()){collector.pages.add(page(page,id,"",0,0,false,"응답 원본 없음 · 값 추출 미검사"));continue;}
      if(packet.responseLength()>MAX_PACKET){collector.pages.add(page(page,id,"",packet.responseLength(),0,false,"응답 원본 8 MiB 초과 · 값 추출 미검사"));continue;}
      String digest=""; int characters=0; Context context=null;
      try {
        byte[] raw=packet.responsePrefix(packet.responseLength()); digest=sha256(raw);
        var response=packet.response(); if(response==null)throw new IOException();
        String headers=EnvironmentPanel.responseHeaders(response);
        if(headers.length()>MAX_HEADERS)throw new IOException("header limit");
        byte[] bytes=response.body()==null?new byte[0]:response.body().getBytes();
        String type=EnvironmentResponse.header(headers,"Content-Type"),body="";
        context=new Context(collector,page,id,response.statusCode(),digest,headers,"");
        context.headers();
        if(bytes.length>MAX_BODY){context.partial("응답 본문 5 MiB 초과 · 본문 미검사");}
        else if(!TextCandidates.accepts(type,packet.url())){context.partial("비텍스트 형식 · 본문 값 추출 미검사");}
        else {
          body=JsBodyDecoder.decode(bytes,EnvironmentResponse.header(headers,"Content-Encoding"),type,MAX_BODY);
          if(!TextCandidates.textual(body)){context.partial("비텍스트 본문 · 값 추출 미검사");}
          else if((long)scanned+body.length()>MAX_TOTAL_SOURCE){context.partial("실행별 본문 64 Mi 문자 제한 · 본문 미검사");}
          else {scanned+=body.length();characters=body.length();context.setBody(body);context.body(type);}
        }
        collector.pages.add(page(page,id,digest,raw.length,characters,page.complete()&&context.complete,context.notes.isEmpty()?"응답 값 추출 범위 내 완료":String.join(" · ",context.notes)));
      } catch(InterruptedIOException cancelled){throw cancelled;}
      catch(Exception|LinkageError error){collector.pages.add(page(page,id,digest,packet.responseLength(),characters,false,"원본 읽기·디코딩·파싱 제한으로 일부 또는 전체 값 추출 미완료"));}
    }
    boolean complete=snapshot.state()==ScopeInspection.State.COMPLETED&&snapshot.pending()==0&&snapshot.failed()==0&&snapshot.partial()==0
        &&collector.pages.stream().allMatch(Page::complete)&&collector.notes.isEmpty();
    if(snapshot.state()!=ScopeInspection.State.COMPLETED||snapshot.pending()>0||snapshot.failed()>0||snapshot.partial()>0)collector.note("원래 실행에 중지·실패·부분·미검사 범위가 있음 · 보고서 미발견은 안전 판정이 아님");
    return new Document(snapshot.id(),snapshot.state().name(),collector.values,collector.pages,complete,collector.notes);
  }
  private static Page page(ScopeInspection.Page p,String id,String digest,int bytes,int chars,boolean complete,String note){return new Page(id,p.account(),p.profileRevision(),ScopeInspectionAnalysis.safeUrl(p.url()),p.method(),p.status(),digest,bytes,chars,complete,note);}
  private static final class Collector {
    final ScopeInspection.Snapshot snapshot;final List<Value>values=new ArrayList<>();final List<Page>pages=new ArrayList<>();final List<String>notes=new ArrayList<>();int valueCharacters;
    Collector(ScopeInspection.Snapshot snapshot){this.snapshot=snapshot;}
    void note(String value){if(notes.size()<100&&!notes.contains(value))notes.add(value);}
  }
  private static final class Context {
    final Collector collector;final ScopeInspection.Page page;final String original,digest,headers;final int status;final List<ScopeInspection.Finding>findings;final List<String>notes=new ArrayList<>();
    String text;int[] newlines=new int[0];boolean complete=true;int nodes,expressions;final List<int[]>excludedExpressions=new ArrayList<>();
    Context(Collector collector,ScopeInspection.Page page,String original,int status,String digest,String headers,String text){
      this.collector=collector;this.page=page;this.original=original;this.status=status;this.digest=digest;this.headers=headers;this.text=text;
      findings=collector.snapshot.findings().stream().filter(f->f.originalId().equals(original)).toList();
    }
    void setBody(String value){text=value;newlines=java.util.stream.IntStream.range(0,value.length()).filter(i->value.charAt(i)=='\n').toArray();}
    void partial(String note){complete=false;if(notes.size()<16&&!notes.contains(note))notes.add(note);}
    void add(String category,String name,String value,String type,String area,String locator,int start,int length,Map<String,String> attributes)throws IOException{
      interrupted();String source=area.equals("RESPONSE_HEADERS")?headers:text;
      if(start<0||length<0||start>source.length()-length){partial("소스 위치 미확정 항목 제외");return;}
      if(value.length()>MAX_VALUE||length>MAX_VALUE){partial("64 Ki 문자 초과 값 제외 · 길이 "+Math.max(value.length(),length)+" · 원본에서 확인");return;}
      if(collector.values.size()>=MAX_VALUES||(long)collector.valueCharacters+value.length()+length>MAX_TOTAL_VALUES){partial("실행별 값 개수/16 Mi 문자 제한 · 추가 값 제외");collector.note("값 추출 결과 한도 도달 · 미출력 값은 원본에서 확인");return;}
      if(collector.values.stream().anyMatch(old->old.source().originalId().equals(original)&&old.source().account().equals(page.account())&&old.source().profileRevision().equals(page.profileRevision())
          &&old.source().area().equals(area)&&old.category().equals(category)&&old.value().equals(value)&&overlap(old.source().offset(),old.source().length(),start,length)))return;
      String classification=classification(name,value);String raw=source.substring(start,start+length);
      String provenance=product(category,name,value,area,start,length)?"PRODUCT_FINDING":"REPORT_SUPPLEMENT";
      int line=area.equals("RESPONSE_HEADERS")?line(headers,start):1+insertion(newlines,start);
      Source origin=new Source(original,page.account(),page.profileRevision(),ScopeInspectionAnalysis.safeUrl(page.url()),page.method(),status,digest,area,locator,line,start,length);
      collector.values.add(new Value(category,name,value,raw,type,classification,provenance,
          type.equals("RAW_LITERAL_UNDECODED")?"미지원 엔터티 · value는 원문 리터럴, 파싱값 미확정":category.equals("API")||category.equals("PATH")?"관측 리터럴·실제 도달 별도":"관측 값·용도/유효성 미검증",origin,attributes));collector.valueCharacters+=value.length()+raw.length();
    }
    boolean product(String category,String name,String value,String area,int offset,int length){
      String fingerprint=ScopeInspectionAnalysis.hash(value);
      for(var finding:findings){
        if(!finding.account().equals(page.account())&&!finding.account().equals("전체"))continue;
        if(!compatibleCategory(category,finding.category()))continue;
        if(area.equals("DECODED_RESPONSE_BODY")&&finding.offset()>=0&&finding.offset()>=offset&&finding.offset()<offset+Math.max(1,length))return true;
        if(!finding.fingerprint().isEmpty()&&(finding.fingerprint().equals(fingerprint)||finding.fingerprint().equals(fingerprint.substring(0,16))))return true;
        if(Set.of("API","PATH").contains(category)&&finding.category().equals("API")&&uriLike(value))try{
          if(ScopeInspectionAnalysis.safeUrl(URI.create(page.url()).resolve(value).toString()).equals(finding.url()))return true;
        }catch(RuntimeException ignored){}
        if(category.equals("ENVIRONMENT")&&finding.category().equals("ENVIRONMENT"))for(String product:List.of("nginx","apache","tomcat","jetty","php","asp.net","spring","cloudflare","cloudfront","express","iis"))
          if(value.toLowerCase(Locale.ROOT).contains(product)&&finding.title().toLowerCase(Locale.ROOT).contains(product))return true;
      }return false;
    }
    void headers()throws IOException{
      int start=0,index=0;
      while(start<headers.length()){
        interrupted();int end=headers.indexOf('\n',start);if(end<0)end=headers.length();String row=headers.substring(start,end);int colon=row.indexOf(':');
        if(colon>0){String name=row.substring(0,colon),value=row.substring(colon+1).strip();int valueStart=start+colon+1;while(valueStart<end&&Character.isWhitespace(headers.charAt(valueStart)))valueStart++;
          String lower=name.toLowerCase(Locale.ROOT),category=category(name,value);
          if(lower.equals("set-cookie")){
            int eq=value.indexOf('='),semi=value.indexOf(';');if(eq>0){int finish=semi<0?value.length():semi;String cookie=value.substring(eq+1,finish);add("TOKEN",value.substring(0,eq),cookie,"STRING","RESPONSE_HEADERS","Set-Cookie["+index+"]",valueStart+eq+1,cookie.length(),Map.of("kind","response cookie","attributes",semi<0?"":value.substring(semi+1)));}
          }else if(lower.equals("authorization")||lower.equals("proxy-authorization")){
            int space=value.indexOf(' ');int begin=space<0?0:space+1;add("TOKEN",name,value.substring(begin),"STRING","RESPONSE_HEADERS",name,valueStart+begin,value.length()-begin,Map.of("scheme",space<0?"":value.substring(0,space)));
          }else if(Set.of("server","x-powered-by","x-aspnet-version","x-aspnetmvc-version","via","x-cache","cf-ray","cf-cache-status","x-amz-cf-id","x-amz-cf-pop").contains(lower))
            add("ENVIRONMENT",name,value,"STRING","RESPONSE_HEADERS",name,valueStart,value.length(),Map.of("inference","배너 관측 · 실제 배포 구성 확정 아님"));
          else if(lower.equals("location"))add("PATH",name,value,"STRING","RESPONSE_HEADERS",name,valueStart,value.length(),apiAttributes(value,"?"));
          else if(category!=null||lower.startsWith("access-control-")||Set.of("content-security-policy","strict-transport-security","x-content-type-options").contains(lower))
            add(category==null?"OTHER":category,name,value,"STRING","RESPONSE_HEADERS",name,valueStart,value.length(),Map.of());
        }start=end+1;index++;
      }
    }
    void body(String type)throws IOException{
      String trimmed=text.stripLeading();boolean json=type.toLowerCase(Locale.ROOT).contains("json")||trimmed.startsWith("{")||trimmed.startsWith("[");
      boolean markup=trimmed.startsWith("<")||type.toLowerCase(Locale.ROOT).contains("html")||type.toLowerCase(Locale.ROOT).contains("xml");
      if(json){try{json(text,0);}catch(IllegalArgumentException error){partial("JSON 구조·크기·노드 제한 또는 형식 오류 · 보조 리터럴만 확인");plain(text,0,false);}}
      else if(markup)markup();
      else if(type.toLowerCase(Locale.ROOT).contains("javascript")||page.url().split("[?#]",2)[0].matches("(?i).*\\.(?:js|mjs|cjs)$"))javascript(text,0);
      else plain(text,0,true);
      providers();if(expressions>0)notes.add("코드 식 "+expressions+"개는 실제 값으로 출력하지 않음");
      if(findings.stream().anyMatch(f->f.offset()<0&&f.detail().toLowerCase(Locale.ROOT).contains("source map"))||text.contains("\"sourcesContent\""))
        partial("소스맵 복원 코드의 가상 위치는 응답 원문 좌표로 추정하지 않음 · 원문 직접값/패턴만 추출, 복원 내부 코드 미검사");
    }
    void scalar(String name,String value,String type,String locator,int start,int length,Map<String,String> attributes)throws IOException{
      String category=category(name,value);if(category!=null)add(category,name,value,type,"DECODED_RESPONSE_BODY",locator,start,length,attributes);
    }
    void json(String source,int base)throws IOException{
      int before=collector.values.size(),characters=collector.valueCharacters;
      try{new Json(this,source,base).parse();}
      catch(IllegalArgumentException invalid){collector.values.subList(before,collector.values.size()).clear();collector.valueCharacters=characters;throw invalid;}
    }
    void excluded(int start,int length){expressions++;if(excludedExpressions.size()<10_000)excludedExpressions.add(new int[]{start,length});else partial("코드 식 위치 개수 제한");}
    boolean inExpression(int start,int end){return excludedExpressions.stream().anyMatch(span->overlap(span[0],span[1],start,end-start));}
    void plain(String source,int base,boolean unquoted)throws IOException{
      int position=0;
      while(position<source.length()){
        interrupted();char c=source.charAt(position);if(!Character.isJavaIdentifierStart(c)){position++;continue;}
        int begin=position++;while(position<source.length()&&(Character.isJavaIdentifierPart(source.charAt(position))||".-".indexOf(source.charAt(position))>=0))position++;
        if(position-begin>128)continue;String key=source.substring(begin,position);int separator=position;
        while(separator<source.length()&&Character.isWhitespace(source.charAt(separator)))separator++;
        if(separator<source.length()&&(source.charAt(separator)=='\''||source.charAt(separator)=='"'))separator++;
        if(separator>=source.length()||":=".indexOf(source.charAt(separator))<0)continue;
        int valueStart=separator+1;while(valueStart<source.length()&&(source.charAt(valueStart)==' '||source.charAt(valueStart)=='\t'))valueStart++;
        if(valueStart>=source.length())break;char quote=source.charAt(valueStart);int end=valueStart;
        if(quote=='\''||quote=='"'){
          end=quotedEnd(source,valueStart);if(end<0){partial("닫히지 않거나 너무 긴 문자열 리터럴 제외");position=valueStart+1;continue;}
          if(!literalTail(source,end)){if(interesting(key))excluded(base+valueStart,end-valueStart);position=end;continue;}
          String raw=source.substring(valueStart,end),value=decodeQuoted(raw);
          scalar(key,value,"STRING","field:"+key,base+valueStart,end-valueStart,Map.of("parser","직접 문자열 리터럴 보조"));position=end;
        }else{
          while(end<source.length()&&"\r\n,;}".indexOf(source.charAt(end))<0&&end-valueStart<=MAX_VALUE)end++;
          String value=source.substring(valueStart,end).stripTrailing();position=Math.max(position,end);
          if(value.isEmpty())continue;
          if(!unquoted&&!value.matches("(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?)")){if(interesting(key))excluded(base+valueStart,value.length());continue;}
          if(!unquoted&&codeExpression(value)){if(interesting(key))excluded(base+valueStart,value.length());continue;}
          scalar(key,value,scalarType(value),"field:"+key,base+valueStart,value.length(),Map.of("parser","이름/직접 값 보조"));
        }
      }
    }
    void javascript(String source,int base)throws IOException{
      interrupted();if(source.length()>4*1024*1024){partial("JS 단일 본문 4 Mi 문자 초과 · 코드 파싱 미검사, 직접 리터럴 보조");plain(source,base,false);return;}
      try{
        CompilerEnvirons env=new CompilerEnvirons();env.setLanguageVersion(org.mozilla.javascript.Context.VERSION_ES6);env.setRecoverFromErrors(false);
        env.setErrorReporter(new ErrorReporter(){public void warning(String a,String b,int c,String d,int e){}public void error(String a,String b,int c,String d,int e){throw new EvaluatorException("report syntax");}public EvaluatorException runtimeError(String a,String b,int c,String d,int e){return new EvaluatorException("report syntax");}});
        String compatible=JsSyntax.compatible(source).text();if(compatible.length()!=source.length())throw new IllegalArgumentException();
        AstRoot root=new Parser(env).parse(compatible,"report.js",1);long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        AtomicFailure failure=new AtomicFailure();
        root.visit(node->{
          if(failure.error!=null)return false;
          try{
            interrupted();if(System.nanoTime()>deadline||++nodes>100_000)throw new IllegalArgumentException();
            if(node instanceof VariableInitializer variable)jsField(jsKey(variable.getTarget()),variable.getInitializer(),base);
            else if(node instanceof ObjectProperty property)jsField(jsKey(property.getKey()),property.getValue(),base);
            else if(node instanceof Assignment assignment)jsField(jsKey(assignment.getLeft()),assignment.getRight(),base);
            if(node instanceof StringLiteral literal){String value=literal.getValue();if(uriLike(value)){
              int start=base+literal.getAbsolutePosition();int length=literal.getLength();String method="?";
              AstNode parent=literal.getParent();if(parent instanceof FunctionCall call&&call.getArguments().indexOf(literal)==0){String callee=call.getTarget().toSource();if(callee.matches("(?:axios|http|httpClient)\\.(?:get|post|put|patch|delete|head|options)"))method=callee.substring(callee.lastIndexOf('.')+1).toUpperCase(Locale.ROOT);else if(Set.of("fetch","window.fetch","Request").contains(callee)&&call.getArguments().size()==1)method="GET";}
              for(var finding:findings)if(finding.category().equals("API")&&finding.offset()>=start&&finding.offset()<start+length&&!finding.method().equals("?")){method=finding.method();break;}
              add(apiLike(value)?"API":"PATH","JS URL/path literal",value,"STRING","DECODED_RESPONSE_BODY","JS literal",start,length,apiAttributes(value,method));
            }}
          }catch(IOException|RuntimeException error){failure.error=error;return false;}return true;
        });
        if(failure.error instanceof IOException io)throw io;if(failure.error!=null)throw new IllegalArgumentException();
      }catch(InterruptedIOException cancelled){throw cancelled;}
      catch(RuntimeException|StackOverflowError error){partial("JS 구문·시간·노드 제한 · 직접 문자열 리터럴 보조");plain(source,base,false);}
    }
    void jsField(String name,AstNode node,int base)throws IOException{
      if(name==null||node==null)return;while(node instanceof ParenthesizedExpression parent)node=parent.getExpression();String value=null,type="STRING";
      if(node instanceof StringLiteral literal)value=literal.getValue();
      else if(node instanceof NumberLiteral literal){value=literal.getValue();type="NUMBER";}
      else if(node instanceof KeywordLiteral literal&&Set.of(Token.TRUE,Token.FALSE,Token.NULL).contains(literal.getType())){value=literal.toSource();type=scalarType(value);}
      if(value==null){if(interesting(name))excluded(base+node.getAbsolutePosition(),node.getLength());return;}
      scalar(name,value,type,"JS:"+name,base+node.getAbsolutePosition(),node.getLength(),Map.of("parser","JS 직접 리터럴 · 코드 실행 없음"));
    }
    void markup()throws IOException{
      String lower=text.toLowerCase(Locale.ROOT);int cursor=0,tags=0;
      while(cursor<text.length()){
        interrupted();int begin=text.indexOf('<',cursor);if(begin<0)break;int end=tagEnd(text,begin);if(end<0){partial("닫히지 않거나 너무 긴 마크업 태그");break;}cursor=end+1;
        if(++tags>20_000){partial("마크업 태그 개수 제한");break;}
        int position=begin+1;while(position<end&&Character.isWhitespace(text.charAt(position)))position++;int nameStart=position;while(position<end&&Character.isLetterOrDigit(text.charAt(position)))position++;String tag=lower.substring(nameStart,position);
        Map<String,Attr> attrs=new LinkedHashMap<>();
        while(position<end){while(position<end&&(Character.isWhitespace(text.charAt(position))||text.charAt(position)=='/'))position++;int keyStart=position;while(position<end&&(Character.isLetterOrDigit(text.charAt(position))||"_:-".indexOf(text.charAt(position))>=0))position++;
          if(position==keyStart){position++;continue;}String key=lower.substring(keyStart,position);while(position<end&&Character.isWhitespace(text.charAt(position)))position++;if(position>=end||text.charAt(position)!='=')continue;position++;while(position<end&&Character.isWhitespace(text.charAt(position)))position++;
          if(position>=end)break;char quote=text.charAt(position);boolean quoted=quote=='\''||quote=='"';int start=quoted?++position:position;
          while(position<end&&(quoted?text.charAt(position)!=quote:!Character.isWhitespace(text.charAt(position))&&text.charAt(position)!='>'))position++;
          int finish=position;if(quoted&&position<end)position++;EntityValue decoded=entityValue(text.substring(start,finish));String value=decoded.value();attrs.put(key,new Attr(value,start,finish-start,decoded.rawOnly()));
          if(decoded.rawOnly())partial("미지원 HTML/XML 엔터티 · 해당 값은 RAW_LITERAL_UNDECODED 원문 리터럴만 보존");
          if(Set.of("href","src","action","data-url","data-endpoint").contains(key)||interesting(key)){
            String category=Set.of("href","src","action").contains(key)?(apiLike(value)||tag.equals("form")?"API":"PATH"):category(key,value);
            if(category!=null)add(category,tag+"@"+key,value,decoded.rawOnly()?"RAW_LITERAL_UNDECODED":"STRING","DECODED_RESPONSE_BODY",tag+"/@"+key,start,finish-start,decoded.rawOnly()?Map.of("entityDecoding","지원하지 않는 엔터티 · 경로 재구성 미실행"):uriLike(value)?apiAttributes(value,"?"):Map.of("parser","마크업 속성"));
          }
        }
        if(tag.equals("input")&&attrs.containsKey("name")&&attrs.containsKey("value")){Attr value=attrs.get("value");scalar(attrs.get("name").value(),value.value(),value.rawOnly()?"RAW_LITERAL_UNDECODED":"STRING","input[name="+attrs.get("name").value()+"]/@value",value.offset(),value.length(),Map.of("parser","입력 필드 응답 속성 · 실제 제출 미실행"));}
        if(!tag.isEmpty()&&!tag.equals("script")&&interesting(tag)){
          int next=text.indexOf('<',cursor);if(next>cursor&&lower.startsWith("</"+tag,next)){
            int start=cursor,finish=next;while(start<finish&&Character.isWhitespace(text.charAt(start)))start++;while(finish>start&&Character.isWhitespace(text.charAt(finish-1)))finish--;
            if(finish>start){EntityValue decoded=entityValue(text.substring(start,finish));if(decoded.rawOnly())partial("미지원 HTML/XML 엔터티 · 해당 값은 RAW_LITERAL_UNDECODED 원문 리터럴만 보존");scalar(tag,decoded.value(),decoded.rawOnly()?"RAW_LITERAL_UNDECODED":"STRING","element:"+tag,start,finish-start,Map.of("parser","요소 텍스트 · 외부 엔터티 해석 없음"));}
          }
        }
        if(tag.equals("script")){
          int close=lower.indexOf("</script",cursor);if(close<0){partial("닫히지 않은 script 본문");break;}String script=text.substring(cursor,close);
          if(attrs.containsKey("type")&&attrs.get("type").value().toLowerCase(Locale.ROOT).contains("json")){try{json(script,cursor);}catch(IllegalArgumentException error){partial("인라인 JSON 구조 분석 미완료");plain(script,cursor,false);}}
          else if(!attrs.containsKey("src"))javascript(script,cursor);cursor=close;
        }
      }
    }
    void providers()throws IOException{
      for(Provider provider:PROVIDERS){Matcher match=provider.pattern().matcher(text);int count=0;while(match.find()){
        interrupted();if(++count>2000){partial("단일 패턴 결과 2,000개 제한");break;}
        if(!inExpression(match.start(),match.end())&&!coveredScalar(provider.category(),match.start(),match.end()))add(provider.category(),provider.name(),match.group(),"STRING","DECODED_RESPONSE_BODY","pattern:"+provider.name(),match.start(),match.end()-match.start(),Map.of("parser","형식/표식 보조 · 유효성 검증 없음"));
      }}
      int start=0;while((start=text.indexOf("-----BEGIN ",start))>=0){
        interrupted();int headingEnd=text.indexOf("-----",start+11);if(headingEnd<0||headingEnd-start>100){start+=11;continue;}String heading=text.substring(start+11,headingEnd);
        if(!heading.matches("(?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY")){start=headingEnd+5;continue;}String ending="-----END "+heading+"-----";int end=text.indexOf(ending,headingEnd+5);
        if(end<0){partial("닫히지 않은 개인 키 블록 제외");break;}end+=ending.length();if(!inExpression(start,end)&&!coveredScalar("SENSITIVE",start,end))add("SENSITIVE","Private key",text.substring(start,end),"STRING","DECODED_RESPONSE_BODY","PEM private key",start,end-start,Map.of("parser","PEM 형식 · 키 사용/검증 없음"));start=end;
      }
    }
    boolean coveredScalar(String category,int start,int end){return collector.values.stream().anyMatch(value->value.category().equals(category)&&value.source().originalId().equals(original)
        &&value.source().account().equals(page.account())&&value.source().profileRevision().equals(page.profileRevision())&&value.source().area().equals("DECODED_RESPONSE_BODY")
        &&!value.source().locator().startsWith("pattern:")&&!value.source().locator().equals("PEM private key")&&value.source().offset()<=start&&(long)value.source().offset()+value.source().length()>=end);}
    Map<String,String> apiAttributes(String literal,String method){
      try{URI resolved=URI.create(page.url()).resolve(literal);return Map.of("observedLiteral",literal,"resolvedEndpoint",resolved.toString(),"httpMethod",method,"resolution","resolvedEndpoint is reconstructed; value/rawText remain the observed literal");}
      catch(RuntimeException error){return Map.of("httpMethod",method,"resolution","상대 경로/변수 해석 미확정");}
    }
  }
  private record Attr(String value,int offset,int length,boolean rawOnly){}
  private record EntityValue(String value,boolean rawOnly){}
  private static final class AtomicFailure {Exception error;}
  private static final class Json {
    final Context context;final String source;final int base;int at,nodes;
    Json(Context context,String source,int base){this.context=context;this.source=source;this.base=base;}
    void parse()throws IOException{value("", "",0);space();if(at!=source.length())throw new IllegalArgumentException();}
    void space(){while(at<source.length()&&" \r\n\t".indexOf(source.charAt(at))>=0)at++;}
    void value(String path,String name,int depth)throws IOException{
      interrupted();space();if(depth>40||++nodes>60_000||at>=source.length())throw new IllegalArgumentException();char c=source.charAt(at);
      if(c=='{'){at++;space();Set<String>keys=new HashSet<>();if(take('}'))return;do{space();int keyStart=at;String key=string();int keyEnd=at;
        if(uriLike(key))context.add(apiLike(key)||path.endsWith("/paths")?"API":"PATH","JSON path key",key,"STRING","DECODED_RESPONSE_BODY","JSON key:"+path,base+keyStart,keyEnd-keyStart,context.apiAttributes(key,"?"));
        if(!keys.add(key))context.partial("JSON 중복 키 관측 · 각 원문 위치를 따로 보존");space();need(':');value(path+"/"+key.replace("~","~0").replace("/","~1"),key,depth+1);space();if(take('}'))return;need(',');}while(true);}
      if(c=='['){at++;space();if(take(']'))return;int index=0;do{value(path+"/"+index++,name,depth+1);space();if(take(']'))return;need(',');}while(true);}
      int start=at;String result,type;
      if(c=='"'){result=string();type="STRING";}
      else{while(at<source.length()&&",]} \r\n\t".indexOf(source.charAt(at))<0)at++;String raw=source.substring(start,at);Object scalar=MinerJson.parse(raw,MAX_VALUE,2);if(scalar instanceof Map<?,?>||scalar instanceof List<?>)throw new IllegalArgumentException();result=raw;type=scalarType(raw);}
      context.scalar(name,result,type,"JSON:"+path,base+start,at-start,Map.of("jsonPointer",path,"parser","위치 보존 JSON scalar"));
      if(type.equals("STRING")&&uriLike(result))context.add(apiLike(result)||name.toLowerCase(Locale.ROOT).contains("api")?"API":"PATH",name,result,type,"DECODED_RESPONSE_BODY","JSON:"+path,base+start,at-start,context.apiAttributes(result,"?"));
    }
    String string(){int start=at;if(at>=source.length()||source.charAt(at)!='"')throw new IllegalArgumentException();int end=quotedEnd(source,at);if(end<0)throw new IllegalArgumentException();at=end;return (String)MinerJson.parse(source.substring(start,end),MAX_VALUE+2,2);}
    boolean take(char c){if(at<source.length()&&source.charAt(at)==c){at++;return true;}return false;}
    void need(char c){if(!take(c))throw new IllegalArgumentException();}
  }

  private static String category(String name,String value){
    String key=Objects.requireNonNullElse(name,"").replaceAll("[^A-Za-z0-9]","").toLowerCase(Locale.ROOT);
    if(key.matches(".*(?:password|passwd|passphrase|clientsecret|privatekey|credential|signingsecret|secret).*")||Set.of("pwd","pin").contains(key))return "SENSITIVE";
    if(key.matches(".*(?:token|jwt|session|cookie|bearer|authorization|oauth).*") )return "TOKEN";
    if(key.matches(".*(?:apikey|accesskey|publickey|publishablekey|sitekey|measurementid|clientid).*") )return "KEY";
    if(key.matches(".*(?:database|connectionstring|dbhost|dbport|dbuser|dburl|jdbc|mongodb|redis).*") )return "DATABASE";
    if(key.matches(".*(?:email|phone|mobile|residentnumber|creditcard|cardnumber|fullname|address).*")||ssnName(name,key))return "SENSITIVE";
    if(key.matches(".*(?:hostname|internalhost|internalip|internalpath|cwd|homedir).*") )return "INTERNAL";
    if(key.matches(".*(?:runtime|version|environment|platform|framework|server|was|nodeenv|osinfo).*") )return "ENVIRONMENT";
    if(key.matches(".*(?:apiurl|endpoint|baseurl).*")&&uriLike(value))return "API";
    if(key.matches(".*(?:url|uri|path|route|href|src).*")&&uriLike(value))return apiLike(value)?"API":"PATH";
    if(key.matches(".*(?:stacktrace|exception|error|debug|role|permission|username|userid|accountid|featureflag|config).*")||key.equals("id"))return "OTHER";
    return null;
  }
  private static boolean ssnName(String name,String normalized){
    if(normalized.endsWith("socialsecuritynumber"))return true;
    String words=Objects.requireNonNullElse(name,"").replaceAll("([a-z0-9])([A-Z])","$1_$2").replaceAll("([A-Z])([A-Z][a-z])","$1_$2");
    return Arrays.stream(words.split("[^A-Za-z0-9]+")).anyMatch(word->word.equalsIgnoreCase("ssn"));
  }
  private static boolean interesting(String key){return category(key,"https://observed.invalid/api/example")!=null;}
  private static String classification(String name,String value){
    if(SecretCandidates.example(name,value)||value.startsWith("${")||value.startsWith("{{")||value.startsWith("process.env.")||value.startsWith("import.meta.env."))return "EXAMPLE_OR_PLACEHOLDER";
    if(SecretCandidates.publicIdentifier(name)||value.startsWith("pk_live_")||value.startsWith("pk_test_"))return "PUBLIC_IDENTIFIER";
    return "UNVERIFIED";
  }
  private static boolean compatibleCategory(String report,String product){return switch(report){
    case "API" -> Set.of("API","INVENTORY").contains(product);case "PATH" -> Set.of("PATH","MENU","API").contains(product);
    case "KEY","TOKEN" -> product.equals("SECRET");case "SENSITIVE" -> Set.of("SECRET","INTERNAL").contains(product);
    case "DATABASE" -> Set.of("DATABASE","SECRET").contains(product);case "ENVIRONMENT" -> product.equals("ENVIRONMENT");
    case "INTERNAL" -> product.equals("INTERNAL");case "OTHER" -> product.equals("RESPONSE");default -> false;
  };}
  private static boolean uriLike(String value){return value!=null&&value.length()<=8192&&(value.matches("(?is)^(?:https?|wss?)://.*")||value.startsWith("/")||value.startsWith("./")||value.startsWith("../")||value.startsWith("#/"));}
  private static boolean apiLike(String value){return value.toLowerCase(Locale.ROOT).matches(".*(?:/(?:api|rest|graphql)(?:[/?.#]|$)|openapi|swagger|api-docs).*");}
  private static boolean codeExpression(String value){return SecretCandidates.expression(value)||value.matches("(?is)^(?:process\\.env\\.|import\\.meta\\.|(?:window|this|config|settings|env)\\.|function\\b|new\\s|[A-Za-z_$][A-Za-z0-9_.$]*\\s*\\(|\\$\\{|\\{\\{|.*=>).*");}
  private static String scalarType(String value){return value.equals("true")||value.equals("false")?"BOOLEAN":value.equals("null")?"NULL":value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")?"NUMBER":"STRING";}
  private static String jsKey(AstNode node){if(node instanceof Name n)return n.getIdentifier();if(node instanceof StringLiteral s)return s.getValue();if(node instanceof PropertyGet p)return p.getProperty().getIdentifier();return null;}
  private static int quotedEnd(String source,int start){char quote=source.charAt(start);for(int i=start+1;i<source.length()&&i-start<=MAX_VALUE+2;i++){char c=source.charAt(i);if(c=='\\'){i++;continue;}if(c==quote)return i+1;}return -1;}
  private static boolean literalTail(String source,int end){
    int next=end;boolean newline=false;
    while(next<source.length()){
      while(next<source.length()&&Character.isWhitespace(source.charAt(next))){newline|=source.charAt(next)=='\r'||source.charAt(next)=='\n';next++;}
      if(source.startsWith("//",next)||next<source.length()&&source.charAt(next)=='#'){int stop=source.indexOf('\n',next);if(stop<0)return true;next=stop+1;newline=true;continue;}
      if(source.startsWith("/*",next)){int stop=source.indexOf("*/",next+2);if(stop<0)return false;newline|=source.substring(next,stop).indexOf('\n')>=0;next=stop+2;continue;}
      break;
    }
    if(next==source.length()||";,})]".indexOf(source.charAt(next))>=0)return true;
    if(!newline||!Character.isJavaIdentifierStart(source.charAt(next)))return false;
    int word=next+1;while(word<source.length()&&Character.isJavaIdentifierPart(source.charAt(word)))word++;return !Set.of("in","instanceof").contains(source.substring(next,word));
  }
  private static String decodeQuoted(String raw){
    if(raw.charAt(0)=='"')try{return (String)MinerJson.parse(raw,MAX_VALUE+2,2);}catch(IllegalArgumentException ignored){}
    StringBuilder value=new StringBuilder();for(int i=1;i<raw.length()-1;i++){char c=raw.charAt(i);if(c!='\\'||i+1>=raw.length()-1){value.append(c);continue;}char escape=raw.charAt(++i);switch(escape){case 'n'->value.append('\n');case 'r'->value.append('\r');case 't'->value.append('\t');case 'b'->value.append('\b');case 'f'->value.append('\f');case '\n'->{}case '\r'->{if(i+1<raw.length()-1&&raw.charAt(i+1)=='\n')i++;}case 'u','x'->{int length=escape=='u'?4:2;try{value.append((char)Integer.parseInt(raw.substring(i+1,i+1+length),16));i+=length;}catch(RuntimeException e){value.append('\\').append(escape);}}default->value.append(escape);}}return value.toString();
  }
  private static int tagEnd(String text,int start){char quote=0;for(int i=start+1;i<text.length()&&i-start<=MAX_VALUE;i++){char c=text.charAt(i);if(quote!=0){if(c==quote)quote=0;}else if(c=='\''||c=='"')quote=c;else if(c=='>')return i;}return -1;}
  private static String entities(String value){
    StringBuilder out=new StringBuilder();for(int at=0;at<value.length();){char c=value.charAt(at);if(c!='&'){out.append(c);at++;continue;}int end=value.indexOf(';',at+1);if(end<0||end-at>12){out.append(c);at++;continue;}String token=value.substring(at+1,end);String decoded=switch(token){case "amp"->"&";case "quot"->"\"";case "apos"->"'";case "lt"->"<";case "gt"->">";default->null;};
      if(decoded==null&&token.startsWith("#"))try{boolean hex=token.startsWith("#x")||token.startsWith("#X");int point=Integer.parseInt(token.substring(hex?2:1),hex?16:10);if(Character.isValidCodePoint(point)&&!(point>=0xd800&&point<=0xdfff))decoded=new String(Character.toChars(point));}catch(RuntimeException ignored){}
      if(decoded==null){out.append(c);at++;}else{out.append(decoded);at=end+1;}}
    return out.toString();
  }
  private static EntityValue entityValue(String raw){
    for(int at=raw.indexOf('&');at>=0;at=raw.indexOf('&',at+1)){
      int end=raw.indexOf(';',at+1);if(end<0||end-at>64)continue;String token=raw.substring(at+1,end);
      if(Set.of("amp","quot","apos","lt","gt").contains(token))continue;
      if(token.matches("#[xX][0-9A-Fa-f]{1,6}|#[0-9]{1,7}"))try{boolean hex=token.charAt(1)=='x'||token.charAt(1)=='X';int point=Integer.parseInt(token.substring(hex?2:1),hex?16:10);if(point>=32&&point!=127&&!(point>=128&&point<=159)&&Character.isValidCodePoint(point)&&!(point>=0xd800&&point<=0xdfff))continue;}catch(RuntimeException ignored){}
      if(token.matches("[A-Za-z][A-Za-z0-9]{0,62}|#.*"))return new EntityValue(raw,true);
    }
    return new EntityValue(entities(raw),false);
  }
  private static int insertion(int[] offsets,int position){int index=Arrays.binarySearch(offsets,position);return index>=0?index:-index-1;}
  private static int line(String source,int offset){int line=1;for(int i=0;i<Math.min(offset,source.length());i++)if(source.charAt(i)=='\n')line++;return line;}
  private static boolean overlap(int a,int al,int b,int bl){return a<(long)b+Math.max(1,bl)&&b<(long)a+Math.max(1,al);}
  private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
  private static void interrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("보고서 생성 취소 · 임시 파일 정리");}
  private static void privateFile(Path file)throws IOException{try{Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}
  private static void entry(ZipOutputStream zip,String name,String value)throws IOException{interrupted();zip.putNextEntry(new ZipEntry(name));Writer output=new OutputStreamWriter(zip,StandardCharsets.UTF_8);try(Reader input=new StringReader(value)){char[] buffer=new char[8192];int read;while((read=input.read(buffer))>=0){interrupted();output.write(buffer,0,read);}}output.flush();zip.closeEntry();}
  private static Map<String,Object> documentMap(Document document){
    List<Object> values=new ArrayList<>(),pages=new ArrayList<>();for(Value value:document.values()){
      Source s=value.source();Map<String,Object> source=new LinkedHashMap<>();source.put("originalId",s.originalId());source.put("account",s.account());source.put("profileRevision",s.profileRevision());source.put("url",s.url());source.put("method",s.method());source.put("responseStatus",s.status());source.put("responseSha256",s.responseSha256());source.put("area",s.area());source.put("locator",s.locator());source.put("line",s.line());source.put("offset",s.offset());source.put("length",s.length());
      Map<String,Object> row=new LinkedHashMap<>();row.put("category",value.category());row.put("name",value.name());row.put("value",value.value());row.put("rawText",value.rawText());row.put("valueType",value.valueType());row.put("classification",value.classification());row.put("provenance",value.provenance());row.put("confidence",value.confidence());row.put("source",source);row.put("attributes",value.attributes());values.add(row);
    }for(Page page:document.pages()){Map<String,Object> row=new LinkedHashMap<>();row.put("originalId",page.originalId());row.put("account",page.account());row.put("profileRevision",page.profileRevision());row.put("url",page.url());row.put("method",page.method());row.put("responseStatus",page.status());row.put("responseSha256",page.responseSha256());row.put("responseBytes",page.responseBytes());row.put("decodedCharacters",page.decodedCharacters());row.put("complete",page.complete());row.put("note",page.note());pages.add(row);}
    return Map.of("schemaVersion",1,"producerVersion",BuildInfo.VERSION,"runId",document.runId(),"state",document.state(),"complete",document.complete(),"coordinateBasis","UTF-16 characters; decoded response body or normalized response headers; offset zero-based, line one-based; not wire bytes","values",values,"pages",pages,"notes",document.notes());
  }
  private static String html(Document document){
    StringBuilder out=new StringBuilder("<!doctype html><html lang=ko><meta charset=utf-8><meta http-equiv=Content-Security-Policy content=\"default-src 'none'; style-src 'unsafe-inline'\"><title>응답 실제값 보고서</title><style>body{font:15px system-ui;margin:32px;max-width:1400px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #bbb;padding:9px;text-align:left;vertical-align:top}pre{white-space:pre-wrap;overflow-wrap:anywhere;margin:0}code{overflow-wrap:anywhere}.muted{color:#555}</style><h1>응답 실제값 보고서</h1><p>명시적으로 저장한 로컬 응답 값 · 키 사용/외부 검증 없음 · 요청 인증 헤더는 포함하지 않음</p><p>");
    out.append(document.values().size()).append("개 값 / ").append(document.pages().size()).append("개 응답 · ").append(document.complete()?"설정한 추출 범위 내 완료":"부분·제외 범위 포함").append("</p><p>PRODUCT_FINDING: 기존 제품 발견과 연결 / REPORT_SUPPLEMENT: 보고서 전용 보조 파싱. UNVERIFIED는 유효한 비밀이라는 뜻이 아닙니다.</p><p>value는 파싱한 실제 값, rawText는 원본 소스 표기입니다. resolvedEndpoint는 재구성 경로이며 원문 값과 구분합니다. 좌표는 디코딩한 본문/정규화 헤더의 UTF-16 문자 기준입니다.</p>");
    for(String note:document.notes())out.append("<p class=muted>").append(escape(note)).append("</p>");
    for(String category:List.of("API","SENSITIVE","TOKEN","KEY","ENVIRONMENT","DATABASE","PATH","INTERNAL","OTHER")){
      var rows=document.values().stream().filter(value->value.category().equals(category)).toList();if(rows.isEmpty())continue;out.append("<h2>").append(category).append(" · ").append(rows.size()).append("</h2><table><tr><th>이름·분류</th><th>실제 값 / 원문</th><th>출처·응답</th></tr>");
      for(Value value:rows){Source s=value.source();out.append("<tr><td>").append(escape(value.name())).append("<br>").append(escape(value.classification())).append("<br>").append(escape(value.provenance())).append("<br>").append(escape(value.valueType())).append("</td><td><b>value</b><pre>").append(escape(value.value())).append("</pre><details><summary>rawText · ").append(s.length()).append(" 문자</summary><pre>").append(escape(value.rawText())).append("</pre></details><pre class=muted>").append(escape(json(value.attributes()))).append("</pre></td><td><code>").append(escape(s.method()+" "+s.url())).append("</code><br>HTTP ").append(s.status()).append(" · ").append(escape(s.account())).append(" · ").append(escape(s.profileRevision())).append("<br>").append(escape(s.originalId())).append("<br>").append(escape(s.area()+" / "+s.locator())).append("<br>줄 ").append(s.line()).append(" · offset ").append(s.offset()).append(" · length ").append(s.length()).append("</td></tr>");}out.append("</table>");
    }
    out.append("<h2>응답별 범위</h2><table><tr><th>응답 원본</th><th>범위</th></tr>");for(Page page:document.pages())out.append("<tr><td>").append(escape(page.originalId()+" · "+page.method()+" "+page.url())).append("</td><td>").append(page.complete()?"완료":"부분/미검사").append(" · ").append(escape(page.note())).append("</td></tr>");return out.append("</table></html>").toString();
  }
  private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
  private static String csv(Document document){
    StringBuilder out=new StringBuilder("category,name,value,rawText,valueType,classification,provenance,originalId,account,profileRevision,responseStatus,method,url,area,locator,line,offset,length,responseSha256,attributes\r\n");
    for(Value v:document.values()){Source s=v.source();List<String> cells=List.of(v.category(),v.name(),v.value(),v.rawText(),v.valueType(),v.classification(),v.provenance(),s.originalId(),s.account(),s.profileRevision(),Integer.toString(s.status()),s.method(),s.url(),s.area(),s.locator(),Integer.toString(s.line()),Integer.toString(s.offset()),Integer.toString(s.length()),s.responseSha256(),json(v.attributes()));out.append(String.join(",",cells.stream().map(ScopeInspectionReport::csvCell).toList())).append("\r\n");}return out.toString();
  }
  private static String csvCell(String value){int index=0;while(index<value.length()&&(Character.isWhitespace(value.charAt(index))||Character.isISOControl(value.charAt(index))||value.charAt(index)=='\ufeff'))index++;if(index<value.length()&&"=+-@".indexOf(value.charAt(index))>=0)value="'"+value;return "\""+value.replace("\"","\"\"")+"\"";}
  private static String json(Object value){
    if(value==null)return "null";if(value instanceof Boolean||value instanceof Number)return value.toString();if(value instanceof Map<?,?> map){List<String>parts=new ArrayList<>();map.forEach((k,v)->parts.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",parts)+"}";}if(value instanceof Collection<?> list)return "["+String.join(",",list.stream().map(ScopeInspectionReport::json).toList())+"]";
    StringBuilder out=new StringBuilder("\"");for(char c:value.toString().toCharArray())switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32||Character.isSurrogate(c))out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}}return out.append('"').toString();
  }
}
