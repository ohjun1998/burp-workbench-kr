package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Each requested target owns its own panel, jobs, selection, packets and stop state. */
public final class PassiveScanWorkspace extends JPanel implements AutoCloseable {
  final MontoyaApi api;
  final JTabbedPane targets=new JTabbedPane();
  final JTextField input=new JTextField(32);
  final LinkedHashMap<String,PassiveScanPanel> panels=new LinkedHashMap<>();
  final JLabel note=new JLabel("요청 우클릭 → URL 후보 수집 시작 · 최대 8개 대상");
  private final TargetWindow targetSettings;
  private final JLabel targetSummary;
  private final JTextArea targetFeedback=new JTextArea(2,48);
  private final Action prepareAction=new AbstractAction("대상 열기") {
    @Override public void actionPerformed(java.awt.event.ActionEvent event){prepareTarget();}
  };
  private final AtomicBoolean closed=new AtomicBoolean();
  boolean horizontal;
  java.util.function.BiConsumer<String,String> discovery=(url,source)->{};
  java.util.function.BiConsumer<java.util.List<burp.api.montoya.http.message.HttpRequestResponse>,String> evidenceSink=
      (sources,note)->{throw new IllegalArgumentException("증거 기능 준비 중");};

  public PassiveScanWorkspace(MontoyaApi api) {
    super(new BorderLayout(6,6));this.api=api;
    targetSummary=new JLabel("대상 · SCOPE에서 선택하세요");
    targetSettings=new TargetWindow(this,"URL 후보 수집 · 대상 설정",this::targetContent);
    input.setName("wb.passive.target.input");
    input.addActionListener(prepareAction);
    targetFeedback.setName("wb.passive.target.feedback");
    targetFeedback.putClientProperty("wb.prose",true);
    targetFeedback.setEditable(false);targetFeedback.setFocusable(false);
    targetFeedback.setLineWrap(true);targetFeedback.setWrapStyleWord(true);
    targetFeedback.getAccessibleContext().setAccessibleName("URL 대상 설정 오류");
    targetFeedback.setVisible(false);
    setBorder(BorderFactory.createEmptyBorder(6,6,6,6));
    targets.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
    JButton start=new JButton("대상 수집 시작"),close=new JButton("선택 대상 닫기");
    start.addActionListener(event->onEdt(()->{
        if(closed.get())return;
        int index=targets.getSelectedIndex();
        if(index<0)targetSettings.open();else ((PassiveScanPanel)targets.getComponentAt(index)).collect();
    }));
    close.addActionListener(event->onEdt(()->{
        if(closed.get())return;
        int index=targets.getSelectedIndex();if(index<0)return;
        PassiveScanPanel panel=(PassiveScanPanel)targets.getComponentAt(index);
        panel.close();panels.values().remove(panel);targets.remove(index);
    }));
    JButton target=new JButton("대상 설정");target.setName("wb.target.settings");
    target.addActionListener(event->onEdt(()->{if(!closed.get())targetSettings.open();}));
    JPanel toolbar=new JPanel(new BorderLayout());
    toolbar.add(WorkbenchUi.row(targetSummary,target,start,close));
    note.addPropertyChangeListener("text",event->toolbar.setToolTipText(note.getText()));
    add(toolbar,BorderLayout.NORTH);add(targets);
    targets.addChangeListener(event->{
      Component selected=targets.getSelectedComponent();
      String key=panels.entrySet().stream().filter(entry->entry.getValue()==selected).map(java.util.Map.Entry::getKey).findFirst().orElse("");
      input.setText(key);TargetWindow.show(targetSummary,key,"대상 · SCOPE에서 선택하세요");
    });
    WorkbenchUi.style(this);
  }

  private JPanel targetContent() {
    JButton prepare=new JButton(prepareAction);prepare.setName("wb.target.prepare");
    return TargetWindow.options(TargetWindow.scopes(api,input::setText),
        WorkbenchUi.row(new JLabel("URL / 도메인"),input),
        WorkbenchUi.row(prepare,new JLabel("대상을 준비한 뒤 수집 버튼으로 시작합니다.")),targetFeedback);
  }

  private void prepareTarget() {
    onEdt(()->{
      if(closed.get())return;
      try{openOnEdt(input.getText(),false);targetSettings.closeWindow();}
      catch(Exception error){
        Diagnostics.failure(this,"URL 대상 설정",error);
        String message=error.getMessage();
        if(message==null||message.isBlank())message="대상을 열지 못했습니다. URL / 도메인을 확인하세요.";
        note.setText(message);targetFeedback(message);
      }
    });
  }

  private void targetFeedback(String message) {
    targetFeedback.setText(message);targetFeedback.setVisible(!message.isBlank());
    if(targetFeedback.getParent()!=null){targetFeedback.getParent().revalidate();targetFeedback.getParent().repaint();}
  }

  PassiveScanPanel open(String url,boolean execute) {
    if(closed.get())return null;
    if(SwingUtilities.isEventDispatchThread())return openOnEdt(url,execute);
    AtomicReference<PassiveScanPanel> result=new AtomicReference<>();
    onEdt(()->result.set(openOnEdt(url,execute)));
    return result.get();
  }

  private PassiveScanPanel openOnEdt(String url,boolean execute) {
    // A queued History/menu action must not recreate a workspace after extension unload.
    if(closed.get())return null;
    final String host;
    try{host=PassiveUrls.domain(url);}
    catch(IllegalArgumentException error){
      String detail=error.getMessage();
      String recovery="URL / 도메인 형식을 확인하고 다시 시도하세요.";
      if(detail!=null&&!detail.isBlank())recovery+="\n"+detail;
      throw new IllegalArgumentException(recovery,error);
    }
    String key=url.strip().contains("://")?java.net.URI.create(url.strip()).normalize().toString().split("#",2)[0]:host;
    PassiveScanPanel panel=panels.get(key);
    if(panel==null){
      if(panels.size()>=8)throw new IllegalArgumentException("대상 탭은 최대 8개입니다. 사용하지 않는 탭을 닫아주세요.");
      panel=new PassiveScanPanel(api);
      panel.discovery=(found,source)->discovery.accept(found,source);
      panel.evidenceSink=(sources,note)->evidenceSink.accept(sources,note);
      panel.target.setText(host);panel.target.setEditable(false);
      panels.put(key,panel);
      targets.addTab(host+(key.contains("://")?" · "+java.net.URI.create(key).getPath():""),panel);
      targets.setToolTipTextAt(targets.getTabCount()-1,key);WorkbenchUi.packetLayout(panel,horizontal);
    }
    targets.setSelectedComponent(panel);input.setText(url);
    TargetWindow.show(targetSummary,key,"대상 · SCOPE에서 선택하세요");
    note.setText("선택 대상: "+key+" · 과거 URL은 도메인 기준으로 수집합니다.");targetFeedback("");
    if(execute)panel.collect();return panel;
  }

  void layout(boolean horizontal){onEdt(()->{if(closed.get())return;this.horizontal=horizontal;for(PassiveScanPanel panel:panels.values())WorkbenchUi.packetLayout(panel,horizontal);});}

  private static void onEdt(Runnable action) {
    if(SwingUtilities.isEventDispatchThread()){action.run();return;}
    try{SwingUtilities.invokeAndWait(action);}
    catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("URL 작업 공간 변경 대기가 중단되었습니다.",error);}
    catch(InvocationTargetException error){
      if(error.getCause() instanceof RuntimeException runtime)throw runtime;
      if(error.getCause() instanceof Error failure)throw failure;
      throw new IllegalStateException(error.getCause());
    }
  }

  @Override public void close() {
    if(!closed.compareAndSet(false,true))return;
    // Never hold a workspace monitor while waiting for EDT work or disposing a dialog.
    onEdt(()->{
      targetSettings.close();
      for(PassiveScanPanel panel:panels.values())panel.close();
      panels.clear();targets.removeAll();
    });
  }
}
