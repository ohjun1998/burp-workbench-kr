package kr.workbench;

import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/** Grouped views preserve source rows, original handoffs and the existing dashboard conditions. */
public final class SimpleGroupedScopeResultsTest extends WorkbenchTest {
  private static ScopeInspection.Finding finding(String category,String title,DiskTrafficStore.PacketRef original) {
    return new ScopeInspection.Finding(category,title,original.url(),"GET","비로그인","환경 단서","INFO",
        "관측","원래 응답의 관측 근거",original.id(),3,10,"same-framework");
  }

  private static JTree navigation(Component panel,String name) {
    var tree=SimpleWorkflowTest.named(panel,name+".tree");
    if(!(tree instanceof JTree result))throw new AssertionError("Missing grouped navigation: "+name+".tree");
    return result;
  }

  private static TreePath titled(JTree tree,String title) {
    var root=(DefaultMutableTreeNode)tree.getModel().getRoot();
    var nodes=root.depthFirstEnumeration();
    while(nodes.hasMoreElements()) {
      var node=(DefaultMutableTreeNode)nodes.nextElement();
      if(title.equals(node.toString()))return new TreePath(node.getPath());
    }
    throw new AssertionError("Missing navigation item: "+title);
  }

  private static String rendered(JTable table,int row,int modelColumn) {
    int view=table.convertColumnIndexToView(modelColumn);
    Component renderer=table.prepareRenderer(table.getCellRenderer(row,view),row,view);
    return renderer instanceof JLabel label?label.getText():"";
  }

  private static void candidateGroups(Component panel,String name,JTable table) {
    JTree tree=navigation(panel,name);
    for(String category:List.of("account","assets","docs")) {
      var parent=(DefaultMutableTreeNode)titled(tree,ResultLabels.groupLabel(category)).getLastPathComponent();
      check(parent.getChildCount()==1,"API candidates have a distinct function leaf in "+category+" group");
      var leaf=(DefaultMutableTreeNode)parent.getChildAt(0);
      tree.setSelectionPath(new TreePath(leaf.getPath()));
      check(table.getRowCount()==1&&table.getModel().getRowCount()==3,
          "selecting an API "+category+" function filters one observation without removing source rows");
    }
    tree.setSelectionPath(titled(tree,"전체 관측"));
    check(table.getRowCount()==3,"API group navigation restores all three original observations");
  }

  private static void settle() throws Exception { for(int i=0;i<5;i++)edt(()->{}); }

  public static void main(String[] args) throws Exception {
    try(var fixture=new ScopeInspectionFixture();var store=new DiskTrafficStore()) {
      var response=ScopeInspectionFixture.wireResponse(200,List.of(header("Content-Type","application/json")),
          "{\"framework\":\"example\"}".getBytes(StandardCharsets.UTF_8));
      var originalA=store.append("grouped-scope","비로그인",
          ScopeInspectionFixture.wireRequest(fixture.origin+"/login","GET",List.of(),new byte[0]),response);
      var originalB=store.append("grouped-scope","비로그인",
          ScopeInspectionFixture.wireRequest(fixture.origin+"/dashboard","GET",List.of(),new byte[0]),response);
      byte[] requestBefore=originalA.requestPrefix(Integer.MAX_VALUE),responseBefore=originalA.responsePrefix(Integer.MAX_VALUE);
      var findings=List.of(finding("ENVIRONMENT","프레임워크 · Flask / Werkzeug",originalA),
          finding("ENVIRONMENT","프레임워크 · Flask / Werkzeug",originalB),
          finding("API","JS/API 경로 후보",originalA),
          new ScopeInspection.Finding("API","JS/API 경로 후보",fixture.origin+"/static/style.css","GET","비로그인",
              "발견 후보","INFO","관측","정적 리소스 후보","",0,-1,"stylesheet"),
          new ScopeInspection.Finding("INVENTORY","명세 경로 후보",fixture.origin+"/api/docs/","GET","비로그인",
              "발견 후보","INFO","관측","문서 경로 후보","",0,-1,"api-docs"));
      var pages=List.of(new ScopeInspection.Page(originalA.url(),"GET","비로그인",200,"JSON",true,"","a",originalA),
          new ScopeInspection.Page(originalB.url(),"GET","비로그인",200,"JSON",true,"","b",originalB));
      var snapshot=new ScopeInspection.Snapshot("grouped-scope",ScopeInspection.State.READY,"준비",0,2,0,0,0,0,
          findings,pages,List.of(),0,0);
      var handoff=new AtomicReference<String>();
      var dashboardHolder=new AtomicReference<ScopeDashboardPanel>();
      var scopeHolder=new AtomicReference<ScopeInspectionPanel>();
      edt(()->{
        var dashboard=new ScopeDashboardPanel(new ScopeDashboardPanel.Actions(){
          public void inspect(){} public void original(String id){handoff.set(id);}
          public void repeater(String id){} public void evidence(String id){}
        });
        dashboardHolder.set(dashboard);dashboard.update(snapshot,20,fixture.origin);
        dashboard.cards.get(ScopeInspectionDashboard.Group.ENVIRONMENT).doClick(0);
        var scope=new ScopeInspectionPanel(fixture.api,
            ()->List.of(new ScopeInspection.Profile("anonymous","비로그인",Map.of(),null)),
            new ScopeInspectionPanel.Actions(){
              public void original(DiskTrafficStore.PacketRef ref){} public void repeater(DiskTrafficStore.PacketRef ref){}
              public void compare(DiskTrafficStore.PacketRef ref){}
              public void evidence(List<DiskTrafficStore.PacketRef> refs,String note){}
              public void tool(String id,DiskTrafficStore.PacketRef ref){}
            });
        scopeHolder.set(scope);scope.accept(snapshot);
      });
      var dashboard=dashboardHolder.get();var scope=scopeHolder.get();
      try {
        settle();
        edt(()->{
          check(dashboard.model.getRowCount()==2&&dashboard.visibleRows.size()==2,
              "grouped dashboard retains both distinct framework observations");
          JTree tree=navigation(dashboard,"wb.scope.dashboard.grouped");
          TreePath framework=titled(tree,"프레임워크 · Flask / Werkzeug");
          Component renderer=tree.getCellRenderer().getTreeCellRendererComponent(tree,framework.getLastPathComponent(),false,false,true,0,false);
          check(renderer.getAccessibleContext().getAccessibleName().contains("2건"),
              "repeated framework observations share one two-observation navigation leaf");
          tree.setSelectionPath(framework);
          check(dashboard.table.getRowCount()==2&&dashboard.model.getRowCount()==2,
              "framework group filtering preserves both original table records");
          int selected=dashboard.table.convertRowIndexToView(1);dashboard.table.setRowSelectionInterval(selected,selected);
          check(dashboard.selectedRow().originalId().equals(originalB.id())&&dashboard.detail.getText().contains(originalB.url()),
              "group selection keeps the exact selected original and URL in dashboard details");
          dashboard.original.doClick(0);
          check(originalB.id().equals(handoff.get()),"original action delegates the actual selected source ID after grouping");
          check(rendered(dashboard.table,0,6).equals(ResultLabels.shortName(originalA.url()))
                  &&dashboard.model.getValueAt(0,6).equals(originalA.url()),
              "dashboard renders a short function label while preserving the original URL model cell");
          dashboard.cards.get(ScopeInspectionDashboard.Group.API).doClick(0);
        });
        settle();
        edt(()->{
          check(dashboard.table.getRowCount()==3&&dashboard.visibleRows.size()==3,
              "switching cards clears an unavailable subgroup instead of hiding the new API results");
          candidateGroups(dashboard,"wb.scope.dashboard.grouped",dashboard.table);
          scope.pages.setSelectedIndex(ScopeInspectionUiTest.indexOf(scope.pages,"api"));
          candidateGroups(scope,"wb.scope.inspection.grouped.api",scope.tables.get("api"));
          JTree tree=navigation(scope,"wb.scope.inspection.grouped.environment");
          tree.setSelectionPath(titled(tree,"프레임워크 · Flask / Werkzeug"));
          scope.pages.setSelectedIndex(ScopeInspectionUiTest.indexOf(scope.pages,"environment"));
          JTable table=scope.tables.get("environment");
          table.getRowSorter().toggleSortOrder(6);
          int row=table.convertRowIndexToView(1);table.setRowSelectionInterval(row,row);
          check(table.getModel().getRowCount()==2&&scope.selectedOriginal().id().equals(originalB.id()),
              "sorted grouped inspection findings retain their exact original packet");
          check(scope.detail.getText().contains(originalB.url())&&scope.detail.getText().contains(originalB.id()),
              "inspection selection exposes exact URL and original ID in details");
          check(rendered(table,row,6).equals(ResultLabels.shortName(originalB.url()))
                  &&table.getModel().getValueAt(1,6).equals(originalB.url()),
              "inspection URL cells are shortened only in the renderer");
          for(String key:scope.tables.keySet())check(SimpleWorkflowTest.named(scope,"wb.scope.inspection.grouped."+key+".groups")!=null,
              "every inspection observation tab uses grouped navigation: "+key);
          check(Arrays.equals(requestBefore,originalA.requestPrefix(Integer.MAX_VALUE))
                  &&Arrays.equals(responseBefore,originalA.responsePrefix(Integer.MAX_VALUE)),
              "grouping leaves full original request and response bytes unchanged");
          check(fixture.visits.isEmpty(),"grouping, selection and original delegation send no target HTTP");
        });
      } finally {edt(()->{scope.close();dashboard.close();});}
    }
    System.out.println("SimpleGroupedScopeResultsTest: "+checks+" checks passed");
  }
}
