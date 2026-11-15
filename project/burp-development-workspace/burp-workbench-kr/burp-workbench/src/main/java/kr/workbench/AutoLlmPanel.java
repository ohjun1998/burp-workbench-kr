package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Explicit session setup; credentials stay in memory and never enter the model prompt. */
final class AutoLlmPanel extends JPanel implements AutoCloseable {
  final JTextField origin=new JTextField(24), endpoint=new JTextField(LocalLlmClient.DEFAULT_URL,24), model=new JTextField(LocalLlmClient.DEFAULT_MODEL,24);
  final JTextArea paths=new JTextArea(2,24), detail=new JTextArea();
  final JSpinner budget=new JSpinner(new SpinnerNumberModel(30,3,300,3)), modelBudget=new JSpinner(new SpinnerNumberModel(10,1,100,1)), interval=new JSpinner(new SpinnerNumberModel(2,1,60,1)), slow=new JSpinner(new SpinnerNumberModel(5,1,10,1));
  final JCheckBox readOnly=new JCheckBox("지정 경로는 재조회해도 상태를 변경하지 않는 테스트 대상입니다"), privateData=new JCheckBox("권한 비교의 첫 두 슬롯을 테스트 계정 A/B로 사용합니다");
  final JButton start=new JButton("자동 점검 시작"), stop=new JButton("즉시 중지"), approve=new JButton("선택 검사 1회 승인…"), evidence=new JButton("선택 근거를 증거에 추가");
  final JLabel state=new JLabel("설정 후 시작 · 새 Proxy/Repeater 응답만 분석"), accounts=new JLabel("권한 비교에서 첫 두 계정 슬롯을 연결하세요");
  final DefaultTableModel rows=new DefaultTableModel(new String[]{"원본 ID","경로","상태","판정 / 대기 이유"},0){public boolean isCellEditable(int r,int c){return false;}};
  final JTable table=new JTable(rows);
  interface Planning { AutoLlmEngine.Plan plan(LocalLlmClient.Settings s, AutoLlmEngine.Capture c, LocalLlmClient.Call call) throws Exception; }
  private final Planning planning;
  private final MontoyaApi api; private final AuthComparePanel auth; private final BountySupportPanel bounty;
  private volatile AutoLlmEngine engine;
  private AutoLlmEngine.Snapshot snapshot;
  private List<AuthComparePanel.Candidate> profiles=List.of();
  private Set<String> authNames=Set.of();
  private String project;
  private boolean closed;
  private long uiGeneration;
  private final javax.swing.Timer lifecycle;

  AutoLlmPanel(MontoyaApi api,AuthComparePanel auth,BountySupportPanel bounty) {
    this(api,auth,bounty,(s,c,call)->new LocalLlmClient().plan(s,c.preview(),call));
  }
  AutoLlmPanel(MontoyaApi api,AuthComparePanel auth,BountySupportPanel bounty,Planning planning) {
    super(new BorderLayout(6,6));this.api=api;this.auth=auth;this.bounty=bounty;this.planning=planning;
    setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
    JPanel form=new JPanel(new GridLayout(0,2,6,4));
    form.add(new JLabel("대상 Origin (SCOPE와 함께 적용)"));form.add(origin);
    form.add(new JLabel("조회 경로 · 한 줄에 정확한 경로 하나"));form.add(new JScrollPane(paths));
    form.add(new JLabel("로컬 모델 주소 /v1"));form.add(endpoint);form.add(new JLabel("모델 ID"));form.add(model);
    JPanel limits=new JPanel(new FlowLayout(FlowLayout.LEFT,3,0));limits.add(new JLabel("대상 요청"));limits.add(budget);limits.add(new JLabel("모델 호출"));limits.add(modelBudget);
    form.add(new JLabel("1회 실행 총 예산 · 비교당 최대 3요청"));form.add(limits);
    JPanel time=new JPanel(new FlowLayout(FlowLayout.LEFT,3,0));time.add(new JLabel("요청 간격 초"));time.add(interval);time.add(new JLabel("지연 중지 초"));time.add(slow);
    form.add(new JLabel("순차 실행 · 오류/429/지연 시 중지"));form.add(time);
    JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));form.setAlignmentX(LEFT_ALIGNMENT);accounts.setAlignmentX(LEFT_ALIGNMENT);readOnly.setAlignmentX(LEFT_ALIGNMENT);privateData.setAlignmentX(LEFT_ALIGNMENT);top.add(form);top.add(accounts);top.add(readOnly);top.add(privateData);
    top.add(new JLabel("계정 비밀값은 가림 후 모델에 전달 · 모델 제안은 취약점 확정이 아닙니다"));add(top,BorderLayout.NORTH);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setRowHeight(27);detail.setEditable(false);detail.setLineWrap(true);detail.setWrapStyleWord(true);
    JScrollPane listScroll=new JScrollPane(table);listScroll.setColumnHeaderView(table.getTableHeader());
    JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,listScroll,new JScrollPane(detail));split.setResizeWeight(.55);add(split,BorderLayout.CENTER);
    JPanel bottom=new JPanel(new BorderLayout());JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));
    for(JButton b:List.of(start,stop,approve,evidence))actions.add(b);
    bottom.add(actions,BorderLayout.CENTER);bottom.add(state,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
    start.addActionListener(e->safe(this::begin));stop.addActionListener(e->{if(engine!=null)engine.stop("사용자 중지 · 시작한 요청의 종료 대기");});
    approve.addActionListener(e->safe(this::approveSelected));evidence.addActionListener(e->safe(this::saveEvidence));
    table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())showSelected();});
    lifecycle=new javax.swing.Timer(500,e->{
      if(engine!=null && snapshot!=null && snapshot.busy()) {
        try{validateSession();}catch(RuntimeException ex){engine.reset();detail.setText("");}
      }
    });lifecycle.start();controls(false);
  }
  private void safe(Runnable task){try{task.run();}catch(RuntimeException e){state.setText(e.getMessage());}}
  private void begin() {
    if(closed)return;
    if(engine!=null && engine.snapshot().busy())throw new IllegalArgumentException("진행 중 요청이 끝난 뒤 시작하세요.");
    if(!readOnly.isSelected()||!privateData.isSelected())throw new IllegalArgumentException("조회 경로 및 테스트 계정 사용 확인이 필요합니다.");
    if(auth.record.isSelected())throw new IllegalArgumentException("기존 신규 요청 자동 비교를 중지하세요. 중복 재전송을 방지합니다.");
    var a=auth.slots[0];var b=auth.slots[1];
    if(a==null||b==null||a.headers().isEmpty()||b.headers().isEmpty()||a.headers().equals(b.headers()))throw new IllegalArgumentException("서로 다른 인증을 가진 테스트 계정 2개를 권한 비교의 첫 두 슬롯에 연결하세요.");
    Set<String> allowed=new LinkedHashSet<>();for(String p:paths.getText().split("\\R"))if(!p.isBlank())allowed.add(p.strip());
    project=WorkspaceResume.projectIdentity(api);
    if(project.isEmpty())throw new IllegalArgumentException("프로젝트 식별을 사용할 수 없어 자동 실행을 시작할 수 없습니다.");
    var config=new AutoLlmEngine.Config(project,origin.getText().strip(),allowed,(Integer)budget.getValue(),(Integer)modelBudget.getValue(),1000*(Integer)interval.getValue(),1000*(Integer)slow.getValue());
    if(!AutoLlmEngine.origin(a.origin()).equals(config.origin())||!AutoLlmEngine.origin(b.origin()).equals(config.origin()))throw new IllegalArgumentException("두 계정의 Origin과 대상이 일치해야 합니다.");
    for(String path:allowed)if(!WorkbenchScope.contains(api,HttpRequest.httpRequestFromUrl(config.origin()+path)))throw new IllegalArgumentException("허용 경로가 현재 SCOPE 밖입니다: "+path);
    profiles=List.of(a,b);Set<String> names=new HashSet<>(auth.authNames);names.addAll(List.of("cookie","authorization","proxy-authorization"));
    for(var p:profiles)for(String h:p.headers().keySet()){
      String n=h.toLowerCase(Locale.ROOT);if(Set.of("host","content-length","transfer-encoding","connection","forwarded","x-forwarded-host").contains(n))throw new IllegalArgumentException("계정에 요청 라우팅 헤더를 사용할 수 없습니다.");names.add(n);
    }
    authNames=Set.copyOf(names);
    var settings=new LocalLlmClient.Settings(LocalLlmClient.endpoint(endpoint.getText()),model.getText().strip(),1024,120);
    if(engine!=null)engine.close();long version=++uiGeneration;
    engine=new AutoLlmEngine(new AutoLlmEngine.Host(){
      public void validate(AutoLlmEngine.Config c,AutoLlmEngine.Source source){
        onEdt(AutoLlmPanel.this::validateSession);
        HttpRequest q=HttpRequest.httpRequestFromUrl(source.url());
        if(!AutoLlmEngine.admitted(c,source)||!WorkbenchScope.contains(api,q))throw new IllegalArgumentException("현재 SCOPE 밖 대상");
      }
      public AutoLlmEngine.Reply send(AutoLlmEngine.Capture input,int account) {
        onEdt(AutoLlmPanel.this::validateSession);
        if(!engine.snapshot().running())throw new java.util.concurrent.CancellationException();
        var original=(HttpRequestResponse)input.original();
        HttpRequest q=Core.sessionRequest(original.request(),profiles.get(account).headers(),authNames);
        if(!WorkbenchScope.contains(api,q))throw new IllegalArgumentException("전송 직전 SCOPE 이탈");
        long began=System.nanoTime();var rr=api.http().sendRequest(q,RequestOptions.requestOptions().withResponseTimeout(10000).withRedirectionMode(RedirectionMode.NEVER));
        long elapsed=(System.nanoTime()-began)/1000000;
        if(rr==null||!EvidenceLinks.bounded(rr.request(),rr.response()))throw new IllegalArgumentException("응답 없음/128 KiB 초과 · 중지");
        return new AutoLlmEngine.Reply(rr.response().statusCode(),rr.response().bodyToString(),elapsed,rr);
      }
    },(input,call)->planning.plan(settings,input,call),s->SwingUtilities.invokeLater(()->{if(!closed&&version==uiGeneration)render(s);}));
    accounts.setText("A: "+auth.label(0)+" / B: "+auth.label(1)+" · 시작 시 인증 고정, 변경 시 중지");engine.start(config);
  }
  private void validateSession() {
    if(closed||!Objects.equals(project,WorkspaceResume.projectIdentity(api))||auth.record.isSelected()||profiles.size()!=2
        ||auth.slots[0]!=profiles.get(0)||auth.slots[1]!=profiles.get(1))throw new IllegalArgumentException("계정/프로젝트/기존 자동 비교 상태 변경");
  }
  private static void onEdt(Runnable r){if(SwingUtilities.isEventDispatchThread()){r.run();return;}try{SwingUtilities.invokeAndWait(r);}catch(Exception e){throw new IllegalStateException("실행 환경 변경",e);}}
  void offer(DiskTrafficStore.PacketRef ref) {
    AutoLlmEngine target=engine;if(target==null||ref==null)return;
    target.offer(new AutoLlmEngine.Source(ref.id(),ref.method(),ref.url(),ref.source(),(int)Math.min(Integer.MAX_VALUE,(long)ref.requestLength()+ref.responseLength()),()->{
      var rr=ref.packet();EvidenceLinks.checked(api,List.of(rr));
      if(rr.response().statusCode()==429 || rr.response().statusCode()>=500)throw new IllegalArgumentException("관측 응답에서 오류/가용성 우려 확인 · 추가 전송 중지");
      if(rr.request().hasHeader("X-HTTP-Method-Override")||rr.request().hasHeader("X-Method-Override")||rr.request().body().length()!=0)throw new IllegalArgumentException("GET 본문은 자동 검사 제외");
      // An observed A request must match the declared baseline identity; do not guess its owner.
      for(String name:authNames){String expected=profiles.get(0).headers().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse("");
        long count=rr.request().headers().stream().filter(h->h.name().equalsIgnoreCase(name)).count();
        String actual=Objects.toString(rr.request().headerValue(name),"");if(count>1||!actual.equals(expected))throw new IllegalArgumentException("원본이 테스트 계정 A 인증과 다릅니다.");}
      String text=BountyEvidence.localLlmPreview(rr.request().toString(),rr.response().toString());
      return new AutoLlmEngine.Capture(rr,"원본 ID: "+ref.id()+"\n"+text.substring(0,Math.min(16000,text.length())));
    }));
  }
  private void render(AutoLlmEngine.Snapshot s) {
    String selected=selected()==null?null:selected().id();snapshot=s;rows.setRowCount(0);int index=0;
    for(var r:s.results()){rows.addRow(new Object[]{r.source().id(),Core.safeUrl(r.source().url()),r.state(),r.note()});if(r.id().equals(selected))table.setRowSelectionInterval(index,index);index++;}
    state.setText(s.status()+" · 대상 요청 "+s.requests()+" · 모델 "+s.models()+" · 대기 "+s.queued()+" · 제외 "+s.excluded());controls(s.busy());showSelected();
  }
  private AutoLlmEngine.Result selected(){int row=table.getSelectedRow();return snapshot!=null&&row>=0&&row<snapshot.results().size()?snapshot.results().get(row):null;}
  private void showSelected(){var r=selected();approve.setEnabled(r!=null&&r.state().equals("사용자 확인 대기")&&snapshot.running());evidence.setEnabled(r!=null&&r.capture()!=null&&!snapshot.busy());
    if(r==null){detail.setText("");return;}
    StringBuilder t=new StringBuilder(r.state()+"\n"+r.note()+"\n원본 ID: "+r.source().id()+"\n");
    if(r.plan()!=null)t.append("모델 검사안: ").append(r.plan().reason()).append("\n가용성 영향 검토 필요: ").append(r.plan().availabilityRisk()).append('\n');
    if(r.capture()!=null)t.append(r.capture().preview()).append('\n');
    for(int i=0;i<r.replies().size();i++){var reply=r.replies().get(i);t.append("재현 ").append(i+1).append(i==1?" 계정 B":" 계정 A").append(" · HTTP ").append(reply.status()).append(" · ").append(reply.millis()).append("ms\n");}
    t.append("LLM 판단/응답 일치만으로 취약점 확정하지 않음. 고비용·변경 검사는 이 실행기의 지원 대상이 아닙니다.");detail.setText(t.toString());detail.setCaretPosition(0);
  }
  private void approveSelected(){var r=selected();if(r==null)return;
    String text="가용성 영향 검토가 필요한 검사입니다.\n"+Core.safeUrl(r.source().url())+"\n"+r.plan().reason()+"\n\n고정 검사: 동일 GET을 테스트 계정 A → B → A로 최대 3회 재전송\n설정한 간격·총 예산·오류/지연 중지는 유지됩니다.\n이 항목에만 1회 적용하며 생성 후 3분이 지나면 만료됩니다.\n실행할까요?";
    if(JOptionPane.showConfirmDialog(this,text,"가용성 영향 검사 · 개별 확인",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)==JOptionPane.YES_OPTION)
      if(!engine.approve(r.id()))state.setText("승인 불가 · 만료/예산/실행 상태/SCOPE를 확인하세요.");
  }
  private void saveEvidence(){var r=selected();if(r==null||r.capture()==null)return;validateSession();List<HttpRequestResponse> packets=new ArrayList<>();packets.add((HttpRequestResponse)r.capture().original());for(var reply:r.replies())packets.add((HttpRequestResponse)reply.packet());
    EvidenceLinks.checked(api,packets);bounty.captureObserved(packets,"[범위 제한 자동 검사 · 확정 취약점 아님]\n원본 ID: "+r.source().id()+"\n검사 ID: "+r.id()+"\n"+r.note());state.setText("원본·재현 요청/응답을 증거에 추가했습니다.");}
  private void controls(boolean busy){start.setEnabled(!busy);stop.setEnabled(busy);for(JComponent c:List.of(origin,paths,endpoint,model,budget,modelBudget,interval,slow,readOnly,privateData))c.setEnabled(!busy);}
  void reset(){uiGeneration++;if(engine!=null)engine.reset();snapshot=null;rows.setRowCount(0);detail.setText("");profiles=List.of();controls(false);}
  public void close(){closed=true;lifecycle.stop();if(engine!=null)engine.close();reset();}
}
