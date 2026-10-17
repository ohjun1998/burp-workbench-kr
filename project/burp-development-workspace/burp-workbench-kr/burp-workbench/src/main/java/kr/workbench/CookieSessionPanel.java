package kr.workbench;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Per-account session inspection. Settings and credentials remain in memory. */
final class CookieSessionPanel extends JPanel implements AutoCloseable,Scrollable {
  final AuthComparePanel owner;
  final JComboBox<String> account=new JComboBox<>(new String[]{"계정 1","계정 2","계정 3"});
  final JTextField url=new JTextField(34),identityPath=new JTextField("$.user.id",14),expected=new JTextField(14);
  final JCheckBox beforeCompare=new JCheckBox("비교 전 신원 검사",true),filterCookies=new JCheckBox("검증한 쿠키 조합 반영",true);
  final JButton run=new JButton("검사 시작"),stop=new JButton("중지"),apply=new JButton("검증 결과 적용"),disable=new JButton("비교 전 검사 해제");
  final JLabel profile=new JLabel("비교 계정을 먼저 지정하세요.");
  final JTextArea summary=new JTextArea("사용자 정보를 반환하는 GET URL과 기대 사용자 값을 입력하세요.",2,50);
  final DefaultTableModel cookieModel=AuthComparePanel.model("쿠키 이름","제외 시","단독 사용","분석 결과"),
      trialModel=AuthComparePanel.model("검증 단계","사용 쿠키 이름","HTTP","결과","근거");
  final JTable cookies=new JTable(cookieModel),trials=new JTable(trialModel);
  final JTabbedPane results=new JTabbedPane();
  final AtomicLong generation=new AtomicLong();
  volatile Future<?> pending;
  CookieSessionProbe.Report report;
  CookieSessionProbe.Config testedConfig;
  AuthComparePanel.Candidate testedCandidate;
  int testedSlot=-1;
  boolean loading,busy;

  CookieSessionPanel(AuthComparePanel owner) {
    super(new BorderLayout(8,8));this.owner=owner;setName("wb.auth.session.panel");
    setBorder(BorderFactory.createEmptyBorder(4,4,4,4));
    url.setName("wb.auth.session.url");identityPath.setName("wb.auth.session.identity.path");expected.setName("wb.auth.session.identity.expected");
    account.setPreferredSize(new Dimension(96,32));
    url.setToolTipText("같은 Origin과 SCOPE의 로그인 사용자 확인 GET API");
    identityPath.setToolTipText("예: $.user.id 또는 $.username · 응답 JSON의 사용자 식별값");
    expected.setToolTipText("해당 계정의 사용자 ID 또는 이름 · HTTP 200만으로 통과하지 않습니다.");
    run.setToolTipText("명시한 GET URL에 직렬 요청 · 쿠키 최대 32개, 총 128회 이내 · 자동 리다이렉트 없음");
    summary.setEditable(false);summary.setLineWrap(true);summary.setWrapStyleWord(true);summary.putClientProperty("wb.prose",true);summary.setAlignmentX(Component.LEFT_ALIGNMENT);
    beforeCompare.setToolTipText("비교 요청마다 최대 두 번의 사용자 확인/비로그인 GET을 먼저 실행하고 신원 확인 실패 시 중단합니다.");
    JPanel top=TargetWindow.options(
        WorkbenchUi.row(new JLabel("검사 계정"),account,profile),
        WorkbenchUi.row(new JLabel("검증 URL"),url),
        WorkbenchUi.row(new JLabel("사용자 JSON"),identityPath,new JLabel("기대 사용자"),expected),
        WorkbenchUi.row(run,stop,disable),summary);
    for(Component child:top.getComponents())if(child instanceof JPanel row&&row.getLayout() instanceof WorkbenchUi.WrapLayout layout)layout.setVgap(2);
    add(top,BorderLayout.NORTH);
    cookies.setName("wb.auth.session.cookies");trials.setName("wb.auth.session.trials");
    for(JTable table:new JTable[]{cookies,trials}) {
      table.setAutoCreateRowSorter(true);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
      table.putClientProperty("wb.table.compact",true);
    }
    int[] cookieWidths={150,100,110,290},trialWidths={180,250,60,100,240};
    for(int i=0;i<cookieWidths.length;i++)cookies.getColumnModel().getColumn(i).setPreferredWidth(cookieWidths[i]);
    for(int i=0;i<trialWidths.length;i++)trials.getColumnModel().getColumn(i).setPreferredWidth(trialWidths[i]);
    results.addTab("쿠키별 결과",new JScrollPane(cookies));results.addTab("검증 요청",new JScrollPane(trials));
    results.setPreferredSize(new Dimension(700,220));add(results);
    add(WorkbenchUi.row(filterCookies,beforeCompare,apply),BorderLayout.SOUTH);
    run.addActionListener(e->safe(this::start));stop.addActionListener(e->cancel());apply.addActionListener(e->safe(this::apply));
    disable.addActionListener(e->safe(()->{owner.editable();owner.sessionChecks[account.getSelectedIndex()]=null;owner.refreshSlots();summary.setText("해당 계정의 비교 전 신원 검사를 해제했습니다.");}));
    account.addActionListener(e->{if(!loading)loadSlot();});
    var listener=new javax.swing.event.DocumentListener() {
      void changed(){if(!loading)invalidateResult();}
      public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}
      public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}
      public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}
    };
    for(JTextField field:new JTextField[]{url,identityPath,expected})field.getDocument().addDocumentListener(listener);
    stop.setEnabled(false);apply.setEnabled(false);WorkbenchUi.style(this);
  }
  public Dimension getPreferredScrollableViewportSize(){return new Dimension(880,600);}
  public int getScrollableUnitIncrement(Rectangle area,int orientation,int direction){return 24;}
  public int getScrollableBlockIncrement(Rectangle area,int orientation,int direction){return 120;}
  public boolean getScrollableTracksViewportWidth(){return true;}
  public boolean getScrollableTracksViewportHeight(){return true;}
  static JDialog window(Component owner,String title,Component content,boolean modal,Runnable closed) {
    if(GraphicsEnvironment.isHeadless())return null;
    JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(owner),title,Dialog.ModalityType.MODELESS);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    dialog.setContentPane(WorkbenchUi.settingsBody(content,dialog::dispose));
    dialog.setMinimumSize(new Dimension(760,480));dialog.setSize(1024,720);dialog.setLocationRelativeTo(owner);
    dialog.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent event){if(closed!=null)closed.run();}});
    dialog.setVisible(true);return dialog;
  }
  void safe(Runnable action){try{action.run();}catch(IllegalArgumentException|RejectedExecutionException e){summary.setText(e instanceof RejectedExecutionException?"작업 대기열을 사용할 수 없습니다.":e.getMessage());}}
  CookieSessionProbe.Config config(){return new CookieSessionProbe.Config(url.getText().strip(),identityPath.getText().strip(),expected.getText().strip());}
  void invalidateResult(){generation.incrementAndGet();report=null;testedConfig=null;testedCandidate=null;apply.setEnabled(false);cookieModel.setRowCount(0);trialModel.setRowCount(0);}
  void loadSlot() {
    if(busy||owner.closed.get())return;
    invalidateResult();loading=true;
    try {
      int slot=account.getSelectedIndex();var candidate=owner.slots[slot];var config=owner.sessionChecks[slot];
      url.setText(config==null?"":config.url());
      identityPath.setText(config==null?(owner.refreshIdentityPaths[slot].getText().isBlank()?"$.user.id":owner.refreshIdentityPaths[slot].getText()):config.identityPath());
      expected.setText(config==null?owner.profileOwners[slot].getText():config.expectedIdentity());
      profile.setText(candidate==null?"미지정":candidate.headers().isEmpty()?"비로그인":owner.label(slot)+" · "+candidate.key().substring(0,Math.min(12,candidate.key().length())));
      summary.setText(config==null?"사용자 정보를 반환하는 GET URL과 기대 사용자 값을 입력하세요.":"비교 전 검사 설정됨 · 쿠키 역할은 검사 시작으로 다시 확인합니다.");
    } finally {loading=false;}
  }
  void busy(boolean value) {
    busy=value;run.setEnabled(!value);stop.setEnabled(value);disable.setEnabled(!value);account.setEnabled(!value);
    for(JTextField field:new JTextField[]{url,identityPath,expected})field.setEnabled(!value);
    apply.setEnabled(!value&&report!=null&&report.valid()&&report.complete());
  }
  void start() {
    if(owner.closed.get()||busy)return;
    owner.editable();var config=config();int slot=account.getSelectedIndex();var candidate=owner.slots[slot];
    if(candidate==null||candidate.headers().isEmpty())throw new IllegalArgumentException("인증 후보를 비교 계정으로 지정한 뒤 검사하세요.");
    CookieSessionProbe.cookies(candidate.headers());
    invalidateResult();long token=generation.get(),parentEpoch=owner.epoch.get();
    Set<String> names=Set.copyOf(owner.authNames);busy(true);owner.active.incrementAndGet();
    summary.setText("검사 중 · 전체 세션/비로그인 대조와 쿠키 제외·단독·조합을 순서대로 확인합니다.");
    try {
      pending=owner.worker.submit(()->{
        CookieSessionProbe.Report result;
        try {
          result=CookieSessionProbe.analyze(owner.api,config,candidate.headers(),names,candidate.origin(),
              ()->owner.closed.get()||generation.get()!=token||owner.epoch.get()!=parentEpoch);
        } catch(Exception error){result=new CookieSessionProbe.Report(false,false,"검사 설정/전송 오류",List.of(),List.of(),Set.of(),false);}
        final CookieSessionProbe.Report completed=result;
        SwingUtilities.invokeLater(()->{
          owner.active.decrementAndGet();busy(false);
          if(owner.closed.get()||generation.get()!=token||owner.epoch.get()!=parentEpoch)return;
          show(completed);testedConfig=config;testedCandidate=candidate;testedSlot=slot;
        });
      });
    } catch(RejectedExecutionException error){owner.active.decrementAndGet();busy(false);throw error;}
  }
  void show(CookieSessionProbe.Report report) {
    this.report=report;summary.setText(report.summary());cookieModel.setRowCount(0);trialModel.setRowCount(0);
    for(var finding:report.findings())cookieModel.addRow(new Object[]{finding.name(),finding.excluded(),finding.alone(),finding.result()});
    for(var trial:report.trials())trialModel.addRow(new Object[]{trial.label(),String.join(", ",trial.cookies()),trial.status(),CookieSessionProbe.text(trial.outcome()),trial.note()});
    apply.setEnabled(!busy&&report.valid()&&report.complete());
  }
  void apply() {
    owner.editable();
    if(owner.closed.get()||busy||report==null||!report.valid()||!report.complete()||testedCandidate==null
        ||testedSlot!=account.getSelectedIndex()||!Objects.equals(config(),testedConfig)
        ||owner.slots[testedSlot]==null||!owner.slots[testedSlot].headers().equals(testedCandidate.headers())
        ||!owner.slots[testedSlot].origin().equals(testedCandidate.origin()))
      throw new IllegalArgumentException("계정/설정이 변경되었습니다. 다시 검사하세요.");
    var headers=report.cookieOnly()&&filterCookies.isSelected()?CookieSessionProbe.retain(testedCandidate.headers(),report.retained()):testedCandidate.headers();
    String key=Core.hash((testedCandidate.origin()+"|"+MetadataExport.json(headers)).getBytes(StandardCharsets.UTF_8));
    String linked=owner.trackingAccounts[testedSlot];
    var reduced=new AuthComparePanel.Candidate(testedCandidate.no(),key,testedCandidate.origin(),headers,testedCandidate.count(),testedCandidate.used());
    owner.copyAccount(testedCandidate,reduced);owner.assign(testedSlot,reduced);
    owner.trackingAccounts[testedSlot]=linked;owner.refreshTracking();
    owner.sessionChecks[testedSlot]=beforeCompare.isSelected()?testedConfig:null;owner.refreshSlots();
    invalidateResult();summary.setText("계정에 검사 결과 적용 · 다음 비교부터 "+(beforeCompare.isSelected()?"신원 확인 후 전송":"현재 인증 조합 사용"));
    owner.state.setText("세션 검사 결과 적용 · "+owner.label(testedSlot));
  }
  void cancel(){generation.incrementAndGet();apply.setEnabled(false);summary.setText("중지 요청 · 이미 전송한 요청은 응답/타임아웃 후 종료합니다.");}
  @Override public void close(){generation.incrementAndGet();Runnable clear=()->{loading=true;url.setText("");identityPath.setText("");expected.setText("");loading=false;invalidateResult();summary.setText("");};if(SwingUtilities.isEventDispatchThread())clear.run();else SwingUtilities.invokeLater(clear);}
}
