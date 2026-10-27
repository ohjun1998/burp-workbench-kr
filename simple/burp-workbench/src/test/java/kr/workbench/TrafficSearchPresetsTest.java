package kr.workbench;

import java.util.*;

/** Stored filters remain exact, bounded metadata; malformed state cannot silently widen a saved search. */
public final class TrafficSearchPresetsTest {
  private static int checks;
  private static void check(boolean passed,String label){if(!passed)throw new AssertionError(label);checks++;}
  private static void rejects(Runnable action,String label){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,label);}

  public static void main(String[] args){
    TrafficQuery selected=new TrafficQuery("POST","4xx",2,true,true,true,false,true,false,"관리자",17);
    List<TrafficSearchPresets.Preset> saved=TrafficSearchPresets.put(List.of(),"오류 응답",selected);
    String encoded=TrafficSearchPresets.encode(saved);
    check(TrafficSearchPresets.decode(encoded).equals(saved),"every filter including custom recent period survives persistence");
    check(TrafficSearchPresets.restore(TrafficSearchPresets.capture(selected)).equals(selected),"workspace restore retains exact filter metadata");
    check(!encoded.contains("headers")&&!encoded.contains("searchText")&&!encoded.contains("body")&&!encoded.contains("keyword"),"stored filter schema excludes search terms, packet bodies, and auth headers");
    TrafficQuery restored=TrafficSearchPresets.decode(encoded).get(0).query();
    check(restored.prepare("items").matchesFields("POST","403","관리자"),"restored filters accept matching method/status/account");
    check(!restored.prepare("items").matchesFields("GET","403","관리자"),"restored method filter still excludes another method");
    check(!restored.prepare("items").matchesFields("POST","200","관리자"),"restored status filter still excludes success");
    check(!restored.prepare("items").matchesFields("POST","403","일반 사용자"),"restored account filter still excludes another label");
    var revised=TrafficSearchPresets.put(saved,"오류 응답",TrafficQuery.defaults());
    check(revised.size()==1&&revised.get(0).query().equals(TrafficQuery.defaults()),"explicit same-name update replaces conditions without duplicate");
    check(saved.get(0).query().equals(selected),"updating a preset cannot mutate the prior snapshot");
    var second=TrafficSearchPresets.put(saved,"최근 요청",TrafficQuery.defaults());
    check(second.size()==2&&second.get(0).name().equals("오류 응답"),"new named condition preserves existing order and content");
    check(TrafficSearchPresets.decode(null).isEmpty(),"new project starts without named searches");
    rejects(()->TrafficSearchPresets.put(saved," ",selected),"blank name rejected");
    rejects(()->TrafficSearchPresets.put(saved,"x".repeat(61),selected),"overlong name rejected");
    rejects(()->TrafficSearchPresets.put(saved,"name\nCookie: secret",selected),"credential-bearing name rejected");
    var limit=new ArrayList<TrafficSearchPresets.Preset>();for(int i=0;i<TrafficSearchPresets.LIMIT;i++)limit.add(new TrafficSearchPresets.Preset("조건 "+i,selected));
    rejects(()->TrafficSearchPresets.put(limit,"새 조건",selected),"bounded named-search count enforced");
    check(TrafficSearchPresets.put(limit,"조건 0",TrafficQuery.defaults()).size()==24,"existing named search can update at capacity");
    rejects(()->TrafficSearchPresets.encode(List.of(saved.get(0),saved.get(0))),"duplicate names cannot hide a conflicting search");
    rejects(()->TrafficSearchPresets.decode(encoded.replace("\"schema\":1","\"schema\":2")),"future schema is rejected rather than reset");
    rejects(()->TrafficSearchPresets.decode(encoded.substring(0,encoded.length()-1)),"truncated storage cannot silently become a broad search");
    rejects(()->TrafficSearchPresets.restore(TrafficSearchPresets.capture(selected).replace("\"range\":2","\"range\":2.5")),"fractional range rejected");
    rejects(()->TrafficSearchPresets.restore(TrafficSearchPresets.capture(selected).replace("\"minutes\":17","\"minutes\":1441")),"out-of-range period rejected");
    rejects(()->TrafficSearchPresets.restore("{\"schema\":1,\"query\":"+MetadataExport.json(selected.json())+",\"body\":\"private\"}"),"unexpected payload fields rejected");
    rejects(()->TrafficSearchPresets.restore("{\"schema\":1,\"query\":null}"),"missing filter object rejected");
    TrafficQuery authName=new TrafficQuery("GET","전체",0,false,false,false,true,false,true,"Bearer secret",0);
    rejects(()->TrafficSearchPresets.capture(authName),"credential-like account label cannot enter resume state");
    Map<String,Object> initial=selected.json(),defaults=TrafficQuery.defaults().json();
    for(String key:initial.keySet()){
      Map<String,Object> cleared=TrafficSearchPresets.clear(selected,key).json();
      check(Objects.equals(cleared.get(key),defaults.get(key)),"chip clears its own condition: "+key);
      for(String other:initial.keySet())if(!other.equals(key))check(Objects.equals(cleared.get(other),initial.get(other)),"chip preserves unrelated condition: "+key+" / "+other);
    }
    rejects(()->TrafficSearchPresets.clear(selected,"headers"),"unknown chip action rejected");
    var chips=TrafficSearchPresets.conditions(selected);
    check(chips.get("method").equals("POST")&&chips.get("status").equals("4xx")&&chips.get("minutes").equals("최근 17분"),"active labels show actual saved conditions");
    check(TrafficSearchPresets.conditions(TrafficQuery.defaults()).isEmpty(),"default view is clearly unfiltered");
    System.out.println("TrafficSearchPresetsTest: "+checks+" checks passed");
  }
}
