package kr.workbench;
import burp.api.montoya.*;import burp.api.montoya.persistence.*;import java.util.*;import java.nio.file.*;import java.util.concurrent.atomic.*;import java.awt.image.*;import javax.swing.*;import javax.imageio.*;
public class CustomAgentPanelPreview extends WorkbenchTest {
 public static void main(String[] args)throws Exception{
  var values=new java.util.concurrent.ConcurrentHashMap<String,String>();
  var data=proxy(PersistedObject.class,(p,m,a)->switch(m.getName()){case "getString"->values.get(a[0]);case "setString"->{values.put((String)a[0],(String)a[1]);yield null;}default->CustomAgentBootTest.answer(m,a);});
  var persistence=proxy(Persistence.class,(p,m,a)->m.getName().equals("extensionData")?data:CustomAgentBootTest.answer(m,a));
  var api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("persistence")?persistence:CustomAgentBootTest.answer(m,a));
  AtomicReference<CustomAgentPanel> ref=new AtomicReference<>();
  edt(()->{var panel=new CustomAgentPanel(api,null,()->null,()->false);ref.set(panel);panel.load.doClick();});
  var panel=ref.get();
  for(int n=0;n<100;n++){Thread.sleep(100);AtomicBoolean ready=new AtomicBoolean();edt(()->ready.set(panel.selected.isEnabled()));if(ready.get())break;}
  edt(()->{check(panel.selected.isEnabled(),"real integration panel loaded upstream UI through load button");check(!panel.active(),"opening the integration panel leaves targets stopped");panel.setSize(1300,950);UiLayoutTest.layout(panel);UiLayoutTest.layout(panel);try{var image=new BufferedImage(1300,950,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();panel.printAll(g);g.dispose();ImageIO.write(image,"png",Path.of(System.getProperty("custom.preview")).toFile());}catch(Exception e){throw new RuntimeException(e);}panel.close();});
  System.out.println("CustomAgentPanelPreview: "+checks+" checks passed");
 }
}
