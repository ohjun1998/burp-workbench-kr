package kr.workbench;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import javax.swing.*;

/** Finishes the approved Mapper presentation around the retained workspace and its controllers. */
final class ApprovedMapperDesign {
  private ApprovedMapperDesign() {}

  static void install(JPanel page) {
    if (Boolean.TRUE.equals(page.getClientProperty("wb.approved.mapper.details"))) return;
    page.putClientProperty("wb.approved.mapper.details", true);
    Component filters=ApprovedUi.find(page,"fm.filters");
    if(filters!=null)for(Component child:ApprovedLayouts.all(filters))if(child instanceof JComboBox<?> combo)combo.putClientProperty("fm.approved.filter.field",true);
    JSplitPane navigation=(JSplitPane)ApprovedUi.find(page,"fm.navigation.split");
    JTree tree=(JTree)ApprovedUi.find(page,"fm.feature.tree");
    JToggleButton unclassified=(JToggleButton)ApprovedUi.find(page,"fm.unclassified");
    JPanel header=(JPanel)ApprovedUi.find(page,"fm.workflow.tree.header");
    if(navigation==null||tree==null||unclassified==null||header==null)return;
    JPanel retained=(JPanel)navigation.getLeftComponent();
    int treeSlot=navigation.getComponentZOrder(retained);
    retained.putClientProperty("fm.approved.surface",true);retained.setOpaque(false);retained.setBorder(null);
    if(retained.getLayout() instanceof BorderLayout border)border.setVgap(0);
    header.putClientProperty("fm.approved.surface",true);header.setPreferredSize(new Dimension(0,43));
    JScrollPane scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,tree);
    if(scroll!=null){
      retained.remove(unclassified);
      unclassified.setPreferredSize(new Dimension(0,44));
      tree.setBorder(BorderFactory.createEmptyBorder(9,0,9,0));
      scroll.putClientProperty("fm.approved.tree.scroll",true);scroll.setBorder(null);
      scroll.setViewportView(new TreeContent(tree,unclassified));
    }
    RoundedTreeCard card=new RoundedTreeCard(retained);
    navigation.setLeftComponent(card);
    if(treeSlot>=0)navigation.setComponentZOrder(card,treeSlot);
    AbstractButton feature=(AbstractButton)ApprovedUi.find(page,"wb.actions.mapper.feature.open");
    if(feature!=null&&feature.getParent() instanceof JPanel group){
      group.putClientProperty("fm.approved.surface",true);group.setPreferredSize(new Dimension(104,43));
      ApprovedUi.layout(group,p->ApprovedUi.bounds(feature,0,6,91,31));
    }
    Component request=ApprovedUi.find(page,"fm.workflow.request.options.open");
    Component classify=ApprovedUi.find(page,"fm.packet.classify");
    if(request!=null)request.setPreferredSize(new Dimension(90,31));
    if(classify!=null)classify.setPreferredSize(new Dimension(70,31));
    page.revalidate();page.repaint();
  }

  /** Content follows the actual expanded model; the orphan filter sits below its rows, not at screen bottom. */
  private static final class TreeContent extends JPanel implements Scrollable {
    TreeContent(JTree tree,AbstractButton unclassified){super(new BorderLayout());putClientProperty("fm.approved.surface",true);add(tree);add(unclassified,BorderLayout.SOUTH);}
    public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
    public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 40;}
    public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(40,visible.height-40);}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return false;}
  }

  private static final class RoundedTreeCard extends JPanel {
    RoundedTreeCard(Component retained){super(new BorderLayout());setName("fm.approved.tree.card");putClientProperty("fm.approved.surface",true);setOpaque(false);setBorder(BorderFactory.createEmptyBorder(1,1,1,1));add(retained);}
    protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();kr.packetui.VisualTheme.smooth(g);g.setColor(kr.packetui.VisualTheme.surface());g.fillRoundRect(0,0,getWidth(),getHeight(),20,20);g.dispose();}
    protected void paintChildren(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();kr.packetui.VisualTheme.smooth(g);g.clip(new RoundRectangle2D.Double(0,0,getWidth(),getHeight(),20,20));super.paintChildren(g);g.setColor(kr.packetui.VisualTheme.line());g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,20,20);g.dispose();}
  }
}
