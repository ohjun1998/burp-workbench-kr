package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.*;

/** Real research/detail presentation keeps selection, source facts and explicit-action boundaries. */
public final class SimpleResearchDesignTest extends WorkbenchTest {
  private static Component named(Component root,String id){Component value=SimpleWorkflowTest.named(root,id);if(value==null)throw new AssertionError(id);return value;}
  private static void layout(JPanel root){for(int pass=0;pass<8;pass++)UiLayoutTest.layout(root);}
  public static void main(String[] args)throws Exception{
    BountyFeaturesTest.factory();BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));
    WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");JPanel root=(JPanel)get(extension,"root");ExposureResearchPanel exposure=(ExposureResearchPanel)get(extension,"exposureResearch");ScopeDashboardPanel dashboard=(ScopeDashboardPanel)get(extension,"scopeDashboard");int before=PayloadIntegrationTest.sent.get();
    try{
      edt(()->{
        root.setSize(1600,1000);extension.openFeature("exposure");exposure.target.setText("research.example.test");exposure.importUrls("https://research.example.test/swagger/index.html\nhttps://research.example.test/api-docs");exposure.exposureTable.setRowSelectionInterval(1,1);layout(root);
      });
      edt(()->{}); // Let the grouped view publish the imported records before inspecting its disclosure.
      edt(()->{
        var selectedSource=exposure.selectedExposure();String selected=selectedSource.url();var model=exposure.exposureTable.getModel();var document=exposure.exposureDetail.getDocument();
        String originalUrl=exposure.exposureTable.getValueAt(0,1).toString();
        var cell=exposure.exposureTable.getCellRenderer(0,1).getTableCellRendererComponent(exposure.exposureTable,exposure.exposureTable.getValueAt(0,1),false,false,0,1);
        check(cell instanceof JLabel label&&label.getText().equals(ResultLabels.shortName(originalUrl)),"the approved grouped URL column displays its short function name");
        check(model.getValueAt(exposure.exposureTable.convertRowIndexToModel(0),1).equals(originalUrl)&&exposure.selectedExposure()==selectedSource,"short labels retain the exact full URL and selected source identity");
        var groupedOriginal=(JToggleButton)named(root,"wb.exposure.search.grouped.original.toggle");groupedOriginal.doClick(0);layout(root);
        var groupedReader=(JTextArea)named(root,"wb.exposure.search.grouped.original");
        check(groupedOriginal.isSelected()&&groupedReader.isVisible()&&groupedReader.getText().contains(selected)&&groupedReader.getText().contains("관측 ID: exposure:"+selectedSource.category()+"|"+selected),"the grouped original disclosure exposes the full selected URL and stable source ID");
        groupedOriginal.doClick(0);layout(root);
        for(int i=0;i<4;i++)WorkbenchUi.style(root);layout(root);
        check(exposure.exposureTable.getModel()==model&&exposure.exposureDetail.getDocument()==document&&exposure.selectedExposure().url().equals(selected),"research restyling preserves the selected source, table model and copied-evidence document");
        check(exposure.exposureRepeater.getIcon()==null,"restyling does not reintroduce the unapproved play icon on selected URL preparation");
        Insets in=exposure.subdomains.getInsets();int required=exposure.subdomains.getFontMetrics(exposure.subdomains.getFont()).stringWidth(exposure.subdomains.getText())+exposure.subdomains.getIcon().getIconWidth()+exposure.subdomains.getIconTextGap()+in.left+in.right;
        check(exposure.subdomains.getWidth()>=required,"the actual subdomain checkbox retains its complete caption at the standard viewport");
        check(named(root,"wb.approved.exposure.selection.state").getWidth()>300&&named(root,"wb.approved.exposure.discovery.column").isVisible(),"selected URL status and fact column use the available detail width");
        var original=(JToggleButton)named(root,"wb.pdf.exposure.discovery.original.toggle");original.doClick(0);layout(root);check(original.isSelected()&&exposure.exposureDetail.isVisible(),"the retained discovery disclosure still opens the original copied-evidence reader");original.doClick(0);
        var product=SimpleExposureResearchTest.p("1.22.1");var candidate=ExposureResearch.parseNvd(SimpleExposureResearchTest.fixture());exposure.display(new ExposureResearch.Batch(candidate,Set.of(),Map.of(),Instant.now(),"fixture","fixture",false),product,false);exposure.tabs.setSelectedIndex(1);exposure.cveTable.setRowSelectionInterval(0,0);layout(root);
        var factsDocument=exposure.affectedDetail.getDocument();String source=exposure.cveNote();var edit=(JToggleButton)named(root,"wb.exposure.product.edit");edit.doClick(0);layout(root);check(edit.isSelected()&&exposure.vendor.isVisible(),"the inline product edit keeps the original CPE correction fields reachable");edit.doClick(0);
        Insets editInsets=edit.getInsets();check(edit.getWidth()>=edit.getFontMetrics(edit.getFont()).stringWidth(edit.getText())+editInsets.left+editInsets.right,"the real 53px product edit caption fits without an ellipsis");
        exposure.unknown.setSelected(false);check(!named(root,"wb.approved.exposure.unknown.conditions").getParent().isVisible(),"the candidate heading reflects the real unknown-condition filter instead of a fixed design example");exposure.unknown.setSelected(true);exposure.cveTable.setRowSelectionInterval(0,0);
        for(int i=0;i<3;i++)WorkbenchUi.style(root);layout(root);
        JLabel unknown=(JLabel)named(root,"wb.approved.exposure.unknown.conditions");var picture=new java.awt.image.BufferedImage(unknown.getWidth(),unknown.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);Graphics2D graphics=picture.createGraphics();unknown.paint(graphics);graphics.dispose();int edgeInk=0;for(int x=0;x<picture.getWidth();x++)for(int y:new int[]{0,1,unknown.getHeight()-2,unknown.getHeight()-1})if(picture.getRGB(x,y)==WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL).getRGB())edgeInk++;check(edgeInk==0,"the actual 22px condition badge paints one centered line without glyphs clipped at its top or bottom");
        check(exposure.affectedDetail.getDocument()==factsDocument&&exposure.cveNote().equals(source),"CVE callout styling preserves applicability, source identifiers and copied full evidence");
        extension.openFeature("dashboard");layout(root);var summaryDocument=dashboard.detailSummary.getDocument();dashboard.detailSummary.setText("Actual selected evidence remains authoritative.\nLong details remain readable without a nested native border.");layout(root);
        check(dashboard.detailSummary.getDocument()==summaryDocument&&dashboard.detailSummary.getParent().getName().equals("wb.approved.dashboard.detail.column"),"the dashboard paragraph uses the actual summary document in the padded reading column");
        check(dashboard.detailSummary.getBorder()==null&&dashboard.original.getIcon()!=null,"the real dashboard paragraph is borderless and its original-source action retains a visible icon");
        for(var metric:dashboard.cards.values())check(metric.getClientProperty("wb.approved.metric.caption") instanceof String&&!metric.getText().isBlank(),"dashboard metric display aliases retain their canonical model caption and live count");
        var provenance=(JToggleButton)named(root,"wb.scope.dashboard.detail.provenance.toggle");provenance.doClick(0);layout(root);check(provenance.isSelected()&&dashboard.detailOrigin.isVisible(),"the retained dashboard provenance disclosure still reveals its full source document");provenance.doClick(0);
      });
      edt(()->{});edt(()->{
        JLabel count=(JLabel)named(root,"wb.approved.exposure.imported.count");check(count.getText().equals("가져온 URL · "+exposure.exposureTable.getRowCount()+"개"),"the imported URL heading follows the actual retained table count");
        check(PayloadIntegrationTest.sent.get()==before,"research layout, product corrections, filters and disclosures send no automatic target HTTP");
      });
    }finally{SimpleWorkflowTest.unload();}
    System.out.println("SimpleResearchDesignTest: "+checks+" checks passed (actual research/detail controls; zero automatic HTTP)");
  }
}
