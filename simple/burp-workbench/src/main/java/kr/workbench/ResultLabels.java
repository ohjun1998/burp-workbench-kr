package kr.workbench;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.table.TableModel;

/** Presentation-only route names. Original values stay in their owning table/store. */
final class ResultLabels {
  private ResultLabels() {}
  private static final Map<String,String> GROUPS=Map.of(
      "business","핵심 업무", "account","계정·인증", "docs","API·문서 탐색",
      "assets","정적 리소스", "ops","환경·운영 탐색", "pages","화면·안내",
      "inputs","입력 변형 관측", "control","대조 경로 관측", "other","기타 관측");
  private static final Map<String,String> NAMES=Map.ofEntries(
      Map.entry("dashboard","대시보드"),Map.entry("check_balance","잔액 조회"),
      Map.entry("balance","잔액 조회"),Map.entry("account_balance","잔액 조회"),
      Map.entry("login","로그인"),Map.entry("signin","로그인"),Map.entry("logout","로그아웃"),
      Map.entry("signout","로그아웃"),Map.entry("register","가입 화면"),Map.entry("signup","가입 화면"),
      Map.entry("admin","관리자 경로 관측"),Map.entry("healthz","연결 확인"),Map.entry("health","연결 확인"),
      Map.entry(".env","환경 설정 파일 관측"),Map.entry("metrics","지표 경로 관측"),Map.entry("status","상태 경로 관측"),
      Map.entry("careers","채용 안내"),Map.entry("blog","블로그"),Map.entry("privacy","개인정보 안내"),
      Map.entry("terms","이용약관"),Map.entry("compliance","준법 안내"),Map.entry("profile","프로필"),
      Map.entry("users","회원 조회"),Map.entry("user","회원 조회"),Map.entry("accounts","계정 조회"),
      Map.entry("orders","주문 조회"),Map.entry("payments","결제 경로"),Map.entry("transfer","이체 경로"),
      Map.entry("posts","게시글 조회"),Map.entry("files","파일 경로"),Map.entry("products","상품 조회"),
      Map.entry("robots.txt","검색 로봇 규칙 경로"),Map.entry("sitemap.xml","사이트맵 경로"),
      Map.entry("security.txt","보안 연락처 경로"),Map.entry("openapi.json","OpenAPI 명세 경로"),
      Map.entry("swagger.json","Swagger 명세 경로"),Map.entry("api-docs","API 문서 경로"),
      Map.entry("docs","API 문서 경로"),Map.entry("apidocs","API 문서 경로"),
      Map.entry("swagger","Swagger 문서 경로"),Map.entry("swagger-ui.html","Swagger 문서 경로"),
      Map.entry("redoc","ReDoc 문서 경로"),Map.entry("oauth2-redirect.html","API 인증 연결 화면"));

  static GroupedResults.Item pathItem(String rowId,String url) {
    String key=groupKey(url);
    return new GroupedResults.Item(rowId,key,groupLabel(key),canonical(url),shortName(url),url);
  }

  static GroupedResults.Item rowItem(TableModel model,int row,int urlColumn) {
    if(row<0||row>=model.getRowCount())return null;
    StringBuilder identity=new StringBuilder();
    for(int column=0;column<model.getColumnCount();column++)identity.append('\u001f').append(model.getValueAt(row,column));
    return pathItem(identity.toString(),String.valueOf(model.getValueAt(row,urlColumn)));
  }

  static String groupLabel(String key) {return GROUPS.getOrDefault(key,"기타 관측");}

  private static String path(String value) {
    if(value==null||value.isBlank())return "";
    String candidate=value.strip().replaceFirst("^(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+","");
    try {URI uri=URI.create(candidate);String path=uri.getRawPath();return path==null&&uri.isOpaque()?candidate:path==null||path.isBlank()?"/":path;}
    catch(IllegalArgumentException ignored){int query=candidate.indexOf('?');return query<0?candidate:candidate.substring(0,query);}
  }

  private static String decoded(String value) {
    try{return URLDecoder.decode(path(value).replace("+","%2B"),StandardCharsets.UTF_8);}
    catch(IllegalArgumentException ignored){return path(value);}
  }

  private static Set<String> parts(String value) {
    return Set.copyOf(java.util.List.of(decoded(value).toLowerCase(Locale.ROOT).split("/")));
  }

  private static boolean has(Set<String> parts,String... names) {
    for(String name:names)if(parts.contains(name))return true;return false;
  }

  static String groupKey(String url) {
    if(url!=null&&(url.startsWith("inline:")||url.startsWith("webpack:")||url.startsWith("data:")||url.startsWith("blob:")))return "assets";
    String path=decoded(url).toLowerCase(Locale.ROOT);Set<String> parts=parts(url);
    if(parts.stream().anyMatch(p->p.startsWith("wb-missing-")||p.startsWith("wb-bank-missing-")))return "control";
    if(path.matches("(?s).*(['\"]\\s*(?:and\\b|or\\b|union\\b|--)|(?:^|/)['\"](?:/|$)|<\\s*(?:script|svg|img)\\b).*"))return "inputs";
    if(has(parts,"docs","apidocs","api-docs","swagger","swagger-ui.html","redoc","openapi.json","swagger.json"))return "docs";
    if(has(parts,"static","assets")||path.matches(".*\\.(?:js|mjs|css|svg|png|jpg|jpeg|ico|woff2?|ttf)(?:$|/).*"))return "assets";
    if(has(parts,".env",".git",".well-known","health","healthz","metrics","status","robots.txt","sitemap.xml","actuator","config")||path.matches("/api/?"))return "ops";
    if(has(parts,"auth","login","logout","signin","signout","register","signup","profile","users","user","session"))return "account";
    if(has(parts,"dashboard","balance","check_balance","account_balance","admin","orders","payments","transfer","transfers","posts","files","products","graphql","ai"))return "business";
    if(path.equals("/")||has(parts,"careers","blog","privacy","terms","compliance","about","contact","help","faq"))return "pages";
    return "other";
  }

  static String shortName(String url) {
    if(url==null||url.isBlank()||url.equals("null"))return "관측 항목";
    if(url.startsWith("inline:"))return "인라인 소스맵";
    if(url.startsWith("webpack:"))return "복원 소스";
    if(url.startsWith("data:"))return "내장 리소스";
    if(url.startsWith("blob:"))return "브라우저 Blob 리소스";
    if(url.startsWith("mailto:"))return "메일 연락처";
    try {URI uri=URI.create(url);if(uri.isOpaque())return uri.getScheme()+" 참조";}
    catch(IllegalArgumentException ignored){}
    if(!url.contains("/")&&!url.contains(":")&&!url.contains("?"))return url;
    String path=decoded(url),key=groupKey(url);Set<String> parts=parts(url);
    if(key.equals("control"))return "대조 경로";
    if(key.equals("inputs"))return has(parts,"check_balance","balance")?"잔액 입력 변형":"입력 변형";
    if(path.equals("/"))return "홈 화면";
    if(parts.contains("merchant")&&has(parts,"login","signin"))return "가맹점 로그인";
    if(parts.contains("merchant")&&has(parts,"register","signup"))return "가맹점 가입 화면";
    if(parts.contains("ai"))return "AI 경로 관측";
    String[] segments=path.split("/");
    for(int index=segments.length-1;index>=0;index--){
      String segment=segments[index].toLowerCase(Locale.ROOT);
      if(NAMES.containsKey(segment))return NAMES.get(segment);
    }
    if(path.matches("/api/?"))return "API 진입점 관측";
    for(int index=segments.length-1;index>=0;index--){
      String segment=segments[index];
      if(segment.isBlank()||segment.matches("[0-9a-fA-F-]{16,}|[0-9]+|\\{[^}]*}|:[a-zA-Z_]+"))continue;
      String name=segment.replace('_',' ').replace('-',' ').replaceAll("[\\p{Cntrl}<>]","");
      if(name.length()>28)name=name.substring(0,28)+"…";
      return name+(key.equals("assets")?" · 리소스":" · 경로 관측");
    }
    return "경로 관측";
  }

  private static String canonical(String url) {
    return url==null?"":url;
  }
}
