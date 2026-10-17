package kr.workbench;

import java.net.URI;
import java.util.*;
import java.util.regex.*;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ast.*;

/** Captured relative modules only. Bounded static inlining; never fetches or executes code. */
final class JsModuleLinks {
  record Unit(String source,String text,String document){}
  record Linked(String source,String prefix,List<String> origins){boolean used(){return !prefix.isEmpty();}}
  private final LinkedHashMap<String,Unit> units=new LinkedHashMap<>();
  static final Pattern IMPORT=Pattern.compile("\\bimport\\s+([A-Za-z_$][\\w$]*|\\{[^}\\n]{1,256}})\\s+from\\s*['\"]([^'\"\\r\\n]{1,1024})['\"]\\s*;?");
  static final Pattern EXPORT=Pattern.compile("\\bexport\\s+(?:const|function)\\s+([A-Za-z_$][\\w$]*)");
  long evicted;
  synchronized boolean remember(String source,String text,String document){
    if(text.length()>JsAnalysis.SOURCE_LIMIT||JsTrafficIndex.key(source)==null)return false;
    var prior=units.get(JsAnalysisPanel.safeSource(source));boolean changed=prior==null||!prior.text().equals(text)||!prior.document().equals(Objects.toString(document,""));
    units.put(JsAnalysisPanel.safeSource(source),new Unit(source,text,Objects.toString(document,"")));
    while(units.size()>48||units.values().stream().mapToLong(u->u.text().length()*2L).sum()>2*1024*1024){units.remove(units.keySet().iterator().next());evicted++;}
    return changed;
  }
  synchronized List<Unit> dependents(String source){
    List<Unit> out=new ArrayList<>();String wanted=JsAnalysisPanel.safeSource(source);
    for(var unit:units.values()){if(unit.source().equals(source)||out.size()>=8)continue;Matcher imports=IMPORT.matcher(unit.text());while(imports.find())try{String ref=imports.group(2);if(!ref.startsWith("./")&&!ref.startsWith("../"))continue;String candidate=JsAnalysisPanel.safeSource(URI.create(unit.source()).resolve(ref).normalize().toString());if(candidate.equals(wanted)||(candidate+".js").equals(wanted)){out.add(unit);break;}}catch(RuntimeException ignored){}}
    return List.copyOf(out);
  }
  synchronized Linked link(String source,String text,String document){
    StringBuilder prefix=new StringBuilder();List<String> origins=new ArrayList<>();Set<String> aliases=new HashSet<>();Set<String> included=new HashSet<>();
    var tokens=JsSyntax.tokens(text);Set<Integer> starts=new HashSet<>();for(var t:tokens)if(!t.quoted()&&t.text().equals("import"))starts.add(t.start());
    Matcher imports=IMPORT.matcher(text);int count=0;
    while(imports.find()&&count++<16&&prefix.length()<128*1024){
      if(!starts.contains(imports.start()))continue;
      try{
        String ref=imports.group(2);if(!ref.startsWith("./")&&!ref.startsWith("../"))continue;
        String resolved=URI.create(source).resolve(ref).normalize().toString();if(!AssessmentTracker.origin(source).equals(AssessmentTracker.origin(resolved)))continue;
        Unit unit=units.get(JsAnalysisPanel.safeSource(resolved));if(unit==null&&!resolved.endsWith(".js"))unit=units.get(JsAnalysisPanel.safeSource(resolved+".js"));if(unit==null)continue;
        if(!unit.document().isBlank()&&!Objects.toString(document,"").isBlank()&&!JsTrafficIndex.key(unit.document()).equals(JsTrafficIndex.key(document)))continue;
        Map<String,AstNode> definitions=new LinkedHashMap<>();Set<String> exported=new HashSet<>();
        var moduleTokens=JsSyntax.tokens(unit.text());Set<Integer> exportStarts=new HashSet<>();for(var t:moduleTokens)if(!t.quoted()&&t.text().equals("export"))exportStarts.add(t.start());
        Matcher exports=EXPORT.matcher(unit.text());while(exports.find())if(exportStarts.contains(exports.start()))exported.add(exports.group(1));
        CompilerEnvirons env=new CompilerEnvirons();env.setLanguageVersion(Context.VERSION_ES6);AstRoot root=new Parser(env).parse(JsSyntax.compatible(unit.text()).text(),"module",1);
        root.visit(n->{if(n instanceof FunctionNode fn){if(fn.getParent()==root&&fn.getFunctionName()!=null)definitions.put(fn.getName(),fn);return false;}if(n instanceof VariableDeclaration declaration&&declaration.isConst()&&declaration.getParent()==root){for(var v:declaration.getVariables())if(v.getTarget() instanceof Name name&&v.getInitializer()!=null)definitions.put(name.getIdentifier(),v.getInitializer());}return true;});
        String ns="WB_MODULE_"+JsMiner.hash(unit.source()).substring(0,8)+"_";Map<String,String> renamed=new HashMap<>();for(String name:definitions.keySet())renamed.put(name,ns+name);
        StringBuilder module=new StringBuilder();for(var def:definitions.entrySet()){if(def.getValue() instanceof FunctionNode fn)module.append(rename(fn.toSource(),renamed));else module.append("const ").append(ns).append(def.getKey()).append("=").append(rename(def.getValue().toSource(),renamed)).append(';');module.append('\n');}
        String spec=imports.group(1);if(!spec.startsWith("{"))continue;StringBuilder bindings=new StringBuilder();
        for(String item:spec.substring(1,spec.length()-1).split(",")){String[] names=item.strip().split("\\s+as\\s+");String exportedName=names[0].strip(),alias=names[names.length-1].strip();if(!alias.matches("[A-Za-z_$][\\w$]*")||!exported.contains(exportedName)||!definitions.containsKey(exportedName)||!aliases.add(alias))continue;
          AstNode value=definitions.get(exportedName);if(value instanceof FunctionNode fn){Map<String,String> fnNames=new HashMap<>(renamed);fnNames.put(exportedName,alias);bindings.append(rename(fn.toSource(),fnNames));}else bindings.append("const ").append(alias).append('=').append(rename(value.toSource(),renamed)).append(';');bindings.append('\n');}
        if(bindings.length()==0||module.length()+bindings.length()>128*1024)continue;
        if(included.add(unit.source())){prefix.append(module);origins.add(JsAnalysisPanel.safeSource(unit.source()));}prefix.append(bindings);
      }catch(RuntimeException ignored){/* Ambiguous/unsupported imports remain unresolved. */}
    }
    return new Linked(text,prefix.toString(),List.copyOf(origins));
  }
  private static String rename(String source,Map<String,String> names){StringBuilder s=new StringBuilder(source);var tokens=JsSyntax.tokens(source);for(int i=tokens.size()-1;i>=0;i--){var t=tokens.get(i);String replacement=names.get(t.text());if(t.quoted()||replacement==null||i>0&&tokens.get(i-1).text().equals(".")||i+1<tokens.size()&&tokens.get(i+1).text().equals(":"))continue;s.replace(t.start(),t.end(),replacement);}return s.toString();}
  static JsAnalysis.Result analyze(Linked linked,String source,String type,String document){
    if(!linked.used())return JsBatchAnalysis.analyze(linked.source(),source,type,document);
    int shift=linked.prefix().length(),lines=(int)linked.prefix().chars().filter(c->c=='\n').count();
    var combined=JsBatchAnalysis.analyze(linked.prefix()+linked.source(),source,type,document);List<JsAnalysis.Endpoint> endpoints=new ArrayList<>();Map<String,JsRequestDraft.Draft> drafts=new LinkedHashMap<>();
    for(var e:combined.endpoints())if(e.start()>=shift){var adjusted=new JsAnalysis.Endpoint(e.url(),e.method(),e.kind(),e.parameters(),e.headers(),e.bodyKeys(),Math.max(1,e.line()-lines),e.start()-shift,e.length(),e.complete(),e.evidence()+" · 파일 연계: "+String.join(", ",linked.origins()));endpoints.add(adjusted);var draft=combined.requestDrafts().get(JsAnalysis.draftKey(e));if(draft!=null)drafts.put(JsAnalysis.draftKey(adjusted),draft);}
    List<JsAnalysis.Secret> secrets=new ArrayList<>();for(var s:combined.secrets())if(s.start()>=shift)secrets.add(new JsAnalysis.Secret(s.rule(),s.masked(),s.length(),s.fingerprint(),Math.max(1,s.line()-lines),s.start()-shift,s.sourceLength(),s.confidence()));
    var miner=JsMiner.analyze(linked.source(),source);return new JsAnalysis.Result(List.copyOf(endpoints),List.copyOf(secrets),combined.status()+" · 수집 파일 연계 "+linked.origins().size()+"개",combined.truncated(),miner,Map.copyOf(drafts));
  }
  synchronized void clear(){units.clear();}
}
