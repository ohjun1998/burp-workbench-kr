package kr.workbench;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Credential-free per-route evidence. Discovery and response observation are separate from target coverage. */
final class ScopeInspectionCoverage {
  record Row(String key, String url, String method, String account, String profileRevision,
             String source, String label, String state, String reason, int status,
             String originalId, boolean retryable, List<String> stages, boolean queryUnresolved) {
    Row(String key,String url,String method,String account,String profileRevision,String source,String label,String state,String reason,
        int status,String originalId,boolean retryable,List<String> stages){
      this(key,url,method,account,profileRevision,source,label,state,reason,status,originalId,retryable,stages,false);
    }
    Row { stages = List.copyOf(stages); }
  }
  record Summary(int discovered, int requested, int responded, int analyzed, int unconfirmed, int retryable) {}
  record Correlation(String templateKey,String template,String templateMethod,String templateAccount,String templateRevision,String templateOriginalId,
                     String observedKey,String observedUrl,String observedMethod,String observedAccount,String observedRevision,int status,
                     String observedOriginalId,String observedState,String note) {}
  static final int MAX_CORRELATIONS=300,MAX_CORRELATION_TEMPLATES=100,MAX_CORRELATION_RESPONSES=500,MAX_CORRELATION_COMPARISONS=20_000;
  private record Template(Row row,Pattern path,String origin,String originalId) {}
  private ScopeInspectionCoverage() {}

  static List<Row> rows(ScopeInspection.Snapshot snapshot) {
    if (!snapshot.coverage().isEmpty()) return snapshot.coverage();
    // Historical/previews without execution traces remain read-only. Never manufacture request evidence.
    List<Row> rows = new ArrayList<>();
    for (var candidate : snapshot.candidates()) {
      List<ScopeInspection.Page> pages = snapshot.pages().stream().filter(p -> p.url().equals(candidate.url())
          && p.method().equals(candidate.method()) && !p.classification().endsWith("CONTROL")).toList();
      if (pages.isEmpty()) rows.add(new Row(key("legacy|"+candidate.method()+"|"+candidate.url()), candidate.url(), candidate.method(),
          "미확인", "", candidate.source(), candidate.label(), "UNVISITED", "발견 문자열만 보관 · 실제 전송 근거 없음", 0, "", false, List.of("DISCOVERED")));
      else for (var page : pages) rows.add(new Row(key("legacy|"+page.account()+"|"+page.profileRevision()+"|"+page.method()+"|"+page.url()),
          page.url(), page.method(), page.account(), page.profileRevision(), candidate.source(), candidate.label(),
          page.complete()?"ANALYZED":"PARTIAL", page.note(), page.status(), page.original()==null?"":page.original().id(), false,
          List.of("DISCOVERED","REQUESTED","RESPONDED","ANALYZED")));
    }
    return List.copyOf(rows);
  }

  static Summary summary(ScopeInspection.Snapshot snapshot) {
    var rows = rows(snapshot);
    return new Summary(rows.size(), (int)rows.stream().filter(r -> r.stages().contains("REQUESTED")).count(),
        (int)rows.stream().filter(r -> r.stages().contains("RESPONDED")).count(),
        (int)rows.stream().filter(r -> r.state().equals("ANALYZED")).count(),
        (int)rows.stream().filter(r -> !r.state().equals("ANALYZED")).count(),
        (int)rows.stream().filter(Row::retryable).count());
  }

  /** Local evidence association only. No interpolation, inferred query values, request creation or executable promotion. */
  static List<Correlation> correlations(ScopeInspection.Snapshot snapshot){
    List<Row> rows=rows(snapshot);List<Template> templates=new ArrayList<>();
    for(Row row:rows){
      if(templates.size()>=MAX_CORRELATION_TEMPLATES)break;
      Template template=template(row,snapshot);if(template!=null)templates.add(template);
    }
    List<Row> observed=new ArrayList<>();
    for(Row row:rows){
      if(observed.size()>=MAX_CORRELATION_RESPONSES)break;
      if(row.state().equals("STALE")||row.queryUnresolved()||!row.stages().contains("SCOPE_CHECKED")||!row.stages().contains("RESPONDED")
          ||row.status()<100||row.status()>599||!Set.of("GET","HEAD").contains(row.method()))continue;
      String normalized=ScopeInspectionRunner.normalized(row.url());
      if(normalized==null||!ScopeInspectionRunner.readOnlyUrl(normalized))continue;
      URI uri=URI.create(normalized);if(uri.getRawQuery()!=null||uri.getRawFragment()!=null)continue;
      // Wire originals retain exact query presence even where the display URL is sanitized.
      boolean wireQuery=snapshot.pages().stream().filter(p->p.original()!=null&&p.original().id().equals(row.originalId())).anyMatch(p->{
        try{URI wire=URI.create(p.original().url());return wire.getRawQuery()!=null||wire.getRawFragment()!=null;}catch(RuntimeException invalid){return true;}
      });
      if(!wireQuery)observed.add(row);
    }
    List<Correlation> result=new ArrayList<>();int comparisons=0;
    for(Template template:templates){int matches=0;
      for(Row actual:observed){
        if(++comparisons>MAX_CORRELATION_COMPARISONS||result.size()>=MAX_CORRELATIONS)return List.copyOf(result);
        if(!template.row().method().equals(actual.method()))continue;
        URI uri=URI.create(actual.url());
        if(!template.origin().equals(EnvironmentCatalog.origin(uri))||!template.path().matcher(uri.getRawPath()).matches())continue;
        result.add(new Correlation(template.row().key(),template.row().url(),template.row().method(),template.row().account(),template.row().profileRevision(),template.originalId(),
            actual.key(),actual.url(),actual.method(),actual.account(),actual.profileRevision(),actual.status(),actual.originalId(),actual.state(),
            "PATH_SHAPE_ONLY · 정적 경로 형태 일치 후보 · 변수값/호스트 식을 평가·복원하지 않음 · 쿼리/fragment 포함 항목 제외 · 동일 계정·인증·권한·메뉴 인과·변수 해소를 입증하지 않음 · 실행가능성 변경·추가 요청 없음 · 입력/대조/결과 한도 내 후보이며 미표시는 부재 판정 아님"));
        if(++matches>=10)break;
      }
    }
    return List.copyOf(result);
  }

  private static Template template(Row row,ScopeInspection.Snapshot snapshot){
    if(row.state().equals("STALE")||row.queryUnresolved()||!Set.of("GET","HEAD").contains(row.method()))return null;
    String raw=row.url();if(raw==null||raw.length()>2048||raw.indexOf('?')>=0||raw.indexOf('#')>=0||raw.indexOf('\\')>=0||raw.chars().anyMatch(c->c<=32))return null;
    if(!raw.contains("${")&&!raw.matches(".*\\{[A-Za-z_][A-Za-z0-9_-]{0,63}}.*"))return null;
    var source=snapshot.findings().stream().filter(f->f.category().equals("API")&&!f.originalId().isBlank()
        &&f.method().equals(row.method())
        &&(f.account().equals(row.account())||f.account().equals("전체"))
        &&(f.fingerprint().equals(key(raw))||f.url().equals(raw)&&f.method().equals(row.method())||f.detail().contains(raw)))
        .findFirst().orElse(null);
    if(source==null)return null;
    String origin;
    String sourceUrl=snapshot.pages().stream().filter(p->p.original()!=null&&p.original().id().equals(source.originalId())).map(ScopeInspection.Page::url).findFirst().orElse(source.url());
    try{origin=EnvironmentCatalog.origin(URI.create(sourceUrl.replace("{","%7B").replace("}","%7D")));if(origin==null||origin.isBlank())return null;}catch(RuntimeException invalid){return null;}
    String path=raw;
    var host=Pattern.compile("^\\$\\{[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*}").matcher(path);
    boolean hostPlaceholder=host.find();
    if(hostPlaceholder)path=path.substring(host.end());
    else if(path.startsWith("http://")||path.startsWith("https://")){
      try{URI uri=URI.create(path.replace("{","%7B").replace("}","%7D"));if(!origin.equals(EnvironmentCatalog.origin(uri)))return null;path=uri.getRawPath().replace("%7B","{").replace("%7D","}");}catch(RuntimeException invalid){return null;}
    }
    if(!path.startsWith("/")||path.startsWith("//")||path.contains("//")||path.matches("(?i).*(?:%2e|%2f|%5c|%25|%00|;).*"))return null;
    String[] segments=path.split("/",-1);int dynamic=0,literal=0;boolean apiAnchor=false;StringBuilder regex=new StringBuilder("^");
    for(int i=1;i<segments.length;i++){
      String segment=segments[i];regex.append('/');
      if(segment.matches("\\$\\{[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*}|\\{[A-Za-z_][A-Za-z0-9_-]{0,63}}")){
        if(++dynamic>3)return null;regex.append("[^/?#]{1,256}");
      }else{
        if(segment.isEmpty()&&i!=segments.length-1||segment.equals(".")||segment.equals("..")||segment.contains("{")||segment.contains("}")||segment.contains("$")||segment.contains("*"))return null;
        if(!segment.isEmpty()){literal++;if(segment.equalsIgnoreCase("api")||segment.equalsIgnoreCase("rest")||segment.equalsIgnoreCase("graphql"))apiAnchor=true;}
        regex.append(Pattern.quote(segment));
      }
    }
    // Require a literal API namespace and bounded single-segment placeholders.
    // A broad /${anything}, all-dynamic path or arbitrary catch-all is excluded.
    if(dynamic==0&&!(hostPlaceholder&&literal>=2)||literal<1||!apiAnchor||dynamic>1&&literal<2)return null;
    regex.append('$');return new Template(row,Pattern.compile(regex.toString()),origin,source.originalId());
  }

  static String stateLabel(String state) {
    return switch (state) {
      case "DISCOVERED" -> "발견"; case "QUEUED" -> "대기"; case "REQUESTING" -> "요청 중";
      case "HTTP_RESPONDED" -> "브라우저 응답 관측·본문 미분석"; case "ANALYZED" -> "분석 완료";
      case "PARTIAL" -> "부분 분석"; case "DENIED" -> "접근 거부·권한 미확인";
      case "AUTH_REQUIRED" -> "인증·세션 확인 필요"; case "FAILED" -> "전송·분석 실패";
      case "EXCLUDED" -> "정책 제외"; case "STALE" -> "인증 조건 변경";
      default -> "미방문";
    };
  }
  static String stageLabel(String stage) {
    return switch (stage) {
      case "SCOPE_CHECKED" -> "Scope 확인"; case "QUEUED" -> "큐 등록";
      case "REQUESTED" -> "요청 전송"; case "RESPONDED" -> "응답 수신";
      case "ANALYZED" -> "응답 분석"; default -> "발견";
    };
  }
  static String key(String text) { return EnvironmentProject.hash(text); }
}
