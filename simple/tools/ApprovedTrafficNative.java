package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.internal.*;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Native screenshot of the production traffic page. All packets are isolated synthetic fixtures. */
public final class ApprovedTrafficNative extends WorkbenchTest {
  private static final java.util.concurrent.atomic.AtomicReference<Throwable> asyncError=new java.util.concurrent.atomic.AtomicReference<>();
  public static void main(String[] args) throws Exception {
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{asyncError.compareAndSet(null,error);error.printStackTrace();});
    try{capture(args);}catch(Throwable error){error.printStackTrace();edt(()->{for(Window window:Window.getWindows())window.dispose();});System.exit(1);}
  }
  private static void capture(String[] args) throws Exception {
    Path out=Path.of(args[0]);Files.createDirectories(out);
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    BurpExtender entry=new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));
    WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");
    JPanel root=(JPanel)get(extension,"root");
    DiskTrafficStore store=(DiskTrafficStore)get(extension,"trafficStore");
    CaptureTableModel model=(CaptureTableModel)get(extension,"logModel");
    JTable table=(JTable)get(extension,"logTable");
    TrafficBrowser browser=extension.trafficBrowser;
    String[] paths={"/api/users/me","/api/documents/101","/api/documents/101","/api/documents/102","/api/projects","/api/projects/12/notes","/assets/app.js","/api/admin/users"};
    String[] times={"10:04:12","10:05:42","10:05:47","10:06:03","10:06:18","10:06:31","10:06:45","10:07:05"};
    String[] notes={"현재 로그인 사용자","문서 상세 조회","다른 계정의 동일 요청","존재하지 않는 문서","프로젝트 목록","회의록 작성","프런트엔드 소스","관리 기능 접근 확인"};
    int[] statuses={200,200,403,404,200,201,200,403};
    String json="{\n  \"id\": 101,\n  \"ownerId\": \"alice\",\n  \"title\": \"프로젝트 회의록\",\n  \"projectId\": 12,\n  \"permission\": \"owner\",\n  \"updatedAt\": \"2026-10-31T09:42:18Z\",\n  \"content\": \"합성 데이터로 작성한 문서입니다.\"\n}";
    for(int i=0;i<paths.length;i++){
      String cookie=i==2?"SID=BOB_SYNTHETIC":"SID=ALICE_SYNTHETIC";
      var request=request("https://example.test"+paths[i],i==5?"POST":"GET",true,List.of(header("Accept","application/json"),header("Cookie",cookie),header("Referer","https://example.test/projects"),header("X-Request-ID","req-010"+i)));
      if(i==6)request=request.withRemovedHeader("Cookie");
      var base=response(statuses[i],json);String wire="HTTP/1.1 "+statuses[i]+(statuses[i]==200?" OK":" Result")+"\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-cache\r\nContent-Length: "+json.getBytes(StandardCharsets.UTF_8).length+"\r\n\r\n"+json;
      HttpResponse reply=proxy(HttpResponse.class,(p,m,a)->switch(m.getName()){case "toByteArray"->bytes(wire.getBytes(StandardCharsets.UTF_8));case "toString"->wire;default->m.invoke(base,a);});
      registerTrafficPacket(request,reply);store.append(times[i],"SYNTHETIC",request,reply);
    }
    JFrame[] window={null};
    edt(()->{
      browser.identities=()->List.of(new TrafficBrowser.Identity("https://example.test:443","Alice",Map.of("cookie","SID=ALICE_SYNTHETIC")),new TrafficBrowser.Identity("https://example.test:443","Bob",Map.of("cookie","SID=BOB_SYNTHETIC")));
      model.publish(8);browser.refresh();
      for(int i=0;i<8;i++)browser.note(i,notes[i]);
      ((WorkbenchScope)unchecked(extension,"localScope")).add("https://example.test/",true);
      ((AbstractButton)unchecked(extension,"captureButton")).doClick();
      SimpleWorkflowTest.click(root,"wb.simple.tool.traffic");
      @SuppressWarnings("unchecked") JComboBox<String> target=(JComboBox<String>)SimpleWorkflowTest.named(root,"wb.traffic.target");
      target.addItem("example.test");target.setSelectedItem("example.test");browser.search.setText("");
      root.setPreferredSize(new Dimension(1600,1000));window[0]=new JFrame("Workbench · synthetic capture");window[0].setContentPane(root);window[0].pack();window[0].setLocation(20,40);window[0].setVisible(true);
    });
    TrafficSearchUpgradeTest.idle(browser);
    edt(()->{table.setRowSelectionInterval(1,1);root.setFocusable(true);root.requestFocusInWindow();root.validate();root.repaint();});
    Robot robot=new Robot();robot.waitForIdle();Thread.sleep(300);
    int sent=PayloadIntegrationTest.sent.get();
    byte[] selectedRequest=store.ref(1).request().toByteArray().getBytes();String selectedId=store.ref(1).id();
    click(robot,SimpleWorkflowTest.named(root,"wb.traffic.quick.POST"));TrafficSearchUpgradeTest.idle(browser);
    edt(()->check(table.getRowCount()==1&&table.convertRowIndexToModel(0)==5,"native POST filter points to exact original row"));
    click(robot,SimpleWorkflowTest.named(root,"wb.traffic.quick.전체"));TrafficSearchUpgradeTest.idle(browser);
    edt(()->{check(table.getRowCount()==8,"native all filter restores all original rows");table.setRowSelectionInterval(1,1);});
    click(robot,SimpleWorkflowTest.named(root,"wb.traffic.request.raw"));
    PacketInspector inspector=(PacketInspector)get(extension,"requestPacket");
    edt(()->check(inspector.mode()==PacketInspector.Mode.RAW&&java.util.Arrays.equals(inspector.previewBytes(),selectedRequest),"native Raw view keeps selected request bytes"));
    click(robot,SimpleWorkflowTest.named(root,"wb.traffic.request.pretty"));
    click(robot,SimpleWorkflowTest.named(root,"wb.workflow.traffic.use.open"));
    edt(()->check(MenuSelectionManager.defaultManager().getSelectedPath().length>0,"native request-use menu opens"));
    robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);robot.waitForIdle();
    click(robot,SimpleWorkflowTest.named(root,"wb.traffic.settings"));
    edt(()->check(MenuSelectionManager.defaultManager().getSelectedPath().length>0,"native collection-settings menu opens"));
    robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);robot.waitForIdle();
    edt(()->{check(store.ref(1).id().equals(selectedId)&&java.util.Arrays.equals(store.ref(1).request().toByteArray().getBytes(),selectedRequest),"native controls preserve original request ID and bytes");check(PayloadIntegrationTest.sent.get()==sent,"native review sends zero HTTP requests");root.requestFocusInWindow();});
    robot.waitForIdle();Thread.sleep(300);edt(root::validate);
    edt(()->{
      check(asyncError.get()==null,"native run has no asynchronous UI exceptions");
      JScrollPane scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,table);
      check(!scroll.getVerticalScrollBar().isShowing()&&!scroll.getHorizontalScrollBar().isShowing(),"eight approved rows fit without unnecessary scrollbars");
      check(rect(root,SimpleWorkflowTest.named(root,"wb.traffic.target")).equals(new Rectangle(235,62,180,31)),"target selector matches the approved rectangle");
      check(rect(root,SimpleWorkflowTest.named(root,"wb.traffic.search")).equals(new Rectangle(206,104,1183,34)),"search field matches the approved rectangle");
      check(rect(root,inspector.getParent()).equals(new Rectangle(206,512,683,427)),"request card matches the approved rectangle: "+rect(root,inspector.getParent()));
      check(rect(root,((PacketInspector)unchecked(extension,"responsePacket")).getParent()).equals(new Rectangle(901,512,683,427)),"response card matches the approved rectangle: "+rect(root,((PacketInspector)unchecked(extension,"responsePacket")).getParent()));
    });
    robot.mouseMove(1800,1100);robot.waitForIdle();Thread.sleep(300);
    Point[] location={null};Dimension[] size={null};
    edt(()->{location[0]=root.getLocationOnScreen();size[0]=root.getSize();});
    ImageIO.write(robot.createScreenCapture(new Rectangle(location[0],size[0])),"png",out.resolve("traffic-actual-light.png").toFile());
    StringBuilder bounds=new StringBuilder();
    edt(()->{for(String name:List.of("wb.traffic","wb.traffic.controls","wb.actions.traffic.search","wb.traffic.workspace","wb.placement.traffic.selection","wb.traffic.request","wb.traffic.response","wb.navigation.tree")){Component c=SimpleWorkflowTest.named(root,name);if(c!=null)bounds.append(name).append(" ").append(SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root)).append('\n');}});
    Files.writeString(out.resolve("bounds.txt"),bounds);
    Files.writeString(out.resolve("verification.json"),MetadataExport.json(Map.of("checks",checks,"httpRequestsSent",PayloadIntegrationTest.sent.get()-sent,"runtime","Linux Xorg / xfwm4 / Java 17 / production Swing; synthetic Burp adapters","sourceVersion",BuildInfo.VERSION,"jarSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(WorkbenchExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI())))),"screenshot","traffic-actual-light.png"))+"\n");
    edt(()->{window[0].dispose();for(var handler:List.copyOf(PayloadIntegrationTest.modernUnloads))handler.extensionUnloaded();for(var handler:List.copyOf(PayloadIntegrationTest.legacyUnloads))handler.extensionUnloaded();});
    System.out.println("Captured production Swing traffic page at "+size[0]+" using synthetic data.");System.exit(0);
  }
  static Rectangle rect(Component root,Component c){if(c==null)throw new AssertionError("Missing geometry anchor");return SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);}
  static void click(Robot robot,Component c)throws Exception{Point[] point={null};edt(()->{if(c==null||!c.isShowing())throw new AssertionError("Missing visible native control");point[0]=c.getLocationOnScreen();point[0].translate(c.getWidth()/2,c.getHeight()/2);});robot.mouseMove(point[0].x,point[0].y);robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);robot.waitForIdle();Thread.sleep(70);}
  static Object unchecked(Object owner,String field){try{return get(owner,field);}catch(Exception e){throw new IllegalStateException(e);}}
}
