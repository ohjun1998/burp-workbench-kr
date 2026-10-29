package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.FontUIResource;

/** External Cycle 5 probe: production UI + synthetic packet adapters, never native Burp GUI. */
public final class DashboardNavigationProbe extends WorkbenchTest {
  static Path output;
  static JPanel root;
  static ScopeDashboardPanel dashboard;
  static int width, height;
  static String theme;
  static final List<Map<String,Object>> observations = new ArrayList<>();

  static Object field(Object owner, String name) {
    try { return get(owner, name); } catch (Exception error) { throw new AssertionError(error); }
  }
  static void set(Object owner, String name, Object value) {
    try { var member = owner.getClass().getDeclaredField(name); member.setAccessible(true); member.set(owner, value); }
    catch (Exception error) { throw new AssertionError(error); }
  }
  static void layout(Component component) {
    if(component instanceof JTable table) {
      var scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,table);
      if(scroll!=null&&(scroll.getColumnHeader()==null||scroll.getColumnHeader().getView()!=table.getTableHeader()))
        scroll.setColumnHeaderView(table.getTableHeader());
    }
    if (component instanceof Container container) {
      container.doLayout(); for (var child : container.getComponents()) layout(child);
    }
  }
  static void settle() {
    for (int pass=0;pass<7;pass++) {
      layout(root);
      var loop=Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
      SwingUtilities.invokeLater(loop::exit); loop.enter();
    }
  }
  static JScrollPane dashboardScroll() {
    return (JScrollPane)SimpleWorkflowTest.named(dashboard,"wb.scope.dashboard.scroll");
  }
  static void top() { dashboardScroll().getViewport().setViewPosition(new Point(0,0)); settle(); }
  static void capture(String name) {
    try {
      var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
      var graphics=image.createGraphics(); root.paint(graphics); graphics.dispose();
      ImageIO.write(image,"png",output.resolve(name+".png").toFile());
    } catch(Exception error) { throw new IllegalStateException(error); }
  }
  static Rectangle bounds(Component component) {
    return SwingUtilities.convertRectangle(component.getParent(),component.getBounds(),root);
  }
  static Rectangle shown(Component component) {
    Rectangle result=bounds(component);
    for(var parent=component.getParent();parent!=null&&parent!=root;parent=parent.getParent()) {
      if(!parent.isVisible())return new Rectangle();
      result=result.intersection(SwingUtilities.convertRectangle(parent,new Rectangle(0,0,parent.getWidth(),parent.getHeight()),root));
    }
    return result.intersection(new Rectangle(0,0,width,height));
  }
  static Map<String,Object> rectangle(Rectangle value) {
    return Map.of("x",value.x,"y",value.y,"width",Math.max(0,value.width),"height",Math.max(0,value.height));
  }
  static boolean listVisible() { var value=shown(dashboard.table); return value.width>=150&&value.height>=80; }
  static boolean detailVisible() { var value=shown(dashboard.detail); return value.width>=120&&value.height>=80; }
  static boolean selectedRowVisible() {
    int row=dashboard.table.getSelectedRow(); if(row<0)return false;
    var cell=dashboard.table.getCellRect(row,0,true);
    var area=SwingUtilities.convertRectangle(dashboard.table,cell,root);
    return shown(dashboard.table).contains(area);
  }
  static Map<String,Object> view() {
    var result=new LinkedHashMap<String,Object>();
    result.put("viewport_y",dashboardScroll().getViewport().getViewPosition().y);
    result.put("list_shown",rectangle(shown(dashboard.table)));
    result.put("detail_shown",rectangle(shown(dashboard.detail)));
    result.put("list_usable",listVisible()); result.put("detail_usable",detailVisible());
    result.put("selected_row_visible",selectedRowVisible());
    result.put("rows",dashboard.table.getRowCount());
    result.put("selected_original",dashboard.selectedRow()==null?"":dashboard.selectedRow().originalId());
    result.put("selected_account",dashboard.selectedAccount());
    result.put("filter_summary",dashboard.filterSummary.getText());
    return result;
  }
  static void measure(String name,String buttonName) {
    top();
    var button=(AbstractButton)SimpleWorkflowTest.named(dashboard,buttonName);
    check(button!=null&&shown(button).contains(bounds(button)),"measured control is initially visible: "+name);
    var row=new LinkedHashMap<String,Object>(); row.put("action",name); row.put("control",buttonName);
    row.put("explicit_control_clicks",1); row.put("before",view());
    button.doClick(0); settle(); row.put("after_click",view()); capture(name+"-after-click");
    boolean needed=!(listVisible()&&detailVisible()&&selectedRowVisible());
    row.put("additional_explicit_scroll_required",needed);
    row.put("wheel_events_estimated",false);
    row.put("explicit_scroll_operations_in_probe",needed?1:0);
    if(needed) {
      JViewport viewport=dashboardScroll().getViewport();
      // One deliberate scrollbar-to-result operation; no guessed mouse-wheel count.
      var split=(JComponent)SimpleWorkflowTest.named(dashboard,"wb.scope.dashboard.results.split");
      var result=SwingUtilities.convertRectangle(split.getParent(),split.getBounds(),viewport.getView());
      int maximum=Math.max(0,viewport.getView().getHeight()-viewport.getExtentSize().height);
      viewport.setViewPosition(new Point(0,Math.min(maximum,Math.max(0,result.y-30))));
      settle();
    }
    row.put("after_explicit_scroll_if_needed",view()); capture(name+"-after-reveal");
    check(listVisible()&&detailVisible(),"result list and detail can be reached with explicit scrolling: "+name);
    observations.add(row);
  }
  static void account(String value) {
    for(int i=0;i<dashboard.account.getItemCount();i++)
      if(dashboard.account.getItemAt(i).value().equals(value)){dashboard.account.setSelectedIndex(i);return;}
    throw new AssertionError("missing account "+value);
  }
  static ScopeInspection.Finding finding(String category,String title,String account,String severity,DiskTrafficStore.PacketRef original) {
    return new ScopeInspection.Finding(category,title,original.url(),"GET",account,"합성 예제",severity,"검토 필요",
        "Cycle 5 synthetic fixture; no target request was sent.\n두 번째 줄의 합성 근거.",original.id(),3,12,title);
  }
  static ScopeInspection.Snapshot snapshot(String id,List<ScopeInspection.Finding> findings,List<ScopeInspection.Page> pages,int sent) {
    return new ScopeInspection.Snapshot(id,ScopeInspection.State.READY,"합성 스냅샷",sent,pages.size(),0,0,0,0,
        findings,pages,List.of(),100,0);
  }
  static String json(Object value) {
    if(value==null)return "null";
    if(value instanceof Number||value instanceof Boolean)return value.toString();
    if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();for(var entry:map.entrySet())parts.add(json(entry.getKey().toString())+":"+json(entry.getValue()));return "{"+String.join(",",parts)+"}";}
    if(value instanceof Iterable<?> list){var parts=new ArrayList<String>();for(var item:list)parts.add(json(item));return "["+String.join(",",parts)+"]";}
    String text=value.toString(); var out=new StringBuilder("\"");
    for(char c:text.toCharArray())switch(c){case '\\'->out.append("\\\\");case '"'->out.append("\\\"");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}
    return out.append('"').toString();
  }
  static void appearance(String theme) throws Exception {
    if(theme.equals("dark")) {
      for(String key:List.of("Panel.background","TabbedPane.background","Button.background","CheckBox.background","ComboBox.background","control"))UIManager.put(key,new Color(30,33,39));
      for(String key:List.of("Label.foreground","Button.foreground","CheckBox.foreground","ComboBox.foreground","TabbedPane.foreground","textText"))UIManager.put(key,new Color(228,233,241));
      UIManager.put("TabbedPane.contentAreaColor",new Color(30,33,39));
    }
    Font font;
    try(var in=DashboardNavigationProbe.class.getResourceAsStream("/fonts/NanumGothic-Regular.ttf")){font=Font.createFont(Font.TRUETYPE_FONT,in);}
    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
    var keys=UIManager.getDefaults().keys();while(keys.hasMoreElements()){Object key=keys.nextElement();if(UIManager.get(key)instanceof FontUIResource)UIManager.put(key,new FontUIResource(font.deriveFont(14f)));}
  }
  public static void main(String[] args) throws Exception {
    output=Path.of(args[0]);theme=args[1];width=Integer.parseInt(args[2]);height=Integer.parseInt(args[3]);
    Files.createDirectories(output); appearance(theme);
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    var api=SimpleWorkflowTest.previewApi();var entry=new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    int initialSent=PayloadIntegrationTest.sent.get();
    edt(()->entry.initialize(api));edt(()->{});
    var extension=(WorkbenchExtension)field(entry,"workbench");root=(JPanel)field(extension,"root");
    dashboard=(ScopeDashboardPanel)field(extension,"scopeDashboard");
    var dialog=new AtomicReference<Component>();
    extension.dialogOpener=(owner,title,content,modal,closed)->{dialog.set(content);return null;};
    var checks=new LinkedHashMap<String,Object>();
    String origin="https://cycle5.example.test";
    try(var store=new DiskTrafficStore()) {
      var aliceRequest=request(origin+"/app/api/alice","GET",true,List.of(header("Cookie","SID=CYCLE5_ALICE_EXAMPLE")));
      var bobRequest=request(origin+"/app/api/bob","GET",true,List.of(header("Cookie","SID=CYCLE5_BOB_EXAMPLE")));
      var aliceResponse=response(200,"{\"owner\":\"Alice\",\"synthetic\":true}");
      var bobResponse=response(403,"{\"owner\":\"Bob\",\"synthetic\":true}");
      registerTrafficPacket(aliceRequest,aliceResponse);registerTrafficPacket(bobRequest,bobResponse);
      var alice=store.append("Cycle5 synthetic","Alice",aliceRequest,aliceResponse);
      var bob=store.append("Cycle5 synthetic","Bob",bobRequest,bobResponse);
      var pages=List.of(new ScopeInspection.Page(alice.url(),"GET","Alice",200,"JSON",true,"synthetic","alice",alice),
          new ScopeInspection.Page(bob.url(),"GET","Bob",403,"DENIED",true,"synthetic","bob",bob));
      var findings=List.of(finding("API","Alice API 후보","Alice","INFO",alice),finding("INVENTORY","Bob 명세 선언","Bob","INFO",bob),
          finding("SECRET","Alice 높은 중요도 키 후보","Alice","HIGH",alice),finding("SECRET","Bob 높은 중요도 키 후보","Bob","HIGH",bob),
          finding("SECRET","Alice 중간 중요도 후보","Alice","MEDIUM",alice),finding("ENVIRONMENT","서버 단서","Alice","INFO",alice),
          finding("ACCESS","계정 조건 검토","Bob","INFO",bob));
      var first=snapshot("cycle5-baseline",findings,pages,0);
      edt(()->{
        root.setSize(width,height);root.setVisible(true);
        SimpleWorkflowTest.click(root,"wb.simple.dashboard.open");
        ((WorkbenchScope)field(extension,"localScope")).add(origin+"/app/",false);
        dashboard.inspect.doClick(0);
      });
      var inspection=(ScopeInspectionPanel)field(extension,"scopeInspectionPanel");
      edt(()->{
        inspection.options=new ScopeInspection.Options(origin+"/app/",20,1,0,2000,1024*1024,2,false,true,
            List.of(new ScopeInspection.Profile("alice","Alice",Map.of(),null),new ScopeInspection.Profile("bob","Bob",Map.of(),null)));
        set(inspection,"runProject",WorkspaceResume.projectIdentity(api));
        set(inspection,"runScope",((WorkbenchScope)field(extension,"localScope")).inspectionFingerprint());
        inspection.accept(first);settle();
        dashboard.cards.get(ScopeInspectionDashboard.Group.SECRETS).doClick(0);account("Bob");
        check(dashboard.selectedRow()!=null&&dashboard.selectedRow().originalId().equals(bob.id()),"Bob filter selects Bob original");
        dashboard.original.doClick(0);
        boolean correct=dialog.get()instanceof JComponent content&&SimpleWorkflowTest.visibleComponents(content).stream()
            .anyMatch(c->c instanceof JLabel label&&label.getText().contains(bob.id()));
        check(correct,"original action opens the selected synthetic Bob packet inspector");
        checks.put("exact_original_selection",correct);
        dashboard.cards.get(ScopeInspectionDashboard.Group.API).doClick(0);
        boolean preserved=dashboard.selectedAccount().equals("Bob")&&dashboard.table.getRowCount()==1;
        check(preserved,"category navigation preserves account condition");checks.put("account_condition_preserved",preserved);
        dashboard.severityButtons.get("INFO").doClick(0);
        boolean empty=dashboard.table.getRowCount()==0&&!dashboard.original.isEnabled();
        dashboard.reset.doClick(0);
        boolean recovered=dashboard.table.getRowCount()==3&&dashboard.selectedAccount().isEmpty();
        check(empty&&recovered,"zero-condition result recovers with condition reset");checks.put("empty_condition_recovered",empty&&recovered);
        account("Alice");top();capture("00-dashboard-summary");
        measure("01-api-card","wb.scope.dashboard.card.api");
        check(dashboard.selectedAccount().equals("Alice"),"measured card keeps account filter");
        measure("02-api-classification","wb.scope.dashboard.bar.api");
        measure("03-high-importance","wb.scope.dashboard.severity.high");
        check(dashboard.selectedRow()!=null&&dashboard.selectedRow().originalId().equals(alice.id()),"importance drilldown retains Alice original");
        var viewport=dashboardScroll().getViewport();
        int middle=Math.max(0,(viewport.getView().getHeight()-viewport.getExtentSize().height)/2);
        viewport.setViewPosition(new Point(0,middle));settle();
        int before=viewport.getViewPosition().y;
        inspection.accept(snapshot(first.id(),findings,pages,1));settle();
        int after=viewport.getViewPosition().y;
        checks.put("automatic_refresh_before_y",before);checks.put("automatic_refresh_after_y",after);
        checks.put("automatic_refresh_preserves_user_scroll",before==after);
        capture("04-automatic-snapshot-refresh");
        check(PayloadIntegrationTest.sent.get()==initialSent,"all production navigation sends zero target HTTP");
        checks.put("target_http_calls",PayloadIntegrationTest.sent.get()-initialSent);
      });
      Path artifact=Path.of(ScopeDashboardPanel.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      var report=new LinkedHashMap<String,Object>();report.put("cycle",5);report.put("version",BuildInfo.VERSION);
      report.put("fixture","synthetic request/response packets in production DiskTrafficStore; no actual HTTP fetch, no native Burp GUI");
      report.put("surface","full Workbench SimpleNavigation and production dashboard appended page");
      report.put("theme",theme);report.put("width",width);report.put("height",height);report.put("artifact",artifact.toString());
      report.put("artifact_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
      report.put("task_count_definition","one explicit card/chart control click; record whether an additional deliberate scrollbar reveal is needed; never estimate wheel ticks");
      report.put("observations",observations);report.put("checks",checks);
      Files.writeString(output.resolve("result.json"),json(report)+"\n");
      System.out.println(json(Map.of("out",output.toString(),"observations",observations.size(),"checks",checks)));
    } finally {SimpleWorkflowTest.unload();}
  }
}
