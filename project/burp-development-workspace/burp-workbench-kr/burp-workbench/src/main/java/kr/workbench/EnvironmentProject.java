package kr.workbench;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit metadata-only project files. Credentials, packets and body values never enter this model. */
final class EnvironmentProject {
  static final int MAX_ENTRIES=2000,MAX_CANDIDATES=1000,MAX_BYTES=4*1024*1024;
  record Pending(String url,int depth){Pending{url=validUrl(url);if(depth<0||depth>3)throw new IllegalArgumentException("탐색 깊이 오류");}}
  record Entry(String url,String account,int status,String kind,String fingerprint,long time,String change){
    Entry{url=validUrl(url);if(!account.matches("anonymous|slot-[0-2](?:-[a-f0-9]{16})?")||status<0||status>599||!kind.matches("[A-Z_0-9]{1,32}")||!fingerprint.matches("[a-f0-9]{64}|^$")||time<0||!Set.of("신규","동일","내용 변경","접근 변경","분류 변경").contains(change))throw new IllegalArgumentException("저장 결과 형식 오류");}
    String key(){return account+" GET "+url;}
  }
  record Candidate(String url,String source){Candidate{url=validUrl(url);if(source==null||source.length()>160||source.chars().anyMatch(c->c<32))throw new IllegalArgumentException("후보 출처 형식 오류");}}
  final List<Entry> history=new ArrayList<>();
  final Map<String,Entry> latest=new LinkedHashMap<>();
  final Map<String,Candidate> candidates=new LinkedHashMap<>();
  List<Pending> pending=List.of();
  String pendingAccount="anonymous";
  String add(String url,String account,int status,String kind,String fingerprint){
    Entry old=latest.get(account+" GET "+url);String change="신규";
    if(old!=null)change=old.status()!=status?"접근 변경":!old.kind().equals(kind)?"분류 변경":!old.fingerprint().equals(fingerprint)?"내용 변경":"동일";
    Entry entry=new Entry(url,account,status,kind,fingerprint,System.currentTimeMillis(),change);
    accept(entry);return change;
  }
  void accept(Entry entry){history.add(entry);if(history.size()>MAX_ENTRIES)history.remove(0);latest.put(entry.key(),entry);while(latest.size()>MAX_ENTRIES)latest.remove(latest.keySet().iterator().next());}
  void candidate(String url,String source){if(candidates.size()<MAX_CANDIDATES||candidates.containsKey(url))candidates.put(url,new Candidate(url,source));}
  String json(){
    Map<String,Object> m=new LinkedHashMap<>();m.put("schema",1);
    m.put("results",history.stream().map(e->List.of(e.url,e.account,e.status,e.kind,e.fingerprint,e.time,e.change)).toList());
    m.put("candidates",candidates.values().stream().map(c->List.of(c.url,c.source)).toList());
    m.put("pending",pending.stream().map(p->List.of(p.url,p.depth)).toList());m.put("pendingAccount",pendingAccount);
    String json=MetadataExport.json(m);if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("프로젝트 4 MiB 한도 초과");return json;
  }
  static EnvironmentProject parse(String json){
    Object parsed=MinerJson.parse(json,MAX_BYTES,60000);if(!(parsed instanceof Map<?,?> m)||!m.keySet().equals(Set.of("schema","results","candidates","pending","pendingAccount"))||number(m.get("schema"))!=1)throw new IllegalArgumentException("지원하지 않는 탐색 프로젝트");
    EnvironmentProject p=new EnvironmentProject();
    for(Object value:list(m.get("results"),MAX_ENTRIES)){List<?> e=list(value,7);if(e.size()!=7)throw new IllegalArgumentException("결과 항목 오류");p.accept(new Entry(string(e.get(0)),string(e.get(1)),integer(e.get(2)),string(e.get(3)),string(e.get(4)),number(e.get(5)),string(e.get(6))));}
    for(Object value:list(m.get("candidates"),MAX_CANDIDATES)){List<?> c=list(value,2);if(c.size()!=2)throw new IllegalArgumentException("후보 항목 오류");p.candidate(string(c.get(0)),string(c.get(1)));}
    List<Pending> pending=new ArrayList<>();for(Object value:list(m.get("pending"),500)){List<?> t=list(value,2);if(t.size()!=2)throw new IllegalArgumentException("대기 항목 오류");pending.add(new Pending(string(t.get(0)),integer(t.get(1))));}
    p.pending=List.copyOf(pending);p.pendingAccount=string(m.get("pendingAccount"));if(!p.pendingAccount.matches("anonymous|slot-[0-2](?:-[a-f0-9]{16})?"))throw new IllegalArgumentException("대기 계정 형식 오류");return p;
  }
  static List<?> list(Object value,int max){if(!(value instanceof List<?> l)||l.size()>max)throw new IllegalArgumentException("프로젝트 항목 한도/형식 오류");return l;}
  static String string(Object value){if(!(value instanceof String s))throw new IllegalArgumentException("프로젝트 문자열 오류");return s;}
  static int integer(Object value){long n=number(value);if(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw new IllegalArgumentException("프로젝트 숫자 범위 오류");return (int)n;}
  static long number(Object value){try{if(!(value instanceof Number))throw new IllegalArgumentException();return new java.math.BigDecimal(value.toString()).longValueExact();}catch(Exception e){throw new IllegalArgumentException("프로젝트 숫자 오류");}}
  static String validUrl(String url){
    try{URI u=URI.create(url);String origin=EnvironmentCatalog.origin(u);if(url.length()>2048||u.getRawQuery()!=null||u.getRawFragment()!=null||u.getRawPath()==null||Arrays.asList(u.getRawPath().split("/")).contains("..")||Arrays.asList(u.getRawPath().split("/")).contains(".")||!url.equals(origin+u.getRawPath())||EnvironmentDiscovery.path(URI.create(origin+"/"),u.getRawPath(),origin)==null)throw new IllegalArgumentException();return url;}catch(Exception e){throw new IllegalArgumentException("프로젝트 URL은 쿼리·인증정보 없는 HTTP(S) 경로여야 합니다.");}
  }
  static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  void write(Path destination)throws Exception{
    byte[] bytes=json().getBytes(StandardCharsets.UTF_8);Path parent=destination.toAbsolutePath().getParent();Path temp=Files.createTempFile(parent,".wb-environment-",".tmp");
    try{Files.write(temp,bytes);Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
  }
  static EnvironmentProject read(Path source)throws Exception{if(Files.size(source)>MAX_BYTES)throw new IllegalArgumentException("프로젝트는 4 MiB 이하이어야 합니다.");return parse(Files.readString(source,StandardCharsets.UTF_8));}
}
