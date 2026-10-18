package kr.workbench;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ast.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
/** Parses JavaScript; never evaluates it, contacts endpoints or validates credentials. */
public final class JsAnalysis {
 public static final int SOURCE_LIMIT=512*1024, RESULT_LIMIT=500;
 public record Endpoint(String url,String method,String kind,String parameters,String headers,String bodyKeys,int line,int start,int length,boolean complete,String evidence){}
 public record Secret(String rule,String masked,int length,String fingerprint,int line,int start,int sourceLength,String confidence){}
 public record Result(List<Endpoint> endpoints,List<Secret> secrets,String status,boolean truncated,JsMiner.Result miner,Map<String,JsRequestDraft.Draft> requestDrafts){
 public Result(List<Endpoint> e,List<Secret> s,String state,boolean t,JsMiner.Result m){this(e,s,state,t,m,Map.of());}
 public Result(List<Endpoint> e,List<Secret> s,String state,boolean t){this(e,s,state,t,new JsMiner.Result(List.of(),List.of(),List.of(),List.of(),List.of(),""));}
 @Override public String toString(){return "Result[endpoints="+endpoints+", secrets="+secrets+", status="+status+", truncated="+truncated+", miner="+miner+"]";}
 }
 private record Value(String text,boolean complete){}
 private final Map<String,JsRequestDraft.Draft> requestDrafts=new LinkedHashMap<>();
 static String draftKey(Endpoint e){return e.start()+"|"+e.method()+"|"+e.url()+(e.kind().startsWith("source map: ")||e.kind().contains("GraphQL")?"|"+e.kind():"");}
 private record Segment(String text,int offset){}
 private record Rule(String name,Pattern pattern){}
 private static final List<Rule> RULES=List.of(
  rule("AWS Access Key ID","(?:AKIA|ASIA)[A-Z0-9]{16}"),rule("Google API Key","AIza[0-9A-Za-z_-]{35}"),
  rule("GitHub token","(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{40,255})"),
  rule("Slack token","xox[baprs]-[0-9A-Za-z-]{10,200}"),rule("Stripe secret key","sk_(?:live|test)_[0-9A-Za-z]{16,200}"),
  rule("JWT / JWS","eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
  rule("Private key","-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----[\\s\\S]{0,16384}?-----END (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"));
 private static Rule rule(String n,String p){return new Rule(n,Pattern.compile(p));}
 private final String source,base;private int[] newlineOffsets;private AstNode wrapperSite;private String documentBase;private boolean contextual;private ObjectLiteral defaults;private int resultLimit=RESULT_LIMIT;private String contentType="";private final List<Endpoint> urls=new ArrayList<>();private final List<Secret> secrets=new ArrayList<>();private final Set<String> urlKeys=new HashSet<>(),secretKeys=new HashSet<>();
 private final Map<Scope,Map<String,VariableInitializer>> bindings=new IdentityHashMap<>();private final Map<Scope,Map<String,FunctionNode>> functions=new IdentityHashMap<>();private record Frame(Scope scope,Map<String,Value> values,Map<String,AstNode> nodes){Frame(Scope scope,Map<String,Value> values){this(scope,values,Map.of());}}private final Deque<Frame> frames=new ArrayDeque<>();private int offset;private boolean bounded;private long deadline;private int recoveryDepth;
 private JsAnalysis(String source,String base){this(source,base,SOURCE_LIMIT);}
 private JsAnalysis(String source,String base,int limit){this.source=source.substring(0,Math.min(limit,source.length()));this.base=base==null?"":base;bounded=source.length()>limit;}
 public static Result analyze(String text,String base){JsAnalysis a=new JsAnalysis(text==null?"":text,base);return a.run();}
 public static Result analyze(String text,String base,String type){JsAnalysis a=new JsAnalysis(text==null?"":text,base);a.contentType=type==null?"":type;return a.run();}
 /** Browser page context is independent from the downloaded script's location. */
 public static Result analyzeContext(String text,String sourceUrl,String type,String page){return analyzeUnit(text,sourceUrl,type,page,false);}
 static Result analyzeUnit(String text,String sourceUrl,String type,String page,boolean full){
  JsAnalysis a=new JsAnalysis(text==null?"":text,sourceUrl,full?Integer.MAX_VALUE:SOURCE_LIMIT);
  a.contentType=type==null?"":type;a.contextual=true;a.documentBase=JsOrigin.document(sourceUrl,a.contentType,page);
  if(full)a.resultLimit=Integer.MAX_VALUE;
  return a.run();
 }
 private Result run(){int errors=0,parsed=0,compatible=0;deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
  List<Endpoint> httpCalls=new ArrayList<>();
  for(Segment segment:segments(source))for(Endpoint found:JsHttpCalls.discover(segment.text(),contextual?documentBase:base,resultLimit,deadline)){
   int position=segment.offset()+found.start();httpCalls.add(new Endpoint(found.url(),found.method(),found.kind(),found.parameters(),found.headers(),found.bodyKeys(),line(position),position,found.length(),found.complete(),found.evidence()));
  }
  TextCandidates.Scan data=TextCandidates.scan(source,contentType,base,deadline);bounded|=data.limited();
  for(Segment seg:data.dataOnly()?List.<Segment>of():segments(source)){if(System.nanoTime()>deadline){bounded=true;break;}offset=seg.offset;bindings.clear();functions.clear();frames.clear();
   try{CompilerEnvirons env=new CompilerEnvirons();env.setLanguageVersion(Context.VERSION_ES6);env.setRecoverFromErrors(false);env.setRecordingComments(false);env.setErrorReporter(new ErrorReporter(){public void warning(String m,String s,int l,String ls,int c){}public void error(String m,String s,int l,String ls,int c){throw new EvaluatorException("unsupported syntax",s,l);}public EvaluatorException runtimeError(String m,String s,int l,String ls,int c){return new EvaluatorException("unsupported syntax",s,l);}});
    JsSyntax.Compatible syntax=JsSyntax.compatible(seg.text);AstRoot root=new Parser(env).parse(syntax.text(),"captured.js",1);if(syntax.changed())compatible++;
    root.visit(n->{budget();if(n instanceof CatchClause clause&&clause.getVarName()==null)clause.setVarName(new Name(clause.getPosition(),"WB_UNUSED_CATCH"));if(n instanceof FunctionNode f&&f.getFunctionName()!=null&&!f.getFunctionName().getIdentifier().isEmpty())functions.computeIfAbsent(f.getEnclosingScope(),x->new HashMap<>()).put(f.getFunctionName().getIdentifier(),f);if(n instanceof VariableInitializer v&&v.getTarget() instanceof Name name&&v.getParent() instanceof VariableDeclaration d)bindings.computeIfAbsent(name.getDefiningScope()==null?name.getEnclosingScope():name.getDefiningScope(),x->new HashMap<>()).put(name.getIdentifier(),d.isConst()?v:null);return true;});
    root.visit(n->{budget();inspect(n);return true;});parsed++;
   }catch(IllegalStateException e){bounded=true;break;}catch(RuntimeException|LinkageError|StackOverflowError e){errors++;Matcher literal=Pattern.compile("[\"']((?:https?://|wss?://|/|\\./|\\.\\./)[^\"'\\r\\n<>]{1,2048})[\"']").matcher(seg.text);while(literal.find()&&urls.size()<resultLimit){StringLiteral node=new StringLiteral(literal.start(),literal.end()-literal.start());node.setValue(literal.group(1));int before=urls.size();endpoint(node,"?","문자열 조각 · AST 실패",null,"body");if(urls.size()>before){Endpoint item=urls.remove(urls.size()-1);urls.add(new Endpoint(item.url,item.method,item.kind,item.parameters,item.headers,item.bodyKeys,item.line,item.start,item.length,false,"문맥/메서드 미확정 · 자동 수집 제외"));}}}
  }
  int recoveredCalls=0;
  for(Endpoint call:httpCalls){
   if(urls.size()>=resultLimit){bounded=true;break;}
   if(urls.stream().anyMatch(existing->existing.start()==call.start()&&existing.method().equals(call.method())))continue;
   urls.add(call);recoveredCalls++;
   try{var draft=JsRequestDraft.preview(call);if(Set.of("POST","PUT","PATCH").contains(call.method()))draft=new JsRequestDraft.Draft(draft.url(),draft.method(),"REPLACE_BODY",draft.contentType());requestDrafts.put(draftKey(call),draft);}catch(IllegalArgumentException ignored){}
  }
  if(errors>0 && !data.dataOnly() && data.values().isEmpty())data=TextCandidates.fallback(source,deadline);
  for(var item:data.values()){if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted()){bounded=true;break;}dataValue(item,data.format());}
  bounded|=data.limited();
  // Provider patterns also cover JSON config, unsupported syntax and HTML outside scripts.
  for(Rule r:RULES){Matcher m=r.pattern.matcher(source);while(m.find()&&secrets.size()<resultLimit)addSecret(r.name,m.group(),m.start(),m.end()-m.start(),"패턴 일치 · 유효성 미검증");}
  String state=(data.dataOnly()?data.format()+" 정적 분석":"AST 블록 "+parsed)+" · URL/API "+urls.size()+" · 시크릿 후보 "+secrets.size()+" · 실행/요청/키 검증 없음";
  if(recoveredCalls>0)state+=" · HTTP 호출 보조 "+recoveredCalls;
  if(compatible>0)state+=" · async/module 구문 호환 보조 "+compatible;if(errors>0)state+=" · AST 실패 "+errors+" (지원 문법 밖/오류; 패턴 탐지만 보조)";if(bounded)state+=" · 입력/시간/결과 제한으로 일부 분석";
  JsMiner.Result miner=JsMiner.analyze(source,base);
  if(recoveryDepth==0)for(var recovered:miner.recovered()){if(System.nanoTime()>deadline){bounded=true;break;}JsAnalysis child=new JsAnalysis(recovered.content(),base);child.recoveryDepth=1;child.contextual=contextual;child.documentBase=documentBase;Result result=child.run();miner=JsMiner.mergeRecon(miner,result.miner());for(Endpoint e:result.endpoints())if(urls.size()<resultLimit){Endpoint mapped=new Endpoint(e.url,e.method,"source map: "+recovered.name()+(e.kind.contains("GraphQL")?" · "+e.kind.substring(e.kind.indexOf("GraphQL")):""),e.parameters,e.headers,e.bodyKeys,e.line,-1-e.start(),e.length,e.complete,e.evidence+" · 원본 열 "+JsFindingGroups.column(recovered.content(),e.start()));urls.add(mapped);var draft=result.requestDrafts().get(draftKey(e));if(draft!=null)requestDrafts.put(draftKey(mapped),draft);}for(Secret secret:result.secrets())if(secrets.size()<resultLimit&&secretKeys.add(secret.rule+":"+secret.fingerprint))secrets.add(new Secret(secret.rule,secret.masked,secret.length,secret.fingerprint,secret.line,-1,0,"source map: "+recovered.name()+" · "+secret.confidence));}
  return new Result(List.copyOf(urls),List.copyOf(secrets),state.replaceAll("URL/API \\d+","URL/API "+urls.size()).replaceAll("시크릿 후보 \\d+","시크릿 후보 "+secrets.size())+" · 도메인 "+miner.domains().size()+" · map "+miner.maps().size()+" · 원본 "+miner.recovered().size(),bounded,miner,Map.copyOf(requestDrafts));
 }
 private void dataValue(TextCandidates.Value item,String format){
  String value=item.text().strip();if(value.isEmpty()||value.length()>16384)return;
  StringLiteral node=new StringLiteral(item.start(),item.length());node.setValue(value);offset=0;
  if(value.matches("(?s)(?:https?://|wss?://|/|\\./|\\.\\./).*")){
   boolean covered=urls.stream().anyMatch(e->item.start()>=e.start()&&item.start()<e.start()+e.length());
   if(!covered){int before=urls.size();endpoint(node,"?",format+" · "+item.key(),null,"body");
    if(urls.size()>before){Endpoint e=urls.remove(urls.size()-1);boolean complete=e.complete()&&!value.matches("(?s).*(?:\\{[^}]+}|\\$\\{|/[ :]*:[A-Za-z_]).*");urls.add(new Endpoint(e.url(),e.method(),e.kind(),e.parameters(),e.headers(),e.bodyKeys(),e.line(),e.start(),e.length(),complete,"정적 문자열 · 메서드/도달 여부 미확정 · URL 쿼리 값 제외"));}
   }
  }
  for(Rule r:RULES){Matcher m=r.pattern.matcher(value);while(m.find()&&secrets.size()<resultLimit)addSecret(r.name,m.group(),item.start(),item.length(),format+" 문자열 패턴 · 유효성 미검증");}
  secretContext(item.key(),new Value(value,true),node);
 }
 private void budget(){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IllegalStateException("limit");}
 private static List<Segment> segments(String text){if(!Pattern.compile("(?is)^\\s*(?:<!doctype|<html|<head|<body|<script|<div)").matcher(text).find())return List.of(new Segment(text,0));List<Segment> list=new ArrayList<>();Matcher m=Pattern.compile("(?is)<script\\b([^>]*)>(.*?)</script\\s*>").matcher(text);while(m.find()){String attrs=m.group(1);if(!attrs.matches("(?is).*\\bsrc\\s*=.*")&&!attrs.matches("(?is).*\\btype\\s*=\\s*['\"]application/(?:ld\\+)?json.*"))list.add(new Segment(m.group(2),m.start(2)));}return list;}
 private void inspect(AstNode n){
  if(n instanceof FunctionCall call){String target=callee(call.getTarget());List<AstNode> args=call.getArguments();
   FunctionNode wrapper=call.getTarget() instanceof Name name?function(name):null;
   if(wrapper!=null&&!args.isEmpty())expandWrapper(wrapper,args,target,call);
   if(axiosInstance(call,args))return;

   if(Set.of("fetch","window.fetch","globalThis.fetch","Request","window.Request").contains(target)&&!args.isEmpty()){ObjectLiteral options=args.size()>1?object(args.get(1)):null;endpoint(args.get(0),method(options,"GET"),target.endsWith("Request")?"Request":"fetch",options,"body");}
   else if(target.equals("axios")&&!args.isEmpty()){ObjectLiteral options=object(args.get(0));if(options!=null&&property(options,"url")!=null)endpoint(property(options,"url"),method(options,"GET"),"axios config",options,"data");else endpoint(args.get(0),"GET","axios",args.size()>1?object(args.get(1)):null,"data");}
   else if(target.matches("axios\\.(get|post|put|patch|delete|head|options|request)")&&!args.isEmpty()){String verb=target.substring(6);if(verb.equals("request")){ObjectLiteral o=object(args.get(0));if(o!=null&&property(o,"url")!=null)endpoint(property(o,"url"),method(o,"GET"),"axios.request",o,"data");}else {boolean hasBody=Set.of("post","put","patch").contains(verb);ObjectLiteral options=hasBody?(args.size()>2?object(args.get(2)):null):(args.size()>1?object(args.get(1)):null);AstNode body=hasBody&&args.size()>1?args.get(1):null;endpoint(args.get(0),verb.toUpperCase(Locale.ROOT),"axios."+verb,options,"data",body);}}
   else if(target.matches("(?:this\\.)?(?:http|httpClient)\\.(get|post|put|patch|delete|head|options)")&&!args.isEmpty()){String verb=target.substring(target.lastIndexOf('.')+1);boolean body=Set.of("post","put","patch").contains(verb);ObjectLiteral options=args.size()>(body?2:1)?object(args.get(body?2:1)):null;endpoint(args.get(0),verb.toUpperCase(Locale.ROOT),"HTTP 클라이언트 "+target,options,"body",body&&args.size()>1?args.get(1):null);}
   else if(target.endsWith(".open")&&!target.equals("window.open")&&args.size()>1){Value verb=value(args.get(0),0);if(verb!=null&&verb.complete&&verb.text.matches("(?i)GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS"))endpoint(args.get(1),verb.text.toUpperCase(Locale.ROOT),"XHR-like open",null,"body");}
   else if(Set.of("WebSocket","window.WebSocket","EventSource","window.EventSource","window.open","location.assign","location.replace","window.location.assign","window.location.replace").contains(target)&&!args.isEmpty())endpoint(args.get(0),target.contains("WebSocket")?"UPGRADE":"GET",target,null,"body");
  }
  if(n instanceof Assignment a&&Set.of("location","location.href","window.location","window.location.href","document.location","document.location.href").contains(callee(a.getLeft())))endpoint(a.getRight(),"GET","location assignment",null,"body");
  if(n instanceof ObjectProperty p){String key=key(p.getKey());Value v=value(p.getValue(),0);secretContext(key,v,p.getValue());if(v!=null&&key.matches("(?i)(url|apiurl|baseurl|endpoint|uri|path)"))endpoint(p.getValue(),"?","config "+key,null,"body");}
  if(n instanceof FunctionNode f&&f.getFunctionName()!=null&&!f.getFunctionName().getIdentifier().isEmpty())functions.computeIfAbsent(f.getEnclosingScope(),x->new HashMap<>()).put(f.getFunctionName().getIdentifier(),f);if(n instanceof VariableInitializer v&&v.getTarget() instanceof Name name){secretContext(name.getIdentifier(),value(v.getInitializer(),0),v.getInitializer());if(name.getIdentifier().matches("(?i)(apiurl|baseurl|endpoint|url|uri)"))endpoint(v.getInitializer(),"?","설정 변수 "+name.getIdentifier(),null,"body");}
  if(n instanceof Assignment a)secretContext(callee(a.getLeft()),value(a.getRight(),0),a.getRight());
  if(n instanceof StringLiteral literal){String v=literal.getValue();for(Rule rule:RULES){Matcher m=rule.pattern.matcher(v);while(m.find()&&secrets.size()<resultLimit)addSecret(rule.name,m.group(),offset+n.getAbsolutePosition(),n.getLength(),"구문 문자열 + 패턴 · 유효성 미검증");}}
 }
 private FunctionNode function(Name name){VariableInitializer b=binding(name);if(b!=null&&b.getInitializer() instanceof FunctionNode f)return f;for(Scope scope=name.getEnclosingScope();scope!=null;scope=scope.getEnclosingScope()){Map<String,FunctionNode> map=functions.get(scope);if(map!=null&&map.containsKey(name.getIdentifier()))return map.get(name.getIdentifier());if(scope instanceof FunctionNode fn&&fn.getParams().stream().anyMatch(p->p instanceof Name param&&param.getIdentifier().equals(name.getIdentifier())))return null;}return null;}
 private boolean wrapper(FunctionNode fn){if(fn.getParams().isEmpty()||!(fn.getParams().get(0) instanceof Name first))return false;boolean[] found={false};fn.getBody().visit(n->{budget();if(n instanceof FunctionNode)return false;if(n instanceof FunctionCall c&&Set.of("fetch","window.fetch","globalThis.fetch").contains(callee(c.getTarget()))&&!c.getArguments().isEmpty()&&c.getArguments().get(0) instanceof Name x&&x.getIdentifier().equals(first.getIdentifier()))found[0]=true;return true;});return found[0];}
 private AstNode argument(Name name){for(Frame f:frames)if((f.scope==name.getDefiningScope()||f.scope==name.getEnclosingScope())&&f.nodes.containsKey(name.getIdentifier()))return f.nodes.get(name.getIdentifier());return null;}
 private AstNode option(ObjectLiteral local,String key){AstNode own=property(local,key);return own!=null?own:property(defaults,key);}
 private boolean axiosInstance(FunctionCall call,List<AstNode> args){
  AstNode receiver=call.getTarget() instanceof PropertyGet p?p.getTarget():call.getTarget();
  if(!(receiver instanceof Name name)||name.getIdentifier().equals("axios")||args.isEmpty())return false;
  VariableInitializer binding=binding(name);if(binding==null||!(binding.getInitializer() instanceof FunctionCall create)||!callee(create.getTarget()).equals("axios.create"))return false;
  ObjectLiteral previous=defaults;defaults=create.getArguments().isEmpty()?null:object(create.getArguments().get(0));
  try{String verb=call.getTarget() instanceof PropertyGet p?p.getProperty().getIdentifier():"request";
   if(verb.equals("request")){ObjectLiteral config=object(args.get(0));if(config!=null&&option(config,"url")!=null)endpoint(option(config,"url"),method(config,"GET"),"Axios instance "+name.getIdentifier()+".request",config,"data");else if(call.getTarget() instanceof Name)endpoint(args.get(0),method(args.size()>1?object(args.get(1)):null,"GET"),"Axios instance "+name.getIdentifier(),args.size()>1?object(args.get(1)):null,"data");}
   else if(Set.of("get","post","put","patch","delete","head","options").contains(verb)){boolean body=Set.of("post","put","patch").contains(verb);ObjectLiteral config=args.size()>(body?2:1)?object(args.get(body?2:1)):null;endpoint(args.get(0),verb.toUpperCase(Locale.ROOT),"Axios instance "+name.getIdentifier()+"."+verb,config,"data",body&&args.size()>1?args.get(1):null);}
   else return false;return true;
  }finally{defaults=previous;}
 }
 private void expandWrapper(FunctionNode fn,List<AstNode> args,String target,FunctionCall site){
  if(frames.size()>=8)return;Map<String,Value> values=new HashMap<>();Map<String,AstNode> nodes=new HashMap<>();
  for(int i=0;i<fn.getParams().size();i++){AstNode parameter=fn.getParams().get(i);AstNode fallback=parameter instanceof Assignment a?a.getRight():null;AstNode targetNode=parameter instanceof Assignment a?a.getLeft():parameter;if(targetNode instanceof Name p){AstNode supplied=i<args.size()?args.get(i):fallback;if(supplied!=null){values.put(p.getIdentifier(),value(supplied,0));nodes.put(p.getIdentifier(),supplied);}}}
  AstNode previousSite=wrapperSite;wrapperSite=site.getArguments().get(0);frames.push(new Frame(fn,values,nodes));try{fn.getBody().visit(node->{budget();if(node instanceof FunctionNode)return false;if(node instanceof FunctionCall c&&Set.of("fetch","window.fetch","globalThis.fetch").contains(callee(c.getTarget()))&&!c.getArguments().isEmpty()){ObjectLiteral options=c.getArguments().size()>1?object(c.getArguments().get(1)):null;endpoint(c.getArguments().get(0),method(options,"GET"),"fetch 래퍼 "+target,options,"body");}return true;});}finally{frames.pop();wrapperSite=previousSite;}
 }
 private ObjectLiteral object(AstNode n){return object(n,0);}
 private ObjectLiteral object(AstNode n,int depth){
  if(n==null||depth>8)return null;
  if(n instanceof ObjectLiteral o)return o;
  if(n instanceof ParenthesizedExpression p)return object(p.getExpression(),depth+1);
  if(n instanceof FunctionCall c&&callee(c.getTarget()).equals("JSON.stringify")&&!c.getArguments().isEmpty())return object(c.getArguments().get(0),depth+1);
  if(n instanceof Name name){AstNode arg=argument(name);if(arg!=null&&arg!=n)return object(arg,depth+1);VariableInitializer v=binding(name);if(v!=null)return object(v.getInitializer(),depth+1);}
  return null;
 }
 private VariableInitializer binding(Name n){for(Scope scope=n.getEnclosingScope();scope!=null;scope=scope.getEnclosingScope()){Map<String,VariableInitializer> map=bindings.get(scope);if(map!=null&&map.containsKey(n.getIdentifier())){VariableInitializer v=map.get(n.getIdentifier());return v!=null&&v.getAbsolutePosition()<n.getAbsolutePosition()?v:null;}if(scope instanceof FunctionNode fn&&fn.getParams().stream().anyMatch(p->p instanceof Name param&&param.getIdentifier().equals(n.getIdentifier())))return null;}return null;}
 private AstNode property(ObjectLiteral o,String key){if(o==null)return null;for(AbstractObjectProperty prop:o.getElements())if(prop instanceof ObjectProperty p&&key(p.getKey()).equals(key))return p.getValue();return null;}
 private String keys(ObjectLiteral o){if(o==null)return "";List<String> keys=new ArrayList<>();for(AbstractObjectProperty prop:o.getElements())if(prop instanceof ObjectProperty p&&keys.size()<32)keys.add(key(p.getKey()));return String.join(", ",keys);}
 private String method(ObjectLiteral o,String fallback){AstNode n=option(o,"method");if(n==null)return fallback;Value v=value(n,0);return v!=null&&v.complete&&v.text.matches("(?i)[A-Z]{3,12}")?v.text.toUpperCase(Locale.ROOT):"동적";}
 private static String key(AstNode n){if(n instanceof Name name)return name.getIdentifier();if(n instanceof StringLiteral str)return str.getValue();return "[동적 키]";}
 private static String callee(AstNode n){if(n instanceof Name name)return name.getIdentifier();if(n instanceof PropertyGet p)return callee(p.getTarget())+"."+p.getProperty().getIdentifier();return "";}
 private Value value(AstNode n,int depth){if(n==null||depth>16)return null;if(n instanceof StringLiteral s)return new Value(s.getValue(),true);if(n instanceof NumberLiteral d)return new Value(d.getValue(),true);if(n instanceof ParenthesizedExpression p)return value(p.getExpression(),depth+1);
  if(n instanceof Name name){for(Frame frame:frames)if((frame.scope==name.getDefiningScope()||frame.scope==name.getEnclosingScope())&&frame.values.containsKey(name.getIdentifier()))return frame.values.get(name.getIdentifier());VariableInitializer b=binding(name);return b==null?null:value(b.getInitializer(),depth+1);}
  if(n instanceof PropertyGet property){ObjectLiteral object=object(property.getTarget());return object==null?null:value(property(object,property.getProperty().getIdentifier()),depth+1);}
  if(n instanceof InfixExpression expression&&expression.getOperator()==Token.OR){Value left=value(expression.getLeft(),depth+1);if(left!=null&&left.complete&&!left.text.isEmpty())return left;if(expression.getLeft() instanceof PropertyGet property&&object(property.getTarget())!=null&&property(object(property.getTarget()),property.getProperty().getIdentifier())==null)return value(expression.getRight(),depth+1);return null;}
  if(n instanceof FunctionCall call&&call.getTarget() instanceof Name name){FunctionNode fn=function(name);if(fn==null||frames.size()>=8)return null;Map<String,Value> values=new HashMap<>();for(int i=0;i<fn.getParams().size();i++)if(fn.getParams().get(i) instanceof Name p){Value v=i<call.getArguments().size()?value(call.getArguments().get(i),depth+1):null;values.put(p.getIdentifier(),v);}List<AstNode> returns=new ArrayList<>();fn.getBody().visit(node->{if(node instanceof FunctionNode)return false;if(node instanceof ReturnStatement r&&r.getReturnValue()!=null)returns.add(r.getReturnValue());return true;});if(returns.size()!=1)return null;frames.push(new Frame(fn,values));try{return value(returns.get(0),depth+1);}finally{frames.pop();}}
  if(n instanceof InfixExpression i&&i.getOperator()==Token.ADD){Value a=value(i.getLeft(),depth+1),b=value(i.getRight(),depth+1);if(a==null&&b==null)return null;if(a!=null&&b!=null&&a.text.matches("[0-9.]+")&&b.text.matches("[0-9.]+"))return null;String text=(a==null?"{dynamic}":a.text)+(b==null?"{dynamic}":b.text);if(text.length()>8192)return null;return new Value(text,a!=null&&b!=null&&a.complete&&b.complete);}
  if(n instanceof TemplateLiteral t){StringBuilder text=new StringBuilder();boolean complete=true;for(AstNode part:t.getElements()){if(part instanceof TemplateCharacters chars)text.append(chars.getValue());else {Value v=value(part,depth+1);text.append(v==null?"{dynamic}":v.text);complete&=v!=null&&v.complete;}if(text.length()>8192)return null;}return new Value(text.toString(),complete);}
  return null;
 }
 private void endpoint(AstNode node,String method,String kind,ObjectLiteral options,String body){endpoint(node,method,kind,options,body,null);}
 private void endpoint(AstNode node,String method,String kind,ObjectLiteral options,String body,AstNode directBody){Value v=value(node,0);if(v==null||v.text.length()>8192||(!v.text.matches("(?s)(?:https?://|wss?://|/|\\./|\\.\\./).*")&&value(option(options,"baseURL"),0)==null))return;
  Value baseValue=value(option(options,"baseURL"),0);String text=v.text;if(baseValue!=null&&baseValue.complete&&!text.matches("(?i)^(?:[a-z][a-z0-9+.-]*:)?//.*"))text=baseValue.text.replaceAll("/+$","")+"/"+text.replaceAll("^/+","");
  String parameters="";int q=text.indexOf('?');if(q>=0){String query=text.substring(q+1).split("#",2)[0];List<String> names=new ArrayList<>();for(String pair:query.split("&"))if(names.size()<32)names.add(pair.split("=",2)[0].replaceAll("[^A-Za-z0-9_.%\\[\\]-]",""));parameters=String.join(", ",names);text=text.substring(0,q);}text=text.split("#",2)[0];
  boolean complete=v.complete,relative=true;try{String origin=contextual?documentBase:base;relative=!URI.create(text.replace("{dynamic}","DYNAMIC_VALUE")).isAbsolute();if(relative){if(origin!=null&&!origin.isBlank())text=URI.create(origin).resolve(text.replace("{dynamic}","DYNAMIC_VALUE")).toString().replace("DYNAMIC_VALUE","{dynamic}");else if(contextual)complete=false;}if(baseValue!=null&&!baseValue.complete)complete=false;URI u=URI.create(text.replace("{dynamic}","DYNAMIC_VALUE"));if(u.getUserInfo()!=null)text=u.getScheme()+"://"+u.getRawAuthority().substring(u.getRawAuthority().lastIndexOf('@')+1)+(u.getRawPath()==null?"":u.getRawPath());}catch(IllegalArgumentException ex){try{URI.create(text.replaceAll("\\{[^}]*}","DYNAMIC_VALUE"));complete=false;}catch(IllegalArgumentException invalid){return;}}
  if(text.isEmpty()||urls.size()>=resultLimit){bounded|=urls.size()>=resultLimit;return;}String paramKeys=keys(object(option(options,"params")));if(!paramKeys.isEmpty())parameters=parameters.isEmpty()?paramKeys:parameters+", "+paramKeys;
  AstNode location=wrapperSite==null?node:wrapperSite;String dedup=text+"|"+method+"|"+(offset+location.getAbsolutePosition());if(!urlKeys.add(dedup))return;int pos=Math.max(0,offset+location.getAbsolutePosition());urls.add(new Endpoint(text,method,kind,parameters,keys(object(option(options,"headers"))),keys(object(directBody==null?property(options,body):directBody)),line(pos),pos,location.getLength(),complete,"구문 맥락 추정 · URL 쿼리 값 제외"+(contextual&&documentBase==null&&relative?" · 실행 페이지 미확정":"")));
  Endpoint added=urls.get(urls.size()-1);prepareDraft(added,v.text,options,directBody==null?property(options,body):directBody);
  var draft=requestDrafts.get(draftKey(added));if(draft!=null){var operations=JsGraphql.operations(draft.body());if(!operations.isEmpty()){
   urls.remove(urls.size()-1);requestDrafts.remove(draftKey(added));
   for(var operation:operations){Endpoint graphEndpoint=new Endpoint(added.url,added.method,added.kind+" · "+operation.label(),added.parameters,added.headers,added.bodyKeys,added.line,added.start,added.length,added.complete,added.evidence);urls.add(graphEndpoint);
    String graphBody=draft.body();try{if(!operation.name().equals("(anonymous)")){var payload=new LinkedHashMap<String,Object>((Map<String,Object>)MinerJson.parse(graphBody));payload.put("operationName",operation.name());graphBody=MetadataExport.json(payload);}}catch(Exception ignored){}
    requestDrafts.put(draftKey(graphEndpoint),new JsRequestDraft.Draft(draft.url(),draft.method(),graphBody,draft.contentType()));
   }
  }}


 }
 // These drafts stay in the bounded source cache, separate from masked reports and exports.
 private void prepareDraft(Endpoint endpoint,String original,ObjectLiteral options,AstNode bodyNode){
  try{
   JsRequestDraft.Draft fallback=JsRequestDraft.preview(endpoint);
   String baseUrl=fallback.url().split("\\?",2)[0];List<String> query=new ArrayList<>();
   int question=original.indexOf('?');
   if(question>=0)for(String pair:original.substring(question+1).split("#",2)[0].split("&")){
    if(pair.isBlank()||query.size()>=64)continue;String[] bits=pair.split("=",2);
    String name=java.net.URLDecoder.decode(bits[0],StandardCharsets.UTF_8);
    String value=bits.length<2?"":java.net.URLDecoder.decode(bits[1],StandardCharsets.UTF_8);
    addQuery(query,name,sensitive(name)||value.contains("{dynamic}")?"REPLACE_ME":value);
   }
   ObjectLiteral params=object(option(options,"params"));
   if(params!=null)for(AbstractObjectProperty item:params.getElements())if(item instanceof ObjectProperty p){
    String name=key(p.getKey());Object value=sensitive(name)?"REPLACE_ME":literal(p.getValue(),0,new int[]{0});
    addQuery(query,name,value==null||value instanceof Map||value instanceof List?"REPLACE_ME":String.valueOf(value));
   }
   String contentType="application/json";ObjectLiteral headers=object(option(options,"headers"));
   if(headers!=null)for(AbstractObjectProperty item:headers.getElements())if(item instanceof ObjectProperty p&&key(p.getKey()).equalsIgnoreCase("Content-Type")){
    Value type=value(p.getValue(),0);if(type!=null&&type.complete)contentType=type.text;
   }
   if(!contentType.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+(?:;[ A-Za-z0-9=._-]*)?"))contentType="application/octet-stream";
   String body="";bodyNode=constantNode(bodyNode,0);
   if(bodyNode!=null){
    String type=contentType.split(";",2)[0].strip().toLowerCase(Locale.ROOT);
    boolean stringify=bodyNode instanceof FunctionCall call&&callee(call.getTarget()).equals("JSON.stringify");
    Object value=literal(stringify?((FunctionCall)bodyNode).getArguments().isEmpty()?null:((FunctionCall)bodyNode).getArguments().get(0):bodyNode,0,new int[]{0});
    if((endpoint.kind().equals("fetch")||endpoint.kind().startsWith("fetch 래퍼")||endpoint.kind().equals("Request"))&&!stringify&&bodyNode instanceof ObjectLiteral)body="REPLACE_BODY";
    else if(type.equals("application/json")||type.endsWith("+json")){
     if(!stringify&&value instanceof String string&&!string.equals("REPLACE_ME"))try{value=maskFields(MinerJson.parse(string));}catch(Exception ignored){value="REPLACE_ME";}
     body=MetadataExport.json(value);
    }else if(type.equals("application/x-www-form-urlencoded")&&value instanceof Map<?,?> fields){
     List<String> pairs=new ArrayList<>();for(var item:fields.entrySet())addQuery(pairs,item.getKey().toString(),item.getValue()==null||item.getValue() instanceof Map||item.getValue() instanceof List?"REPLACE_ME":String.valueOf(item.getValue()));body=String.join("&",pairs);
    }else if(value instanceof String string&&List.of("text/plain","application/x-www-form-urlencoded","application/xml","text/xml").contains(type))body=string;
    else body="REPLACE_BODY";
    if(body.length()>16384)body="REPLACE_BODY";
   }
   requestDrafts.put(draftKey(endpoint),new JsRequestDraft.Draft(baseUrl+(query.isEmpty()?"":"?"+String.join("&",query)),fallback.method(),body,contentType));
  }catch(IllegalArgumentException ignored){/* An unsupported URL still remains visible as a finding. */}
 }
 private AstNode constantNode(AstNode node,int depth){
  if(depth>8)return node;
  if(node instanceof ParenthesizedExpression p)return constantNode(p.getExpression(),depth+1);
  if(node instanceof Name name){AstNode arg=argument(name);if(arg!=null&&arg!=node)return constantNode(arg,depth+1);VariableInitializer v=binding(name);if(v!=null)return constantNode(v.getInitializer(),depth+1);}
  return node;
 }
 private static boolean sensitive(String key){return key.matches("(?i).*(?:token|secret|password|authorization|cookie|credential|session|api.?key).*");}
 private static void addQuery(List<String> query,String name,String value){if(!name.isBlank()&&name.length()<=128&&query.size()<64)query.add(java.net.URLEncoder.encode(name,StandardCharsets.UTF_8)+"="+java.net.URLEncoder.encode(value,StandardCharsets.UTF_8));}
 private Object literal(AstNode node,int depth,int[] count){
  budget();if(node==null||depth>8||++count[0]>256)return "REPLACE_ME";
  if(node instanceof ParenthesizedExpression expression)return literal(expression.getExpression(),depth+1,count);
  if(node instanceof Name name){AstNode arg=argument(name);if(arg!=null&&arg!=node)return literal(arg,depth+1,count);VariableInitializer variable=binding(name);return variable==null?"REPLACE_ME":literal(variable.getInitializer(),depth+1,count);}
  if(node instanceof StringLiteral string)return string.getValue().length()>16384?"REPLACE_ME":string.getValue();
  if(node instanceof NumberLiteral number)try{return MinerJson.parse(number.getValue());}catch(Exception ignored){return "REPLACE_ME";}
  if(node instanceof KeywordLiteral keyword)return switch(keyword.getType()){case Token.TRUE->true;case Token.FALSE->false;case Token.NULL->null;default->"REPLACE_ME";};
  if(node instanceof UnaryExpression unary&&unary.getOperator()==Token.NEG&&unary.getOperand() instanceof NumberLiteral number)try{return MinerJson.parse("-"+number.getValue());}catch(Exception ignored){return "REPLACE_ME";}
  if(node instanceof ObjectLiteral object){
   Map<String,Object> result=new LinkedHashMap<>();for(AbstractObjectProperty item:object.getElements()){
    if(!(item instanceof ObjectProperty p)||result.size()>=64||p.isGetterMethod()||p.isSetterMethod()||p.isNormalMethod())return "REPLACE_ME";
    String key=key(p.getKey());if(key.equals("[동적 키]"))return "REPLACE_ME";
    result.put(key,sensitive(key)?"REPLACE_ME":literal(p.getValue(),depth+1,count));
   }return result;
  }
  if(node instanceof ArrayLiteral array){List<Object> values=new ArrayList<>();for(AstNode item:array.getElements()){if(values.size()>=64)return "REPLACE_ME";values.add(literal(item,depth+1,count));}return values;}
  Value constant=value(node,0);return constant!=null&&constant.complete&&constant.text.length()<=16384?constant.text:"REPLACE_ME";
 }
 private static Object maskFields(Object value){
  if(value instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();for(var item:map.entrySet())result.put(item.getKey().toString(),sensitive(item.getKey().toString())?"REPLACE_ME":maskFields(item.getValue()));return result;}
  if(value instanceof List<?> list)return list.stream().map(JsAnalysis::maskFields).toList();return value;
 }

 private void secretContext(String key,Value v,AstNode n){if(v==null||!v.complete||n==null||!key.matches("(?i).*(?:api[_-]?key|secret|token|password|authorization|private[_-]?key).*")||v.text.length()<8||v.text.length()>16384)return;if(v.text.matches("(?i).*(?:example|placeholder|your[_ -]|changeme|undefined|process\\.env|\\{dynamic\\}).*"))return;for(Rule r:RULES)if(r.pattern.matcher(v.text).find())return;
  double entropy=entropy(v.text);if(entropy<3.0||v.text.matches("(?i)^(?:https?://|/).*"))return;addSecret("시크릿 이름: "+key.replaceAll("[^A-Za-z0-9_.$-]",""),v.text,offset+n.getAbsolutePosition(),n.getLength(),"이름·엔트로피 추정 · 공개 설정값/오탐 가능");}
 public static double entropy(String text){Map<Character,Integer> f=new HashMap<>();for(char c:text.toCharArray())f.merge(c,1,Integer::sum);double e=0;for(int n:f.values()){double p=(double)n/text.length();e-=p*Math.log(p)/Math.log(2);}return e;}
 private void addSecret(String rule,String value,int position,int length,String confidence){
  String nearby=source.substring(Math.max(0,position-96),Math.min(source.length(),position+length+64)).toLowerCase(Locale.ROOT);
  if(value.matches("(?is).*(?:example|placeholder|your[_-]?key|changeme).*"))confidence+=" · 예제/자리표시자 의심";
  else if(entropy(value)<2.5)confidence+=" · 반복 패턴 의심";
  else if(nearby.matches("(?s).*(?:publishable|public[_-]?key|client[_-]?id|measurement[_-]?id).*"))confidence+=" · 공개 식별자 가능";
  if(secrets.size()>=resultLimit){bounded=true;return;}String hash=hash(value);if(!secretKeys.add(rule+":"+hash))return;secrets.add(new Secret(rule,"[가림 · "+value.length()+"자]",value.length(),hash.substring(0,16),line(position),position,length,confidence+" · Shannon "+String.format(Locale.ROOT,"%.2f",entropy(value))));}
 private static String hash(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private int line(int position){if(newlineOffsets==null)newlineOffsets=java.util.stream.IntStream.range(0,source.length()).filter(i->source.charAt(i)=='\n').toArray();int index=Arrays.binarySearch(newlineOffsets,Math.min(position,source.length()));return 1+(index>=0?index:-index-1);}
}

