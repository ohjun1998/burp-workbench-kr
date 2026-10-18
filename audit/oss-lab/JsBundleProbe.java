package kr.workbench;
import java.nio.file.*;
import java.util.*;
/** Diagnostic only: invoke the same released bundle parser on the exact downloaded target asset. */
public final class JsBundleProbe {
 public static void main(String[] args)throws Exception{
  Map<String,Object> result=new LinkedHashMap<>();String text=Files.readString(Path.of(args[0]));result.put("sourceCharacters",text.length());
  try{var r=JsBatchAnalysis.analyze(text,"http://127.0.0.1:24540/main.js","application/javascript","http://127.0.0.1:24540/");result.put("stage","versions");new JsVersions().record("http://127.0.0.1:24540/main.js","probe",r);result.put("stage","grouping");var grouped=JsFindingGroups.group(r);result.put("groupedEndpoints",grouped.endpoints().size());result.put("status",r.status());result.put("endpoints",r.endpoints().size());result.put("threw",false);}catch(Throwable e){result.put("threw",true);result.put("exception",e.getClass().getName());result.put("message",Objects.toString(e.getMessage(),""));result.put("frames",Arrays.stream(e.getStackTrace()).limit(10).map(Object::toString).toList());}
  Files.writeString(Path.of(args[1]),MetadataExport.json(result));System.out.println(MetadataExport.json(result));
 }
}
