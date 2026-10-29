package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpRequestResponse;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.table.*;

/** Real URL-import/collection presentation, exact original selection and partial-failure recovery. */
public final class PassiveImportBatchTest extends WorkbenchTest {
  static final String ONE="https://example.test/api/items/0?view=one";
  static final String TWO="https://example.test/api/items/0?view=two";
  static final class Counts extends TableRowSorter<DefaultTableModel> {
    int filters,events;
    Counts(DefaultTableModel model){super(model);}
    @Override public void setRowFilter(RowFilter<? super DefaultTableModel,? super Integer> filter){filters++;super.setRowFilter(filter);}
    void reset(){filters=events=0;}
  }
  static Counts count(PassiveScanPanel panel){
    Counts sorter=new Counts(panel.model);panel.table.setRowSorter(sorter);
    panel.model.addTableModelListener(event->sorter.events++);sorter.reset();return sorter;
  }
  static String thousand(){
    StringBuilder text=new StringBuilder(ONE+"\n"+TWO+"\n"+ONE+"\nhttps://outside.test/rejected\n");
    for(int i=2;i<1000;i++)text.append("https://example.test/api/items/").append(i).append("?view=detail\n");
    return text.toString();
  }
  static void select(PassiveScanPanel panel,String url){
    for(int row=0;row<panel.model.getRowCount();row++)if(url.equals(panel.model.getValueAt(row,1))){
      int view=panel.table.convertRowIndexToView(row);panel.table.setRowSelectionInterval(view,view);return;
    }
    throw new AssertionError("Missing URL "+url);
  }
  static MontoyaApi api(AtomicInteger sends){
    Http http=proxy(Http.class,(p,m,a)->{if(m.getName().equals("sendRequest")){sends.incrementAndGet();throw new AssertionError("Import emitted HTTP");}return def(m);});
    return proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));
  }
  public static void main(String[] args)throws Exception{
    BountyFeaturesTest.factory();AtomicInteger sends=new AtomicInteger();MontoyaApi api=api(sends);
    edt(()->{manualAndPrevious(api);collectionAndFilters(api);failureAndReentrancy(api);});
    check(sends.get()==0,"import, source merge, filter, selection and failure recovery emit no target request");
    System.out.println("PassiveImportBatchTest: "+checks+" checks passed");
  }
  static void manualAndPrevious(MontoyaApi api){
    PassiveScanPanel panel=new PassiveScanPanel(api);
    try{
      panel.target.setText("example.test");Counts counts=count(panel);
      panel.add(TWO,"Burp History GET");
      check(panel.table.getRowCount()==1&&counts.events==1&&counts.filters==1,
          "a single add still presents its row and filter immediately");
      var original=panel.items.get(TWO);String first=original.first;
      original.state="HTTP 200";original.verificationAttempted=true;original.lastVerification="2026-10-30T00:00:00Z";
      var source=proxy(HttpRequestResponse.class,(p,m,a)->def(m));
      var packet=new PassiveScanPanel.Packet("selected-request", "selected-response\nsecond line", "selected-response\nsecond line",source);
      panel.packets.put(TWO,packet);
      var sortKeys=List.of(new RowSorter.SortKey(1,SortOrder.DESCENDING));
      counts.setSortKeys(sortKeys);select(panel,TWO);panel.response.text.select(2,9);
      int caret=panel.response.text.getCaretPosition(),start=panel.response.text.getSelectionStart(),end=panel.response.text.getSelectionEnd();
      AtomicInteger callbacks=new AtomicInteger();AtomicBoolean callbackConsistent=new AtomicBoolean(true);
      panel.discovery=(url,tool)->{callbacks.incrementAndGet();if(!panel.items.containsKey(url)||panel.model.getRowCount()!=panel.items.size())callbackConsistent.set(false);};
      counts.reset();panel.importUrls(thousand());
      check(panel.model.getRowCount()==1000&&panel.table.getRowCount()==1000&&panel.items.size()==1000,
          "1000 imported originals survive one presentation batch without duplicate rows");
      check(counts.events==1&&counts.filters==1,"one paste produces one table notification and one filter installation");
      check(callbacks.get()==1000&&callbackConsistent.get(),"source callbacks remain synchronous, see live originals and deduplicate each URL/source");
      check(panel.items.containsKey(ONE)&&panel.items.containsKey(TWO)&&!panel.items.containsKey("https://outside.test/rejected"),
          "query values remain separate and an unrelated host stays excluded");
      check(panel.items.get(TWO)==original&&original.first.equals(first)&&original.state.equals("HTTP 200")
          &&original.verificationAttempted&&original.lastVerification.equals("2026-10-30T00:00:00Z"),
          "merging discovery preserves the existing original, first observation and explicit GET result");
      check(original.sources.equals(new LinkedHashSet<>(List.of("Burp History GET","가져오기")))
          &&original.observedMethods.equals(Set.of("GET"))&&original.inferredMethods.isEmpty(),
          "manual import cannot replace a directly observed method or invent inferred evidence");
      check(counts.getSortKeys().equals(sortKeys)&&TWO.equals(panel.selected())&&panel.packets.get(TWO)==packet,
          "descending sorting retains the exact selected URL and original packet reference");
      check(panel.response.text.getCaretPosition()==caret&&panel.response.text.getSelectionStart()==start&&panel.response.text.getSelectionEnd()==end,
          "an unchanged selected packet keeps the reader's text position and selection");
      check(panel.captureEvidence.isEnabled()&&panel.request.text.getText().contains("selected-request"),
          "the selected packet remains available to the existing evidence action");
      counts.reset();callbacks.set(0);panel.importPrevious(thousand());
      check(counts.events==1&&counts.filters==1&&callbacks.get()==1000,
          "previous DB merge batches presentation while retaining each new provenance callback");
      check(panel.previous.size()==1000&&panel.items.values().stream().allMatch(item->item.sources.contains("이전 DB")),
          "previous DB provenance is attached to every admitted exact original");
      boolean allPrevious=true;for(int row=0;row<panel.model.getRowCount();row++)allPrevious&="이전".equals(panel.model.getValueAt(row,8));
      check(allPrevious&&TWO.equals(panel.selected())&&panel.packets.get(TWO)==packet,
          "previous labels, selection and packet ownership survive the merged update");
      panel.add("https://example.test/overflow","가져오기");panel.add(TWO,"OpenAPI POST");
      check(panel.items.size()==1000&&!panel.items.containsKey("https://example.test/overflow")&&original.inferredMethods.equals(Set.of("POST")),
          "the 1000-row bound still permits a new source for an already retained URL");
      panel.search.setText("view=two");
      check(panel.visible().equals(List.of(TWO))&&TWO.equals(panel.selected()),"query filtering maps to the exact retained original after batching");
      check(panel.model.getColumnCount()==10&&!panel.model.isCellEditable(0,0),"the existing ten-column read-only model contract remains");
    }finally{panel.close();}
  }
  static void collectionAndFilters(MontoyaApi api){
    PassiveScanPanel panel=new PassiveScanPanel(api);
    try{
      panel.target.setText("example.test");Counts counts=count(panel);AtomicInteger callbacks=new AtomicInteger();
      panel.discovery=(url,source)->callbacks.incrementAndGet();
      List<PassiveScanPanel.Update> updates=new ArrayList<>();
      for(int i=0;i<1000;i++)updates.add(new PassiveScanPanel.Update("https://example.test/api/"+i,"Burp History GET",null,null));
      updates.add(new PassiveScanPanel.Update("https://example.test/api/0","OpenAPI POST",null,null));
      updates.add(new PassiveScanPanel.Update("https://example.test/api/0","OpenAPI POST",null,null));
      panel.acceptCollected(updates);
      check(counts.events==1&&counts.filters==1&&panel.table.getRowCount()==1000,
          "one delivered collection chunk presents all admitted results in one table/filter update");
      var item=panel.items.get("https://example.test/api/0");
      check(callbacks.get()==1001&&item.observedMethods.equals(Set.of("GET"))&&item.inferredMethods.equals(Set.of("POST")),
          "collection chunks preserve all unique source callbacks and observed/inferred method separation");
      panel.representatives.setSelected(true);panel.hideStatic.setSelected(true);panel.search.setText("/api/");
      Set<String> before=new LinkedHashSet<>(panel.visible());counts.reset();panel.acceptCollected(updates);
      check(new LinkedHashSet<>(panel.visible()).equals(before)&&panel.items.size()==1000&&callbacks.get()==1001,
          "repeated collection preserves representative filters and does not manufacture source callbacks");
      check(counts.filters==1,"an already-filtered collection chunk installs the final filter once");
      panel.search.setText("missing");counts.reset();panel.acceptCollected(List.of(new PassiveScanPanel.Update("https://example.test/api/1","gau",null,null)));
      check(panel.table.getRowCount()==0&&panel.selected()==null&&counts.filters==1,
          "an active empty-result filter stays empty after a source-only update");
      panel.close();panel.acceptCollected(updates);
      check(panel.items.isEmpty()&&panel.model.getRowCount()==0,"late collection delivery cannot repopulate a closed target");
    }finally{panel.close();}
  }
  static void failureAndReentrancy(MontoyaApi api){
    PassiveScanPanel panel=new PassiveScanPanel(api);
    try{
      panel.target.setText("example.test");Counts counts=count(panel);AtomicInteger callbacks=new AtomicInteger();
      panel.discovery=(url,source)->{if(callbacks.incrementAndGet()==2)throw new IllegalStateException("callback fixture failure");};
      boolean failed=false;try{panel.importUrls(ONE+"\n"+TWO+"\nhttps://example.test/not-yet");}catch(IllegalStateException expected){failed=true;}
      check(failed&&panel.items.size()==2&&panel.table.getRowCount()==2&&counts.filters==1&&counts.events==1,
          "a failed callback flushes admitted partial results instead of leaving a stale table");
      panel.discovery=(url,source)->{};counts.reset();panel.add("https://example.test/recovered","gau");
      check(panel.table.getRowCount()==3&&counts.events==1&&counts.filters==1&&panel.urlBatchDepth==0,
          "the next single add works immediately after a batch callback fails");
      counts.reset();AtomicInteger nested=new AtomicInteger();panel.discovery=(url,source)->{
        if(nested.getAndIncrement()==0)panel.importUrls("https://example.test/nested");
      };
      panel.importUrls("https://example.test/outer");
      check(panel.items.containsKey("https://example.test/outer")&&panel.items.containsKey("https://example.test/nested")
          &&counts.filters==1&&counts.events==1&&panel.urlBatchDepth==0,
          "a reentrant discovery callback retains both originals and completes one outer presentation batch");
    }finally{panel.close();}
  }
}
