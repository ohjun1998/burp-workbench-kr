package kr.workbench;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Identical deterministic fixture and operations against either release's JsArchive. */
public final class JsArchiveBenchmark {
  public static void main(String[] args)throws Exception{
    Path directory=Path.of(args[1]);int count=Integer.parseInt(args[2]);
    var archive=new JsArchive(directory);archive.load();
    if(args[0].equals("seed")){
      for(int n=0;n<count;n++){
        String key="https://benchmark.test/bundle-"+n+".js";
        var result=new JsAnalysis.Result(List.of(),List.of(),n%5==0?"partial":"complete",false);
        archive.save(key,"const item="+n+";","https://benchmark.test/","application/javascript",result,null,JsMiner.hash("fixture-"+n));
      }
      System.out.println("seeded "+archive.page(0,"").total());return;
    }
    int samples=30,pages=(count+199)/200;
    if(archive.page(0,"").total()!=count)throw new AssertionError("fixture count");
    long coldStart=System.nanoTime();var cold=archive.page(0,"bundle-");long coldNs=System.nanoTime()-coldStart;
    if(cold.total()!=count)throw new AssertionError("cold total");
    List<Long> search=new ArrayList<>(),sequential=new ArrayList<>();long checksum=0,maxHeap=0;
    long gcBefore=gcTime();
    for(int iteration=-5;iteration<samples;iteration++){
      int offset=Math.floorMod(iteration*997,pages)*200;
      long start=System.nanoTime();var page=archive.page(offset,"bundle-");long elapsed=System.nanoTime()-start;
      if(page.total()!=count||page.rows().size()!=Math.min(200,count-offset))throw new AssertionError("search page size");
      for(int i=0;i<page.rows().size();i++)if(!page.rows().get(i).key().equals("https://benchmark.test/bundle-"+(count-1-offset-i)+".js"))throw new AssertionError("search order");
      if(iteration>=0){search.add(elapsed);checksum+=page.rows().get(0).key().hashCode();}
      maxHeap=Math.max(maxHeap,usedHeap());
    }
    for(int iteration=0;iteration<3;iteration++)for(int offset=0;offset<count;offset+=200){
      long start=System.nanoTime();var page=archive.page(offset,"");long elapsed=System.nanoTime()-start;
      if(page.total()!=count||!page.rows().get(0).key().equals("https://benchmark.test/bundle-"+(count-1-offset)+".js"))throw new AssertionError("unfiltered order");
      if(iteration>0)sequential.add(elapsed);maxHeap=Math.max(maxHeap,usedHeap());
    }
    Map<String,Object> output=new LinkedHashMap<>();
    output.put("records",count);output.put("searchSamples",search.size());output.put("pageSize",200);
    output.put("coldSearchMs",coldNs/1e6);output.put("searchP50Ms",percentile(search,.5));output.put("searchP95Ms",percentile(search,.95));
    output.put("sequentialPageP50Ms",percentile(sequential,.5));output.put("sequentialPageP95Ms",percentile(sequential,.95));
    output.put("sampledPeakHeapBytes",maxHeap);output.put("gcCollectionMs",gcTime()-gcBefore);output.put("checksum",checksum);
    output.put("java",System.getProperty("java.version"));output.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
    System.out.println(MetadataExport.json(output));
  }
  static long usedHeap(){var runtime=Runtime.getRuntime();return runtime.totalMemory()-runtime.freeMemory();}
  static long gcTime(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x->Math.max(0,x.getCollectionTime())).sum();}
  static double percentile(List<Long> values,double fraction){var sorted=new ArrayList<>(values);Collections.sort(sorted);return sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(sorted.size()*fraction)-1))/1e6;}
}
