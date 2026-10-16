package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Real toolbar/dialog/error flows with API adapters; no native Burp or outgoing HTTP. */
public final class DiagnosticsWorkflowTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    Diagnostics log = new Diagnostics();
    RuntimeException secret = new IllegalStateException("Cookie: PRIVATE_PACKET_TOKEN",new java.io.IOException("PRIVATE_BODY"));
    log.record("디스크 저장",secret);
    check(log.text().contains("IllegalStateException → IOException")&&!log.text().contains("PRIVATE"),"error causes remain useful without retaining exception messages or HTTP data");
    check(log.text().contains("DiagnosticsWorkflowTest"),"diagnostics retain application code location");
    var workers=Executors.newFixedThreadPool(4);
    try { List<Callable<Void>> jobs=new ArrayList<>();for(int t=0;t<4;t++)jobs.add(()->{for(int i=0;i<300;i++)log.record("동시 오류",secret);return null;});for(var result:workers.invokeAll(jobs))result.get(); }
    finally {workers.shutdownNow();}
    check(log.size()==1000&&log.total()==1201&&log.discarded()==201,"concurrent diagnostics are bounded with exact overflow accounting");
    var before=log.snapshot();log.clear();check(before.size()==1000&&log.size()==0&&log.total()==1201,"clear releases records while cumulative error count remains accurate");
    log.close();log.record("종료 후",secret);check(log.size()==0,"closed diagnostics reject later records");

    WorkbenchExtension ext=new WorkbenchExtension();ext.initialize((MontoyaApi)PayloadIntegrationTest.host(MontoyaApi.class));edt(()->{});
    JPanel root=(JPanel)get(ext,"root");JTabbedPane pages=(JTabbedPane)get(ext,"tabs");
    Diagnostics diagnostics=(Diagnostics)get(ext,"diagnostics");
    List<Component> opened=new ArrayList<>();List<String> titles=new ArrayList<>();
    try {
      edt(()->{
        try {
          ext.dialogOpener=(owner,title,content,modal,closed)->{opened.add(content);titles.add(title);return null;};
          root.setSize(1280,800);for(int i=0;i<6;i++)UiLayoutTest.layout(root);
          JPanel actions=(JPanel)UiLayoutTest.named(root,"wb.navigation.actions");
          check(Arrays.stream(actions.getComponents()).map(c->((JButton)c).getText()).toList().equals(List.of("기능 안내","설정","로그")),"toolbar order is help, settings, logs");
          check(((BorderLayout)root.getLayout()).getLayoutComponent(BorderLayout.SOUTH)==null,"global footer no longer takes packet space");
          check(UiLayoutTest.named(root,"wb.traffic.title")==null,"large traffic title is removed");
          check(((JLabel)get(ext,"captureInfo")).getParent()==null&&((JLabel)get(ext,"selectionInfo")).getParent()==null,"traffic count and selected-packet summaries are absent from layout");
          Component search=UiLayoutTest.named(root,"wb.traffic.search");
          check(search.isVisible()&&search.getWidth()>200,"search remains visible at top with useful input width");
          int selected=pages.getSelectedIndex();JTextArea input=(JTextArea)get(ext,"codecIn");input.setText("KEEP_WORKING_INPUT");
          ((JButton)UiLayoutTest.named(root,"wb.navigation.logs")).doClick(0);
          check(titles.get(0).endsWith("로그")&&UiLayoutTest.named(opened.get(0),"diagnostics.table") instanceof JTable,"log button opens the actual error table");
          check(pages.getSelectedIndex()==selected&&input.getText().equals("KEEP_WORKING_INPUT"),"opening logs preserves current tool and input");
          BountySupportPanel support=(BountySupportPanel)get(ext,"bountySupport");
          support.action("오류 흐름 재현",()->{throw secret;}).doClick(0);
          check(diagnostics.size()==1&&diagnostics.text().contains("재현·증거 정리"),"handled tool errors reach the shared log");
          diagnostics.refresh();JTable table=(JTable)UiLayoutTest.named(opened.get(0),"diagnostics.table");
          check(table.getRowCount()==1,"open log view refreshes after a new error");
          table.setRowSelectionInterval(0,0);
          check(((JTextArea)UiLayoutTest.named(opened.get(0),"diagnostics.details")).getText().contains("IOException"),"selecting an error shows its safe cause and code details");
          ((JButton)UiLayoutTest.named(opened.get(0),"diagnostics.clear")).doClick(0);
          check(table.getRowCount()==0&&diagnostics.text().isEmpty(),"clear button removes rows and copied diagnostic text");
          ((JButton)UiLayoutTest.named(root,"wb.navigation.logs")).doClick(0);
          check(opened.get(0)==opened.get(1),"reopening logs reuses its content and timer");
          for(int i=0;i<pages.getTabCount();i++) {
            pages.setSelectedIndex(i);UiLayoutTest.layout(root);
            if(!"설정".equals(pages.getTitleAt(i))){
              boolean visible=true;
              for(Component action:actions.getComponents()){
                Rectangle bounds=SwingUtilities.convertRectangle(action.getParent(),action.getBounds(),root);
                visible&=new Rectangle(0,0,root.getWidth(),root.getHeight()).contains(bounds);
                for(Container parent=action.getParent();parent!=null&&parent!=root;parent=parent.getParent())visible&=SwingUtilities.convertRectangle(parent,new Rectangle(0,0,parent.getWidth(),parent.getHeight()),root).contains(bounds);
              }
              check(visible,"all toolbar buttons stay fully visible on tool "+i);
            }
          }
        } catch(Exception e) {throw new IllegalStateException(e);}
      });
      check(PayloadIntegrationTest.sent.get()==0,"layout, help and diagnostic actions send no HTTP requests");
    } finally {for(var unload:PayloadIntegrationTest.modernUnloads)unload.extensionUnloaded();edt(()->{});}
    check(diagnostics.size()==0,"unload clears extension diagnostic data");
    System.out.println("DiagnosticsWorkflowTest: "+checks+" checks passed");
  }
}
