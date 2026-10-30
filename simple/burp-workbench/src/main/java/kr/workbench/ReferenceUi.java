package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** Reference component shapes backed by existing models and explicit actions. */
public final class ReferenceUi {
  private ReferenceUi() {}
  public static JToggleButton step(int number,String label){return new Step(number,label);}
  public static JToggleButton tab(String label){return new Tab(label);}
  public static JLabel arrow(){JLabel label=new JLabel(WorkspaceIcons.of("arrow"));label.setBorder(BorderFactory.createEmptyBorder(0,12,0,12));return label;}
  public static JPanel stepRow(Component... children){JPanel row=new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.LEFT,0,0)){public Dimension getMinimumSize(){return getPreferredSize();}};for(Component c:children)row.add(c);return row;}
  private static class Tab extends JToggleButton {
    Tab(String text){super(text);putClientProperty("wb.reference.tab",true);setBorder(BorderFactory.createEmptyBorder(8,18,8,18));}
    public Dimension getPreferredSize(){return new Dimension(getFontMetrics(WorkbenchUi.font()).stringWidth(getText())+36,44);}
    protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.surface());g.fillRect(0,0,getWidth(),getHeight());g.setFont(WorkbenchUi.font().deriveFont(isSelected()?Font.BOLD:Font.PLAIN));g.setColor(isSelected()?WorkbenchUi.accent():WorkbenchUi.ink());g.drawString(getText(),18,(getHeight()-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());g.setColor(isSelected()?WorkbenchUi.accent():WorkbenchUi.line());g.fillRect(0,getHeight()-(isSelected()?2:1),getWidth(),isSelected()?2:1);if(hasFocus())g.drawRect(2,2,getWidth()-5,getHeight()-5);g.dispose();}
  }
  private static final class Step extends Tab {
    final int number;Step(int number,String text){super(text);this.number=number;getAccessibleContext().setAccessibleName(number+" "+text);}
    public Dimension getPreferredSize(){return new Dimension(54+getFontMetrics(WorkbenchUi.font()).stringWidth(getText())+20,54);}
    protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);int y=(getHeight()-32)/2;g.setColor(isSelected()?WorkbenchUi.accent():WorkbenchUi.surface());g.fillOval(2,y,32,32);g.setColor(isSelected()?WorkbenchUi.accent():WorkbenchUi.line());g.drawOval(2,y,32,32);g.setFont(WorkbenchUi.font().deriveFont(Font.BOLD,16f));g.setColor(isSelected()?Color.WHITE:WorkbenchUi.ink());String digit=Integer.toString(number);g.drawString(digit,18-g.getFontMetrics().stringWidth(digit)/2,y+22);g.setColor(isSelected()?WorkbenchUi.ink():WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));g.setFont(WorkbenchUi.font().deriveFont(isSelected()?Font.BOLD:Font.PLAIN,16f));g.drawString(getText(),48,y+22);if(isSelected()){g.setColor(WorkbenchUi.accent());g.fillRect(42,getHeight()-3,getWidth()-44,2);}if(hasFocus()){g.setColor(WorkbenchUi.accent());g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,6,6);}g.dispose();}
  }
  public static JPanel summary(Component... fields){JPanel panel=new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.LEFT,14,10));panel.setName("wb.reference.summary");panel.setBorder(kr.packetui.VisualTheme.border(1,8,1,8));for(Component field:fields)panel.add(field);return panel;}
  public static JPanel field(String title,JComponent value){JLabel label=new JLabel(title);label.putClientProperty("wb.secondary",true);JPanel p=new JPanel(new BorderLayout(14,0));p.setOpaque(false);p.add(label,BorderLayout.WEST);p.add(value);p.setBorder(BorderFactory.createEmptyBorder(0,0,0,16));return p;}
  public static JPanel header(Component title,Component actions){JPanel p=new JPanel(new BorderLayout()) {protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();kr.packetui.VisualTheme.wash(g,getWidth(),getHeight(),WorkbenchUi.surface(),WorkbenchUi.raised());g.setColor(WorkbenchUi.line());g.drawLine(0,getHeight()-1,getWidth(),getHeight()-1);g.dispose();}};p.setName("wb.reference.header");p.add(title);if(actions!=null)p.add(actions,BorderLayout.EAST);return p;}

  /** Read-only, labeled rows; the source document remains the authoritative selection model. */
  static JPanel facts(JTextArea source){JPanel rows=new FactRows();rows.putClientProperty("wb.surface",true);rows.setBorder(BorderFactory.createEmptyBorder(12,14,12,14));rows.setName("wb.reference.facts");
    final boolean[] queued={false};Runnable rebuild=()->{queued[0]=false;rows.removeAll();String value=source.getText();if(value.isBlank()){rows.add(new JLabel("항목을 선택하면 상세 정보가 표시됩니다."));}else for(String block:value.split("\\n\\n")){String[] lines=block.split("\\n",2);String line=lines[0];int delimiter=line.indexOf("   ");if(delimiter<0)delimiter=line.indexOf(": ");if(delimiter>0&&delimiter<18){String title=line.substring(0,delimiter),body=line.substring(delimiter).replaceFirst("^(?: +|: )","")+(lines.length>1?"\n"+lines[1]:"");JLabel key=new JLabel(title);key.putClientProperty("wb.secondary",true);key.setPreferredSize(new Dimension(80,26));JTextArea text=new JTextArea(body);text.putClientProperty("wb.prose",true);text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);JPanel pair=new JPanel(new BorderLayout(10,0));pair.putClientProperty("wb.surface",true);pair.add(key,BorderLayout.WEST);pair.add(text);pair.setAlignmentX(Component.LEFT_ALIGNMENT);pair.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,WorkbenchUi.line()),BorderFactory.createEmptyBorder(8,0,8,0)));pair.setMaximumSize(new Dimension(Integer.MAX_VALUE,Math.max(48,text.getPreferredSize().height+18)));rows.add(pair);}else{JTextArea text=new JTextArea(block);text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.putClientProperty("wb.prose",true);text.setAlignmentX(Component.LEFT_ALIGNMENT);text.setBorder(BorderFactory.createEmptyBorder(8,0,8,0));rows.add(text);}}WorkbenchUi.style(rows);rows.revalidate();rows.repaint();SwingUtilities.invokeLater(()->{Container ancestor=SwingUtilities.getAncestorOfClass(JViewport.class,rows);if(ancestor instanceof JViewport viewport)viewport.setViewPosition(new Point(0,0));});};
    source.getDocument().addDocumentListener(new DocumentListener(){void update(){if(!queued[0]){queued[0]=true;SwingUtilities.invokeLater(rebuild);}}public void insertUpdate(DocumentEvent e){update();}public void removeUpdate(DocumentEvent e){update();}public void changedUpdate(DocumentEvent e){update();}});rebuild.run();JPanel result=new JPanel(new BorderLayout());result.add(new JScrollPane(rows));return result;
  }

  /** Track the viewport width and grow each wrapped value vertically instead of clipping it. */
  private static final class FactRows extends JPanel implements Scrollable {
    FactRows(){setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));}
    private int measure(){
      int width=Math.max(120,getWidth()-getInsets().left-getInsets().right),total=getInsets().top+getInsets().bottom;
      for(Component row:getComponents()){
        JTextArea value=null;int available=width,extra=0;
        if(row instanceof JPanel panel&&panel.getLayout() instanceof BorderLayout layout){
          Component center=layout.getLayoutComponent(BorderLayout.CENTER);
          if(center instanceof JTextArea area)value=area;
          Component key=layout.getLayoutComponent(BorderLayout.WEST);
          available-=panel.getInsets().left+panel.getInsets().right+(key==null?0:key.getPreferredSize().width+layout.getHgap());
          extra=panel.getInsets().top+panel.getInsets().bottom;
        }else if(row instanceof JTextArea area)value=area;
        if(value!=null){
          JTextArea measured=new JTextArea(value.getText());measured.setFont(value.getFont());measured.setMargin(value.getMargin());measured.setBorder(value.getBorder());measured.setLineWrap(true);measured.setWrapStyleWord(value.getWrapStyleWord());measured.setSize(Math.max(40,available),Short.MAX_VALUE);
          int height=Math.max(40,measured.getPreferredSize().height+extra);
          row.setMaximumSize(new Dimension(Integer.MAX_VALUE,height));
          if(row!=value)row.setPreferredSize(new Dimension(width,height));
          total+=height;
        }else total+=row.getPreferredSize().height;
      }
      return total;
    }
    @Override public Dimension getPreferredSize(){return new Dimension(getWidth()>0?getWidth():400,measure());}
    @Override public void doLayout(){measure();((BoxLayout)getLayout()).invalidateLayout(this);super.doLayout();}
    public Dimension getPreferredScrollableViewportSize(){return new Dimension(400,240);}
    public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 24;}
    public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(24,visible.height-24);}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return false;}
  }

  /** Plain report editing keeps the original text; auxiliary formatting controls were retired. */
  static JPanel writing(JTextArea text){
    JPanel panel=new JPanel(new BorderLayout());panel.setName("wb.reference.writing");
    panel.add(new JScrollPane(text));return panel;
  }

}
