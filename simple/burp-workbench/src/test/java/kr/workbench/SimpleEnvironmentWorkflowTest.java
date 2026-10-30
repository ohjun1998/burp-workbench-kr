package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.TableRowSorter;

/** Daily choices and exact local reading survive background metadata; no native Burp or HTTP. */
public final class SimpleEnvironmentWorkflowTest extends WorkbenchTest {
  static final String BASE="https://environment.test",URL=BASE+"/selected";
  static final class Events implements DocumentListener{
    int count;public void insertUpdate(DocumentEvent e){count++;}public void removeUpdate(DocumentEvent e){count++;}public void changedUpdate(DocumentEvent e){count++;}
  }
  static EnvironmentPanel.Observation observation(EnvironmentPanel panel,String origin,String body){
    var request=ScopeInspectionFixture.wireRequest(origin+"/observed","GET",List.of(),new byte[0]);
    var response=ScopeInspectionFixture.wireResponse(200,List.of(header("Server","nginx"),header("Content-Type","text/html")),body.getBytes(StandardCharsets.UTF_8));
    return panel.prepareObservation(request,response);
  }
  static void select(EnvironmentPanel panel,String url){for(int i=0;i<panel.candidates.size();i++)if(panel.candidates.get(i).url().equals(url)){int view=panel.pathTable.convertRowIndexToView(i);panel.pathTable.setRowSelectionInterval(view,view);return;}throw new AssertionError("Missing candidate");}
  static void pinReading(EnvironmentPanel panel){panel.requestView.select(2,10);panel.responseView.select(12,23);}
  static boolean reading(EnvironmentPanel panel){return panel.requestView.getSelectionStart()==2&&panel.requestView.getSelectionEnd()==10&&panel.responseView.getSelectionStart()==12&&panel.responseView.getSelectionEnd()==23;}
  static EnvironmentPanel.Packet packet(String label){String text="HTTP/1.1 200\r\nContent-Type: application/json\r\n\r\n{\"value\":\""+label+"\"}";return new EnvironmentPanel.Packet(URL,"GET /selected HTTP/1.1\r\nHost: environment.test\r\n\r\n",text,"HTTP 200",text);}
  public static void main(String[]args)throws Exception{
    ScopeInspectionFixture.installFactory();AtomicInteger sent=new AtomicInteger();
    Http http=proxy(Http.class,(p,m,a)->{if(m.getName().equals("sendRequest")){sent.incrementAndGet();throw new AssertionError("Unexpected target HTTP");}return def(m);});
    MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));
    edt(()->{EnvironmentPanel panel=new EnvironmentPanel(api);try{dailyChoices(panel);readingAndRows(panel);sourceInvalidation(panel);lifecycle(panel);}catch(Exception error){throw new RuntimeException(error);}finally{panel.close();}});
    check(sent.get()==0,"daily choices, metadata, original review and recovery issue no automatic target HTTP");
    System.out.println("SimpleEnvironmentWorkflowTest: "+checks+" checks passed");
  }
  static void dailyChoices(EnvironmentPanel panel)throws Exception{
    check(SwingUtilities.isDescendingFrom(panel.origins,panel)&&SwingUtilities.isDescendingFrom(panel.account,panel),"observed target and verification account are both available on the main workspace");
    check(panel.origins.getName().equals("wb.environment.origin")&&panel.account.getName().equals("wb.environment.account"),"daily selectors have stable accessible component names");
    AtomicReference<Component> content=new AtomicReference<>();TargetWindow target=(TargetWindow)get(panel,"targetSettings");target.opener=(o,t,c,m,x)->{content.set(c);return null;};target.open();
    check(content.get()!=null&&SwingUtilities.isDescendingFrom(panel.origins,panel),"opening SCOPE target settings cannot move the daily origin selector out of the workspace");
    check(UiLayoutTest.named(content.get(),"wb.target.scope.choices")!=null,"target management retains the current registered-SCOPE picker");
    check(SwingUtilities.isDescendingFrom((Component)get(panel,"targetSummary"),(java.awt.Container)content.get()),"SCOPE management retains its read-only current-target summary");
    Component options=panel.settingsContent();
    check(SwingUtilities.isDescendingFrom(panel.account,panel)&&!SwingUtilities.isDescendingFrom(panel.account,(java.awt.Container)options),"advanced settings cannot steal or duplicate the daily account selector");
    check(SwingUtilities.isDescendingFrom(panel.budget,(java.awt.Container)options)&&SwingUtilities.isDescendingFrom(panel.volatileFields,(java.awt.Container)options),"request budgets and comparison rules remain in advanced settings");
  }
  static void readingAndRows(EnvironmentPanel panel){
    var same=observation(panel,BASE,"<a href='/selected'>Observed</a>");panel.applyObservation(same);
    var original=packet("original-marker");panel.packets.put(URL,original);panel.packetAccounts.put(URL,"anonymous");select(panel,URL);panel.showSelected();pinReading(panel);
    Events request=new Events(),response=new Events();panel.requestView.getDocument().addDocumentListener(request);panel.responseView.getDocument().addDocumentListener(response);
    AtomicInteger pathEvents=new AtomicInteger(),techEvents=new AtomicInteger(),structures=new AtomicInteger();
    panel.paths.addTableModelListener(e->{pathEvents.incrementAndGet();if(e.getFirstRow()==TableModelEvent.HEADER_ROW)structures.incrementAndGet();});panel.tech.addTableModelListener(e->{techEvents.incrementAndGet();if(e.getFirstRow()==TableModelEvent.HEADER_ROW)structures.incrementAndGet();});
    for(int i=0;i<20;i++)panel.applyObservation(same);
    check(reading(panel)&&request.count==0&&response.count==0,"identical background metadata leaves the reader's request/response selection and documents untouched");
    check(pathEvents.get()==0&&techEvents.get()==0,"identical metadata emits no repeated table rebuilds");
    var other=observation(panel,"https://other-environment.test","<a href='/elsewhere'>Observed elsewhere</a>");panel.applyObservation(other);
    check(reading(panel)&&request.count==0&&response.count==0&&pathEvents.get()==0&&techEvents.get()==0,"another origin's observation does not rebuild or replace the active result");
    check(panel.catalog.sites.containsKey("https://other-environment.test")&&panel.origins.getItemCount()==2,"background origin discovery remains available for explicit selection");
    var sortKeys=List.of(new RowSorter.SortKey(0,SortOrder.DESCENDING));panel.pathTable.getRowSorter().setSortKeys(sortKeys);
    var firstColumn=panel.pathTable.getColumnModel().getColumn(0);firstColumn.setPreferredWidth(231);select(panel,URL);pinReading(panel);pathEvents.set(0);techEvents.set(0);
    panel.applyObservation(observation(panel,BASE,"<a href='/new-candidate'>New candidate</a>"));
    check(pathEvents.get()==1&&techEvents.get()==0&&structures.get()==0,"changed candidate rows produce one ordinary data event without rebuilding unchanged technology rows");
    check(panel.pathTable.getColumnModel().getColumn(0)==firstColumn&&firstColumn.getPreferredWidth()==231&&panel.pathTable.getRowSorter().getSortKeys().equals(sortKeys),"data replacement preserves column order, sizing and sort keys");
    check(URL.equals(panel.selectedUrl())&&panel.currentPacket(URL)==original&&reading(panel)&&request.count==0&&response.count==0,"a new candidate preserves the exact sorted original and its active reading position");
    pathEvents.set(0);techEvents.set(0);panel.refresh();
    check(pathEvents.get()==0&&techEvents.get()==0&&reading(panel),"explicit refresh of unchanged rows also preserves local reading");
    panel.requestView.getDocument().removeDocumentListener(request);panel.responseView.getDocument().removeDocumentListener(response);
  }
  static void sourceInvalidation(EnvironmentPanel panel){
    panel.packets.put(URL,packet("replacement-marker"));panel.showSelected();
    check(panel.responseView.getText().contains("replacement-marker")&&!panel.responseView.getText().contains("original-marker"),"a new packet instance at the same exact URL replaces the prior result");
    panel.formatted.doClick(0);
    check(panel.responseView.getText().equals(panel.currentPacket(URL).response()),"raw/structured mode changes invalidate the preview and show the requested raw packet");
    panel.formatted.doClick(0);check(panel.responseView.getText().contains("\"value\": \"replacement-marker\""),"structured mode can be restored without replacing stored packet bytes");
    panel.account.setSelectedIndex(1);
    check(panel.currentPacket(URL)==null&&!panel.responseView.getText().contains("replacement-marker"),"switching verification accounts cannot display the previous account's packet");
    AtomicReference<CookieSessionProbe.Config> config=new AtomicReference<>(new CookieSessionProbe.Config(BASE+"/identity","$.id","first"));panel.sessionConfig=slot->config.get();panel.refresh();
    var accountPacket=packet("account-marker");panel.packets.put(URL,accountPacket);panel.packetAccounts.put(URL,panel.accountKey());panel.refresh();
    check(panel.responseView.getText().contains("account-marker"),"the selected account's own original can be reviewed at the same URL");
    config.set(new CookieSessionProbe.Config(BASE+"/identity","$.id","second"));
    panel.applyObservation(observation(panel,BASE,"<a href='/selected'>Observed</a>"));
    check(panel.currentPacket(URL)==null&&!panel.responseView.getText().contains("account-marker")
        &&panel.paths.getValueAt(panel.pathTable.convertRowIndexToModel(panel.pathTable.getSelectedRow()),3).toString().contains("미검증"),
        "identical selected-origin metadata detects changed identity conditions and clears the former account packet and status");
    var nextAccountPacket=packet("next-account-marker");panel.packets.put(URL,nextAccountPacket);panel.packetAccounts.put(URL,panel.accountKey());panel.refresh();
    check(panel.responseView.getText().contains("next-account-marker"),"the replacement identity conditions can display their own original");
    config.set(new CookieSessionProbe.Config(BASE+"/identity","$.id","third"));
    panel.applyObservation(observation(panel,"https://other-environment.test","<a href='/elsewhere'>Observed elsewhere</a>"));
    check(panel.currentPacket(URL)==null&&!panel.responseView.getText().contains("next-account-marker")
        &&panel.paths.getValueAt(panel.pathTable.convertRowIndexToModel(panel.pathTable.getSelectedRow()),3).toString().contains("미검증"),
        "another origin's metadata also invalidates externally changed account conditions without manual refresh");
    panel.packets.remove(URL);panel.packetAccounts.remove(URL);panel.account.setSelectedIndex(0);panel.project.add(URL,"anonymous",200,"JSON","");panel.refresh();
    check(panel.responseView.getText().contains("저장된 판정 · HTTP 200")&&!panel.responseView.getText().contains("account-marker"),"metadata-only results show the saved account-specific verdict without inventing an original packet");
    panel.project.add(URL,"anonymous",404,"JSON","");panel.refresh();check(panel.responseView.getText().contains("저장된 판정 · HTTP 404"),"a changed saved verdict invalidates the preview even without a current packet");
    panel.prefix.setText("//invalid-origin");panel.refresh();check(panel.candidates.isEmpty()&&panel.paths.getRowCount()==0&&panel.selectedUrl()==null&&!panel.responseView.getText().contains("HTTP 404"),"invalid candidate settings remove obsolete rows and cached verdicts");
    panel.prefix.setText("");panel.refresh();select(panel,URL);check(panel.responseView.getText().contains("저장된 판정 · HTTP 404"),"correcting settings recovers the current saved result without another request");
  }
  static void lifecycle(EnvironmentPanel panel)throws Exception{
    var stale=observation(panel,BASE,"<a href='/stale'>Stale</a>");panel.clearAnalysis();panel.applyObservation(stale);
    check(panel.catalog.sites.isEmpty()&&panel.paths.getRowCount()==0&&panel.tech.getRowCount()==0&&panel.selectedUrl()==null,"clear discards both cached presentation and late metadata from the prior generation");
    check(get(panel,"previewPacket")==null,"clearing results releases the cached original packet reference");
    panel.applyObservation(observation(panel,BASE,"<a href='/fresh'>Fresh</a>"));check(panel.candidates.stream().anyMatch(c->c.url().equals(BASE+"/fresh")),"new-generation metadata works after clear without stale-source suppression");
    var late=observation(panel,BASE,"<a href='/late'>Late</a>");panel.close();panel.applyObservation(late);
    check(panel.catalog.sites.isEmpty()&&panel.packets.isEmpty()&&panel.paths.getRowCount()==0&&get(panel,"previewPacket")==null,"close releases original/cache state and rejects later metadata completions");
  }
}
