package kr.workbench;

import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** PDF reading structures retain their real selected data and never start a lookup or GET. */
public final class SimplePdfResearchLayoutTest extends WorkbenchTest {
  static Component named(Component root,String name){
    Component value=SimpleWorkflowTest.named(root,name);
    if(value==null)throw new AssertionError("Missing PDF research component: "+name);
    return value;
  }
  static boolean displayed(Component value,Component root){
    for(Component at=value;at!=null;at=at.getParent()){
      if(!at.isVisible())return false;
      if(at==root)return true;
    }return false;
  }
  public static void main(String[] args)throws Exception{
    ScopeInspectionFixture.installFactory();
    jsReading();exposureReading();environmentReading();responseReading();
    System.out.println("SimplePdfResearchLayoutTest: "+checks+" checks passed");
  }
  static void jsReading()throws Exception{
    AtomicReference<JsAnalysisPanel> ref=new AtomicReference<>();
    edt(()->ref.set(new JsAnalysisPanel(null,()->{})));
    JsAnalysisPanel panel=ref.get();String raw="// authored 한글\nfetch('/pdf/documents');\n";
    try{
      edt(()->{
        String html="<html><img src='http://127.0.0.1:9/should-not-load'>source 한글</html>";
        panel.resultView.summary.setText(html);panel.sourceCallLocation.setText(html);
        check(panel.resultView.summary.getText().equals(html)&&panel.sourceCallLocation.getText().equals(html)
            &&panel.resultView.summary.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey)==null
            &&panel.sourceCallLocation.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey)==null,
            "selected JS route and source labels display HTML-shaped source data literally without HTML/image views");
      });
      edt(()->panel.load(raw,"https://pdf.test/assets/documents.js"));
      JsAnalysisPanelTest.waitDone(panel);
      edt(()->{
        panel.urlTable.setRowSelectionInterval(0,0);
        check(panel.urlTable.getColumnCount()==4&&panel.urlTable.convertColumnIndexToModel(0)==1
            &&panel.urlTable.convertColumnIndexToModel(1)==0&&panel.urlTable.convertColumnIndexToModel(2)==7
            &&panel.urlTable.convertColumnIndexToModel(3)==6,"PDF route table shows method, path, actual state and call location without changing the full source model");
        check(panel.endpoints.getColumnCount()==9&&panel.resultView.detail.getText().contains("변경"),"existing change metadata remains in the full keyed model and selected analysis details");
        check(panel.resultView.summary.getText().contains("/pdf/documents"),"PDF selected-route context names the actual selected endpoint");
        JLabel location=(JLabel)named(panel,"wb.pdf.js.call.location");
        check(location.getText().contains("documents.js:")&&location.getToolTipText().startsWith("https://pdf.test/"),"call-source card keeps the real source location and full source tooltip");
        check(SwingUtilities.isDescendingFrom(panel.input,(Container)named(panel,"wb.pdf.js.call.source")),"actual source editor is retained inside the call-source card");
        String before=panel.input.getText();
        JToggleButton navigation=(JToggleButton)named(panel,"wb.workflow.js.navigation.toggle");
        navigation.doClick(0);navigation.doClick(0);
        JToggleButton facts=(JToggleButton)named(panel,"wb.workflow.js.evidence.toggle");
        facts.doClick(0);facts.doClick(0);
        check(before.equals(panel.input.getText())&&before.contains("authored 한글"),"reading and folding source navigation preserve authored source bytes");
        panel.urlTable.clearSelection();
        check(!panel.resolve.isEnabled()&&location.getText().contains("선택"),"clearing a path removes stale call location and disables request preparation");
      });
    }finally{edt(panel::close);}
  }
  static void exposureReading()throws Exception{
    AtomicInteger calls=new AtomicInteger();
    VulnerabilityClient client=new VulnerabilityClient(uri->{calls.incrementAndGet();throw new AssertionError("Unexpected provider request");});
    edt(()->{
      ExposureResearchPanel panel=new ExposureResearchPanel(null,url->url.startsWith("https://example.test/"),client);
      try{
        String html="<html><img src='http://127.0.0.1:9/should-not-load'>untrusted 한글</html>";
        JLabel firstValue=new JLabel();ExposureResearchPanel.setFact(firstValue,html);
        check(firstValue.getText().equals(html)&&Boolean.TRUE.equals(firstValue.getClientProperty("html.disable"))
            &&firstValue.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey)==null,
            "the first external fact value stays literal and creates no HTML/image view");
        check(!javax.swing.plaf.basic.BasicHTML.isHTMLString(firstValue.getToolTipText())&&firstValue.getToolTipText().contains(html),
            "HTML-shaped fact text remains available in a literal tooltip without an image-loading markup prefix");
        panel.target.setText("example.test");panel.importUrls("https://example.test/z\nhttps://example.test/a");
        panel.exposureTable.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1,SortOrder.ASCENDING)));
        panel.exposureTable.setRowSelectionInterval(0,0);
        check(panel.discoveryUrl.getText().equals(panel.selectedExposure().url())&&panel.discoveryUrl.getText().endsWith("/a"),"structured URL facts follow the exact sorted selected record");
        String original=panel.exposureDetail.getText();
        check(original.contains("https://example.test/a")&&original.contains(panel.selectedExposure().query()),"folded discovery text retains exact selected URL and search expression");
        JToggleButton discovery=(JToggleButton)named(panel,"wb.pdf.exposure.discovery.original.toggle");
        check(!discovery.isSelected()&&!displayed(panel.exposureDetail,panel),"long discovery original starts folded beneath the visible facts");
        discovery.doClick(0);
        check(displayed(panel.exposureDetail,panel)&&original.equals(panel.exposureDetail.getText()),"opening discovery original reveals the same retained reader without rewriting content");
        var candidate=ExposureResearch.parseNvd(SimpleExposureResearchTest.fixture()).get(0);
        var product=SimpleExposureResearchTest.p("1.22.1");
        var batch=new ExposureResearch.Batch(List.of(candidate),Set.of(),Map.of(),Instant.EPOCH,"미확인","미확인",false);
        panel.display(batch,product,false);panel.cveTable.setRowSelectionInterval(0,0);
        check(panel.cveScore.getText().contains("9.8")&&panel.cveVerdict.getText().equals(ExposureResearch.verdict(ExposureResearch.applicability(candidate,product))),"CVE score and version verdict reflect the selected real candidate model");
        check(panel.affectedDetail.getText().contains("1.18.0")&&panel.fixedDetail.getText().contains("공지 확인"),"vertical applicability facts preserve observed interval and unknown supplier fix state");
        String evidence=panel.evidence.getText();
        JToggleButton conditions=(JToggleButton)named(panel,"wb.pdf.exposure.cve.conditions.toggle");conditions.doClick(0);conditions.doClick(0);
        check(evidence.equals(panel.evidence.getText())&&evidence.contains("fixture-store:42"),"folding applicability explanation preserves immutable original evidence identity");
        panel.minimum.setValue(10.0);
        check(panel.cveTable.getRowCount()==0&&panel.cveScore.getText().equals("CVSS 미확인")&&panel.evidence.getText().isBlank(),"filtering out selection clears structured facts and original evidence together");
        check(calls.get()==0,"selection, sorting, facts and disclosure controls invoke no provider lookup");
      }finally{panel.close();}
    });
  }
  static void environmentReading()throws Exception{
    edt(()->{
      var scope=proxy(burp.api.montoya.scope.Scope.class,(p,m,a)->m.getName().equals("isInScope")?true:def(m));
      var api=proxy(burp.api.montoya.MontoyaApi.class,(p,m,a)->{
        if(m.getName().equals("scope"))return scope;
        if(m.getName().equals("http"))throw new AssertionError("Layout controls must not send target HTTP");
        return def(m);
      });
      EnvironmentPanel panel=new EnvironmentPanel(api);
      try{
        panel.applyObservation(SimpleEnvironmentWorkflowTest.observation(panel,SimpleEnvironmentWorkflowTest.BASE,"<a href='/selected'>selected</a>"));
        var packet=SimpleEnvironmentWorkflowTest.packet("PDF original 한글");
        panel.packets.put(SimpleEnvironmentWorkflowTest.URL,packet);panel.packetAccounts.put(SimpleEnvironmentWorkflowTest.URL,"anonymous");
        SimpleEnvironmentWorkflowTest.select(panel,SimpleEnvironmentWorkflowTest.URL);panel.showSelected();
        panel.setSize(1280,800);for(int pass=0;pass<6;pass++)UiLayoutTest.layout(panel);
        Component observations=named(panel,"wb.environment.observations");
        check(observations.getWidth()>panel.pathTable.getWidth(),"observed-environment disclosure spans the whole workspace above both readers");
        check(panel.selectedPath.getText().equals(SimpleEnvironmentWorkflowTest.URL)&&panel.currentPacket(SimpleEnvironmentWorkflowTest.URL)==packet,"selected path card still owns the exact current-account packet");
        JToggleButton show=(JToggleButton)named(panel,"wb.environment.observations.toggle");show.doClick(0);show.doClick(0);
        check(panel.currentPacket(SimpleEnvironmentWorkflowTest.URL)==packet&&panel.responseView.getText().contains("PDF original 한글"),"folding observations retains selected response and original bytes");
        panel.project.add(SimpleEnvironmentWorkflowTest.BASE+"/health","anonymous",404,"JSON","");panel.refresh();
        int all=panel.pathTable.getRowCount();panel.responseCandidates.doClick(0);
        check(panel.pathTable.getRowCount()==2&&SimpleEnvironmentWorkflowTest.URL.equals(panel.selectedUrl())
            &&panel.currentPacket(SimpleEnvironmentWorkflowTest.URL)==packet,"response quick view includes real packet and saved HTTP metadata while retaining exact selection");
        panel.pathTable.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0,SortOrder.DESCENDING)));
        JTextField search=(JTextField)named(panel,"wb.environment.search");search.setText("selected");
        check(panel.pathTable.getRowCount()==1&&SimpleEnvironmentWorkflowTest.URL.equals(panel.selectedUrl()),"literal search composes with response quick view after URL sorting");
        search.setText("[");
        check(panel.pathTable.getRowCount()==0&&panel.selectedUrl()==null&&!panel.responseView.getText().contains("PDF original 한글"),"excluding a selected result clears its reader without choosing a different original");
        search.setText("");panel.unverifiedCandidates.doClick(0);
        check(panel.pathTable.getRowCount()==all-2&&panel.selectedUrl()==null&&panel.currentPacket(SimpleEnvironmentWorkflowTest.URL)==packet,
            "unverified quick view excludes recorded responses without deleting their original packets");
        panel.allCandidates.doClick(0);
        check(panel.pathTable.getRowCount()==all&&panel.selectedUrl()==null,"returning to all candidates restores rows without automatically selecting or sending a request");
      }finally{panel.close();}
    });
  }
  static void responseReading()throws Exception{
    edt(()->{
      ResponseReconPanel panel=new ResponseReconPanel(ResponseBudgetTest.api(new AtomicBoolean(true)));
      try{
        panel.findings.addRow(new Object[]{"PDF observation","https://pdf.test/exact","Body: 17","KEEP <literal> 한글",1});
        panel.findingTable.setRowSelectionInterval(0,0);panel.showFinding();
        JTabbedPane tabs=(JTabbedPane)named(panel,"wb.response.finding.pages");
        check(tabs.getTabCount()==4&&tabs.getTitleAt(3).equals("분석 근거"),"original comparison card exposes response, request, affected URLs and analysis evidence");
        String exact=panel.findingDetail.getText();tabs.setSelectedIndex(3);
        check(displayed(panel.findingDetail,panel)&&exact.contains("KEEP <literal> 한글"),"analysis evidence tab retains literal selected observation text");
        tabs.setSelectedIndex(0);
        check(SwingUtilities.isDescendingFrom(panel.findingResponse,(Container)named(panel,"wb.card.pdf.response.reader"))&&panel.findingDetail.getText().equals(exact),"returning to actual packet reader leaves evidence unchanged");
      }finally{panel.close();}
    });
  }
}
