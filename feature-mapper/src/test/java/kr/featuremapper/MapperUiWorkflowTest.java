package kr.featuremapper;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Production Swing with a local API adapter; no native Burp GUI. */
final class MapperUiWorkflowTest {
  static final AtomicInteger sent=new AtomicInteger();
  static Object host(Class<?> type) {
    if(!type.isInterface())return null;
    return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
      if(m.getName().equals("equals"))return p==a[0];if(m.getName().equals("hashCode"))return System.identityHashCode(p);
      if(Set.of("sendRequest","makeHttpRequest").contains(m.getName()))sent.incrementAndGet();
      Class<?> returns=m.getReturnType();if(returns==boolean.class)return false;if(returns==int.class)return 0;if(returns==long.class)return 0L;
      if(returns==List.class)return List.of();if(returns==Optional.class)return Optional.empty();return returns.isInterface()?host(returns):null;
    });
  }
  static Object get(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
  static Object call(Object owner,String name)throws Exception {Method m=owner.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(owner);}
  static Component named(Component c,String name){if(name.equals(c.getName()))return c;if(c instanceof Container container)for(Component child:container.getComponents()){Component found=named(child,name);if(found!=null)return found;}return null;}
  static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container container)layout(container);}
  static void edt(Runnable work)throws Exception {SwingUtilities.invokeAndWait(work);}
  static final class Fixture implements AutoCloseable {
    final FeatureMapperWorkspace ext=new FeatureMapperWorkspace();
    final JPanel root;
    final List<Component> opened=new ArrayList<>();final List<String> titles=new ArrayList<>();
    Fixture()throws Exception {
      ext.initialize((MontoyaApi)host(MontoyaApi.class));root=(JPanel)get(ext,"root");
      ext.dialogOpener=(owner,title,content,closed)->{opened.add(content);titles.add(title);return null;};
      long time=System.currentTimeMillis();for(String path:List.of("/api/posts/102","/api/posts/103","/api/profile","/js-lab"))ext.mapper.accept(MapperTest.input(time,"GET","http://127.0.0.1:8088"+path,"","{\"note\":\"UI demonstration\"}","UI_DEMO"));
      edt(()->{try{call(ext,"refresh");}catch(Exception e){throw new RuntimeException(e);}});
    }
    void failure()throws Exception {Method m=FeatureMapperWorkspace.class.getDeclaredMethod("failure",String.class,Exception.class);m.setAccessible(true);m.invoke(ext,"프로젝트 저장",new java.io.IOException("PRIVATE_PACKET_CONTENT"));}
    public void close()throws Exception {call(ext,"unload");edt(()->{});}
  }
  static void run()throws Exception {
    AtomicInteger asynchronous=new AtomicInteger();var previous=Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{asynchronous.incrementAndGet();error.printStackTrace();});
    try(Fixture fixture=new Fixture()) {
      var ext=fixture.ext;JPanel root=fixture.root;
      edt(()->{
        root.setSize(1280,800);for(int i=0;i<6;i++)layout(root);
        JPanel actions=(JPanel)named(root,"fm.navigation.actions");
        MapperTest.check(Arrays.stream(actions.getComponents()).map(c->((JButton)c).getText()).toList().equals(List.of("기능 안내","설정","로그")),"Mapper toolbar uses help, settings, log order");
        MapperTest.check(((BorderLayout)root.getLayout()).getLayoutComponent(BorderLayout.SOUTH)==null,"Mapper footer is removed to enlarge packet editors");
        MapperTest.check(MapperUi.font().canDisplayUpTo("요청 응답 기능 안내 설정 로그")<0,"Mapper bundled Korean font renders all toolbar captions");
        try{for(String field:List.of("requestText","responseText")){
          JTextArea text=(JTextArea)get(ext,field);String packet="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"n\":2}";text.setText(packet);
          MapperTest.check(kr.packetui.PacketStyle.installed(text),"Mapper "+field+" uses shared packet syntax");
          JScrollPane scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,text);
          MapperTest.check(scroll.getRowHeader()!=null&&text.getText().equals(packet),"Mapper line gutter preserves packet CRLF and content");
        }}catch(Exception e){throw new RuntimeException(e);}
        JTable table=(JTable)named(root,"fm.history");
        MapperTest.check(table.getRowHeight()>=32&&table.getRowCount()==4,"Mapper keeps readable rows and every captured item");
        ((JButton)named(root,"fm.navigation.settings")).doClick(0);
        MapperTest.check(fixture.titles.get(0).endsWith("설정")&&named(fixture.opened.get(0),"fm.settings")!=null,"Mapper settings open separately from the current traffic view");
        JTextArea connection=(JTextArea)named(fixture.opened.get(0),"fm.browser.status");
        MapperTest.check(connection.getText().contains("인증 연결 대기")&&connection.getText().contains("HTTP 자동 분류 4건")&&connection.getText().contains("브라우저 연결 근거 0건"),"settings distinguish HTTP classification from actual helper authentication and linkage");
        MapperTest.check(!connection.getText().contains(ext.bridge.token),"connection status never displays the pairing token");
        try {((JCheckBox)get(ext,"scopeOnly")).doClick(0);}catch(Exception e){throw new RuntimeException(e);}
        ((JButton)named(root,"fm.capture")).doClick(0);
        MapperTest.check(((JButton)named(root,"fm.capture")).getText().equals("OFF"),"compact capture button can stop collection");
        ((JButton)named(root,"fm.capture")).doClick(0);
        MapperTest.check(((JButton)named(root,"fm.capture")).getText().equals("SCOPE 수집"),"capture indicator reflects the separate native Scope setting");
      });
      fixture.failure();
      edt(()->{
        try {
          ((JButton)named(root,"fm.navigation.logs")).doClick(0);
          JPanel panel=(JPanel)fixture.opened.get(1);JTable table=(JTable)named(panel,"diagnostics.table");
          MapperTest.check(table.getRowCount()==1,"Mapper errors populate the actual log view");
          Diagnostics diagnostics=(Diagnostics)get(ext,"diagnostics");
          MapperTest.check(diagnostics.text().contains("IOException")&&!diagnostics.text().contains("PRIVATE"),"Mapper log omits packet-bearing exception messages");
          ((JButton)named(panel,"diagnostics.clear")).doClick(0);
          MapperTest.check(table.getRowCount()==0&&diagnostics.size()==0,"Mapper clear log action releases diagnostic records");
          SwingUtilities.updateComponentTreeUI(root);
        }catch(Exception e){throw new RuntimeException(e);}
      });
      edt(()->{});edt(()->{});
      edt(()->{
        try{MapperTest.check(kr.packetui.PacketStyle.installed((JTextArea)get(ext,"requestText")),"Mapper packet style returns after host re-theming");}catch(Exception e){throw new RuntimeException(e);}
        MapperTest.check(root.getBackground().equals(MapperUi.background()),"Mapper local appearance returns after host look-and-feel replacement");
        MapperTest.check(named(root,"fm.navigation.logs").isVisible(),"Mapper log access survives host re-theming");
      });
      MapperTest.check(sent.get()==0,"Mapper layout, settings and logs send no HTTP requests");
    } finally {Thread.setDefaultUncaughtExceptionHandler(previous);}
    MapperTest.check(asynchronous.get()==0,"Mapper host re-theming and diagnostics emit no asynchronous errors");
  }
}
