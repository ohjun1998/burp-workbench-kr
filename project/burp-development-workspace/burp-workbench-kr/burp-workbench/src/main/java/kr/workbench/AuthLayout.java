package kr.workbench;

import java.awt.*;
import javax.swing.*;
import javax.swing.table.TableModel;

/** Fill wide viewports; preserve readable columns and horizontal scrolling on narrow ones. */
final class AuthLayout {
  static class Table extends JTable {
    String emptyMessage="";
    Table(TableModel model){super(model);}
    @Override protected void paintComponent(Graphics graphics){
      super.paintComponent(graphics);
      if(getRowCount()==0&&!emptyMessage.isEmpty()&&getVisibleRect().height>90){
        Graphics2D g=(Graphics2D)graphics.create();g.setFont(WorkbenchUi.font());g.setColor(WorkbenchUi.ink());
        Rectangle r=getVisibleRect();int width=g.getFontMetrics().stringWidth(emptyMessage);
        g.drawString(emptyMessage,r.x+Math.max(12,(r.width-width)/2),r.y+Math.min(90,r.height/2));g.dispose();
      }
    }
    @Override public boolean getScrollableTracksViewportWidth(){
      int minimum=0;for(int i=0;i<getColumnCount();i++)minimum+=getColumnModel().getColumn(i).getMinWidth();
      return getParent() instanceof JViewport viewport&&viewport.getWidth()>=minimum;
    }
  }
  static JPanel card(String title,String hint,Component body,JButton action){
    JPanel card=WorkbenchUi.card(title,body);
    JLabel heading=new JLabel(title);heading.putClientProperty("wb.font.heading",true);heading.putClientProperty("wb.font.size",16);
    JLabel help=new JLabel(hint);help.putClientProperty("wb.font.size",12);
    JPanel bar=new JPanel(new BorderLayout(8,0));bar.setBorder(BorderFactory.createEmptyBorder(2,4,2,8));bar.add(WorkbenchUi.row(heading,help));
    if(action!=null)bar.add(action,BorderLayout.EAST);
    card.remove(((BorderLayout)card.getLayout()).getLayoutComponent(BorderLayout.NORTH));card.add(bar,BorderLayout.NORTH);return card;
  }
  static JPanel verdictLegend(){
    JPanel legend=new JPanel(new FlowLayout(FlowLayout.RIGHT,10,5));
    for(AuthVerdict verdict:AuthVerdict.values()){
      JLabel label=new JLabel(verdict==AuthVerdict.BLOCKED?"차단 확인 / 정책 일치":verdict.label,verdict.icon(),SwingConstants.LEADING);label.putClientProperty("wb.font.size",12);legend.add(label);
    }
    return legend;
  }
}
