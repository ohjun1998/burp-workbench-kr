package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.table.*;

/** Asynchronous disk-backed view. Original row indexes and packets never change. */
final class TrafficBrowser implements AutoCloseable {
  static final int SEARCH_BYTES=2*1024*1024;
  record Identity(String origin,String label,Map<String,String> headers){Identity{
    Map<String,String> normalized=new TreeMap<>();
    headers.forEach((name,value)->{String key=name.strip().toLowerCase(Locale.ROOT);String prior=normalized.putIfAbsent(key,value.strip());if(prior!=null&&!prior.equals(value.strip()))throw new IllegalArgumentException("중복 인증 헤더의 값이 다릅니다.");});
    headers=Map.copyOf(normalized);
  }}
  record Mark(boolean star,String note){}
  record Group(int first,List<Integer> members,String path,String statuses,String key){}
  record View(BitSet rows,Map<Integer,Group> groups,Map<Integer,String> accounts,int count,int partial,int matched){}
  record SearchKey(DiskTrafficStore disk,TrafficQuery query,String text,List<Identity> profiles,long marksVersion){}
  record SearchSnapshot(SearchKey key,View view,int count,long expiresAt){}
  record SearchMetrics(boolean incremental,int examinedRows,int reusedRows,int totalRows,long elapsedNanos){}
  private record ScanResult(View view,int examinedRows,long expiresAt,BitSet changedRows){}
  private volatile SearchSnapshot snapshot;
  private volatile SearchKey runningKey;
  private final AtomicLong marksVersion=new AtomicLong();
  final AtomicLong evaluatedRows=new AtomicLong();
  volatile SearchMetrics lastMetrics=new SearchMetrics(false,0,0,0,0);
  LongSupplier clock=System::currentTimeMillis;
  private final RowFilter<CaptureTableModel,Integer> visibleFilter=new RowFilter<>(){
    @Override public boolean include(Entry<? extends CaptureTableModel,? extends Integer> entry){View current=view;return current!=null&&current.rows().get(entry.getIdentifier());}
  };
  final JTable table;final CaptureTableModel model;final JTextField search;final MontoyaApi api;
  final Supplier<DiskTrafficStore> store;final Runnable changed;
  Supplier<List<Identity>> identities=List::of;
  final JButton settingsButton=WorkspaceIcons.button("gear","wb.traffic.search.settings","검색·목록 설정");
  final JButton cancel=new JButton("검색 취소");
  final JLabel feedback=new JLabel(" ");
  final TargetWindow settings;
  final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"Workbench-traffic-search");t.setDaemon(true);return t;});
  final AtomicLong generation=new AtomicLong();final AtomicBoolean busy=new AtomicBoolean();
  final Map<String,Mark> marks=new ConcurrentHashMap<>();
  final Set<String> expanded=new HashSet<>();
  volatile boolean closed;private boolean paused;volatile View view;volatile int scanned=-1;volatile TrafficQuery query=TrafficQuery.defaults();
  volatile String text="";private Future<?> pending;
  private final javax.swing.Timer debounce;
  final JComboBox<String> method=new JComboBox<>(new String[]{"전체","GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS"});
  final JComboBox<String> response=new JComboBox<>(new String[]{"전체","2xx","3xx","4xx","5xx","200","201","204","301","302","400","401","403","404","429","500"});
  final JToggleButton[] ranges={new JToggleButton("목록"),new JToggleButton("요청 포함"),new JToggleButton("전체 패킷")};
  final JCheckBox group=new JCheckBox("비슷한 요청 묶기"),important=new JCheckBox("중요 표시만 보기"),normalize=new JCheckBox("숫자·UUID 경로 묶기",true),memo=new JCheckBox("메모 열 표시",true),sensitive=new JCheckBox("대소문자 구분"),regex=new JCheckBox("정규식");
  final JTextField account=new JTextField(12);final JComboBox<String> period=new JComboBox<>(new String[]{"전체","최근 5분","최근 30분","최근 1시간","최근 24시간"});
  final JPanel advanced=new JPanel(new GridLayout(0,1,0,4));
  final JToggleButton advancedToggle=new JToggleButton("▸ 고급 설정");
  private final Set<Integer> hidden=new HashSet<>();

  TrafficBrowser(JTable table,CaptureTableModel model,JTextField search,MontoyaApi api,Supplier<DiskTrafficStore> store,Runnable changed){
    this.table=table;this.model=model;this.search=search;this.api=api;this.store=store;this.changed=changed;
    try{if(api!=null&&api.persistence()!=null)query=TrafficQuery.read(api.persistence().extensionData().getString(BuildInfo.storageKey("traffic-view-v1")));}catch(Exception ignored){}
    settings=new TargetWindow(table,"검색·목록 설정",this::content);settings.opener=TrafficBrowser::window;settingsButton.addActionListener(e->{loadControls();settings.open();});
    debounce=new javax.swing.Timer(220,e->begin());debounce.setRepeats(false);
    cancel.setName("wb.traffic.search.cancel");cancel.addActionListener(e->{generation.incrementAndGet();debounce.stop();cancelPending();paused=true;busy.set(false);feedback.setText("검색 취소 · 이전 결과 유지");});
    search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){schedule();}public void removeUpdate(javax.swing.event.DocumentEvent e){schedule();}public void changedUpdate(javax.swing.event.DocumentEvent e){schedule();}});
    model.extra=(row,col)->extra(row,col);
    table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){int row=table.rowAtPoint(e.getPoint()),col=table.columnAtPoint(e.getPoint());if(row<0||col<0||!SwingUtilities.isLeftMouseButton(e))return;int raw=table.convertRowIndexToModel(row);if(table.convertColumnIndexToModel(col)==7){toggleStar(raw);return;}if(e.getClickCount()==2&&query.group()){toggleGroup(raw);}}});
    method.setPreferredSize(new Dimension(120,32));response.setPreferredSize(new Dimension(120,32));period.setPreferredSize(new Dimension(150,32));
    loadControls();
  }
  private void cancelPending(){if(pending!=null)pending.cancel(true);worker.getQueue().clear();}
  void schedule(){if(closed)return;generation.incrementAndGet();cancelPending();paused=false;busy.set(false);scanned=-1;debounce.restart();}
  void refresh(){
    if(closed||paused||debounce.isRunning())return;
    DiskTrafficStore disk=store.get();if(disk==null)return;
    SearchKey key=key(disk,search.getText().strip(),List.copyOf(identities.get()));
    if(busy.get()){
      // Account credentials/labels can change without a document event or a new packet.
      if(runningKey!=null&&!runningKey.equals(key))schedule();
      return;
    }
    SearchSnapshot prior=snapshot;
    if(prior==null||!prior.key().equals(key)||prior.count()!=disk.size()||clock.getAsLong()>=prior.expiresAt())begin();
  }
  boolean idle(){return !busy.get()&&!debounce.isRunning();}
  private SearchKey key(DiskTrafficStore disk,String requested,List<Identity> profiles){return new SearchKey(disk,query,requested,profiles,marksVersion.get());}
  private boolean stillCurrent(SearchKey key){return key.equals(key(store.get(),search.getText().strip(),List.copyOf(identities.get())));}
  private void begin(){
    if(closed)return;debounce.stop();cancelPending();busy.set(false);String requested=search.getText().strip();if(requested.length()>512){paused=true;feedback.setText("검색어 512자 제한 · 이전 결과 유지");return;}
    text=requested;TrafficQuery settings=query;
    if(settings.regex()&&!requested.isBlank())try{java.util.regex.Pattern.compile(requested);}catch(java.util.regex.PatternSyntaxException ex){paused=true;feedback.setText("정규식 오류 · 이전 결과 유지");return;}
    long token=generation.incrementAndGet();DiskTrafficStore disk=store.get();if(disk==null)return;int count=Math.toIntExact(disk.size());
    List<Identity> profiles=List.copyOf(identities.get());Set<String> open=Set.copyOf(expanded);SearchKey key=key(disk,requested,profiles);
    Map<String,Mark> markSnapshot=Map.copyOf(marks);long now=clock.getAsLong();SearchSnapshot prior=snapshot;
    if(requested.isBlank()&&settings.equals(TrafficQuery.defaults())&&profiles.isEmpty()){
      scanned=count;view=null;snapshot=new SearchSnapshot(key,null,count,Long.MAX_VALUE);runningKey=null;busy.set(false);
      lastMetrics=new SearchMetrics(prior!=null&&prior.key().equals(key),0,prior==null?0:Math.min(count,prior.count()),count,0);
      var sorter=(TableRowSorter<?>)table.getRowSorter();if(sorter.getRowFilter()!=null)sorter.setRowFilter(null);
      feedback.setText("전체 "+count+"건");changed.run();return;
    }
    boolean incremental=prior!=null&&prior.view()!=null&&prior.key().equals(key)&&prior.count()<=count&&settings.minutes()==0;
    View previous=incremental?prior.view():null;int from=incremental?prior.count():0;
    runningKey=key;busy.set(true);feedback.setText("검색 중 · "+(incremental?"신규 "+(count-from)+" / 전체 ":"")+count+"건");
    pending=worker.submit(()->{
      long started=System.nanoTime();
      try{
        ScanResult result=evaluate(disk,count,settings,requested,profiles,open,markSnapshot,now,previous,from,()->closed||token!=generation.get()||Thread.currentThread().isInterrupted());
        View next=result.view();SearchMetrics metrics=new SearchMetrics(incremental,result.examinedRows(),from,count,System.nanoTime()-started);
        SwingUtilities.invokeLater(()->{
          if(closed||token!=generation.get()||disk!=store.get())return;
          if(!stillCurrent(key)){schedule();return;}
          view=next;scanned=count;snapshot=new SearchSnapshot(key,next,count,result.expiresAt());lastMetrics=metrics;
          @SuppressWarnings("unchecked") TableRowSorter<CaptureTableModel> sorter=(TableRowSorter<CaptureTableModel>)table.getRowSorter();
          boolean plain=requested.isBlank()&&settings.method().equals("전체")&&settings.status().equals("전체")&&settings.account().isBlank()&&settings.minutes()==0&&!settings.group()&&!settings.important();
          RowFilter<CaptureTableModel,Integer> filter=plain?null:visibleFilter;
          boolean filterChanged=sorter.getRowFilter()!=filter;
          if(filterChanged)sorter.setRowFilter(filter);
          else if(incremental)publishRows(result.changedRows(),sorter);
          else model.fireTableDataChanged();
          feedback.setText("표시 "+next.rows().cardinality()+" / 일치 "+next.matched()+" / 전체 "+count+(next.partial()>0?" · 본문 일부 검사 "+next.partial()+"건 (2 MiB)":""));
          settingsButton.setToolTipText("검색·목록 설정 · "+feedback.getText()+" · "+(incremental?"증분":"전체")+" 평가 "+metrics.examinedRows()+"건 · 이전 결과 재사용 "+metrics.reusedRows()+"건");
          if(filterChanged)table.repaint();changed.run();
        });
      }catch(CancellationException ignored){}catch(Exception ex){SwingUtilities.invokeLater(()->{if(!closed&&token==generation.get()){paused=true;feedback.setText("검색 실패 · 검색 조건을 바꿔 재시도: "+Objects.toString(ex.getMessage(),ex.getClass().getSimpleName()));}});}
      finally{SwingUtilities.invokeLater(()->{if(token==generation.get()){runningKey=null;busy.set(false);}});}
    });
  }
  /** Re-evaluate only changed filter/extra-column rows; avoid full sorter resets on append. */
  private void publishRows(BitSet rows,TableRowSorter<CaptureTableModel> sorter){
    boolean previous=sorter.getSortsOnUpdates();sorter.setSortsOnUpdates(true);
    try{for(int first=rows.nextSetBit(0);first>=0&&first<model.getRowCount();){int end=Math.min(model.getRowCount(),rows.nextClearBit(first))-1;model.fireTableRowsUpdated(first,end);first=rows.nextSetBit(end+1);}}
    finally{sorter.setSortsOnUpdates(previous);}
  }
  View scan(DiskTrafficStore disk,int count,TrafficQuery q,String text,List<Identity> profiles,Set<String> open,BooleanSupplier cancelled){
    return evaluate(disk,count,q,text,List.copyOf(profiles),open,Map.copyOf(marks),clock.getAsLong(),null,0,cancelled).view();
  }
  private ScanResult evaluate(DiskTrafficStore disk,int count,TrafficQuery q,String text,List<Identity> profiles,Set<String> open,Map<String,Mark> markSnapshot,long now,View previous,int from,BooleanSupplier cancelled){
    BitSet visible=previous==null?new BitSet():(BitSet)previous.rows().clone();
    Map<String,List<Integer>> members=new LinkedHashMap<>();Map<Integer,String> labels=previous==null?new HashMap<>():new HashMap<>(previous.accounts());
    Map<Integer,Group> groups=previous==null?new HashMap<>():new HashMap<>(previous.groups());
    Map<String,Group> existing=new HashMap<>();for(Group item:groups.values())existing.put(item.key(),item);
    Map<String,String> groupPaths=new HashMap<>();Map<String,Set<String>> statuses=new HashMap<>();
    BitSet dirty=new BitSet();if(from<count)dirty.set(from,count);
    int partial=previous==null?0:previous.partial(),matched=previous==null?0:previous.matched(),examined=0;long expiresAt=Long.MAX_VALUE;
    for(int row=from;row<count;row++){
      if(cancelled.getAsBoolean())throw new CancellationException();evaluatedRows.incrementAndGet();examined++;
      var ref=disk.ref(row);Mark mark=markSnapshot.getOrDefault(ref.id(),new Mark(false,""));
      if(q.important()&&!mark.star())continue;
      if(!q.includesTime(ref.capturedAt(),now))continue;
      expiresAt=Math.min(expiresAt,q.expiresAt(ref.capturedAt()));
      byte[] prefix=profiles.isEmpty()&&!q.group()?null:ref.requestPrefix(65536);String who="미매핑",identity="";
      if(prefix!=null){String wire=HttpText.decode(prefix,null);boolean complete=wire.contains("\r\n\r\n")||wire.contains("\n\n");String header=wire.split("\\r?\\n\\r?\\n",2)[0];identity=complete?sessionKey(header):"unresolved:"+ref.id();if(complete)who=label(header,ref.url(),profiles);}
      if(!who.equals("미매핑"))labels.put(row,who);
      if(!q.matchesMeta(ref.method(),ref.url(),ref.responseStatus(),who,"",cancelled))continue;
      boolean found=q.matchesMeta(ref.method(),ref.url(),ref.responseStatus(),who,text,cancelled);
      if(!found&&q.range()>0){
        String request=HttpText.decode(ref.requestPrefix(SEARCH_BYTES),null);if(ref.requestLength()>SEARCH_BYTES)partial++;
        found=q.matchesText(request,text,cancelled);
        if(!found&&q.range()==2&&ref.hasResponse()){
          byte[] bytes=ref.responsePrefix(SEARCH_BYTES);String raw=HttpText.decode(bytes,null);if(ref.responseLength()>SEARCH_BYTES)partial++;
          found=q.matchesText(raw,text,cancelled);
          if(!found&&ref.responseLength()<=SEARCH_BYTES){String decoded=null;try{var response=burp.api.montoya.http.message.responses.HttpResponse.httpResponse(burp.api.montoya.core.ByteArray.byteArray(bytes));decoded=JsBodyDecoder.decode(response.body().getBytes(),response.headerValue("Content-Encoding"),response.headerValue("Content-Type"));}catch(Exception ignored){}if(decoded!=null)found=q.matchesText(decoded,text,cancelled);}
        }
      }
      if(!found)continue;matched++;
      if(!q.group()){visible.set(row);continue;}
      String key=q.groupKey(ref.method(),ref.url(),identity);members.computeIfAbsent(key,k->new ArrayList<>()).add(row);groupPaths.putIfAbsent(key,q.groupPath(ref.url()));statuses.computeIfAbsent(key,k->new TreeSet<>()).add(ref.responseStatus());
    }
    for(var item:members.entrySet()){
      Group old=existing.get(item.getKey());List<Integer> combined=new ArrayList<>();Set<String> codes=new TreeSet<>();
      if(old!=null){combined.addAll(old.members());codes.addAll(Arrays.asList(old.statuses().split(", ")));}
      combined.addAll(item.getValue());codes.addAll(statuses.get(item.getKey()));List<Integer> rows=List.copyOf(combined);int first=rows.get(0);
      Group next=new Group(first,rows,old==null?groupPaths.get(item.getKey()):old.path(),String.join(", ",codes),item.getKey());groups.put(first,next);dirty.set(first);
    }
    if(q.group()){
      visible.clear();
      for(Group item:groups.values()){if(cancelled.getAsBoolean())throw new CancellationException();visible.set(item.first());boolean expanded=open.contains(item.key());if(previous!=null&&item.members().size()>1&&previous.rows().get(item.members().get(1))!=expanded)dirty.set(item.first());if(expanded)for(int row:item.members())visible.set(row);}
    }
    if(previous!=null){BitSet visibility=(BitSet)previous.rows().clone();visibility.xor(visible);dirty.or(visibility);}
    if(cancelled.getAsBoolean())throw new CancellationException();
    return new ScanResult(new View(visible,Map.copyOf(groups),Map.copyOf(labels),count,partial,matched),examined,expiresAt,dirty);
  }
  static String sessionKey(String header){
    List<String> values=new ArrayList<>();authHeaders(header).forEach((name,items)->items.forEach(value->values.add(name+":"+value)));Collections.sort(values);return JsMiner.hash(String.join("\n",values));
  }
  private static Map<String,List<String>> authHeaders(String header){
    Map<String,List<String>> values=new HashMap<>();for(String line:header.split("\\r?\\n")){int c=line.indexOf(':');if(c<0)continue;String name=line.substring(0,c).strip().toLowerCase(Locale.ROOT);if(AuthComparePanel.DEFAULT_AUTH.contains(name))values.computeIfAbsent(name,key->new ArrayList<>()).add(line.substring(c+1).strip());}return values;
  }
  static String label(String header,String url,List<Identity> profiles){
    Map<String,List<String>> actual=authHeaders(header);
    Set<String> matches=new HashSet<>();for(var profile:profiles){if(!Objects.equals(JsTrafficIndex.key(profile.origin()+"/"),JsTrafficIndex.key(AssessmentTracker.origin(url)+"/")))continue;boolean has=false,ok=true;for(var h:profile.headers().entrySet()){if(!AuthComparePanel.DEFAULT_AUTH.contains(h.getKey()))continue;has=true;if(!Objects.equals(List.of(h.getValue()),actual.get(h.getKey())))ok=false;}if(has&&ok)matches.add(profile.label());}return matches.size()==1?matches.iterator().next():"미매핑";
  }
  Object extra(int row,int column){
    var ref=store.get().ref(row);Mark mark=marks.getOrDefault(ref.id(),new Mark(false,""));View v=view;Group g=v==null?null:v.groups().get(row);
    return switch(column){case 7->mark.star()?"★":"☆";case 8->v==null?"미매핑":v.accounts().getOrDefault(row,"미매핑");case 9->g==null?1:g.members().size();case 10->mark.note();default->"";};
  }
  String displayUrl(int raw,String original){View v=view;Group g=v==null?null:v.groups().get(raw);return g!=null&&g.members().size()>1?(expanded.contains(g.key())?"▾ ":"▸ ")+g.path():original;}
  void toggleStar(int raw){var ref=store.get().ref(raw);marks.compute(ref.id(),(k,v)->new Mark(v==null||!v.star(),v==null?"":v.note()));marksVersion.incrementAndGet();schedule();model.fireTableRowsUpdated(raw,raw);}
  void note(int raw,String note){if(note.length()>1000)throw new IllegalArgumentException("메모 1,000자 제한");var ref=store.get().ref(raw);marks.compute(ref.id(),(k,v)->new Mark(v!=null&&v.star(),note));marksVersion.incrementAndGet();schedule();model.fireTableRowsUpdated(raw,raw);}
  void toggleGroup(int raw){View v=view;if(v==null)return;Group g=v.groups().get(raw);if(g==null)return;if(!expanded.add(g.key()))expanded.remove(g.key());schedule();}
  JPanel content(){
    JPanel body=new JPanel();body.setName("wb.traffic.search.content");body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS));body.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
    body.add(section("검색 조건"));body.add(WorkbenchUi.row(new JLabel("메서드"),method,new JLabel("상태"),response));
    body.add(section("검색 범위"));ButtonGroup choices=new ButtonGroup();JPanel range=new JPanel(new GridLayout(1,3,0,0));for(int i=0;i<3;i++){ranges[i].setName("wb.traffic.range."+i);choices.add(ranges[i]);range.add(ranges[i]);}range.setMaximumSize(new Dimension(Integer.MAX_VALUE,34));body.add(range);
    body.add(section("목록 정리"));body.add(group);body.add(important);
    advancedToggle.setName("wb.traffic.search.advanced");advancedToggle.setHorizontalAlignment(SwingConstants.LEFT);body.add(advancedToggle);
    advanced.removeAll();advanced.add(WorkbenchUi.row(new JLabel("계정"),account,new JLabel("기간"),period));advanced.add(WorkbenchUi.row(sensitive,regex));advanced.add(WorkbenchUi.row(normalize,memo));advanced.setVisible(advancedToggle.isSelected());body.add(advanced);
    for(ActionListener l:advancedToggle.getActionListeners())advancedToggle.removeActionListener(l);
    advancedToggle.addActionListener(e->{advanced.setVisible(advancedToggle.isSelected());advancedToggle.setText((advanced.isVisible()?"▾ ":"▸ ")+"고급 설정");body.revalidate();});
    JButton reset=new JButton("초기화"),apply=new JButton("적용"),close=new JButton("닫기");reset.setName("wb.traffic.search.reset");apply.setName("wb.traffic.search.apply");
    reset.addActionListener(e->{query=TrafficQuery.defaults();loadControls();search.setText("");apply();});apply.addActionListener(e->{try{apply();settings.closeWindow();}catch(Exception ex){feedback.setText(ex.getMessage());}});close.addActionListener(e->settings.closeWindow());
    feedback.setName("wb.traffic.search.progress");body.add(feedback);
    JPanel shell=new JPanel(new BorderLayout(0,8));shell.setName("wb.traffic.search.window");shell.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));JScrollPane scroll=new JScrollPane(body);scroll.setBorder(null);scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);shell.add(scroll);shell.add(WorkbenchUi.row(reset,cancel,close,apply),BorderLayout.SOUTH);WorkbenchUi.style(shell);return shell;
  }
  static JDialog window(Component owner,String title,Component content,boolean modal,Runnable closed){
    if(GraphicsEnvironment.isHeadless())return null;JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(owner),title,Dialog.ModalityType.MODELESS);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setContentPane((Container)content);dialog.setSize(550,440);dialog.setMinimumSize(new Dimension(480,360));dialog.setLocationRelativeTo(owner);dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){closed.run();}});dialog.setVisible(true);return dialog;
  }
  private JLabel section(String text){JLabel l=new JLabel(text);l.setBorder(BorderFactory.createEmptyBorder(12,0,6,0));return l;}
  void loadControls(){TrafficQuery q=query;method.setSelectedItem(q.method());response.setSelectedItem(q.status());for(int i=0;i<3;i++)ranges[i].setSelected(i==q.range());group.setSelected(q.group());important.setSelected(q.important());normalize.setSelected(q.normalize());memo.setSelected(q.memo());sensitive.setSelected(q.sensitive());regex.setSelected(q.regex());account.setText(q.account());period.setSelectedIndex(q.minutes()==5?1:q.minutes()==30?2:q.minutes()==60?3:q.minutes()==1440?4:0);}
  void apply(){int range=0;for(int i=0;i<3;i++)if(ranges[i].isSelected())range=i;TrafficQuery next=new TrafficQuery(method.getSelectedItem().toString(),response.getSelectedItem().toString(),range,sensitive.isSelected(),regex.isSelected(),group.isSelected(),normalize.isSelected(),important.isSelected(),memo.isSelected(),account.getText().strip(),new int[]{0,5,30,60,1440}[period.getSelectedIndex()]);
    if(next.regex()&&!search.getText().isBlank())java.util.regex.Pattern.compile(search.getText());
    query=next;
    try{if(api!=null&&api.persistence()!=null)api.persistence().extensionData().setString(BuildInfo.storageKey("traffic-view-v1"),MetadataExport.json(query.json()));}catch(Exception ex){feedback.setText("설정 저장 실패 · 현재 창에 적용");}
    updateColumns();schedule();
  }
  void updateColumns(){int view=table.convertColumnIndexToView(10);if(view>=0){var c=table.getColumnModel().getColumn(view);c.setMinWidth(0);c.setMaxWidth(query.memo()?400:0);c.setPreferredWidth(query.memo()?130:0);} }
  void clear(){marks.clear();marksVersion.incrementAndGet();expanded.clear();view=null;snapshot=null;runningKey=null;scanned=-1;generation.incrementAndGet();cancelPending();paused=false;busy.set(false);}
  @Override public void close(){closed=true;snapshot=null;runningKey=null;generation.incrementAndGet();debounce.stop();settings.close();if(pending!=null)pending.cancel(true);worker.shutdownNow();}
}
