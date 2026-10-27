package kr.workbench;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded disk pages, reference-counted totals, legacy preservation and pure syntax reuse. */
public final class JsArchivePagingTest extends WorkbenchTest {
  static JsAnalysis.Result result(String path){return new JsAnalysis.Result(List.of(new JsAnalysis.Endpoint("https://app.test"+path,"GET","fetch","","","",1,0,5,true,"static")),List.of(),"complete",false);}
  public static void main(String[] args)throws Exception{
    Path root=Files.createTempDirectory("js-page-next-");
    try{
      var archive=new JsArchive(root);archive.load();
      for(int n=0;n<450;n++){String key="https://app.test/part-"+n+".js";archive.save(key,"fetch('/shared');","https://app.test/","application/javascript",result(n%2==0?"/even":"/odd"),null,JsMiner.hash("source-"+n));}
      var first=archive.page(0,"");var second=archive.page(200,"");var last=archive.page(400,"");
      check(first.rows().size()==200&&second.rows().size()==200&&last.rows().size()==50&&first.total()==450,"history reads 200-item pages with exact total");
      check(Collections.disjoint(first.rows().stream().map(JsArchive.Row::key).toList(),second.rows().stream().map(JsArchive.Row::key).toList()),"adjacent history pages do not repeat rows");
      check(archive.snapshot().size()==200,"compatibility summary snapshot is bounded to one page");
      check(archive.counts().apis()==2,"incremental disk reference counts deduplicate findings across every page");
      check(archive.page(0,"part-44").total()==11,"search covers records beyond the current page");
      check(archive.page(0,"",()->true).cancelled(),"obsolete page search can stop before reading rows");
      String key="https://app.test/part-449.js";archive.protect(key);
      try{archive.delete(key);throw new AssertionError("protected deletion accepted");}catch(java.io.IOException expected){check(true,"preserved evidence-linked history cannot be deleted through cleanup");}
      check(archive.prune(new JsArchive.Retention(false,1,1024*1024))==0,"retention settings default to no automatic deletion");
      String removed="https://app.test/part-448.js";archive.delete(removed);archive.save(removed,"new","","",result("/even"),null,JsMiner.hash("new"));
      check(archive.page(0,"").total()==450&&archive.page(0,"part-448.js").rows().size()==1,"delete and re-add do not resurrect an old index position");
      archive.updateResult(removed,result("/third"));check(archive.counts().apis()==3,"result-only update adjusts exact reference counts");
      var reopened=new JsArchive(root);reopened.load();check(reopened.counts().apis()==3&&reopened.page(400,"").rows().size()==50,"counts and pagination survive extension restart without retaining summaries");
      String versionSource="https://app.test/version.js";
      for(int n=0;n<12;n++){String hash=JsMiner.hash("version"+n);archive.save(archive.versionKey(versionSource,hash),"version"+n,"","",result("/even"),null,hash);}
      check(archive.versions(versionSource).size()==8&&archive.versionKey(versionSource,JsMiner.hash("version1")).contains(JsMiner.hash("version1").substring(0,16)),"source index restores recent eight versions and finds older hashes directly");
      String large="/*"+"x".repeat(2200*1024)+"*/fetch('/tail');";String largeKey="https://app.test/large.js";
      archive.save(largeKey,large,"https://app.test/","application/javascript",result("/tail"),new String[]{"request","response",large},JsMiner.hash(large));
      var preview=archive.readPreview(largeKey);check(preview.source().length()==JsAnalysis.SOURCE_LIMIT&&preview.packet()[2].length()==JsAnalysis.SOURCE_LIMIT,"detail preview reads bounded source and packet sections");
      var slice=archive.sourceSlice(largeKey,large.length()-12,JsAnalysis.SOURCE_LIMIT);
      check(slice.start()>0&&slice.text().contains("/tail")&&slice.text().length()<=JsAnalysis.SOURCE_LIMIT,"late source navigation reads a bounded surrounding range");
      Path sourceFile=root.resolve("v2").resolve(JsMiner.hash(largeKey)+".source");Path hidden=sourceFile.resolveSibling(sourceFile.getFileName()+".hold");Files.move(sourceFile,hidden);
      check(archive.resultMetadata(largeKey).endpoints().size()==1&&archive.page(0,"large.js").total()==1,"list and version metadata do not read the source body");Files.move(hidden,sourceFile);
      check(archive.read(largeKey).source().equals(large),"explicit full compatibility read preserves captured source");
      Files.writeString(root.resolve("v2/index/dirty"),"interrupted index update");var recovered=new JsArchive(root);recovered.load();check(recovered.counts().apis()==4&&recovered.page(0,"").total()==463,"interrupted index update rebuilds bounded records and exact totals");
      var usage=recovered.usage();check(usage.bytes()>large.length()&&usage.records()==463&&usage.protectedRecords()==1,"disk usage distinguishes records and preserved history");
      cacheInvalidation(root.resolve("query-cache"));legacy(root.resolve("legacy"));cache();panelWorkflow(root.resolve("panel"));
    }finally{Files.walkFileTree(root,new SimpleFileVisitor<Path>(){public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws java.io.IOException{Files.delete(file);return FileVisitResult.CONTINUE;}public FileVisitResult postVisitDirectory(Path dir,java.io.IOException error)throws java.io.IOException{Files.delete(dir);return FileVisitResult.CONTINUE;}});}
    System.out.println("JsArchivePagingTest: "+checks+" checks passed");
  }
  static void cacheInvalidation(Path root)throws Exception{
    var archive=new JsArchive(root);archive.load();
    for(int n=0;n<430;n++)archive.save("https://app.test/cache-"+n+".js","x","","",result("/shared"),null,JsMiner.hash("cache-"+n));
    var first=archive.page(0," CACHE- ");var next=archive.page(200,"cache-");var last=archive.page(400,"cache-");
    check(first.total()==430&&next.rows().size()==200&&last.rows().size()==30,"normalized query pages preserve total and complete final page");
    check(first.rows().get(0).key().endsWith("429.js")&&next.rows().get(0).key().endsWith("229.js")&&last.rows().get(0).key().endsWith("29.js"),"cached matching digests preserve newest-first page order");
    var cancel=new AtomicInteger();check(archive.page(0,"different",()->cancel.incrementAndGet()>30).cancelled(),"cancelled replacement search does not publish a partial total");
    check(archive.page(400,"cache-").rows().equals(last.rows()),"cancelled other query preserves previous completed result");
    check(archive.page(0,"cache-",()->true).cancelled(),"cached lookup still observes cancellation");
    String key="https://app.test/cache-428.js";archive.delete(key);
    check(archive.page(0,"cache-").total()==429&&archive.page(0,"428.js").total()==0,"deletion invalidates both total and matching rows");
    archive.save(key,"restored","","",result("/shared"),null,JsMiner.hash("restored"));
    check(archive.page(0,"cache-").total()==430&&archive.page(0,"cache-").rows().get(0).key().equals(key),"re-added key appears once in newest position");
    archive.updateResult(key,new JsAnalysis.Result(List.of(),List.of(),"new-status",false));
    check(archive.page(0,"NEW-STATUS").total()==1&&archive.page(0,"complete").total()==429,"result status update invalidates query membership");
    archive.protect(key);check(archive.page(0,"new-status").rows().get(0).key().equals(key)&&archive.protectedRecord(key),"protection preserves search and exact selected record");
    var reopened=new JsArchive(root);reopened.load();
    check(reopened.page(0,"new-status").total()==1&&reopened.page(400,"cache-").rows().size()==30,"restart rebuilds transient query state from current disk metadata");
    var all=new ArrayList<String>();for(int offset=0;offset<430;offset+=200)for(var row:archive.page(offset,"").rows())all.add(row.key());
    check(all.size()==430&&new HashSet<>(all).size()==430&&all.get(0).equals(key),"unfiltered page checkpoints preserve order and uniqueness after tombstones");
    archive.delete("https://app.test/cache-427.js");
    check(archive.page(200,"").rows().size()==200&&archive.page(400,"").rows().size()==29,"mutation invalidates blank-page checkpoints before later-page reads");
    archive.page(0,"cache-");Files.write(root.resolve("v2/index/query-page.bin"),new byte[]{1,2,3});
    check(archive.page(200,"cache-").rows().size()==200&&archive.page(0,"cache-").total()==429,"damaged optional query cache is rebuilt without losing readable history");
    var left=archive.page(0,"cache-1");var other=new JsArchive(root);other.load();var right=other.page(0,"cache-2");
    check(left.total()==right.total()&&archive.page(0,"cache-1").rows().equals(left.rows()),"another instance cannot substitute an equal-size opposite-query cache");
    Path cacheFile=root.resolve("v2/index/query-page.bin");byte[] damaged=Files.readAllBytes(cacheFile);
    System.arraycopy(JsMiner.hash("https://app.test/cache-299.js").getBytes(StandardCharsets.US_ASCII),0,damaged,64,64);Files.write(cacheFile,damaged);
    check(archive.page(0,"cache-1").rows().equals(left.rows()),"same-size corruption containing a valid unrelated record cannot change cached results");
    archive.page(0,"new-status");other.updateResult("https://app.test/cache-426.js",new JsAnalysis.Result(List.of(),List.of(),"new-status",false));
    check(archive.page(0,"new-status").total()==2,"completed status update by another instance invalidates membership without a query from the writer");
    archive.page(0,"");archive.page(200,"");other.save("https://app.test/cache-added.js","new","","",result("/shared"),null,JsMiner.hash("added"));
    check(archive.page(0,"").total()==430&&archive.page(0,"").rows().get(0).key().endsWith("cache-added.js")&&archive.page(400,"").rows().size()==30,"completed insertion refreshes another instance's count and page checkpoints");
    archive.page(0,"new-status");other.delete("https://app.test/cache-426.js");
    check(archive.page(0,"new-status").total()==1&&archive.page(0,"").total()==429,"completed deletion invalidates another instance's filtered and unfiltered views");
    archive.page(0,"cache-");Files.writeString(root.resolve("v2/index/dirty"),"rebuild elsewhere");var rebuilder=new JsArchive(root);rebuilder.load();
    check(archive.page(0,"cache-").total()==429&&archive.page(0,"").total()==429,"index rebuild publishes a new generation to existing readers");
    Files.createDirectory(root.resolve("v2/index/query-page.building"));
    check(archive.page(0,"428.js").total()==1,"unwritable optional cache falls back to a complete uncached query");
  }
  static void legacy(Path path)throws Exception{
    Files.createDirectories(path);String key="https://old.test/bundle.js",hash=JsMiner.hash("old");var result=result("/old");
    Path data=path.resolve(JsMiner.hash(key)+".data"),meta=path.resolve(JsMiner.hash(key)+".meta");
    String content=MetadataExport.json(Map.of("source","fetch('/old');","page","https://old.test/","type","application/javascript","result",JsArchive.encode(result),"packet",List.of()));
    Files.writeString(data,content);Files.writeString(meta,MetadataExport.json(JsArchive.encode(new JsArchive.Summary(key,1,0,"old",hash,List.of(JsFindingGroups.key(result.endpoints().get(0))),List.of(),List.of(),List.of()))));
    byte[] oldMeta=Files.readAllBytes(meta);var archive=new JsArchive(path);archive.load();
    check(archive.page(0,"").total()==1&&archive.read(key).source().contains("/old"),"previous archive format remains readable through a new index");
    archive.clear();check(Files.readString(data).equals(content)&&Arrays.equals(Files.readAllBytes(meta),oldMeta)&&archive.page(0,"").total()==1,"cleanup never rewrites or deletes previous-version history");
  }
  static void cache(){
    var cache=new JsStaticContentCache(8192);var calls=new AtomicInteger();String code="export const value = async () => '/relative';";
    var first=cache.get(code,"parser-A",()->{calls.incrementAndGet();return new JsSyntax.Compatible(code,true);});
    check(cache.get(code,"parser-A",()->{throw new AssertionError("cache miss");})==first&&cache.hits()==1,"same content and parser configuration reuse pure syntax normalization");
    cache.get(code,"parser-B",()->{calls.incrementAndGet();return new JsSyntax.Compatible(code,false);});check(calls.get()==2,"parser or configuration change cannot reuse an incompatible value");
    for(int n=0;n<100;n++){String input="x".repeat(300)+n;cache.get(input,"parser-A",()->new JsSyntax.Compatible(input,false));}
    check(cache.bytes()<=8192&&cache.size()<100,"shared syntax results obey independent byte and count budgets");
    var a=JsAnalysis.analyzeContext("fetch('/relative');","https://cdn.test/app.js","application/javascript","https://a.test/page");
    var b=JsAnalysis.analyzeContext("fetch('/relative');","https://cdn.test/app.js","application/javascript","https://b.test/page");
    check(a.endpoints().get(0).url().startsWith("https://a.test/")&&b.endpoints().get(0).url().startsWith("https://b.test/"),"content reuse never merges document-relative endpoint origins");
  }
  static void panelWorkflow(Path home)throws Exception {
    Files.createDirectories(home);String previousHome=System.getProperty("user.home");System.setProperty("user.home",home.toString());
    Map<String,String> data=new HashMap<>();
    var object=proxy(burp.api.montoya.persistence.PersistedObject.class,(p,m,a)->switch(m.getName()){case "getString"->data.get(a[0]);case "setString"->{data.put(a[0].toString(),a[1].toString());yield null;}default->def(m);});
    var persistence=proxy(burp.api.montoya.persistence.Persistence.class,(p,m,a)->m.getName().equals("extensionData")?object:def(m));
    var api=proxy(burp.api.montoya.MontoyaApi.class,(p,m,a)->m.getName().equals("persistence")?persistence:def(m));
    var holder=new java.util.concurrent.atomic.AtomicReference<JsAnalysisPanel>();edt(()->holder.set(new JsAnalysisPanel(api,()->{})));var panel=holder.get();
    try {
      for(int n=0;n<205;n++)panel.archive.save("https://app.test/ui-"+n+".js","fetch('/item');","https://app.test/","application/javascript",result("/item"),null,JsMiner.hash("ui-"+n));
      String key="https://app.test/tail.js",source="fetch('/head');/*"+"x".repeat(2200*1024)+"*/fetch('/tail');";
      int head=source.indexOf("'/head'"),tail=source.indexOf("'/tail'");
      var findings=new JsAnalysis.Result(List.of(new JsAnalysis.Endpoint("https://app.test/head","GET","fetch","","","",1,head,7,true,"static"),new JsAnalysis.Endpoint("https://app.test/tail","GET","fetch","","","",1,tail,7,true,"static")),List.of(),"complete",false);
      panel.archive.save(key,source,"https://app.test/","application/javascript",findings,null,JsMiner.hash(source));
      edt(()->{panel.analysisContent();panel.startHistory();});JsAnalysisPanelTest.waitDone(panel);
      check(panel.historyModel.getRowCount()==200&&panel.historyPageLabel.getText().endsWith("/ 206"),"real history model displays one bounded disk page");
      edt(panel.historyNext::doClick);JsAnalysisPanelTest.waitDone(panel);check(panel.historyModel.getRowCount()==6&&panel.historyPrevious.isEnabled()&&!panel.historyNext.isEnabled(),"next history page retains exact navigation state");
      edt(()->{panel.historySearch.setText("ui-1");panel.historySearch.setText("ui-204.js");});JsAnalysisPanelTest.waitDone(panel);
      check(panel.historyModel.getRowCount()==1&&panel.historyModel.getValueAt(0,0).toString().endsWith("ui-204.js"),"newer search wins over an obsolete queued query");
      edt(()->panel.historySearch.setText("tail.js"));JsAnalysisPanelTest.waitDone(panel);
      edt(()->panel.historyTable.setRowSelectionInterval(0,0));JsAnalysisPanelTest.waitDone(panel);
      check(panel.currentSourceKey().equals(key)&&panel.currentResult().endpoints().size()==2,"selecting history loads complete finding metadata with bounded source preview");
      edt(()->panel.urlTable.setRowSelectionInterval(1,1));JsAnalysisPanelTest.waitDone(panel);
      check(panel.input.getText().contains("/tail")&&panel.input.getText().length()<=JsAnalysis.SOURCE_LIMIT&&panel.input.getText().substring(panel.input.getCaretPosition()).startsWith("'/tail'"),"late endpoint navigation reads only its source range and preserves the exact position");
      edt(()->panel.urlTable.setRowSelectionInterval(0,0));JsAnalysisPanelTest.waitDone(panel);
      check(panel.input.getText().startsWith("fetch('/head')")&&panel.input.getCaretPosition()==head,"navigation can return from a late range to the original prefix");
      edt(()->panel.protectHistory(key));JsAnalysisPanelTest.waitDone(panel);
      check(panel.archive.protectedRecord(key),"explicit history protection is persisted by the panel workflow");
      edt(panel::clear);JsAnalysisPanelTest.waitDone(panel);
      check(panel.archive.page(0,"").total()==1&&panel.historyModel.getRowCount()==1,"initialization preserves protected history and refreshes the visible page");
    } finally {edt(panel::close);System.setProperty("user.home",previousHome);}
  }

}
