package kr.workbench;

import java.awt.*;
import javax.swing.*;

/** Small theme-aware icons with text alternatives supplied by each owning control. */
final class WorkspaceIcons {
  static Icon of(String kind) { return new Icon() {
    public int getIconWidth(){return 16;} public int getIconHeight(){return 16;}
    public void paintIcon(Component c,Graphics graphics,int x,int y){
      Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);WorkbenchSkin.antialias(g);
      g.setColor(c instanceof JComponent j&&Boolean.TRUE.equals(j.getClientProperty("wb.icon.active"))?WorkbenchUi.accent():c.isEnabled()?WorkbenchUi.ink():WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));
      g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
      if(kind.equals("filter")){g.drawPolyline(new int[]{1,15,10,10,6,6,1},new int[]{2,2,8,14,12,8,2},7);}
      else if(kind.equals("gear")){g.drawOval(3,3,10,10);g.drawOval(6,6,4,4);for(int i=0;i<8;i++){double a=i*Math.PI/4;g.drawLine(8+(int)(5*Math.cos(a)),8+(int)(5*Math.sin(a)),8+(int)(7*Math.cos(a)),8+(int)(7*Math.sin(a)));}}
      else for(int i=0;i<3;i++)g.fillOval(1+i*6,7,3,3);
      g.dispose();
    }
  }; }
  static JButton button(String kind,String name,String tip){
    JButton b=new JButton(of(kind));b.setName(name);b.setToolTipText(tip);b.getAccessibleContext().setAccessibleName(tip);
    b.putClientProperty("wb.compact.button",true);b.setPreferredSize(new Dimension(36,32));b.setMinimumSize(new Dimension(32,30));return b;
  }
}
