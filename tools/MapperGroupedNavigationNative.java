package kr.featuremapper;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.Document;
import javax.swing.tree.*;

/** Displayed production Swing on isolated Xorg, synthetic packets/API; no native Burp GUI. */
public final class MapperGroupedNavigationNative {
  private static final List<String> checks=new ArrayList<>();
  private static final List<Throwable> asynchronous=Collections.synchronizedList(new ArrayList<>());
  private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks.add(message);}
  private static void edt(Runnable action)throws Exception{SwingUtilities.invokeAndWait(action);}
  private static Object field(Object owner,String name){try{return MapperUiWorkflowTest.get(owner,name);}catch(Exception e){throw new IllegalStateException(name,e);}}
  private static void refresh(FeatureMapperWorkspace workspace){try{MapperUiWorkflowTest.call(workspace,"refresh");}catch(Exception e){throw new IllegalStateException(e);}}
  private static void await(BooleanSupplier condition,String message)throws Exception{
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
    while(System.nanoTime()<deadline){boolean[] ok={false};edt(()->ok[0]=condition.getAsBoolean());if(ok[0])return;Thread.sleep(30);}
    throw new AssertionError("Native timeout: "+message);
  }
  private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  private static DefaultMutableTreeNode root(JTree tree){return (DefaultMutableTreeNode)tree.getModel().getRoot();}
  private static List<DefaultMutableTreeNode> nodes(JTree tree){List<DefaultMutableTreeNode> result=new ArrayList<>();var all=root(tree).depthFirstEnumeration();while(all.hasMoreElements())result.add((DefaultMutableTreeNode)all.nextElement());return result;}
  private static TreePath featurePath(JTree tree,String id){return nodes(tree).stream().filter(n->n.getUserObject() instanceof FeatureMapperWorkspace.FeatureNode f&&id.equals(f.id())).map(n->new TreePath(n.getPath())).findFirst().orElseThrow();}
  private static TreePath firstGroup(JTree tree,TreePath feature){return feature.getParentPath();}
  private static void treeClick(Robot robot,JTree tree,TreePath path)throws Exception{
    edt(()->{tree.scrollPathToVisible(path);tree.requestFocusInWindow();});
    await(()->tree.getPathBounds(path)!=null,"tree row available");
    Point[] location={null};Rectangle[] bounds={null};edt(()->{location[0]=tree.getLocationOnScreen();bounds[0]=tree.getPathBounds(path);});
    robot.mouseMove(location[0].x+bounds[0].x+Math.min(45,Math.max(2,bounds[0].width-4)),location[0].y+bounds[0].y+bounds[0].height/2);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);robot.waitForIdle();
  }
  private static void key(Robot robot,int code){robot.keyPress(code);robot.keyRelease(code);robot.waitForIdle();}
  private static void click(Robot robot,Component component)throws Exception{
    Point[] point={null};edt(()->{point[0]=component.getLocationOnScreen();point[0].translate(component.getWidth()/2,component.getHeight()/2);});
    robot.mouseMove(point[0].x,point[0].y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);robot.waitForIdle();
  }
  private static void capture(Robot robot,JPanel panel,Path file)throws Exception{
    Rectangle[] bounds={null};edt(()->bounds[0]=new Rectangle(panel.getLocationOnScreen(),panel.getSize()));
    robot.mouseMove(1870,1150);Toolkit.getDefaultToolkit().sync();robot.waitForIdle();
    ImageIO.write(robot.createScreenCapture(bounds[0]),"png",file.toFile());
  }
  private static Mapper.Packet seed(Mapper mapper,String path,int status,int copies,String mime)throws Exception{
    Mapper.Packet result=null;for(int i=0;i<copies;i++)result=mapper.accept(MapperTest.input(1793530000000L+mapper.count()*100L,"GET","https://grouped.example.test"+path,new byte[0],("{\"fixture\":true,\"sequence\":"+mapper.count()+"}").getBytes(StandardCharsets.UTF_8),"SYNTHETIC",status,mime));return result;
  }
  public static void main(String[] args)throws Exception{
    if(GraphicsEnvironment.isHeadless())throw new AssertionError("Run on isolated native Xorg");
    Path out=Path.of(args[0]);Files.createDirectories(out);String theme=args[1];int width=Integer.parseInt(args[2]),height=Integer.parseInt(args[3]);
    if(theme.equals("dark"))UIManager.put("Panel.background",new Color(30,33,39));else UIManager.put("Panel.background",new Color(246,248,251));
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{asynchronous.add(error);error.printStackTrace();});
    FeatureMapperWorkspace workspace=new FeatureMapperWorkspace();JFrame[] window={null};Robot robot=new Robot();robot.setAutoDelay(55);
    int sentBefore=MapperUiWorkflowTest.sent.get();
    try{
      edt(()->workspace.initialize((MontoyaApi)MapperUiWorkflowTest.host(MontoyaApi.class)));
      Mapper mapper=workspace.mapper;
      seed(mapper,"/dashboard",200,3,"application/json");
      Mapper.Packet balance=seed(mapper,"/check_balance/1791279111",200,13,"application/json");
      mapper.edit(balance.primary,"잔액 조회","미점검","");
      seed(mapper,"/admin",200,2,"application/json");seed(mapper,"/login",200,2,"text/html");
      seed(mapper,"/merchant/login",200,2,"text/html");seed(mapper,"/register",200,2,"text/html");
      seed(mapper,"/merchant/register",200,2,"text/html");seed(mapper,"/api/docs/",200,2,"text/html");
      seed(mapper,"/swagger-ui.html",404,2,"text/html");seed(mapper,"/openapi.json",404,2,"application/json");
      seed(mapper,"/healthz",200,2,"application/json");seed(mapper,"/.env",200,2,"text/plain");
      Mapper.Packet probe=seed(mapper,"/wb-missing-11111111-2222-4333-8444-555555555555",404,2,"text/html");
      seed(mapper,"/wb-bank-missing-1422",404,1,"text/html");
      seed(mapper,"/check_balance/%27",500,1,"application/json");
      seed(mapper,"/api/ai/chat/anonymous",405,2,"application/json");
      seed(mapper,"/privacy",200,2,"text/html");seed(mapper,"/assets/app.js",200,2,"application/javascript");
      Map<Long,List<String>> packets=new LinkedHashMap<>();
      for(var packet:mapper.packetList(null))packets.put(packet.id,List.of(packet.url,packet.primary,sha(mapper.raw(packet.id)[0]),sha(mapper.raw(packet.id)[1])));
      JPanel panel=(JPanel)field(workspace,"root");JTree tree=(JTree)field(workspace,"tree");JTable table=(JTable)field(workspace,"table");
      JTextArea request=(JTextArea)field(workspace,"requestText"),response=(JTextArea)field(workspace,"responseText");Document requestDocument=request.getDocument(),responseDocument=response.getDocument();
      edt(()->{refresh(workspace);JFrame frame=new JFrame("Mapper grouped navigation · "+theme);window[0]=frame;frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);frame.setContentPane(panel);frame.setBounds(30,40,width,height);frame.setVisible(true);frame.toFront();});
      await(()->window[0].isActive()&&panel.isShowing()&&tree.isShowing(),"displayed Mapper window");
      edt(()->{
        check(!tree.isRootVisible(),"Technical tree root remains hidden");
        Set<String> actual=new HashSet<>();
        for(var node:nodes(tree))if(node.getUserObject() instanceof FeatureMapperWorkspace.FeatureNode f){actual.add(f.id());check(!f.label().contains("https://")&&!f.label().contains("wb-missing-"),"Feature caption omits URL and random probe path: "+f.id());}
        check(actual.equals(new HashSet<>(mapper.features.keySet())),"Every original feature ID remains reachable");
        check(nodes(tree).stream().filter(n->n.getUserObject().getClass().getSimpleName().equals("AllFeaturesNode")).count()==1,"Exactly one all-observations row");
      });
      TreePath balancePath=featurePath(tree,balance.primary);treeClick(robot,tree,balancePath);
      await(()->balance.primary.equals(field(workspace,"selectedFeature"))&&table.getRowCount()==13,"selected balance feature and13 requests");
      edt(()->{
        var node=(DefaultMutableTreeNode)balancePath.getLastPathComponent();Component renderer=tree.getCellRenderer().getTreeCellRendererComponent(tree,node,true,false,true,tree.getRowForPath(balancePath),true);
        String accessible=renderer.getAccessibleContext().getAccessibleName();
        check(accessible!=null&&accessible.contains("13"),"Native row renderer exposes its live13-request count");
        table.setRowSelectionInterval(0,0);
      });
      await(()->!request.getText().isEmpty()&&!response.getText().isEmpty(),"selected original request and response loaded");
      capture(robot,panel,out.resolve("grouped-selected.png"));
      JToggleButton originalToggle=(JToggleButton)field(workspace,"originalInfoToggle");
      JTextArea original=(JTextArea)field(workspace,"originalInfoText");
      check(!originalToggle.isSelected()&&!original.isShowing(),"Original metadata is collapsed by default");
      click(robot,originalToggle);
      await(original::isShowing,"physical disclosure opens selected original metadata");
      check(original.getText().contains(balance.url)&&original.getText().contains(balance.primary),"Expanded metadata contains the exact original URL and feature ID");
      capture(robot,panel,out.resolve("grouped-original-info.png"));
      click(robot,originalToggle);await(()->!original.isShowing(),"physical disclosure closes original metadata");
      check(!originalToggle.isSelected(),"Original metadata can be collapsed without losing selected feature");
      TreePath probePath=featurePath(tree,probe.primary),probeGroup=firstGroup(tree,probePath);
      edt(()->tree.expandPath(probeGroup));treeClick(robot,tree,probeGroup);key(robot,KeyEvent.VK_LEFT);
      await(()->!tree.isExpanded(probeGroup),"physical left arrow collapses probe group");
      edt(()->refresh(workspace));
      check(!tree.isExpanded(firstGroup(tree,featurePath(tree,probe.primary))),"Collapsed group remains collapsed after capture refresh");
      key(robot,KeyEvent.VK_RIGHT);
      await(()->tree.isExpanded(firstGroup(tree,featurePath(tree,probe.primary))),"physical right arrow expands probe group");
      check(true,"Physical keyboard group collapse/expand works");
      treeClick(robot,tree,featurePath(tree,probe.primary));
      await(()->probe.primary.equals(field(workspace,"selectedFeature"))&&table.getRowCount()==2,"probe feature original membership");
      edt(()->refresh(workspace));
      check(probe.primary.equals(field(workspace,"selectedFeature")),"Feature selection survives refresh");
      capture(robot,panel,out.resolve("grouped-probe-expanded.png"));
      TreePath all=nodes(tree).stream().filter(n->n.getUserObject().getClass().getSimpleName().equals("AllFeaturesNode")).map(n->new TreePath(n.getPath())).findFirst().orElseThrow();
      treeClick(robot,tree,all);await(()->table.getRowCount()==mapper.count(),"all original observations returned");
      check(request.getDocument()==requestDocument&&response.getDocument()==responseDocument,"Original request/response document objects retained");
      for(var packet:mapper.packetList(null))check(packets.get(packet.id).equals(List.of(packet.url,packet.primary,sha(mapper.raw(packet.id)[0]),sha(mapper.raw(packet.id)[1]))),"Original ID,URL,mapping and both byte arrays retained: "+packet.id);
      check(MapperUiWorkflowTest.sent.get()==sentBefore,"Grouping, physical navigation and refresh send zero target requests");
      check(asynchronous.isEmpty(),"No asynchronous Swing failures");
      Path jar=Path.of(FeatureMapperWorkspace.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Map<String,Object> result=new LinkedHashMap<>();result.put("result","passed");result.put("theme",theme);result.put("windowWidth",width);result.put("windowHeight",height);result.put("checks",checks.size());result.put("checkDetails",checks);result.put("featureCount",mapper.features.size());result.put("observationCount",mapper.count());result.put("jarSha256",sha(Files.readAllBytes(jar)));result.put("nativeSwing",true);result.put("nativeBurpGui",false);result.put("syntheticPackets",true);result.put("targetHttpRequests",0);
      Files.writeString(out.resolve("verification.json"),JsonWrite.text(result)+"\n");System.out.println("Mapper grouped native: "+checks.size()+" checks passed; "+theme+" "+width+"x"+height);
    }finally{
      edt(()->{workspace.close();if(window[0]!=null)window[0].dispose();});
    }
  }
}
