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
 final JButton background=new JButton("작업 창 열기"),settingsButton=new JButton("검사 설정"),moreButton=new JButton("더보기"),helpButton=new JButton("유형 설명");final JToggleButton requestPreview=new JToggleButton("정렬 미리보기");
 final JProgressBar progress=new JProgressBar();
 JDialog taskWindow;JTable taskTable;JTextArea taskDetail;
 final JLabel profileHint=new JLabel(),configSummary=new JLabel();
 final JLabel status=new JLabel("요청을 우클릭해 가져오거나 raw 요청을 붙여 넣으세요.");
 final DefaultTableModel model=new DefaultTableModel(new String[]{"#","페이로드","상태","bytes","ms","판정","근거"},0){public boolean isCellEditable(int r,int c){return false;}};
 final JTable table=new JTable(model);
 record Entry(Engine.Row row,IHttpService service){}
 final List<Entry> entries=new ArrayList<>();final AtomicBoolean cancel=new AtomicBoolean();volatile boolean running,closed;
 final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"PayloadRunner-KR");t.setDaemon(true);return t;});
 private Runnable onImported=()->{};
 private java.util.function.BiConsumer<IHttpService,List<Engine.Row>> evidenceSink;
 public void setEvidenceSink(java.util.function.BiConsumer<IHttpService,List<Engine.Row>> sink){evidenceSink=sink;}
 public void captureEvidence(){try{if(running)throw new IllegalArgumentException("검사 종료 후 증거를 추가하세요.");Entry selected=selected();if(selected==null)throw new IllegalArgumentException("검사 결과를 선택하세요.");if(evidenceSink==null)throw new IllegalArgumentException("증거 연결 준비 중");if(entries.size()>32)throw new IllegalArgumentException("한 검사 증거는 최대 32개 · 요청 한도를 줄여 새 검사하거나 CSV로 보완하세요.");evidenceSink.accept(selected.service,entries.stream().map(Entry::row).toList());status.setText("기준·공격·대조 결과 → 재현·증거 정리의 증거 묶음");}catch(Exception ex){status.setText(ex.getMessage());}}

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
  settingsButton.addActionListener(e->{if(running)return;kr.workbench.WorkbenchUi.settingsWindow(panel,"입력값 보안 검사 · 검사 설정",inspectionSettings(),true,null);});
  helpButton.addActionListener(e->JOptionPane.showMessageDialog(panel,Engine.hint((Engine.Profile)profile.getSelectedItem()),"검사 유형 설명",JOptionPane.INFORMATION_MESSAGE));
  JPopupMenu moreMenu=new JPopupMenu();JMenuItem evidenceItem=new JMenuItem("검사 결과 → 증거 묶음");evidenceItem.addActionListener(e->captureEvidence());moreMenu.add(evidenceItem);for(JButton action:new JButton[]{background,export,clear}){JMenuItem item=new JMenuItem(action.getText());item.addActionListener(e->action.doClick());moreMenu.add(item);}moreButton.addActionListener(e->moreMenu.show(moreButton,0,moreButton.getHeight()));
  Font mono=new Font(Font.MONOSPACED,Font.PLAIN,12);request.setFont(mono);payloads.setFont(mono);detail.setFont(mono);detail.setEditable(false);detail.setLineWrap(true);detail.setWrapStyleWord(true);
  request.putClientProperty("wb.packet.syntax",true);JPanel req=new JPanel(new BorderLayout());req.add(new JLabel("요청 편집 · §PAYLOAD§ 삽입 위치"),BorderLayout.NORTH);CardLayout requestCards=new CardLayout();JPanel requestBody=new JPanel(requestCards);PacketView requestReadOnly=new PacketView();requestBody.add(new JScrollPane(request),"edit");requestBody.add(requestReadOnly,"preview");requestPreview.addActionListener(e->{if(requestPreview.isSelected()){requestReadOnly.setPacket(request.getText(),PacketFormat.decode(request.getText().getBytes(StandardCharsets.ISO_8859_1)));requestCards.show(requestBody,"preview");}else requestCards.show(requestBody,"edit");});req.add(requestBody,BorderLayout.CENTER);req.add(WorkbenchUi.row(mark,requestPreview),BorderLayout.SOUTH);
  JPanel pay=new JPanel(new BorderLayout());pay.add(WorkbenchUi.hint("검사 후보 · 응답에 따라 선택 / 생략"),BorderLayout.NORTH);pay.add(new JScrollPane(payloads),BorderLayout.CENTER);pay.add(WorkbenchUi.row(reset),BorderLayout.SOUTH);
  JTabbedPane editors=new JTabbedPane();editors.addTab("요청 준비",req);editors.addTab("검사 후보",pay);editors.setMinimumSize(new Dimension(80,60));
  table.setAutoCreateRowSorter(true);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);int[] widths={45,270,65,80,70,160,560};for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);table.moveColumn(5,1);table.moveColumn(3,2);mode.setToolTipText("적응형은 기준·탐색 응답을 분석해 후보 순서 조정, 대조·반복 요청, 조기 종료를 수행합니다. 전체 목록은 후보를 순서대로 전송합니다. 규칙 기반이며 AI 모델은 사용하지 않습니다.");
  JSplitPane results=WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT,editors,WorkbenchUi.card("진단 결과 · 후보와 근거",new JScrollPane(table)),.50);results.setName("wb.payload.lists");
  JSplitPane content=WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,results,detailPane(detail),.45);content.setName("wb.payload.workspace");panel.add(content,BorderLayout.CENTER);
  start.setText("입력값 검사 시작");start.setToolTipText("설정한 입력 위치에 시험 문자열을 넣어 실제 요청을 보냅니다. 기준·대조 요청도 총 요청 한도에 포함됩니다.");stopButton.setText("중지");JPanel controls=WorkbenchUi.row(start,stopButton,repeater,settingsButton,moreButton);progress.setStringPainted(true);progress.setPreferredSize(new Dimension(180,24));progress.setString("대기");controls.add(progress);top.add(controls);top.add(WorkbenchUi.row(configSummary,status));panel.add(WorkbenchUi.header(FeatureGuide.title("payload"),"시험 문자열을 넣을 위치·유형 확인 → 직접 검사 시작",top),BorderLayout.NORTH);
  profile.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){super.getListCellRendererComponent(list,value,index,selected,focus);setText(Engine.label((Engine.Profile)value));return this;}});WorkbenchUi.primary(start);stopButton.setEnabled(false);resetPayloads();
  profile.addActionListener(e->{resetPayloads();updateSummary();});scheme.addActionListener(e->updateSummary());mode.addActionListener(e->updateSummary());encoding.addActionListener(e->updateSummary());port.addChangeListener(e->updateSummary());host.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){updateSummary();}public void removeUpdate(javax.swing.event.DocumentEvent e){updateSummary();}public void changedUpdate(javax.swing.event.DocumentEvent e){updateSummary();}});updateSummary();reset.addActionListener(e->resetPayloads());mark.addActionListener(e->{if(running)return;int a=request.getSelectionStart(),b=request.getSelectionEnd();if(a==b){message("요청에서 교체할 파라미터 값·파일명·파일 내용을 선택하세요");return;}base.setText(decodeOriginal(request.getText().substring(a,b)));request.replaceRange(Engine.MARK,a,b);});
  start.addActionListener(e->startScan());stopButton.addActionListener(e->{cancel.set(true);status.setText("후속 요청 중지 · 이미 보낸 요청은 응답/최대 "+responseTimeout.getValue()+"초 대기 후 종료");});
  clear.addActionListener(e->{if(!running){entries.clear();model.setRowCount(0);detail.setText("");}});
  repeater.addActionListener(e->{Entry entry=selected();if(entry!=null)callbacks.sendToRepeater(entry.service.getHost(),entry.service.getPort(),entry.service.getProtocol().equalsIgnoreCase("https"),entry.row.request(),"Payload Runner "+entry.row.index());});
  table.getSelectionModel().addListSelectionListener(e->{Entry entry=selected();if(entry!=null){var r=entry.row;detail.setText("판정: "+r.verdict()+"\n관찰 / 요청 선정 근거: "+r.evidence()+"\n\nREQUEST (byte view)\n"+new String(r.request(),StandardCharsets.ISO_8859_1)+"\n\nRESPONSE\n"+(r.response()==null?"응답 없음":"HTTP "+r.response().status()+" · "+r.response().mime()+" · "+r.response().wireLength()+" bytes\n"+r.response().text()+(r.response().truncated()?"\n[본문/보관 한도로 일부 생략]":"")));detail.setCaretPosition(0);}});
  background.addActionListener(e->openTaskWindow());
  export.addActionListener(e->exportCSV());callbacks.customizeUiComponent(panel);WorkbenchUi.primary(start);WorkbenchUi.style(panel);WorkbenchUi.status(status);
 }
 JPanel inspectionSettings(){JPanel options=new JPanel();options.setName("wb.payload.inspection.settings");options.setLayout(new BoxLayout(options,BoxLayout.Y_AXIS));options.add(WorkbenchUi.group("대상",scheme,host,new JLabel("포트"),port,scope));options.add(WorkbenchUi.group("검사 유형",profile,new JLabel("인코딩"),encoding,new JLabel("기준값"),base,helpButton));options.add(WorkbenchUi.group("실행",mode,new JLabel("간격(ms)"),interval,new JLabel("총 요청 한도"),budget));options.add(WorkbenchUi.row(new JLabel("응답 대기시간(초)"),responseTimeout,new JLabel("두 모드 공통 · 401/403/429/503 후속 요청 중단")));options.add(WorkbenchUi.row(new JLabel("기대 응답 문자열"),proof));options.add(WorkbenchUi.row(sessionControl));options.add(WorkbenchUi.group("신원 대조",identityPath,new JLabel("JSON 경로"),identityField,new JLabel("기대 사용자"),identityValue));options.add(WorkbenchUi.row(uploadReadback));options.add(WorkbenchUi.row(callbackMode,new JLabel("콜백 포트"),callbackPort));WorkbenchUi.style(options);return options;}
 void updateSummary(){String full=scheme.getSelectedItem()+"://"+host.getText()+":"+port.getValue()+" · "+Engine.label((Engine.Profile)profile.getSelectedItem())+" · "+mode.getSelectedItem();String target=host.getText();if(target.length()>35)target=target.substring(0,35)+"…";configSummary.setText(Engine.label((Engine.Profile)profile.getSelectedItem())+" · "+(mode.getSelectedIndex()==0?"적응형":"순차 전송")+" · "+target+":"+port.getValue());configSummary.setToolTipText(full);}
 JPanel detailPane(JTextArea source){
  PacketView req=new PacketView(),res=new PacketView();JTextArea evidence=new JTextArea(2,70);evidence.putClientProperty("wb.prose",true);evidence.setEditable(false);evidence.setLineWrap(true);evidence.setWrapStyleWord(true);
  Runnable update=()->{Entry entry=source==detail?selected():selectedTask();if(entry==null||source.getText().isEmpty()){evidence.setText("결과 행을 선택하세요.");req.setPacket(null);res.setPacket(null);return;}var r=entry.row;evidence.setText("판정: "+r.verdict()+"\n관찰 / 요청 선정 근거: "+r.evidence());req.setPacket(PacketFormat.decode(r.request()));if(r.response()==null)res.setPacket("응답 없음");else{var response=r.response();res.setPacket(PacketFormat.decode(response.raw()),PacketFormat.response(response.raw(),response.status(),response.mime(),response.text())+(response.truncated()?"\n[일부 생략]":""));}};
  source.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){update.run();}public void removeUpdate(javax.swing.event.DocumentEvent e){update.run();}public void changedUpdate(javax.swing.event.DocumentEvent e){update.run();}});update.run();
  JPanel pane=new JPanel(new BorderLayout(0,5));pane.add(new JScrollPane(evidence),BorderLayout.NORTH);pane.add(WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT,WorkbenchUi.card("전송 요청 REQUEST",req),WorkbenchUi.card("응답 RESPONSE",res),.5));return pane;
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
 Entry selected(){int i=table.getSelectedRow();if(i<0)return null;int row=table.convertRowIndexToModel(i);return row<entries.size()?entries.get(row):null;}
 void message(String s){JOptionPane.showMessageDialog(panel,s,"입력값 보안 검사",JOptionPane.INFORMATION_MESSAGE);}
 void setBusy(boolean b){running=b;settingsButton.setEnabled(!b);for(JComponent c:new JComponent[]{start,mark,reset,clear,host,scheme,port,profile,encoding,base,payloads,request,interval,budget,proof,scope,mode,uploadReadback,callbackMode,callbackPort,responseTimeout,sessionControl,identityPath,identityField,identityValue})c.setEnabled(!b);stopButton.setEnabled(b);}
 void startScan(){if(running||closed)return;try{if(transport!=null&&transport.busy())throw new IllegalArgumentException("이전 Legacy 요청이 실제로 종료된 뒤 다시 시작하세요.");
  List<String> lines=Arrays.stream(payloads.getText().split("\\r?\\n",-1)).filter(s->!s.isBlank()).toList();
  var config=new Engine.Config((Engine.Profile)profile.getSelectedItem(),(Engine.Encoding)encoding.getSelectedItem(),request.getText(),base.getText(),lines,(Integer)interval.getValue(),(Integer)budget.getValue(),proof.getText());
  PayloadSessionCheck.Config identityCheck=sessionControl.isSelected()?new PayloadSessionCheck.Config(identityPath.getText().strip(),identityField.getText().strip(),identityValue.getText()):null;
  String hostname=host.getText().strip();if(hostname.isBlank()||hostname.contains("/")||hostname.contains(" "))throw new IllegalArgumentException("대상 호스트를 확인하세요");
  IHttpService service=callbacks.getHelpers().buildHttpService(hostname,(Integer)port.getValue(),scheme.getSelectedItem().equals("https"));
  byte[] original=Engine.render(config.template(),config.baseline(),config.encoding());
  var url=callbacks.getHelpers().analyzeRequest(service,original).getUrl();if(scope.isSelected()&&!inScope(url))throw new IllegalArgumentException("대상이 SCOPE 밖입니다. 트래픽 우클릭 → SCOPE 지정으로 주소를 추가하세요");
  entries.clear();model.setRowCount(0);detail.setText("");request.setCaretPosition(0);payloads.setCaretPosition(0);cancel.set(false);setBusy(true);progress.setMaximum(lines.size());progress.setValue(0);progress.setString("0 / "+lines.size());status.setText("백그라운드 · 기준 응답 수집 중…");
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
 public void importRequest(IHttpService service,byte[] raw,int[] bounds){SwingUtilities.invokeLater(()->{if(closed)return;if(running){status.setText("검사 계속 진행 중 · 새 요청 가져오기는 완료 또는 Stop 후 가능");return;}String s=new String(raw,StandardCharsets.ISO_8859_1);host.setText(service.getHost());port.setValue(service.getPort());scheme.setSelectedItem(service.getProtocol().toLowerCase(Locale.ROOT));if(bounds!=null){base.setText(decodeOriginal(s.substring(bounds[0],bounds[1])));s=s.substring(0,bounds[0])+Engine.MARK+s.substring(bounds[1]);}request.setText(s);request.setCaretPosition(0);status.setText("요청을 가져왔습니다. 삽입 위치·기준값·유형 확인 후 Start");onImported.run();});}
 public Component getUiComponent(){return panel;}
 @Override public void extensionUnloaded(){closed=true;if(transport!=null)transport.close();cancel.set(true);CallbackProbe active=collector;if(active!=null)active.close();worker.shutdownNow();SwingUtilities.invokeLater(()->{if(taskWindow!=null)taskWindow.dispose();});}
}
