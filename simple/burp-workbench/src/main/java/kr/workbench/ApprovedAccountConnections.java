package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

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
    rows.setBorder(BorderFactory.createEmptyBorder(26,24,8,24));
    Component header=((BorderLayout)card.getLayout()).getLayoutComponent(BorderLayout.NORTH);header.setPreferredSize(new Dimension(0,39));
    for(int slot:new int[]{0,1,3,2}){
      JPanel row=(JPanel)ApprovedUi.find(card,"wb.auth.preparation.role."+slot);row.setPreferredSize(new Dimension(850,70));
      Component role=((BorderLayout)row.getLayout()).getLayoutComponent(BorderLayout.WEST);
      JComboBox<?> choice=(JComboBox<?>)ApprovedUi.find(row,"wb.auth.preparation.account."+slot);Component state=ApprovedUi.find(row,"wb.auth.preparation.connection."+slot);
      ApprovedUi.combo(choice,592);
      ApprovedUi.layout(row,p->{ApprovedUi.bounds(role,0,10,155,38);ApprovedUi.bounds(choice,169,12,p.getWidth()-258,31);ApprovedUi.bounds(state,p.getWidth()-75,16,75,22);});
    }
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
    if(ApprovedUi.find(content,"wb.auth.preparation.extra.toggle") instanceof JToggleButton extra){Runnable resize=()->{dialog.setSize(dialog.getWidth(),447+(extra.isSelected()?70:0));dialog.setLocationRelativeTo(owner);dialog.validate();};extra.addItemListener(e->resize.run());resize.run();extra.putClientProperty("wb.segment.button",true);extra.setIcon(kr.packetui.VisualTheme.checkIcon());extra.setHorizontalAlignment(SwingConstants.LEFT);}
    JRootPane ownerRoot=SwingUtilities.getRootPane(owner);Component previousGlass=ownerRoot==null?null:ownerRoot.getGlassPane();boolean glassVisible=previousGlass!=null&&previousGlass.isVisible();
    JComponent shade=new JComponent(){protected void paintComponent(Graphics graphics){graphics.setColor(new Color(0,0,0,88));graphics.fillRect(0,0,getWidth(),getHeight());}};
    shade.addMouseListener(new MouseAdapter(){public void mousePressed(MouseEvent e){dialog.toFront();}});if(ownerRoot!=null){ownerRoot.setGlassPane(shade);shade.setVisible(true);}
    dialog.cleanup=()->{if(ownerRoot!=null&&ownerRoot.getGlassPane()==shade){ownerRoot.setGlassPane(previousGlass);previousGlass.setVisible(glassVisible);}};
    dialog.getRootPane().registerKeyboardAction(e->dialog.dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
    dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){dialog.cleanup();if(closed!=null)closed.run();}});
    Component head=content instanceof JPanel panel&&panel.getLayout() instanceof BorderLayout b?b.getLayoutComponent(BorderLayout.NORTH):content;
    MouseAdapter drag=new MouseAdapter(){Point origin;public void mousePressed(MouseEvent e){origin=e.getPoint();}public void mouseDragged(MouseEvent e){if(origin!=null){Point at=e.getLocationOnScreen();dialog.setLocation(at.x-origin.x,at.y-origin.y);}}};head.addMouseListener(drag);head.addMouseMotionListener(drag);
    dialog.setVisible(true);return dialog;
  }
}
