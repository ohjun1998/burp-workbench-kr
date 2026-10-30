package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;

/** One retained workspace in a modeless desktop window; the Burp tab is its launcher. */
final class WorkbenchWindow implements AutoCloseable {
  final JPanel workspace;
  final JPanel launcher = new JPanel(new GridBagLayout());
  final JButton openButton = new JButton("Workbench 열기");
  final JButton fullScreenButton = new JButton("전체화면");
  final JLabel status = new JLabel("Workbench 탭을 선택하면 별도 창으로 열립니다.");
  private final Set<JTabbedPane> tabParents = Collections.newSetFromMap(new IdentityHashMap<>());
  private final HierarchyListener hierarchyListener = this::hierarchyChanged;
  private final MouseAdapter tabMouseListener = new MouseAdapter() {
    @Override public void mousePressed(MouseEvent event) {
      JTabbedPane tabs=(JTabbedPane)event.getSource();
      int clicked=tabs.indexAtLocation(event.getX(),event.getY());
      if(clicked>=0 && containsLauncher(tabs.getComponentAt(clicked))) requestOpen();
    }
  };
  JFrame window;
  boolean closed;
  private boolean fullScreen, openQueued;
  private Rectangle normalBounds;
  private GraphicsDevice fullScreenDevice;
  private final Set<Dialog> hiddenDialogs = new LinkedHashSet<>();
  private int normalState;

  WorkbenchWindow(JPanel workspace) {
    this.workspace=workspace;
    launcher.setName("wb.window.launcher");
    openButton.setName("wb.window.open");
    openButton.addActionListener(e->open());
    WorkbenchUi.primary(openButton);
    fullScreenButton.setName("wb.window.fullscreen");
    fullScreenButton.setToolTipText("전체화면 전환 · F11 / Esc로 복원");
    fullScreenButton.addActionListener(e->toggleFullScreen());
    JPanel content=new JPanel();content.setLayout(new BoxLayout(content,BoxLayout.Y_AXIS));
    for(Component row:new Component[]{new JLabel("Workbench"),Box.createVerticalStrut(12),status,
        Box.createVerticalStrut(16),openButton,Box.createVerticalStrut(12),
        new JLabel("창 최대화 · F11 전체화면 · 닫아도 작업 내용 유지")}) {
      if(row instanceof JComponent component)component.setAlignmentX(Component.CENTER_ALIGNMENT);content.add(row);
    }
    launcher.add(content);
    launcher.addHierarchyListener(hierarchyListener);
    bind("F11","wb.window.fullscreen",this::toggleFullScreen);
    bind("ESCAPE","wb.window.restore",()->{if(fullScreen)toggleFullScreen();});
  }

  private void bind(String stroke,String name,Runnable action) {
    workspace.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(stroke),name);
    workspace.getActionMap().put(name,new AbstractAction(){public void actionPerformed(ActionEvent e){action.run();}});
  }

  private boolean containsLauncher(Component component) {
    return component==launcher || component instanceof Container parent && SwingUtilities.isDescendingFrom(launcher,parent);
  }

  private void hierarchyChanged(HierarchyEvent event) {
    if(closed)return;
    if((event.getChangeFlags()&(HierarchyEvent.PARENT_CHANGED|HierarchyEvent.SHOWING_CHANGED))==0)return;
    SwingUtilities.invokeLater(()->{
      if(closed)return;
      watchTabParents();
      if(launcher.isShowing())requestOpen();
    });
  }

  private void watchTabParents() {
    Set<JTabbedPane> current=Collections.newSetFromMap(new IdentityHashMap<>());
    for(Container parent=launcher.getParent();parent!=null;parent=parent.getParent())
      if(parent instanceof JTabbedPane tabs)current.add(tabs);
    for(JTabbedPane old:new ArrayList<>(tabParents))if(!current.contains(old)){
      old.removeMouseListener(tabMouseListener);tabParents.remove(old);
    }
    for(JTabbedPane tabs:current)if(tabParents.add(tabs))tabs.addMouseListener(tabMouseListener);
  }

  private void requestOpen() {
    if(closed||openQueued)return;
    openQueued=true;
    SwingUtilities.invokeLater(()->{openQueued=false;if(!closed&&launcher.isShowing())open();});
  }

  void open() {
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::open);return;}
    if(closed)return;
    if(GraphicsEnvironment.isHeadless()){
      status.setText("그래픽 환경에서 Workbench 별도 창을 열 수 있습니다.");return;
    }
    if(window==null){
      Window burpWindow=SwingUtilities.getWindowAncestor(launcher);
      GraphicsConfiguration configuration=burpWindow==null?null:burpWindow.getGraphicsConfiguration();
      window=configuration==null?new JFrame("Workbench Simple · "+BuildInfo.VERSION)
          :new JFrame("Workbench Simple · "+BuildInfo.VERSION,configuration);
      window.setName("wb.window.frame");
      window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
      window.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent event){hide();}});
      window.setContentPane(workspace);
      Rectangle screen=usableBounds(window.getGraphicsConfiguration());
      int width=Math.min(1400,screen.width),height=Math.min(900,screen.height);
      window.setMinimumSize(new Dimension(Math.min(900,screen.width),Math.min(600,screen.height)));
      window.setBounds(screen.x+(screen.width-width)/2,screen.y+(screen.height-height)/2,width,height);
      window.setExtendedState(Frame.MAXIMIZED_BOTH);
    }
    if(!fullScreen){
      Rectangle safe=reachable(window.getBounds(),currentDevice().getDefaultConfiguration());
      if(!safe.equals(window.getBounds()))window.setBounds(safe);
    }
    window.setExtendedState(window.getExtendedState()&~Frame.ICONIFIED);
    window.setVisible(true);
    if(fullScreen){GraphicsDevice current=currentDevice();if(fullScreenDevice!=null&&fullScreenDevice!=current&&fullScreenDevice.getFullScreenWindow()==window)fullScreenDevice.setFullScreenWindow(null);fullScreenDevice=current;fullScreenDevice.setFullScreenWindow(window);}
    for(Dialog dialog:new ArrayList<>(hiddenDialogs))if(dialog.isDisplayable())dialog.setVisible(true);
    hiddenDialogs.clear();window.toFront();window.requestFocus();
    status.setText("Workbench가 별도 창에서 열려 있습니다. 다시 누르면 앞으로 가져옵니다.");
  }

  void hide() {
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::hide);return;}
    if(closed||window==null||!window.isVisible())return;
    hiddenDialogs.clear();collectVisibleDialogs(window,hiddenDialogs);
    for(Dialog dialog:hiddenDialogs)dialog.setVisible(false);
    if(fullScreenDevice!=null&&fullScreenDevice.getFullScreenWindow()==window)fullScreenDevice.setFullScreenWindow(null);
    window.setVisible(false);
    status.setText("작업 내용이 유지됩니다. Workbench 열기로 계속하세요.");
  }

  void toggleFullScreen() {
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::toggleFullScreen);return;}
    if(closed)return;
    open();if(window==null)return;
    if(!fullScreen){
      normalBounds=window.getBounds();normalState=window.getExtendedState()&~Frame.ICONIFIED;
      fullScreenDevice=currentDevice();
      fullScreenDevice.setFullScreenWindow(window);fullScreen=true;
    }else{
      if(fullScreenDevice!=null&&fullScreenDevice.getFullScreenWindow()==window)fullScreenDevice.setFullScreenWindow(null);
      fullScreen=false;window.setBounds(reachable(normalBounds,currentDevice().getDefaultConfiguration()));window.setExtendedState(normalState);
    }
    fullScreenButton.setText(fullScreen?"창 모드":"전체화면");
    window.toFront();workspace.requestFocusInWindow();
  }

  private static void collectVisibleDialogs(Window owner,Set<Dialog> dialogs) {
    for(Window child:owner.getOwnedWindows()){
      if(child instanceof Dialog dialog&&dialog.isVisible())dialogs.add(dialog);
      collectVisibleDialogs(child,dialogs);
    }
  }

  private GraphicsDevice currentDevice() {
    GraphicsDevice preferred=window.getGraphicsConfiguration().getDevice();
    for(GraphicsDevice device:GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())if(device==preferred)return device;
    return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
  }

  boolean isFullScreen(){return fullScreen;}

  static Rectangle usableBounds(GraphicsConfiguration configuration) {
    Rectangle b=new Rectangle(configuration.getBounds());Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(configuration);
    return new Rectangle(b.x+insets.left,b.y+insets.top,b.width-insets.left-insets.right,b.height-insets.top-insets.bottom);
  }

  static Rectangle reachable(Rectangle saved,GraphicsConfiguration fallback) {
    Rectangle chosen=null;long area=-1;
    for(GraphicsDevice device:GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()){
      Rectangle screen=usableBounds(device.getDefaultConfiguration()),overlap=screen.intersection(saved);
      long score=overlap.isEmpty()?0L:(long)overlap.width*overlap.height;
      if(score>area){area=score;chosen=screen;}
    }
    if(chosen==null||area==0)chosen=usableBounds(fallback);
    int width=Math.min(saved.width,chosen.width),height=Math.min(saved.height,chosen.height);
    int x=Math.max(chosen.x,Math.min(saved.x,chosen.x+chosen.width-width));
    int y=Math.max(chosen.y,Math.min(saved.y,chosen.y+chosen.height-height));
    return new Rectangle(x,y,width,height);
  }

  @Override public void close() {
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::close);return;}
    if(closed)return;closed=true;
    launcher.removeHierarchyListener(hierarchyListener);
    for(JTabbedPane tabs:tabParents)tabs.removeMouseListener(tabMouseListener);tabParents.clear();
    if(fullScreenDevice!=null&&fullScreenDevice.getFullScreenWindow()==window)fullScreenDevice.setFullScreenWindow(null);
    hiddenDialogs.clear();
    if(window!=null){window.dispose();window.setContentPane(new JPanel());window=null;}
    workspace.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).remove(KeyStroke.getKeyStroke("F11"));
    workspace.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).remove(KeyStroke.getKeyStroke("ESCAPE"));
    workspace.getActionMap().remove("wb.window.fullscreen");workspace.getActionMap().remove("wb.window.restore");
    openButton.setEnabled(false);fullScreenButton.setEnabled(false);status.setText("확장이 종료되었습니다.");
  }
}
