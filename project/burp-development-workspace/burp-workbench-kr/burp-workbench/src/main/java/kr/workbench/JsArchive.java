package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Project-isolated history with bounded pages and disk indexes. Legacy files remain untouched. */
final class JsArchive {
  static final int PAGE_SIZE=200,DETAIL_LIMIT=160*1024*1024,RESULT_LIMIT=32*1024*1024;
  record Summary(String key,int apis,int secrets,String status,String fingerprint,List<String> apiKeys,List<String> secretKeys,List<String> domains,List<String> clouds){}
  record Saved(String source,String page,String type,JsAnalysis.Result result,String[] packet){}
  record Row(String key,String source,int apis,int secrets,String status,String fingerprint,String time,boolean legacy){}
  record Page(List<Row> rows,long total,int offset,boolean cancelled){Page{rows=List.copyOf(rows);} boolean more(){return offset+rows.size()<total;}}
  record Counts(long apis,long secrets,long domains,long clouds){}
  record Usage(long bytes,long records,long protectedRecords,long legacyRecords){}
  record SourceSlice(String text,int start,int total){boolean partial(){return start>0||start+text.length()<total;}}
  record Retention(boolean enabled,int days,long warningBytes){
    Retention{if(days<1||days>3650||warningBytes<1024*1024)throw new IllegalArgumentException("보존 설정 범위 확인");}
  }
  final Path directory;
  private final Path store,index,order,dirty,complete,countsFile;
  private boolean loaded;
  private volatile long revision;
  private long recordCount;
  JsArchive(Path directory){
    this.directory=directory;store=directory.resolve("v2");index=store.resolve("index");
    order=index.resolve("order.bin");dirty=index.resolve("dirty");complete=index.resolve("complete");
    countsFile=index.resolve("findings.bin");
  }
  static JsArchive project(MontoyaApi api){
    try{
      if(api==null||api.persistence()==null||api.persistence().extensionData()==null)return null;
      var data=api.persistence().extensionData();String id=data.getString(BuildInfo.storageKey("js-analysis-archive-project-v1"));
      if(id==null||!id.matches("[0-9a-f-]{36}")){id=UUID.randomUUID().toString();data.setString(BuildInfo.storageKey("js-analysis-archive-project-v1"),id);}
      return new JsArchive(Path.of(System.getProperty("user.home"),BuildInfo.STORAGE_DIRECTORY,"js-analysis",id));
    }catch(Exception ex){return null;}
  }
  synchronized void load()throws IOException{
    if(loaded)return;Files.createDirectories(directory);privatePath(directory,true);
    Files.createDirectories(store);privatePath(store,true);Files.createDirectories(index);privatePath(index,true);
    String legacyStamp=Long.toString(Files.getLastModifiedTime(directory).toMillis());
    boolean rebuild=!Files.isRegularFile(complete)||Files.exists(dirty)||!Files.exists(order)||!Files.exists(countsFile)||!Files.exists(index.resolve("records"));
    if(!rebuild)try{rebuild=!Files.readString(complete).equals(legacyStamp)||Files.size(order)%65!=0;try(var c=new JsArchiveCounts(countsFile)){c.counts();}}catch(Exception ex){rebuild=true;}
    if(rebuild){
      recordCount=0;deleteTree(index);Files.createDirectories(index);privatePath(index,true);Files.writeString(dirty,"index rebuild");Files.createFile(order);
      try(var counts=new JsArchiveCounts(countsFile)){
        for(Path folder:List.of(store,directory))try(var files=Files.newDirectoryStream(folder,"*.meta")){
          for(Path path:files){if(!path.getFileName().toString().matches("[0-9a-f]{64}\\.meta"))continue;
            try{
              Summary summary=readSummary(path);String digest=JsMiner.hash(summary.key());
              boolean legacy=folder.equals(directory);
              if(Files.exists(rowPath(digest))||!Files.isRegularFile(folder.resolve(digest+".data")))continue;
              String time=Files.getLastModifiedTime(path).toInstant().toString();
              if(!legacy){var header=(Map<?,?>)readJson(folder.resolve(digest+".data"),65536,10000);time=Objects.toString(header.get("time"),time);}
              writeIndex(summary,time,legacy,true);counts.apply(summary,1);
            }catch(RuntimeException ex){/* A malformed record cannot hide the remaining history. */}
          }
        }
      }
      atomic(index.resolve("records"),Long.toString(recordCount));atomic(complete,legacyStamp);Files.deleteIfExists(dirty);
    }
    recordCount=Long.parseLong(Files.readString(index.resolve("records")));loaded=true;revision++;
  }
  long revision(){return revision;}
  synchronized Page page(int offset,String query)throws IOException{return page(offset,query,()->false);}
  synchronized Page page(int offset,String query,BooleanSupplier cancelled)throws IOException{
    load();if(offset<0)throw new IllegalArgumentException("negative page offset");
    String needle=Objects.toString(query,"").strip().toLowerCase(Locale.ROOT);if(needle.length()>512)needle=needle.substring(0,512);
    List<Row> selected=new ArrayList<>(PAGE_SIZE);long matched=0;
    try(var in=new RandomAccessFile(order.toFile(),"r")){
      byte[] raw=new byte[64];long records=in.length()/65;
      for(long at=records-1;at>=0;at--){
        if(cancelled.getAsBoolean())return new Page(List.of(),0,offset,true);
        in.seek(at*65);in.readFully(raw);Row row=readRow(new String(raw,StandardCharsets.US_ASCII));if(row==null)continue;
        if(!needle.isEmpty()&&!(row.key()+" "+row.status()).toLowerCase(Locale.ROOT).contains(needle))continue;
        if(matched>=offset&&selected.size()<PAGE_SIZE)selected.add(row);matched++;
        if(needle.isEmpty()&&selected.size()==PAGE_SIZE)break;
      }
    }
    return new Page(selected,needle.isEmpty()?recordCount:matched,offset,false);
  }
  /** Compatibility view for callers/tests: never more than a single page. */
  synchronized Map<String,Summary> snapshot(){
    Map<String,Summary> out=new LinkedHashMap<>();if(!loaded&&!Files.exists(store))return out;try{for(Row row:page(0,"").rows()){Summary s=summary(row.key());if(s!=null)out.put(s.key(),s);}}catch(IOException ignored){}
    return out;
  }
  synchronized Summary summary(String key)throws IOException{
    load();Row row=readRow(JsMiner.hash(key));return row==null?null:readSummary(file(key,".meta",row.legacy()));
  }
  synchronized Counts counts()throws IOException{load();try(var c=new JsArchiveCounts(countsFile)){long[] n=c.counts();return new Counts(n[0],n[1],n[2],n[3]);}}
  synchronized Counts countsWith(Collection<JsSourceCache.Entry> cached)throws IOException{
    load();try(var c=new JsArchiveCounts(countsFile)){
      List<Set<String>> extra=List.of(new HashSet<>(),new HashSet<>(),new HashSet<>(),new HashSet<>());
      for(var entry:cached){var r=entry.result();if(r==null)continue;
        List<List<String>> keys=List.of(r.endpoints().stream().map(JsFindingGroups::key).toList(),r.secrets().stream().map(JsAnalysis.Secret::fingerprint).toList(),r.miner().domains().stream().map(JsMiner.Domain::host).toList(),r.miner().clouds().stream().map(JsMiner.Cloud::url).toList());
        for(int n=0;n<4;n++)for(String value:keys.get(n))if(!c.contains(n,value))extra.get(n).add(value);
      }long[] counts=c.counts();return new Counts(counts[0]+extra.get(0).size(),counts[1]+extra.get(1).size(),counts[2]+extra.get(2).size(),counts[3]+extra.get(3).size());
    }
  }
  synchronized String versionKey(String source,String hash){
    try{
      load();String base=JsVersions.base(source);Path match=index.resolve("lookup-"+JsMiner.hash(base+"\n"+hash));
      if(Files.isRegularFile(match)&&Files.size(match)<16384){String key=Files.readString(match);Row row=readRow(JsMiner.hash(key));if(row!=null&&row.fingerprint().equals(hash))return key;}
      return readRow(JsMiner.hash(source))!=null?source+"#wb-version="+hash.substring(0,Math.min(16,hash.length())):source;
    }catch(IOException ex){return source+"#wb-version="+hash.substring(0,Math.min(16,hash.length()));}
  }
  synchronized boolean has(String key,String hash){try{load();Row row=readRow(JsMiner.hash(key));return row!=null&&row.fingerprint().equals(hash);}catch(IOException ex){return false;}}
  synchronized void save(String key,String source,String page,String type,JsAnalysis.Result result,String[] packet,String hash)throws IOException{
    load();Summary old=summary(key);Row oldRow=readRow(JsMiner.hash(key));String savedAt=Instant.now().toString();Files.writeString(dirty,"save");
    try{
      Summary summary=new Summary(key,result.endpoints().size(),result.secrets().size(),result.status(),hash,
          result.endpoints().stream().map(JsFindingGroups::key).distinct().toList(),result.secrets().stream().map(JsAnalysis.Secret::fingerprint).distinct().toList(),
          result.miner().domains().stream().map(JsMiner.Domain::host).distinct().toList(),result.miner().clouds().stream().map(JsMiner.Cloud::url).distinct().toList());
      atomic(file(key,".source",false),source);
      atomic(file(key,".result",false),MetadataExport.json(encode(result)));
      atomic(file(key,".packet",false),MetadataExport.json(Map.of("count",packet==null?0:packet.length)));
      if(packet!=null)for(int n=0;n<packet.length;n++)atomic(file(key,".packet"+n,false),Objects.toString(packet[n],""));
      atomic(file(key,".data",false),MetadataExport.json(Map.of("format",2,"page",Objects.toString(page,""),"type",Objects.toString(type,""),"characters",source.length(),"time",savedAt)));
      atomic(file(key,".meta",false),MetadataExport.json(encode(summary)));
      writeIndex(summary,savedAt,false,old==null);
      if(oldRow!=null&&oldRow.legacy())atomic(store.resolve(JsMiner.hash(key)+".pin"),"이전 버전에서 연결한 이력 보존");
      try(var counts=new JsArchiveCounts(countsFile)){if(old!=null)counts.apply(old,-1);counts.apply(summary,1);}
      atomic(index.resolve("records"),Long.toString(recordCount));Files.deleteIfExists(dirty);revision++;
    }catch(IOException|RuntimeException ex){loaded=false;throw ex;}
  }
  synchronized void updateResult(String key,JsAnalysis.Result result)throws IOException {
    load();Row row=readRow(JsMiner.hash(key));if(row==null)return;
    if(row.legacy()){Saved saved=readLegacy(key);save(key,saved.source(),saved.page(),saved.type(),result,saved.packet(),row.fingerprint());protect(key);return;}
    Summary old=summary(key);Summary next=new Summary(key,result.endpoints().size(),result.secrets().size(),result.status(),row.fingerprint(),
        result.endpoints().stream().map(JsFindingGroups::key).distinct().toList(),result.secrets().stream().map(JsAnalysis.Secret::fingerprint).distinct().toList(),result.miner().domains().stream().map(JsMiner.Domain::host).distinct().toList(),result.miner().clouds().stream().map(JsMiner.Cloud::url).distinct().toList());
    Files.writeString(dirty,"result update");try{atomic(file(key,".result",false),MetadataExport.json(encode(result)));atomic(file(key,".meta",false),MetadataExport.json(encode(next)));writeIndex(next,row.time(),false,false);try(var counts=new JsArchiveCounts(countsFile)){counts.apply(old,-1);counts.apply(next,1);}Files.deleteIfExists(dirty);revision++;}catch(IOException|RuntimeException ex){loaded=false;throw ex;}
  }
  synchronized Saved read(String key)throws IOException{
    load();Row row=readRow(JsMiner.hash(key));if(row==null)return null;
    if(row.legacy())return readLegacy(key);
    try{
      Map<?,?> header=(Map<?,?>)readJson(file(key,".data",false),65536,10000);
      JsAnalysis.Result result=resultMetadata(key);if(result==null)throw new IOException("분석 결과 읽기 제한");
      String source=readText(file(key,".source",false),DETAIL_LIMIT);
      String[] packet=readPackets(key,false);
      return new Saved(source,Objects.toString(header.get("page"),""),Objects.toString(header.get("type"),""),result,packet);
    }catch(RuntimeException ex){throw new IOException("분석 기록 형식 오류",ex);}
  }
  synchronized Saved readPreview(String key)throws IOException{
    load();Row row=readRow(JsMiner.hash(key));if(row==null)return null;
    if(row.legacy())return readLegacy(key);
    Map<?,?> header=(Map<?,?>)readJson(file(key,".data",false),65536,10000);
    SourceSlice slice=sourceSlice(key,0,JsAnalysis.SOURCE_LIMIT);
    JsAnalysis.Result result=resultMetadata(key);if(result==null)throw new IOException("분석 결과 읽기 제한");
    return new Saved(slice.text(),Objects.toString(header.get("page"),""),Objects.toString(header.get("type"),""),result,readPackets(key,true));
  }
  private String[] readPackets(String key,boolean preview)throws IOException{
    Object value=readJson(file(key,".packet",false),65536,10000);
    if(value instanceof List<?> list)return list.isEmpty()?null:list.stream().map(Objects::toString).toArray(String[]::new);
    int count=((Number)((Map<?,?>)value).get("count")).intValue();if(count<0||count>4)throw new IOException("패킷 개수 오류");
    if(count==0)return null;String[] packets=new String[count];
    for(int i=0;i<count;i++){
      Path path=file(key,".packet"+i,false);
      if(!preview)packets[i]=readText(path,DETAIL_LIMIT);
      else try(Reader in=Files.newBufferedReader(path,StandardCharsets.UTF_8)){char[] text=new char[JsAnalysis.SOURCE_LIMIT];int n=0,k;while(n<text.length&&(k=in.read(text,n,text.length-n))>=0)n+=k;packets[i]=new String(text,0,n);}
    }return packets;
  }
  private Saved readLegacy(String key)throws IOException{
    try{
      var map=(Map<?,?>)readJson(file(key,".data",true),DETAIL_LIMIT,1000000);var packets=(List<?>)map.get("packet");
      return new Saved((String)map.get("source"),(String)map.get("page"),(String)map.get("type"),(JsAnalysis.Result)decode(map.get("result"),JsAnalysis.Result.class),packets.isEmpty()?null:packets.stream().map(Object::toString).toArray(String[]::new));
    }catch(RuntimeException ex){throw new IOException("이전 분석 기록 형식 오류",ex);}
  }
  synchronized SourceSlice sourceSlice(String key,int around,int limit)throws IOException{
    load();if(limit<1||limit>JsAnalysis.SOURCE_LIMIT||around<0)throw new IllegalArgumentException("원문 표시 범위");
    Row row=readRow(JsMiner.hash(key));if(row==null)return null;
    int start=Math.max(0,around-limit/3);
    if(row.legacy()){Saved saved=readLegacy(key);int end=Math.min(saved.source().length(),start+limit);return new SourceSlice(saved.source().substring(Math.min(start,end),end),Math.min(start,end),saved.source().length());}
    Map<?,?> header=(Map<?,?>)readJson(file(key,".data",false),65536,10000);int total=((Number)header.get("characters")).intValue();start=Math.min(start,total);
    try(Reader in=Files.newBufferedReader(file(key,".source",false),StandardCharsets.UTF_8)){
      long left=start;while(left>0){long skipped=in.skip(left);if(skipped==0){if(in.read()<0)break;skipped=1;}left-=skipped;}
      char[] text=new char[Math.min(limit,total-start)];int n=0,read;while(n<text.length&&(read=in.read(text,n,text.length-n))>=0)n+=read;
      return new SourceSlice(new String(text,0,n),start,total);
    }
  }
  synchronized List<Summary> versions(String source){
    try{load();Path path=index.resolve("source-"+JsMiner.hash(JsVersions.base(source)));if(!Files.exists(path))return List.of();
      Comparator<Row> byTime=Comparator.comparing(Row::time).thenComparing(Row::key);
      PriorityQueue<Row> recent=new PriorityQueue<>(byTime);Set<String> keys=new HashSet<>();
      try(var in=new RandomAccessFile(path.toFile(),"r")){byte[] raw=new byte[64];for(long at=in.length()/65-1;at>=0;at--){
        in.seek(at*65);in.readFully(raw);Row row=readRow(new String(raw,StandardCharsets.US_ASCII));if(row==null||keys.contains(row.key()))continue;
        if(recent.size()==8&&byTime.compare(row,recent.peek())<=0)continue;
        recent.add(row);keys.add(row.key());if(recent.size()>8)keys.remove(recent.remove().key());
      }}
      return recent.stream().sorted(byTime).map(row->new Summary(row.key(),row.apis(),row.secrets(),row.status(),row.fingerprint(),List.of(),List.of(),List.of(),List.of())).toList();
    }catch(IOException ex){return List.of();}
  }
  synchronized String time(String key){try{load();Row row=readRow(JsMiner.hash(key));return row==null?"1970-01-01T00:00:00Z":row.time();}catch(IOException ex){return "1970-01-01T00:00:00Z";}}
  synchronized JsAnalysis.Result resultMetadata(String key)throws IOException{
    load();Row row=readRow(JsMiner.hash(key));if(row==null)return null;
    try{
      if(!row.legacy())return (JsAnalysis.Result)decode(readJson(file(key,".result",false),RESULT_LIMIT,1000000),JsAnalysis.Result.class);
      Path path=file(key,".data",true);if(Files.size(path)>8L*1024*1024)return null;
      return (JsAnalysis.Result)decode(((Map<?,?>)readJson(path,8*1024*1024,500000)).get("result"),JsAnalysis.Result.class);
    }catch(RuntimeException ex){return null;}
  }
  synchronized void protect(String key)throws IOException{load();if(readRow(JsMiner.hash(key))!=null)atomic(store.resolve(JsMiner.hash(key)+".pin"),"사용자 보존 또는 외부 작업 연결");}
  synchronized boolean protectedRecord(String key)throws IOException{load();Row row=readRow(JsMiner.hash(key));return row!=null&&(row.legacy()||Files.exists(store.resolve(JsMiner.hash(key)+".pin")));}
  synchronized boolean delete(String key)throws IOException{
    load();Row row=readRow(JsMiner.hash(key));if(row==null)return false;if(protectedRecord(key))throw new IOException("보존 표시·이전 버전 이력은 정리에서 제외합니다.");
    Summary old=summary(key);Files.writeString(dirty,"delete");
    try{
      String digest=JsMiner.hash(key);Path position=index.resolve(digest+".position");
      if(Files.isRegularFile(position)){long ordinal=Long.parseLong(Files.readString(position));try(var out=new RandomAccessFile(order.toFile(),"rw")){out.seek(ordinal*65);out.write(("0".repeat(64)+"\n").getBytes(StandardCharsets.US_ASCII));}}
      Files.deleteIfExists(position);Files.deleteIfExists(rowPath(digest));recordCount--;
      try(var counts=new JsArchiveCounts(countsFile)){counts.apply(old,-1);}
      for(String suffix:List.of(".data",".meta",".source",".result",".packet",".packet0",".packet1",".packet2",".packet3"))Files.deleteIfExists(file(key,suffix,false));
      atomic(index.resolve("records"),Long.toString(recordCount));Files.deleteIfExists(dirty);revision++;return true;
    }catch(IOException|RuntimeException ex){loaded=false;throw ex;}
  }
  synchronized Usage usage()throws IOException{
    load();long bytes=0,records=0,pins=0,legacy=0;
    try(var paths=Files.walk(directory)){var it=paths.iterator();while(it.hasNext()){Path p=it.next();if(Files.isRegularFile(p))bytes+=Files.size(p);}}
    try(var in=new RandomAccessFile(order.toFile(),"r")){byte[] raw=new byte[64];for(long at=0;at<in.length()/65;at++){in.seek(at*65);in.readFully(raw);Row row=readRow(new String(raw,StandardCharsets.US_ASCII));if(row==null)continue;records++;if(row.legacy())legacy++;if(protectedRecord(row.key()))pins++;}}
    return new Usage(bytes,records,pins,legacy);
  }
  synchronized Retention retention()throws IOException{
    load();Path path=store.resolve("retention.json");if(!Files.exists(path))return new Retention(false,90,2L*1024*1024*1024);
    try{Map<?,?> map=(Map<?,?>)readJson(path,4096,100);return new Retention(Boolean.TRUE.equals(map.get("enabled")),((Number)map.get("days")).intValue(),((Number)map.get("warningBytes")).longValue());}catch(RuntimeException ex){return new Retention(false,90,2L*1024*1024*1024);}
  }
  synchronized void retention(Retention value)throws IOException{load();atomic(store.resolve("retention.json"),MetadataExport.json(Map.of("enabled",value.enabled(),"days",value.days(),"warningBytes",value.warningBytes())));}
  synchronized int prune(Retention policy)throws IOException{
    if(!policy.enabled())return 0;load();Instant cutoff=Instant.now().minusSeconds(policy.days()*86400L);int removed=0;
    // A page is held at a time; removed rows no longer count toward the next logical offset.
    for(int offset=0;;){Page page=page(offset,"");int deleted=0;for(Row row:page.rows())if(!protectedRecord(row.key())&&Instant.parse(row.time()).isBefore(cutoff)){if(delete(row.key())){removed++;deleted++;}}
      if(!page.more())break;offset+=page.rows().size()-deleted;}
    return removed;
  }
  synchronized void clear()throws IOException{
    load();for(int offset=0;;){Page page=page(offset,"");int removed=0;for(Row row:page.rows())if(!protectedRecord(row.key())&&delete(row.key()))removed++;if(!page.more())break;offset+=page.rows().size()-removed;}
    if(page(0,"").total()==0){deleteTree(store);loaded=false;}revision++;
  }
  private void writeIndex(Summary s,String time,boolean legacy,boolean append)throws IOException{
    String digest=JsMiner.hash(s.key()),base=JsVersions.base(s.key());
    atomic(rowPath(digest),MetadataExport.json(encode(new Row(s.key(),base,s.apis(),s.secrets(),s.status(),s.fingerprint(),time,legacy))));
    if(append){atomic(index.resolve(digest+".position"),Long.toString(Files.size(order)/65));recordCount++;Files.writeString(order,digest+"\n",StandardCharsets.US_ASCII,StandardOpenOption.CREATE,StandardOpenOption.APPEND);Files.writeString(index.resolve("source-"+JsMiner.hash(base)),digest+"\n",StandardCharsets.US_ASCII,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    atomic(index.resolve("lookup-"+JsMiner.hash(base+"\n"+s.fingerprint())),s.key());
  }
  private Row readRow(String digest)throws IOException{Path path=rowPath(digest);if(!Files.isRegularFile(path))return null;try{return (Row)decode(readJson(path,65536,10000),Row.class);}catch(RuntimeException ex){return null;}}
  private Path rowPath(String digest){return index.resolve(digest+".row");}
  private Summary readSummary(Path path)throws IOException{return (Summary)decode(readJson(path,8*1024*1024,200000),Summary.class);}
  private Path file(String key,String suffix,boolean legacy){return (legacy?directory:store).resolve(JsMiner.hash(key)+suffix);}
  private static String readText(Path path,int limit)throws IOException{if(Files.size(path)>limit)throw new IOException("분석 기록 읽기 한도 초과");return Files.readString(path,StandardCharsets.UTF_8);}
  private static Object readJson(Path path,int bytes,int nodes)throws IOException{return MinerJson.parse(readText(path,bytes),bytes,nodes);}
  private static void deleteTree(Path path)throws IOException{if(!Files.exists(path))return;Files.walkFileTree(path,new SimpleFileVisitor<Path>(){public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{Files.deleteIfExists(file);return FileVisitResult.CONTINUE;}public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;Files.deleteIfExists(dir);return FileVisitResult.CONTINUE;}});}
  private static void privatePath(Path path,boolean directory)throws IOException{try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));}catch(UnsupportedOperationException ignored){}}
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
