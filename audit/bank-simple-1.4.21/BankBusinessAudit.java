package kr.workbench;

import java.awt.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/** One explicitly authorized fake-money transfer through the released Bounty UI runner. */
public final class BankBusinessAudit {
  @SuppressWarnings("unchecked") static BigDecimal balance(BountyWorkflow.Result r){return new BigDecimal(((Map<String,Object>)MinerJson.parse(r.response().bodyToString())).get("balance").toString());}
  static String get(String authority,String account){return "GET /check_balance/"+account+" HTTP/1.1\r\nHost: "+authority+"\r\nAccept: application/json\r\n\r\n";}
  static Component named(Component c,String name){if(name.equals(c.getName()))return c;if(c instanceof Container p)for(Component child:p.getComponents()){Component found=named(child,name);if(found!=null)return found;}return null;}
  @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception{
    if(args.length<3)throw new IllegalArgumentException("BankBusinessAudit private-state private-output public-output [images]");
    Map<String,Object> state=(Map<String,Object>)MinerJson.parse(Files.readString(Path.of(args[0])));BankSimpleAudit b=new BankSimpleAudit();b.base=state.get("base").toString().replaceAll("/$","");b.actors=(List<Map<String,Object>>)state.get("actors");
    BankSimpleAudit.require(Set.of("127.0.0.1","localhost","::1").contains(java.net.URI.create(b.base).getHost()),"Loopback bank only");
    b.out=Path.of(args[1]);b.published=Path.of(args[2]);Files.createDirectories(b.out);Files.createDirectories(b.published);
    for(var actor:b.actors)for(String key:List.of("token","password"))if(actor.get(key) instanceof String s&&!s.isEmpty())b.secrets.add(s);if(state.get("password") instanceof String s&&!s.isEmpty())b.secrets.add(s);
    Path product=Path.of(WorkbenchExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());BankSimpleAudit.require(BankSimpleAudit.sha(Files.readAllBytes(product)).equals(BankSimpleAudit.EXPECTED_JAR),"Exact released product JAR");
    JFrame[] frame={null};
    try{
      b.initialize();BountySupportPanel p=b.bounty;String authority=java.net.URI.create(b.base).getRawAuthority(),alice=b.actors.get(1).get("account").toString(),bob=b.actors.get(2).get("account").toString();
      String body=MetadataExport.json(Map.of("to_account",bob,"amount",-5,"description","WORKBENCH_SIMPLE_1421_NEGATIVE_TRANSFER"));
      String post="POST /transfer HTTP/1.1\r\nHost: "+authority+"\r\nAuthorization: Bearer "+b.actors.get(1).get("token")+"\r\nContent-Type: application/json\r\n\r\n"+body;
      String workflow=String.join("\n===STEP===\n",get(authority,alice),get(authority,bob),post,get(authority,alice),get(authority,bob));
      WorkbenchTest.edt(()->{b.wb.openFeature("bounty");p.pages.setSelectedIndex(1);p.flowOrigin.setText(b.base);p.workflow.setText(workflow);p.extract.setText("");p.flowChecks.setText("1.HTTP=200\n2.HTTP=200\n3.HTTP=200\n4.HTTP=200\n5.HTTP=200");p.flowWrites.setSelected(true);p.flowBudget.setValue(5);p.flowTimeout.setValue(10);p.flowRun.doClick(0);});
      BankSimpleAudit.await(()->!(Boolean)BankSimpleAudit.field(p,"running")&&p.flowResults.size()>=5,60);b.settleObservers();
      List<BountyWorkflow.Result> rows=List.copyOf(p.flowResults);BankSimpleAudit.require(rows.size()==5,"Exactly five Bounty UI workflow requests");
      for(var row:rows)BankSimpleAudit.require(row.response()!=null&&row.response().statusCode()==200&&row.state().contains("기대 조건 일치"),"Actual step expectation matched");
      BigDecimal beforeAlice=balance(rows.get(0)),beforeBob=balance(rows.get(1)),afterAlice=balance(rows.get(3)),afterBob=balance(rows.get(4));
      BigDecimal aliceDelta=afterAlice.subtract(beforeAlice),bobDelta=afterBob.subtract(beforeBob);BankSimpleAudit.require(aliceDelta.compareTo(new BigDecimal("5"))==0&&bobDelta.compareTo(new BigDecimal("-5"))==0,"Negative transfer actually reverses Alice/Bob balances");
      b.save("business-negative-transfer",BankSimpleAudit.obj("result","PASS","version",BuildInfo.VERSION,"jarSHA256",BankSimpleAudit.EXPECTED_JAR,"executedAtUTC",Instant.now().toString(),"bank",b.base,"fakeMoneyAmount",-5,"sender","wb_alice","recipient","wb_bob","senderBefore",beforeAlice,"senderAfter",afterAlice,"senderDelta",aliceDelta,"recipientBefore",beforeBob,"recipientAfter",afterBob,"recipientDelta",bobDelta,"bountyUiWritesExplicitlyAllowed",true,"workflowRequestLimit",5,"actualHttpRequests",b.sequence.get(),"steps",rows.stream().map(r->BankSimpleAudit.obj("step",r.step(),"method",r.request().method(),"url",r.request().url(),"status",(int)r.response().statusCode(),"productState",r.state())).toList(),"classification","Independent numeric comparison of actual response bodies returned by the production Bounty UI workflow; the tool states only that configured HTTP expectations matched, not an automatic vulnerability verdict.","nativeBurp",false,"syntheticTargetResponses",false));
      b.exportOriginals();b.save("transport-index",b.transport);b.save("diagnostics",((Diagnostics)BankSimpleAudit.field(b.wb,"diagnostics")).snapshot().stream().map(e->BankSimpleAudit.obj("area",e.area(),"type",e.type(),"location",e.location())).toList());
      if(args.length>3&&!GraphicsEnvironment.isHeadless()){
        Path images=Path.of(args[3]);Files.createDirectories(images);JPanel root=(JPanel)BankSimpleAudit.field(b.wb,"root");
        WorkbenchTest.edt(()->{frame[0]=new JFrame("Workbench Simple1.4.21 · 실제 음수 이체 재현 결과");frame[0].setContentPane(root);frame[0].setSize(1600,1000);frame[0].setLocation(25,35);frame[0].setVisible(true);});
        BankSimpleAudit.await(()->frame[0].isActive()&&root.isShowing(),15);Robot robot=new Robot();robot.setAutoDelay(40);List<Map<String,Object>> captures=new ArrayList<>();
        for(int selected:new int[]{3,4}){
          WorkbenchTest.edt(()->{p.flowTable.setRowSelectionInterval(selected,selected);((JTabbedPane)named(root,"wb.bounty.flow.packet.tabs")).setSelectedIndex(0);frame[0].validate();});Thread.sleep(400);WorkbenchTest.edt(()->{});
          Component component=named(root,"wb.bounty.flow.results");AtomicReference<Rectangle> bounds=new AtomicReference<>();WorkbenchTest.edt(()->bounds.set(new Rectangle(component.getLocationOnScreen(),component.getSize())));
          BankSimpleAudit.require(!p.workflowDetail.getText().contains(b.actors.get(1).get("token").toString()),"Selected response contains no bearer token");String name=selected==3?"alice-after.png":"bob-after.png";robot.mouseMove(1900,1150);ImageIO.write(robot.createScreenCapture(bounds.get()),"png",images.resolve(name).toFile());captures.add(BankSimpleAudit.obj("file",name,"sha256",BankSimpleAudit.sha(Files.readAllBytes(images.resolve(name))),"selectedStep",selected+1,"rectangle",List.of(bounds.get().x,bounds.get().y,bounds.get().width,bounds.get().height),"scope","Original production Bounty results component only; raw authenticated request input lies outside this Robot capture region."));
        }
        b.save("business-captures",BankSimpleAudit.obj("nativeSwing",true,"nativeBurp",false,"imageEdited",false,"captures",captures));
      }
      System.out.println("BANK_BUSINESS_FINISHED steps="+rows.size()+" HTTP="+b.sequence.get()+" senderDelta="+aliceDelta+" recipientDelta="+bobDelta);
    }finally{for(var h:PayloadIntegrationTest.modernUnloads)h.extensionUnloaded();for(var h:PayloadIntegrationTest.legacyUnloads)h.extensionUnloaded();WorkbenchTest.edt(()->{for(var w:Window.getWindows())w.dispose();});}
    System.exit(0);
  }
}
