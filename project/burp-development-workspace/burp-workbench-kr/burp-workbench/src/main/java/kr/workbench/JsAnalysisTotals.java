package kr.workbench;

import java.awt.*;
import java.text.NumberFormat;
import java.util.*;
import javax.swing.*;

/** Unique findings across retained analyses, independent of the selected source. */
final class JsAnalysisTotals extends JPanel {
  final Lamp api=new Lamp("URL/API","wb.js.totals.api"),secret=new Lamp("토큰/API 키 후보","wb.js.totals.secret"),
      domain=new Lamp("도메인","wb.js.totals.domain"),cloud=new Lamp("클라우드 URL","wb.js.totals.cloud");
  JsAnalysisTotals(){
    super(new GridLayout(2,2,6,5));setName("wb.js.totals");add(api);add(secret);add(domain);add(cloud);
    setToolTipText("전체 분석 목록의 중복 제외 개수 · 보관 중인 분석 기준 · 새 분석·갱신·초기화에 반영");
  }
  void update(Collection<JsSourceCache.Entry> sources){
    Set<String> apis=new HashSet<>(),secrets=new HashSet<>(),domains=new HashSet<>(),clouds=new HashSet<>();
    for(var source:sources){var result=source.result();if(result==null)continue;
      for(var item:result.endpoints())apis.add(item.method()+" "+item.url());
      for(var item:result.secrets())secrets.add(item.fingerprint());
      for(var item:result.miner().domains())domains.add(item.host().toLowerCase(Locale.ROOT));
      for(var item:result.miner().clouds())clouds.add(item.url());
    }
    api.count(apis.size());secret.count(secrets.size());domain.count(domains.size());cloud.count(clouds.size());
  }
  static final class Lamp extends JLabel {
    final String caption;int count;
    Lamp(String caption,String name){this.caption=caption;setName(name);putClientProperty("wb.font.size",12);setOpaque(false);count(0);setToolTipText(caption+" · 전체 분석 목록에서 중복 제외 · 후보의 유효성은 미확인");}
    void count(int count){this.count=count;setText(caption+"  "+NumberFormat.getIntegerInstance(Locale.KOREA).format(count));revalidate();repaint();}
    @Override public Dimension getPreferredSize(){return new Dimension(getFontMetrics(WorkbenchUi.font().deriveFont(12f)).stringWidth(getText())+41,34);}
    @Override protected void paintComponent(Graphics graphics){
      Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
      boolean dark=WorkbenchUi.dark();Color green=count>0?new Color(42,215,117):new Color(92,137,111);
      g.setColor(dark?new Color(10,39,26):new Color(235,249,240));g.fillRoundRect(1,1,getWidth()-2,getHeight()-2,10,10);
      g.setColor(new Color(green.getRed(),green.getGreen(),green.getBlue(),count>0?170:75));g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,10,10);
      int y=getHeight()/2;
      for(int radius=9;radius>=5;radius-=2){g.setColor(new Color(green.getRed(),green.getGreen(),green.getBlue(),count>0?35:12));g.fillOval(15-radius,y-radius,radius*2,radius*2);}
      g.setColor(green);g.fillOval(11,y-4,8,8);
      g.setFont(WorkbenchUi.font().deriveFont(12f));g.setColor(dark?new Color(216,242,226):new Color(24,87,49));FontMetrics metrics=g.getFontMetrics();
      g.drawString(getText(),28,(getHeight()-metrics.getHeight())/2+metrics.getAscent());g.dispose();
    }
  }
}
