package kr.workbench;

import java.awt.*;
import javax.swing.*;

/** Small theme-aware icons with text alternatives supplied by each owning control. */
final class WorkspaceIcons {
  static Icon of(String kind) { return new Icon() {
    public int getIconWidth(){return 16;} public int getIconHeight(){return 16;}
    public void paintIcon(Component c,Graphics graphics,int x,int y){
      Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);WorkbenchSkin.antialias(g);
      g.setColor(c instanceof JComponent j&&Boolean.TRUE.equals(j.getClientProperty("wb.icon.active"))?WorkbenchUi.accent():c.isEnabled()?c.getForeground():WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));
      g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
      if(kind.equals("menu")){for(int i=0;i<3;i++)g.drawLine(1,3+i*5,15,3+i*5);}
      else if(kind.equals("search")){g.drawOval(1,1,10,10);g.drawLine(10,10,15,15);}
      else if(kind.equals("target")){g.drawOval(2,2,12,12);g.drawOval(6,6,4,4);}
      else if(kind.equals("filter")){g.drawPolyline(new int[]{1,15,10,10,6,6,1},new int[]{2,2,8,14,12,8,2},7);}
      else if(kind.equals("gear")){g.drawOval(3,3,10,10);g.drawOval(6,6,4,4);for(int i=0;i<8;i++){double a=i*Math.PI/4;g.drawLine(8+(int)(5*Math.cos(a)),8+(int)(5*Math.sin(a)),8+(int)(7*Math.cos(a)),8+(int)(7*Math.sin(a)));}}
      else if(kind.equals("copy")){g.drawRoundRect(5,4,9,11,2,2);g.drawPolyline(new int[]{10,10,2,2,4},new int[]{3,1,1,12,12},5);}
      else if(kind.equals("more"))for(int i=0;i<3;i++)g.fillOval(1+i*6,7,3,3);
      else if(kind.equals("security")){g.drawPolygon(new int[]{8,14,13,8,3,2},new int[]{1,4,10,15,10,4},6);g.drawPolyline(new int[]{5,7,11},new int[]{8,10,6},3);}
      else if(kind.equals("discovery")){g.drawRect(6,1,4,4);g.drawLine(8,5,8,9);g.drawLine(3,9,13,9);g.drawLine(3,9,3,11);g.drawLine(13,9,13,11);g.drawRect(1,11,4,4);g.drawRect(11,11,4,4);}
      else if(kind.equals("group")){for(int i=0;i<4;i++)g.drawRoundRect(1+(i%2)*8,1+(i/2)*8,5,5,2,2);}
      else if(kind.equals("auth")){g.drawOval(5,1,6,6);g.drawArc(2,8,12,12,0,180);g.drawLine(2,14,14,14);}
      else if(kind.equals("traffic")){g.drawLine(3,2,3,13);g.drawLine(3,13,13,13);g.drawPolyline(new int[]{5,8,10,14},new int[]{10,5,8,3},4);}
      else if(kind.equals("mapper")){g.drawRoundRect(2,1,12,14,2,2);g.drawLine(5,5,11,5);g.drawLine(8,3,8,10);g.drawLine(5,8,8,11);g.drawLine(11,8,8,11);}
      else if(kind.equals("undo")||kind.equals("redo")){boolean undo=kind.equals("undo");if(!undo){g.translate(16,0);g.scale(-1,1);}g.drawArc(3,4,11,10,-90,240);g.drawPolyline(new int[]{6,2,6},new int[]{2,6,9},3);}
      else if(kind.equals("data")){g.drawRoundRect(1,1,14,14,2,2);g.drawLine(4,8,12,8);g.drawLine(8,4,8,12);}
      else if(kind.equals("activity")){g.drawRoundRect(2,1,12,14,2,2);g.drawPolyline(new int[]{5,7,11},new int[]{8,10,5},3);}
      else {g.drawRoundRect(3,1,10,14,2,2);g.drawLine(6,5,10,5);g.drawLine(6,8,10,8);if(kind.equals("js")){g.drawLine(1,10,1,5);g.drawLine(15,5,15,10);}else g.drawLine(6,11,9,11);}
      g.dispose();
    }
  }; }
  static JButton button(String kind,String name,String tip){
    JButton b=new JButton(of(kind));b.setName(name);b.setToolTipText(tip);b.getAccessibleContext().setAccessibleName(tip);
    b.putClientProperty("wb.compact.button",true);b.setPreferredSize(new Dimension(36,32));b.setMinimumSize(new Dimension(32,30));return b;
  }
}
