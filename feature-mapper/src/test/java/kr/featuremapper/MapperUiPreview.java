package kr.featuremapper;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Screenshots of production Swing with synthetic data; no native Burp windows. */
public final class MapperUiPreview {
  static final List<String> measurements=new ArrayList<>();
  static final List<Throwable> asynchronousErrors=Collections.synchronizedList(new ArrayList<>());
  static String theme;
  static void render(JPanel root,Path file,int width,int height)throws Exception {
    MapperUiWorkflowTest.edt(()->{root.setSize(width,height);for(int i=0;i<6;i++)MapperUiWorkflowTest.layout(root);});
    MapperUiWorkflowTest.edt(()->{});MapperUiWorkflowTest.edt(()->{});
    MapperUiWorkflowTest.edt(()->{
      try {
        for(int i=0;i<6;i++)MapperUiWorkflowTest.layout(root);
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);root.printAll(g);g.dispose();ImageIO.write(image,"png",file.toFile());
        measure(root,root,file.getFileName().toString(),width,height);
      }catch(Exception e){throw new RuntimeException(e);}
    });
  }
  static void measure(Component c,JPanel root,String view,int width,int height) {
    if(!c.isVisible())return;
    if(c instanceof JButton b&&!(b instanceof javax.swing.plaf.basic.BasicArrowButton)) {
      Rectangle r=SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);
      boolean visible=new Rectangle(0,0,width,height).contains(r);
      boolean scrollable=false;
      for(Container parent=c.getParent();parent!=null&&parent!=root;parent=parent.getParent()) {
        if(parent instanceof JViewport)scrollable=true;
        visible&=SwingUtilities.convertRectangle(parent,new Rectangle(0,0,parent.getWidth(),parent.getHeight()),root).contains(r);
      }
      measurements.add(String.join(",",theme,view,""+width,""+height,b.getText().replace(","," "),""+r.x,""+r.y,""+r.width,""+r.height,""+visible,""+scrollable));
    }
    if(c instanceof Container container)for(Component child:container.getComponents())measure(child,root,view,width,height);
  }
  public static void main(String[] args)throws Exception {
    Path out=Path.of(args[0]);Files.createDirectories(out);theme=args[1];int width=Integer.parseInt(args[2]),height=Integer.parseInt(args[3]);
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{asynchronousErrors.add(error);error.printStackTrace();});
    if(theme.equals("dark"))UIManager.put("Panel.background",new Color(17,22,29));
    else UIManager.put("Panel.background",new Color(246,248,251));
    measurements.add("theme,view,width,height,label,x,y,controlWidth,controlHeight,visible,scrollable");
    try(var fixture=new MapperUiWorkflowTest.Fixture()) {
      JPanel root=fixture.root;var ext=fixture.ext;
      MapperUiWorkflowTest.edt(()->{try{root.addNotify();root.setSize(width,height);for(int i=0;i<6;i++)MapperUiWorkflowTest.layout(root);((JTree)MapperUiWorkflowTest.get(ext,"tree")).setSelectionRow(0);((JTable)MapperUiWorkflowTest.get(ext,"table")).setRowSelectionInterval(0,0);}catch(Exception e){throw new RuntimeException(e);}});
      long until=System.nanoTime()+5_000_000_000L;
      while(((JTextArea)MapperUiWorkflowTest.get(ext,"requestText")).getText().isEmpty()&&System.nanoTime()<until)Thread.sleep(20);
      render(root,out.resolve("workspace.png"),width,height);
      for(String name:List.of("settings","help","logs")) {
        if(name.equals("logs"))fixture.failure();
        MapperUiWorkflowTest.edt(()->((JButton)MapperUiWorkflowTest.named(root,"fm.navigation."+name)).doClick(0));
        Component content=fixture.opened.get(fixture.opened.size()-1);
        if(name.equals("logs"))MapperUiWorkflowTest.edt(()->{JTable errors=(JTable)MapperUiWorkflowTest.named(content,"diagnostics.table");errors.setRowSelectionInterval(0,0);});
        java.util.concurrent.atomic.AtomicReference<JPanel> body=new java.util.concurrent.atomic.AtomicReference<>();
        MapperUiWorkflowTest.edt(()->body.set(MapperUi.windowBody(content,()->{})));render(body.get(),out.resolve(name+".png"),760,480);
      }
      MapperUiWorkflowTest.edt(()->SwingUtilities.updateComponentTreeUI(root));MapperUiWorkflowTest.edt(()->{});MapperUiWorkflowTest.edt(()->{});
      render(root,out.resolve("host-theme-recovered.png"),width,height);
      MapperUiWorkflowTest.edt(root::removeNotify);
    }
    Files.write(out.resolve("controls.csv"),measurements);
    if(!asynchronousErrors.isEmpty())throw new AssertionError("Asynchronous Swing failures: "+asynchronousErrors.size());
    System.out.println("Mapper UI rendered: "+theme+" "+width+"x"+height+"; production Swing, synthetic data, no native Burp GUI.");
  }
}
