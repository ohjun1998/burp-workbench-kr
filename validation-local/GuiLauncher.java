package check;
import java.awt.*;
import java.nio.file.*;
import javax.swing.*;
import javax.imageio.ImageIO;
public class GuiLauncher {
 static Path out=Path.of(System.getProperty("test.run.root"));
 static void dump(Component c,StringBuilder s,String indent){
  if(!c.isVisible())return;
  s.append(indent).append(c.getClass().getName()).append(" name=").append(c.getName());
  if(c instanceof AbstractButton b)s.append(" text=").append(b.getText());
  if(c instanceof JLabel l)s.append(" text=").append(l.getText());
  if(c instanceof JTabbedPane t){s.append(" tabs=");for(int i=0;i<t.getTabCount();i++)s.append(i).append(':').append(t.getTitleAt(i)).append(';');}
  s.append('\n');if(c instanceof Container p)for(Component child:p.getComponents())dump(child,s,indent+" ");
 }
 static boolean click(Component c,String title){
  if(c instanceof AbstractButton b&&b.isShowing()&&b.isEnabled()&&title.equals(b.getText())){b.doClick();return true;}
  if(c instanceof Container p)for(Component child:p.getComponents())if(click(child,title))return true;return false;
 }
 public static void main(String[] args)throws Exception{
  Thread monitor=new Thread(()->{try{while(true){Thread.sleep(1500);SwingUtilities.invokeAndWait(()->{try{
   StringBuilder s=new StringBuilder();for(Window w:Window.getWindows())if(w.isShowing())dump(w,s,"");Files.writeString(out.resolve("ui-tree.txt"),s);
   Path cmd=out.resolve("click.txt");if(Files.exists(cmd)){String title=Files.readString(cmd).strip();Files.delete(cmd);boolean clicked=false;for(Window w:Window.getWindows())if(w.isShowing()&&click(w,title)){clicked=true;break;}Files.writeString(out.resolve("click-result.txt"),title+" clicked="+clicked);}
  }catch(Exception e){e.printStackTrace();}});ImageIO.write(new Robot().createScreenCapture(new Rectangle(0,0,1600,1000)),"png",out.resolve("current-screen.png").toFile());}}catch(Exception e){e.printStackTrace();}},"isolated-ui-monitor");monitor.setDaemon(true);monitor.start();
  Class.forName("burp.StartBurp").getMethod("main",String[].class).invoke(null,(Object)args);
 }
}
