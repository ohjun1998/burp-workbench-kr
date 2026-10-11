package kr.workbench;
import burp.*;import burp.api.montoya.*;import javax.swing.*;import java.awt.*;import java.awt.image.*;import javax.imageio.ImageIO;import java.io.*;import java.util.*;
/** Exercises delayed first display, both orientations and every managed page without resetting dividers. */
public class LayoutBalancePreview extends WorkbenchTest {
 public static void main(String[] args)throws Exception{
  edt(()->{try{Font font=Font.createFonts(new File("src/main/resources/fonts/NanumGothic-Regular.ttf"))[0].deriveFont(13f);for(Object key:new ArrayList<>(UIManager.getDefaults().keySet()))if(UIManager.get(key) instanceof Font)UIManager.put(key,font);}catch(Exception e){throw new RuntimeException(e);}});
  BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));edt(()->entry.initialize((MontoyaApi)PayloadIntegrationTest.host(MontoyaApi.class)));edt(()->{});
  WorkbenchExtension w=(WorkbenchExtension)get(entry,"workbench");JPanel root=(JPanel)get(w,"root");JTabbedPane tabs=(JTabbedPane)get(w,"tabs");
  edt(()->{PassiveScanPanel p=((PassiveScanWorkspace)unchecked(w,"passiveScan")).open("https://example.test/catalog",false);p.add("https://example.test/api/profile","Wayback 내장");p.packets.put("https://example.test/api/profile",new PassiveScanPanel.Packet("GET /api/profile HTTP/1.1\r\nHost: example.test\r\n\r\n","HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"user\":{\"id\":101},\"roles\":[\"viewer\"]}","HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"user\":{\"id\":101},\"roles\":[\"viewer\"]}"));p.table.setRowSelectionInterval(0,0);root.setSize(1280,900);root.addNotify();});
  
  for(int mode:new int[]{1,0,1}){edt(()->{((LayoutSettings)unchecked(w,"commonSettings")).choice.setSelectedIndex(mode);});edt(()->{});for(int width:new int[]{1000,1280,1600})for(int page=0;page<tabs.getTabCount();page++){
   int selected=page;edt(()->{root.setSize(width,900);tabs.setSelectedIndex(selected);UiLayoutTest.layout(root);});for(int i=0;i<4;i++){edt(()->{});edt(()->UiLayoutTest.layout(root));}
   edt(()->{Component current=tabs.getSelectedComponent();for(Component c:PacketUiTest.components(current))if(c instanceof JSplitPane p&&p.isShowing()){
    int size=p.getOrientation()==JSplitPane.HORIZONTAL_SPLIT?p.getWidth():p.getHeight();if(size<160)continue;double expected=(Double)p.getClientProperty("wb.split.ratio");double actual=(double)p.getDividerLocation()/(size-p.getDividerSize());check(Math.abs(actual-expected)<.08,"balanced "+p.getName()+" mode="+mode+" width="+width+" page="+selected+" ratio="+actual);
   }
   for(Component c:PacketUiTest.components(current))if(c instanceof AbstractButton b&&b.isShowing()&&b.getParent()!=null){Rectangle bounds=SwingUtilities.convertRectangle(b.getParent(),b.getBounds(),root);check(bounds.y>=0&&bounds.y+bounds.height<=root.getHeight()&&bounds.x>=0&&bounds.x+bounds.width<=root.getWidth(),"visible button "+b.getText()+" at "+width+" page="+selected);}
   if(width==1280)try{BufferedImage img=new BufferedImage(width,900,BufferedImage.TYPE_INT_RGB);Graphics2D g=img.createGraphics();root.printAll(g);g.dispose();ImageIO.write(img,"png",new File(args[0]+"/layout-016-page"+selected+"-mode"+mode+".png"));}catch(Exception e){throw new RuntimeException(e);}
   });
  }}
  for(var h:PayloadIntegrationTest.modernUnloads)h.extensionUnloaded();for(var h:PayloadIntegrationTest.legacyUnloads)h.extensionUnloaded();edt(()->{});System.out.println("LayoutBalancePreview: "+checks+" checks passed");
 }
 static Object unchecked(Object o,String name){try{return get(o,name);}catch(Exception e){throw new RuntimeException(e);}}
}
