package kr.workbench;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Same retained original responses into each immutable product JAR; makes no HTTP calls. */
public final class OfflineDetectionAudit extends WorkbenchTest {
  static String string(Map<?,?> value,String name){return Objects.toString(value.get(name),"");}
  static int number(Map<?,?> value,String name){return ((Number)value.get(name)).intValue();}
  static String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
  static Map<String,Object> map(Object...pairs){var out=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)out.put(pairs[i].toString(),pairs[i+1]);return out;}
  static void save(Path path,Object value)throws Exception{Files.writeString(path,MetadataExport.json(value)+"\n",StandardCharsets.UTF_8);}
  static boolean compatible(String expected,String actual){return switch(expected){
    case "KEY","TOKEN","SENSITIVE" -> Set.of("SECRET","INTERNAL","SENSITIVE","TOKEN","KEY").contains(actual);
    case "DATABASE" -> Set.of("DATABASE","SECRET","ENVIRONMENT").contains(actual);
    case "INTERNAL" -> Set.of("INTERNAL","SECRET").contains(actual);
    case "API" -> Set.of("API","MENU","PATH").contains(actual);
    case "PATH" -> Set.of("API","MENU","PATH","INTERNAL").contains(actual);
    case "ENVIRONMENT" -> actual.equals("ENVIRONMENT");
    default -> true;
  };}
  static boolean productMatch(Map<?,?> truth,ScopeInspection.Finding finding){
    if(!compatible(string(truth,"category"),finding.category()))return false;
    String digest=string(truth,"valueSHA256");
    boolean fingerprint=!finding.fingerprint().isEmpty()&&(finding.fingerprint().equals(digest)||finding.fingerprint().equals(digest.substring(0,16)));
    int offset=number(truth,"offsetUtf16"),length=number(truth,"lengthUtf16");
    return fingerprint||(!string(truth,"sourceArea").equals("response.headers")&&finding.offset()>=offset&&finding.offset()<offset+Math.max(1,length));
  }
  static String semanticClassification(ScopeInspection.Finding finding){
    var match=java.util.regex.Pattern.compile("분류:\\s*([A-Z_]+)").matcher(finding.detail());
    return match.find()?match.group(1):"";
  }
  static boolean exactSpan(ScopeInspectionReport.Value value,String headers,String body){
    String source=value.source().area().equals("RESPONSE_HEADERS")?headers:body;
    int start=value.source().offset(),length=value.source().length();
    return start>=0&&length>=0&&start<=source.length()-length&&value.rawText().equals(source.substring(start,start+length));
  }
  public static void main(String[]args)throws Exception{
    if(args.length!=2)throw new IllegalArgumentException("OfflineDetectionAudit private-corpus.json output.json");
    Map<?,?> manifest=(Map<?,?>)MinerJson.parse(Files.readString(Path.of(args[0])),16*1024*1024,100000);
    ScopeInspectionFixture.installFactory(); List<Map<String,Object>> cases=new ArrayList<>();
    for(Object item:(List<?>)manifest.get("cases")){
      Map<?,?> input=(Map<?,?>)item;byte[] raw=Files.readAllBytes(Path.of(string(input,"responsePath")));
      if(!hash(raw).equals(string(input,"responseSHA256")))throw new AssertionError("Frozen response changed");
      byte[] decodedBytes=Files.readAllBytes(Path.of(string(input,"decodedPath")));
      if(!hash(decodedBytes).equals(string(input,"decodedSHA256")))throw new AssertionError("Frozen decoded response changed");
      String body=new String(decodedBytes,StandardCharsets.UTF_8),url=string(input,"url"),account=string(input,"account");
      var response=ScopeInspectionFixture.parseResponse(raw);String headers=EnvironmentPanel.responseHeaders(response);
      boolean adapterExact=Arrays.equals(raw,response.toByteArray().getBytes());
      long began=System.nanoTime();
      var result=ScopeInspectionAnalysis.analyze(url,number(input,"status"),headers,body,account,string(input,"originalId"));
      var checks=new ArrayList<Map<String,Object>>();
      try(DiskTrafficStore store=new DiskTrafficStore()){
        var request=ScopeInspectionFixture.wireRequest(url,"GET",List.of(),new byte[0]);registerTrafficPacket(request,response);
        var ref=store.append("offline-audit","retained original response; no target request",request,response);
        var findings=result.findings().stream().map(f->new ScopeInspection.Finding(f.category(),f.title(),f.url(),f.method(),f.account(),f.confidence(),f.severity(),f.state(),f.detail(),ref.id(),f.line(),f.offset(),f.fingerprint())).toList();
        var page=new ScopeInspection.Page(url,"GET",account,number(input,"status"),"offline original",true,"",string(input,"responseSHA256"),ref,string(input,"profileRevision"));
        var snapshot=new ScopeInspection.Snapshot("offline-audit",ScopeInspection.State.COMPLETED,"",1,1,0,0,0,0,findings,List.of(page),result.candidates(),1,2);
        var document=ScopeInspectionReport.collect(snapshot);
        for(Object target:(List<?>)input.get("truth")){
          Map<?,?> truth=(Map<?,?>)target;String expected=string(truth,"value");
          var matching=result.findings().stream().filter(f->productMatch(truth,f)).toList();
          // Display/parser names differ between the old independent supplement
          // and the product. Match the actual value and original evidence span.
          var exported=document.values().stream().filter(v->v.value().equals(expected)).toList();
          boolean exact=exported.stream().anyMatch(v->v.rawText().equals(string(truth,"rawLexeme")));
          boolean exactSource=exported.stream().anyMatch(v->exactSpan(v,headers,body)&&v.source().responseSha256().equals(string(input,"responseSHA256")));
          boolean secret=matching.stream().anyMatch(f->f.category().equals("SECRET")&&!(f.state().contains("예제")||f.state().contains("공개")));
          checks.add(map("id",string(truth,"id"),"category",string(truth,"category"),"expectedKind",string(truth,"expectedKind"),
              "valueSHA256",string(truth,"valueSHA256"),"productLocationOrFingerprintMatch",!matching.isEmpty(),
              "matchedCategories",matching.stream().map(ScopeInspection.Finding::category).distinct().toList(),
              "matchedStates",matching.stream().map(ScopeInspection.Finding::state).distinct().toList(),
              "matchedSeverities",matching.stream().map(ScopeInspection.Finding::severity).distinct().toList(),
              "matchedSemantic",matching.stream().map(f->map("category",f.category(),"state",f.state(),"severity",f.severity(),"classification",semanticClassification(f))).toList(),
              "reportExactValueAndRawMatch",exact,"reportValuePresent",!exported.isEmpty(),
              "reportExactValueAndSourceSpan",exactSource,
              "reportFieldNameMatch",exported.stream().anyMatch(v->v.name().equals(string(truth,"name"))),
              "exportClassifications",exported.stream().map(ScopeInspectionReport.Value::classification).distinct().toList(),
              "exportCategories",exported.stream().map(ScopeInspectionReport.Value::category).distinct().toList(),
              "exportProvenance",exported.stream().map(ScopeInspectionReport.Value::provenance).distinct().toList(),
              "promotedUnverifiedSecret",secret));
        }
        cases.add(map("caseId",string(input,"caseId"),"siteId",string(input,"siteId"),"status",number(input,"status"),
            "responseSHA256",string(input,"responseSHA256"),"decodedSHA256",string(input,"decodedSHA256"),
            "productFindings",result.findings().size(),"productCandidates",result.candidates().size(),"analysisComplete",result.complete(),
            "reportValues",document.values().size(),"reportComplete",document.complete(),
            "adapterWireMatchesOriginal",adapterExact,
            "allExportSourceSpansVerified",document.values().stream().allMatch(v->exactSpan(v,headers,body)),
            "allExportResponseHashesMatchOriginal",document.values().stream().allMatch(v->v.source().responseSha256().equals(string(input,"responseSHA256"))),
            "elapsedMillis",(System.nanoTime()-began)/1_000_000,"checks",checks));
      }
      System.out.println(string(input,"caseId")+" "+string(input,"siteId")+" analyzed");
    }
    save(Path.of(args[1]),map("version",BuildInfo.VERSION,"networkRequests",0,"caseCount",cases.size(),"cases",cases,
        "scope","Offline retained original local-app response reanalysis; no new crawl, live auth, native Burp or whole-site recall measurement"));
  }
}
