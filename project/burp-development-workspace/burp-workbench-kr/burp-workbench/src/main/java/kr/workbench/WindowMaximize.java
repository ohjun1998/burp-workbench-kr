package kr.workbench;

import java.awt.*;
import javax.swing.*;

/** Maximizes modeless dialogs within the current monitor's usable area. */
final class WindowMaximize extends JButton {
  interface Host {
    Rectangle bounds();
    Rectangle available();
    void bounds(Rectangle bounds);
  }
  private Rectangle restore;

  WindowMaximize(Component owner) {
    super("최대화");setName("wb.js.repeater.maximize");
    setToolTipText("현재 화면에 맞게 최대화 · 다시 누르면 이전 크기로 복원");
    addActionListener(event->{
      Window window=SwingUtilities.getWindowAncestor(owner);
      if(window==null)return;
      toggle(new Host(){
        public Rectangle bounds(){return window.getBounds();}
        public Rectangle available(){
          GraphicsConfiguration config=window.getGraphicsConfiguration();
          Rectangle bounds=new Rectangle(config.getBounds());
          Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(config);
          return new Rectangle(bounds.x+insets.left,bounds.y+insets.top,
              bounds.width-insets.left-insets.right,bounds.height-insets.top-insets.bottom);
        }
        public void bounds(Rectangle bounds){window.setBounds(bounds);}
      });
    });
  }

  void toggle(Host host) {
    if(restore==null){restore=new Rectangle(host.bounds());host.bounds(new Rectangle(host.available()));setText("이전 크기");}
    else {
      Rectangle area=host.available(),normal=new Rectangle(restore);restore=null;
      normal.width=Math.min(normal.width,area.width);normal.height=Math.min(normal.height,area.height);
      normal.x=Math.max(area.x,Math.min(normal.x,area.x+area.width-normal.width));
      normal.y=Math.max(area.y,Math.min(normal.y,area.y+area.height-normal.height));
      host.bounds(normal);setText("최대화");
    }
  }
}
