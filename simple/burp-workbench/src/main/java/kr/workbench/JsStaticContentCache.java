package kr.workbench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Pure offset-preserving syntax normalization only; never caches URLs, packets, accounts or ASTs. */
final class JsStaticContentCache {
  static final long DEFAULT_BUDGET=4L*1024*1024;
  static final String PARSER_CONFIG="rhino-1.9.1:es6:offset-compat-v2:tokens-100000";
  private final long budget;
  private final LinkedHashMap<String,JsSyntax.Compatible> values=new LinkedHashMap<>(16,.75f,true);
  private long bytes,hits,misses;
  JsStaticContentCache(long budget){if(budget<1)throw new IllegalArgumentException("cache budget");this.budget=budget;}
  JsSyntax.Compatible get(String source,String configuration,Supplier<JsSyntax.Compatible> compute){
    // Large source units are processed once without adding another retained copy.
    if(source.length()>1024*1024)return compute.get();
    String key=configuration+":"+JsMiner.hash(source);
    synchronized(this){var value=values.get(key);if(value!=null){hits++;return value;}misses++;}
    JsSyntax.Compatible value=compute.get();long cost=128L+key.length()*2L+value.text().length()*2L;
    synchronized(this){if(cost>budget)return value;var old=values.remove(key);if(old!=null)bytes-=weight(key,old);
      while(!values.isEmpty()&&(bytes+cost>budget||values.size()>=128)){var first=values.entrySet().iterator().next();bytes-=weight(first.getKey(),first.getValue());values.remove(first.getKey());}
      values.put(key,value);bytes+=cost;}
    return value;
  }
  private static long weight(String key,JsSyntax.Compatible value){return 128L+key.length()*2L+value.text().length()*2L;}
  synchronized long bytes(){return bytes;} synchronized long hits(){return hits;} synchronized long misses(){return misses;}
  synchronized int size(){return values.size();}
  synchronized void clear(){values.clear();bytes=0;}
}
