package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.*;
import burp.api.montoya.repeater.Repeater;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import static kr.workbench.WorkbenchTest.*;

/** User-facing actions on production Swing components; all packet data are synthetic. */
public final class JsResponseWorkflowTest {
  static int checks;
  static void verify(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
  static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
  static boolean contains(Component root,String text){
    if(root instanceof AbstractButton b&&text.equals(b.getText()))return true;
    if(root instanceof Container c)for(Component child:c.getComponents())if(contains(child,text))return true;
    return false;
  }
  static void doubleClick(JTable table){
    table.setSize(900,300);
    table.dispatchEvent(new MouseEvent(table,MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,3,3,2,false,MouseEvent.BUTTON1));
  }
  static HttpResponse response(String type,String body){
    String wire="HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\n\r\n"+body;
    return proxy(HttpResponse.class,(p,m,a)->switch(m.getName()){
      case "headerValue"->"Content-Type".equals(a[0])?type:null;
      case "body"->bytes(body.getBytes(StandardCharsets.UTF_8));
      case "toByteArray"->bytes(wire.getBytes(StandardCharsets.UTF_8));
      case "toString"->wire;case "statusCode"->(short)200;default->def(m);
    });
  }
  public static void main(String[] args)throws Exception{
    AtomicInteger repeated=new AtomicInteger(),sent=new AtomicInteger(),opened=new AtomicInteger();
    AtomicReference<HttpRequest> draft=new AtomicReference<>();
    var repeater=proxy(Repeater.class,(p,m,a)->{if(m.getName().equals("sendToRepeater")){repeated.incrementAndGet();draft.set((HttpRequest)a[0]);}return def(m);});
    var http=proxy(burp.api.montoya.http.Http.class,(p,m,a)->{if(m.getName().equals("sendRequest"))sent.incrementAndGet();return def(m);});
    var api=proxy(MontoyaApi.class,(p,m,a)->switch(m.getName()){case "repeater"->repeater;case "http"->http;case "scope"->proxy(burp.api.montoya.scope.Scope.class,(q,n,b)->n.getName().equals("isInScope")?b[0].toString().startsWith("https://allowed.test/"):def(n));default->def(m);});
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->m.getReturnType()==HttpRequest.class&&a.length==1&&a[0] instanceof String url?ReviewWorkflowTest.draftRequest(url,"GET","",List.of()):def(m));
    AtomicReference<JsAnalysisPanel> ref=new AtomicReference<>();edt(()->ref.set(new JsAnalysisPanel(api,()->{})));var panel=ref.get();
    AtomicReference<Component> listContent=new AtomicReference<>(),packetContent=new AtomicReference<>();
    edt(()->{
      panel.analysisWindow.opener=(owner,title,content,modal,closed)->{verify(!modal,"analysis list opens modelessly");listContent.set(content);opened.incrementAndGet();return null;};
      panel.packetWindow.opener=(owner,title,content,modal,closed)->{verify(!modal,"source packets open modelessly");packetContent.set(content);opened.incrementAndGet();return null;};
      panel.repeater.opener=(owner,title,content,modal,closed)->{verify(!modal,"Repeater opens modelessly");return null;};
      for(String removed:List.of("수동 분석","선택 응답 가져오기","수집 파일 저장","선택 URL → Repeater","최근 분석"))verify(!contains(panel,removed),"removed inline action: "+removed);
      verify(!SwingUtilities.isDescendingFrom(panel.historyTable,panel)&&SwingUtilities.isDescendingFrom(panel.sources,panel),"analysis history stays in its window while source choice is inline");
      verify(!SwingUtilities.isDescendingFrom(panel.requestPacket,panel)&&!SwingUtilities.isDescendingFrom(panel.responsePacket,panel),"main workspace reserves its area for source and results rather than packets");
      verify(UiLayoutTest.named(panel,"wb.js.findings")!=null&&panel.results.getTabCount()==7,"seven finding tabs remain while analysis details open separately");
      verify(panel.historyTable.getAutoResizeMode()==JTable.AUTO_RESIZE_OFF&&panel.historyTable.getColumnModel().getColumn(0).getPreferredWidth()>=380,"analysis list preserves enough source URL width and allows horizontal scrolling");
      verify(!panel.input.isEditable(),"source is a read-only navigation view after manual analysis is removed");
      verify(panel.passive.getText().equals("분석 ON")&&panel.passive.getBackground().getGreen()>panel.passive.getBackground().getRed(),"analysis ON is a green toggle");
      panel.passive.doClick(0);WorkbenchUi.style(panel);
      verify(panel.passive.getText().equals("분석 OFF")&&panel.passive.getBackground().getRed()>panel.passive.getBackground().getGreen(),"analysis OFF stays red after theme styling");
      verify(!panel.offer(request("https://allowed.test/off.xml","GET",true,List.of()),response("application/xml","<url>/off</url>")),"OFF blocks automatic analysis for non-JavaScript responses");
      panel.passive.doClick(0);
      panel.rememberPacket("https://allowed.test/a.js","REQUEST_SOURCE_A","RESPONSE_SOURCE_A","RESPONSE_SOURCE_A");
      panel.load("fetch('/z');fetch('/a');fetch('/dynamic/'+id);const apiKey='ghp_"+"A1b2C3d4".repeat(5)+"';require('@org/widget');const cloud='https://bucket.s3.amazonaws.com/object';//# sourceMappingURL=a.js.map","https://allowed.test/a.js");
    });
    JsAnalysisPanelTest.waitDone(panel);
    AtomicReference<Component> firstPacket=new AtomicReference<>();
    edt(()->{
      verify(panel.urlTable.getRowCount()==3&&panel.urlTable.getRowSorter().getViewRowCount()==3,"all APIs including unresolved paths are visible by default");
      KeyStroke key=KeyStroke.getKeyStroke(KeyEvent.VK_R,InputEvent.CTRL_DOWN_MASK);
      Object binding=panel.urlTable.getInputMap(JComponent.WHEN_FOCUSED).get(key);
      verify("wb.js.repeater".equals(binding)&&panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(key).equals(binding),"Ctrl+R is bound in the focused table and the JS workspace");
      panel.urlTable.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0,SortOrder.ASCENDING)));
      panel.urlTable.setRowSelectionInterval(0,0);
      panel.urlTable.getActionMap().get(binding).actionPerformed(new ActionEvent(panel.urlTable,0,""));
      verify(repeated.get()==0&&JsAnalysisPanelTest.wire(panel,0).startsWith("GET /a HTTP/1.1"),"Ctrl+R opens the sorted selected endpoint in its own Repeater window");
      doubleClick(panel.urlTable);firstPacket.set(packetContent.get());
      verify(((JTextArea)UiLayoutTest.named(firstPacket.get(),"wb.js.packet.request.text")).getText().equals("REQUEST_SOURCE_A"),"double-click shows the request of the finding source without fabricating a candidate request");
      verify(((JTextArea)UiLayoutTest.named(firstPacket.get(),"wb.js.packet.response.text")).getText().equals("RESPONSE_SOURCE_A"),"double-click shows the matching captured response");
      panel.results.setSelectedIndex(1);doubleClick(panel.secretTable);
      verify(!panel.toRepeater.isEnabled()&&packetContent.get()!=null,"secret findings open packets without enabling a stale endpoint shortcut");
      for(int tab=2;tab<7;tab++){
        var table=(JTable)((JScrollPane)panel.results.getComponentAt(tab)).getViewport().getView();
        if(table.getRowCount()==0){var model=(DefaultTableModel)table.getModel();model.addRow(new Object[model.getColumnCount()]);}
        panel.results.setSelectedIndex(tab);int before=opened.get();doubleClick(table);
        verify(opened.get()==before+1,"double-click opens the source packet from result tab "+tab);
      }
      panel.analysisList.doClick(0);
      verify(listContent.get()!=null&&SwingUtilities.isDescendingFrom(panel.historyTable,(Container)listContent.get()),"analysis list button mounts the real sortable history table in its window");
      panel.rememberPacket("https://allowed.test/b.js","REQUEST_SOURCE_B","RESPONSE_SOURCE_B","RESPONSE_SOURCE_B");
      panel.load("fetch('/second');","https://allowed.test/b.js");
    });
    JsAnalysisPanelTest.waitDone(panel);
    edt(()->{
      verify(((JTextArea)UiLayoutTest.named(firstPacket.get(),"wb.js.packet.response.text")).getText().equals("RESPONSE_SOURCE_A"),"an already opened packet snapshot does not change when background/current source changes");
      panel.historyTable.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0,SortOrder.DESCENDING)));
      doubleClick(panel.historyTable);
      verify(panel.maskedReport().contains("/second")&&((JTextArea)UiLayoutTest.named(packetContent.get(),"wb.js.packet.response.text")).getText().equals("RESPONSE_SOURCE_B"),"sorted history selection resolves the right analysis and source packet");
      panel.offer(request("https://allowed.test/data","GET",true,List.of()),response("application/json","{\"url\":\"/json-api\"}"));
      panel.offer(request("https://allowed.test/config","GET",true,List.of()),response("application/xml","<config><endpoint>/xml-api</endpoint></config>"));
      panel.offer(request("https://allowed.test/plain","GET",true,List.of()),response("text/plain","endpoint=/plain-api"));
      panel.offer(request("https://allowed.test/html","GET",true,List.of()),response("text/html","<html><a href='/html-without-script'>link</a></html>"));
    });
    JsAnalysisPanelTest.waitDone(panel);
    edt(()->{
      for(String[] item:new String[][]{{"data","/json-api"},{"config","/xml-api"},{"plain","/plain-api"},{"html","/html-without-script"}}){
        panel.sources.setSelectedItem("https://allowed.test/"+item[0]);verify(panel.maskedReport().contains(item[1]),"automatic History pipeline analyzes "+item[0]+" responses");
      }
      panel.sources.setSelectedItem("https://allowed.test/data");panel.results.setSelectedIndex(0);panel.urlTable.setRowSelectionInterval(0,0);
      AtomicReference<Component> confirm=new AtomicReference<>();
      panel.resolveWindow.opener=(owner,title,content,modal,closed)->{confirm.set(content);return null;};
      panel.prepareRepeater();
      verify(confirm.get()==null&&repeated.get()==0&&JsAnalysisPanelTest.wire(panel,1).startsWith("GET /json-api"),"Ctrl+R opens an unknown method as default GET without transmitting");
      panel.resolve.doClick(0);
      panel.resolvedMethod.setSelectedItem("POST");((JButton)UiLayoutTest.named(confirm.get(),"wb.js.draft.prepare")).doClick(0);
      verify(repeated.get()==0&&JsAnalysisPanelTest.wire(panel,2).startsWith("POST /json-api HTTP/1.1"),"explicit method confirmation opens the chosen data endpoint without sending it");
      long processed=panel.processed.get();
      panel.offer(request("https://allowed.test/a.png","GET",true,List.of()),response("image/png","https://example.test/should-not-be-scanned"));
      verify(panel.processed.get()==processed&&panel.unsupported.get()>0,"known binary media do not enter the analysis queue");
    });
    CountDownLatch gate=new CountDownLatch(1),entered=new CountDownLatch(1);
    var worker=(ThreadPoolExecutor)get(panel,"worker");worker.execute(()->{entered.countDown();try{gate.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});entered.await(5,TimeUnit.SECONDS);
    long before=panel.processed.get();
    edt(()->{
      panel.offer(request("https://allowed.test/queued","GET",true,List.of()),response("text/plain","endpoint=/queued-api"));
      panel.passive.doClick(0);
      verify(panel.queuedBytes()==0,"OFF releases queued response snapshots immediately");
    });gate.countDown();JsAnalysisPanelTest.waitDone(panel);
    verify(panel.processed.get()==before,"cancelled automatic work cannot restore results while analysis is OFF");
    edt(()->{
      panel.passive.doClick(0);
      panel.offer(request("https://allowed.test/resume","GET",true,List.of()),response("text/plain","endpoint=/resumed-api"));
    });JsAnalysisPanelTest.waitDone(panel);
    edt(()->{
      panel.sources.setSelectedItem("https://allowed.test/resume");verify(panel.maskedReport().contains("/resumed-api"),"ON resumes automatic text analysis");
      verify(sent.get()==0,"source selection, packet windows, analysis and Repeater preparation send no HTTP requests");
      panel.close();int count=opened.get();panel.analysisWindow.open();panel.packetWindow.open();
      verify(opened.get()==count&&panel.historyModel.getRowCount()==0,"unload clears analyses and prevents popup reopening");
    });
    System.out.println("JsResponseWorkflowTest: "+checks+" checks passed");
  }
}
