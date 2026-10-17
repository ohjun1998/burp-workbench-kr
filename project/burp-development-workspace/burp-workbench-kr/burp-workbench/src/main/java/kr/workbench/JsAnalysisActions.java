package kr.workbench;

import java.awt.*;
import javax.swing.*;

/** Two compact action rows, with the analysis windows kept together. */
final class JsAnalysisActions {
  private JsAnalysisActions() {}

  static JPanel create(JToggleButton passive, JButton target, JButton list, JButton details,
      JButton request, JButton locate, JButton tools, JButton more) {
    JButton[] buttons={target,list,details,request,locate,tools,more};
    String[] icons={"target","list","details","request","locate","tools","more"};
    for(int i=0;i<buttons.length;i++) {
      buttons[i].putClientProperty("wb.compact.button",true);
      buttons[i].putClientProperty("wb.font.size",13);
      buttons[i].setIcon(icon(icons[i]));
      buttons[i].setIconTextGap(6);
    }
    more.setText("");more.setName("wb.js.more");more.setToolTipText("더보기");more.getAccessibleContext().setAccessibleName("더보기");more.setPreferredSize(new Dimension(36,34));more.setMinimumSize(new Dimension(32,30));
    JPanel windows=new JPanel(new GridLayout(1,2,0,0)) {
      @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
        g.setColor(WorkbenchUi.surface());g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,9,9);
        g.setColor(WorkbenchUi.line());g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,9,9);
        Color line=WorkbenchUi.line();g.setColor(new Color(line.getRed(),line.getGreen(),line.getBlue(),110));
        g.drawLine(getWidth()/2,11,getWidth()/2,getHeight()-12);g.dispose();
      }
    };
    windows.setName("wb.js.analysis.group");windows.setOpaque(false);
    windows.setBorder(BorderFactory.createEmptyBorder(3,3,3,3));
    list.putClientProperty("wb.segment.button",true);details.putClientProperty("wb.segment.button",true);
    windows.add(list);windows.add(details);
    JComponent separator=new JComponent() {
      @Override public Dimension getPreferredSize(){return new Dimension(6,38);}
      @Override protected void paintComponent(Graphics graphics) {
        Color line=WorkbenchUi.line();graphics.setColor(new Color(line.getRed(),line.getGreen(),line.getBlue(),90));
        graphics.drawLine(getWidth()/2,(getHeight()-12)/2,getWidth()/2,(getHeight()+12)/2);
      }
    };
    separator.setName("wb.js.actions.separator");
    JPanel primary=row(passive,target,separator,windows),secondary=row(request,locate,tools,more);
    primary.setName("wb.js.toolbar.primary");secondary.setName("wb.js.toolbar.secondary");
    JPanel toolbar=new JPanel(new BorderLayout(0,9));toolbar.setName("wb.js.toolbar");
    toolbar.add(primary,BorderLayout.NORTH);toolbar.add(secondary,BorderLayout.SOUTH);
    return toolbar;
  }

  private static JPanel row(Component... components) {
    JPanel row=new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.LEFT,10,0));
    for(Component component:components)row.add(component);
    return row;
  }

  private static Icon icon(String kind) {
    return new Icon() {
      public int getIconWidth(){return 16;}
      public int getIconHeight(){return 16;}
      public void paintIcon(Component component,Graphics graphics,int x,int y) {
        Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);WorkbenchSkin.antialias(g);
        g.setColor(component.isEnabled()?WorkbenchUi.ink():WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));
        g.setStroke(new BasicStroke(1.3f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        switch(kind) {
          case "target" -> {g.drawOval(4,4,8,8);g.drawOval(7,7,2,2);for(int i=0;i<8;i++){double a=i*Math.PI/4;g.drawLine(8+(int)(6*Math.cos(a)),8+(int)(6*Math.sin(a)),8+(int)(7*Math.cos(a)),8+(int)(7*Math.sin(a)));}}
          case "list" -> {for(int i=0;i<3;i++){int yy=3+i*5;g.fillOval(1,yy-1,2,2);g.drawLine(6,yy,15,yy);}}
          case "details" -> {g.drawRoundRect(3,1,10,14,2,2);g.drawLine(6,6,10,6);g.drawLine(6,9,10,9);g.drawLine(6,12,9,12);}
          case "request" -> {g.drawRoundRect(1,3,10,9,3,3);g.drawLine(7,8,15,8);g.drawLine(12,5,15,8);g.drawLine(12,11,15,8);}
          case "locate" -> {g.drawOval(3,1,10,10);g.drawOval(7,5,2,2);g.drawLine(4,10,8,15);g.drawLine(12,10,8,15);}
          case "tools" -> {g.drawLine(3,13,11,5);g.drawLine(5,15,13,7);g.drawArc(7,0,8,8,170,270);g.drawLine(11,1,11,5);g.drawLine(11,5,15,5);}
          default -> {for(int i=0;i<3;i++)g.fillOval(1+i*6,7,3,3);}
        }
        g.dispose();
      }
    };
  }
}
