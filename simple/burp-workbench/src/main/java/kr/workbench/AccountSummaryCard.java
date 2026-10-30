package kr.workbench;
import java.awt.*;
import javax.swing.*;
/** A real account assignment button with a compact, three-level summary. */
final class AccountSummaryCard extends JButton {
  private final int slot;private String title="",identity="",description="";private boolean reference;
  AccountSummaryCard(int slot){this.slot=slot;setHorizontalAlignment(LEFT);}
  void summary(String title,String identity,String description,boolean reference){this.title=title;this.identity=identity;this.description=description;this.reference=reference;setText(title);getAccessibleContext().setAccessibleName(title+" · "+identity+" · "+description+(reference?" · 계정 1":""));repaint();}
  public Dimension getPreferredSize(){return new Dimension(180,WorkbenchUi.displayPixels(70));}
  public Dimension getMinimumSize(){return new Dimension(140,WorkbenchUi.displayPixels(70));}
  protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
    Color surface=WorkbenchUi.surface();g.setPaint(new GradientPaint(0,0,surface,0,getHeight(),getModel().isRollover()?WorkbenchUi.raised():surface));g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,9,9);
    g.setColor(hasFocus()?WorkbenchUi.accent():WorkbenchUi.line());g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,9,9);
    Color color=slot==3?WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL):WorkbenchUi.toneInk(slot==0?WorkbenchUi.Tone.ACTIVE:slot==1?WorkbenchUi.Tone.SUCCESS:WorkbenchUi.Tone.WARNING);
    g.setColor(color);int cy=WorkbenchUi.displayPixels(22);g.fillOval(18,cy-8,13,13);g.fillRoundRect(13,cy+7,23,12,7,7);
    int x=48,base=WorkbenchUi.displayPixels(23),available=Math.max(0,getWidth()-x-12);
    g.setFont(WorkbenchUi.font().deriveFont(Font.BOLD,WorkbenchUi.displaySize(16)));g.setColor(WorkbenchUi.ink());
    int badge=reference&&available>160?70:0;String caption=fit(g,title,available-badge);g.drawString(caption,x,base);
    if(badge>0){int bx=x+g.getFontMetrics().stringWidth(caption)+8;g.setColor(WorkbenchUi.toneFill(WorkbenchUi.Tone.ACTIVE));g.fillRoundRect(bx,base-17,62,23,6,6);g.setColor(WorkbenchUi.toneInk(WorkbenchUi.Tone.ACTIVE));g.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(12)));g.drawString("계정 1",bx+6,base);}
    g.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(13)));g.setColor(WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));g.drawString(fit(g,identity,available),x,base+WorkbenchUi.displayPixels(19));g.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(12)));g.drawString(fit(g,description,available),x,base+WorkbenchUi.displayPixels(36));g.dispose();
  }
  private static String fit(Graphics2D g,String text,int width){if(g.getFontMetrics().stringWidth(text)<=width)return text;int end=text.length();while(end>0&&g.getFontMetrics().stringWidth(text.substring(0,end)+"…")>width)end--;return end==0?"":text.substring(0,end)+"…";}
}
