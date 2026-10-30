package kr.workbench;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** Passive observations and conservative applicability; no target requests. */
final class ExposureResearch {
  enum Truth { YES, NO, UNKNOWN }
  record Product(String vendor, String name, String version, String evidence, String source, String originalId) {
    public String toString(){return name + (version.isBlank()?" · 버전 미확인":" "+version);}
  }
  record Exposure(String url, String category, String query, Instant discovered) {}
  record Candidate(String id, String description, Double score, String vector, String metricSource,
      List<Object> configurations, List<String> references, String modified) {}
  record Batch(List<Candidate> candidates, Set<String> kev, Map<String,Double> epss, Instant fetched,
      String kevStatus, String epssStatus, boolean truncated) {}
  static String host(String input) {
    URI u=URI.create(input.contains("://")?input:"https://"+input);
    if(!Set.of("https","http").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)
      throw new IllegalArgumentException("HTTP(S) 대상 도메인을 입력하세요.");
    return u.getHost().toLowerCase(Locale.ROOT);
  }
  static String url(String input) {
    String normalized=WorkbenchScope.canonical(input.strip());
    if(normalized==null||URI.create(normalized).getUserInfo()!=null)throw new IllegalArgumentException("HTTP(S) URL 형식을 확인하세요.");
    URI u=URI.create(normalized);
    return u.getScheme().toLowerCase(Locale.ROOT)+"://"+u.getRawAuthority().toLowerCase(Locale.ROOT)+(u.getRawPath().isEmpty()?"/":u.getRawPath())+(u.getRawQuery()==null?"":"?"+u.getRawQuery());
  }
  static boolean within(String url,String target,boolean subdomains) {
    try {String h=host(url),t=host(target);return h.equals(t)||(subdomains&&h.endsWith("."+t));}catch(RuntimeException e){return false;}
  }
  static final Map<String,String> DORKS=new LinkedHashMap<>();
  static {DORKS.put("API 문서","(inurl:swagger OR inurl:api-docs OR inurl:openapi)");DORKS.put("공개 문서","(filetype:pdf OR filetype:docx OR filetype:xlsx)");DORKS.put("관리 화면","(inurl:admin OR inurl:manage OR inurl:login)");DORKS.put("디렉터리 목록","intitle:\"index of\"");}
  static String query(String target,String category,boolean subdomains){String h=host(target);return "site:"+h+(subdomains?"":" -site:*."+h)+" "+DORKS.getOrDefault(category,"");}
  static List<Product> observe(String url,String headers,String originalId) {
    List<Product> result=new ArrayList<>();
    String[][] signatures={{"nginx","nginx","(?im)^Server:\\s*(nginx)(?:/([^\\s;()]+))?"},{"apache","http_server","(?im)^Server:\\s*(Apache)(?:/([^\\s;()]+))?"},{"microsoft","internet_information_services","(?im)^Server:\\s*(Microsoft-IIS)(?:/([^\\s;()]+))?"},{"php","php","(?im)^X-Powered-By:\\s*(PHP)(?:/([^\\s;()]+))?"},{"expressjs","express","(?im)^X-Powered-By:\\s*(Express)(?:/([^\\s;()]+))?"}};
    String bounded=headers.substring(0,Math.min(headers.length(),16384));
    for(String[] s:signatures){Matcher m=Pattern.compile(s[2]).matcher(bounded);if(m.find())result.add(new Product(s[0],s[1],Objects.toString(m.group(2),""),m.group(),url,originalId));}
    return List.copyOf(result);
  }
  static Map<?,?> map(Object o){return o instanceof Map<?,?> m?m:Map.of();}
  static List<?> list(Object o){return o instanceof List<?> l?l:List.of();}
  static String str(Object o){return Objects.toString(o,"");}
  static List<Candidate> parseNvd(String json) {
    Map<?,?> doc=map(MinerJson.parse(json,8*1024*1024,300000));
    if(!(doc.get("vulnerabilities") instanceof List<?>))throw new IllegalArgumentException("NVD 응답 형식 오류");
    List<Candidate> result=new ArrayList<>();
    for(Object item:list(doc.get("vulnerabilities"))){Map<?,?> c=map(map(item).get("cve"));String id=str(c.get("id"));
      if(!id.matches("CVE-\\d{4}-\\d{4,}")||"Rejected".equalsIgnoreCase(str(c.get("vulnStatus"))))continue;
      String description="설명 없음";for(Object d:list(c.get("descriptions"))){Map<?,?> m=map(d);if("en".equals(m.get("lang")))description=str(m.get("value"));}
      Map<?,?> metrics=map(c.get("metrics")),metric=Map.of();
      for(String key:List.of("cvssMetricV40","cvssMetricV31","cvssMetricV30")){List<?> options=list(metrics.get(key));if(!options.isEmpty()){metric=map(options.get(0));for(Object o:options)if("Primary".equals(map(o).get("type"))){metric=map(o);break;}break;}}
      Map<?,?> cvss=map(metric.get("cvssData"));Double score=cvss.get("baseScore") instanceof Number n?n.doubleValue():null;
      List<String> refs=new ArrayList<>();for(Object o:list(c.get("references"))){String ref=str(map(o).get("url"));try{refs.add(url(ref));}catch(RuntimeException ignored){}}
      result.add(new Candidate(id,description,score,str(cvss.get("vectorString")),str(metric.get("source")),new ArrayList<>(list(c.get("configurations"))),List.copyOf(refs),str(c.get("lastModified"))));
    }return List.copyOf(result);
  }
  static Truth and(List<Truth> values){return values.contains(Truth.NO)?Truth.NO:values.isEmpty()||values.contains(Truth.UNKNOWN)?Truth.UNKNOWN:Truth.YES;}
  static Truth or(List<Truth> values){return values.contains(Truth.YES)?Truth.YES:values.isEmpty()||values.contains(Truth.UNKNOWN)?Truth.UNKNOWN:Truth.NO;}
  static Integer compareVersion(String a,String b){if(!a.matches("\\d+(\\.\\d+){0,9}")||!b.matches("\\d+(\\.\\d+){0,9}"))return null;String[] x=a.split("\\."),y=b.split("\\.");for(int i=0;i<Math.max(x.length,y.length);i++){int c=new java.math.BigInteger(i<x.length?x[i]:"0").compareTo(new java.math.BigInteger(i<y.length?y[i]:"0"));if(c!=0)return c;}return 0;}
  static Truth match(Map<?,?> rule,Product p){
    String[] cpe=str(rule.get("criteria")).split(":",-1);
    if(cpe.length!=13||!cpe[0].equals("cpe")||!cpe[1].equals("2.3"))return Truth.UNKNOWN;
    if(!cpe[2].equals("a")||!cpe[3].equals(p.vendor())||!cpe[4].equals(p.name()))return Truth.UNKNOWN;
    if(p.version().isBlank())return Truth.UNKNOWN;
    boolean constrained=false;List<Truth> conditions=new ArrayList<>();
    if(!cpe[5].equals("*")){Integer cmp=compareVersion(p.version(),cpe[5]);conditions.add(cmp==null?Truth.UNKNOWN:cmp==0?Truth.YES:Truth.NO);constrained=true;}
    for(String key:List.of("versionStartIncluding","versionStartExcluding","versionEndIncluding","versionEndExcluding"))if(rule.containsKey(key)){
      constrained=true;Integer cmp=compareVersion(p.version(),str(rule.get(key)));
      boolean ok=cmp!=null&&(key.equals("versionStartIncluding")?cmp>=0:key.equals("versionStartExcluding")?cmp>0:key.equals("versionEndIncluding")?cmp<=0:cmp<0);
      conditions.add(cmp==null?Truth.UNKNOWN:ok?Truth.YES:Truth.NO);
    }
    // Edition, target OS/software, language and update constraints are not established by a Server header.
    for(int i=6;i<13;i++)if(!cpe[i].equals("*"))conditions.add(Truth.UNKNOWN);
    if(!constrained)conditions.add(Truth.UNKNOWN);
    return and(conditions);
  }
  static Truth node(Object object,Product p){Map<?,?> n=map(object);List<Truth> values=new ArrayList<>();for(Object r:list(n.get("cpeMatch")))values.add(match(map(r),p));for(Object child:list(n.get("nodes")))values.add(node(child,p));for(Object child:list(n.get("children")))values.add(node(child,p));String operator=str(n.get("operator"));Truth t=operator.equals("AND")?and(values):operator.equals("OR")||operator.isBlank()?or(values):Truth.UNKNOWN;return Boolean.TRUE.equals(n.get("negate"))?Truth.UNKNOWN:t;}
  static boolean hasProduct(Object object,Product p){Map<?,?> n=map(object);for(Object r:list(n.get("cpeMatch"))){Map<?,?> rule=map(r);String[] c=str(rule.get("criteria")).split(":",-1);if(Boolean.TRUE.equals(rule.get("vulnerable"))&&c.length==13&&c[3].equals(p.vendor())&&c[4].equals(p.name()))return true;}for(String key:List.of("nodes","children"))for(Object child:list(n.get(key)))if(hasProduct(child,p))return true;return false;}
  static Truth applicability(Candidate c,Product p){if(c.configurations().stream().noneMatch(n->hasProduct(n,p)))return Truth.UNKNOWN;return or(c.configurations().stream().map(n->node(n,p)).toList());}
  static Truth remoteAnonymous(Candidate c){String v=c.vector();if(v.isBlank()||(!v.startsWith("CVSS:3.")&&!v.startsWith("CVSS:4.")))return Truth.UNKNOWN;return v.contains("/AV:N")&&v.contains("/PR:N")?Truth.YES:Truth.NO;}
  static String affected(Candidate c,Product p){List<String> out=new ArrayList<>();for(Object n:c.configurations())ranges(n,p,out);return out.isEmpty()?"제품 매핑·영향 버전 미확인":String.join("\n",out);}
  static void ranges(Object object,Product p,List<String> out){Map<?,?> n=map(object);for(Object r:list(n.get("cpeMatch"))){Map<?,?> rule=map(r);String[] a=str(rule.get("criteria")).split(":",-1);if(a.length==13&&a[3].equals(p.vendor())&&a[4].equals(p.name())&&Boolean.TRUE.equals(rule.get("vulnerable"))){List<String> parts=new ArrayList<>();if(!a[5].equals("*"))parts.add("= "+a[5]);String[] keys={"versionStartIncluding","versionStartExcluding","versionEndIncluding","versionEndExcluding"},ops={"≥ ","> ","≤ ","< "};for(int i=0;i<keys.length;i++)if(rule.containsKey(keys[i]))parts.add(ops[i]+str(rule.get(keys[i])));out.add(parts.isEmpty()?"버전 범위 미지정":String.join(" · ",parts));}}for(String key:List.of("nodes","children"))for(Object child:list(n.get(key)))ranges(child,p,out);}
  static String verdict(Truth t){return t==Truth.YES?"버전 조건 일치":t==Truth.NO?"버전 조건 제외":"확인 필요";}
}
