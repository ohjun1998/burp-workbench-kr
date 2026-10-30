package kr.workbench;
import burp.api.montoya.MontoyaApi;import burp.api.montoya.http.message.HttpRequestResponse;import java.nio.file.*;import java.util.function.*;import burp.api.montoya.http.message.requests.HttpRequest;import burp.api.montoya.http.message.responses.HttpResponse;
import javax.swing.*;import javax.swing.table.DefaultTableModel;import java.awt.*;import java.util.*;import java.util.List;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
/** Scope-only response analysis; all work is passive and bounded. Repeater is explicit preparation only. */
final class ResponseReconPanel extends JPanel implements AutoCloseable {
 final MontoyaApi api;final ObservationTableModel findings=model("규칙","URL (쿼리 제외)","근거 위치","관찰 근거","관측 횟수"),documents=model("메서드","경로 템플릿","쿼리 이름","본문 이름","관찰 결과"),flowModel=model("마커","쓰기 URL","조회 URL","쓰기 응답","조회 재출력","HTML 출력","브라우저 실행","HTTP");
 final PacketInspector findingResponse=new PacketInspector("wb.response.finding.response"),findingRequest=new PacketInspector("wb.response.finding.request");
 final JTextArea findingUrls=new JTextArea();
 final JTable findingTable=new AuthLayout.Table(findings),apiTable=new JTable(documents),flowTable=new JTable(flowModel);final JLabel status=new JLabel("SCOPE 내 응답 자동 관찰 · 취약점 확정/추가 요청 없음");
 final StoredFlows flows=new StoredFlows();final LinkedHashMap<String,PassiveFindings.Finding> issues=new LinkedHashMap<>();final Map<String,Integer> counts=new HashMap<>();final Map<String,Set<String>> affected=new LinkedHashMap<>();volatile Map<String,Set<String>> displayedAffected=Map.of();final JTextArea findingDetail=new JTextArea(7,70);final LinkedHashMap<String,ApiInventory.Endpoint> inventory=new LinkedHashMap<>();final LinkedHashMap<String,Set<String>> observed=new LinkedHashMap<>();final Map<String,Set<String>> observedTemplates=new HashMap<>();
 final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(50),r->{Thread t=new Thread(r,"Workbench-response-recon");t.setDaemon(true);return t;});final AtomicLong generation=new AtomicLong();final AtomicBoolean closed=new AtomicBoolean(),counterUpdatePending=new AtomicBoolean();final AtomicInteger dropped=new AtomicInteger(),oversized=new AtomicInteger(),queueRejected=new AtomicInteger(),analysisErrors=new AtomicInteger();
 final JTextField marker=new JTextField("WB_STORE_DEMO01",24),resolved=new JTextField(42);final TargetWindow markerWindow,draftWindow;ApiInventory.Endpoint drafting;volatile Map<String,ApiInventory.Endpoint> displayedInventory=Map.of();
 final AnalysisRetries retries=new AnalysisRetries();
 final ResponseAnalysisBudget analysisBudget=new ResponseAnalysisBudget();
 final ResponseAnalysisQueue analysisQueue=new ResponseAnalysisQueue(worker);
 final LinkedHashSet<String> countedOriginals=new LinkedHashSet<>();
 final Object packetLock=new Object();
 final LinkedHashMap<String,HttpRequestResponse> findingPackets=new LinkedHashMap<>(),flowPackets=new LinkedHashMap<>();
 volatile Map<String,HttpRequestResponse> displayedFindingPackets=Map.of(),displayedFlowPackets=Map.of();
 volatile List<String> displayedFindingKeys=List.of();volatile List<StoredFlows.Flow> displayedFlows=List.of();
 final LinkedHashMap<String,Path> browserImages=new LinkedHashMap<>();final JTextArea flowDetail=new JTextArea(9,70);
 BiConsumer<List<HttpRequestResponse>,String> evidenceSink=(packets,note)->{throw new IllegalArgumentException("증거 연결이 준비되지 않았습니다.");};
 @FunctionalInterface interface StoredEvidenceSink{void accept(List<HttpRequestResponse> packets,String note,Path image,String expectedHash);}
 StoredEvidenceSink storedEvidenceSink=(packets,note,image,sha)->{throw new IllegalArgumentException("브라우저 증거 연결이 준비되지 않았습니다.");};
 final JButton findingCapture=action("선택 관찰 → 증거",this::captureFinding),flowCapture=action("선택 흐름 → 증거",this::captureStored),flowBrowser=action("브라우저 실행 확인",this::chooseBrowserEvidence),flowClear=action("실행 근거 해제",()->{var f=selectedFlow();flows.clearBrowserEvidence(f.id());synchronized(browserImages){browserImages.remove(f.id());}submit(()->refresh(generation.get()));}),apiDraft=new JButton("Repeater 초안");
 final AtomicReference<ViewSnapshot> pendingView=new AtomicReference<>();
 final AtomicBoolean viewUpdatePending=new AtomicBoolean();
 final RuntimeMetrics.Window viewLatency=new RuntimeMetrics.Window();
 static final class ObservationTableModel extends DefaultTableModel {
  ObservationTableModel(String... names){super(names,0);}
  @Override public boolean isCellEditable(int row,int column){return false;}
  void replaceRows(List<Object[]> rows){
   dataVector.clear();
   for(Object[] row:rows)dataVector.add(convertToVector(row));
   fireTableDataChanged();
  }
 }
 private static ObservationTableModel model(String... names){return new ObservationTableModel(names);}
 record ViewSnapshot(long token,List<Object[]> findings,List<Object[]> apis,List<Object[]> flowRows,
   Map<String,ApiInventory.Endpoint> inventory,Map<String,Set<String>> affected,
   Map<String,HttpRequestResponse> findingPackets,Map<String,HttpRequestResponse> flowPackets,
   List<String> findingKeys,List<StoredFlows.Flow> flows){}
 ResponseReconPanel(MontoyaApi api){super(new BorderLayout(8,8));this.api=api;setName("wb.response.recon");setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
  markerWindow=new TargetWindow(this,"저장/조회 · 고유 마커 등록",()->{JButton register=new JButton("마커 등록");register.addActionListener(e->{try{flows.register(marker.getText().strip());status.setText("마커 등록 · 실제 쓰기/조회 요청을 관찰합니다. 요청을 자동 전송하지 않습니다.");}catch(Exception ex){Diagnostics.failure(ResponseReconPanel.this,"응답 보안 분석",ex);status.setText(ex.getMessage());}});return TargetWindow.options(WorkbenchUi.row(new JLabel("고유 마커"),marker),new JLabel("WB_ / PR_ 접두어 · 최대 20개 · 원문 페이로드/인증값은 저장하지 않습니다."),WorkbenchUi.row(register));});
  draftWindow=new TargetWindow(this,"OpenAPI 초안 · 경로 변수 확정",()->{JButton send=new JButton("Repeater 초안 준비");send.addActionListener(e->{try{if(drafting==null)throw new IllegalArgumentException("API를 선택하세요.");if(drafting.contentType().equals("multipart/form-data"))throw new IllegalArgumentException("Multipart는 실제 트래픽을 가져와 파일 본문을 확인하세요.");var d=ApiInventory.draft(drafting,resolved.getText());HttpRequest q=HttpRequest.httpRequestFromUrl(d.url()).withMethod(d.method());if(!d.body().isEmpty())q=q.withHeader("Content-Type",drafting.contentType().isEmpty()?"application/json":drafting.contentType()).withBody(d.body());if(!WorkbenchScope.contains(api,q))throw new IllegalArgumentException("Scope 제외 · "+WorkbenchScope.reason(api,q));api.repeater().sendToRepeater(q,"OpenAPI 검토");status.setText("Repeater 초안 준비 · 인증/값/문서와 실제 메서드를 확인하세요.");}catch(Exception ex){Diagnostics.failure(ResponseReconPanel.this,"응답 보안 분석",ex);status.setText(ex.getMessage());}});return TargetWindow.options(WorkbenchUi.row(new JLabel("확정할 URL"),resolved),new JLabel("같은 Origin · 쿼리값/인증을 복사하지 않음 · 준비는 전송/완료 처리 아님"),WorkbenchUi.row(send));});
  JButton clear=new JButton("관찰 초기화");apiDraft.addActionListener(e->{int row=apiTable.getSelectedRow();if(row<0){status.setText("API 항목을 선택하세요.");return;}String key=documents.getValueAt(apiTable.convertRowIndexToModel(row),0)+" "+documents.getValueAt(apiTable.convertRowIndexToModel(row),1);drafting=displayedInventory.get(key);if(drafting==null)return;resolved.setText(drafting.url());draftWindow.closeWindow();draftWindow.open();});clear.addActionListener(e->{generation.incrementAndGet();analysisQueue.cancelQueued();worker.getQueue().clear();retries.clear();submit(()->{issues.clear();counts.clear();countedOriginals.clear();affected.clear();clearPackets();synchronized(browserImages){browserImages.clear();}inventory.clear();observed.clear();observedTemplates.clear();flows.clear();refresh(generation.get());});});
  clear.setToolTipText("현재 Passive/API/저장·조회 관측과 마커를 모두 비웁니다. 원본 트래픽은 유지합니다.");JTabbedPane tabs=new JTabbedPane();tabs.setName("wb.response.pages");findingDetail.setEditable(false);findingDetail.setLineWrap(true);findingDetail.setWrapStyleWord(true);findingTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()){showFinding();refreshActions();}});JPanel passivePage=new JPanel(new BorderLayout(4,4));findingCapture.setName("wb.response.finding.evidence");WorkbenchUi.primary(findingCapture);JButton more=new JButton("더보기");JPopupMenu maintenance=new JPopupMenu();JMenuItem clearItem=new JMenuItem("관찰 초기화");clearItem.addActionListener(e->clear.doClick());maintenance.add(clearItem);more.addActionListener(e->maintenance.show(more,0,more.getHeight()));
  passivePage.add(CompactWorkbenchUi.toolbar(CompactWorkbenchUi.search(findingTable,"wb.response.search","분류·URL·관찰 내용 검색"),WorkbenchUi.row(findingCapture,more)),BorderLayout.NORTH);
  JTabbedPane detailTabs=new JTabbedPane();findingUrls.setEditable(false);findingUrls.putClientProperty("wb.prose",true);
  JSplitPane detailSplit=WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,ReferenceUi.facts(findingDetail),WorkbenchUi.card("응답 본문 (Response)",findingResponse),.35);detailTabs.addTab("근거 상세",detailSplit);detailTabs.addTab("관련 요청",findingRequest);detailTabs.addTab("영향 URL",new JScrollPane(findingUrls));
  detailSplit.setName("wb.response.finding.detail");
  JSplitPane findingSplit=WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT,new JScrollPane(findingTable),detailTabs,.34);
  findingSplit.setName("wb.response.finding.split");passivePage.add(findingSplit);tabs.addTab("응답 관찰",passivePage);JPanel apiPage=new JPanel(new BorderLayout(4,4));apiDraft.setName("wb.response.api.draft");apiDraft.setToolTipText("선택 API의 URL 변수를 확인한 뒤 Repeater에 초안을 준비합니다. 서버로 전송하지 않습니다.");WorkbenchUi.primary(apiDraft);apiPage.add(WorkbenchUi.row(apiDraft,WorkbenchUi.hint("API 선택 → 경로 변수 확인 → 초안 준비")),BorderLayout.NORTH);apiPage.add(new JScrollPane(apiTable));apiTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())refreshActions();});tabs.addTab("API 문서",apiPage);tabs.addTab("저장/조회 연결",storedContent());tabs.addTab("분석 처리·재시도",retries.content(this::retryOriginal));add(tabs);add(status,BorderLayout.SOUTH);WorkbenchUi.feedback(status);int[][] widths={{190,290,140,270,80},{90,300,180,180,230},{150,230,230,150,160,130,160,70}};int t=0;for(JTable table:List.of(findingTable,apiTable,flowTable)){table.setAutoCreateRowSorter(true);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);for(int i=0;i<table.getColumnCount();i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[t][i]);t++;}CompactWorkbenchUi.columns(findingTable,140,340,110,320,70);refreshActions();WorkbenchUi.style(this);

 }

 void showFinding(){
  int view=findingTable.getSelectedRow();
  if(view<0){findingDetail.setText("");findingUrls.setText("");AuthComparePanel.loadInspector(findingRequest,null,null);AuthComparePanel.loadInspector(findingResponse,null,null);return;}
  int row=findingTable.convertRowIndexToModel(view);
  String key=row<displayedFindingKeys.size()?displayedFindingKeys.get(row):"";
  String group=ApiInventory.origin(Objects.toString(findings.getValueAt(row,1),""))+"|"+findings.getValueAt(row,0)+"|"+findings.getValueAt(row,2)+"|"+findings.getValueAt(row,3);
  String text="분류: "+findings.getValueAt(row,0)+"\n\n위치: "+findings.getValueAt(row,2)+"\n\n관찰: "+findings.getValueAt(row,3)+"\n\n관찰 후보 · 취약점 확정 아님\n\n영향 URL (쿼리 값 제외 · 그룹별 최대 100개)\n"+String.join("\n",displayedAffected.getOrDefault(group,Set.of()));
  if(!text.equals(findingDetail.getText())){findingDetail.setText(text);findingDetail.setCaretPosition(0);}
  findingUrls.setText(String.join("\n",displayedAffected.getOrDefault(group,Set.of())));findingUrls.setCaretPosition(0);
  var packet=displayedFindingPackets.get(key);AuthComparePanel.loadInspector(findingRequest,packet==null?null:packet.request(),null);
  AuthComparePanel.loadInspector(findingResponse,null,packet==null?null:packet.response());
 }
 JButton action(String title,Runnable task){JButton button=new JButton(title);button.addActionListener(e->{try{task.run();}catch(Exception ex){Diagnostics.failure(ResponseReconPanel.this,"응답 보안 분석",ex);status.setText(ex.getMessage());}});return button;}
 JPanel storedContent(){
  JPanel page=new JPanel(new BorderLayout(4,4));JButton register=action("마커 등록",()->markerWindow.open());register.setToolTipText("저장 요청과 후속 조회를 연결할 고유 WB_ / PR_ 마커를 등록합니다. 요청은 자동 전송하지 않습니다.");flowCapture.setName("wb.response.flow.evidence");flowBrowser.setName("wb.response.flow.browser");flowClear.setName("wb.response.flow.clear");WorkbenchUi.primary(flowCapture);flowBrowser.setToolTipText("실제 브라우저에서 실행을 직접 확인한 뒤 PNG/JPEG 근거를 연결합니다. 마커 출력만으로 XSS를 확정하지 않습니다.");page.add(WorkbenchUi.row(register,flowCapture,flowBrowser,flowClear),BorderLayout.NORTH);
  flowDetail.setEditable(false);flowDetail.setLineWrap(true);flowDetail.setWrapStyleWord(true);
  flowTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);flowTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()){showFlowDetail();refreshActions();}});
  page.add(WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT,new JScrollPane(flowTable),new JScrollPane(flowDetail),.6));
  JLabel hint=WorkbenchUi.hint("마커 출력은 XSS 확정 아님 · 원본은 메모리에만 보관");hint.setToolTipText("원본 최대 100건 · 건당 128KiB · 쓰기/조회 응답을 다시 관찰하면 원본과 단계가 갱신됩니다.");page.add(hint,BorderLayout.SOUTH);return page;
 }
 void refreshActions(){int found=findingTable.getSelectedRow();int n=found<0?-1:findingTable.convertRowIndexToModel(found);findingCapture.setEnabled(n>=0&&n<displayedFindingKeys.size()&&displayedFindingPackets.containsKey(displayedFindingKeys.get(n)));apiDraft.setEnabled(apiTable.getSelectedRow()>=0);int row=flowTable.getSelectedRow();int index=row<0?-1:flowTable.convertRowIndexToModel(row);var flow=index>=0&&index<displayedFlows.size()?displayedFlows.get(index):null;flowCapture.setEnabled(flow!=null&&flow.writeSource()!=null&&displayedFlowPackets.containsKey(flow.writeSource().id())&&(flow.readSource()==null||displayedFlowPackets.containsKey(flow.readSource().id())));flowBrowser.setEnabled(flow!=null&&flow.htmlObserved()&&flow.readSource()!=null);flowClear.setEnabled(flow!=null&&!flow.browserEvidence().isEmpty());}

 void cachePacket(LinkedHashMap<String,HttpRequestResponse> cache,String key,HttpRequestResponse packet){synchronized(packetLock){if(closed.get())return;rememberPacket(cache,key,packet);}}
 Map<String,HttpRequestResponse> snapshotPackets(LinkedHashMap<String,HttpRequestResponse> cache){synchronized(packetLock){return Map.copyOf(cache);}}
 void clearPackets(){synchronized(packetLock){findingPackets.clear();flowPackets.clear();}}
 static void rememberPacket(LinkedHashMap<String,HttpRequestResponse> cache,String key,HttpRequestResponse packet){
  cache.remove(key);if(packet==null)return;if(cache.size()>=100)cache.remove(cache.keySet().iterator().next());cache.put(key,packet);
 }
 String selectedFlowId(){int row=flowTable.getSelectedRow();if(row<0)return "";int n=flowTable.convertRowIndexToModel(row);return n<displayedFlows.size()?displayedFlows.get(n).id():"";}
 StoredFlows.Flow selectedFlow(){
  String id=selectedFlowId();StoredFlows.Flow shown=displayedFlows.stream().filter(f->f.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("저장/조회 항목을 선택하세요."));
  StoredFlows.Flow current=flows.views().stream().filter(f->f.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("관측이 만료되었습니다. 다시 선택하세요."));
  if(!Objects.equals(shown.readSource(),current.readSource())||!Objects.equals(shown.writeSource(),current.writeSource()))throw new IllegalArgumentException("관측이 갱신되었습니다. 다시 선택하세요.");return current;
 }
 void showFlowDetail(){try{flowDetail.setText(StoredFlows.describe(selectedFlow()));flowDetail.setCaretPosition(0);}catch(Exception ex){flowDetail.setText(ex.getMessage());}}
 void captureFinding(){
  int row=findingTable.getSelectedRow();if(row<0)throw new IllegalArgumentException("Passive 관찰을 선택하세요.");int n=findingTable.convertRowIndexToModel(row);
  if(n>=displayedFindingKeys.size())throw new IllegalArgumentException("관찰이 갱신되었습니다. 다시 선택하세요.");
  HttpRequestResponse packet=displayedFindingPackets.get(displayedFindingKeys.get(n));if(packet==null)throw new IllegalArgumentException("원본이 크기/보관 한도로 제외되었습니다. 해당 요청을 다시 관측하세요.");
  String note="Passive 관찰 · "+findings.getValueAt(n,0)+" · "+findings.getValueAt(n,2)+" · "+findings.getValueAt(n,3)+" · 관찰 후보이며 취약점 확정 아님";
  evidenceSink.accept(EvidenceLinks.checked(api,List.of(packet)),note);status.setText("Passive 원본을 증거 묶음에 추가 · 저장 전 영향/재현 절차 확인");
 }
 void chooseBrowserEvidence(){
  var f=selectedFlow();if(!f.htmlObserved()||f.readSource()==null)throw new IllegalArgumentException("성공한 HTML 조회 행을 선택하세요.");
  if(JOptionPane.showConfirmDialog(this,"동일 마커를 실제 브라우저에서 재현하고 실행을 직접 확인했습니까?\n마커 출력/스크린샷 존재만으로 실행이 증명되지는 않습니다.\n민감정보를 가린 PNG/JPEG를 연결하세요.","브라우저 실행 수동 확인",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
  JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
  Path image=chooser.getSelectedFile().toPath();long token=generation.get();submit(()->{try{
   byte[] imageBytes=BountyEvidence.imageBytes(image,null);BountyEvidence.validateImageBytes(imageBytes);String sha=Core.hash(imageBytes);
   if(closed.get()||token!=generation.get())return;flows.recordBrowserEvidence(f.id(),f.readSource().id(),sha);
   synchronized(browserImages){if(closed.get()||token!=generation.get())return;if(browserImages.size()>=100&&!browserImages.containsKey(f.id()))browserImages.remove(browserImages.keySet().iterator().next());browserImages.put(f.id(),image);}
   refresh(token);
  }catch(Exception ex){Diagnostics.failure(ResponseReconPanel.this,"응답 보안 분석",ex);SwingUtilities.invokeLater(()->{if(!closed.get()&&token==generation.get())status.setText(ex.getMessage());});}});
 }
 void captureStored(){
  var f=selectedFlow();List<HttpRequestResponse> packets=new ArrayList<>();
  HttpRequestResponse write=displayedFlowPackets.get(f.writeSource().id());if(write==null)throw new IllegalArgumentException("쓰기 원본이 만료/크기 제외되었습니다. 다시 관측하세요.");packets.add(write);
  if(f.readSource()!=null){HttpRequestResponse read=displayedFlowPackets.get(f.readSource().id());if(read==null)throw new IllegalArgumentException("조회 원본이 만료/크기 제외되었습니다. 다시 관측하세요.");packets.add(read);}
  List<HttpRequestResponse> checked=EvidenceLinks.checked(api,packets);String note=StoredFlows.describe(f);
  if(f.browserEvidence().isEmpty()){evidenceSink.accept(checked,note);status.setText("쓰기/조회 원본을 증거 묶음에 추가 · 브라우저 실행 미검증");return;}
  Path image;synchronized(browserImages){image=browserImages.get(f.id());}if(image==null)throw new IllegalArgumentException("브라우저 이미지 연결이 없습니다. 다시 연결하세요.");long token=generation.get();
  submit(()->{try{byte[] imageBytes=BountyEvidence.imageBytes(image,f.browserEvidence());BountyEvidence.validateImageBytes(imageBytes);
   SwingUtilities.invokeLater(()->{try{if(closed.get()||token!=generation.get())return;var current=flows.views().stream().filter(v->v.id().equals(f.id())).findFirst().orElseThrow(()->new IllegalArgumentException("관측 만료"));if(!current.equals(f))throw new IllegalArgumentException("관측이 갱신되었습니다. 다시 선택하세요.");storedEvidenceSink.accept(EvidenceLinks.checked(api,checked),note,image,f.browserEvidence());status.setText("쓰기/조회 원본과 수동 브라우저 이미지 연결 · 증거 ZIP 저장 필요");}catch(Exception ex){Diagnostics.failure(ResponseReconPanel.this,"응답 보안 분석",ex);status.setText(ex.getMessage());}});
  }catch(Exception ex){Diagnostics.failure(ResponseReconPanel.this,"응답 보안 분석",ex);SwingUtilities.invokeLater(()->{if(!closed.get()&&token==generation.get())status.setText(ex.getMessage());});}});
 }
 void updateCounters(){status.setText("Passive "+findings.getRowCount()+" · API "+documents.getRowCount()+" · 흐름 "+flowModel.getRowCount()+" · 처리 제외 "+dropped.get()+" · 본문 한도 "+oversized.get()+" · 관찰/후보");status.setToolTipText("<html><body style='width:560px'>"+Core.html("수신 본문 2 MiB · 원본 복원 5 MiB · JSON 순차 구조 2 Mi 문자 · 일반 문자열 규칙 앞 1 Mi 문자 · 큐 제외 "+queueRejected.get()+" · 처리 오류 "+analysisErrors.get()+" · 누적 수치는 확장 로드 이후 · 취약점 확정 아님\n"+analysisBudget.diagnostics()+"\n화면 갱신 "+viewLatency.snapshot().summary()).replace("\n","<br>")+"</body></html>");}
 void countersChanged(){if(closed.get()||!counterUpdatePending.compareAndSet(false,true))return;SwingUtilities.invokeLater(()->{counterUpdatePending.set(false);if(!closed.get())updateCounters();});}
 void submit(Runnable task){if(closed.get())return;try{worker.execute(task);}catch(RejectedExecutionException e){dropped.incrementAndGet();queueRejected.incrementAndGet();countersChanged();}}
 void offer(HttpRequest q,HttpResponse s){offer(q,s,null);}
 void offer(HttpRequest q,HttpResponse s,DiskTrafficStore.PacketRef original){
  if(closed.get()||q==null||s==null)return;
  long token=generation.get();String retryId=null;
  try{
   if(!WorkbenchScope.contains(api,q)||s.body()==null)return;
   if(original!=null){var previous=retries.get(original.id());if(previous!=null&&(previous.state()==AnalysisRetries.State.QUEUED||previous.state()==AnalysisRetries.State.RUNNING||previous.state()==AnalysisRetries.State.COMPLETE))return;}
   retryId=retries.record(original,q.method(),q.url(),AnalysisRetries.State.QUEUED,"로컬 분석 대기");
   int bytes=original==null?s.body().length():original.responseBodyLength();
   if(bytes>ResponseAnalysisBudget.BODY_LIMIT){
    retries.state(retryId,AnalysisRetries.State.TOO_LARGE,"수신 본문 2 MiB 한도 초과 · 본문 미검사 · 원본 트래픽에서 별도 확인");
    String safe=ActivityTracker.safeUrl(q.url());if(safe==null)return;oversized.incrementAndGet();countersChanged();
    submit(()->{if(closed.get()||token!=generation.get()||!WorkbenchScope.contains(api,safe))return;String key=ApiInventory.origin(safe)+"|응답 분석 범위 제한|Body|수신 본문 한도";if(issues.size()<500||issues.containsKey(key)){issues.put(key,new PassiveFindings.Finding("응답 분석 범위 제한",safe,"Body","수신 본문 "+bytes+" bytes · 2 MiB 한도 초과 · 본문 미검사 · 원본 트래픽에서 별도 확인"));counts.merge(key,1,(a,b)->Math.min(1000000,a+b));Set<String> urls=affected.computeIfAbsent(key,k->new LinkedHashSet<>());if(urls.size()<100)urls.add(safe);}refresh(token);});return;
   }
   if(original!=null&&original.requestLength()+(long)original.responseLength()>ResponseAnalysisBudget.WIRE_LIMIT){
    oversized.incrementAndGet();retries.state(retryId,AnalysisRetries.State.TOO_LARGE,"원본 복원 5 MiB 한도 초과 · 원본 트래픽에서 별도 확인");countersChanged();return;
   }
   ResponseAnalysisInput.Prepared prepared;
   try{prepared=original==null?ResponseAnalysisInput.snapshot(q,s,analysisBudget):ResponseAnalysisInput.original(original,analysisBudget);}
   catch(ResponseAnalysisInput.TooLarge error){oversized.incrementAndGet();retries.state(retryId,AnalysisRetries.State.TOO_LARGE,error.getMessage());countersChanged();return;}
   String id=retryId;ResponseAnalysisInput input=prepared.input();
   submitAnalysis(()->analyzeInput(input,token,id),id,prepared.reservation());
  }catch(Exception error){analysisErrors.incrementAndGet();dropped.incrementAndGet();if(retryId!=null)retries.state(retryId,AnalysisRetries.State.FAILED,"원본 읽기/분석 준비 실패");countersChanged();}
 }
 boolean submitAnalysis(Runnable task,String retryId,ResponseAnalysisBudget.Reservation reservation){
  if(closed.get()){if(reservation!=null)reservation.close();return false;}
  if(analysisQueue.offer(task,reservation)){countersChanged();return true;}
  dropped.incrementAndGet();queueRejected.incrementAndGet();retries.state(retryId,AnalysisRetries.State.QUEUE_FULL,"대기열 개수/입력 바이트 한도 · 대기가 줄어든 뒤 선택 원본 재시도");countersChanged();return false;
 }
 void retryOriginal(String id){
  if(closed.get())return;var entry=retries.begin(id);if(entry==null)return;var original=entry.original();
  if(original.responseBodyLength()>ResponseAnalysisBudget.BODY_LIMIT||original.requestLength()+(long)original.responseLength()>ResponseAnalysisBudget.WIRE_LIMIT){retries.state(id,AnalysisRetries.State.TOO_LARGE,"본문 2 MiB / 원본 복원 5 MiB 한도 초과 · 원본에서 별도 확인");return;}
  long token=generation.get();var prepared=ResponseAnalysisInput.original(original,analysisBudget);
  submitAnalysis(()->analyzeInput(prepared.input(),token,id),id,prepared.reservation());
 }
 void analyzeInput(ResponseAnalysisInput input,long token,String retryId){
  if(closed.get()||token!=generation.get())return;
  if(!WorkbenchScope.contains(api,input.url())){retries.state(retryId,AnalysisRetries.State.SCOPE_EXCLUDED,"현재 Scope 밖 원본 · 분석하지 않았습니다.");return;}
  // Account for raw restoration and host packet copies before asking the store/factory for bytes.
  try(var restore=analysisBudget.working(3L*input.wireBytes()+512*1024L)){
   if(restore==null){retries.state(retryId,AnalysisRetries.State.QUEUE_FULL,"원본 복원 작업 예산 부족 · 작업 종료 후 재시도");return;}
   countersChanged();
   if(input instanceof ResponseAnalysisInput.Snapshot snapshot){analyzeData(snapshot.data(),token,retryId,()->retainedSource(snapshot::packet));return;}
   HttpRequestResponse packet;
   try{packet=input.packet();if(packet==null||packet.request()==null||packet.response()==null)throw new IllegalStateException("원본 복원 실패");}
   catch(RuntimeException error){retries.state(retryId,input instanceof ResponseAnalysisInput.Original?AnalysisRetries.State.EXPIRED:AnalysisRetries.State.FAILED,"원본 복원 불가 · 초기화/만료 또는 입력 형식을 확인하세요.");return;}
   analyze(packet.request(),packet.response(),token,retryId);
  }finally{countersChanged();}
 }
 void analyze(HttpRequest q,HttpResponse s,long token,String retryId){
  if(closed.get()||token!=generation.get())return;
  try{
   if(!WorkbenchScope.contains(api,q)){retries.state(retryId,AnalysisRetries.State.SCOPE_EXCLUDED,"현재 Scope 밖 원본 · 분석하지 않았습니다.");return;}
   analyzeData(ResponseAnalysisInput.data(q,s),token,retryId,()->EvidenceLinks.retain(q,s));
  }catch(Exception error){analysisFailure(token,retryId);}
 }
 HttpRequestResponse retainedSource(Supplier<HttpRequestResponse> source){try{var packet=source.get();return packet==null?null:EvidenceLinks.retain(packet.request(),packet.response());}catch(RuntimeException unavailable){return null;}}
 void analysisFailure(long token,String retryId){if(closed.get()||token!=generation.get())return;dropped.incrementAndGet();analysisErrors.incrementAndGet();retries.state(retryId,AnalysisRetries.State.FAILED,"압축 해제/로컬 분석 실패 · 원본 형식 확인 후 재시도");countersChanged();}
 void analyzeData(ResponseAnalysisInput.Data data,long token,String retryId,Supplier<HttpRequestResponse> source){
  if(closed.get()||token!=generation.get())return;
  try{
   int bodyBytes=data.body().length;if(bodyBytes>ResponseAnalysisBudget.BODY_LIMIT){retries.state(retryId,AnalysisRetries.State.TOO_LARGE,"수신 본문 2 MiB 한도 초과");return;}
   String encoding=Objects.toString(data.encoding(),"");
   long decodedLimit=encoding.isBlank()||encoding.equalsIgnoreCase("identity")?bodyBytes:JsBodyDecoder.WORK_LIMIT;
   // Six copies covers the expanding byte buffer, output copy and UTF-16 text allowance.
   try(var decoding=analysisBudget.working(6L*decodedLimit+512*1024L)){
   if(decoding==null){retries.state(retryId,AnalysisRetries.State.QUEUE_FULL,"디코딩 작업 예산 부족 · 작업 종료 후 재시도");return;}
   countersChanged();
   retries.state(retryId,AnalysisRetries.State.RUNNING,"보관 응답을 로컬에서 분석 중");
   String url=data.url(),safe=ActivityTracker.safeUrl(url);if(safe==null){retries.state(retryId,AnalysisRetries.State.UNSUPPORTED,"URL 형식 확인 필요");return;}String body=JsBodyDecoder.decode(data.body(),encoding,data.contentType()),headers=data.headers(),type=EnvironmentResponse.header(headers,"Content-Type");
   var passiveAnalysis=PassiveFindings.analyzeDetailed(url,data.status(),headers,body);
   var analyzedFindings=passiveAnalysis.findings();
   boolean counted=countedOriginals.contains(retryId);if(!counted&&countedOriginals.size()>=AnalysisRetries.LIMIT)countedOriginals.remove(countedOriginals.iterator().next());countedOriginals.add(retryId);
   for(var f:analyzedFindings){String key=ApiInventory.origin(safe)+"|"+f.rule()+"|"+f.location().replaceAll("Body: [0-9]+","Body")+"|"+f.evidence();if(issues.size()<500||issues.containsKey(key)){issues.put(key,f);if(!counted||!counts.containsKey(key))counts.merge(key,1,(a,b)->Math.min(1000000,a+b));cachePacket(findingPackets,key,source.get());Set<String> urls=affected.computeIfAbsent(key,k->new LinkedHashSet<>());if(urls.size()<100)urls.add(safe);}}
   if(observed.size()>=1000&&!observed.containsKey(safe))observed.remove(observed.keySet().iterator().next());observed.computeIfAbsent(safe,k->new HashSet<>()).add(data.method());
   var docs=ApiInventory.parse(body,url);for(var e:docs)if(inventory.size()<500||inventory.containsKey(e.method()+" "+e.url()))inventory.put(e.method()+" "+e.url(),e);
   if(!docs.isEmpty()){observedTemplates.clear();for(var e:inventory.values())for(var entry:observed.entrySet())if(e.matches(entry.getKey()))observedTemplates.computeIfAbsent(e.url(),k->new HashSet<>()).addAll(entry.getValue());}
   else for(var e:inventory.values())if(e.matches(safe))observedTemplates.computeIfAbsent(e.url(),k->new HashSet<>()).add(data.method());
   String sourceId=flows.observe(url,data.method(),data.requestBody(),data.status(),type,body.substring(0,Math.min(body.length(),65536)),data.location());if(!sourceId.isEmpty())cachePacket(flowPackets,sourceId,source.get());refresh(token);
   if(closed.get()||token!=generation.get())return;
   retries.state(retryId,passiveAnalysis.complete()?AnalysisRetries.State.COMPLETE:AnalysisRetries.State.PARTIAL,passiveAnalysis.note()+" · 관찰 후보 · 취약점 확정 아님");
   }
  }catch(Exception error){analysisFailure(token,retryId);}
 }

 void refresh(long token){
  if(closed.get()||token!=generation.get())return;
  Map<String,Set<String>> affectedSnapshot=new LinkedHashMap<>();
  List<Object[]> fs=new ArrayList<>(),apis=new ArrayList<>(),stored=new ArrayList<>();
  List<String> findingKeys=new ArrayList<>();
  for(var entry:issues.entrySet()){
   var f=entry.getValue();findingKeys.add(entry.getKey());
   String group=ApiInventory.origin(f.url())+"|"+f.rule()+"|"+f.location()+"|"+f.evidence();
   affectedSnapshot.put(group,Set.copyOf(affected.getOrDefault(entry.getKey(),Set.of())));
   fs.add(new Object[]{f.rule(),f.url(),f.location(),f.evidence(),counts.get(entry.getKey())});
  }
  Map<String,Set<String>> documented=new HashMap<>();
  for(var e:inventory.values())documented.computeIfAbsent(e.url(),k->new HashSet<>()).add(e.method());
  for(var e:inventory.values()){
   Set<String> methods=new TreeSet<>(observedTemplates.getOrDefault(e.url(),Set.of()));
   methods.removeAll(documented.get(e.url()));
   String note=methods.isEmpty()?e.note():"문서/관측 메서드 불일치: "+String.join(",",methods);
   if(e.template())note+=" · 변수 미확정";
   apis.add(new Object[]{e.method(),e.url(),String.join(", ",new TreeSet<>(e.query())),String.join(", ",new TreeSet<>(e.body())),note});
  }
  List<StoredFlows.Flow> flowSnapshot=flows.views();
  for(var f:flowSnapshot)stored.add(new Object[]{f.marker(),f.writeUrl(),f.readUrl(),StoredFlows.writeState(f),
    f.readbackObserved()?"2xx에서 마커 관측":"미확인",f.htmlObserved()?(f.escapedCandidate()?"문자 표시 후보":"출력 관측"):"미확인",
    f.browserEvidence().isEmpty()?"실행 미검증":"사용자 수동 확인",f.status()});
  if(closed.get()||token!=generation.get())return;
  // Keep one latest immutable view while EDT is busy; analysis/evidence are never dropped.
  ViewSnapshot view=new ViewSnapshot(token,List.copyOf(fs),List.copyOf(apis),List.copyOf(stored),
    Map.copyOf(inventory),Map.copyOf(affectedSnapshot),snapshotPackets(findingPackets),snapshotPackets(flowPackets),
    List.copyOf(findingKeys),flowSnapshot);
  pendingView.set(view);
  if(closed.get()||token!=generation.get()){pendingView.compareAndSet(view,null);return;}
  scheduleView();
 }

 void scheduleView(){
  if(closed.get())return;
  if(viewUpdatePending.compareAndSet(false,true))SwingUtilities.invokeLater(this::applyPendingView);
 }

 void applyPendingView(){
  try{
   ViewSnapshot snapshot=pendingView.getAndSet(null);
   if(snapshot==null||closed.get()||snapshot.token()!=generation.get())return;
   long started=System.nanoTime();
   int selectedFinding=findingTable.getSelectedRow();
   int modelFinding=selectedFinding<0?-1:findingTable.convertRowIndexToModel(selectedFinding);
   String selectedKey=modelFinding<0||modelFinding>=displayedFindingKeys.size()?null:displayedFindingKeys.get(modelFinding);
   int selectedApi=apiTable.getSelectedRow();
   int modelApi=selectedApi<0?-1:apiTable.convertRowIndexToModel(selectedApi);
   String selectedApiKey=modelApi<0?null:documents.getValueAt(modelApi,0)+" "+documents.getValueAt(modelApi,1);
   String selectedFlow=selectedFlowId();
   displayedInventory=snapshot.inventory();displayedAffected=snapshot.affected();
   displayedFindingPackets=snapshot.findingPackets();displayedFlowPackets=snapshot.flowPackets();
   displayedFindingKeys=snapshot.findingKeys();displayedFlows=snapshot.flows();
   // One data event preserves column widths and sorting without per-row sorter rebuilds.
   findings.replaceRows(snapshot.findings());documents.replaceRows(snapshot.apis());flowModel.replaceRows(snapshot.flowRows());
   if(selectedKey!=null)restoreSelection(findingTable,displayedFindingKeys.indexOf(selectedKey));
   if(selectedApiKey!=null)for(int row=0;row<documents.getRowCount();row++)
    if(selectedApiKey.equals(documents.getValueAt(row,0)+" "+documents.getValueAt(row,1))){restoreSelection(apiTable,row);break;}
   for(int row=0;row<displayedFlows.size();row++)if(displayedFlows.get(row).id().equals(selectedFlow)){restoreSelection(flowTable,row);break;}
   showFlowDetail();refreshActions();viewLatency.since(started);updateCounters();
  }finally{
   viewUpdatePending.set(false);
   if(pendingView.get()!=null&&!closed.get())scheduleView();
  }
 }

 private static void restoreSelection(JTable table,int modelRow){
  if(modelRow<0)return;
  int view=table.convertRowIndexToView(modelRow);
  if(view>=0)table.setRowSelectionInterval(view,view);
 }
 @Override public void close(){findingResponse.close();findingRequest.close();closed.set(true);pendingView.set(null);retries.clear();generation.incrementAndGet();analysisQueue.close();markerWindow.close();draftWindow.close();flows.clear();clearPackets();displayedFindingPackets=Map.of();displayedFlowPackets=Map.of();displayedFlows=List.of();displayedFindingKeys=List.of();synchronized(browserImages){browserImages.clear();}flowDetail.setText("");}
}
