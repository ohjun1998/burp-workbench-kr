package kr.workbench;
public final class EnvironmentTest {
 static int checks;static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;System.out.println("PASS "+m);}
 public static void main(String[] args){var c=new EnvironmentCatalog();var s=c.observe("https://example.test/app/page","Server: nginx\nX-Powered-By: Express\nSet-Cookie: secret=TOKEN_SECRET", "<script src='/_next/static/main.js?token=HIDDEN'></script><a href='https://other.test/admin'>x</a><link href='/wp-content/plugins/elementor/style.css'>");
 check(s.detections.containsKey("nginx")&&s.detections.containsKey("Express"),"server and framework headers");check(s.detections.containsKey("Next.js")&&s.detections.containsKey("WordPress")&&s.detections.containsKey("plugin:elementor"),"framework CMS plugin resource signatures");var list=c.candidates(s,"/app");check(list.stream().anyMatch(x->x.url().equals("https://example.test/app/nginx_status")),"application prefix");check(list.stream().anyMatch(x->x.url().equals("https://example.test/_next/static/main.js")),"observed resource query redaction");check(list.stream().allMatch(x->x.url().startsWith("https://example.test/")),"external resources excluded");check(s.detections.values().stream().noneMatch(x->x.evidence().contains("TOKEN_SECRET")),"cookie values never retained");
 for(String bad:new String[]{"//evil.test","/../admin","/a/./b","/a?x=1","/a#b","/a\r\nb","/%2e%2e","/a\\b"}){boolean rejected=false;try{EnvironmentCatalog.prefix(bad);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"prefix validation "+bad.replaceAll("[\\r\\n]",""));}
 var unknown=c.observe("https://unknown.test/","","ordinary text");check(c.candidates(unknown,"",false,100).isEmpty(),"unknown stack does not fabricate candidates");var spring=c.observe("https://spring.test/","Content-Type: application/vnd.spring-boot.actuator.v3+json","");check(c.candidates(spring,"").stream().anyMatch(x->x.url().endsWith("/actuator/health")),"Actuator candidates");for(int i=0;i<70;i++)c.observe("http://host"+i+".test/","","");check(c.sites.size()==50,"origin memory bounded");
 var rich=c.observe("https://rich.test/app/","Server: nginx\nContent-Type: application/vnd.spring-boot.actuator.v3+json","<script src='/app/wp-content/plugins/woocommerce/main.js'></script> fetch('/app/api/orders'); //# sourceMappingURL=/app/app.js.map");
 var expanded=c.candidates(rich,"",true,300);
 check(expanded.size()>=80,"multiple environment candidates >=80: "+expanded.size());
 check(expanded.stream().anyMatch(x->x.url().equals("https://rich.test/app/actuator/env")),"resource-derived application prefix");
 check(expanded.stream().anyMatch(x->x.url().equals("https://rich.test/app/api/orders")),"JS quoted endpoint extraction");
 check(expanded.stream().anyMatch(x->x.url().equals("https://rich.test/app/app.js.map")),"explicit sourceMappingURL extraction");
 check(expanded.stream().map(Candidate->Candidate.url()).distinct().count()==expanded.size(),"cross-profile candidates deduplicated");
 boolean sorted=true;for(int i=1;i<expanded.size();i++)sorted&=expanded.get(i-1).score()>=expanded.get(i).score();check(sorted,"evidence priority descending");
 check(c.candidates(rich,"",true,30).size()==30,"top 30 limit");
 check(c.candidates(rich,"",false,300).stream().noneMatch(x->x.source().equals("공통 탐색")),"common candidates can be excluded");
 check(c.candidates(unknown,"",true,100).size()==18,"unknown stack common fallback distinguished");
 check(expanded.stream().filter(x->x.source().equals("공통 탐색")).allMatch(x->x.score()<60),"unsubstantiated candidates ranked low");
 System.out.println("EnvironmentTest: "+checks+" checks passed");}
}
