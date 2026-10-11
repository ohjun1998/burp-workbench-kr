package check;
import burp.api.montoya.*;
import java.awt.*;
import java.nio.file.*;
import java.util.ArrayList;
import javax.swing.*;
import javax.imageio.ImageIO;
public class GuiAuditObserver implements BurpExtension {
 static Component named(Component c,String name){if(name.equals(c.getName()))return c;if(c instanceof Container p)for(Component child:p.getComponents()){Component found=named(child,name);if(found!=null)return found;}return null;}
 static JTabbedPane containing(Component c,String title){if(c instanceof JTabbedPane t)for(int i=0;i<t.getTabCount();i++)if(title.equals(t.getTitleAt(i)))return t;if(c instanceof Container p)for(Component child:p.getComponents()){var found=containing(child,title);if(found!=null)return found;}return null;}
 static void select(JTabbedPane pane,String title){for(int i=0;i<pane.getTabCount();i++)if(title.equals(pane.getTitleAt(i))){pane.setSelectedIndex(i);return;}throw new IllegalStateException("Missing UI tab: "+title);}
 public void initialize(MontoyaApi api){api.extension().setName("Isolated actual Burp GUI audit");Thread thread=new Thread(()->{Path out=Path.of(System.getProperty("test.run.root"));try{
  Thread.sleep(6000);Files.writeString(out.resolve("burp-version.txt"),api.burpSuite().version().toString());Component[] root={null};JTabbedPane[] pages={null};
  SwingUtilities.invokeAndWait(()->{for(Window w:Window.getWindows())if(w.isShowing()){Component r=named(w,"wb.root");if(r!=null){root[0]=r;JTabbedPane top=containing(w,"Workbench KR");select(top,"Workbench KR");pages[0]=containing(r,"활동 추적");if(w instanceof Frame f){f.setLocation(0,0);f.setSize(1600,1000);}break;}}});
  if(root[0]==null)throw new AssertionError("Release extension UI was not found in actual Burp");
  var names=new ArrayList<String>();SwingUtilities.invokeAndWait(()->{for(int i=0;i<pages[0].getTabCount();i++)names.add(pages[0].getTitleAt(i));});Files.writeString(out.resolve("gui-tabs.txt"),String.join("\n",names));
  for(String title:names){if(title.equals("설정"))continue;SwingUtilities.invokeAndWait(()->select(pages[0],title));Thread.sleep(450);ImageIO.write(new Robot().createScreenCapture(new Rectangle(0,0,1600,1000)),"png",out.resolve("screen-"+title+".png").toFile());}
  SwingUtilities.invokeAndWait(()->select(pages[0],"활동 추적"));for(String title:new String[]{"계정별 점검","업무별 현황"}){SwingUtilities.invokeAndWait(()->{var sub=containing(root[0],title);if(sub==null)throw new IllegalStateException("Missing activity tab "+title);select(sub,title);});Thread.sleep(450);ImageIO.write(new Robot().createScreenCapture(new Rectangle(0,0,1600,1000)),"png",out.resolve("screen-"+title+".png").toFile());}
  Files.writeString(out.resolve("gui-ready.txt"),"Actual Burp GUI release tab and account/work pages selected and captured; native audit follows.");
  api.scope().includeInScope("http://127.0.0.1:18299/");try(var loader=new java.net.URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("test.release.jar")).toUri().toURL(),Path.of(System.getProperty("test.audit.classes")).toUri().toURL()},getClass().getClassLoader())){
   Class.forName("kr.workbench.RuntimeWholeAudit",true,loader).getMethod("run",MontoyaApi.class,Path.class).invoke(null,api,out);
   Class.forName("kr.workbench.RuntimeAssessmentAudit",true,loader).getMethod("run",MontoyaApi.class,Path.class).invoke(null,api,out);
  }
  Files.writeString(out.resolve("native-ready.txt"),api.burpSuite().version().toString());
 }catch(Throwable e){e.printStackTrace();try{Files.writeString(out.resolve("gui-failure.txt"),e.toString());}catch(Exception ignored){}}},"actual-burp-gui-audit");thread.setDaemon(true);thread.start();}
}
