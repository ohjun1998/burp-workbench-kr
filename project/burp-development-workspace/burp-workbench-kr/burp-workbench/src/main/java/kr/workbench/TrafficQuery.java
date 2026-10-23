package kr.workbench;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Immutable view settings; no scope or capture side effects and no credential serialization. */
record TrafficQuery(String method,String status,int range,boolean sensitive,boolean regex,
                    boolean group,boolean normalize,boolean important,boolean memo,String account,int minutes) {
  private static final Pattern GROUP_ID=Pattern.compile("(?i)(?<=/)(?:[0-9]+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?=/|$)");
  TrafficQuery { if(method==null||!method.matches("전체|GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS")||status==null||!status.matches("전체|[1-5][0-9]{2}|[2-5]xx")||account==null||account.length()>128||account.chars().anyMatch(Character::isISOControl)||range<0||range>2||minutes<0||minutes>1440)throw new IllegalArgumentException("검색 조건·범위 확인"); }
  static TrafficQuery defaults(){return new TrafficQuery("전체","전체",0,false,false,false,true,false,true,"",0);}
  Map<String,Object> json(){return Map.ofEntries(Map.entry("method",method),Map.entry("status",status),Map.entry("range",range),Map.entry("sensitive",sensitive),Map.entry("regex",regex),Map.entry("group",group),Map.entry("normalize",normalize),Map.entry("important",important),Map.entry("memo",memo),Map.entry("account",account),Map.entry("minutes",minutes));}
  static TrafficQuery read(String json){
    if(json==null||json.length()>4096)return defaults();
    try {var m=(Map<?,?>)MinerJson.parse(json);return new TrafficQuery((String)m.get("method"),(String)m.get("status"),((Number)m.get("range")).intValue(),(Boolean)m.get("sensitive"),(Boolean)m.get("regex"),(Boolean)m.get("group"),(Boolean)m.get("normalize"),(Boolean)m.get("important"),(Boolean)m.get("memo"),(String)m.get("account"),((Number)m.get("minutes")).intValue());}
    catch(RuntimeException ex){return defaults();}
  }
  boolean matchesMeta(String verb,String url,String code,String who,String query,BooleanSupplier cancelled){
    SearchPredicate predicate=prepare(query);
    return predicate.matchesFields(verb,code,who)&&predicate.matchesText(verb+" "+url+" "+code,cancelled);
  }
  boolean matchesText(String text,String query,BooleanSupplier cancelled){
    return prepare(query).matchesText(text,cancelled);
  }
  SearchPredicate prepare(String query){return new SearchPredicate(this,query);}

  /** Compile once per scan and stop at the first nonempty match. A table filter
   * needs membership, not the packet inspector's list of up to 2,000 highlights. */
  static final class SearchPredicate {
    private final TrafficQuery settings;
    private final String account;
    private final Pattern pattern;
    SearchPredicate(TrafficQuery settings,String query){
      this.settings=settings;account=settings.account().toLowerCase(Locale.ROOT);
      if(query.length()>512)throw new IllegalArgumentException("검색어 512자 제한");
      try{pattern=query.isBlank()?null:Pattern.compile(settings.regex()?query:Pattern.quote(query),Pattern.MULTILINE|(settings.sensitive()?0:Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE));}
      catch(PatternSyntaxException error){throw new IllegalArgumentException("정규식 오류",error);}
    }
    boolean matchesFields(String verb,String code,String who){
      if(!settings.method().equals("전체")&&!settings.method().equals(verb))return false;
      String status=settings.status();
      if(!status.equals("전체")&&!(status.endsWith("xx")?code.startsWith(status.substring(0,1)):code.equals(status)))return false;
      return account.isBlank()||who.toLowerCase(Locale.ROOT).contains(account);
    }
    boolean matchesText(String value,BooleanSupplier cancelled){
      if(cancelled.getAsBoolean())throw new CancellationException();
      if(pattern==null)return true;
      long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(200);
      GuardedText guarded=new GuardedText(value,0,value.length(),deadline,cancelled);
      try{
        var matcher=pattern.matcher(guarded);
        while(matcher.find()){
          guarded.check();
          if(matcher.start()!=matcher.end())return true;
        }
        guarded.check();return false;
      }catch(SearchBudget|StackOverflowError error){throw new IllegalArgumentException("검색 제한");}
    }
  }
  private static final class SearchBudget extends RuntimeException {}
  private static final class GuardedText implements CharSequence {
    private final String value;private final int start,end;private final long deadline;private final BooleanSupplier cancelled;private int reads;
    GuardedText(String value,int start,int end,long deadline,BooleanSupplier cancelled){this.value=value;this.start=start;this.end=end;this.deadline=deadline;this.cancelled=cancelled;}
    void check(){if(cancelled.getAsBoolean())throw new CancellationException();if(System.nanoTime()>deadline)throw new SearchBudget();}
    private void guard(){if((++reads&255)==0)check();}
    public int length(){guard();return end-start;}
    public char charAt(int index){guard();Objects.checkIndex(index,end-start);return value.charAt(start+index);}
    public CharSequence subSequence(int from,int to){guard();Objects.checkFromToIndex(from,to,end-start);return new GuardedText(value,start+from,start+to,deadline,cancelled);}
    public String toString(){check();return value.substring(start,end);}
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
      if(normalize)path=GROUP_ID.matcher(path).replaceAll("{id}");
      TreeSet<String> names=new TreeSet<>();if(u.getRawQuery()!=null)for(String p:u.getRawQuery().split("&"))names.add(p.split("=",2)[0]);
      return method+" "+u.getScheme()+"://"+u.getRawAuthority()+path+"?"+String.join("&",names)+"|"+identity;
    }catch(RuntimeException ex){return method+" "+url+"|"+identity;}
  }
  String groupPath(String url){String key=groupKey("",url,"");return key.substring(1,key.length()-1).replaceAll("\\?$","");}
}
