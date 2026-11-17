package kr.payloadrunner;

import burp.*;
import kr.workbench.WorkbenchUi;import kr.workbench.FeatureGuide;
import kr.workbench.PacketView;
import kr.workbench.PacketFormat;
import kr.workbench.PayloadSessionCheck;

import kr.payloadrunner.Engine;
import kr.payloadrunner.Adaptive;
import kr.payloadrunner.CallbackProbe;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URLDecoder;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Payload Runner KR 1.3.0 embedded in Workbench; no separate Burp entry point or suite tab. */
public final class PayloadRunnerPanel implements IContextMenuFactory,IExtensionStateListener {
 private java.util.function.Predicate<java.net.URL> workbenchScope;
 public void setScopePredicate(java.util.function.Predicate<java.net.URL> predicate){workbenchScope=predicate;}
 private boolean inScope(java.net.URL url){return workbenchScope==null?callbacks.isInScope(url):workbenchScope.test(url);}

 IBurpExtenderCallbacks callbacks; JPanel panel;private burp.api.montoya.MontoyaApi payloadApi;private PayloadHttp transport;
 public void setMontoyaApi(burp.api.montoya.MontoyaApi api){payloadApi=api;if(transport!=null)transport.api=api;}
 final JSpinner responseTimeout=new JSpinner(new SpinnerNumberModel(10,1,120,1));
 final JComboBox<Engine.Profile> profile=new JComboBox<>(Engine.Profile.values());
 final JComboBox<Engine.Encoding> encoding=new JComboBox<>(Engine.Encoding.values());
 final JComboBox<String> scheme=new JComboBox<>(new String[]{"http","https"});
 final JTextField host=new JTextField("127.0.0.1",18),base=new JTextField("",20),proof=new JTextField("",20);
 final JSpinner port=new JSpinner(new SpinnerNumberModel(8088,1,65535,1)),interval=new JSpinner(new SpinnerNumberModel(250,0,60000,50)),budget=new JSpinner(new SpinnerNumberModel(100,1,1000,10));
 final JComboBox<String> mode=new JComboBox<>(new String[]{"응답 기반 · 적응형", "전체 목록 · 순차 전송"});
 volatile CallbackProbe collector;
 final JCheckBox uploadReadback=new JCheckBox("업로드 반환 URL 재조회 · 같은 대상",true),callbackMode=new JCheckBox("SSRF · 로컬 HTTP 콜백 2회 검증(목록 대신)",false);
 final JSpinner callbackPort=new JSpinner(new SpinnerNumberModel(19112,1,65535,1));
 final JCheckBox scope=new JCheckBox("Workbench SCOPE 내 요청만",true);
 final JCheckBox sessionControl=new JCheckBox("검사 전·401/403 후 신원 대조 GET · 요청 한도 포함",false);
 final JTextField identityPath=new JTextField("/api/me",20),identityField=new JTextField("$.id",12),identityValue=new JTextField("",14);
 final JTextArea request=new JTextArea("GET /api/community/vuln/search?q=§PAYLOAD§ HTTP/1.1\r\nHost: 127.0.0.1:8088\r\nAccept: application/json\r\n\r\n",10,70),payloads=new JTextArea(8,35),detail=new JTextArea();
 final JButton start=new JButton("Start"),stopButton=new JButton("중지"),mark=new JButton("선택 영역 → §PAYLOAD§"),reset=new JButton("기본 페이로드"),export=new JButton("CSV 내보내기"),repeater=new JButton("선택 요청 → Repeater"),clear=new JButton("결과 지우기");
 final JButton background=new JButton("작업 창 열기"),settingsButton=new JButton("검사 설정"),moreButton=new JButton("더보기"),helpButton=new JButton("유형 설명"),evidenceButton=new JButton("결과를 증거로");final JToggleButton requestPreview=new JToggleButton("정렬 미리보기");
 final JProgressBar progress=new JProgressBar();
 JDialog taskWindow;JTable taskTable;JTextArea taskDetail;
 final JLabel profileHint=new JLabel(),configSummary=new JLabel(),targetSummary=new JLabel(),typeSummary=new JLabel(),accountSummary=new JLabel(),positionSummary=new JLabel(),limitSummary=new JLabel(),scopeSummary=new JLabel();
 private JPanel advancedSettings,inspectionOptions;
 final JLabel status=new JLabel("요청을 우클릭해 가져오거나 raw 요청을 붙여 넣으세요.");
 final DefaultTableModel model=new DefaultTableModel(new String[]{"#","페이로드","상태","bytes","ms","판정","근거"},0){public boolean isCellEditable(int r,int c){return false;}};
 final JTable table=new JTable(model);
 final JToggleButton allResults=new JToggleButton(),observedResults=new JToggleButton(),controlResults=new JToggleButton();
 final JLabel resultCount=new JLabel("검사 결과 · 0건");
 private TableRowSorter<DefaultTableModel> resultSorter;
 private int resultFilter;
 final JTabbedPane workflow=kr.workbench.CompactWorkbenchUi.pages();
 record Entry(Engine.Row row,IHttpService service){}
 final List<Entry> entries=new ArrayList<>();final AtomicBoolean cancel=new AtomicBoolean();volatile boolean running,closed;
 final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"PayloadRunner-KR");t.setDaemon(true);return t;});
 private Runnable onImported=()->{};
 private java.util.function.BiConsumer<IHttpService,List<Engine.Row>> evidenceSink;
 public void setEvidenceSink(java.util.function.BiConsumer<IHttpService,List<Engine.Row>> sink){evidenceSink=sink;if(SwingUtilities.isEventDispatchThread())refreshEvidenceAction();else SwingUtilities.invokeLater(this::refreshEvidenceAction);}
 void refreshEvidenceAction(){
  String reason=closed?"확장이 종료되었습니다.":running?"검사가 끝난 뒤 결과를 증거에 연결하세요.":evidenceSink==null?"증거 연결을 준비하고 있습니다.":entries.isEmpty()?"검사 결과가 없습니다. 검사 후 결과 행을 선택하세요.":entries.size()>32?"증거는 최대 32개입니다. 요청 한도를 줄여 다시 검사하세요.":selected()==null?"결과 행을 선택하면 이 검사의 기준·공격·대조 요청을 연결합니다.":"이 검사의 기준·공격·대조 요청을 증거 정리에 연결합니다.";
  evidenceButton.setEnabled(!closed&&!running&&evidenceSink!=null&&!entries.isEmpty()&&entries.size()<=32&&selected()!=null);evidenceButton.setToolTipText(reason);
  repeater.setEnabled(!closed&&selected()!=null);
 }
 public void captureEvidence(){try{if(running)throw new IllegalArgumentException("검사 종료 후 증거를 추가하세요.");Entry selected=selected();if(selected==null)throw new IllegalArgumentException("검사 결과를 선택하세요.");if(evidenceSink==null)throw new IllegalArgumentException("증거 연결 준비 중");if(entries.size()>32)throw new IllegalArgumentException("한 검사 증거는 최대 32개 · 요청 한도를 32 이하로 줄여 다시 검사하세요.");evidenceSink.accept(selected.service,entries.stream().map(Entry::row).toList());status.setText("기준·공격·대조 결과를 증거 정리에 연결했습니다.");}catch(Exception ex){status.setText(ex.getMessage());}}

 public Component initializeEmbedded(IBurpExtenderCallbacks c,Runnable onImported){
  if(callbacks!=null)throw new IllegalStateException("Payload Runner already initialized");
  callbacks=Objects.requireNonNull(c);transport=new PayloadHttp(callbacks,payloadApi);this.onImported=Objects.requireNonNull(onImported);
  try{if(SwingUtilities.isEventDispatchThread())buildUI();else SwingUtilities.invokeAndWait(this::buildUI);}
  catch(Exception e){extensionUnloaded();throw new IllegalStateException("Payload Runner UI initialization failed",e);}
  c.registerContextMenuFactory(this);c.registerExtensionStateListener(this);
  c.printOutput("Workbench: Payload Runner KR 1.3.0 initialized. No requests sent until Start.");
  return panel;
 }
 void buildUI(){
  panel=new JPanel(new BorderLayout(8,8));panel.setName("wb.payload.root");panel.addPropertyChangeListener("wb.packet.horizontal",e->{if(taskWindow!=null)WorkbenchUi.packetLayout(taskWindow.getContentPane(),Boolean.TRUE.equals(e.getNewValue()));});panel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
  JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
  settingsButton.addActionListener(e->{if(running)return;workflow.setSelectedIndex(0);if(advancedSettings!=null&&advancedSettings.getComponent(0) instanceof AbstractButton toggle){if(!toggle.isSelected())toggle.doClick();advancedSettings.scrollRectToVisible(new Rectangle(0,0,advancedSettings.getWidth(),Math.max(50,advancedSettings.getHeight())));}});
  helpButton.addActionListener(e->JOptionPane.showMessageDialog(panel,Engine.hint((Engine.Profile)profile.getSelectedItem()),"검사 유형 설명",JOptionPane.INFORMATION_MESSAGE));
  // Keep execution fixed while selection actions stay with the result table.
  // CSV export and the standalone task window stay outside the main workflow.
  evidenceButton.setName("wb.simple.payload.evidence");evidenceButton.setToolTipText("종료된 검사의 결과 행을 선택하면 기준·공격·대조 요청을 증거에 연결합니다.");evidenceButton.addActionListener(e->captureEvidence());evidenceButton.setEnabled(false);
  table.setName("wb.payload.results.table");
  start.setName("wb.simple.payload.start");stopButton.setName("wb.simple.payload.stop");settingsButton.setName("wb.simple.payload.settings");request.setName("wb.simple.payload.request");payloads.setName("wb.simple.payload.values");
  model.addTableModelListener(e->{refreshEvidenceAction();refreshResultCounts();});
  Font mono=new Font(Font.MONOSPACED,Font.PLAIN,12);request.setFont(mono);payloads.setFont(mono);detail.setFont(mono);detail.setEditable(false);detail.setLineWrap(true);detail.setWrapStyleWord(true);
  request.putClientProperty("wb.packet.syntax",true);
  JPanel req=new JPanel(new BorderLayout());
  mark.setName("wb.payload.mark");requestPreview.setName("wb.payload.preview");
  mark.setText("선택 값 표시");mark.setToolTipText("선택한 값을 §PAYLOAD§ 검사 위치로 표시합니다. 요청 전송 없음.");
  JPanel prepareActions=WorkbenchUi.row(mark,kr.workbench.WorkflowUi.menu("wb.flow.payload.edit","편집",requestPreview));prepareActions.setName("wb.actions.payload.prepare");
  JPanel requestActions=kr.workbench.CompactWorkbenchUi.toolbar(new JLabel("검사 요청"),prepareActions);
  requestActions.setName("wb.placement.payload.request");req.add(requestActions,BorderLayout.NORTH);
  CardLayout requestCards=new CardLayout();JPanel requestBody=new JPanel(requestCards);PacketView requestReadOnly=new PacketView();requestBody.add(new JScrollPane(request),"edit");requestBody.add(requestReadOnly,"preview");requestPreview.addActionListener(e->{if(requestPreview.isSelected()){requestReadOnly.setPacket(request.getText(),PacketFormat.decode(request.getText().getBytes(StandardCharsets.ISO_8859_1)));requestCards.show(requestBody,"preview");}else requestCards.show(requestBody,"edit");});req.add(requestBody,BorderLayout.CENTER);
  JPanel pay=new JPanel(new BorderLayout(0,4));pay.add(new JScrollPane(payloads),BorderLayout.CENTER);pay.add(WorkbenchUi.row(reset),BorderLayout.SOUTH);pay.setPreferredSize(new Dimension(220,170));
  JPanel candidates=kr.workbench.CompactWorkbenchUi.disclosure("wb.payload.candidates","검사 후보",pay,false);
  Runnable candidateCount=()->{long count=payloads.getText().lines().map(String::trim).filter(v->!v.isEmpty()&&!v.startsWith("#")).count();((AbstractButton)candidates.getComponent(0)).setText("검사 후보 · "+count+"개");};
  payloads.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){candidateCount.run();}public void removeUpdate(javax.swing.event.DocumentEvent e){candidateCount.run();}public void changedUpdate(javax.swing.event.DocumentEvent e){}});
  profile.setName("wb.payload.condition.profile");encoding.setName("wb.payload.condition.encoding");budget.setName("wb.payload.condition.budget");interval.setName("wb.payload.condition.interval");
  JPanel conditionBody=new ConditionColumn();conditionBody.setLayout(new BoxLayout(conditionBody,BoxLayout.Y_AXIS));conditionBody.setName("wb.payload.conditions");conditionBody.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
  conditionBody.add(conditionRow("검사 유형",profile));conditionBody.add(conditionRow("인코딩",encoding));conditionBody.add(conditionRow("총 요청 한도",budget));conditionBody.add(conditionRow("간격(ms)",interval));
  positionSummary.setName("wb.payload.condition.position");limitSummary.setName("wb.payload.condition.limit");scopeSummary.setName("wb.payload.condition.scope");
  for(JLabel summary:new JLabel[]{positionSummary,limitSummary,scopeSummary}){summary.setAlignmentX(Component.LEFT_ALIGNMENT);summary.putClientProperty("wb.secondary",true);conditionBody.add(summary);conditionBody.add(Box.createVerticalStrut(6));}
  conditionBody.add(bounded(candidates));conditionBody.add(Box.createVerticalStrut(8));
  advancedSettings=kr.workbench.CompactWorkbenchUi.disclosure("wb.payload.advanced","고급 실행 조건",inspectionSettings(),false);conditionBody.add(bounded(advancedSettings));
  JPanel conditions=WorkbenchUi.card("검사 조건",new JScrollPane(conditionBody));conditions.setName("wb.flow.payload.conditions");conditions.setPreferredSize(new Dimension(285,500));conditions.setMinimumSize(new Dimension(230,100));
  JPanel editors=new JPanel(new BorderLayout(10,0));editors.setName("wb.flow.payload.preparation");editors.add(req);editors.add(conditions,BorderLayout.EAST);editors.setMinimumSize(new Dimension(80,60));
  resultSorter=new TableRowSorter<>(model);resultSorter.setComparator(4,Comparator.comparingLong(value->((Number)value).longValue()));table.setRowSorter(resultSorter);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);table.removeColumn(table.getColumnModel().getColumn(6));
  String[] headers={"번호","검사값","HTTP","크기","응답 시간(ms)","관측 결과"};int[] widths={55,300,75,85,100,180};
  kr.workbench.CompactWorkbenchUi.columns(table,widths);for(int i=0;i<headers.length;i++)table.getColumnModel().getColumn(i).setHeaderValue(headers[i]);
  for(int col:new int[]{0,2,3,4})table.getColumnModel().getColumn(col).setMaxWidth(col==0?65:115);
  mode.setToolTipText("적응형은 기준·탐색 응답을 분석해 후보 순서 조정, 대조·반복 요청, 조기 종료를 수행합니다. 전체 목록은 후보를 순서대로 전송합니다. 규칙 기반이며 AI 모델은 사용하지 않습니다.");
  repeater.setText("Repeater 열기");repeater.setName("wb.payload.repeater");
  export.setName("wb.payload.export");clear.setName("wb.payload.clear");
  JPanel management=kr.workbench.WorkflowUi.menu("wb.flow.payload.result.other","검사 관리",start,settingsButton,export,clear);
  JPanel resultActions=new JPanel(new GridBagLayout());resultActions.setName("wb.actions.payload.results");GridBagConstraints resultCell=new GridBagConstraints();resultCell.insets=new Insets(0,3,0,3);resultActions.add(evidenceButton,resultCell);resultActions.add(repeater,resultCell);resultActions.add(management,resultCell);
  ButtonGroup filters=new ButtonGroup();JToggleButton[] filterButtons={allResults,observedResults,controlResults};String[] filterNames={"all","observed","control"};
  for(int i=0;i<filterButtons.length;i++){final int selectedFilter=i;JToggleButton filter=filterButtons[i];filter.setName("wb.payload.results.filter."+filterNames[i]);filter.putClientProperty("wb.compact.button",true);filters.add(filter);filter.addActionListener(e->filterResults(selectedFilter));}allResults.setSelected(true);observedResults.setToolTipText("기준·대조를 제외한 검사 결과입니다. 취약점 확정을 뜻하지 않습니다.");controlResults.setToolTipText("엔진이 기록한 기준·재검증·대조 단계입니다. 검사값 문자열로 분류하지 않습니다.");refreshResultCounts();
  JPanel resultBody=new JPanel(new BorderLayout(0,4));
  JPanel resultCommands=kr.workbench.CompactWorkbenchUi.toolbar(resultCount,WorkbenchUi.row(allResults,observedResults,controlResults));
  resultCommands.setName("wb.placement.payload.results");resultBody.add(resultCommands,BorderLayout.NORTH);resultBody.add(new JScrollPane(table));
  JSplitPane resultWorkspace=WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT,resultBody,detailPane(detail),.40);
  resultWorkspace.setName("wb.payload.workspace");resultWorkspace.putClientProperty("wb.workflow.fixedLayout",true);
  workflow.putClientProperty("wb.hidden.tabs",true);workflow.setName("wb.payload.steps");workflow.addTab("1 요청 준비",editors);workflow.addTab("2 실행·결과",resultWorkspace);
  panel.add(workflow,BorderLayout.CENTER);
  start.setText("검사 시작");start.setToolTipText("설정한 입력 위치에 시험 문자열을 넣어 실제 요청을 보냅니다. 기준·대조 요청도 총 요청 한도에 포함됩니다.");stopButton.setText("중지");
  settingsButton.setText("조건 확인");
  // Execution and cancellation stay on one baseline when the results-only condition action appears.
  // Let the outer toolbar move this group as a unit instead of wrapping individual controls.
  JPanel controls=new JPanel(new GridBagLayout());controls.setName("wb.actions.payload.execution");GridBagConstraints actionCell=new GridBagConstraints();actionCell.anchor=GridBagConstraints.EAST;actionCell.insets=new Insets(2,3,2,3);controls.add(resultActions,actionCell);controls.add(start,actionCell);controls.add(stopButton,actionCell);
  progress.setStringPainted(true);progress.setPreferredSize(new Dimension(180,32));progress.putClientProperty("wb.reference.progress",true);progress.setString("대기");
  JToggleButton preparation=kr.workbench.ReferenceUi.step(1,"요청 준비"),resultsStep=kr.workbench.ReferenceUi.step(2,"실행 · 결과");preparation.setSelected(true);preparation.setName("wb.payload.step.prepare");resultsStep.setName("wb.payload.step.results");ButtonGroup steps=new ButtonGroup();steps.add(preparation);steps.add(resultsStep);preparation.addActionListener(e->workflow.setSelectedIndex(0));resultsStep.addActionListener(e->workflow.setSelectedIndex(1));workflow.addChangeListener(e->{preparation.setSelected(workflow.getSelectedIndex()==0);resultsStep.setSelected(workflow.getSelectedIndex()==1);});
  JPanel executionHeader=kr.workbench.CompactWorkbenchUi.toolbar(kr.workbench.ReferenceUi.stepRow(preparation,kr.workbench.ReferenceUi.arrow(),resultsStep),controls);executionHeader.setName("wb.placement.payload.execution");top.add(executionHeader);
  Runnable phaseActions=()->{boolean results=workflow.getSelectedIndex()==1;resultActions.setVisible(results);start.setVisible(!results);start.setText(results?"다시 검사":"검사 시작");kr.workbench.WorkflowUi.secondary(start);kr.workbench.WorkflowUi.secondary(evidenceButton);if(results)WorkbenchUi.primary(evidenceButton);else WorkbenchUi.primary(start);controls.revalidate();};workflow.addChangeListener(e->phaseActions.run());phaseActions.run();
  JPanel facts=kr.workbench.ReferenceUi.summary(kr.workbench.ReferenceUi.field("대상",targetSummary),kr.workbench.ReferenceUi.field("검사 유형",typeSummary),kr.workbench.ReferenceUi.field("계정",accountSummary));top.add(facts);
  status.setMinimumSize(new Dimension(0,24));
  JPanel executionState=new JPanel(new BorderLayout(12,0));executionState.add(status);executionState.add(progress,BorderLayout.EAST);executionState.setName("wb.flow.payload.status");
  panel.add(executionState,BorderLayout.SOUTH);panel.add(WorkbenchUi.header(FeatureGuide.title("payload"),"요청 연결 → 검사 위치·설정 → 실행 → 결과를 증거로",top),BorderLayout.NORTH);
  profile.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){super.getListCellRendererComponent(list,value,index,selected,focus);setText(Engine.label((Engine.Profile)value));return this;}});WorkbenchUi.primary(start);stopButton.setEnabled(false);resetPayloads();
  profile.addActionListener(e->{resetPayloads();updateSummary();});scheme.addActionListener(e->updateSummary());mode.addActionListener(e->updateSummary());encoding.addActionListener(e->updateSummary());port.addChangeListener(e->updateSummary());host.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){updateSummary();}public void removeUpdate(javax.swing.event.DocumentEvent e){updateSummary();}public void changedUpdate(javax.swing.event.DocumentEvent e){updateSummary();}});request.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){updateSummary();}public void removeUpdate(javax.swing.event.DocumentEvent e){updateSummary();}public void changedUpdate(javax.swing.event.DocumentEvent e){}});updateSummary();reset.addActionListener(e->resetPayloads());mark.addActionListener(e->{if(running)return;int a=request.getSelectionStart(),b=request.getSelectionEnd();if(a==b){message("요청에서 교체할 파라미터 값·파일명·파일 내용을 선택하세요");return;}base.setText(decodeOriginal(request.getText().substring(a,b)));request.replaceRange(Engine.MARK,a,b);});
  budget.addChangeListener(e->updateSummary());scope.addActionListener(e->updateSummary());
  start.addActionListener(e->startScan());stopButton.addActionListener(e->{cancel.set(true);status.setText("후속 요청 중지 · 이미 보낸 요청은 응답/최대 "+responseTimeout.getValue()+"초 대기 후 종료");});
  clear.addActionListener(e->{if(!running){entries.clear();model.setRowCount(0);detail.setText("");}});
  repeater.addActionListener(e->{Entry entry=selected();if(entry!=null)callbacks.sendToRepeater(entry.service.getHost(),entry.service.getPort(),entry.service.getProtocol().equalsIgnoreCase("https"),entry.row.request(),"Payload Runner "+entry.row.index());});
  table.getSelectionModel().addListSelectionListener(e->{refreshEvidenceAction();Entry entry=selected();if(entry==null){detail.setText("");return;}var r=entry.row;detail.setText("판정: "+r.verdict()+"\n관찰 / 요청 선정 근거: "+r.evidence()+"\n\nREQUEST (byte view)\n"+new String(r.request(),StandardCharsets.ISO_8859_1)+"\n\nRESPONSE\n"+(r.response()==null?"응답 없음":"HTTP "+r.response().status()+" · "+r.response().mime()+" · "+r.response().wireLength()+" bytes\n"+r.response().text()+(r.response().truncated()?"\n[본문/보관 한도로 일부 생략]":"")));detail.setCaretPosition(0);});
  background.addActionListener(e->openTaskWindow());
  export.addActionListener(e->exportCSV());callbacks.customizeUiComponent(panel);phaseActions.run();WorkbenchUi.style(panel);status.putClientProperty("wb.font.size",12);refreshEvidenceAction();
 }
 private static final class ConditionColumn extends JPanel implements Scrollable {
  public Dimension getPreferredScrollableViewportSize(){return new Dimension(260,450);}
  public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 24;}
  public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(24,visible.height-32);}
  public boolean getScrollableTracksViewportWidth(){return true;}
  public boolean getScrollableTracksViewportHeight(){return false;}
 }
 JPanel bounded(JComponent content){JPanel panel=new JPanel(new BorderLayout()){public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}};panel.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(content);return panel;}
 JPanel conditionRow(String title,JComponent value){JPanel row=new JPanel(new BorderLayout(8,0)){public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}};JLabel label=new JLabel(title);label.setPreferredSize(new Dimension(82,32));label.putClientProperty("wb.secondary",true);label.setLabelFor(value);value.setMinimumSize(new Dimension(60,32));row.add(label,BorderLayout.WEST);row.add(value);row.setBorder(BorderFactory.createEmptyBorder(0,0,7,0));row.setAlignmentX(Component.LEFT_ALIGNMENT);return row;}
 boolean controlResult(Engine.Row row){
  String verdict=Objects.toString(row.verdict(),""),stage=Objects.toString(row.evidence(),"").split(" · ",2)[0];
  // Engine reserves zero/negative indices for baseline, revalidation and identity controls,
  // including a baseline halted by authentication or rate limiting. Adaptive.note retains
  // the explicit output-control verdict when it replaces the original stage explanation.
  return row.index()<=0||verdict.startsWith("기준 ")||verdict.startsWith("대조 ")||stage.startsWith("기준 ")
   ||List.of("출력 대조 일치","출력 대조 미완료").contains(verdict)
   ||List.of("후속 기준 재확인","SQL 오류 기준 재확인","SQL 기준 재확인","조건 대조 기준 재확인","존재하지 않는 파일 대조","서로 다른 출력 대조").contains(stage);
 }
 void refreshResultCounts(){long controls=entries.stream().filter(e->controlResult(e.row())).count();allResults.setText("전체 "+entries.size());observedResults.setText("관찰 "+(entries.size()-controls));controlResults.setText("기준·대조 "+controls);resultCount.setText("검사 결과 · "+entries.size()+"건");}
 void filterResults(int filter){
  Entry retained=selected();int modelRow=retained==null?-1:entries.indexOf(retained);resultFilter=filter;
  resultSorter.setRowFilter(filter==0?null:new RowFilter<DefaultTableModel,Integer>(){public boolean include(RowFilter.Entry<? extends DefaultTableModel,? extends Integer> row){int index=row.getIdentifier();if(index<0||index>=entries.size())return false;return controlResult(entries.get(index).row())==(resultFilter==2);}});
  int view=modelRow<0?-1:table.convertRowIndexToView(modelRow);if(view>=0)table.setRowSelectionInterval(view,view);else{table.clearSelection();detail.setText("");}refreshEvidenceAction();
 }
 JPanel inspectionSettings(){if(inspectionOptions!=null)return inspectionOptions;JPanel options=new JPanel();inspectionOptions=options;options.setName("wb.payload.inspection.settings");options.setLayout(new BoxLayout(options,BoxLayout.Y_AXIS));options.add(WorkbenchUi.group("대상",scheme,host,new JLabel("포트"),port,scope));options.add(WorkbenchUi.group("기준값",base,helpButton));options.add(WorkbenchUi.group("실행 방식",mode));options.add(WorkbenchUi.row(new JLabel("응답 대기시간(초)"),responseTimeout,new JLabel("401/403/429/503 후속 요청 중단")));options.add(WorkbenchUi.row(new JLabel("기대 응답 문자열"),proof));options.add(WorkbenchUi.row(sessionControl));options.add(WorkbenchUi.group("신원 대조",identityPath,new JLabel("JSON 경로"),identityField,new JLabel("기대 사용자"),identityValue));options.add(WorkbenchUi.row(uploadReadback));options.add(WorkbenchUi.row(callbackMode,new JLabel("콜백 포트"),callbackPort));WorkbenchUi.style(options);return options;}
 void updateSummary(){String full=scheme.getSelectedItem()+"://"+host.getText()+":"+port.getValue()+" · "+Engine.label((Engine.Profile)profile.getSelectedItem())+" · "+mode.getSelectedItem();String target=host.getText();if(target.length()>35)target=target.substring(0,35)+"…";configSummary.setText(Engine.label((Engine.Profile)profile.getSelectedItem())+" · "+(mode.getSelectedIndex()==0?"적응형":"순차 전송")+" · "+target+":"+port.getValue());configSummary.setToolTipText(full);targetSummary.setText(target+":"+port.getValue());typeSummary.setText(Engine.label((Engine.Profile)profile.getSelectedItem()));accountSummary.setText(request.getText().matches("(?is).*\\r?\\n(?:Cookie|Authorization):.*")?"요청의 인증 사용":"비로그인 (익명)");int locations=0,at=0;while((at=request.getText().indexOf(Engine.MARK,at))>=0){locations++;at+=Engine.MARK.length();}positionSummary.setText(locations==0?"검사 위치를 표시하세요":"검사 위치 · "+locations+"곳");limitSummary.setText("최대 "+budget.getValue()+"회 · 기준·대조 포함");scopeSummary.setText(scope.isSelected()?"Workbench SCOPE 적용":"SCOPE 제한 해제");}
 JPanel detailPane(JTextArea source){
  PacketView req=new PacketView(),res=new PacketView();JTextArea evidence=new JTextArea(2,70);evidence.putClientProperty("wb.prose",true);evidence.setEditable(false);evidence.setLineWrap(true);evidence.setWrapStyleWord(true);
  JLabel summary=new JLabel("결과 행을 선택하세요.");summary.setName(source==detail?"wb.payload.results.selection":"wb.payload.task.selection");summary.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));
  Runnable update=()->{Entry entry=source==detail?selected():selectedTask();if(entry==null||source.getText().isEmpty()){evidence.setText("결과 행을 선택하세요.");summary.setText("결과 행을 선택하세요.");summary.setToolTipText(null);req.setPacket(null);res.setPacket(null);return;}var r=entry.row;evidence.setText("판정: "+r.verdict()+"\n관찰 / 요청 선정 근거: "+r.evidence()+"\n브라우저 실행: 미확인 ("+r.browserExecutionStatus()+")");summary.setText("선택 결과 · "+r.verdict()+" · 브라우저 실행 미확인");summary.setToolTipText(evidence.getText());req.setPacket(PacketFormat.decode(r.request()));if(r.response()==null)res.setPacket("응답 없음");else{var response=r.response();res.setPacket(PacketFormat.decode(response.raw()),PacketFormat.response(response.raw(),response.status(),response.mime(),response.text())+(response.truncated()?"\n[일부 생략]":""));}};
  source.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){update.run();}public void removeUpdate(javax.swing.event.DocumentEvent e){update.run();}public void changedUpdate(javax.swing.event.DocumentEvent e){update.run();}});update.run();
  JPanel pane=new JPanel(new BorderLayout(0,5));JScrollPane reason=new JScrollPane(evidence);reason.setName("wb.flow.payload.reason");reason.setPreferredSize(new Dimension(400,80));reason.setBorder(kr.packetui.VisualTheme.border(1,8,1,8));
  if(source==detail){req.setName("wb.payload.result.request");res.setName("wb.payload.result.response");JSplitPane readers=WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,WorkbenchUi.card("실제 요청",req),WorkbenchUi.card("실제 응답",res),.5);readers.setName("wb.payload.results.messages");readers.putClientProperty("wb.workflow.fixedLayout",true);pane.add(summary,BorderLayout.NORTH);pane.add(readers);pane.add(kr.workbench.CompactWorkbenchUi.disclosure("wb.payload.results.reason","선택 결과의 판정·전송 근거",reason,false),BorderLayout.SOUTH);}
  else{pane.add(reason,BorderLayout.NORTH);pane.add(WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT,WorkbenchUi.card("요청 (Request)",req),WorkbenchUi.card("응답 (Response)",res),.5));}return pane;
 }
 Entry selectedTask(){if(taskTable==null||taskTable.getSelectedRow()<0)return null;int row=taskTable.convertRowIndexToModel(taskTable.getSelectedRow());return row<entries.size()?entries.get(row):null;}
 boolean packetHorizontal(){for(Container c=panel;c!=null;c=c.getParent())if(c instanceof JComponent j&&j.getClientProperty("wb.packet.horizontal") instanceof Boolean value)return value;return false;}
 void openTaskWindow(){
  if(taskWindow!=null){WorkbenchUi.packetLayout(taskWindow.getContentPane(),packetHorizontal());taskWindow.setVisible(true);taskWindow.toFront();return;}
  taskWindow=new JDialog((Frame)null,"입력값 보안 검사 · 백그라운드 작업",false);taskWindow.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
  taskTable=new JTable(model);taskTable.setAutoCreateRowSorter(true);taskTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
  taskDetail=new JTextArea();taskDetail.setEditable(false);taskDetail.setFont(detail.getFont());taskDetail.setLineWrap(true);taskDetail.setWrapStyleWord(true);
  taskTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()){int view=taskTable.getSelectedRow();if(view>=0){int row=taskTable.convertRowIndexToModel(view);Entry entry=entries.get(row);var r=entry.row;taskDetail.setText("판정: "+r.verdict()+"\n관찰 / 요청 선정 근거: "+r.evidence()+"\n\nREQUEST (byte view)\n"+new String(r.request(),StandardCharsets.ISO_8859_1)+"\n\nRESPONSE\n"+(r.response()==null?"응답 없음":r.response().text()));taskDetail.setCaretPosition(0);}}});
  JSplitPane split=WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(taskTable),detailPane(taskDetail),.45);split.setName("wb.payload.task.workspace");
  JLabel taskStatus=new JLabel(status.getText());status.addPropertyChangeListener("text",e->taskStatus.setText((String)e.getNewValue()));
  JProgressBar taskProgress=new JProgressBar(progress.getModel());taskProgress.setStringPainted(true);
  JButton taskStop=new JButton("중지"),taskRepeater=new JButton("선택 요청 → Repeater"),taskExport=new JButton("CSV 내보내기"),hide=new JButton("창 닫기 · 검사 계속");
  taskStop.setEnabled(stopButton.isEnabled());stopButton.addPropertyChangeListener("enabled",e->taskStop.setEnabled((Boolean)e.getNewValue()));taskStop.addActionListener(e->stopButton.doClick());
  taskRepeater.addActionListener(e->{int view=taskTable.getSelectedRow();if(view>=0){Entry entry=entries.get(taskTable.convertRowIndexToModel(view));callbacks.sendToRepeater(entry.service.getHost(),entry.service.getPort(),entry.service.getProtocol().equalsIgnoreCase("https"),entry.row.request(),"Payload Runner "+entry.row.index());}});
  taskExport.addActionListener(e->exportCSV());hide.addActionListener(e->taskWindow.setVisible(false));
  JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT));for(Component c:new Component[]{taskStop,taskRepeater,taskExport,hide,taskProgress})buttons.add(c);
  JPanel south=new JPanel(new BorderLayout());south.add(buttons,BorderLayout.NORTH);south.add(taskStatus,BorderLayout.SOUTH);
  taskWindow.add(new JLabel("이 창을 닫거나 Burp 탭을 전환해도 검사 계속 · 한 번에 한 작업, 순차 전송"),BorderLayout.NORTH);taskWindow.add(split,BorderLayout.CENTER);taskWindow.add(south,BorderLayout.SOUTH);
  WorkbenchUi.style(taskWindow.getContentPane());WorkbenchUi.status(taskStatus);WorkbenchUi.packetLayout(taskWindow.getContentPane(),packetHorizontal());taskWindow.setSize(1100,720);taskWindow.setLocationRelativeTo(panel);taskWindow.setVisible(true);
 }
 void resetPayloads(){payloads.setText(String.join("\n",Engine.defaults((Engine.Profile)profile.getSelectedItem())));profileHint.setText(Engine.hint((Engine.Profile)profile.getSelectedItem()));if(profile.getSelectedItem()==Engine.Profile.UPLOAD_NAME||profile.getSelectedItem()==Engine.Profile.UPLOAD_BODY||profile.getSelectedItem()==Engine.Profile.XXE||profile.getSelectedItem()==Engine.Profile.NOSQL)encoding.setSelectedItem(Engine.Encoding.RAW);else encoding.setSelectedItem(Engine.Encoding.URL);}
 String decodeOriginal(String s){try{return encoding.getSelectedItem()==Engine.Encoding.URL?URLDecoder.decode(s,StandardCharsets.UTF_8):new String(s.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8);}catch(Exception e){return s;}}
 Entry selected(){int i=table.getSelectedRow();if(i<0||i>=table.getRowCount())return null;int row=table.convertRowIndexToModel(i);return row>=0&&row<entries.size()?entries.get(row):null;}
 void message(String s){JOptionPane.showMessageDialog(panel,s,"입력값 보안 검사",JOptionPane.INFORMATION_MESSAGE);}
 void setBusy(boolean b){running=b;settingsButton.setEnabled(!b);for(JComponent c:new JComponent[]{start,mark,reset,clear,host,scheme,port,profile,encoding,base,payloads,request,interval,budget,proof,scope,mode,uploadReadback,callbackMode,callbackPort,responseTimeout,sessionControl,identityPath,identityField,identityValue})c.setEnabled(!b);stopButton.setEnabled(b);refreshEvidenceAction();}
 void startScan(){if(running||closed)return;try{if(transport!=null&&transport.busy())throw new IllegalArgumentException("이전 Legacy 요청이 실제로 종료된 뒤 다시 시작하세요.");
  List<String> lines=Arrays.stream(payloads.getText().split("\\r?\\n",-1)).filter(s->!s.isBlank()).toList();
  var config=new Engine.Config((Engine.Profile)profile.getSelectedItem(),(Engine.Encoding)encoding.getSelectedItem(),request.getText(),base.getText(),lines,(Integer)interval.getValue(),(Integer)budget.getValue(),proof.getText());
  PayloadSessionCheck.Config identityCheck=sessionControl.isSelected()?new PayloadSessionCheck.Config(identityPath.getText().strip(),identityField.getText().strip(),identityValue.getText()):null;
  String hostname=host.getText().strip();if(hostname.isBlank()||hostname.contains("/")||hostname.contains(" "))throw new IllegalArgumentException("대상 호스트를 확인하세요");
  IHttpService service=callbacks.getHelpers().buildHttpService(hostname,(Integer)port.getValue(),scheme.getSelectedItem().equals("https"));
  byte[] original=Engine.render(config.template(),config.baseline(),config.encoding());
  var url=callbacks.getHelpers().analyzeRequest(service,original).getUrl();if(scope.isSelected()&&!inScope(url))throw new IllegalArgumentException("대상이 SCOPE 밖입니다. 트래픽 우클릭 → SCOPE 지정으로 주소를 추가하세요");
  entries.clear();model.setRowCount(0);detail.setText("");request.setCaretPosition(0);payloads.setCaretPosition(0);cancel.set(false);setBusy(true);workflow.setSelectedIndex(1);progress.setMaximum(lines.size());progress.setValue(0);progress.setString("0 / "+lines.size());status.setText("백그라운드 · 기준 응답 수집 중…");
  boolean adaptive=mode.getSelectedIndex()==0,readback=uploadReadback.isSelected(),localCallback=callbackMode.isSelected()&&config.profile()==Engine.Profile.SSRF,enforceScope=scope.isSelected();int listenPort=(Integer)callbackPort.getValue(),timeoutMs=((Integer)responseTimeout.getValue())*1000;
  worker.submit(()->{try{Engine.Transport transport=bytes->{if(closed||cancel.get())throw new InterruptedException("중지");if(enforceScope&&!inScope(callbacks.getHelpers().analyzeRequest(service,bytes).getUrl()))throw new SecurityException("후속 요청이 SCOPE 밖입니다");return this.transport.send(service,bytes,timeoutMs,cancel);};
   java.util.function.Consumer<Engine.Row> receive=row->{if(!closed)SwingUtilities.invokeLater(()->{entries.add(new Entry(row,service));model.addRow(new Object[]{row.index(),row.payload(),row.response()==null?"—":row.response().status(),row.response()==null?0:row.response().wireLength(),row.millis(),row.verdict(),row.evidence()});progress.setValue(Math.min(lines.size(),Math.max(0,row.index())));progress.setString(adaptive?"적응형 · 관찰 "+entries.size():Math.max(0,row.index())+" / "+lines.size());status.setText("백그라운드 · "+row.verdict());});};
   String finished;
   if(identityCheck!=null){if(localCallback)collector=new CallbackProbe(listenPort);var summary=PayloadSessionCheck.run(config,transport,cancel,receive,identityCheck,adaptive,new Adaptive.Options(readback,collector,3000));finished=(summary.stopped()?"중지":summary.budgetReached()?"요청 한도 도달":"완료")+" · 신원 대조 포함 실제 요청 "+summary.requests()+"회 · "+summary.reason();}
   else if(adaptive){if(localCallback)collector=new CallbackProbe(listenPort);var summary=Adaptive.run(config,transport,cancel,receive,new Adaptive.Options(readback,collector,3000));finished=(summary.stopped()?"중지":summary.budgetReached()?"요청 한도 도달":"완료")+" · 실제 요청 "+summary.requests()+"회 · 목록 생략/중복 "+summary.skipped()+"개 · "+summary.reason();}
   else{var summary=Engine.run(config,transport,cancel,receive);finished=(summary.stopped()?"중지":summary.budgetReached()?"요청 한도 도달":"완료")+" · 총 "+summary.requests()+"회 / 페이로드 "+summary.completed()+"개 · "+summary.reason();}
   if(!closed)SwingUtilities.invokeLater(()->{status.setText(finished);progress.setString("완료/중지");});}catch(Exception ex){kr.workbench.Diagnostics.failure(panel,"입력값 보안 검사",ex);if(!closed)SwingUtilities.invokeLater(()->status.setText("검사 중단: "+ex.getMessage()));}finally{CallbackProbe active=collector;collector=null;if(active!=null)active.close();if(!closed)SwingUtilities.invokeLater(()->setBusy(false));}});
 }catch(Exception e){kr.workbench.Diagnostics.failure(panel,"입력값 보안 검사",e);message(e.getMessage());}}
 public static String csvCell(String s){s=Objects.toString(s,"");if(!s.isEmpty()&&"=+-@\t\r".indexOf(s.charAt(0))>=0)s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
 void exportCSV(){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("payload-runner-results.csv"));if(chooser.showSaveDialog(panel)!=JFileChooser.APPROVE_OPTION)return;try{var s=new StringBuilder("\ufeffindex,payload,status,bytes,millis,verdict,evidence,browser_execution\r\n");for(Entry e:entries){var r=e.row;s.append(r.index()).append(',').append(csvCell(r.payload())).append(',').append(r.response()==null?"":r.response().status()).append(',').append(r.response()==null?0:r.response().wireLength()).append(',').append(r.millis()).append(',').append(csvCell(r.verdict())).append(',').append(csvCell(r.evidence())).append(',').append(csvCell(r.browserExecutionStatus())).append("\r\n");}Files.writeString(chooser.getSelectedFile().toPath(),s,StandardCharsets.UTF_8);status.setText("CSV 저장 완료");}catch(Exception e){message("CSV 저장 실패: "+e.getMessage());}}
 @Override public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation){var selected=invocation.getSelectedMessages();if(selected==null||selected.length==0||selected[0].getRequest()==null)return null;byte[] raw=selected[0].getRequest().clone();IHttpService service=selected[0].getHttpService();int[] selectedBounds=invocation.getSelectionBounds();int[] bounds=selectedBounds==null?null:selectedBounds.clone();boolean isRequest=invocation.getInvocationContext()==IContextMenuInvocation.CONTEXT_MESSAGE_EDITOR_REQUEST||invocation.getInvocationContext()==IContextMenuInvocation.CONTEXT_MESSAGE_VIEWER_REQUEST;
  JMenuItem item=new JMenuItem("Workbench → 입력값 검사에 요청 연결"),marked=new JMenuItem("Workbench → 선택 값을 검사 위치로 지정");item.addActionListener(e->importRequest(service,raw,null));marked.setEnabled(isRequest&&bounds!=null&&bounds.length==2&&bounds[1]>bounds[0]&&bounds[0]>=0&&bounds[1]<=raw.length);marked.addActionListener(e->importRequest(service,raw,bounds));return List.of(item,marked);}
 public void importRequest(IHttpService service,byte[] raw,int[] bounds){SwingUtilities.invokeLater(()->{if(closed)return;if(running){status.setText("검사 계속 진행 중 · 새 요청 가져오기는 완료 또는 Stop 후 가능");return;}String s=new String(raw,StandardCharsets.ISO_8859_1);host.setText(service.getHost());port.setValue(service.getPort());scheme.setSelectedItem(service.getProtocol().toLowerCase(Locale.ROOT));if(bounds!=null){base.setText(decodeOriginal(s.substring(bounds[0],bounds[1])));s=s.substring(0,bounds[0])+Engine.MARK+s.substring(bounds[1]);}request.setText(s);request.setCaretPosition(0);workflow.setSelectedIndex(0);if(requestPreview.isSelected())requestPreview.doClick();status.setText("요청을 가져왔습니다. 검사 위치·기준값·유형을 확인한 뒤 직접 검사를 시작하세요.");onImported.run();});}
 public Component getUiComponent(){return panel;}
 @Override public void extensionUnloaded(){closed=true;if(transport!=null)transport.close();cancel.set(true);CallbackProbe active=collector;if(active!=null)active.close();worker.shutdownNow();SwingUtilities.invokeLater(()->{refreshEvidenceAction();if(taskWindow!=null)taskWindow.dispose();});}
}
