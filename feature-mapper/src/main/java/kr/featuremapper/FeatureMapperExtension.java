package kr.featuremapper;

import burp.api.montoya.*;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.core.ByteArray;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.tree.*;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Standalone Montoya entry point; can be installed alongside Workbench. */
public final class FeatureMapperExtension implements BurpExtension,HttpHandler {
    volatile Mapper mapper;
    BrowserBridge bridge;
    private MontoyaApi api;
    private final Object projectLock=new Object();
    private final ConcurrentHashMap<Integer,Long> starts=new ConcurrentHashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicLong revision=new AtomicLong(),errors=new AtomicLong();
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"feature-mapper-files");t.setDaemon(true);return t;});
    private JPanel root;
    private final JLabel status=new JLabel("사이트를 사용하면 기능을 자동으로 분류합니다.");
    private final JCheckBox scopeOnly=new JCheckBox("Burp Scope만 수집",false);
    private final JCheckBox enabled=new JCheckBox("자동 수집",true);
    private final AtomicBoolean collect=new AtomicBoolean(true),scope=new AtomicBoolean(false);
    private final DefaultMutableTreeNode treeRoot=new DefaultMutableTreeNode("자동 발견 기능");
    private final DefaultTreeModel treeModel=new DefaultTreeModel(treeRoot);
    private final JTree tree=new JTree(treeModel);
    private final DefaultTableModel rows=new DefaultTableModel(new String[]{"NO","메서드","URL","상태","세션","실행","매핑"},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(rows);
    private final JTextArea requestText=new JTextArea(),responseText=new JTextArea(),explanation=new JTextArea();
    private final JComboBox<String> runs=new JComboBox<>();
    private final JComboBox<String> sessions=new JComboBox<>();
    private final JTextField search=new JTextField(16);
    private List<Mapper.Packet> shown=List.of();
    private String selectedFeature;
    private javax.swing.Timer timer;
    private long rendered=-1;
    private boolean rebuilding;
    private final AtomicLong selectionGeneration=new AtomicLong();

    public void initialize(MontoyaApi api){
        this.api=api;api.extension().setName("Feature Mapper KR 0.1.0");
        try{
            mapper=new Mapper();bridge=new BrowserBridge(event->{synchronized(projectLock){if(!closed.get()){mapper.browser(event);revision.incrementAndGet();}}});
            if(SwingUtilities.isEventDispatchThread())buildUi();else SwingUtilities.invokeAndWait(this::buildUi);
            api.http().registerHttpHandler(this);api.extension().registerUnloadingHandler(this::unload);
            api.logging().logToOutput("Feature Mapper KR 0.1.0 · 자동 매핑 준비 완료");
        }catch(Exception e){unload();throw new IllegalStateException("Feature Mapper 초기화 실패",e);}
    }
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request){if(!closed.get()){starts.put(request.messageId(),System.currentTimeMillis());if(starts.size()>10000){long cutoff=System.currentTimeMillis()-120000;starts.entrySet().removeIf(e->e.getValue()<cutoff);}}return RequestToBeSentAction.continueWith(request,request.annotations());}
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response){
        Long started=starts.remove(response.messageId());
        if(!closed.get()&&collect.get())try{
            HttpRequest request=response.initiatingRequest();if(!scope.get()||request.isInScope()){
                Mapper.Input input=new Mapper.Input(started==null?System.currentTimeMillis():started,response.toolSource().toolType().toString(),request.method(),request.url(),response.statusCode(),request.toByteArray().getBytes(),response.toByteArray().getBytes(),request.body().getBytes(),response.body().getBytes(),headerMap(request.headers()),headerMap(response.headers()));
                synchronized(projectLock){if(!closed.get()){mapper.accept(input);revision.incrementAndGet();}}
            }
        }catch(Exception error){failure("패킷 보관·매핑",error);}
        return ResponseReceivedAction.continueWith(response,response.annotations());
    }
    private static Map<String,String> headerMap(List<burp.api.montoya.http.message.HttpHeader> headers){Map<String,String> out=new LinkedHashMap<>();for(var h:headers)out.merge(h.name().toLowerCase(Locale.ROOT),h.value(),(a,b)->a+"; "+b);return out;}
    private void failure(String stage,Exception error){long n=errors.incrementAndGet();if(n<=3||(n&(n-1))==0)try{api.logging().logToError("Feature Mapper · "+stage+" · "+error.getClass().getSimpleName()+" · "+n+"회");}catch(Exception ignored){}SwingUtilities.invokeLater(()->status.setText("확인 필요 · "+stage+" · "+error.getClass().getSimpleName()+" · 오류 "+n));}
    private JButton button(String name,Runnable action){JButton b=new JButton(name);b.addActionListener(e->{try{action.run();}catch(Exception ex){failure(name,ex);}});return b;}
    private void buildUi(){
        root=new JPanel(new BorderLayout(8,8));root.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        JPanel toolbar=new JPanel(new FlowLayout(FlowLayout.LEFT));enabled.addActionListener(e->collect.set(enabled.isSelected()));scopeOnly.addActionListener(e->scope.set(scopeOnly.isSelected()));
        for(Component c:List.of(enabled,scopeOnly,button("프로젝트 저장",()->chooseFile(true)),button("프로젝트 열기",()->chooseFile(false)),button("새 프로젝트",this::newProject),button("브라우저 연결",this::showBridge)))toolbar.add(c);
        root.add(toolbar,BorderLayout.NORTH);
        tree.setRootVisible(true);DefaultTreeCellRenderer tr=new DefaultTreeCellRenderer();tr.putClientProperty("html.disable",true);tree.setCellRenderer(tr);tree.addTreeSelectionListener(e->{if(rebuilding)return;Object selected=tree.getLastSelectedPathComponent();if(selected instanceof DefaultMutableTreeNode node&&node.getUserObject() instanceof FeatureNode f)selectedFeature=f.id;else selectedFeature=null;fillRows();});
        JPanel left=new JPanel(new BorderLayout(4,4));left.add(new JScrollPane(tree));JPanel actions=new JPanel(new GridLayout(0,1,4,4));actions.add(button("기능 이름·상태·메모",this::editFeature));actions.add(button("선택 패킷 분류 수정",this::movePackets));left.add(actions,BorderLayout.SOUTH);
        table.setAutoCreateRowSorter(true);table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);DefaultTableCellRenderer renderer=new DefaultTableCellRenderer();renderer.putClientProperty("html.disable",true);table.setDefaultRenderer(Object.class,renderer);table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())showSelected();});
        JPanel filters=new JPanel(new FlowLayout(FlowLayout.LEFT));filters.add(new JLabel("실행"));filters.add(runs);filters.add(new JLabel("세션"));filters.add(sessions);filters.add(new JLabel("검색"));filters.add(search);filters.add(button("Repeater에 준비",this::sendToRepeater));runs.addActionListener(e->{if(!rebuilding)fillRows();});sessions.addActionListener(e->{if(!rebuilding)fillRows();});search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){fillRows();}public void removeUpdate(javax.swing.event.DocumentEvent e){fillRows();}public void changedUpdate(javax.swing.event.DocumentEvent e){fillRows();}});
        for(JTextArea a:List.of(requestText,responseText,explanation)){a.setEditable(false);a.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));}explanation.setLineWrap(true);explanation.setWrapStyleWord(true);
        JSplitPane packets=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,scroll("요청 원본 미리보기",requestText),scroll("응답 원본 미리보기",responseText));packets.setResizeWeight(.5);
        JTabbedPane details=new JTabbedPane();details.addTab("요청·응답",packets);details.addTab("매핑 근거",new JScrollPane(explanation));
        JPanel top=new JPanel(new BorderLayout());top.add(filters,BorderLayout.NORTH);top.add(new JScrollPane(table));JSplitPane main=new JSplitPane(JSplitPane.VERTICAL_SPLIT,top,details);main.setResizeWeight(.55);
        JSplitPane all=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,left,main);all.setResizeWeight(.23);all.setDividerLocation(300);root.add(all);root.add(status,BorderLayout.SOUTH);
        api.userInterface().applyThemeToComponent(root);api.userInterface().registerSuiteTab("기능 자동 매핑",root);
        timer=new javax.swing.Timer(500,e->{try{if(revision.get()!=rendered)refresh();}catch(Exception ex){failure("화면 갱신",ex);}});timer.start();refresh();
    }
    private JPanel scroll(String title,JTextArea a){JPanel p=new JPanel(new BorderLayout());p.add(new JLabel(title),BorderLayout.NORTH);p.add(new JScrollPane(a));return p;}
    record FeatureNode(String id,String label){public String toString(){return label;}}
    private void refresh(){
        if(closed.get())return;rendered=revision.get();rebuilding=true;treeRoot.removeAllChildren();Map<String,DefaultMutableTreeNode> parents=new LinkedHashMap<>();DefaultMutableTreeNode keep=null;
        synchronized(projectLock){for(Mapper.Feature f:mapper.featureList()){List<Mapper.Packet> ps=mapper.packetList(f.id);String key=f.origin+" / "+f.entity;DefaultMutableTreeNode parent=parents.computeIfAbsent(key,k->{var node=new DefaultMutableTreeNode(k);treeRoot.add(node);return node;});var node=new DefaultMutableTreeNode(new FeatureNode(f.id,f.name+" ["+ps.size()+"건 · "+f.state+"]"));parent.add(node);if(f.id.equals(selectedFeature))keep=node;}}
        treeModel.reload();for(int i=0;i<tree.getRowCount();i++)tree.expandRow(i);if(keep!=null)tree.setSelectionPath(new TreePath(keep.getPath()));rebuilding=false;fillRows();
        try{status.setText("보관 "+mapper.count()+"건 · 기능 "+mapper.featureList().size()+"개 · 디스크 "+mapper.diskBytes()+" bytes · 오류 "+errors.get());}catch(IOException e){failure("저장 크기",e);}
    }
    private void fillRows(){
        if(rebuilding||mapper==null)return;List<Long> keepIds=selectedPackets().stream().map(p->p.id).toList();rebuilding=true;
        String run=String.valueOf(runs.getSelectedItem()),session=String.valueOf(sessions.getSelectedItem());List<Mapper.Packet> ps;
        synchronized(projectLock){ps=mapper.packetList(selectedFeature);}Set<String> rs=new LinkedHashSet<>(),ss=new LinkedHashSet<>();rs.add("전체");ss.add("전체");for(var p:ps){rs.add(selectedFeature==null?p.run:p.links.get(selectedFeature));ss.add(p.session);}runs.removeAllItems();rs.forEach(runs::addItem);if(rs.contains(run))runs.setSelectedItem(run);else run="전체";sessions.removeAllItems();ss.forEach(sessions::addItem);if(ss.contains(session))sessions.setSelectedItem(session);else session="전체";
        rows.setRowCount(0);List<Mapper.Packet> visible=new ArrayList<>();String term=search.getText().toLowerCase(Locale.ROOT);
        for(var p:ps){String r=selectedFeature==null?p.run:p.links.get(selectedFeature);if(!run.equals("전체")&&!r.equals(run)||!session.equals("전체")&&!p.session.equals(session)||!(p.method+" "+p.url).toLowerCase(Locale.ROOT).contains(term))continue;visible.add(p);rows.addRow(new Object[]{p.id,p.method,p.url,p.status,p.session,r,p.confidence});}shown=List.copyOf(visible);for(int i=0;i<shown.size();i++)if(keepIds.contains(shown.get(i).id)){int view=table.convertRowIndexToView(i);if(view>=0)table.addRowSelectionInterval(view,view);}rebuilding=false;if(!keepIds.isEmpty()&&selectedPackets().isEmpty())showSelected();
    }
    private List<Mapper.Packet> selectedPackets(){List<Mapper.Packet> out=new ArrayList<>();for(int index:table.getSelectedRows()){int model=table.convertRowIndexToModel(index);if(model<shown.size())out.add(shown.get(model));}return out;}
    private void showSelected(){if(rebuilding)return;long generation=selectionGeneration.incrementAndGet();List<Mapper.Packet> selected=selectedPackets();if(selected.isEmpty()){requestText.setText("");responseText.setText("");explanation.setText("");return;}Mapper.Packet p=selected.get(0);io.execute(()->{try{byte[][] raw;synchronized(projectLock){raw=mapper.raw(p.id);}String q=preview(raw[0]),s=preview(raw[1]);SwingUtilities.invokeLater(()->{if(generation!=selectionGeneration.get()||closed.get())return;requestText.setText(q);responseText.setText(s);requestText.setCaretPosition(0);responseText.setCaretPosition(0);explanation.setText("매핑: "+p.confidence+"\n동작: "+p.operation+"\n"+String.join("\n",p.reasons)+"\n\n매핑은 기능 추정이며 취약점 판정이 아닙니다.");});}catch(Exception e){failure("원본 읽기",e);}});}
    private static String preview(byte[] b){int n=Math.min(b.length,128*1024);return new String(b,0,n,StandardCharsets.ISO_8859_1)+(n<b.length?"\n\n[미리보기 생략 · 전체 원본은 저장·Repeater에서 사용]":"");}
    private void sendToRepeater(){List<Mapper.Packet> selected=selectedPackets();io.execute(()->{for(var p:selected)try{byte[][] raw;synchronized(projectLock){raw=mapper.raw(p.id);}URI u=URI.create(p.url);HttpService service=HttpService.httpService(u.getHost(),u.getPort()<0?(u.getScheme().equals("https")?443:80):u.getPort(),u.getScheme().equals("https"));api.repeater().sendToRepeater(HttpRequest.httpRequest(service,ByteArray.byteArray(raw[0])),"기능 매핑 / "+p.operation);}catch(Exception e){failure("Repeater 준비",e);}});}
    private void showBridge(){JTextArea text=new JTextArea("연결 주소: "+bridge.baseUrl()+"\n연결 토큰: "+bridge.token+"\n\n브라우저 보조 확장의 연결 설정에 입력하세요.\n브라우저 보조 확장에서 분석할 사이트를 활성화한 뒤 새로고침합니다.",7,70);text.setEditable(false);text.setLineWrap(true);JOptionPane.showMessageDialog(root,new JScrollPane(text),"브라우저 연결",JOptionPane.INFORMATION_MESSAGE);}
    private void chooseFile(boolean save){JFileChooser chooser=new JFileChooser();chooser.setDialogTitle(save?"원본 패킷 포함 프로젝트 저장 (.fmkr)":"프로젝트 열기 (.fmkr)");chooser.setSelectedFile(new File("feature-mapper.fmkr"));if((save?chooser.showSaveDialog(root):chooser.showOpenDialog(root))!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();status.setText("프로젝트 "+(save?"저장":"불러오기")+" 중…");io.execute(()->{try{if(save){synchronized(projectLock){ProjectIO.save(mapper,path);}}else{Mapper loaded=ProjectIO.load(path);synchronized(projectLock){if(closed.get()){loaded.close();return;}Mapper old=mapper;mapper=loaded;old.close();selectionGeneration.incrementAndGet();revision.incrementAndGet();}}SwingUtilities.invokeLater(()->{selectedFeature=null;refresh();status.setText("프로젝트 "+(save?"저장":"불러오기")+" 완료 · 원본 패킷 포함");});}catch(Exception e){failure("프로젝트 파일",e);}});}
    private void newProject(){if(JOptionPane.showConfirmDialog(root,"저장하지 않은 수집 내용을 비우고 새 프로젝트를 시작할까요?","새 프로젝트",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;io.execute(()->{try{Mapper next=new Mapper();synchronized(projectLock){if(closed.get()){next.close();return;}Mapper old=mapper;mapper=next;old.close();selectionGeneration.incrementAndGet();revision.incrementAndGet();}SwingUtilities.invokeLater(()->{selectedFeature=null;refresh();});}catch(Exception e){failure("새 프로젝트",e);}});}
    private void editFeature(){if(selectedFeature==null)return;Mapper.Feature f=mapper.features.get(selectedFeature);if(f==null)return;JTextField name=new JTextField(f.name,35);JComboBox<String> state=new JComboBox<>(new String[]{"미점검","진행 중","완료","재확인"});state.setSelectedItem(f.state);JTextArea note=new JTextArea(f.note,6,35);JPanel form=new JPanel(new GridLayout(0,1));form.add(new JLabel("기능 이름"));form.add(name);form.add(state);form.add(new JScrollPane(note));if(JOptionPane.showConfirmDialog(root,form,"기능 기록",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){synchronized(projectLock){mapper.edit(f.id,name.getText(),String.valueOf(state.getSelectedItem()),note.getText());revision.incrementAndGet();}}}
    private void movePackets(){List<Mapper.Packet> ps=selectedPackets();if(ps.isEmpty())return;List<Mapper.Feature> options=mapper.featureList().stream().filter(f->f.origin.equals(ps.get(0).origin)).toList();FeatureNode[] choices=options.stream().map(f->new FeatureNode(f.id,f.name)).toArray(FeatureNode[]::new);Object chosen=JOptionPane.showInputDialog(root,"선택 패킷을 연결할 기능 · 다음 동일 요청에도 수정 규칙 적용","분류 수정",JOptionPane.QUESTION_MESSAGE,null,choices,choices[0]);if(chosen instanceof FeatureNode f){synchronized(projectLock){for(var p:ps)mapper.move(p.id,f.id);revision.incrementAndGet();}}}
    private void unload(){if(!closed.compareAndSet(false,true))return;if(bridge!=null)bridge.close();io.shutdownNow();starts.clear();synchronized(projectLock){if(mapper!=null)try{mapper.close();}catch(IOException ignored){}}SwingUtilities.invokeLater(()->{if(timer!=null)timer.stop();});}
}
