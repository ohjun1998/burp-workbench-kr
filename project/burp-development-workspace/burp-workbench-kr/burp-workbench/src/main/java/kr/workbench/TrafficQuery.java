package kr.workbench;

import java.net.URI;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Immutable view settings; no scope or capture side effects and no credential serialization. */
record TrafficQuery(String method,String status,int range,boolean sensitive,boolean regex,
                    boolean group,boolean normalize,boolean important,boolean memo,String account,int minutes) {
  TrafficQuery { if(method==null||!method.matches("전체|GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS")||status==null||!status.matches("전체|[1-5][0-9]{2}|[2-5]xx")||account==null||account.length()>128||account.chars().anyMatch(Character::isISOControl)||range<0||range>2||minutes<0||minutes>1440)throw new IllegalArgumentException("검색 조건·범위 확인"); }
  static TrafficQuery defaults(){return new TrafficQuery("전체","전체",0,false,false,false,true,false,true,"",0);}
  Map<String,Object> json(){return Map.ofEntries(Map.entry("method",method),Map.entry("status",status),Map.entry("range",range),Map.entry("sensitive",sensitive),Map.entry("regex",regex),Map.entry("group",group),Map.entry("normalize",normalize),Map.entry("important",important),Map.entry("memo",memo),Map.entry("account",account),Map.entry("minutes",minutes));}
  static TrafficQuery read(String json){
    if(json==null||json.length()>4096)return defaults();
    try {var m=(Map<?,?>)MinerJson.parse(json);return new TrafficQuery((String)m.get("method"),(String)m.get("status"),((Number)m.get("range")).intValue(),(Boolean)m.get("sensitive"),(Boolean)m.get("regex"),(Boolean)m.get("group"),(Boolean)m.get("normalize"),(Boolean)m.get("important"),(Boolean)m.get("memo"),(String)m.get("account"),((Number)m.get("minutes")).intValue());}
    catch(RuntimeException ex){return defaults();}
  }
  boolean matchesMeta(String verb,String url,String code,String who,String query,BooleanSupplier cancelled){
    if(!method.equals("전체")&&!method.equals(verb))return false;
    if(!status.equals("전체")&&!(status.endsWith("xx")?code.startsWith(status.substring(0,1)):code.equals(status)))return false;
    if(!account.isBlank()&&!who.toLowerCase(Locale.ROOT).contains(account.toLowerCase(Locale.ROOT)))return false;
    return matchesText(verb+" "+url+" "+code,query,cancelled);
  }
  boolean matchesText(String text,String query,BooleanSupplier cancelled){
    if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
    if(query.isBlank())return true;
    var r=PacketInspector.find(text,query,regex,sensitive,cancelled);
    if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
    if(r.error()!=null)throw new IllegalArgumentException(r.error());return !r.spans().isEmpty();
  }
  /** The boundary is inclusive, matching the original recent-period filter. */
  boolean includesTime(long capturedAt,long now){return minutes==0||now<=expiresAt(capturedAt)-1;}
  long expiresAt(long capturedAt){
    if(minutes==0)return Long.MAX_VALUE;
    long duration=minutes*60_000L+1;
    return capturedAt>Long.MAX_VALUE-duration?Long.MAX_VALUE:capturedAt+duration;
  }
  String groupKey(String method,String url,String identity){
    try {URI u=URI.create(url);String path=u.getRawPath();
      if(normalize)path=path.replaceAll("(?i)(?<=/)(?:[0-9]+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?=/|$)","{id}");
      TreeSet<String> names=new TreeSet<>();if(u.getRawQuery()!=null)for(String p:u.getRawQuery().split("&"))names.add(p.split("=",2)[0]);
      return method+" "+u.getScheme()+"://"+u.getRawAuthority()+path+"?"+String.join("&",names)+"|"+identity;
    }catch(RuntimeException ex){return method+" "+url+"|"+identity;}
  }
  String groupPath(String url){String key=groupKey("",url,"");return key.substring(1,key.length()-1).replaceAll("\\?$","");}
}
