package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.*;

/** Korean integration controls around the upstream UI. Closing the window stops target transmissions. */
final class CustomAgentPanel extends JPanel implements AutoCloseable {
  final JTextField endpoint=new JTextField(LocalLlmClient.DEFAULT_URL,23), model=new JTextField(LocalLlmClient.DEFAULT_MODEL,23), origin=new JTextField(23);
  final JComboBox<String> account=new JComboBox<>(new String[]{"권한 비교 · 테스트 계정 A","권한 비교 · 테스트 계정 B"});
  final JSpinner budget=new JSpinner(new SpinnerNumberModel(30,1,300,1)), models=new JSpinner(new SpinnerNumberModel(20,1,100,1)), delay=new JSpinner(new SpinnerNumberModel(2,1,60,1));
  final JButton load=new JButton("Agent 열기"), arm=new JButton("대상 전송 준비"), stop=new JButton("대상 전송 중지"), selected=new JButton("선택 요청의 Agent 도구"), unload=new JButton("Agent 종료");
  final JLabel status=new JLabel("Custom AI Agent 1.1.0 · 시작 OFF · 설정은 이 세션에서만 유지");
  final JPanel content=new JPanel(new BorderLayout());
  private final MontoyaApi api;
  private final AuthComparePanel auth;
  private final Supplier<HttpRequestResponse> selection;
  private final Supplier<Boolean> otherRunning;
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"workbench-custom-agent-lifecycle");t.setDaemon(true);return t;});
  private volatile CustomAgentBridge bridge;
  private volatile CustomAgentGate gate;
  private volatile boolean closed;
  private String project;
  private long uiGeneration;
  private final javax.swing.Timer lifecycle;
  private JDialog pending;

  CustomAgentPanel(MontoyaApi api,AuthComparePanel auth,Supplier<HttpRequestResponse> selection,Supplier<Boolean> otherRunning){
    super(new BorderLayout(5,5));this.api=api;this.auth=auth;this.selection=selection;this.otherRunning=otherRunning;
    setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
    JPanel form=new JPanel(new GridLayout(0,2,5,3));
    form.add(new JLabel("로컬 OpenAI 호환 API /v1"));form.add(endpoint);form.add(new JLabel("모델 ID"));form.add(model);
    form.add(new JLabel("능동 검사 대상 Origin · Workbench SCOPE도 적용"));form.add(origin);form.add(new JLabel("능동 요청에 적용할 테스트 계정"));form.add(account);
    JPanel limits=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));limits.add(new JLabel("대상"));limits.add(budget);limits.add(new JLabel("모델"));limits.add(models);limits.add(new JLabel("간격(초)"));limits.add(delay);
    form.add(new JLabel("Agent 전용 예산 · 대상 요청은 매번 승인"));form.add(limits);
    JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));for(var b:List.of(load,selected,arm,stop,unload))actions.add(b);
    JPanel top=new JPanel(new BorderLayout());top.add(form,BorderLayout.CENTER);top.add(actions,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);
    content.add(new JLabel("Agent 열기를 누르면 여기에 원본 Agent 화면이 나타납니다.",SwingConstants.CENTER));add(content,BorderLayout.CENTER);add(status,BorderLayout.SOUTH);
    load.addActionListener(e->openAgent());stop.addActionListener(e->stop());arm.addActionListener(e->arm());unload.addActionListener(e->shutdown(false));selected.addActionListener(e->selectedMenu());
    lifecycle=new javax.swing.Timer(500,e->{if(bridge!=null&&!Objects.equals(project,WorkspaceResume.projectIdentity(api)))shutdown(false);});lifecycle.start();controls(false);
  }
  private void controls(boolean loaded){load.setEnabled(!loaded);endpoint.setEnabled(!loaded);model.setEnabled(!loaded);selected.setEnabled(loaded);arm.setEnabled(loaded);stop.setEnabled(loaded);unload.setEnabled(loaded);}
  private void message(String text){SwingUtilities.invokeLater(()->{if(!closed)status.setText(text);});}
  private void openAgent(){
    if(closed||bridge!=null)return;
    try{
      var base=LocalLlmClient.endpoint(endpoint.getText());String modelName=model.getText().strip();
      if(modelName.isEmpty())throw new IllegalArgumentException("모델 ID를 입력하세요.");
      project=WorkspaceResume.projectIdentity(api);
      if(project.isEmpty())throw new IllegalStateException("프로젝트 식별을 확인할 수 없습니다.");
      gate=new CustomAgentGate(api,base,this::approve,this::message);
      long token=++uiGeneration;
      bridge=new CustomAgentBridge(api,gate,modelName,component->SwingUtilities.invokeLater(()->{
        if(!closed&&token==uiGeneration){content.removeAll();content.add(component);content.revalidate();content.repaint();}
      }));
      controls(true);arm.setEnabled(false);selected.setEnabled(false);status.setText("Agent 로드 중…");
      var created=bridge;
      worker.submit(()->{try{created.load();SwingUtilities.invokeLater(()->{if(!closed&&bridge==created){arm.setEnabled(true);selected.setEnabled(true);status.setText("Agent 준비 · 로컬 분석 가능 · 대상 전송 중지 상태");}});}
        catch(Throwable e){created.close();SwingUtilities.invokeLater(()->{if(bridge==created){bridge=null;gate=null;controls(false);status.setText("Agent 로드 실패: "+e.getMessage());}});}});
    }catch(RuntimeException e){status.setText(e.getMessage());}
  }
  private void arm(){
    try{
      if(gate==null||bridge==null)return;
      if(gate.running())throw new IllegalStateException("먼저 대상 전송을 중지하세요.");
      if(auth.record.isSelected()||otherRunning.get())throw new IllegalStateException("기존 자동 점검·신규 요청 자동 비교를 먼저 중지하세요.");
      int slot=account.getSelectedIndex();var profile=auth.slots[slot];
      if(profile==null||profile.headers().isEmpty())throw new IllegalStateException("선택한 테스트 계정을 권한 비교에 연결하세요.");
      String target=AutoLlmEngine.origin(origin.getText().strip());
      if(!AutoLlmEngine.origin(profile.origin()).equals(target))throw new IllegalStateException("계정과 대상 Origin이 다릅니다.");
      Set<String> names=new HashSet<>(auth.authNames);names.addAll(List.of("cookie","authorization","proxy-authorization"));
      for(String h:profile.headers().keySet()){if(Set.of("host","content-length","transfer-encoding","connection","forwarded","x-forwarded-host").contains(h.toLowerCase(Locale.ROOT)))throw new IllegalStateException("인증에 라우팅 헤더를 사용할 수 없습니다.");names.add(h.toLowerCase(Locale.ROOT));}
      final var authNames=Set.copyOf(names);int count=(Integer)budget.getValue(),modelCount=(Integer)models.getValue(),interval=(Integer)delay.getValue()*1000;
      CustomAgentGate current=gate;String projectId=project;
      Runnable validator=()->onEdt(()->{if(closed||bridge==null||gate!=current||!projectId.equals(WorkspaceResume.projectIdentity(api))||auth.slots[slot]!=profile||auth.record.isSelected()||otherRunning.get())throw new IllegalStateException("프로젝트·계정·자동 실행 상태 변경 · 전송 차단");});
      arm.setEnabled(false);worker.submit(()->{try{current.start(target,count,modelCount,interval,q->Core.sessionRequest(q,profile.headers(),authNames),validator);}
        catch(RuntimeException e){message(e.getMessage());}finally{SwingUtilities.invokeLater(()->{if(!closed&&bridge!=null)arm.setEnabled(true);});}});
    }catch(RuntimeException e){status.setText(e.getMessage());}
  }
  boolean active(){return gate!=null&&gate.running();}
  void stop(){if(gate!=null)gate.stop();if(pending!=null)pending.dispose();}
  private boolean approve(HttpRequest q){
    AtomicBoolean yes=new AtomicBoolean();
    onEdt(()->{
      if(closed||gate==null||!gate.running())return;
      JTextArea preview=new JTextArea("이 요청은 서버 상태·가용성에 영향을 줄 수 있습니다.\n선택한 테스트 계정으로 아래 요청 1건만 전송합니다.\n취소하면 전송하지 않습니다. 재시도도 새 승인이 필요합니다.\n\n"+q.toString(),22,90);
      preview.setEditable(false);preview.setLineWrap(true);preview.setWrapStyleWord(true);
      JOptionPane pane=new JOptionPane(new JScrollPane(preview),JOptionPane.WARNING_MESSAGE,JOptionPane.YES_NO_OPTION);
      pending=pane.createDialog(this,"대상 요청 1건 승인 · 가용성 영향 확인");
      pending.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);pending.setVisible(true);
      yes.set(Objects.equals(pane.getValue(),JOptionPane.YES_OPTION));pending.dispose();pending=null;
    });return yes.get();
  }
  private void selectedMenu(){
    try{
      if(bridge==null)return;HttpRequestResponse rr=selection.get();
      if(rr==null)throw new IllegalArgumentException("Workbench 요청·응답 목록에서 원본을 선택하세요.");
      EvidenceLinks.checked(api,List.of(rr));
      ContextMenuEvent event=CustomAgentBridge.proxy(ContextMenuEvent.class,(p,m,a)->switch(m.getName()){
        case "selectedRequestResponses"->List.of(rr);case "messageEditorRequestResponse"->Optional.empty();case "isFrom"->false;case "selectedIssues"->List.of();default->null;
      });
      JPopupMenu menu=new JPopupMenu();for(var provider:bridge.menus){var items=provider.provideMenuItems(event);if(items!=null)for(var item:items)menu.add(item);}
      if(menu.getComponentCount()==0)throw new IllegalStateException("Agent 메뉴 준비 중입니다.");menu.show(selected,0,selected.getHeight());
    }catch(RuntimeException e){status.setText(e.getMessage());}
  }
  private void shutdown(boolean dispose){
    if(closed)return;
    ++uiGeneration;stop();var old=bridge;if(old!=null)old.gate.close();bridge=null;gate=null;controls(false);content.removeAll();content.add(new JLabel("Agent 종료 · 다시 열면 새 세션으로 시작합니다.",SwingConstants.CENTER));content.revalidate();content.repaint();
    if(old!=null){load.setEnabled(false);worker.submit(()->{old.close();SwingUtilities.invokeLater(()->{if(!closed)load.setEnabled(true);});});}
    if(dispose){closed=true;lifecycle.stop();worker.shutdown();}
  }
  public void close(){shutdown(true);}
  static void onEdt(Runnable action){if(SwingUtilities.isEventDispatchThread()){action.run();return;}try{SwingUtilities.invokeAndWait(action);}catch(Exception e){throw new IllegalStateException("실행 상태 확인 실패",e);}}
}
