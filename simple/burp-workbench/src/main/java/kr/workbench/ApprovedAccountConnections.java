package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;

/** The approved compact role connector; complete readiness/policy tabs stay behind management. */
final class ApprovedAccountConnections {
  private ApprovedAccountConnections() {}
  /** Window-closed events are queued, so restore the owner before a replacement can open. */
  private static final class ConnectionDialog extends JDialog {
    private Runnable cleanup=()->{};
    private boolean cleaned;
    private volatile boolean disposed;
    ConnectionDialog(Window owner,String title){super(owner,title,Dialog.ModalityType.MODELESS);}
    void cleanup(){if(cleaned)return;cleaned=true;cleanup.run();}
    @Override @SuppressWarnings("deprecation") public void show(){if(!disposed)super.show();}
    @Override public void dispose(){
      if(SwingUtilities.isEventDispatchThread()){disposed=true;cleanup();super.dispose();return;}
      try{SwingUtilities.invokeAndWait(this::dispose);}
      catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("계정 연결창 닫기가 중단됐습니다.",interrupted);}
      catch(java.lang.reflect.InvocationTargetException failure){throw new IllegalStateException("계정 연결창을 닫지 못했습니다.",failure.getCause());}
    }
  }
  static JPanel content(AuthComparePanel controller,Runnable close,Runnable manage){
    JPanel card=controller.preparationAccountChoices();card.setName("wb.auth.connections");card.setPreferredSize(new Dimension(900,447));
    JPanel rows=(JPanel)((BorderLayout)card.getLayout()).getLayoutComponent(BorderLayout.CENTER);
    rows.setBorder(BorderFactory.createEmptyBorder());
    Component header=((BorderLayout)card.getLayout()).getLayoutComponent(BorderLayout.NORTH);header.setPreferredSize(new Dimension(0,39));
    JLabel roles=new JLabel();roles.setName("wb.auth.connections.role.count");ApprovedIdentityDesign.badge(roles,"blue");
    JPanel headerRow=(JPanel)header;headerRow.add(roles,BorderLayout.EAST);headerRow.setBorder(BorderFactory.createEmptyBorder(0,0,0,12));
    List<JComboBox<?>> choices=new ArrayList<>();
    for(int slot:new int[]{0,1,3,2}){
      JPanel row=(JPanel)ApprovedUi.find(card,"wb.auth.preparation.role."+slot);row.setPreferredSize(new Dimension(850,70));
      Component role=((BorderLayout)row.getLayout()).getLayoutComponent(BorderLayout.WEST);
      JComboBox<?> choice=(JComboBox<?>)ApprovedUi.find(row,"wb.auth.preparation.account."+slot);Component state=ApprovedUi.find(row,"wb.auth.preparation.connection."+slot);
      choices.add(choice);if(state instanceof JLabel label){label.setPreferredSize(null);label.putClientProperty("wb.identity.link.state",true);ApprovedIdentityDesign.badge(label,slot==3?"neutral":"blue");}
      ApprovedUi.combo(choice,592);
      final int vertical=slot==0?14:slot==1?20:slot==3?24:14;
      if(slot==3){
        JLabel description=new JLabel("Cookie·Authorization을 사용하지 않습니다.");description.putClientProperty("wb.secondary",true);description.putClientProperty("wb.font.size",11);
        JToggleButton use=new JToggleButton();use.setName("wb.auth.connections.anonymous.use");use.getAccessibleContext().setAccessibleName("비로그인 비교 사용");
        Runnable sync=()->{use.setSelected(choice.getSelectedIndex()>0);use.setText(use.isSelected()?"사용":"사용 안 함");use.setEnabled(choice.isEnabled()&&choice.getItemCount()>1);};
        choice.addActionListener(e->sync.run());choice.addPropertyChangeListener("enabled",e->sync.run());
        use.addActionListener(e->choice.setSelectedIndex(use.isSelected()&&choice.getItemCount()>1?1:0));sync.run();
        use.putClientProperty("wb.embedded.style",(Runnable)()->{if(!(use.getUI() instanceof UseUi))use.setUI(new UseUi());use.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f)));use.setBorder(null);use.setOpaque(false);});
        row.add(description);row.add(use);choice.setVisible(false);state.setVisible(false);row.setBorder(null);
        ApprovedUi.layout(row,p->{ApprovedUi.bounds(role,0,16,155,38);ApprovedUi.bounds(description,169,20,p.getWidth()-258,31);ApprovedUi.bounds(use,p.getWidth()-75,24,75,22);ApprovedUi.bounds(choice,0,0,0,0);ApprovedUi.bounds(state,0,0,0,0);});
      }else ApprovedUi.layout(row,p->{ApprovedUi.bounds(role,0,vertical-2,155,38);ApprovedUi.bounds(choice,169,vertical,p.getWidth()-258,31);ApprovedUi.bounds(state,p.getWidth()-75,vertical+4,Math.min(75,state.getPreferredSize().width),22);});
    }
    Runnable roleCount=()->roles.setText("비교 역할 "+choices.stream().filter(c->c.getSelectedIndex()>0).count()+"개");for(JComboBox<?> choice:choices)choice.addActionListener(e->roleCount.run());roleCount.run();
    JLabel origin=(JLabel)ApprovedUi.find(card,"wb.auth.preparation.origin"),immediate=(JLabel)ApprovedUi.find(card,"wb.auth.preparation.accounts.note");
    for(JLabel label:new JLabel[]{origin,immediate}){label.setIcon(WorkspaceIcons.of("info"));label.setIconTextGap(7);label.putClientProperty("wb.secondary",true);}
    Component first=ApprovedUi.find(card,"wb.auth.preparation.role.0"),second=ApprovedUi.find(card,"wb.auth.preparation.role.1"),anonymous=ApprovedUi.find(card,"wb.auth.preparation.role.3");
    JToggleButton extra=(JToggleButton)ApprovedUi.find(card,"wb.auth.preparation.extra.toggle");extra.putClientProperty("wb.approved.disclosure",true);extra.setPreferredSize(new Dimension(0,36));
    Container disclosure=extra.getParent();Component extraRow=disclosure.getParent();List<Component> repairs=new ArrayList<>();for(Component child:rows.getComponents())if(child instanceof AbstractButton)repairs.add(child);
    int repairHeight=repairs.isEmpty()?0:34;card.putClientProperty("wb.identity.repair.height",repairHeight);
    ApprovedUi.layout(rows,p->{
      int w=p.getWidth()-48,offset=repairHeight,expanded=extra.isSelected()?70:0;
      for(Component child:p.getComponents())ApprovedUi.bounds(child,0,0,0,0);
      ApprovedUi.bounds(origin,24,20,w,24);for(Component repair:repairs)ApprovedUi.bounds(repair,24,48,w,31);
      ApprovedUi.bounds(first,24,53+offset,w,64);ApprovedUi.bounds(second,24,117+offset,w,69);ApprovedUi.bounds(anonymous,24,186+offset,w,69);
      ApprovedUi.bounds(extraRow,24,264+offset,w,36+expanded);ApprovedUi.bounds(immediate,24,304+offset+expanded,w,24);
    });
    JPanel footer=new JPanel(new BorderLayout(12,0));footer.putClientProperty("wb.surface",true);footer.setPreferredSize(new Dimension(0,67));
    footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,WorkbenchUi.line()),BorderFactory.createEmptyBorder(13,24,22,24)));
    JLabel note=new JLabel("연결 역할은 점검 대상의 권한 정책과 직접 대조하세요.");note.putClientProperty("wb.font.size",11);note.putClientProperty("wb.secondary",true);footer.add(note);
    JButton management=new JButton("인증 후보 관리"),dismiss=new JButton("닫기");management.setName("wb.auth.connections.manage");dismiss.setName("wb.auth.connections.close");management.addActionListener(e->manage.run());dismiss.addActionListener(e->close.run());WorkbenchUi.primary(dismiss);
    JPanel actions=WorkbenchUi.row(management,dismiss);ApprovedUi.row(actions,8);footer.add(actions,BorderLayout.EAST);card.add(footer,BorderLayout.SOUTH);WorkbenchUi.style(card);return card;
  }
  static JDialog window(Component owner,String title,Component content,boolean modal,Runnable closed){
    if(GraphicsEnvironment.isHeadless())return null;
    ConnectionDialog dialog=new ConnectionDialog(SwingUtilities.getWindowAncestor(owner),title);dialog.setUndecorated(true);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setContentPane((Container)content);
    dialog.setSize(Math.min(900,Math.max(680,owner.getWidth()-32)),447);dialog.setLocationRelativeTo(owner);
    if(ApprovedUi.find(content,"wb.auth.preparation.extra.toggle") instanceof JToggleButton extra){Runnable resize=()->{int repair=content instanceof JComponent c&&c.getClientProperty("wb.identity.repair.height") instanceof Integer value?value:0;dialog.setSize(dialog.getWidth(),447+repair+(extra.isSelected()?70:0));dialog.setLocationRelativeTo(owner);dialog.validate();try{if(dialog.getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT))dialog.setShape(new RoundRectangle2D.Double(0,0,dialog.getWidth(),dialog.getHeight(),20,20));}catch(UnsupportedOperationException ignored){}};extra.addItemListener(e->resize.run());resize.run();extra.setHorizontalAlignment(SwingConstants.LEFT);}
    JRootPane ownerRoot=SwingUtilities.getRootPane(owner);Component previousGlass=ownerRoot==null?null:ownerRoot.getGlassPane();boolean glassVisible=previousGlass!=null&&previousGlass.isVisible();
    JComponent shade=new JComponent(){protected void paintComponent(Graphics graphics){Rectangle area=owner.getParent()==null?new Rectangle(0,0,getWidth(),getHeight()):SwingUtilities.convertRectangle(owner.getParent(),owner.getBounds(),this);graphics.setColor(WorkbenchUi.background());graphics.fillRect(area.x,area.y,area.width,area.height);}};
    shade.addMouseListener(new MouseAdapter(){public void mousePressed(MouseEvent e){dialog.toFront();}});if(ownerRoot!=null){ownerRoot.setGlassPane(shade);shade.setVisible(true);}
    dialog.cleanup=()->{if(ownerRoot!=null&&ownerRoot.getGlassPane()==shade){ownerRoot.setGlassPane(previousGlass);previousGlass.setVisible(glassVisible);}};
    dialog.getRootPane().registerKeyboardAction(e->dialog.dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
    dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){dialog.cleanup();if(closed!=null)closed.run();}});
    Component head=content instanceof JPanel panel&&panel.getLayout() instanceof BorderLayout b?b.getLayoutComponent(BorderLayout.NORTH):content;
    MouseAdapter drag=new MouseAdapter(){Point origin;public void mousePressed(MouseEvent e){origin=e.getPoint();}public void mouseDragged(MouseEvent e){if(origin!=null){Point at=e.getLocationOnScreen();dialog.setLocation(at.x-origin.x,at.y-origin.y);}}};head.addMouseListener(drag);head.addMouseMotionListener(drag);
    dialog.setVisible(true);return dialog;
  }
  private static final class UseUi extends BasicButtonUI {
    @Override public void update(Graphics graphics,JComponent component){paint(graphics,component);}
    @Override public void paint(Graphics graphics,JComponent component){AbstractButton button=(AbstractButton)component;Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.raised());g.fillRoundRect(0,0,component.getWidth()-1,component.getHeight()-1,5,5);g.setColor(button.hasFocus()?WorkbenchUi.accent():WorkbenchUi.line());g.drawRoundRect(0,0,component.getWidth()-1,component.getHeight()-1,5,5);g.setFont(component.getFont());g.setColor(kr.packetui.VisualTheme.muted());String text=button.getText();g.drawString(text,(component.getWidth()-g.getFontMetrics().stringWidth(text))/2,(component.getHeight()-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());g.dispose();}
  }
}
