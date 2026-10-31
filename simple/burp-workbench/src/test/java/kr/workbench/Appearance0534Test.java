package kr.workbench;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Durable metadata restoration, schema limits, project isolation and actual Swing display settings. */
public final class Appearance0534Test extends WorkbenchTest {
  static final String A="11111111-1111-1111-1111-111111111111", B="22222222-2222-2222-2222-222222222222";
  static final class Memory implements WorkspaceAppearance.Storage {
    String project=A; boolean fail, discard; final Map<String,String> saved=new HashMap<>(); int writes;
    public String project(){return project;}
    public String read(){return saved.get(project);}
    public void write(String value){if(fail)throw new IllegalStateException("full"); writes++;if(!discard)saved.put(project,value);}
  }
  record View(JPanel root,JTable table,JSplitPane split,JTextField text,JPanel nativeEditor){}
  static View view(){
    JTable table=new JTable(new DefaultTableModel(new Object[][]{{"PACKET_SECRET_VALUE", "B", "C"}},new Object[]{"ID","상태","메모"}));
    table.setName("wb.test.traffic");table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    JTextField text=new JTextField("UNSAVED_CREDENTIAL_VALUE");
    JPanel nativeEditor=new JPanel();nativeEditor.putClientProperty("wb.nativeEditor",true);nativeEditor.setFont(new Font(Font.DIALOG,Font.PLAIN,11));
    JPanel right=new JPanel(new BorderLayout());right.add(text);right.add(nativeEditor,BorderLayout.SOUTH);
    JSplitPane split=WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(table),right,.5);split.setName("wb.test.workspace");
    JPanel root=new JPanel(new BorderLayout());root.setName("appearance.test.root");root.add(split);root.setSize(1024,720);
    return new View(root,table,split,text,nativeEditor);
  }
  static void reject(Runnable action,String message){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,message);}
  public static void main(String[] args)throws Exception{
    var defaults=WorkspaceAppearance.defaults(A,true);
    check(WorkspaceAppearance.decode(WorkspaceAppearance.encode(defaults)).equals(defaults),"default metadata round trips");
    var columns=new WorkspaceAppearance.Columns("0".repeat(24),List.of(1,0),List.of(120,200));
    var rich=new WorkspaceAppearance.State(A,125,true,false,Map.of("t-"+"1".repeat(24),columns),Map.of("s-"+"2".repeat(24)+"-0",.37));
    check(WorkspaceAppearance.decode(WorkspaceAppearance.encode(rich)).equals(rich),"column ordering widths and split proportions round trip");
    reject(()->WorkspaceAppearance.defaults("project-name",true),"project identity must be exact UUID");
    reject(()->defaults.display(200,false,true),"unbounded font scales rejected");
    reject(()->new WorkspaceAppearance.Columns("0".repeat(24),List.of(0,0),List.of(100,100)),"duplicate model indices rejected");
    reject(()->new WorkspaceAppearance.Columns("0".repeat(24),List.of(0),List.of(9000)),"oversized column width rejected");
    reject(()->new WorkspaceAppearance.State(A,100,false,true,Map.of(),Map.of("secret",.5)),"surface IDs contain no user data");
    reject(()->new WorkspaceAppearance.State(A,100,false,true,Map.of(),Map.of("s-"+"2".repeat(24),Double.NaN)),"non-finite ratio rejected");
    reject(()->WorkspaceAppearance.decode(WorkspaceAppearance.encode(defaults).replace("\"schema\":1","\"schema\":2")),"future schema preserved through rejection");
    reject(()->WorkspaceAppearance.decode("x".repeat(WorkspaceAppearance.MAX_JSON+1)),"oversized metadata rejected before parsing");
    reject(()->WorkspaceAppearance.decode(WorkspaceAppearance.encode(defaults).replace("\"scale\":100","\"scale\":100.5")),"fractional scale rejected");
    var subset=new WorkspaceAppearance.Columns("0".repeat(24),List.of(0,1,2,3,7,8,9,10),List.of(56,75,230,65,45,110,55,130));
    check(subset.order().get(7)==10,"visible subset retains full model indices above its visible column count");
    reject(()->new WorkspaceAppearance.Columns("0".repeat(24),List.of(64),List.of(100)),"visible model index remains bounded to supported full schemas");
    Memory memory=new Memory();
    edt(()->{
      View first=view();WorkspaceAppearance appearance=new WorkspaceAppearance(memory,first.root,true,null);
      PacketInspector packet=new PacketInspector("appearance.packet");
      first.root.add(packet,BorderLayout.SOUTH);packet.setPacket("HTTP/1.1 200 OK\r\n\r\n{\"value\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8),null);
      byte[] wire=packet.previewBytes();
      appearance.display(150,true,true);
      check(javax.swing.text.StyleConstants.getFontSize(packet.text.getStyledDocument().getCharacterElement(0).getAttributes())==21,"actual styled packet characters scale to 21px");
      check(Arrays.equals(wire,packet.previewBytes()),"display preference preserves exact packet preview bytes");
      check(!ApprovedWorkflowTestSupport.visible(packet.search,packet),"packet search starts folded at the selected display scale");
      packet.getActionMap().get("find").actionPerformed(new java.awt.event.ActionEvent(packet,0,"find"));
      packet.setSize(280,210);for(int pass=0;pass<5;pass++)UiLayoutTest.layout(packet);
      check(packet.modePicker.getParent()!=null,"narrow reader exposes every display mode in accessible picker");
      check(packet.searchOptions.getParent()!=null,"narrow reader exposes optional search toggles in menu");
      check(packet.previous.getWidth()>=24&&packet.next.getWidth()>=24&&packet.searchOptions.getWidth()>=24,"narrow search buttons preserve usable width instead of BoxLayout shrinkage");
      check(((JViewport)packet.text.getParent()).getExtentSize().height>=70,"150 percent narrow reader retains at least70px packet body");
      packet.modePicker.setSelectedIndex(1);
      check(packet.mode()==PacketInspector.Mode.RAW&&Arrays.equals(wire,packet.previewBytes()),"compact mode picker changes view without changing wire");
      appearance.display(100,false,true);
      check(javax.swing.text.StyleConstants.getFontSize(packet.text.getStyledDocument().getCharacterElement(0).getAttributes())==14,"changing preference updates already loaded styled packet");
      appearance.display(150,true,true);packet.close();first.root.remove(packet);
      check(first.text.getFont().getSize2D()==21f,"150 percent uses actual 21px editor font");
      check(first.table.getFont().getSize2D()==19.5f&&first.table.getRowHeight()==36,"compact table scales font and row height together");
      check(first.nativeEditor.getFont().getSize()==11,"native editor subtree remains untouched");
      first.table.moveColumn(2,0);first.table.getColumnModel().getColumn(0).setWidth(231);
      first.table.getColumnModel().getColumn(1).setWidth(145);
      first.split.putClientProperty("wb.split.ratio",.37);
      appearance.capture();appearance.close();
      String saved=memory.saved.get(A);
      check(saved!=null&&!saved.contains("PACKET_SECRET_VALUE")&&!saved.contains("UNSAVED_CREDENTIAL_VALUE"),"stored metadata never contains table cell or editor contents");
      check(WorkspaceAppearance.decode(saved).columns().size()==1,"one bounded surface state captured");
      View second=view();WorkspaceAppearance restored=new WorkspaceAppearance(memory,second.root,true,null);
      check(second.table.convertColumnIndexToModel(0)==2,"recreated table restores model-index order");
      check(second.table.getColumnModel().getColumn(0).getWidth()==231,"recreated table restores column width");
      check(Math.abs((Double)second.split.getClientProperty("wb.split.ratio")-.37)<.0001,"recreated split restores exact proportion");
      check(restored.state().scale()==150&&restored.state().compact(),"recreated display controls retain scale and density");
      memory.project=B;restored.refreshProject();
      check(restored.state().project().equals(B)&&restored.state().scale()==100&&!restored.state().compact(),"new project restores its own defaults");
      check(second.table.convertColumnIndexToModel(0)==0,"project switch does not inherit previous column order");
      check(memory.saved.get(A).equals(saved)&&!memory.saved.containsKey(B),"switch alone never writes old state into new project");
      restored.display(125,false,false);
      check(second.text.getFont().getSize2D()==17.5f&&second.table.getRowHeight()==40,"125 percent and comfortable rows applied together");
      memory.project=A;restored.refreshProject();
      check(restored.state().scale()==150&&second.table.convertColumnIndexToModel(0)==2,"returning to project restores original profile");
      restored.resetSurfaces();
      check(second.table.convertColumnIndexToModel(0)==0&&restored.state().columns().isEmpty(),"layout reset restores default column order and clears persisted positions");
      check(restored.state().scale()==150&&restored.state().compact(),"layout reset preserves display scale and density");
      restored.resetAll();
      check(restored.state().scale()==100&&!restored.state().compact(),"display reset restores 100 percent and comfortable density");
      JTable forcedCompact=new JTable(1,1);forcedCompact.putClientProperty("wb.table.compact",true);WorkbenchUi.table(forcedCompact);
      check(forcedCompact.getRowHeight()==24,"explicit compact critical tables stay compact in comfortable mode");
      memory.fail=true;restored.display(125,false,true);
      check(!restored.problem().isBlank()&&restored.state().scale()==125,"write failure is visible while display setting still applies locally");
      memory.fail=false;memory.discard=true;restored.display(150,false,true);
      check(!restored.problem().isBlank(),"silent dropped writes fail readback verification");
      memory.discard=false;restored.close();
      memory.saved.put(A,"{broken");View corruptView=view();WorkspaceAppearance corrupt=new WorkspaceAppearance(memory,corruptView.root,true,null);
      check(corrupt.blocked()&&!corrupt.problem().isBlank(),"corrupt profile blocks automatic overwrite with explanation");
      corrupt.display(125,true,true);corrupt.capture();
      check(memory.saved.get(A).equals("{broken"),"editing display never silently destroys corrupt saved profile");
      corrupt.resetAll();check(!corrupt.blocked()&&WorkspaceAppearance.decode(memory.saved.get(A)).scale()==100,"explicit reset repairs corrupt profile");
      corrupt.close();
      memory.saved.put(A,WorkspaceAppearance.encode(WorkspaceAppearance.defaults(B,true)));
      WorkspaceAppearance mismatch=new WorkspaceAppearance(memory,view().root,true,null);
      check(mismatch.blocked()&&mismatch.state().project().equals(A),"foreign-project record is rejected");mismatch.close();
      WorkbenchUi.setAppearance(100,false);
    });
    actualTrafficColumns();
    System.out.println("Appearance0534Test: "+checks+" checks passed");
  }
  record Running(WorkbenchExtension extension,List<burp.api.montoya.extension.ExtensionUnloadingHandler> modern,List<burp.IExtensionStateListener> legacy) {}
  static Running workspace(StabilizationIntegration0534Test.Host host)throws Exception {
    int modern=PayloadIntegrationTest.modernUnloads.size(),legacy=PayloadIntegrationTest.legacyUnloads.size();
    burp.BurpExtender entry=new burp.BurpExtender();
    entry.registerExtenderCallbacks((burp.IBurpExtenderCallbacks)PayloadIntegrationTest.host(burp.IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(host.api));edt(()->{});
    WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");
    edt(()->{
      JTable table=(JTable)unchecked(extension,"logTable");table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
      ((LayoutSettings)unchecked(extension,"commonSettings")).refreshProject();
    });
    return new Running(extension,List.copyOf(PayloadIntegrationTest.modernUnloads.subList(modern,PayloadIntegrationTest.modernUnloads.size())),List.copyOf(PayloadIntegrationTest.legacyUnloads.subList(legacy,PayloadIntegrationTest.legacyUnloads.size())));
  }
  static void stop(Running running)throws Exception {
    if(running==null)return;
    for(var handler:running.modern)handler.extensionUnloaded();
    for(var handler:running.legacy)handler.extensionUnloaded();
    edt(()->{});
  }
  static List<Integer> visibleModels(JTable table){List<Integer> indices=new ArrayList<>();for(int i=0;i<table.getColumnCount();i++)indices.add(table.getColumnModel().getColumn(i).getModelIndex());return List.copyOf(indices);}
  static void actualTrafficColumns()throws Exception {
    burp.api.montoya.internal.ObjectFactoryLocator.FACTORY=proxy(burp.api.montoya.internal.MontoyaObjectFactory.class,(p,m,a)->def(m));
    StabilizationIntegration0534Test.Host host=new StabilizationIntegration0534Test.Host();
    Map<String,Object> projectA=host.project.get();projectA.put(WorkspaceResume.PROJECT_KEY,A);
    Map<String,Object> projectB=new HashMap<>();projectB.put(WorkspaceResume.PROJECT_KEY,B);
    int sent=PayloadIntegrationTest.sent.get();Running first=null,second=null,third=null;
    try {
      first=workspace(host);WorkbenchExtension initial=first.extension;
      edt(()->{
        JTable table=(JTable)unchecked(initial,"logTable");LayoutSettings layout=(LayoutSettings)unchecked(initial,"commonSettings");
        WorkspaceAppearance appearance=(WorkspaceAppearance)unchecked(layout,"appearance");
        check(table.getModel().getColumnCount()==11&&table.getColumnCount()==7,"production traffic keeps11 model columns with7 approved visible columns");
        check(visibleModels(table).equals(List.of(0,1,2,3,8,4,10)),"production metadata columns4 through6 are hidden");
        table.moveColumn(table.convertColumnIndexToView(2),0);table.getColumnModel().getColumn(0).setWidth(231);
        appearance.capture();
        WorkspaceAppearance.State saved=WorkspaceAppearance.decode((String)projectA.get(WorkspaceAppearance.KEY));
        String id=(String)table.getClientProperty("wb.appearance.id");
        check(saved.columns().containsKey(id)&&saved.columns().get(id).order().equals(List.of(2,0,1,3,8,4,10)),"production traffic's actual visible subset is durably captured");
        check(saved.columns().get(id).widths().get(0)==231,"production URL column width is stored with its exact model index");
        JTable changedSubset=new JTable(table.getModel());
        for(int hidden=6;hidden>=4;hidden--)changedSubset.removeColumn(changedSubset.getColumnModel().getColumn(hidden));
        changedSubset.removeColumn(changedSubset.getColumnModel().getColumn(changedSubset.convertColumnIndexToView(7)));
        changedSubset.addColumn(new javax.swing.table.TableColumn(4));
        List<Integer> before=visibleModels(changedSubset);
        try {
          var restore=WorkspaceAppearance.class.getDeclaredMethod("restoreColumns",JTable.class,WorkspaceAppearance.Columns.class);restore.setAccessible(true);
          restore.invoke(null,changedSubset,saved.columns().get(id));
        }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
        check(visibleModels(changedSubset).equals(before),"same full model with a different visible subset rejects stale saved ordering");
        check(changedSubset.convertColumnIndexToView(7)<0&&changedSubset.convertColumnIndexToView(4)>=0,"restoration never changes tool-owned visible index membership");
      });
      stop(first);first=null;
      second=workspace(host);WorkbenchExtension reopened=second.extension;
      edt(()->{
        JTable table=(JTable)unchecked(reopened,"logTable");LayoutSettings layout=(LayoutSettings)unchecked(reopened,"commonSettings");
        WorkspaceAppearance appearance=(WorkspaceAppearance)unchecked(layout,"appearance");
        check(visibleModels(table).equals(List.of(2,0,1,3,8,4,10)),"recreated production workspace restores hidden-subset column order");
        check(table.getColumnModel().getColumn(0).getWidth()==231,"recreated production workspace restores URL width");
        check(table.convertColumnIndexToView(7)<0&&table.convertColumnIndexToView(5)<0&&table.convertColumnIndexToView(6)<0,"restoration never exposes hidden traffic metadata");
        String aSaved=(String)projectA.get(WorkspaceAppearance.KEY);host.project.set(projectB);layout.refreshProject();
        check(visibleModels(table).equals(List.of(0,1,2,3,8,4,10)),"new project resets traffic order to its own visible subset defaults");
        check(aSaved.equals(projectA.get(WorkspaceAppearance.KEY)),"project switch preserves the previous project's column metadata");
        table.moveColumn(table.convertColumnIndexToView(10),0);table.getColumnModel().getColumn(0).setWidth(179);appearance.capture();
        host.project.set(projectA);layout.refreshProject();
        check(table.convertColumnIndexToModel(0)==2&&table.getColumnModel().getColumn(0).getWidth()==231,"returning to original project restores its own hidden-subset profile");
        host.project.set(projectB);layout.refreshProject();
        check(table.convertColumnIndexToModel(0)==10&&table.getColumnModel().getColumn(0).getWidth()==179,"second project independently retains its memo column placement");
        host.project.set(projectA);layout.refreshProject();
        TrafficBrowser browser=(TrafficBrowser)unchecked(reopened,"trafficBrowser");
        TrafficQuery noMemo=new TrafficQuery("전체","전체",0,false,false,false,true,false,false,"",0);
        browser.restoreSearchState(TrafficSearchPresets.capture(noMemo));appearance.capture();
        check(table.getColumnModel().getColumn(table.convertColumnIndexToView(10)).getMaxWidth()==0,"memo visibility remains owned by traffic search settings");
      });
      stop(second);second=null;
      third=workspace(host);WorkbenchExtension memoReopened=third.extension;
      edt(()->{
        JTable table=(JTable)unchecked(memoReopened,"logTable");
        check(table.getColumnModel().getColumn(table.convertColumnIndexToView(10)).getMaxWidth()==0&&table.getColumnModel().getColumn(table.convertColumnIndexToView(10)).getWidth()==0,"reopening saved widths cannot unhide disabled memo column");
        check(table.convertColumnIndexToView(7)<0&&table.convertColumnIndexToView(5)<0&&table.convertColumnIndexToView(6)<0,"reopening with memo disabled preserves all metadata column exclusions");
        TrafficBrowser browser=(TrafficBrowser)unchecked(memoReopened,"trafficBrowser");
        browser.restoreSearchState(TrafficSearchPresets.capture(TrafficQuery.defaults()));
        check(table.getColumnModel().getColumn(table.convertColumnIndexToView(10)).getMaxWidth()==400,"memo can be explicitly enabled again without restoring hidden metadata");
      });
      check(PayloadIntegrationTest.sent.get()==sent,"appearance reopen and project switching send zero HTTP requests");
    } finally {stop(first);stop(second);stop(third);WorkbenchUi.setAppearance(100,false);}
  }
  static Object unchecked(Object owner,String field){try{return get(owner,field);}catch(Exception error){throw new IllegalStateException(error);}}

}
