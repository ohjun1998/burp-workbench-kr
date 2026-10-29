package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import javax.swing.table.*;

/** Synthetic URLs through the production Swing component; no native Burp GUI or target traffic. */
public final class PassiveImportBenchmark {
  static final com.sun.management.ThreadMXBean MX =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
  static final AtomicInteger SENDS = new AtomicInteger();
  static final class CountingSorter extends TableRowSorter<DefaultTableModel> {
    long filterSets, includes, events;
    CountingSorter(DefaultTableModel model) { super(model); addRowSorterListener(e -> events++); }
    @Override public void setRowFilter(RowFilter<? super DefaultTableModel, ? super Integer> filter) {
      filterSets++;
      super.setRowFilter(filter == null ? null : new RowFilter<DefaultTableModel,Integer>() {
        public boolean include(Entry<? extends DefaultTableModel,? extends Integer> entry) {
          includes++; return filter.include(entry);
        }
      });
    }
    void resetCounts() { filterSets = includes = events = 0; }
  }
  @SuppressWarnings("unchecked")
  static <T> T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
  }
  static Object fallback(java.lang.reflect.Method method) {
    Class<?> type=method.getReturnType();
    if(!type.isPrimitive())return null;
    if(type==boolean.class)return false;if(type==byte.class)return (byte)0;
    if(type==short.class)return(short)0;if(type==int.class)return 0;
    if(type==long.class)return 0L;if(type==float.class)return 0F;
    if(type==double.class)return 0D;if(type==char.class)return '\0';return null;
  }
  static final MontoyaApi API = proxy(MontoyaApi.class,(p,m,a) -> m.getName().equals("http")
      ? proxy(Http.class,(q,n,b)->{if(n.getName().equals("sendRequest")){SENDS.incrementAndGet();throw new AssertionError("Import emitted target traffic");}return fallback(n);})
      : fallback(m));
  static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  static String input(){
    StringBuilder value=new StringBuilder("https://example.test/api/items/0?view=one\nhttps://example.test/api/items/0?view=two\n");
    for(int i=2;i<1000;i++)value.append("https://example.test/api/items/").append(i).append("?view=detail\n");
    return value.toString();
  }
  static Map<String,Object> sample(int index)throws Exception{
    AtomicReference<Map<String,Object>> answer = new AtomicReference<>();
    SwingUtilities.invokeAndWait(()->{
      PassiveScanPanel panel=new PassiveScanPanel(API);
      try{
        panel.target.setText("example.test");
        CountingSorter sorter=new CountingSorter(panel.model);panel.table.setRowSorter(sorter);sorter.resetCounts();
        long allocated=MX.getThreadAllocatedBytes(Thread.currentThread().getId());
        long started=System.nanoTime();panel.importUrls(input());long nanos=System.nanoTime()-started;
        long bytes=MX.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
        Map<String,Object> sample=new LinkedHashMap<>();sample.put("sample",index);sample.put("durationMs",nanos/1e6);sample.put("edtAllocatedBytes",bytes);
        sample.put("filterSets",sorter.filterSets);sample.put("rowFilterIncludes",sorter.includes);sample.put("sortEvents",sorter.events);
        require(panel.items.size()==1000&&panel.model.getRowCount()==1000&&panel.table.getRowCount()==1000,"exactly1000rows");
        String a="https://example.test/api/items/0?view=one",b="https://example.test/api/items/0?view=two";
        require(panel.items.containsKey(a)&&panel.items.containsKey(b)&&!a.equals(b),"queryValuesRemainDistinct");
        require(panel.items.values().stream().allMatch(x->x.sources.equals(Set.of("가져오기"))&&x.state.equals("미검증")),"originalImportSourcesAndStates");
        panel.add(a,"Burp History GET");panel.add(a,"OpenAPI POST");
        var item=panel.items.get(a);
        require(item.sources.equals(new LinkedHashSet<>(List.of("가져오기","Burp History GET","OpenAPI POST")))&&item.observedMethods.equals(Set.of("GET"))&&item.inferredMethods.equals(Set.of("POST")),"mergedSourcesAndMethodProvenance");
        require(panel.items.size()==1000&&panel.model.getRowCount()==1000,"sourceMergeDoesNotDuplicateRow");
        panel.search.setText("view=two");require(panel.table.getRowCount()==1&&panel.visible().equals(List.of(b)),"queryFilterTargetsExactOriginal");
        require(SENDS.get()==0,"zeroTargetRequests");
        sample.put("retainedRows",1000);sample.put("queryValuesRemainDistinct",true);sample.put("sourceAndMethodProvenancePreserved",true);sample.put("queryFilterTargetsExactOriginal",true);sample.put("targetRequests",SENDS.get());
        answer.set(sample);
      }finally{panel.close();}
    });
    return answer.get();
  }
  static double median(List<Map<String,Object>> rows,String name){return rows.stream().mapToDouble(r->((Number)r.get(name)).doubleValue()).sorted().skip(rows.size()/2).findFirst().orElseThrow();}
  static double max(List<Map<String,Object>> rows,String name){return rows.stream().mapToDouble(r->((Number)r.get(name)).doubleValue()).max().orElseThrow();}
  public static void main(String[]args)throws Exception{
    require(MX.isThreadAllocatedMemorySupported(),"ThreadMXBean supports allocation measurement");MX.setThreadAllocatedMemoryEnabled(true);
    Map<String,Object> warmup=sample(-1);List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<5;i++){rows.add(sample(i));System.out.println(MetadataExport.json(rows.get(i)));}
    Map<String,Object> report=new LinkedHashMap<>();report.put("scenario","Import1000SyntheticUrlsThroughProductionPassiveScanPanel");report.put("execution","headless Swing on EDT; Montoya API adapter; no native Burp GUI; no external target");report.put("java",System.getProperty("java.runtime.version"));report.put("loadedFrom",PassiveScanPanel.class.getProtectionDomain().getCodeSource().getLocation().toString());report.put("warmupSamples",1);report.put("measuredSamples",5);report.put("warmup",warmup);report.put("samples",rows);report.put("medianMs",median(rows,"durationMs"));report.put("maxMs",max(rows,"durationMs"));report.put("medianEdtAllocatedBytes",median(rows,"edtAllocatedBytes"));report.put("maxEdtAllocatedBytes",max(rows,"edtAllocatedBytes"));report.put("targetRequests",SENDS.get());
    Path path=Path.of(args[0]);Files.writeString(path,MetadataExport.json(report)+"\n");System.out.println("Saved "+path);
  }
}
