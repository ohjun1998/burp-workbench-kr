package kr.workbench;
import java.awt.*;
import javax.swing.*;
/** Clickable role summary; complete identity and unverified state stay accessible. */
final class AccountSummaryCard extends JButton {
  private final int slot;private String title="",identity="",description="";private boolean reference;
  AccountSummaryCard(int slot){this.slot=slot;setHorizontalAlignment(LEFT);}
  void summary(String title,String identity,String description,boolean reference){
    this.title=title;this.identity=identity;this.description=description;this.reference=reference;setText(title);
    String detail=title+" · "+identity+" · "+description+(reference?" · 계정 1":"")+" · 역할 연결은 세션·권한 검증 결과가 아닙니다.";
    setToolTipText(detail);getAccessibleContext().setAccessibleName(detail);repaint();
  }
  public Dimension getPreferredSize(){return new Dimension(180,WorkbenchUi.displayPixels(42));}
  public Dimension getMinimumSize(){return new Dimension(130,WorkbenchUi.displayPixels(42));}
  protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
    g.setColor(getModel().isRollover()?WorkbenchUi.raised():WorkbenchUi.surface());g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,9,9);
    g.setColor(hasFocus()?WorkbenchUi.accent():WorkbenchUi.line());g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,9,9);
    boolean linked=!identity.isBlank()&&!identity.equals("계정 연결 필요");
    Color color=slot==3?WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL):WorkbenchUi.toneInk(slot==0?WorkbenchUi.Tone.ACTIVE:slot==1?WorkbenchUi.Tone.SUCCESS:WorkbenchUi.Tone.WARNING);
    int cy=getHeight()/2;g.setColor(WorkbenchUi.toneFill(slot==3?WorkbenchUi.Tone.NEUTRAL:slot==1?WorkbenchUi.Tone.SUCCESS:WorkbenchUi.Tone.ACTIVE));g.fillRoundRect(10,cy-12,24,24,7,7);
    g.setFont(WorkbenchUi.font().deriveFont(Font.BOLD,WorkbenchUi.displaySize(12f)));g.setColor(color);String digit=slot==3?"—":Integer.toString(slot+1);g.drawString(digit,22-g.getFontMetrics().stringWidth(digit)/2,cy+4);
    String badge=linked?(slot==3?"비로그인":reference?"계정 1":"연결됨"):"미연결";g.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f)));int bw=Math.min(58,g.getFontMetrics().stringWidth(badge)+14),bx=getWidth()-bw-10;
    if(getWidth()>170){g.setColor(WorkbenchUi.toneFill(linked&&slot!=3?WorkbenchUi.Tone.ACTIVE:WorkbenchUi.Tone.NEUTRAL));g.fillRoundRect(bx,cy-10,bw,20,5,5);g.setColor(linked&&slot!=3?WorkbenchUi.accent():WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));g.drawString(badge,bx+7,cy+4);}else bx=getWidth()-3;
    g.setFont(WorkbenchUi.font().deriveFont(Font.BOLD,WorkbenchUi.displaySize(13f)));g.setColor(WorkbenchUi.ink());g.drawString(fit(g,title,Math.max(0,bx-48)),43,cy+5);g.dispose();
  }
  private static String fit(Graphics2D g,String text,int width){if(g.getFontMetrics().stringWidth(text)<=width)return text;int end=text.length();while(end>0&&g.getFontMetrics().stringWidth(text.substring(0,end)+"…")>width)end--;return end==0?"":text.substring(0,end)+"…";}
}
