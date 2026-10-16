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
 public record Result(List<Endpoint> endpoints,List<Secret> secrets,String status,boolean truncated,JsMiner.Result miner){
 public Result(List<Endpoint> e,List<Secret> s,String state,boolean t){this(e,s,state,t,new JsMiner.Result(List.of(),List.of(),List.of(),List.of(),List.of(),""));}
 }
 private record Value(String text,boolean complete){}
 private record Segment(String text,int offset){}
 private record Rule(String name,Pattern pattern){}
 private static final List<Rule> RULES=List.of(
  rule("AWS Access Key ID","(?:AKIA|ASIA)[A-Z0-9]{16}"),rule("Google API Key","AIza[0-9A-Za-z_-]{35}"),
  rule("GitHub token","(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{40,255})"),
  rule("Slack token","xox[baprs]-[0-9A-Za-z-]{10,200}"),rule("Stripe secret key","sk_(?:live|test)_[0-9A-Za-z]{16,200}"),
  rule("JWT / JWS","eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
  rule("Private key","-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----[\\s\\S]{0,16384}?-----END (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"));
 private static Rule rule(String n,String p){return new Rule(n,Pattern.compile(p));}
 private final String source,base;private String contentType="";private final List<Endpoint> urls=new ArrayList<>();private final List<Secret> secrets=new ArrayList<>();private final Set<String> urlKeys=new HashSet<>(),secretKeys=new HashSet<>();
 private final Map<Scope,Map<String,VariableInitializer>> bindings=new IdentityHashMap<>();private final Map<Scope,Map<String,FunctionNode>> functions=new IdentityHashMap<>();private record Frame(Scope scope,Map<String,Value> values){}private final Deque<Frame> frames=new ArrayDeque<>();private int offset;private boolean bounded;private long deadline;private int recoveryDepth;
 private JsAnalysis(String source,String base){this.source=source.substring(0,Math.min(SOURCE_LIMIT,source.length()));this.base=base==null?"":base;bounded=source.length()>SOURCE_LIMIT;}
 public static Result analyze(String text,String base){JsAnalysis a=new JsAnalysis(text==null?"":text,base);return a.run();}
 public static Result analyze(String text,String base,String type){JsAnalysis a=new JsAnalysis(text==null?"":text,base);a.contentType=type==null?"":type;return a.run();}
 private Result run(){int errors=0,parsed=0,compatible=0;deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
  TextCandidates.Scan data=TextCandidates.scan(source,contentType,base,deadline);bounded|=data.limited();
  for(Segment seg:data.dataOnly()?List.<Segment>of():segments(source)){if(System.nanoTime()>deadline){bounded=true;break;}offset=seg.offset;bindings.clear();functions.clear();frames.clear();
   try{CompilerEnvirons env=new CompilerEnvirons();env.setLanguageVersion(Context.VERSION_ES6);env.setRecoverFromErrors(false);env.setRecordingComments(false);env.setErrorReporter(new ErrorReporter(){public void warning(String m,String s,int l,String ls,int c){}public void error(String m,String s,int l,String ls,int c){throw new EvaluatorException("unsupported syntax",s,l);}public EvaluatorException runtimeError(String m,String s,int l,String ls,int c){return new EvaluatorException("unsupported syntax",s,l);}});
    JsSyntax.Compatible syntax=JsSyntax.compatible(seg.text);AstRoot root=new Parser(env).parse(syntax.text(),"captured.js",1);if(syntax.changed())compatible++;
    root.visit(n->{budget();if(n instanceof CatchClause clause&&clause.getVarName()==null)clause.setVarName(new Name(clause.getPosition(),"WB_UNUSED_CATCH"));if(n instanceof FunctionNode f&&f.getFunctionName()!=null&&!f.getFunctionName().getIdentifier().isEmpty())functions.computeIfAbsent(f.getEnclosingScope(),x->new HashMap<>()).put(f.getFunctionName().getIdentifier(),f);if(n instanceof VariableInitializer v&&v.getTarget() instanceof Name name&&v.getParent() instanceof VariableDeclaration d)bindings.computeIfAbsent(name.getDefiningScope()==null?name.getEnclosingScope():name.getDefiningScope(),x->new HashMap<>()).put(name.getIdentifier(),d.isConst()?v:null);return true;});
    root.visit(n->{budget();inspect(n);return true;});parsed++;
   }catch(IllegalStateException e){bounded=true;break;}catch(EvaluatorException|StackOverflowError e){errors++;Matcher literal=Pattern.compile("[\"']((?:https?://|wss?://|/|\\./|\\.\\./)[^\"'\\r\\n<>]{1,2048})[\"']").matcher(seg.text);while(literal.find()&&urls.size()<RESULT_LIMIT){StringLiteral node=new StringLiteral(literal.start(),literal.end()-literal.start());node.setValue(literal.group(1));int before=urls.size();endpoint(node,"?","문자열 조각 · AST 실패",null,"body");if(urls.size()>before){Endpoint item=urls.remove(urls.size()-1);urls.add(new Endpoint(item.url,item.method,item.kind,item.parameters,item.headers,item.bodyKeys,item.line,item.start,item.length,false,"문맥/메서드 미확정 · 자동 수집 제외"));}}}
  }
  if(errors>0 && !data.dataOnly() && data.values().isEmpty())data=TextCandidates.fallback(source,deadline);
  for(var item:data.values()){if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted()){bounded=true;break;}dataValue(item,data.format());}
  bounded|=data.limited();
  // Provider patterns also cover JSON config, unsupported syntax and HTML outside scripts.
  for(Rule r:RULES){Matcher m=r.pattern.matcher(source);while(m.find()&&secrets.size()<RESULT_LIMIT)addSecret(r.name,m.group(),m.start(),m.end()-m.start(),"패턴 일치 · 유효성 미검증");}
  String state=(data.dataOnly()?data.format()+" 정적 분석":"AST 블록 "+parsed)+" · URL/API "+urls.size()+" · 시크릿 후보 "+secrets.size()+" · 실행/요청/키 검증 없음";
  if(compatible>0)state+=" · async/module 구문 호환 보조 "+compatible;if(errors>0)state+=" · AST 실패 "+errors+" (지원 문법 밖/오류; 패턴 탐지만 보조)";if(bounded)state+=" · 입력/시간/결과 제한으로 일부 분석";
  JsMiner.Result miner=JsMiner.analyze(source,base);
  if(recoveryDepth==0)for(var recovered:miner.recovered()){if(System.nanoTime()>deadline){bounded=true;break;}JsAnalysis child=new JsAnalysis(recovered.content(),base);child.recoveryDepth=1;Result result=child.run();miner=JsMiner.mergeRecon(miner,result.miner());for(Endpoint e:result.endpoints())if(urls.size()<RESULT_LIMIT)urls.add(new Endpoint(e.url,e.method,"source map: "+recovered.name(),e.parameters,e.headers,e.bodyKeys,e.line,-1,0,e.complete,e.evidence));for(Secret secret:result.secrets())if(secrets.size()<RESULT_LIMIT&&secretKeys.add(secret.rule+":"+secret.fingerprint))secrets.add(new Secret(secret.rule,secret.masked,secret.length,secret.fingerprint,secret.line,-1,0,"source map: "+recovered.name()+" · "+secret.confidence));}
  return new Result(List.copyOf(urls),List.copyOf(secrets),state.replaceAll("URL/API \\d+","URL/API "+urls.size()).replaceAll("시크릿 후보 \\d+","시크릿 후보 "+secrets.size())+" · 도메인 "+miner.domains().size()+" · map "+miner.maps().size()+" · 원본 "+miner.recovered().size(),bounded,miner);
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
  for(Rule r:RULES){Matcher m=r.pattern.matcher(value);while(m.find()&&secrets.size()<RESULT_LIMIT)addSecret(r.name,m.group(),item.start(),item.length(),format+" 문자열 패턴 · 유효성 미검증");}
  secretContext(item.key(),new Value(value,true),node);
 }
 private void budget(){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IllegalStateException("limit");}
 private static List<Segment> segments(String text){if(!Pattern.compile("(?is)^\\s*(?:<!doctype|<html|<head|<body|<script|<div)").matcher(text).find())return List.of(new Segment(text,0));List<Segment> list=new ArrayList<>();Matcher m=Pattern.compile("(?is)<script\\b([^>]*)>(.*?)</script\\s*>").matcher(text);while(m.find()&&list.size()<64){String attrs=m.group(1);if(!attrs.matches("(?is).*\\bsrc\\s*=.*")&&!attrs.matches("(?is).*\\btype\\s*=\\s*['\"]application/(?:ld\\+)?json.*"))list.add(new Segment(m.group(2),m.start(2)));}return list;}
 private void inspect(AstNode n){
  if(n instanceof FunctionCall call){String target=callee(call.getTarget());List<AstNode> args=call.getArguments();
   FunctionNode wrapper=call.getTarget() instanceof Name name?function(name):null;if(wrapper!=null&&wrapper(wrapper)&&!args.isEmpty()){ObjectLiteral options=args.size()>1?object(args.get(1)):null;endpoint(args.get(0),method(options,"GET"),"fetch 래퍼 "+target,options,"body");}
   if(Set.of("fetch","window.fetch","globalThis.fetch","Request","window.Request").contains(target)&&!args.isEmpty()){ObjectLiteral options=args.size()>1?object(args.get(1)):null;endpoint(args.get(0),method(options,"GET"),target.endsWith("Request")?"Request":"fetch",options,"body");}
   else if(target.equals("axios")&&!args.isEmpty()){ObjectLiteral options=object(args.get(0));if(options!=null&&property(options,"url")!=null)endpoint(property(options,"url"),method(options,"GET"),"axios config",options,"data");else endpoint(args.get(0),"GET","axios",args.size()>1?object(args.get(1)):null,"data");}
   else if(target.matches("axios\\.(get|post|put|patch|delete|head|options|request)")&&!args.isEmpty()){String verb=target.substring(6);if(verb.equals("request")){ObjectLiteral o=object(args.get(0));if(o!=null&&property(o,"url")!=null)endpoint(property(o,"url"),method(o,"GET"),"axios.request",o,"data");}else {ObjectLiteral options=args.size()>1?object(args.get(args.size()-1)):null;ObjectLiteral body=Set.of("post","put","patch").contains(verb)&&args.size()>1?object(args.get(1)):null;endpoint(args.get(0),verb.toUpperCase(Locale.ROOT),"axios."+verb,options,"data",body);}}
   else if(target.endsWith(".open")&&!target.equals("window.open")&&args.size()>1){Value verb=value(args.get(0),0);if(verb!=null&&verb.complete&&verb.text.matches("(?i)GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS"))endpoint(args.get(1),verb.text.toUpperCase(Locale.ROOT),"XHR-like open",null,"body");}
   else if(Set.of("WebSocket","window.WebSocket","EventSource","window.EventSource","window.open","location.assign","location.replace","window.location.assign","window.location.replace").contains(target)&&!args.isEmpty())endpoint(args.get(0),target.contains("WebSocket")?"UPGRADE":"GET",target,null,"body");
  }
  if(n instanceof Assignment a&&Set.of("location","location.href","window.location","window.location.href","document.location","document.location.href").contains(callee(a.getLeft())))endpoint(a.getRight(),"GET","location assignment",null,"body");
  if(n instanceof ObjectProperty p){String key=key(p.getKey());Value v=value(p.getValue(),0);secretContext(key,v,p.getValue());if(v!=null&&key.matches("(?i)(url|apiurl|baseurl|endpoint|uri|path)"))endpoint(p.getValue(),"?","config "+key,null,"body");}
  if(n instanceof FunctionNode f&&f.getFunctionName()!=null&&!f.getFunctionName().getIdentifier().isEmpty())functions.computeIfAbsent(f.getEnclosingScope(),x->new HashMap<>()).put(f.getFunctionName().getIdentifier(),f);if(n instanceof VariableInitializer v&&v.getTarget() instanceof Name name){secretContext(name.getIdentifier(),value(v.getInitializer(),0),v.getInitializer());if(name.getIdentifier().matches("(?i)(apiurl|baseurl|endpoint|url|uri)"))endpoint(v.getInitializer(),"?","설정 변수 "+name.getIdentifier(),null,"body");}
  if(n instanceof Assignment a)secretContext(callee(a.getLeft()),value(a.getRight(),0),a.getRight());
  if(n instanceof StringLiteral literal){String v=literal.getValue();for(Rule rule:RULES){Matcher m=rule.pattern.matcher(v);while(m.find()&&secrets.size()<RESULT_LIMIT)addSecret(rule.name,m.group(),offset+n.getAbsolutePosition(),n.getLength(),"구문 문자열 + 패턴 · 유효성 미검증");}}
 }
 private FunctionNode function(Name name){VariableInitializer b=binding(name);if(b!=null&&b.getInitializer() instanceof FunctionNode f)return f;for(Scope scope=name.getEnclosingScope();scope!=null;scope=scope.getEnclosingScope()){Map<String,FunctionNode> map=functions.get(scope);if(map!=null&&map.containsKey(name.getIdentifier()))return map.get(name.getIdentifier());if(scope instanceof FunctionNode fn&&fn.getParams().stream().anyMatch(p->p instanceof Name param&&param.getIdentifier().equals(name.getIdentifier())))return null;}return null;}
 private boolean wrapper(FunctionNode fn){if(fn.getParams().isEmpty()||!(fn.getParams().get(0) instanceof Name first))return false;boolean[] found={false};fn.getBody().visit(n->{budget();if(n instanceof FunctionNode)return false;if(n instanceof FunctionCall c&&Set.of("fetch","window.fetch","globalThis.fetch").contains(callee(c.getTarget()))&&!c.getArguments().isEmpty()&&c.getArguments().get(0) instanceof Name x&&x.getIdentifier().equals(first.getIdentifier()))found[0]=true;return true;});return found[0];}
 private ObjectLiteral object(AstNode n){if(n instanceof ObjectLiteral o)return o;if(n instanceof Name name){VariableInitializer v=binding(name);if(v!=null&&v.getInitializer() instanceof ObjectLiteral o)return o;}return null;}
 private VariableInitializer binding(Name n){for(Scope scope=n.getEnclosingScope();scope!=null;scope=scope.getEnclosingScope()){Map<String,VariableInitializer> map=bindings.get(scope);if(map!=null&&map.containsKey(n.getIdentifier())){VariableInitializer v=map.get(n.getIdentifier());return v!=null&&v.getAbsolutePosition()<n.getAbsolutePosition()?v:null;}if(scope instanceof FunctionNode fn&&fn.getParams().stream().anyMatch(p->p instanceof Name param&&param.getIdentifier().equals(n.getIdentifier())))return null;}return null;}
 private AstNode property(ObjectLiteral o,String key){if(o==null)return null;for(AbstractObjectProperty prop:o.getElements())if(prop instanceof ObjectProperty p&&key(p.getKey()).equals(key))return p.getValue();return null;}
 private String keys(ObjectLiteral o){if(o==null)return "";List<String> keys=new ArrayList<>();for(AbstractObjectProperty prop:o.getElements())if(prop instanceof ObjectProperty p&&keys.size()<32)keys.add(key(p.getKey()));return String.join(", ",keys);}
 private String method(ObjectLiteral o,String fallback){AstNode n=property(o,"method");if(n==null)return fallback;Value v=value(n,0);return v!=null&&v.complete&&v.text.matches("(?i)[A-Z]{3,12}")?v.text.toUpperCase(Locale.ROOT):"동적";}
 private static String key(AstNode n){if(n instanceof Name name)return name.getIdentifier();if(n instanceof StringLiteral str)return str.getValue();return "[동적 키]";}
 private static String callee(AstNode n){if(n instanceof Name name)return name.getIdentifier();if(n instanceof PropertyGet p)return callee(p.getTarget())+"."+p.getProperty().getIdentifier();return "";}
 private Value value(AstNode n,int depth){if(n==null||depth>16)return null;if(n instanceof StringLiteral s)return new Value(s.getValue(),true);if(n instanceof NumberLiteral d)return new Value(d.getValue(),true);if(n instanceof ParenthesizedExpression p)return value(p.getExpression(),depth+1);
  if(n instanceof Name name){for(Frame frame:frames)if((frame.scope==name.getDefiningScope()||frame.scope==name.getEnclosingScope())&&frame.values.containsKey(name.getIdentifier()))return frame.values.get(name.getIdentifier());VariableInitializer b=binding(name);return b==null?null:value(b.getInitializer(),depth+1);}
  if(n instanceof FunctionCall call&&call.getTarget() instanceof Name name){FunctionNode fn=function(name);if(fn==null||frames.size()>=8)return null;Map<String,Value> values=new HashMap<>();for(int i=0;i<fn.getParams().size();i++)if(fn.getParams().get(i) instanceof Name p){Value v=i<call.getArguments().size()?value(call.getArguments().get(i),depth+1):null;values.put(p.getIdentifier(),v);}List<AstNode> returns=new ArrayList<>();fn.getBody().visit(node->{if(node instanceof FunctionNode)return false;if(node instanceof ReturnStatement r&&r.getReturnValue()!=null)returns.add(r.getReturnValue());return true;});if(returns.size()!=1)return null;frames.push(new Frame(fn,values));try{return value(returns.get(0),depth+1);}finally{frames.pop();}}
  if(n instanceof InfixExpression i&&i.getOperator()==Token.ADD){Value a=value(i.getLeft(),depth+1),b=value(i.getRight(),depth+1);if(a==null&&b==null)return null;if(a!=null&&b!=null&&a.text.matches("[0-9.]+")&&b.text.matches("[0-9.]+"))return null;String text=(a==null?"{dynamic}":a.text)+(b==null?"{dynamic}":b.text);if(text.length()>8192)return null;return new Value(text,a!=null&&b!=null&&a.complete&&b.complete);}
  if(n instanceof TemplateLiteral t){StringBuilder text=new StringBuilder();boolean complete=true;for(AstNode part:t.getElements()){if(part instanceof TemplateCharacters chars)text.append(chars.getValue());else {Value v=value(part,depth+1);text.append(v==null?"{dynamic}":v.text);complete&=v!=null&&v.complete;}if(text.length()>8192)return null;}return new Value(text.toString(),complete);}
  return null;
 }
 private void endpoint(AstNode node,String method,String kind,ObjectLiteral options,String body){endpoint(node,method,kind,options,body,null);}
 private void endpoint(AstNode node,String method,String kind,ObjectLiteral options,String body,ObjectLiteral directBody){Value v=value(node,0);if(v==null||v.text.length()>8192||!v.text.matches("(?s)(?:https?://|wss?://|/|\\./|\\.\\./).*"))return;
  Value baseValue=value(property(options,"baseURL"),0);String text=v.text;if(baseValue!=null&&baseValue.complete&&text.startsWith("/")&&!text.startsWith("//"))text=baseValue.text.replaceAll("/+$","")+text;
  String parameters="";int q=text.indexOf('?');if(q>=0){String query=text.substring(q+1).split("#",2)[0];List<String> names=new ArrayList<>();for(String pair:query.split("&"))if(names.size()<32)names.add(pair.split("=",2)[0].replaceAll("[^A-Za-z0-9_.%\\[\\]-]",""));parameters=String.join(", ",names);text=text.substring(0,q);}text=text.split("#",2)[0];
  boolean complete=v.complete;try{if(!base.isBlank())text=URI.create(base).resolve(text.replace("{dynamic}","DYNAMIC_VALUE")).toString().replace("DYNAMIC_VALUE","{dynamic}");URI u=URI.create(text.replace("{dynamic}","DYNAMIC_VALUE"));if(u.getUserInfo()!=null)text=u.getScheme()+"://"+u.getRawAuthority().substring(u.getRawAuthority().lastIndexOf('@')+1)+(u.getRawPath()==null?"":u.getRawPath());}catch(Exception ex){complete=false;}
  if(text.isEmpty()||urls.size()>=RESULT_LIMIT){bounded|=urls.size()>=RESULT_LIMIT;return;}String paramKeys=keys(object(property(options,"params")));if(!paramKeys.isEmpty())parameters=parameters.isEmpty()?paramKeys:parameters+", "+paramKeys;
  String dedup=text+"|"+method+"|"+(offset+node.getAbsolutePosition());if(!urlKeys.add(dedup))return;int pos=Math.max(0,offset+node.getAbsolutePosition());urls.add(new Endpoint(text,method,kind,parameters,keys(object(property(options,"headers"))),keys(directBody==null?object(property(options,body)):directBody),line(pos),pos,node.getLength(),complete,"구문 맥락 추정 · URL 쿼리 값 제외"));
 }
 private void secretContext(String key,Value v,AstNode n){if(v==null||!v.complete||n==null||!key.matches("(?i).*(?:api[_-]?key|secret|token|password|authorization|private[_-]?key).*")||v.text.length()<8||v.text.length()>16384)return;if(v.text.matches("(?i).*(?:example|placeholder|your[_ -]|changeme|undefined|process\\.env|\\{dynamic\\}).*"))return;for(Rule r:RULES)if(r.pattern.matcher(v.text).find())return;
  double entropy=entropy(v.text);if(entropy<3.0||v.text.matches("(?i)^(?:https?://|/).*"))return;addSecret("시크릿 이름: "+key.replaceAll("[^A-Za-z0-9_.$-]",""),v.text,offset+n.getAbsolutePosition(),n.getLength(),"이름·엔트로피 추정 · 공개 설정값/오탐 가능");}
 public static double entropy(String text){Map<Character,Integer> f=new HashMap<>();for(char c:text.toCharArray())f.merge(c,1,Integer::sum);double e=0;for(int n:f.values()){double p=(double)n/text.length();e-=p*Math.log(p)/Math.log(2);}return e;}
 private void addSecret(String rule,String value,int position,int length,String confidence){if(secrets.size()>=RESULT_LIMIT){bounded=true;return;}String hash=hash(value);if(!secretKeys.add(rule+":"+hash))return;secrets.add(new Secret(rule,"[가림 · "+value.length()+"자]",value.length(),hash.substring(0,16),line(position),position,length,confidence+" · Shannon "+String.format(Locale.ROOT,"%.2f",entropy(value))));}
 private static String hash(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private int line(int position){int line=1;for(int i=0;i<Math.min(position,source.length());i++)if(source.charAt(i)=='\n')line++;return line;}
}
