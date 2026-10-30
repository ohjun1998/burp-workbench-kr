package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/** Modeless owned guide window stays above the fullscreen workspace without changing tools. */
final class VisualGuideWindow implements AutoCloseable {
  final VisualGuidePanel panel;
  final Component owner;
  JDialog window;
  boolean closed;
  private Rectangle normalBounds;
  private boolean maximized;
  VisualGuideWindow(Component owner){
    this.owner=owner;panel=new VisualGuidePanel();
    panel.addPropertyChangeListener("guide.screen",event->{if(!closed&&window!=null)window.setTitle("Guide · "+VisualGuideCatalog.screen((String)event.getNewValue()).title());});
    panel.maximize.addActionListener(e->toggleMaximized());
    bind("F11","wb.visual.guide.maximize",this::toggleMaximized);
    bind("ESCAPE","wb.visual.guide.hide",()->{if(window!=null)window.setVisible(false);});
  }
  private void bind(String key,String name,Runnable action){panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key),name);panel.getActionMap().put(name,new AbstractAction(){public void actionPerformed(ActionEvent e){action.run();}});}
  void open(String screen){
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(()->open(screen));return;}
    if(closed)return;panel.select(screen);
    if(GraphicsEnvironment.isHeadless())return;
    if(window==null){
      Window parent=SwingUtilities.getWindowAncestor(owner);
      window=new JDialog(parent,"Workbench Guide",Dialog.ModalityType.MODELESS);window.setName("wb.visual.guide.window");
      window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);window.setResizable(true);window.setContentPane(panel);
      Rectangle bounds=WorkbenchWindow.usableBounds(window.getGraphicsConfiguration());
      int width=Math.min(1440,bounds.width),height=Math.min(900,bounds.height);
      window.setMinimumSize(new Dimension(Math.min(900,bounds.width),Math.min(600,bounds.height)));
      window.setBounds(bounds.x+(bounds.width-width)/2,bounds.y+(bounds.height-height)/2,width,height);
    }
    window.setTitle("Guide · "+VisualGuideCatalog.screen(panel.selectedId()).title());
    window.setVisible(true);window.toFront();window.requestFocus();
  }
  void toggleMaximized(){
    if(closed||window==null)return;
    if(!maximized){normalBounds=window.getBounds();window.setBounds(WorkbenchWindow.usableBounds(window.getGraphicsConfiguration()));}
    else window.setBounds(WorkbenchWindow.reachable(normalBounds,window.getGraphicsConfiguration()));
    maximized=!maximized;panel.maximize.setText(maximized?"복원":"최대화");
  }
  public void close(){
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::close);return;}
    if(closed)return;closed=true;panel.close();if(window!=null){window.dispose();window=null;}
  }
}
