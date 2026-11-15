package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Boots the actual pinned upstream JAR, using synthetic Montoya. No native Burp or model server. */
public final class CustomAgentBootTest extends WorkbenchTest {
  static final AtomicInteger network=new AtomicInteger(),registered=new AtomicInteger(),removed=new AtomicInteger();
  static Object answer(Method method,Object[] args){
    String name=method.getName();Class<?> type=method.getReturnType();
    if(name.startsWith("sendRequest")){network.incrementAndGet();throw new AssertionError("unexpected HTTP during initialization");}
    if(name.startsWith("logTo")){if(args.length>0)System.out.println("upstream: "+args[0]);return null;}
    if(type==Registration.class){registered.incrementAndGet();return CustomAgentBridge.registration(removed::incrementAndGet);}
    if(type==String.class)return name.equals("name")?"synthetic-project":"";
    if(type==Font.class)return new Font(Font.SANS_SERIF,Font.PLAIN,13);
    if(type==List.class||type==Collection.class)return List.of();if(type==Set.class)return Set.of();if(type==Map.class)return Map.of();if(type==Optional.class)return Optional.empty();
    if(type.isEnum()){Object[] values=type.getEnumConstants();for(Object x:values)if(x.toString().equalsIgnoreCase("COMMUNITY")||x.toString().equalsIgnoreCase("LIGHT"))return x;return values.length==0?null:values[0];}
    if(type.isInterface())return proxy(type,(p,m,a)->answer(m,a));
    return def(method);
  }
  public static void main(String[] args)throws Exception {
    if(Runtime.version().feature()<21)throw new IllegalStateException("Run the real upstream boot test with Java 21+");
    MontoyaApi host=proxy(MontoyaApi.class,(p,m,a)->answer(m,a));
    var gate=new CustomAgentGate(host,URI.create("http://127.0.0.1:8731/v1"),q->false,s->{},q->false);
    AtomicReference<Component> root=new AtomicReference<>();
    var bridge=new CustomAgentBridge(host,gate,"synthetic-test-model",root::set);
    try {
      bridge.load();check(root.get()!=null,"actual upstream MainTab is embedded");
      check(!bridge.menus.isEmpty(),"upstream request context menu registered");
      check(network.get()==0,"upstream initialization emits no HTTP");check(!gate.running(),"upstream initialization cannot arm target sends");
      var loader=(ClassLoader)get(bridge,"loader");
      check(!loader.getResources("META-INF/services/com.six2dez.burp.aiagent.spi.ExternalBackendSource").hasMoreElements(),"drop-in backend loading excluded");
      check(!loader.getResources("META-INF/services/com.six2dez.burp.aiagent.spi.ToolProvider").hasMoreElements(),"external and broad MCP tool providers excluded");
      check(loader.getResources("META-INF/services/com.six2dez.burp.aiagent.spi.AiAccessPolicy").hasMoreElements(),"full-build local HTTP policy retained");
      edt(()->{
        Component c=root.get();c.setSize(1280,760);UiLayoutTest.layout((Container)c);UiLayoutTest.layout((Container)c);
        String capture=System.getProperty("custom.agent.capture");
        if(capture!=null)try{BufferedImage image=new BufferedImage(1280,760,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();c.printAll(g);g.dispose();ImageIO.write(image,"png",Path.of(capture).toFile());}catch(Exception e){throw new RuntimeException(e);}
      });
      Path extracted=(Path)get(bridge,"extracted");bridge.close();
      check(!Files.exists(extracted),"temporary upstream JAR removed on unload");
      check(registered.get()>0&&registered.get()==removed.get(),"all upstream registrations deregistered");
      check(network.get()==0,"boot and unload generated no HTTP");
    }finally{bridge.close();}
    System.out.println("CustomAgentBootTest: "+checks+" checks passed");
  }
}
