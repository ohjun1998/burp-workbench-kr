package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Exercises the composed extension, including the late attached payload and embedded Mapper. */
public final class SimpleApprovedAllToolsTest extends WorkbenchTest {
  static Component named(Component root,String id){Component c=SimpleWorkflowTest.named(root,id);if(c==null)throw new AssertionError("Missing approved control: "+id);return c;}
  static void layout(Component root){for(int pass=0;pass<6;pass++)UiLayoutTest.layout((Container)root);}
  static Rectangle bounds(Component root,Component c){return SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);}
  static void select(Component root,String route){((FolderNavigation)named(root,"wb.navigation")).navigate(route);}
  static void click(Component root,String id){((AbstractButton)named(root,id)).doClick(0);}
  static boolean visible(Component root,Component c){for(Component p=c;p!=null;p=p.getParent()){if(!p.isVisible())return false;if(p==root)return true;}return false;}
  public static void main(String[] args)throws Exception{
    BountyFeaturesTest.factory();BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));
    WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");JPanel root=(JPanel)get(extension,"root");int sent=PayloadIntegrationTest.sent.get();
    try{
      edt(()->{
        root.setSize(1600,1000);layout(root);
        for(String route:List.of("traffic","mapper","js","environment","exposure","auth","response.recon","payload","data","activity","bounty","dashboard")){
          select(root,route);layout(root);
          JTree nav=(JTree)named(root,"wb.navigation.tree");check(bounds(root,nav).x<190,"approved 190px navigation remains available on "+route);
          check(root.getWidth()==1600,"navigation retains the production root on "+route);
        }
        JsAnalysisPanel js=(JsAnalysisPanel)SimpleWorkflowTest.unchecked(extension,"jsAnalysis");
        select(root,"js");layout(root);
        check(visible(root,named(root,"wb.workflow.js.types")),"JS analysis and filter menu has a visible invoker");
        JPanel navigation=(JPanel)named(root,"wb.workflow.js.navigation");Component navBody=ApprovedLayouts.region(navigation,BorderLayout.CENTER);
        JButton evidence=(JButton)named(root,"wb.approved.js.evidence.open");JMenuItem navItem=(JMenuItem)named(evidence.getComponentPopupMenu(),"wb.workflow.js.navigation.toggle.menu");
        navItem.doClick(0);layout(root);check(navBody.isVisible()&&visible(root,navBody),"source location navigation is reachable through the actual analysis menu");navItem.doClick(0);
        check(js.urlTable.getModel().getColumnCount()==9&&js.urlTable.getColumnCount()==4,"JS display columns retain all original metadata");
        select(root,"exposure");ExposureResearchPanel exposure=(ExposureResearchPanel)SimpleWorkflowTest.unchecked(extension,"exposureResearch");exposure.tabs.setSelectedIndex(1);layout(root);
        AbstractButton filter=null;for(Component c:ApprovedLayouts.all(exposure))if(c instanceof AbstractButton b&&"wb.flow.exposure.cve.filters.toggle".equals(b.getName()))filter=b;
        check(filter!=null&&visible(root,filter),"CVE filter remains visible beside the real summary chips");filter.doClick(0);layout(root);
        check(visible(root,exposure.minimum)&&visible(root,exposure.remote),"CVE expanded conditions expose the original retained controls");filter.doClick(0);
        check(exposure.cveTable.getModel().getColumnCount()==6&&exposure.cveTable.getColumnCount()==5,"CVE table preserves EPSS in its underlying model");
        EnvironmentPanel environment=(EnvironmentPanel)SimpleWorkflowTest.unchecked(extension,"environments");select(root,"environment");layout(root);
        check(environment.pathTable.convertColumnIndexToModel(2)==3,"environment status column uses actual scan status rather than diagnosis text");
        check(visible(root,named(root,"wb.approved.environment.packet.open")),"environment raw and formatted controls have a visible tools menu");
        var mapper=(kr.featuremapper.FeatureMapperWorkspace)SimpleWorkflowTest.unchecked(extension,"featureMapper");select(root,"mapper");layout(root);
        PacketInspector request=(PacketInspector)named(root,"wb.mapper.request"),response=(PacketInspector)named(root,"wb.mapper.response");
        var host=(kr.featuremapper.FeatureMapperWorkspace.EmbeddedHost)SimpleWorkflowTest.unchecked(mapper,"embeddedHost");byte[] q="GET /original HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1),s="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"id\":101}".getBytes(StandardCharsets.ISO_8859_1);host.selectedPacket(q,q.length,s,s.length);q[0]='X';
        check(request.previewBytes()[0]=='G'&&Arrays.equals(s,response.previewBytes()),"embedded viewers copy the real wire prefixes without retaining mutable caller bytes");
        click(root,"wb.mapper.response.raw");check(response.mode()==PacketInspector.Mode.RAW&&Arrays.equals(s,response.previewBytes()),"Mapper Raw switches the actual inspector while preserving wire bytes");click(root,"wb.mapper.response.pretty");
        AtomicInteger uiChanges=new AtomicInteger();request.tabs.get(0).addPropertyChangeListener("UI",e->uiChanges.incrementAndGet());for(int i=0;i<4;i++)WorkbenchUi.style(root);check(uiChanges.get()==0,"nested Mapper packet styling reuses its delegate instead of oscillating between owners");
        layout(root);check(bounds(root,request.getParent()).equals(new Rectangle(436,482,569,484)),"Mapper request card matches the approved full working rectangle");
        select(root,"payload");layout(root);check(Boolean.TRUE.equals(((JComponent)named(root,"wb.payload.step.prepare")).getClientProperty("wb.approved.step")),"late attached payload receives the approved step layout");
        check(PayloadIntegrationTest.sent.get()==sent,"all tool layout, menus, filters and modes send zero HTTP");
      });
    }finally{SimpleWorkflowTest.unload();}
    System.out.println("SimpleApprovedAllToolsTest: "+checks+" checks passed");
  }
}
