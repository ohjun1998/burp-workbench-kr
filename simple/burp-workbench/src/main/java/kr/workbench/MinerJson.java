package kr.workbench;
import java.util.*;
/** Bounded data-only JSON parser; no JavaScript evaluation. */
final class MinerJson {
 private final String s;private int p,nodes;private final int nodeLimit;private MinerJson(String s,int nodes){this.s=s;this.nodeLimit=nodes;}
 static Object parse(String s){return parse(s,2*1024*1024,20000);}
 static Object parse(String s,int bytes,int nodes){if(s.length()>bytes)throw new IllegalArgumentException("JSON 크기 제한");MinerJson j=new MinerJson(s,nodes);Object v=j.value(0);j.ws();if(j.p!=s.length())throw new IllegalArgumentException("JSON 후행 데이터");return v;}
 private void ws(){while(p<s.length()&&" \t\r\n".indexOf(s.charAt(p))>=0)p++;}
 private Object value(int d){ws();if(d>40||++nodes>nodeLimit||p>=s.length())throw new IllegalArgumentException("JSON 구조 제한/오류");char c=s.charAt(p);
  if(c=='"')return string();if(c=='{'){p++;Map<String,Object> m=new LinkedHashMap<>();ws();if(take('}'))return m;do{ws();String k=string();ws();need(':');if(m.containsKey(k))throw new IllegalArgumentException("JSON 중복 키");m.put(k,value(d+1));ws();if(take('}'))return m;need(',');}while(true);}
  if(c=='['){p++;List<Object>a=new ArrayList<>();ws();if(take(']'))return a;do{a.add(value(d+1));ws();if(take(']'))return a;need(',');}while(true);}
  for(String t:List.of("true","false","null"))if(s.startsWith(t,p)){p+=t.length();return t.equals("null")?null:t.equals("true");}
  int start=p;while(p<s.length()&&"-+0123456789.eE".indexOf(s.charAt(p))>=0)p++;String num=s.substring(start,p);if(!num.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))throw new IllegalArgumentException("JSON 값 오류");return new java.math.BigDecimal(num).stripTrailingZeros();
 }
 private String string(){need('"');StringBuilder b=new StringBuilder();while(p<s.length()){char c=s.charAt(p++);if(c=='"')return b.toString();if(c<32)throw new IllegalArgumentException("JSON 제어문자");if(c=='\\'){if(p>=s.length())break;c=s.charAt(p++);switch(c){case '"','\\','/'->b.append(c);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(p+4>s.length())throw new IllegalArgumentException("JSON unicode");try{b.append((char)Integer.parseInt(s.substring(p,p+4),16));}catch(Exception e){throw new IllegalArgumentException("JSON unicode");}p+=4;}default->throw new IllegalArgumentException("JSON escape");}}else b.append(c);}throw new IllegalArgumentException("JSON 문자열 오류");}
 private boolean take(char c){if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false;}private void need(char c){if(!take(c))throw new IllegalArgumentException("JSON 구분자 오류");}
}
