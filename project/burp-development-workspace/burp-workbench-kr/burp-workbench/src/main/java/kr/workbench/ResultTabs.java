package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/** Scrollable result titles with a permanent, accessible pair of navigation arrows. */
final class ResultTabs extends JPanel {
  final JTabbedPane content;
  final JPanel strip = new JPanel();
  final JViewport viewport = new JViewport();
  final JButton previous = new Arrow(false), next = new Arrow(true);
  final List<JToggleButton> titles = new ArrayList<>();

  ResultTabs(JTabbedPane content) {
    super(new BorderLayout(0, 4));
    this.content = content;
    setName("wb.js.result.workspace");
    content.putClientProperty("wb.external.tabs", true);
    strip.setLayout(new BoxLayout(strip, BoxLayout.X_AXIS));
    ButtonGroup group = new ButtonGroup();
    for (int index=0; index<content.getTabCount(); index++) {
      int tab=index;
      JToggleButton title=new Title(content.getTitleAt(index));
      title.setName("wb.js.tab."+index);
      title.getAccessibleContext().setAccessibleName(content.getTitleAt(index));
      title.addActionListener(event -> content.setSelectedIndex(tab));
      group.add(title);strip.add(title);titles.add(title);
    }
    viewport.setView(strip);
    viewport.setPreferredSize(new Dimension(200, 32));
    viewport.setMinimumSize(new Dimension(30, 32));
    previous.setName("wb.js.tabs.previous");next.setName("wb.js.tabs.next");
    previous.setToolTipText("결과 탭을 왼쪽으로 이동");next.setToolTipText("결과 탭을 오른쪽으로 이동");
    previous.getAccessibleContext().setAccessibleName("결과 탭 왼쪽 이동");
    next.getAccessibleContext().setAccessibleName("결과 탭 오른쪽 이동");
    previous.addActionListener(event -> scroll(-1));next.addActionListener(event -> scroll(1));
    JPanel arrows=new JPanel(new GridLayout(1,2,2,0));arrows.add(previous);arrows.add(next);
    JPanel header=new JPanel(new BorderLayout(4,0));header.add(viewport);header.add(arrows,BorderLayout.EAST);
    add(header,BorderLayout.NORTH);add(content);
    viewport.addChangeListener(event -> updateArrows());
    content.addChangeListener(event -> selectTitle());
    viewport.addComponentListener(new ComponentAdapter() {
      public void componentResized(ComponentEvent event) { revealSelected(); }
    });
    selectTitle();
  }

  private int limit() { return Math.max(0,strip.getPreferredSize().width-viewport.getExtentSize().width); }
  private void scroll(int direction) {
    int x=Math.max(0,Math.min(limit(),viewport.getViewPosition().x+direction*Math.max(80,viewport.getWidth()/2)));
    viewport.setViewPosition(new Point(x,0));updateArrows();
  }
  private void updateArrows() {
    int x=viewport.getViewPosition().x;
    previous.setEnabled(x>0);next.setEnabled(x<limit());
  }
  private void selectTitle() {
    for(int i=0;i<titles.size();i++)titles.get(i).setSelected(i==content.getSelectedIndex());
    SwingUtilities.invokeLater(this::revealSelected);
  }
  private void revealSelected() {
    int selected=content.getSelectedIndex();
    if(selected<0 || selected>=titles.size())return;
    Rectangle bounds=titles.get(selected).getBounds();
    int x=viewport.getViewPosition().x,width=viewport.getExtentSize().width;
    if(bounds.x<x)x=bounds.x;
    else if(bounds.x+bounds.width>x+width)x=bounds.x+bounds.width-width;
    viewport.setViewPosition(new Point(Math.max(0,Math.min(limit(),x)),0));updateArrows();
  }

  private static final class Title extends JToggleButton {
    Title(String text) { super(text);putClientProperty("wb.compact.button",true); }
    @Override public Dimension getPreferredSize() {
      return new Dimension(getFontMetrics(WorkbenchUi.font()).stringWidth(getText())+22,32);
    }
    @Override public Dimension getMaximumSize() { return getPreferredSize(); }
    @Override protected void paintComponent(Graphics graphics) {
      Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
      g.setColor(isSelected()?WorkbenchUi.selected():WorkbenchUi.raised());g.fillRect(0,0,getWidth(),getHeight());
      g.setFont(WorkbenchUi.font());g.setColor(WorkbenchUi.ink());
      FontMetrics fm=g.getFontMetrics();g.drawString(getText(),11,(getHeight()-fm.getHeight())/2+fm.getAscent());
      g.setColor(isSelected()?WorkbenchUi.accent():WorkbenchUi.line());
      g.fillRect(0,getHeight()-(isSelected()?2:1),getWidth(),isSelected()?2:1);
      if(hasFocus()){g.setColor(WorkbenchUi.accent());g.drawRect(2,2,getWidth()-5,getHeight()-5);}
      g.dispose();
    }
  }

  private static final class Arrow extends JButton {
    final boolean right;
    Arrow(boolean right) { this.right=right; }
    @Override public Dimension getPreferredSize() { return new Dimension(28,32); }
    @Override protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
      g.setColor(isEnabled()?WorkbenchUi.ink():WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));
      int x=getWidth()/2,y=getHeight()/2,d=right?1:-1;
      g.drawLine(x-2*d,y-4,x+2*d,y);g.drawLine(x+2*d,y,x-2*d,y+4);g.dispose();
    }
  }
}
