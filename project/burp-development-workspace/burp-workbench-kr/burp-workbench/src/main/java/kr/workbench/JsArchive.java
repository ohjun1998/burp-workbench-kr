package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Project-isolated disk history. Draft values are rebuilt in memory, never serialized. */
final class JsArchive {
  record Summary(String key,int apis,int secrets,String status,String fingerprint,List<String> apiKeys,List<String> secretKeys,List<String> domains,List<String> clouds){}
  record Saved(String source,String page,String type,JsAnalysis.Result result,String[] packet){}
  final Path directory;
  private final Map<String,Summary> summaries=new LinkedHashMap<>();
  private boolean loaded;
  JsArchive(Path directory){this.directory=directory;}
  static JsArchive project(MontoyaApi api){
    try{
      if(api==null||api.persistence()==null||api.persistence().extensionData()==null)return null;
      var data=api.persistence().extensionData();String id=data.getString("js-analysis-archive-project-v1");
      if(id==null||!id.matches("[0-9a-f-]{36}")){id=UUID.randomUUID().toString();data.setString("js-analysis-archive-project-v1",id);}
      return new JsArchive(Path.of(System.getProperty("user.home"),".burp-workbench","js-analysis",id));
    }catch(Exception ex){return null;}
  }
  synchronized void load()throws IOException{
    if(loaded)return;Files.createDirectories(directory);privatePath(directory,true);
    try(var files=Files.list(directory)){for(Path path:files.filter(p->p.getFileName().toString().matches("[0-9a-f]{64}\\.meta")).sorted().toList()){
      try{if(Files.size(path)>8*1024*1024)continue;Summary summary=(Summary)decode(MinerJson.parse(Files.readString(path),8*1024*1024,200000),Summary.class);if(Files.isRegularFile(file(summary.key(),".data")))summaries.put(summary.key(),summary);}catch(RuntimeException ex){/* A broken record must not hide the remaining history. */}
    }}loaded=true;
  }
  synchronized Map<String,Summary> snapshot(){return new LinkedHashMap<>(summaries);}
  synchronized String versionKey(String source,String hash){
    for(var summary:summaries.values())if(summary.key().split("#wb-version=",2)[0].equals(source)&&summary.fingerprint().equals(hash))return summary.key();
    return summaries.containsKey(source)?source+"#wb-version="+hash.substring(0,16):source;
  }
  synchronized boolean has(String key,String hash){Summary summary=summaries.get(key);return summary!=null&&summary.fingerprint().equals(hash);}
  synchronized void save(String key,String source,String page,String type,JsAnalysis.Result result,String[] packet,String hash)throws IOException{
    load();
    Map<String,Object> saved=new LinkedHashMap<>();saved.put("source",source);saved.put("page",Objects.toString(page,""));saved.put("type",Objects.toString(type,""));saved.put("result",encode(result));saved.put("packet",packet==null?List.of():Arrays.asList(packet));
    Summary summary=new Summary(key,result.endpoints().size(),result.secrets().size(),result.status(),hash,result.endpoints().stream().map(JsFindingGroups::key).distinct().toList(),result.secrets().stream().map(JsAnalysis.Secret::fingerprint).distinct().toList(),result.miner().domains().stream().map(JsMiner.Domain::host).distinct().toList(),result.miner().clouds().stream().map(JsMiner.Cloud::url).distinct().toList());
    atomic(file(key,".data"),MetadataExport.json(saved));atomic(file(key,".meta"),MetadataExport.json(encode(summary)));summaries.put(key,summary);
  }
  synchronized Saved read(String key)throws IOException{
    if(!summaries.containsKey(key))return null;Path path=file(key,".data");
    if(Files.size(path)>160L*1024*1024)throw new IOException("분석 기록 크기 오류");
    try{
      var map=(Map<?,?>)MinerJson.parse(Files.readString(path),160*1024*1024,1000000);
      var packets=(List<?>)map.get("packet");String[] packet=packets.isEmpty()?null:packets.stream().map(Object::toString).toArray(String[]::new);
      return new Saved((String)map.get("source"),(String)map.get("page"),(String)map.get("type"),(JsAnalysis.Result)decode(map.get("result"),JsAnalysis.Result.class),packet);
    }catch(RuntimeException ex){throw new IOException("분석 기록 형식 오류",ex);}
  }
  synchronized void clear()throws IOException{
    if(!Files.exists(directory)){summaries.clear();return;}
    try(var files=Files.list(directory)){for(Path path:files.filter(p->p.getFileName().toString().matches("[0-9a-f]{64}\\.(?:data|meta|tmp)")).toList())Files.deleteIfExists(path);}
    summaries.clear();
  }
  private Path file(String key,String suffix){return directory.resolve(JsMiner.hash(key)+suffix);}
  private static void privatePath(Path path,boolean directory)throws IOException{
    try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));}catch(UnsupportedOperationException ignored){}
  }
  private static void atomic(Path path,String text)throws IOException{
    Path temp=path.resolveSibling(path.getFileName()+".tmp");
    try{Files.writeString(temp,text,StandardCharsets.UTF_8);privatePath(temp,false);try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}
  }
  // The expected class comes only from compiled record components, never from file input.
  static Object encode(Object value){
    if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;
    if(value instanceof Collection<?> list)return list.stream().map(JsArchive::encode).toList();
    if(value instanceof Map<?,?> map){Map<String,Object> out=new LinkedHashMap<>();map.forEach((k,v)->out.put(k.toString(),encode(v)));return out;}
    if(value.getClass().isRecord())try{Map<String,Object> out=new LinkedHashMap<>();for(var component:value.getClass().getRecordComponents())out.put(component.getName(),component.getName().equals("requestDrafts")?Map.of():encode(component.getAccessor().invoke(value)));return out;}catch(ReflectiveOperationException ex){throw new IllegalArgumentException(ex);}
    throw new IllegalArgumentException("Unsupported archive value");
  }
  static Object decode(Object value,Type expected){
    if(expected instanceof ParameterizedType type){
      if(type.getRawType()==List.class){List<Object> out=new ArrayList<>();for(Object item:(List<?>)value)out.add(decode(item,type.getActualTypeArguments()[0]));return List.copyOf(out);}
      if(type.getRawType()==Map.class)return Map.of();
      throw new IllegalArgumentException("Unsupported collection");
    }
    Class<?> type=(Class<?>)expected;if(type==String.class||type==boolean.class)return value;
    if(type==int.class)return ((Number)value).intValue();
    if(type.isRecord()&&type.getName().startsWith("kr.workbench."))try{
      var components=type.getRecordComponents();Object[] args=new Object[components.length];Class<?>[] types=new Class<?>[components.length];var map=(Map<?,?>)value;
      for(int i=0;i<components.length;i++){types[i]=components[i].getType();args[i]=decode(map.get(components[i].getName()),components[i].getGenericType());}
      var constructor=type.getDeclaredConstructor(types);constructor.setAccessible(true);return constructor.newInstance(args);
    }catch(ReflectiveOperationException ex){throw new IllegalArgumentException(ex);}
    throw new IllegalArgumentException("Unsupported archive component");
  }
}
