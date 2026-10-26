package kr.workbench;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Identical harness for 0.52.0 and candidate sources, using the disk store and
 * production search. Synthetic adapters only; no targets or native Burp. */
public final class TrafficSearchBenchmark extends WorkbenchTest {
  static final int WARMUP=5;
  static final int SAMPLES=30;
  static final List<Map<String,Object>> results=new ArrayList<>();
  static long gcCount(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionCount())).sum();}
  static long gcMillis(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();}
  static double percentile(List<Long> values,double percentile){var sorted=new ArrayList<>(values);Collections.sort(sorted);return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*percentile)-1))/1_000_000.0;}
  static String signature(TrafficBrowser.View view)throws Exception{
    MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(view.rows().toByteArray());
    digest.update((view.count()+":"+view.partial()+":"+view.matched()).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }
  static TrafficBrowser browser(DiskTrafficStore disk)throws Exception{
    var holder=new AtomicReference<TrafficBrowser>();
    edt(()->{var model=new CaptureTableModel(disk::ref);model.publish(Math.toIntExact(disk.size()));var table=new JTable(model);table.setRowSorter(new CaptureRowSorter(model,e->{throw e;}));holder.set(new TrafficBrowser(table,model,new JTextField(),null,()->disk,()->{}));});return holder.get();
  }
  static void measure(String name,DiskTrafficStore disk,TrafficQuery query,String text,int expected)throws Exception{
    var browser=browser(disk);List<Long> durations=new ArrayList<>(),cpuDurations=new ArrayList<>();AtomicLong heap=new AtomicLong();List<Long> edtDelays=Collections.synchronizedList(new ArrayList<>());
    var threads=ManagementFactory.getThreadMXBean();boolean cpuAvailable=threads.isCurrentThreadCpuTimeSupported()&&threads.isThreadCpuTimeEnabled();
    var monitor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"search-benchmark-monitor");t.setDaemon(true);return t;});
    AtomicBoolean pending=new AtomicBoolean();
    long gcBefore=gcCount(),gcTimeBefore=gcMillis();String fingerprint=null;
    monitor.scheduleAtFixedRate(()->{heap.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);if(pending.compareAndSet(false,true)){long submitted=System.nanoTime();SwingUtilities.invokeLater(()->{edtDelays.add(System.nanoTime()-submitted);pending.set(false);});}},0,10,TimeUnit.MILLISECONDS);
    try{
      for(int sample=-WARMUP;sample<SAMPLES;sample++){
        long cpuStarted=cpuAvailable?threads.getCurrentThreadCpuTime():0;long started=System.nanoTime();var view=browser.scan(disk,Math.toIntExact(disk.size()),query,text,List.of(),Set.of(),()->false);long elapsed=System.nanoTime()-started;long cpuElapsed=cpuAvailable?threads.getCurrentThreadCpuTime()-cpuStarted:0;
        if(view.matched()!=expected||view.rows().cardinality()!=expected)throw new AssertionError(name+" membership mismatch: "+view.matched());
        String current=signature(view);if(fingerprint!=null&&!fingerprint.equals(current))throw new AssertionError(name+" nondeterministic result");fingerprint=current;
        if(sample>=0){durations.add(elapsed);if(cpuAvailable)cpuDurations.add(cpuElapsed);}
      }
      edt(()->{});
      Map<String,Object> result=new LinkedHashMap<>();result.put("scenario",name);result.put("rows",disk.size());result.put("matchedRows",expected);result.put("warmup",WARMUP);result.put("samples",SAMPLES);result.put("resultSha256",fingerprint);result.put("searchP50Ms",percentile(durations,.50));result.put("searchP95Ms",percentile(durations,.95));result.put("searchSamplesMs",durations.stream().map(n->n/1_000_000.0).toList());result.put("threadCpuP50Ms",cpuDurations.isEmpty()?null:percentile(cpuDurations,.50));result.put("threadCpuP95Ms",cpuDurations.isEmpty()?null:percentile(cpuDurations,.95));result.put("sampledPeakHeapBytes",heap.get());result.put("gcCollections",gcCount()-gcBefore);result.put("gcCollectionTimeMs",gcMillis()-gcTimeBefore);result.put("edtQueueP95Ms",edtDelays.isEmpty()?null:percentile(edtDelays,.95));results.add(result);
    }finally{monitor.shutdownNow();edt(browser::close);}
  }
  public static void main(String[] args)throws Exception{
    installTrafficPacketFactory();
    try(var disk=new DiskTrafficStore()){
      var reply=response(200,"small body");
      for(int row=0;row<10000;row++)disk.append(request("https://benchmark.invalid/"+(row%2==0?"match/":"other/")+row,"GET",true,List.of()),reply);
      measure("metadata-literal-10000",disk,TrafficQuery.defaults(),"/match/",5000);
      var regex=new TrafficQuery("전체","전체",0,false,true,false,true,false,true,"",0);
      measure("metadata-regex-10000",disk,regex,"/match/[0-9]+",5000);
    }
    try(var disk=new DiskTrafficStore()){
      var request=request("https://benchmark.invalid/body","GET",true,List.of());
      var matching=response(200,"body-needle 한글 ".repeat(500));var other=response(200,"other-value 한글 ".repeat(500));
      for(int row=0;row<500;row++)disk.append(request,row%2==0?matching:other);
      measure("response-body-repeated-500",disk,new TrafficQuery("전체","전체",2,false,false,false,true,false,true,"",0),"body-needle",250);
    }
    Map<String,Object> report=new LinkedHashMap<>();report.put("java",System.getProperty("java.version"));report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());report.put("scope","Synthetic API adapters and production disk-backed full scans; EDT queue delay excludes rendering. Peak heap is sampled, not retained-memory measurement. GC time includes scenario warmup.");report.put("results",results);
    Path output=Path.of(System.getProperty("qa.output","search-benchmark.json"));Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,MetadataExport.json(report)+"\n",StandardCharsets.UTF_8);System.out.println(output);
  }
}
