package kr.workbench;
import java.net.URI;
import java.util.*;
import java.util.regex.*;
/** Passive, bounded fingerprinting. Candidates are never sent by this class. */
public final class EnvironmentCatalog {
 public record Detection(String category,String name,String confidence,String evidence){}
 public record Candidate(String url,String source,String purpose,int score,String condition){}
 public static final class Site {public final String origin; public final Map<String,Detection> detections=new LinkedHashMap<>(); public final Set<String> observed=new LinkedHashSet<>(); public final Set<String> prefixes=new LinkedHashSet<>(); public final Map<String,Candidate> discovered=new LinkedHashMap<>(); public final Map<String,String> associations=new LinkedHashMap<>(); public final Map<String,Integer> feedback=new LinkedHashMap<>(); public final Set<String> managementRoots=new LinkedHashSet<>();Site(String o){origin=o;}}
 public final Map<String,Site> sites=new LinkedHashMap<>();
 private record Rule(String category,String name,String pattern,String[] paths){}
 private static Rule r(String c,String n,String p,String... paths){return new Rule(c,n,p,paths);}
 private static final List<Rule> RULES=List.of(
 r("서버","nginx","(?im)^server:\\s*nginx","/nginx_status"),r("서버","Apache HTTPD","(?im)^server:\\s*apache","/server-status","/server-info"),
 r("서버","IIS","(?im)^server:.*microsoft-iis","/"),r("WAS","Tomcat","(?i)(apache[ -]tomcat|server:.*tomcat)","/manager/html","/host-manager/html","/docs/"),
 r("WAS","Jetty","(?im)^server:.*jetty","/"),r("WAS","WildFly / JBoss","(?i)(wildfly|jboss)","/console","/management"),r("WAS","WebLogic","(?i)weblogic","/console"),
 r("프레임워크","ASP.NET","(?i)(x-powered-by:.*asp.net|set-cookie:\\s*asp.net_sessionid=)","/trace.axd","/elmah.axd"),
 r("프레임워크","Spring Boot / Actuator","(?i)(application/vnd.spring-boot.actuator|/actuator(?:/|\"|'))","/actuator","/actuator/health","/actuator/info","/actuator/env","/actuator/configprops","/actuator/mappings"),
 r("프레임워크","Django","(?i)(csrfmiddlewaretoken|__admin_media_prefix__)","/admin/"),r("프레임워크","Flask / Werkzeug","(?im)^server:.*werkzeug","/robots.txt"),
 r("프레임워크","Express","(?im)^x-powered-by:\\s*express","/robots.txt"),r("프레임워크","PHP","(?im)^x-powered-by:\\s*php","/robots.txt"),
 r("프레임워크","Laravel","(?i)(set-cookie:\\s*laravel_session=|laravel framework)","/storage/"),
 r("프레임워크","Next.js","(?i)(__next_data__|/_next/static/)","/robots.txt","/sitemap.xml"),r("프레임워크","Nuxt","(?i)(__nuxt__|/_nuxt/)","/robots.txt"),
 r("프레임워크","Angular","(?i)(ng-version=|<app-root)","/robots.txt"),
 r("CMS","WordPress","(?i)(/wp-content/|/wp-includes/|api.w.org)","/wp-login.php","/wp-admin/","/wp-json/"),
 r("CMS","Drupal","(?i)(drupalsettings|x-generator:.*drupal)","/user/login","/core/CHANGELOG.txt"),r("CMS","Joomla","(?i)(content=[\"']joomla|/media/system/js/)","/administrator/"),
 r("플러그인","WooCommerce","(?i)/wp-content/plugins/woocommerce/","/wp-json/wc/v3/"),r("플러그인","Laravel Telescope","(?i)(/telescope/|laravel telescope)","/telescope"),
 r("API 도구","Swagger / OpenAPI","(?i)(swagger-ui|swaggeruibundle)","/swagger-ui/","/swagger-ui/index.html","/v3/api-docs","/swagger.json","/openapi.json"),
 r("API 도구","GraphQL","(?i)(graphql playground|graphiql|[\"']/graphql[\"'])","/graphql"),
 r("서비스","Jenkins","(?im)^x-jenkins:","/login","/api/json"),r("서비스","Grafana","(?i)(grafanabootdata|<title>grafana)","/login","/api/health"),r("프레임워크","FastAPI","(?i)(<title>[^<]*fastapi|fastapi - swagger ui)","/docs","/redoc","/openapi.json"),
 r("프레임워크","Symfony","(?im)^x-debug-token(?:-link)?:","/_profiler/"),r("프레임워크","Ruby on Rails","(?i)(set-cookie:\\s*_rails|rails-default-error-page|rails-logo)","/up"),
 r("CMS","Adobe Experience Manager","(?i)(/etc.clientlibs/|/libs/granite/)","/system/console/"),
 r("서비스","Kibana","(?im)^kbn-name:","/app/home","/api/status"));
 private static final Map<String,String[]> EXPANDED=new LinkedHashMap<>();
 static {
  EXPANDED.put("nginx",new String[]{"/basic_status","/stub_status","/status"});
  EXPANDED.put("Apache HTTPD",new String[]{"/server-status/","/server-info/","/icons/","/manual/"});
  EXPANDED.put("IIS",new String[]{"/iisstart.htm","/iisstart.png","/aspnet_client/"});
  EXPANDED.put("Tomcat",new String[]{"/manager/status","/manager/status/all","/manager/text/list","/host-manager/text/list","/examples/","/examples/servlets/","/examples/jsp/"});
  EXPANDED.put("WildFly / JBoss",new String[]{"/jmx-console/","/web-console/","/invoker/","/jbossws/"});
  EXPANDED.put("WebLogic",new String[]{"/console/login/LoginForm.jsp","/wls-wsat/","/bea_wls_internal/","/uddiexplorer/"});
  EXPANDED.put("ASP.NET",new String[]{"/Trace.axd","/elmah","/swagger/","/swagger/index.html","/swagger/v1/swagger.json"});
  EXPANDED.put("Spring Boot / Actuator",new String[]{"/actuator/beans","/actuator/conditions","/actuator/metrics","/actuator/prometheus","/actuator/loggers","/actuator/logfile","/actuator/threaddump","/actuator/heapdump","/actuator/httpexchanges","/actuator/httptrace","/actuator/scheduledtasks","/actuator/caches","/actuator/auditevents","/actuator/sessions","/actuator/flyway","/actuator/liquibase"});
  EXPANDED.put("Django",new String[]{"/admin/login/","/admin/password_reset/","/api/","/api/schema/","/api/schema/swagger-ui/","/api/schema/redoc/"});
  EXPANDED.put("Flask / Werkzeug",new String[]{"/swagger/","/apidocs/","/openapi.json","/health"});
  EXPANDED.put("Express",new String[]{"/api/","/api-docs/","/swagger.json","/openapi.json","/health","/healthz","/status"});
  EXPANDED.put("PHP",new String[]{"/phpinfo.php","/info.php","/phpmyadmin/"});
  EXPANDED.put("Laravel",new String[]{"/login","/register","/api/","/sanctum/csrf-cookie","/storage/logs/laravel.log"});
  EXPANDED.put("Next.js",new String[]{"/api/","/_next/static/","/_next/image","/manifest.json"});
  EXPANDED.put("Nuxt",new String[]{"/api/","/api/_content/","/manifest.json"});
  EXPANDED.put("WordPress",new String[]{"/wp-json/wp/v2/users","/wp-json/wp/v2/types","/wp-json/wp/v2/posts","/wp-json/wp/v2/pages","/wp-json/wp/v2/media","/wp-json/wp/v2/settings","/wp-json/wp/v2/plugins","/wp-admin/admin-ajax.php","/wp-admin/admin-post.php","/xmlrpc.php","/readme.html","/license.txt","/wp-content/uploads/","/wp-content/debug.log"});
  EXPANDED.put("Drupal",new String[]{"/user/register","/user/password","/admin/","/jsonapi/","/sites/default/files/","/CHANGELOG.txt"});
  EXPANDED.put("Joomla",new String[]{"/administrator/index.php","/api/index.php/v1","/configuration.php","/README.txt"});
  EXPANDED.put("WooCommerce",new String[]{"/wp-json/wc/v3/products","/wp-json/wc/v3/orders","/wp-json/wc/v3/customers","/wp-json/wc/v3/settings","/wp-json/wc/store/v1/products","/wp-json/wc/store/v1/cart"});
  EXPANDED.put("Laravel Telescope",new String[]{"/telescope/requests","/telescope/exceptions","/telescope/queries","/telescope/logs"});
  EXPANDED.put("Swagger / OpenAPI",new String[]{"/swagger-ui.html","/swagger/","/swagger/index.html","/api-docs","/api-docs/","/v2/api-docs","/v3/api-docs/swagger-config","/swagger/v1/swagger.json","/openapi.yaml","/redoc/"});
  EXPANDED.put("GraphQL",new String[]{"/graphiql","/playground","/graphql/schema.json"});
  EXPANDED.put("Jenkins",new String[]{"/manage","/whoAmI/api/json","/computer/api/json","/view/all/api/json","/script","/credentials/","/user/"});
  EXPANDED.put("Grafana",new String[]{"/api/frontend/settings","/api/org","/api/user","/api/search","/api/datasources","/api/admin/settings","/public/build/"});
  EXPANDED.put("Kibana",new String[]{"/login","/api/features","/api/saved_objects/_find","/app/dev_tools","/app/management"});
 }
 private static final String[] COMMON={"/robots.txt","/sitemap.xml","/.well-known/security.txt","/login","/admin/","/api/","/health","/healthz","/status","/metrics","/swagger-ui/","/swagger-ui.html","/swagger/","/api-docs","/v3/api-docs","/openapi.json","/swagger.json","/redoc/"};
 public Site observe(String url,String headers,String body){
  URI u=URI.create(url);String origin=origin(u);Site s=sites.get(origin);if(s==null){if(sites.size()>=50)sites.remove(sites.keySet().iterator().next());s=new Site(origin);sites.put(origin,s);}
  String h=headers==null?"":headers; // redact cookie values before matching or retaining evidence
  h=h.replaceAll("(?im)^(set-cookie:\\s*[^=;\\r\\n]+)=.*$","$1=");
  String b=body==null?"":body.substring(0,Math.min(65536,body.length()));String input=h+"\n"+b;
  for(Rule rule:RULES){Matcher m=Pattern.compile(rule.pattern).matcher(input);if(m.find()&&s.detections.size()<40){String e=m.group();e=e.substring(0,Math.min(140,e.length())).replaceAll("[\\r\\n]"," ");s.detections.put(rule.name,new Detection(rule.category,rule.name,m.start()<h.length()?"헤더 근거":"본문 근거 · 추정",e));}}
  Matcher plugin=Pattern.compile("/wp-content/plugins/([a-zA-Z0-9_-]{1,64})/").matcher(b);while(plugin.find()&&s.detections.size()<40){String slug=plugin.group(1);s.detections.put("plugin:"+slug,new Detection("플러그인","WordPress: "+slug,"리소스 경로 · 추정",plugin.group()));}
  Matcher paths=Pattern.compile("(?:src|href)\\s*=\\s*[\"']([^\"'<>\\s]+)[\"']",Pattern.CASE_INSENSITIVE).matcher(b);
  while(paths.find()&&s.observed.size()<100){try{URI asset=u.resolve(paths.group(1));if(origin(asset).equals(origin)&&asset.getRawPath()!=null&&asset.getRawPath().startsWith("/")&&!asset.getRawPath().contains(";"))s.observed.add(asset.getRawPath());}catch(Exception ignored){}}
  Matcher quoted=Pattern.compile("[\"']((?:/|https?://)[^\"'<>\\s]{1,512})[\"']").matcher(b);
  while(quoted.find()&&s.observed.size()<100)remember(s,u,quoted.group(1));
  Matcher maps=Pattern.compile("sourceMappingURL=([^\\s<>]{1,512})").matcher(b);while(maps.find()&&s.observed.size()<100)remember(s,u,maps.group(1));
  for(String path:s.observed){Matcher marker=Pattern.compile("^(.+?)/(?:wp-content|wp-includes|wp-json|_next|_nuxt|actuator)(?:/|$)").matcher(path);if(marker.find()&&s.prefixes.size()<8){try{s.prefixes.add(prefix(marker.group(1)));}catch(Exception ignored){}}}
  EnvironmentDiscovery.observe(s,u,h,b);
  return s;
 }
 private void remember(Site s,URI from,String reference){String path=EnvironmentDiscovery.path(from,reference,s.origin);if(path!=null)s.observed.add(path);}
 public void feedback(String url,int code,String state){try{Site s=sites.get(origin(URI.create(url)));if(s!=null){if(s.feedback.size()>=500&&!s.feedback.containsKey(url))s.feedback.remove(s.feedback.keySet().iterator().next());s.feedback.put(url,(state.contains("없는 경로와 동일")||state.contains("기준과 유사")||state.contains("로그인 화면")||state.contains("보안 확인 화면"))?-1:code);}}catch(Exception ignored){}}


 static String origin(URI u){if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException("HTTP(S) URL 필요");int p=u.getPort();return u.getScheme()+"://"+(u.getHost().startsWith("[")?u.getHost():u.getHost().contains(":")?"["+u.getHost()+"]":u.getHost().toLowerCase(Locale.ROOT))+((p<0||p==80&&u.getScheme().equals("http")||p==443&&u.getScheme().equals("https"))?"":":"+p);}
 public static String prefix(String p){if(p==null||p.isBlank()||p.equals("/"))return "";if(!p.startsWith("/")||p.startsWith("//")||p.contains("\\")||p.contains("%")||p.contains("?")||p.contains("#")||p.chars().anyMatch(ch->Character.isWhitespace(ch)||Character.isISOControl(ch))||Arrays.asList(p.split("/")).contains("..")||Arrays.asList(p.split("/")).contains("."))throw new IllegalArgumentException("앱 경로 예: /app (URL·쿼리·상위 경로 불가)");return p.replaceAll("/+$","");}
 public List<Candidate> candidates(Site s,String base){return candidates(s,base,true,100);}
 public List<Candidate> candidates(Site s,String base,boolean common,int limit){
  String p=prefix(base);Map<String,Candidate> out=new LinkedHashMap<>();
  for(Candidate extra:s.discovered.values())out.put(extra.url(),extra);
  for(String path:s.observed){put(out,s,path,"실제 응답 참조",sensitive(path)?95:90,"응답에서 참조됨 · 존재/권한 미검증");if(path.endsWith(".js"))put(out,s,path+".map","관찰 JS의 소스맵 후보",50,"소스맵 생성 여부 미확인");}
  Set<String> bases=new LinkedHashSet<>();bases.add(p);if(p.isEmpty())bases.addAll(s.prefixes);
  for(String root:bases){
   for(Rule r:RULES)if(s.detections.containsKey(r.name)){
    for(String path:r.paths)put(out,s,root+path,r.name,sensitive(path)?85:80,"환경 근거 · 설정 의존");
    for(String path:EXPANDED.getOrDefault(r.name,new String[0]))put(out,s,root+path,r.name+" 확장 후보",sensitive(path)?75:65,"버전·추가 모듈·설정 의존");
   }
   for(var entry:EnvironmentKnowledge.ENTRIES)if(s.detections.containsKey(entry.technology())){
    put(out,s,root+entry.path(),entry.technology()+" · 선별 경로",72,"참고: "+entry.reference()+" · "+entry.purpose()+" · 설정/버전 의존");
   }
   if(common)for(String path:COMMON)put(out,s,root+path,"공통 탐색",35,"환경 직접 근거 없음 · 낮은 우선순위");
  }
  for(var entry:s.detections.entrySet())if(entry.getKey().startsWith("plugin:")){String slug=entry.getKey().substring(7);for(String root:bases)for(String tail:new String[]{"/","/readme.txt","/changelog.txt"})put(out,s,root+"/wp-content/plugins/"+slug+tail,"관찰 플러그인 "+slug,70,"실제 플러그인 표식 · 파일 존재 미확인");}
  if(s.detections.containsKey("Spring Boot / Actuator"))for(String management:s.managementRoots){
   for(String tail:List.of("","/health","/info","/env","/configprops","/mappings","/beans","/metrics","/logfile"))put(out,s,management+tail,"관찰 Actuator 기본 경로",88,"응답 링크에서 관리 경로 확인 · "+EnvironmentKnowledge.SPRING);
  }
  for(var e:new ArrayList<>(out.entrySet())){Candidate c=e.getValue();Integer code=s.feedback.get(c.url());if(code!=null){int delta=(code==404||code==410||code==-1)?-60:(code==401||code==403)?8:code>=200&&code<300?12:0;out.put(e.getKey(),new Candidate(c.url(),c.source(),c.purpose(),c.score()+delta,c.condition()+" · 이전 응답 "+(code==-1?"없는 경로와 동일":code)));}}
  return out.values().stream().sorted(Comparator.comparingInt(Candidate::score).reversed().thenComparing(Candidate::url)).limit(Math.max(1,Math.min(300,limit))).toList();
 }
 static boolean sensitive(String path){return Pattern.compile("(?i)(admin|manager|console|env|config|log|dump|users|settings|credentials|orders|customers|graphql|api-docs|swagger|openapi)").matcher(path).find();}
 static String purpose(String path){if(path.matches("(?i).*(?:/\\.env|/\\.git/|composer\\.(?:json|lock)|package(?:-lock)?\\.json|WEB-INF|META-INF).*"))return "배포 파일·설정 정보 노출 확인";if(path.contains("heapdump"))return "메모리 덤프 노출·권한 (대용량 응답 가능)";if(path.endsWith(".map"))return "소스맵·원본 코드 노출";if(path.matches("(?i).*(swagger|api-docs|openapi|redoc).*"))return "API 문서·인증 요구 확인";if(path.matches("(?i).*(admin|manager|console|credentials).*"))return "관리 기능 접근 제어 확인";if(path.matches("(?i).*(env|config|log|dump|settings).*"))return "설정·내부 정보 노출 확인";if(path.contains("/wp-json")||path.contains("/api/"))return "API 인증·권한·민감 데이터 확인";return "존재·접근 제어·응답 정보 확인";}
 static void put(Map<String,Candidate> out,Site s,String path,String source,int score,String condition){if(source.contains("Spring Boot")&&path.matches(".*/actuator/(?:env|configprops|heapdump|mappings)$"))condition+=" · 공개 연구 참고: "+EnvironmentKnowledge.ACTUATOR_CASE;
 Candidate n=new Candidate(s.origin+path,source,purpose(path),score,condition);Candidate old=out.get(n.url());if(old==null)out.put(n.url(),n);else {Candidate best=old.score()<score?n:old;String extra=old.score()<score?old.condition():condition;out.put(n.url(),new Candidate(best.url(),best.source(),best.purpose(),best.score(),best.condition().contains(extra)?best.condition():best.condition()+" · "+extra));}}
}
