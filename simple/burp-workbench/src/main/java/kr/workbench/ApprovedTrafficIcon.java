package kr.workbench;

import java.awt.*;
import java.awt.geom.*;
import javax.swing.Icon;

/** The approved reference's 24-unit outline paths, rendered locally without image assets. */
final class ApprovedTrafficIcon implements Icon {
  private final String kind;
  private final Color color;
  ApprovedTrafficIcon(String kind,Color color){this.kind=kind;this.color=color;}
  public int getIconWidth(){return 15;}
  public int getIconHeight(){return 15;}
  private static void line(Graphics2D g,double... points){Path2D p=new Path2D.Double();p.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)p.lineTo(points[i],points[i+1]);g.draw(p);}
  public void paintIcon(Component c,Graphics graphics,int x,int y){
    Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.translate(x,y);g.scale(15d/24,15d/24);g.setColor(color);g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
    switch(kind){
      case "chevron"->line(g,8,10,12,14,16,10);
      case "traffic","dashboard"->{line(g,3,3,3,21,21,21);line(g,7,16,7,12);line(g,12,16,12,7);line(g,17,16,17,9);}
      case "mapper"->{g.draw(new RoundRectangle2D.Double(9,2,6,5,2,2));line(g,12,7,12,12);line(g,5,16,5,12,19,12,19,16);g.draw(new RoundRectangle2D.Double(2,16,6,5,2,2));g.draw(new RoundRectangle2D.Double(16,16,6,5,2,2));}
      case "js"->{line(g,8,6,2,12,8,18);line(g,16,6,22,12,16,18);line(g,13,3,11,21);}
      case "environment"->{line(g,12,3,22,9,12,15,2,9,12,3);line(g,2,14,12,20,22,14);line(g,2,18,12,24,22,18);}
      case "exposure.search","exposure.cve","exposure"->{g.draw(new Ellipse2D.Double(4,4,13,13));line(g,16,16,20.5,20.5);}
      case "auth"->{g.draw(new Ellipse2D.Double(8,3,8,8));Path2D p=new Path2D.Double();p.moveTo(4,22);p.lineTo(4,19);p.curveTo(4,8.4,20,8.4,20,19);p.lineTo(20,22);g.draw(p);}
      case "response.recon","response"->{Path2D p=new Path2D.Double();p.moveTo(12,2);p.lineTo(21,6);p.lineTo(21,13);p.curveTo(21,18,12,22,12,22);p.curveTo(12,22,3,18,3,13);p.lineTo(3,6);p.closePath();g.draw(p);line(g,8,12,11,15,16,9);}
      case "payload"->{g.draw(new RoundRectangle2D.Double(5,10,14,12,2,2));Path2D p=new Path2D.Double();p.moveTo(8,10);p.lineTo(8,6);p.curveTo(8,.7,16,.7,16,6);p.lineTo(16,10);g.draw(p);}
      case "data","jwt"->{for(int a:new int[]{3,14})for(int b:new int[]{3,14})g.draw(new RoundRectangle2D.Double(a,b,7,7,2,2));}
      case "activity"->line(g,5,12,9,16,19,5);
      case "bounty","evidence"->{line(g,5,3,14,3,19,8,19,21,5,21,5,3);line(g,14,3,14,9,19,9);line(g,8,13,16,13);line(g,8,17,14,17);}
      case "moon"->{Path2D p=new Path2D.Double();p.moveTo(20,15);p.curveTo(12,19,5,11,9,3);p.curveTo(-3,6,5,26,17,20);p.curveTo(19,19,20,17,20,15);g.draw(p);}
      default->g.draw(new Ellipse2D.Double(4,4,16,16));
    }
    g.dispose();
  }
}
