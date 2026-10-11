package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.contextmenu.*;
import burp.api.montoya.ui.editor.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

public final class WorkbenchExtension implements HttpHandler, ContextMenuItemsProvider {
    private static final int ROW_LIMIT=250, MESSAGE_LIMIT=128*1024;
    private MontoyaApi api;private WorkbenchScope localScope;private final DefaultTableModel scopeModel=model("범위","등록 URL (쿼리 제외)","포함 관계");private final JTable scopeTable=new JTable(scopeModel){public String getToolTipText(java.awt.event.MouseEvent e){int row=rowAtPoint(e.getPoint()),column=columnAtPoint(e.getPoint());return row<0||column<0?null:Objects.toString(getValueAt(row,column),"");}};private JPanel scopeContent;private JDialog scopeWindow;private boolean settingsActionReady;
    @FunctionalInterface interface DialogOpener {JDialog open(Component owner,String title,Component content,boolean modal,Runnable closed);}
    DialogOpener dialogOpener=WorkbenchUi::settingsWindow;private JButton scopeListButton;
    private final AtomicBoolean capture=new AtomicBoolean(true), scopeOnly=new AtomicBoolean(false), unloaded=new AtomicBoolean(false);
    private final AtomicLong generation=new AtomicLong();
    private final AtomicInteger dropped=new AtomicInteger();
    private final ArrayBlockingQueue<Traffic> incoming=new ArrayBlockingQueue<>(250);
    private final List<Traffic> traffic=new ArrayList<>();
    private final DefaultTableModel logModel=model("NO","메서드","URL","상태","시간","도구","본문 bytes");
    private JTable logTable;
    private long trafficNo;
    private final JLabel captureInfo=new JLabel();
    private JTabbedPane tabs;private JTabbedPane dataTabs;private LayoutSettings commonSettings;private JPanel commonSettingsContent;private JDialog commonSettingsWindow;
    public void addPayloadRunnerPage(Component page) {WorkbenchUi.style(page);tabs.insertTab("페이로드 검사",null,page,null,tabs.indexOfTab("점검 지원"));WorkbenchUi.packetLayout(page,commonSettings.choice.getSelectedIndex()==1);}
    public void selectPayloadRunnerPage() {for(int i=0;i<tabs.getTabCount();i++)if(tabs.getTitleAt(i).equals("페이로드 검사")){tabs.setSelectedIndex(i);break;}}
    public void capturePayloadEvidence(burp.api.montoya.http.HttpService service,java.util.List<kr.payloadrunner.Engine.Row> rows){bountySupport.capturePayload(service,rows);tabs.setSelectedComponent(bountySupport);bountySupport.pages.setSelectedIndex(3);}
    private java.util.function.Consumer<HttpRequest> payloadImport;
    public void setPayloadImport(java.util.function.Consumer<HttpRequest> action){payloadImport=action;}
    private void selectedPayload(){if(current==null)throw new IllegalArgumentException("요청을 선택하세요.");if(payloadImport==null)throw new IllegalStateException("Payload Runner 준비 중");payloadImport.accept(current.request);}
    private JPanel root;
    private EnvironmentPanel environments;private PassiveScanWorkspace passiveScan;private ActivityPanel activity;
    private StructuredComparePanel structuredCompare;private ResponseReconPanel responseRecon;private JsAnalysisPanel jsAnalysis;
    private AuthComparePanel authCompare;private BountySupportPanel bountySupport;
    private JLabel selectionInfo=new JLabel("요청을 선택하세요");
    private JLabel status=new JLabel("준비됨"), stats=new JLabel();
    private JTextField filter=new JTextField(24);
    private JTextArea codecIn=area("",10), codecOut=area("",10), jwtIn=area("",5), jwtOut=area("",12), jsIn=area("",10), jsOut=area("",10);
    private HttpRequestEditor requestView;private final PacketView requestPacket=new PacketView(),responsePacket=new PacketView();
    private HttpResponseEditor responseView;
    private Traffic current;
    private javax.swing.Timer timer;
    private record Traffic(String time,String source,HttpRequest request,HttpResponse response) {}

    public void initialize(MontoyaApi api) {
        this.api=api;localScope=WorkbenchScope.install(api);api.extension().setName("Burp Workbench KR 0.23.0");
        api.extension().registerUnloadingHandler(()->{
            unloaded.set(true);generation.incrementAndGet();incoming.clear();
            SwingUtilities.invokeLater(()->{if(commonSettingsWindow!=null)commonSettingsWindow.dispose();if(scopeWindow!=null)scopeWindow.dispose();if(timer!=null) timer.stop();if(structuredCompare!=null)structuredCompare.clearSources();if(responseRecon!=null)responseRecon.close();if(jsAnalysis!=null)jsAnalysis.close();if(authCompare!=null)authCompare.close();if(bountySupport!=null)bountySupport.close();traffic.clear();if(environments!=null){environments.close();}if(passiveScan!=null)passiveScan.close();if(activity!=null)activity.close();WorkbenchScope.release(api);});
        });
        try {
            if(SwingUtilities.isEventDispatchThread()) buildUi(); else SwingUtilities.invokeAndWait(this::buildUi);
            SwingUtilities.invokeLater(jsAnalysis::startHistory);api.http().registerHttpHandler(this);api.userInterface().registerContextMenuItemsProvider(this);
            api.logging().logToOutput("Burp Workbench KR loaded. 인증/인가 재전송은 선택 실행 또는 명시적인 Record ON에서 실행합니다.");
        } catch(Exception e) { api.logging().logToError(e.toString());throw new IllegalStateException(e); }
    }
    private static DefaultTableModel model(String... names) { return new DefaultTableModel(names,0){@Override public boolean isCellEditable(int r,int c){return false;}}; }
    private static JTextArea area(String text,int rows) {JTextArea a=new JTextArea(text,rows,60);a.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));a.setLineWrap(true);a.setWrapStyleWord(true);return a;}
    private JButton button(String text,Runnable action) {JButton b=new JButton(text);b.addActionListener(e->{try{action.run();}catch(Exception ex){error(ex);}});return b;}
    private static JPanel row(Component... parts) {return WorkbenchUi.row(parts);}
    private static JPanel page(Component top,Component center) {JPanel p=new JPanel(new BorderLayout(8,8));p.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));p.add(top,BorderLayout.NORTH);p.add(center,BorderLayout.CENTER);return p;}
    private static JPanel labeled(String label,Component c) {return WorkbenchUi.card(label,c);}
    private static JSplitPane split(int orientation,Component a,Component b) {return WorkbenchUi.split(orientation,a,b,.5);}
    private void buildUi() {
        root=new JPanel(new BorderLayout());root.setName("wb.root");tabs=new JTabbedPane(){@Override public void setSelectedIndex(int index){if(settingsActionReady&&index>=0&&index<getTabCount()&&getTitleAt(index).equals("설정")){openSettings();return;}super.setSelectedIndex(index);}};
        requestView=api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        responseView=api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        addPage("트래픽","traffic",trafficPanel(),false);addPage("권한 비교","auth",authPanel(),false);addPage("데이터 도구","data",dataPanel(),true);addPage("JS 추출","js",jsPanel(),false);environments=new EnvironmentPanel(api);addPage("환경 탐색","environment",environments,false);passiveScan=new PassiveScanWorkspace(api);addPage("URL 수집","passive",passiveScan,false);
        activity=new ActivityPanel(api);activity.assessmentPanel.selectedTraffic=()->selectedTraffic().stream().map(t->HttpRequestResponse.httpRequestResponse(t.request,t.response)).toList();activity.assessmentPanel.authNames=()->authCompare.authNames;activity.assessmentPanel.cookieNames=()->authCompare.cookieNames;authCompare.trackingSource=activity.tracker.assessments::accounts;authCompare.trackingCreate=activity.tracker.assessments::register;authCompare.trackingBind=activity.tracker.assessments::bind;authCompare.trackingSink=activity::accountInspection;jsAnalysis.coverage=(result,source)->activity.analyzed(result,source);jsAnalysis.observation=activity::observation;activity.sourceNavigator=(source,line,kind)->{tabs.setSelectedComponent(jsAnalysis);jsAnalysis.navigateSource(source,line,kind);};authCompare.reviewSink=(method,url,reason)->activity.review(method,url,reason);environments.sessions=(slot,url)->{var candidate=authCompare.slots[slot];if(candidate==null)throw new IllegalArgumentException("권한 비교 계정 미지정");if(!candidate.origin().equals(AuthComparePanel.origin(HttpRequest.httpRequestFromUrl(url))))throw new IllegalArgumentException("계정 Origin과 대상이 다릅니다.");return candidate.headers();};addPage("활동 추적","activity",activity,false);passiveScan.discovery=(url,source)->activity.discover(url,source);responseRecon=new ResponseReconPanel(api);addPage("응답 분석","response.recon",responseRecon,false);bountySupport=new BountySupportPanel(api,()->selectedTraffic().stream().map(t->HttpRequestResponse.httpRequestResponse(t.request,t.response)).toList(),authCompare,activity);addPage("점검 지원","bounty",bountySupport,false);structuredCompare.evidenceSink=this::captureObserved;passiveScan.evidenceSink=this::captureObserved;responseRecon.evidenceSink=this::captureObserved;responseRecon.storedEvidenceSink=(packets,note,image,sha)->{bountySupport.captureObservedWithImage(packets,note,image,sha);tabs.setSelectedComponent(bountySupport);};tabs.addTab("설정",settingsPanel());settingsActionReady=true;root.add(tabs);commonSettings.apply();JPanel footer=new JPanel(new BorderLayout(12,0));footer.setBorder(BorderFactory.createEmptyBorder(6,12,6,12));footer.add(status);footer.add(stats,BorderLayout.EAST);root.add(footer,BorderLayout.SOUTH);WorkbenchUi.status(status);
        api.userInterface().applyThemeToComponent(root);WorkbenchUi.style(root);api.userInterface().registerSuiteTab("Workbench KR",root);
        timer=new javax.swing.Timer(300,e->{drain();});timer.start();
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control R"),"repeater");
        root.getActionMap().put("repeater",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){try{selectedTraffic().forEach(t->api.repeater().sendToRepeater(t.request,"Workbench"));}catch(Exception ex){error(ex);}}});
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control F"),"search");
        root.getActionMap().put("search",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){tabs.setSelectedIndex(0);filter.requestFocusInWindow();filter.selectAll();}});
    }
    private void addPage(String title,String id,Component body,boolean data){tabs.addTab(title,body);}
    private JPanel settingsPanel(){
        JPanel page=new JPanel(new BorderLayout(8,8));page.setName("wb.common.settings");page.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));commonSettings=new LayoutSettings(api,root);commonSettingsContent=new JPanel();commonSettingsContent.setLayout(new BoxLayout(commonSettingsContent,BoxLayout.Y_AXIS));commonSettingsContent.add(WorkbenchUi.card("화면 배치",commonSettings));commonSettingsContent.add(WorkbenchUi.row(new JLabel("모든 도구에 공통 적용 · 데이터 도구는 입력 / 출력 방향에 적용")));JButton open=button("설정 창 열기",this::openSettings);open.setName("wb.common.settings.open");WorkbenchUi.primary(open);page.add(WorkbenchUi.header("설정","공통 설정은 별도 창에서 관리합니다.",WorkbenchUi.row(open)),BorderLayout.NORTH);return page;
    }
    private void openSettings(){if(unloaded.get())return;if(commonSettingsWindow!=null&&commonSettingsWindow.isDisplayable()){commonSettingsWindow.toFront();commonSettingsWindow.requestFocus();return;}commonSettingsWindow=dialogOpener.open(root,"Workbench KR · 공통 설정",commonSettingsContent,false,()->commonSettingsWindow=null);}
    private void captureObserved(List<HttpRequestResponse> packets,String note){bountySupport.captureObserved(packets,note);tabs.setSelectedComponent(bountySupport);}
    private JPanel dataPanel(){dataTabs=new JTabbedPane();dataTabs.addTab("변환",withHeading("변환","입력과 출력 동시 확인",codecPanel()));dataTabs.addTab("JWT",withHeading("JWT","헤더·페이로드 디코딩",jwtPanel()));structuredCompare=new StructuredComparePanel(api);structuredCompare.selectedTraffic=()->selectedTraffic().stream().map(t->HttpRequestResponse.httpRequestResponse(t.request,t.response)).toList();dataTabs.addTab("비교",structuredCompare);JPanel page=new JPanel(new BorderLayout());page.setName("wb.data.tools");page.putClientProperty("wb.data.tools",true);page.add(dataTabs);return page;}
    private JMenuItem trafficAction(String title,Runnable action){JMenuItem item=new JMenuItem(title);item.addActionListener(e->{try{action.run();}catch(Exception ex){error(ex);}});return item;}
    private JPanel trafficPanel() {
        JCheckBox enabled=new JCheckBox("수집 ON",true),scope=new JCheckBox("Scope만",false);
        enabled.setToolTipText("응답이 있는 신규 요청을 자동 수집합니다. 기존 History는 우클릭으로 가져오세요.");
        scope.setToolTipText("기본 OFF · 전체 트래픽에서 우클릭으로 SCOPE를 등록하세요. ON이면 이후 SCOPE 내 트래픽만 수집합니다.");
        enabled.addActionListener(e->{capture.set(enabled.isSelected());enabled.setText(enabled.isSelected()?"수집 ON":"수집 OFF");refreshTrafficInfo();});
        scope.addActionListener(e->{scopeOnly.set(scope.isSelected());refreshTrafficInfo();});
        logTable=new JTable(logModel){public String getToolTipText(java.awt.event.MouseEvent e){int row=rowAtPoint(e.getPoint());if(row<0)return null;int index=convertRowIndexToModel(row);return index<traffic.size()?WorkbenchScope.reason(api,traffic.get(index).request):null;}};logTable.setName("wb.traffic.history");logTable.setAutoCreateRowSorter(true);logTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);JPopupMenu trafficMenu=new JPopupMenu();trafficMenu.add(trafficAction("URL 수집",()->{if(current==null)throw new IllegalArgumentException("요청을 선택하세요.");openPassive(current.request.url());}));trafficMenu.addSeparator();trafficMenu.add(trafficAction("권한 비교",()->{selectedTraffic();tabs.setSelectedIndex(1);authCompare.compareSelection();}));trafficMenu.add(trafficAction("Repeater",()->selectedTraffic().forEach(t->api.repeater().sendToRepeater(t.request,"Workbench"))));trafficMenu.add(trafficAction("페이로드 검사",this::selectedPayload));trafficMenu.add(trafficAction("점검 지원",()->{selectedTraffic();tabs.setSelectedComponent(bountySupport);}));trafficMenu.add(trafficAction("JWT",this::selectedJwt));trafficMenu.add(trafficAction("JS 추출",this::selectedJs));trafficMenu.add(trafficAction("변환",()->{if(current==null)throw new IllegalArgumentException("요청을 선택하세요.");codecIn.setText(current.request.toString());tabs.setSelectedIndex(2);dataTabs.setSelectedIndex(0);}));JMenu scopeMenu=new JMenu("SCOPE 지정");scopeMenu.setName("wb.traffic.scope.menu");scopeMenu.add(trafficAction("선택 URL 경로 추가",()->addSelectedScope(false)));scopeMenu.add(trafficAction("선택 도메인 전체 추가",()->addSelectedScope(true)));scopeMenu.add(trafficAction("SCOPE 목록 보기",this::showScopeList));trafficMenu.addSeparator();trafficMenu.add(scopeMenu);logTable.putClientProperty("wb.traffic.popup",trafficMenu);logTable.addMouseListener(new java.awt.event.MouseAdapter(){void popup(java.awt.event.MouseEvent e){if(!e.isPopupTrigger())return;int row=logTable.rowAtPoint(e.getPoint());if(row>=0){if(!logTable.isRowSelected(row))logTable.setRowSelectionInterval(row,row);showTraffic(row);}else logTable.clearSelection();for(Component c:trafficMenu.getComponents())if(c instanceof JMenuItem item)item.setEnabled(row>=0);trafficMenu.show(logTable,e.getX(),e.getY());}public void mousePressed(java.awt.event.MouseEvent e){popup(e);}public void mouseReleased(java.awt.event.MouseEvent e){popup(e);}});
        for(int i=6;i>=4;i--)logTable.removeColumn(logTable.getColumnModel().getColumn(i));
        int[] widths={55,75,340,65};for(int i=0;i<widths.length;i++)logTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        logTable.getColumnModel().getColumn(0).setMaxWidth(75);logTable.getColumnModel().getColumn(1).setMaxWidth(100);logTable.getColumnModel().getColumn(3).setMaxWidth(85);
        TableRowSorter<?> sorter=(TableRowSorter<?>)logTable.getRowSorter();
        sorter.setComparator(0,(a,b)->Long.compare(((Number)a).longValue(),((Number)b).longValue()));
        filter.setColumns(20);filter.setToolTipText("메서드·URL·상태 검색 · Ctrl+F");
        filter.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){change();}public void removeUpdate(DocumentEvent e){change();}public void changedUpdate(DocumentEvent e){change();}void change(){sorter.setRowFilter(filter.getText().isEmpty()?null:RowFilter.regexFilter("(?i)"+Pattern.quote(filter.getText()),1,2,3));refreshTrafficInfo();}});
        logTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())showTraffic(logTable.getSelectedRow());});
        JButton clear=button("목록 비우기",()->{generation.incrementAndGet();incoming.clear();traffic.clear();logModel.setRowCount(0);current=null;selectionInfo.setText("요청을 선택하세요");requestView.setRequest(null);responseView.setResponse(null);requestPacket.setPacket(null);responsePacket.setPacket(null);refreshTrafficInfo();});
        scopeListButton=button("SCOPE 목록",this::showScopeList);scopeListButton.setName("wb.traffic.scope.list");JPanel controls=row(new JLabel("트래픽"),enabled,scope,new JLabel("검색"),filter,button("검색 초기화",()->filter.setText("")),clear,button("내보내기",this::export),scopeListButton,captureInfo);
        JPanel top=controls;
        JPanel req=WorkbenchUi.card("요청  REQUEST",requestPacket),res=WorkbenchUi.card("응답  RESPONSE",responsePacket);
        JSplitPane viewers=WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT,req,res,.5);viewers.setName("wb.traffic.messages");
        JPanel messages=new JPanel(new BorderLayout(0,6));selectionInfo.setBorder(BorderFactory.createEmptyBorder(6,8,6,8));messages.add(selectionInfo,BorderLayout.NORTH);messages.add(viewers);
        JSplitPane body=WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,trafficListPanel(),messages,.30);body.setName("wb.traffic.workspace");
        JPanel main=page(top,body);main.setName("wb.traffic");refreshTrafficInfo();return main;
    }
    private void showTraffic(int view){if(view>=0){int index=logTable.convertRowIndexToModel(view);if(index<traffic.size()){current=traffic.get(index);requestView.setRequest(current.request);responseView.setResponse(current.response);requestPacket.setPacket(current.request.toString());responsePacket.setPacket(current.response==null?null:current.response.toString(),PacketFormat.decodedResponse(current.response));selectionInfo.setText(String.format("%03d",((Number)logModel.getValueAt(index,0)).longValue())+"   "+current.request.method()+"   "+current.request.url()+"   · "+(current.response==null?"응답 없음":current.response.statusCode()+" · "+current.response.body().length()+" bytes"));selectionInfo.setText(selectionInfo.getText()+" · "+WorkbenchScope.reason(api,current.request));selectionInfo.setToolTipText(selectionInfo.getText());}}else{current=null;requestView.setRequest(null);responseView.setResponse(null);requestPacket.setPacket(null);responsePacket.setPacket(null);selectionInfo.setText("목록에서 요청을 선택하세요 · 요청과 응답을 함께 표시합니다.");}}
    void openPassive(String url){for(int i=0;i<tabs.getTabCount();i++)if(tabs.getTitleAt(i).equals("URL 수집")){tabs.setSelectedIndex(i);break;}try{passiveScan.open(url,true);}catch(IllegalArgumentException e){status.setText("URL 수집 · "+e.getMessage());}}
    private void refreshTrafficInfo(){if(logTable==null)return;captureInfo.setText("보관 "+traffic.size()+" · 표시 "+logTable.getRowCount()+" · "+(capture.get()?"수집 중":"일시정지")+" · "+(scopeOnly.get()?"Scope 내":"전체 대상"));captureInfo.setToolTipText("최근 250건 · 요청+응답 합계 128 KiB 이하 · Scope만 ON이면 범위 밖 제외 · 응답 없는 요청은 트래픽 수집 제외");}
    private JPanel withHeading(String title,String hint,JPanel content){JPanel p=new JPanel(new BorderLayout(0,4));p.setBorder(BorderFactory.createEmptyBorder(6,8,8,8));p.add(WorkbenchUi.header(title,hint,null),BorderLayout.NORTH);p.add(content);return p;}
    private Component trafficListPanel(){
        JPanel panel=new JPanel(new BorderLayout(4,4));scopeTable.setName("wb.traffic.scope.table");scopeTable.setAutoCreateRowSorter(true);scopeTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);scopeTable.getColumnModel().getColumn(0).setMinWidth(115);scopeTable.getColumnModel().getColumn(0).setPreferredWidth(120);scopeTable.getColumnModel().getColumn(0).setMaxWidth(140);scopeTable.getColumnModel().getColumn(1).setPreferredWidth(450);scopeTable.getColumnModel().getColumn(2).setPreferredWidth(340);JScrollPane rulesScroll=new JScrollPane(scopeTable);rulesScroll.setPreferredSize(new Dimension(650,230));panel.add(rulesScroll);JTextArea note=new JTextArea("트래픽 우클릭 → SCOPE 지정\nURL 경로: 같은 경로와 하위 경로 · 도메인 전체: 같은 프로토콜/호스트/포트\n등록한 Workbench SCOPE를 모든 도구에 적용합니다. Burp Target Scope는 변경하지 않습니다.");note.setEditable(false);note.setLineWrap(true);note.setWrapStyleWord(true);panel.add(note,BorderLayout.NORTH);JButton remove=button("선택 SCOPE 삭제",()->{List<WorkbenchScope.Rule> rules=localScope.rules();List<WorkbenchScope.Rule> picked=new ArrayList<>();for(int row:scopeTable.getSelectedRows())picked.add(rules.get(scopeTable.convertRowIndexToModel(row)));for(var rule:picked)localScope.remove(rule);scopeChanged();});remove.setName("wb.traffic.scope.remove");panel.add(row(remove,button("규칙 내보내기",this::exportScopeRules),button("규칙 불러오기 · 교체",this::importScopeRules)),BorderLayout.SOUTH);scopeContent=panel;scopeContent.setName("wb.traffic.scope.content");WorkbenchUi.style(scopeContent);refreshScopeList();return new JScrollPane(logTable);
    }
    private void exportScopeRules(){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("workbench-scope.json"));if(chooser.showSaveDialog(root)!=JFileChooser.APPROVE_OPTION)return;java.nio.file.Path path=chooser.getSelectedFile().toPath();if(java.nio.file.Files.exists(path)&&JOptionPane.showConfirmDialog(root,"기존 파일을 덮어쓸까요?","SCOPE 저장",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;try{java.nio.file.Files.writeString(path,localScope.save(),java.nio.charset.StandardCharsets.UTF_8);status.setText("SCOPE 저장 완료 · URL 쿼리 값 제외");}catch(Exception ex){error(ex);}}
 private void importScopeRules(){JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(root)!=JFileChooser.APPROVE_OPTION)return;try{java.nio.file.Path path=chooser.getSelectedFile().toPath();if(java.nio.file.Files.size(path)>2*1024*1024)throw new IllegalArgumentException("SCOPE 파일 2MB 제한");localScope.importRules(java.nio.file.Files.readString(path));scopeChanged();}catch(Exception ex){error(ex);}}
 private void showScopeList(){if(unloaded.get())return;if(scopeWindow!=null&&scopeWindow.isDisplayable()){scopeWindow.toFront();scopeWindow.requestFocus();return;}refreshScopeList();scopeWindow=dialogOpener.open(root,"Workbench KR · SCOPE 목록",scopeContent,false,()->scopeWindow=null);}
    private void refreshScopeList(){scopeModel.setRowCount(0);for(var rule:localScope.rules())scopeModel.addRow(new Object[]{rule.originOnly()?"도메인 전체":"URL 경로",rule.url(),localScope.overlap(rule)});if(scopeListButton!=null)scopeListButton.setText("SCOPE 목록 ("+scopeModel.getRowCount()+")");}
    private void addSelectedScope(boolean originOnly){List<Traffic> chosen=selectedTraffic();List<Traffic> newlyIncluded=traffic.stream().filter(t->!WorkbenchScope.contains(api,t.request)).toList();for(Traffic t:chosen)localScope.add(t.request.url(),originOnly);scopeChanged();for(Traffic t:newlyIncluded)if(WorkbenchScope.contains(api,t.request)){environments.observe(t.request,t.response);authCompare.observe(t.request,t.response);if(responseRecon!=null)responseRecon.offer(t.request,t.response);if(activity!=null){activity.observe(t.request,"트래픽 SCOPE");activity.response(t.request,t.response);}}status.setText("SCOPE 등록 완료 · "+chosen.size()+"개 선택 · URL 경로/도메인 범위는 SCOPE 목록에서 확인하세요.");}
    private void scopeChanged(){refreshScopeList();if(current!=null&&logTable.getSelectedRow()>=0)showTraffic(logTable.getSelectedRow());if(jsAnalysis!=null)jsAnalysis.rescanHistory();status.setText("Workbench SCOPE "+localScope.rules().size()+"개 · 모든 도구에 적용 · 마지막 범위를 삭제하면 전송 대상 없음");}
    private JPanel authPanel() {
        authCompare=new AuthComparePanel(api,()->selectedTraffic().stream().map(t->HttpRequestResponse.httpRequestResponse(t.request,t.response)).toList());return authCompare;
    }
    private JPanel codecPanel() {
        codecOut.setEditable(false);String[] ops={"URL 인코딩 (form)","URL 디코딩 (form)","Base64 인코딩","Base64 디코딩","Base64URL 인코딩","Base64URL 디코딩","Hex 인코딩","Hex 디코딩","SHA-256","Gzip → Base64","Base64 → Gzip 해제"};
        JComboBox<String> operation=new JComboBox<>(ops);
        JSplitPane editors=split(JSplitPane.HORIZONTAL_SPLIT,labeled("입력 (UTF-8 텍스트)",new JScrollPane(codecIn)),labeled("출력",new JScrollPane(codecOut)));editors.setName("wb.codec.messages");return page(row(operation,button("변환",()->{try{codecOut.setText(Core.transform((String)operation.getSelectedItem(),codecIn.getText()));}catch(Exception e){error(e);}}),button("출력 → 입력",()->codecIn.setText(codecOut.getText())),button("복사",()->copy(codecOut.getText()))),editors);
    }
    private JPanel jwtPanel() {
        jwtOut.setEditable(false);
        JSplitPane editors=split(JSplitPane.HORIZONTAL_SPLIT,labeled("JWT 입력",new JScrollPane(jwtIn)),labeled("디코딩 결과",new JScrollPane(jwtOut)));editors.setName("wb.jwt.messages");return page(row(button("JWT 디코딩",()->jwtOut.setText(Core.jwt(jwtIn.getText()))),button("선택 요청에서 가져오기",this::selectedJwt),new JLabel("헤더·페이로드 보기 · 서명 검증 기능 없음")),editors);
    }
    private JPanel jsPanel() {jsAnalysis=new JsAnalysisPanel(api,this::selectedJs);return jsAnalysis;}
    private void copy(String text) {Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text),null);}
    private List<Traffic> selectedTraffic() {
        List<Traffic> out=new ArrayList<>();for(int row:logTable.getSelectedRows())out.add(traffic.get(logTable.convertRowIndexToModel(row)));
        if(out.isEmpty()) throw new IllegalArgumentException("트래픽에서 요청을 선택하세요.");return out;
    }
    private void selectedJwt() {
        if(current==null)throw new IllegalArgumentException("트래픽에서 요청을 선택하세요.");
        String token=Core.jwtFromMessage(current.request.toString());
        if(token.isEmpty())throw new IllegalArgumentException("요청에서 JWS 후보를 찾지 못했습니다. JWT 탭에 직접 붙여넣으세요.");
        jwtIn.setText(token);jwtOut.setText(Core.jwt(token));tabs.setSelectedIndex(2);dataTabs.setSelectedIndex(1);
    }
    private void selectedJs() {
        if(current==null || current.response==null)throw new IllegalArgumentException("응답이 있는 요청을 선택하세요.");
        String text=EnvironmentPanel.decodedBody(PacketFormat.decodedResponse(current.response));jsAnalysis.rememberPacket(current.request.url(),EnvironmentPanel.preview(current.request.toString()),EnvironmentPanel.preview(current.response.toString()),EnvironmentPanel.preview(PacketFormat.response(current.response.toByteArray().getBytes(),current.response.statusCode(),"",text)));jsAnalysis.load(text,current.request.url());tabs.setSelectedIndex(3);
    }
    @Override public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent r){if(activity!=null&&!unloaded.get())activity.observe(r,r.toolSource().toolType().toString());if(authCompare!=null&&!unloaded.get())try{authCompare.begin(r,r.toolSource().toolType(),r.messageId());}catch(Exception e){api.logging().logToError("인가 Record 요청 확인 실패: "+e.getClass().getSimpleName());}return RequestToBeSentAction.continueWith(r,r.annotations());}
    @Override public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived r) {
        if(responseRecon!=null&&!unloaded.get())responseRecon.offer(r.initiatingRequest(),r);if(activity!=null&&!unloaded.get())activity.response(r.initiatingRequest(),r);
        if(authCompare!=null&&!unloaded.get())try{authCompare.received(r.messageId(),r.initiatingRequest(),r);}catch(Exception e){api.logging().logToError("인가 Record 응답 확인 실패: "+e.getClass().getSimpleName());}
        if(!unloaded.get()&&capture.get())try {
            HttpRequest q=r.initiatingRequest();
            if((!scopeOnly.get()||WorkbenchScope.contains(api,q))&&q.toByteArray().length()+r.toByteArray().length()<=MESSAGE_LIMIT) {
                Traffic t=new Traffic(LocalTime.now().withNano(0).toString(),r.toolSource().toolType().toString(),q,r);
                if(!incoming.offer(t))dropped.incrementAndGet();
            } else if(q.toByteArray().length()+r.toByteArray().length()>MESSAGE_LIMIT)dropped.incrementAndGet();
        }catch(Exception e){dropped.incrementAndGet();}
        return ResponseReceivedAction.continueWith(r,r.annotations());
    }
    private void drain() {
        for(int i=0;i<50;i++){Traffic t=incoming.poll();if(t==null)break;addTraffic(t);}
        stats.setText("트래픽 "+traffic.size()+" · 인가 "+(authCompare==null?0:authCompare.runs.size())+" · 누락 "+dropped.get());stats.setToolTipText("최근 250행 · 메시지 합계 128 KiB/행");refreshTrafficInfo();
    }
    private void addTraffic(Traffic t) {
        environments.observe(t.request,t.response);if(authCompare!=null)authCompare.observe(t.request,t.response);
        if(traffic.size()>=ROW_LIMIT){traffic.remove(0);logModel.removeRow(0);}traffic.add(t);logModel.addRow(new Object[]{++trafficNo,t.request.method(),t.request.url(),t.response==null?"없음":t.response.statusCode(),t.time,t.source,t.response==null?0:t.response.body().length()});refreshTrafficInfo();
    }
    @Override public List<Component> provideMenuItems(ContextMenuEvent e) {
        List<HttpRequestResponse> picked=new ArrayList<>(e.selectedRequestResponses());if(picked.isEmpty())e.messageEditorRequestResponse().ifPresent(m->picked.add(m.requestResponse()));if(picked.isEmpty())return List.of();
        JMenu menu=new JMenu("Workbench KR");JMenuItem bring=new JMenuItem("통합 트래픽에 가져오기");bring.addActionListener(x->SwingUtilities.invokeLater(()->{
            int count=0;for(HttpRequestResponse rr:picked){if(count>=20)break;if(rr.request()==null||rr.request().toByteArray().length()+(rr.response()==null?0:rr.response().toByteArray().length())>MESSAGE_LIMIT){dropped.incrementAndGet();continue;}addTraffic(new Traffic(LocalTime.now().withNano(0).toString(),"가져오기",rr.request(),rr.response()));if(responseRecon!=null)responseRecon.offer(rr.request(),rr.response());if(activity!=null){activity.observe(rr.request(),"가져오기");activity.response(rr.request(),rr.response());}count++;}
            if(count>0){int row=logTable.convertRowIndexToView(traffic.size()-1);if(row>=0)logTable.setRowSelectionInterval(row,row);status.setText("가져오기 완료 · Workbench KR 탭에서 확인하세요.");}
        }));menu.add(bring);
        JMenuItem repeater=new JMenuItem("Repeater로 보내기");repeater.addActionListener(x->picked.stream().limit(20).forEach(rr->api.repeater().sendToRepeater(rr.request(),"Workbench")));menu.add(repeater);return List.of(menu);
    }
    private void export() {
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("메타데이터 결과 저장 (세션·본문·쿼리 제외)");chooser.setSelectedFile(new File("workbench-results.html"));
        if(chooser.showSaveDialog(root)!=JFileChooser.APPROVE_OPTION)return;File f=chooser.getSelectedFile();
        if(f.exists()&&JOptionPane.showConfirmDialog(root,"기존 파일을 덮어쓸까요?","파일 저장",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        String output=f.getName().toLowerCase(Locale.ROOT).endsWith(".csv")?csvReport():htmlReport();
        try{Files.writeString(f.toPath(),output,StandardCharsets.UTF_8);status.setText("결과 저장 완료 · "+f.getName());}catch(IOException e){error(e);}
    }
    String csvReport() {
        StringBuilder b=new StringBuilder("\ufeff유형,메서드,URL,프로필,원본상태,비교상태,검토결과\r\n");
        for(Traffic t:traffic)b.append("트래픽,").append(Core.csv(t.request.method())).append(',').append(Core.csv(Core.safeUrl(t.request.url()))).append(",,").append(t.response==null?"":t.response.statusCode()).append(",,\r\n");
        return b.toString();
    }
    String htmlReport() {
        StringBuilder b=new StringBuilder("<!doctype html><html lang='ko'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Burp Workbench 결과</title><style>body{font:15px system-ui;margin:32px;background:#f5f7fb;color:#17243b}table{border-collapse:collapse;width:100%;background:white}td,th{padding:10px;border:1px solid #d9e0eb;text-align:left;overflow-wrap:anywhere}th{background:#e4ebf5}h1{font-size:26px}</style><h1>Burp Workbench KR · 결과</h1><p>자동 판정은 검토 후보입니다. 세션 헤더·본문·쿼리·fragment는 내보내지 않습니다. URL 경로에는 식별자가 남을 수 있습니다.</p>");
        b.append("<h2>트래픽 목록</h2><table><tr><th>시간</th><th>도구</th><th>메서드 / URL</th><th>상태</th></tr>");
        for(Traffic t:traffic)b.append("<tr><td>").append(Core.html(t.time)).append("</td><td>").append(Core.html(t.source)).append("</td><td>").append(Core.html(t.request.method()+" "+Core.safeUrl(t.request.url()))).append("</td><td>").append(t.response==null?"없음":t.response.statusCode()).append("</td></tr>");return b.append("</table></html>").toString();
    }
    private void error(Exception e){status.setText("확인 필요 · "+e.getMessage());JOptionPane.showMessageDialog(root,e.getMessage(),"Workbench KR",JOptionPane.INFORMATION_MESSAGE);}
}
