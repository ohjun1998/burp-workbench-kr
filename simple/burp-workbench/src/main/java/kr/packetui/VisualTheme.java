package kr.packetui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.AbstractBorder;

/** Drawing primitives shared by both distributions; never installs global Swing defaults. */
public final class VisualTheme {
  private VisualTheme() {}
  public static boolean dark(){Color c=UIManager.getColor("Panel.background");return c!=null&&c.getRed()+c.getGreen()+c.getBlue()<360;}
  public static Color line(){return dark()?new Color(51,61,74):new Color(222,231,241);}
  public static Color accent(){return new Color(255,101,37);}
  public static void smooth(Graphics2D g){g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);}
  public static void wash(Graphics2D g,int width,int height,Color top,Color bottom){g.setPaint(new GradientPaint(0,0,top,0,Math.max(1,height),bottom));g.fillRect(0,0,width,height);}
  public static AbstractBorder border(int top,int left,int bottom,int right){return new AbstractBorder(){
    public Insets getBorderInsets(Component c){return new Insets(top,left,bottom,right);}
    public Insets getBorderInsets(Component c,Insets in){in.set(top,left,bottom,right);return in;}
    public void paintBorder(Component c,Graphics graphics,int x,int y,int width,int height){Graphics2D g=(Graphics2D)graphics.create();smooth(g);g.setColor(c.hasFocus()?accent():line());g.drawRoundRect(x,y,width-1,height-1,8,8);g.dispose();}
  };}
  public static JLabel heading(String title){return new JLabel(title){protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();wash(g,getWidth(),getHeight(),dark()?new Color(31,39,49):new Color(250,252,255),dark()?new Color(26,33,42):new Color(242,247,252));g.setColor(line());g.drawLine(0,getHeight()-1,getWidth(),getHeight()-1);g.dispose();setOpaque(false);super.paintComponent(graphics);}};}
  public static Icon document(){return new Icon(){public int getIconWidth(){return 22;}public int getIconHeight(){return 22;}public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();smooth(g);g.translate(x,y);g.setColor(c.getForeground());g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.drawPolyline(new int[]{5,12,17,17,5,5},new int[]{2,2,7,20,20,2},6);g.drawPolyline(new int[]{12,12,17},new int[]{2,7,7},3);g.drawLine(8,11,11,11);g.drawLine(8,15,13,15);g.dispose();}};}
  public static Icon chevron(boolean down){return new Icon(){public int getIconWidth(){return 12;}public int getIconHeight(){return 12;}public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();smooth(g);g.setColor(c.getForeground());g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));if(down)g.drawPolyline(new int[]{x+2,x+6,x+10},new int[]{y+4,y+8,y+4},3);else g.drawPolyline(new int[]{x+4,x+8,x+4},new int[]{y+2,y+6,y+10},3);g.dispose();}};}
  public static Icon checkIcon(){return new Icon(){public int getIconWidth(){return 18;}public int getIconHeight(){return 18;}public void paintIcon(Component c,Graphics graphics,int x,int y){AbstractButton button=(AbstractButton)c;Graphics2D g=(Graphics2D)graphics.create();smooth(g);g.setColor(button.isSelected()&&button.isEnabled()?accent():dark()?new Color(23,29,37):Color.WHITE);g.fillRoundRect(x+1,y+1,15,15,4,4);g.setColor(button.hasFocus()?accent():line());g.drawRoundRect(x+1,y+1,15,15,4,4);if(button.isSelected()){g.setColor(button.isEnabled()?Color.WHITE:line());g.setStroke(new BasicStroke(1.8f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.drawPolyline(new int[]{x+4,x+7,x+13},new int[]{y+8,y+11,y+5},3);}g.dispose();}};}
  public static void emptyTable(Graphics graphics,JTable table){if(table.getRowCount()!=0||table.getHeight()<110||table.getWidth()<180)return;Graphics2D g=(Graphics2D)graphics.create();smooth(g);g.setColor(dark()?new Color(163,177,196):new Color(115,130,150));int x=table.getWidth()/2,y=Math.min(table.getHeight()/2,130);g.setStroke(new BasicStroke(1.4f));g.drawRoundRect(x-13,y-38,26,22,5,5);g.drawLine(x-7,y-30,x+7,y-30);g.drawLine(x-7,y-24,x+3,y-24);g.setFont(table.getFont());String title=table.getModel().getRowCount()>0?"검색 조건에 맞는 결과가 없습니다":"아직 표시할 항목이 없습니다";g.drawString(title,x-g.getFontMetrics().stringWidth(title)/2,y+4);g.setFont(table.getFont().deriveFont(12f));String hint=table.getModel().getRowCount()>0?"필터를 조정해 다시 확인하세요":"작업 결과는 이 목록에 표시됩니다";g.drawString(hint,x-g.getFontMetrics().stringWidth(hint)/2,y+28);g.dispose();}
}
