package kr.workbench;

import java.awt.*;
import java.text.NumberFormat;
import java.util.*;
import javax.swing.*;

/** Unique findings across retained analyses, independent of the selected source. */
final class JsAnalysisTotals extends JPanel {
  final Lamp api=new Lamp("주요 경로","URL/API","wb.js.totals.api"),
      secret=new Lamp("민감한 정보","토큰/API 키 후보","wb.js.totals.secret"),
      domain=new Lamp("도메인","도메인","wb.js.totals.domain"),
      cloud=new Lamp("클라우드","클라우드 URL","wb.js.totals.cloud");
  JsAnalysisTotals(){
    super(new GridLayout(2,2,12,0));setName("wb.js.totals");setOpaque(false);
    setBorder(BorderFactory.createEmptyBorder(2,3,2,3));
    add(api);add(secret);add(domain);add(cloud);
    setToolTipText("전체 분석 목록의 중복 제외 개수 · 보관 중인 분석 기준 · 새 분석·갱신·초기화에 반영");
  }
  @Override protected void paintComponent(Graphics graphics) {
    super.paintComponent(graphics);
    Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
    Color line=WorkbenchUi.line();g.setColor(new Color(line.getRed(),line.getGreen(),line.getBlue(),100));
    g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,8,8);
    g.drawLine(8,getHeight()/2,getWidth()-9,getHeight()/2);
    g.drawLine(getWidth()/2,10,getWidth()/2,getHeight()/2-8);
    g.drawLine(getWidth()/2,getHeight()/2+8,getWidth()/2,getHeight()-11);g.dispose();
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
  void updateArchive(Collection<JsArchive.Summary> summaries,Collection<JsSourceCache.Entry> sources){
    Set<String> apis=new HashSet<>(),secrets=new HashSet<>(),domains=new HashSet<>(),clouds=new HashSet<>();
    for(var summary:summaries){apis.addAll(summary.apiKeys());secrets.addAll(summary.secretKeys());domains.addAll(summary.domains());clouds.addAll(summary.clouds());}
    for(var source:sources){var result=source.result();if(result==null)continue;for(var item:result.endpoints())apis.add(JsFindingGroups.key(item));for(var item:result.secrets())secrets.add(item.fingerprint());for(var item:result.miner().domains())domains.add(item.host());for(var item:result.miner().clouds())clouds.add(item.url());}
    api.count(apis.size());secret.count(secrets.size());domain.count(domains.size());cloud.count(clouds.size());
  }
  static final class Lamp extends JLabel {
    static final float LABEL_SIZE=13f,VALUE_SIZE=16f;
    static final int ROW_HEIGHT=40;
    final String caption;int count;
    Lamp(String caption,String fullCaption,String name){this.caption=caption;setName(name);putClientProperty("wb.font.size",13);setOpaque(false);count(0);setToolTipText(fullCaption+" · 전체 분석 목록에서 중복 제외 · 후보의 유효성은 미확인");}
    void count(int count){this.count=count;setText(caption+"  "+NumberFormat.getIntegerInstance(Locale.KOREA).format(count));revalidate();repaint();}
    String number(){return NumberFormat.getIntegerInstance(Locale.KOREA).format(count);}
    @Override public Dimension getPreferredSize(){
      int labelWidth=getFontMetrics(WorkbenchUi.font().deriveFont(LABEL_SIZE)).stringWidth(caption);
      int valueWidth=getFontMetrics(WorkbenchUi.font().deriveFont(Font.BOLD,VALUE_SIZE)).stringWidth(number());
      return new Dimension(Math.max(140,labelWidth+valueWidth+54),ROW_HEIGHT);
    }
    @Override protected void paintComponent(Graphics graphics){
      Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
      boolean dark=WorkbenchUi.dark();
      Color red=count>0?(dark?new Color(255,83,91):new Color(208,49,60))
          :(dark?new Color(174,72,80):new Color(167,89,96));
      int y=getHeight()/2;
      for(int radius=8;radius>=5;radius-=2){g.setColor(new Color(red.getRed(),red.getGreen(),red.getBlue(),count>0?30:10));g.fillOval(14-radius,y-radius,radius*2,radius*2);}
      g.setColor(red);g.fillOval(11,y-3,6,6);
      g.setFont(WorkbenchUi.font().deriveFont(LABEL_SIZE));g.setColor(WorkbenchUi.ink());FontMetrics metrics=g.getFontMetrics();
      g.drawString(caption,29,(getHeight()-metrics.getHeight())/2+metrics.getAscent());
      g.setFont(WorkbenchUi.font().deriveFont(Font.BOLD,VALUE_SIZE));metrics=g.getFontMetrics();
      g.drawString(number(),getWidth()-12-metrics.stringWidth(number()),(getHeight()-metrics.getHeight())/2+metrics.getAscent());g.dispose();
    }
  }
}

