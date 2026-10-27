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
  /** Ephemeral navigation/undo state, never written to project metadata. */
  record SearchPosition(TrafficQuery query,String text,String project){}
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
  final JToggleButton freezeView=new JToggleButton("목록 고정");
  final JButton newTraffic=new JButton("새 요청 0건");
  final JButton undoStars=new JButton("중요 변경 취소");
  private boolean viewFrozen;
  private DiskTrafficStore frozenStore;
  private int frozenCount;
  static final int MAX_MARK_SELECTION=4096,MAX_MARK_UNDO=20;
  private record StarEdit(DiskTrafficStore disk,String project,Map<Integer,Boolean> before,boolean after){}
  private final Deque<StarEdit> starUndo=new ArrayDeque<>();
  private String annotationStatus="";
  final JLabel feedback=new JLabel(" ");
  final TargetWindow settings;
  final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"Workbench-traffic-search");t.setDaemon(true);return t;});
  final AtomicLong generation=new AtomicLong();final AtomicBoolean busy=new AtomicBoolean();
  final Map<String,Mark> marks=new ConcurrentHashMap<>();
  final Set<String> expanded=new HashSet<>();
  volatile boolean closed;private boolean paused;volatile View view;volatile int scanned=-1;volatile TrafficQuery query=TrafficQuery.defaults();
  volatile String text="";private Future<?> pending;
  private final javax.swing.Timer debounce;
  // A one-shot Swing Timer stops being "running" before its queued EDT action
  // executes. Keep logical pending work until begin/cancel consumes it.
  private boolean debouncePending;
  final JComboBox<String> method=new JComboBox<>(new String[]{"전체","GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS"});
  final JComboBox<String> response=new JComboBox<>(new String[]{"전체","2xx","3xx","4xx","5xx","200","201","204","301","302","400","401","403","404","429","500"});
  final JToggleButton[] ranges={new JToggleButton("목록"),new JToggleButton("요청 포함"),new JToggleButton("전체 패킷")};
  final JCheckBox group=new JCheckBox("비슷한 요청 묶기"),important=new JCheckBox("중요 표시만 보기"),normalize=new JCheckBox("숫자·UUID 경로 묶기",true),memo=new JCheckBox("메모 열 표시",true),sensitive=new JCheckBox("대소문자 구분"),regex=new JCheckBox("정규식");
  final JTextField account=new JTextField(12);final JComboBox<String> period=new JComboBox<>(new String[]{"전체","최근 5분","최근 30분","최근 1시간","최근 24시간"});
  final JPanel advanced=new JPanel(new GridLayout(0,1,0,4));
  final JToggleButton advancedToggle=new JToggleButton("▸ 고급 설정");
  private final Set<Integer> hidden=new HashSet<>();
  private final JPanel conditionBar=new JPanel(new BorderLayout(0,0));
  private final JPanel conditionChips=new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.LEFT,5,2));
  private final JComboBox<String> savedConditions=new JComboBox<>();
  private final JButton presetApply=new JButton("조건 적용"),presetSave=new JButton("조건 저장"),presetDelete=new JButton("삭제");
  private final JLabel listFeedback=new JLabel(" ");
  final JLabel inputProblem=new JLabel(),settingsProblem=new JLabel();
  final JComboBox<RelaxChoice> relaxChoices=new JComboBox<>();
  final JButton relaxCondition=new JButton("이 조건만 해제"),undoRelaxation=new JButton("이전 조건으로");
  final JPanel recoveryActions=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));
  private JButton settingsApply;
  private SearchPosition relaxedBefore,relaxedAfter;
  private final AtomicLong contextRevision=new AtomicLong();
  private PacketInspector linkedRequest,linkedResponse;
  record RelaxChoice(String key,String label){@Override public String toString(){return label;}}
  BackgroundJobs backgroundJobs;
  private List<TrafficSearchPresets.Preset> presets=List.of();
  private String projectIdentity="",settingsProject="",storedPresets;
  private boolean projectLoaded,presetLoadFailure;
  private Runnable searchStateChanged=()->{};
  private Runnable viewPublished=()->{};

  TrafficBrowser(JTable table,CaptureTableModel model,JTextField search,MontoyaApi api,Supplier<DiskTrafficStore> store,Runnable changed){
    this.table=table;this.model=model;this.search=search;this.api=api;this.store=store;this.changed=changed;
    try{if(api!=null&&api.persistence()!=null)query=TrafficQuery.read(api.persistence().extensionData().getString(BuildInfo.storageKey("traffic-view-v1")));}catch(Exception ignored){}
    settings=new TargetWindow(table,"검색·목록 설정",this::content);settings.opener=TrafficBrowser::window;settingsButton.addActionListener(e->{ensureProjectState();settingsProject=projectIdentity;loadControls();settings.open();});
    debounce=new javax.swing.Timer(220,e->begin());debounce.setRepeats(false);
    cancel.setName("wb.traffic.search.cancel");cancel.addActionListener(e->{generation.incrementAndGet();debounce.stop();debouncePending=false;cancelPending();paused=true;busy.set(false);feedback.setText("검색 취소 · 이전 결과 유지");refreshRecoveryControls();});
    search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){schedule();}public void removeUpdate(javax.swing.event.DocumentEvent e){schedule();}public void changedUpdate(javax.swing.event.DocumentEvent e){schedule();}});
    model.extra=(row,col)->extra(row,col);
    table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){int row=table.rowAtPoint(e.getPoint()),col=table.columnAtPoint(e.getPoint());if(row<0||col<0||!SwingUtilities.isLeftMouseButton(e))return;int raw=table.convertRowIndexToModel(row);if(table.convertColumnIndexToModel(col)==7){toggleStar(raw);return;}if(e.getClickCount()==2&&query.group()){toggleGroup(raw);}}});
    method.setPreferredSize(new Dimension(120,32));response.setPreferredSize(new Dimension(120,32));period.setPreferredSize(new Dimension(150,32));
    regex.addActionListener(e->refreshInputValidation());
    account.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){refreshInputValidation();}public void removeUpdate(javax.swing.event.DocumentEvent e){refreshInputValidation();}public void changedUpdate(javax.swing.event.DocumentEvent e){refreshInputValidation();}});
    freezeView.setName("wb.traffic.view.freeze");newTraffic.setName("wb.traffic.view.apply");undoStars.setName("wb.traffic.stars.undo");
    freezeView.setToolTipText("목록 화면만 고정 · 수집 OFF/SCOPE와 원본 저장은 유지합니다.");
    newTraffic.setToolTipText("쌓인 새 요청을 목록에 반영하고 실시간 갱신을 재개합니다.");
    freezeView.addActionListener(e->setViewFrozen(freezeView.isSelected()));
    newTraffic.addActionListener(e->setViewFrozen(false));
    undoStars.addActionListener(e->presetAction(this::undoStarEdit));
    table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())updateMarkActions();});
    buildConditionBar();ensureProjectState();loadControls();refreshConditionChips();updateLiveControls();updateMarkActions();
  }
  private void cancelPending(){if(pending!=null)pending.cancel(true);worker.getQueue().clear();}
  void schedule(){if(closed)return;invalidateLinkedContexts();generation.incrementAndGet();cancelPending();paused=false;busy.set(false);scanned=-1;debouncePending=true;debounce.restart();refreshConditionChips();refreshInputValidation();refreshRecoveryControls();}
  void refresh(){
    if(closed)return;ensureProjectState();updateLiveControls();if(viewFrozen||paused||debouncePending)return;
    DiskTrafficStore disk=store.get();if(disk==null)return;
    SearchKey key=key(disk,search.getText().strip(),List.copyOf(identities.get()));
    if(busy.get()){
      // Account credentials/labels can change without a document event or a new packet.
      if(runningKey!=null&&!runningKey.equals(key))schedule();
      return;
    }
    SearchSnapshot prior=snapshot;
    if(prior==null||!prior.key().equals(key)||prior.count()!=effectiveCount(disk)||clock.getAsLong()>=prior.expiresAt())begin();
  }
  boolean idle(){return !busy.get()&&!debouncePending;}
  boolean searchViewCurrent(){SearchSnapshot current=snapshot;return current!=null&&stillCurrent(current.key());}
  private SearchKey key(DiskTrafficStore disk,String requested,List<Identity> profiles){return new SearchKey(disk,query,requested,profiles,marksVersion.get());}
  private boolean stillCurrent(SearchKey key){return key.equals(key(store.get(),search.getText().strip(),List.copyOf(identities.get())));}
  private void begin(){
    if(closed)return;ensureProjectState();debounce.stop();debouncePending=false;cancelPending();busy.set(false);String requested=search.getText().strip();if(requested.length()>512){paused=true;feedback.setText("검색어 512자 제한 · 이전 결과 유지");return;}
    text=requested;TrafficQuery settings=query;
    if(settings.regex()&&!requested.isBlank())try{java.util.regex.Pattern.compile(requested);}catch(java.util.regex.PatternSyntaxException ex){paused=true;feedback.setText("정규식 오류 · 이전 결과 유지");return;}
    long token=generation.incrementAndGet();DiskTrafficStore disk=store.get();if(disk==null)return;int count=effectiveCount(disk);
    List<Identity> profiles=List.copyOf(identities.get());Set<String> open=Set.copyOf(expanded);SearchKey key=key(disk,requested,profiles);
    Map<String,Mark> markSnapshot=Map.copyOf(marks);long now=clock.getAsLong();SearchSnapshot prior=snapshot;
    if(prior==null||!prior.key().equals(key))invalidateLinkedContexts();
    if(requested.isBlank()&&settings.equals(TrafficQuery.defaults())&&profiles.isEmpty()){
      scanned=count;view=null;snapshot=new SearchSnapshot(key,null,count,Long.MAX_VALUE);runningKey=null;busy.set(false);
      lastMetrics=new SearchMetrics(prior!=null&&prior.key().equals(key),0,prior==null?0:Math.min(count,prior.count()),count,0);
      var sorter=(TableRowSorter<?>)table.getRowSorter();preserveTablePosition(()->{if(sorter.getRowFilter()!=null)sorter.setRowFilter(null);});
      feedback.setText(count==0?"수집된 트래픽이 없습니다 · SCOPE와 수집 상태를 확인하고 요청을 관찰하세요.":"전체 "+count+"건");changed.run();refreshRecoveryControls();viewPublished.run();return;
    }
    boolean incremental=prior!=null&&prior.view()!=null&&prior.key().equals(key)&&prior.count()<=count&&settings.minutes()==0;
    View previous=incremental?prior.view():null;int from=incremental?prior.count():0;
    runningKey=key;busy.set(true);feedback.setText("검색 중 · "+(incremental?"신규 "+(count-from)+" / 전체 ":"")+count+"건");
    pending=worker.submit(()->{
      BackgroundJobs.Handle job=BackgroundJobs.begin(backgroundJobs,"트래픽 검색","traffic",()->SwingUtilities.invokeLater(()->{if(!closed&&token==generation.get()&&disk==store.get())cancel.doClick();}));
      long started=System.nanoTime();
      try{
        job.progress("수집된 트래픽 검사",from,count);
        ScanResult result=evaluate(disk,count,settings,requested,profiles,open,markSnapshot,now,previous,from,()->closed||token!=generation.get()||Thread.currentThread().isInterrupted(),processed->job.progress("수집된 트래픽 검사",processed,count));
        View next=result.view();SearchMetrics metrics=new SearchMetrics(incremental,result.examinedRows(),from,count,System.nanoTime()-started);
        SwingUtilities.invokeLater(()->{
          if(closed||token!=generation.get()||disk!=store.get()){job.cancelled("검색 교체/종료");return;}
          if(!stillCurrent(key)){job.cancelled("검색 조건 변경");schedule();return;}
          view=next;scanned=count;snapshot=new SearchSnapshot(key,next,count,result.expiresAt());lastMetrics=metrics;
          @SuppressWarnings("unchecked") TableRowSorter<CaptureTableModel> sorter=(TableRowSorter<CaptureTableModel>)table.getRowSorter();
          boolean plain=requested.isBlank()&&settings.method().equals("전체")&&settings.status().equals("전체")&&settings.account().isBlank()&&settings.minutes()==0&&!settings.group()&&!settings.important();
          RowFilter<CaptureTableModel,Integer> filter=plain?null:visibleFilter;
          boolean filterChanged=sorter.getRowFilter()!=filter;
          preserveTablePosition(()->{
            if(filterChanged)sorter.setRowFilter(filter);
            else if(incremental)publishRows(result.changedRows(),sorter);
            else model.fireTableDataChanged();
          });
          feedback.setText(resultFeedback(next));
          settingsButton.setToolTipText("검색·목록 설정 · "+feedback.getText()+" · "+(incremental?"증분":"전체")+" 평가 "+metrics.examinedRows()+"건 · 이전 결과 재사용 "+metrics.reusedRows()+"건");
          // Selection events during sorter publication see the old busy state. Notify once the
          // complete view is ready, even when Swing retained the same selected original row.
          runningKey=null;busy.set(false);
          if(filterChanged)table.repaint();changed.run();refreshRecoveryControls();job.progress("검색 결과 반영",count,count);job.complete("검색 결과 반영 완료");viewPublished.run();
        });
      }catch(CancellationException ignored){job.cancelled("검색 중지 완료");}catch(Exception ex){job.fail("검색 조건/저장소 확인 필요");SwingUtilities.invokeLater(()->{if(!closed&&token==generation.get()){paused=true;feedback.setText("검색 실패 · 검색 조건을 바꿔 재시도: "+Objects.toString(ex.getMessage(),ex.getClass().getSimpleName()));}});}
      finally{if(closed||token!=generation.get()||Thread.currentThread().isInterrupted())job.cancelled("검색 중지 완료");SwingUtilities.invokeLater(()->{if(token==generation.get()){runningKey=null;busy.set(false);refreshRecoveryControls();}});}
    });
  }
  /** Re-evaluate only changed filter/extra-column rows; avoid full sorter resets on append. */
  private void publishRows(BitSet rows,TableRowSorter<CaptureTableModel> sorter){
    boolean previous=sorter.getSortsOnUpdates();sorter.setSortsOnUpdates(true);
    try{for(int first=rows.nextSetBit(0);first>=0&&first<model.getRowCount();){int end=Math.min(model.getRowCount(),rows.nextClearBit(first))-1;model.fireTableRowsUpdated(first,end);first=rows.nextSetBit(end+1);}}
    finally{sorter.setSortsOnUpdates(previous);}
  }
  View scan(DiskTrafficStore disk,int count,TrafficQuery q,String text,List<Identity> profiles,Set<String> open,BooleanSupplier cancelled){
    return evaluate(disk,count,q,text,List.copyOf(profiles),open,Map.copyOf(marks),clock.getAsLong(),null,0,cancelled,processed->{}).view();
  }
  private ScanResult evaluate(DiskTrafficStore disk,int count,TrafficQuery q,String text,List<Identity> profiles,Set<String> open,Map<String,Mark> markSnapshot,long now,View previous,int from,BooleanSupplier cancelled,IntConsumer progress){
    TrafficQuery.SearchPredicate predicate=q.prepare(text);
    BitSet visible=previous==null?new BitSet():(BitSet)previous.rows().clone();
    Map<String,List<Integer>> members=new LinkedHashMap<>();Map<Integer,String> labels=previous==null?new HashMap<>():new HashMap<>(previous.accounts());
    Map<Integer,Group> groups=previous==null?new HashMap<>():new HashMap<>(previous.groups());
    Map<String,Group> existing=new HashMap<>();for(Group item:groups.values())existing.put(item.key(),item);
    Map<String,String> groupPaths=new HashMap<>();Map<String,Set<String>> statuses=new HashMap<>();
    BitSet dirty=new BitSet();if(from<count)dirty.set(from,count);
    int partial=previous==null?0:previous.partial(),matched=previous==null?0:previous.matched(),examined=0;long expiresAt=Long.MAX_VALUE;
    for(int row=from;row<count;row++){
      if(cancelled.getAsBoolean())throw new CancellationException();evaluatedRows.incrementAndGet();examined++;
      if((row&127)==0)progress.accept(row+1);
      var ref=disk.ref(row);Mark mark=markSnapshot.getOrDefault(ref.id(),new Mark(false,""));
      if(q.important()&&!mark.star())continue;
      if(!q.includesTime(ref.capturedAt(),now))continue;
      expiresAt=Math.min(expiresAt,q.expiresAt(ref.capturedAt()));
      byte[] prefix=profiles.isEmpty()&&!q.group()?null:ref.requestPrefix(65536);String who="미매핑",identity="";
      if(prefix!=null){String wire=HttpText.decode(prefix,null);boolean complete=wire.contains("\r\n\r\n")||wire.contains("\n\n");String header=wire.split("\\r?\\n\\r?\\n",2)[0];identity=complete?sessionKey(header):"unresolved:"+ref.id();if(complete)who=label(header,ref.url(),profiles);}
      if(!who.equals("미매핑"))labels.put(row,who);
      if(!predicate.matchesFields(ref.method(),ref.responseStatus(),who))continue;
      boolean found=predicate.matchesText(ref.method()+" "+ref.url()+" "+ref.responseStatus(),cancelled);
      if(!found&&q.range()>0){
        String request=HttpText.decode(ref.requestPrefix(SEARCH_BYTES),null);if(ref.requestLength()>SEARCH_BYTES)partial++;
        found=predicate.matchesText(request,cancelled);
        if(!found&&q.range()==2&&ref.hasResponse()){
          byte[] bytes=ref.responsePrefix(SEARCH_BYTES);String raw=HttpText.decode(bytes,null);if(ref.responseLength()>SEARCH_BYTES)partial++;
          found=predicate.matchesText(raw,cancelled);
          if(!found&&ref.responseLength()<=SEARCH_BYTES){String decoded=null;try{var response=burp.api.montoya.http.message.responses.HttpResponse.httpResponse(burp.api.montoya.core.ByteArray.byteArray(bytes));decoded=JsBodyDecoder.decode(response.body().getBytes(),response.headerValue("Content-Encoding"),response.headerValue("Content-Type"));}catch(Exception ignored){}if(decoded!=null)found=predicate.matchesText(decoded,cancelled);}
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
  void toggleStar(int raw){ensureProjectState();var ref=store.get().ref(raw);setStars(new int[]{raw},!marks.getOrDefault(ref.id(),new Mark(false,"")).star());}
  void setSelectedStars(boolean value){ensureProjectState();int[] selected=table.getSelectedRows();if(selected.length==0)throw new IllegalArgumentException("중요 표시를 변경할 요청을 선택하세요.");if(selected.length>MAX_MARK_SELECTION)throw new IllegalArgumentException("한 번에 "+MAX_MARK_SELECTION+"건까지 중요 표시를 변경할 수 있습니다.");int[] rows=Arrays.stream(selected).map(table::convertRowIndexToModel).toArray();setStars(rows,value);}
  void setStars(int[] rows,boolean value){
    ensureProjectState();if(rows.length>MAX_MARK_SELECTION)throw new IllegalArgumentException("중요 표시 변경 선택 수 제한");
    DiskTrafficStore disk=store.get();Map<Integer,Boolean> before=new LinkedHashMap<>();
    for(int raw:rows){if(raw<0||raw>=model.getRowCount()||raw>=disk.size())throw new IllegalArgumentException("선택한 원본을 다시 확인하세요.");var ref=disk.ref(raw);boolean old=marks.getOrDefault(ref.id(),new Mark(false,"")).star();if(old!=value)before.put(raw,old);}
    if(before.isEmpty()){annotationStatus="선택 "+rows.length+"건 · 이미 모두 "+(value?"중요 표시":"해제")+" 상태";feedback.setText(annotationStatus);updateMarkActions();return;}
    StarEdit edit=new StarEdit(disk,projectIdentity,Map.copyOf(before),value);starUndo.addLast(edit);while(starUndo.size()>MAX_MARK_UNDO)starUndo.removeFirst();
    for(int raw:before.keySet()){var ref=disk.ref(raw);marks.compute(ref.id(),(k,v)->new Mark(value,v==null?"":v.note()));}
    publishMarkEdit(before.keySet());annotationStatus="선택 "+rows.length+"건 · "+before.size()+"건 "+(value?"중요 표시":"중요 해제");feedback.setText(annotationStatus);updateMarkActions();
  }
  void undoStarEdit(){
    ensureProjectState();StarEdit edit=starUndo.peekLast();if(edit==null)throw new IllegalArgumentException("되돌릴 중요 표시 변경이 없습니다.");
    if(edit.disk()!=store.get()||!edit.project().equals(projectIdentity)){starUndo.clear();updateMarkActions();throw new IllegalArgumentException("프로젝트나 원본 저장소가 바뀌어 이전 변경을 복원할 수 없습니다.");}
    for(var item:edit.before().entrySet()){var ref=edit.disk().ref(item.getKey());if(marks.getOrDefault(ref.id(),new Mark(false,"")).star()!=edit.after())throw new IllegalArgumentException("중요 상태가 바뀌어 이전 변경을 복원할 수 없습니다.");}
    starUndo.removeLast();for(var item:edit.before().entrySet()){var ref=edit.disk().ref(item.getKey());marks.compute(ref.id(),(k,v)->new Mark(item.getValue(),v==null?"":v.note()));}
    publishMarkEdit(edit.before().keySet());annotationStatus="중요 표시 변경 "+edit.before().size()+"건 복원 · 메모 유지";feedback.setText(annotationStatus);updateMarkActions();
  }
  private void publishMarkEdit(Set<Integer> rows){marksVersion.incrementAndGet();schedule();preserveTablePosition(()->{for(int raw:rows)model.fireTableRowsUpdated(raw,raw);});}
  private void updateMarkActions(){int count=table.getSelectedRowCount();StarEdit edit=starUndo.peekLast();undoStars.setEnabled(edit!=null);undoStars.setVisible(edit!=null);undoStars.setText(edit==null?"중요 변경 취소":"중요 변경 취소 ("+edit.before().size()+"건)");undoStars.setToolTipText("최근 "+MAX_MARK_UNDO+"회 중요 표시 변경 복원 · 메모 유지 · Alt+Z");table.putClientProperty("wb.traffic.selection.count",count);}
  void note(int raw,String note){ensureProjectState();if(note.length()>1000)throw new IllegalArgumentException("메모 1,000자 제한");var ref=store.get().ref(raw);marks.compute(ref.id(),(k,v)->new Mark(v!=null&&v.star(),note));marksVersion.incrementAndGet();schedule();preserveTablePosition(()->model.fireTableRowsUpdated(raw,raw));annotationStatus="메모 저장 · 원본 변경 없음";feedback.setText(annotationStatus);updateMarkActions();}
  void toggleGroup(int raw){View v=view;if(v==null)return;Group g=v.groups().get(raw);if(g==null)return;if(!expanded.add(g.key()))expanded.remove(g.key());schedule();}

  /** Capture writes remain independent: only the published metadata boundary is frozen. */
  boolean viewFrozen(){return viewFrozen;}
  int pendingRows(){DiskTrafficStore disk=store.get();return disk==null?0:Math.max(0,Math.toIntExact(disk.size())-model.getRowCount());}
  private int effectiveCount(DiskTrafficStore disk){return viewFrozen&&disk==frozenStore?Math.min(frozenCount,Math.toIntExact(disk.size())):Math.toIntExact(disk.size());}
  void publishCommittedRows(){
    if(closed)return;ensureProjectState();DiskTrafficStore disk=store.get();if(disk==null)return;
    if(viewFrozen&&disk!=frozenStore){viewFrozen=false;frozenStore=null;}
    if(!viewFrozen){int count=Math.toIntExact(disk.size());if(count!=model.getRowCount())preserveTablePosition(()->model.publish(count));else model.publish(count);}
    updateLiveControls();
  }
  void setViewFrozen(boolean frozen){
    if(closed)return;ensureProjectState();DiskTrafficStore disk=store.get();if(disk==null)return;
    if(frozen==viewFrozen){updateLiveControls();return;}
    viewFrozen=frozen;
    if(frozen){frozenStore=disk;frozenCount=model.getRowCount();generation.incrementAndGet();cancelPending();debounce.stop();debouncePending=false;busy.set(false);runningKey=null;feedback.setText("목록 고정 · 수집 상태 유지 · 새 요청은 반영 버튼으로 확인");}
    else{frozenStore=null;publishCommittedRows();schedule();}
    updateLiveControls();
  }
  private void updateLiveControls(){freezeView.setSelected(viewFrozen);newTraffic.setText("새 요청 "+pendingRows()+"건");newTraffic.setEnabled(viewFrozen);newTraffic.setVisible(viewFrozen);freezeView.setText(viewFrozen?"고정 해제":"목록 고정");}
  /** Restore exact original rows and the viewport, never merely a former view index. */
  private void preserveTablePosition(Runnable publish){
    DiskTrafficStore disk=store.get();int first=table.getSelectionModel().getMinSelectionIndex(),last=table.getSelectionModel().getMaxSelectionIndex();
    List<Integer> selected=new ArrayList<>();if(first>=0&&last-first<MAX_MARK_SELECTION)for(int row=first;row<=last;row++)if(row<table.getRowCount()&&table.isRowSelected(row))selected.add(table.convertRowIndexToModel(row));
    JViewport viewport=table.getParent() instanceof JViewport value?value:null;Point point=viewport==null?null:viewport.getViewPosition();
    publish.run();
    if(disk!=store.get())return;
    if(!selected.isEmpty()){
      int[] current=table.getSelectedRows();boolean same=current.length==selected.size();
      if(same)for(int index=0;index<current.length;index++)if(table.convertRowIndexToModel(current[index])!=selected.get(index)){same=false;break;}
      if(!same){table.getSelectionModel().setValueIsAdjusting(true);try{table.clearSelection();for(int raw:selected)if(raw<model.getRowCount()){int visible=table.convertRowIndexToView(raw);if(visible>=0)table.addRowSelectionInterval(visible,visible);}}finally{table.getSelectionModel().setValueIsAdjusting(false);}}
    }
    if(viewport!=null&&point!=null&&!point.equals(viewport.getViewPosition()))viewport.setViewPosition(point);
  }

  JPanel conditionBar(){return conditionBar;}
  SearchPosition capturePosition(){ensureProjectState();return new SearchPosition(query,search.getText(),projectIdentity);}
  void restorePosition(SearchPosition position){
    Objects.requireNonNull(position);requireProject(position.project());
    position.query().prepare(position.text().strip());search.setText(position.text());commitQuery(position.query(),position.project());
  }
  boolean positionReady(SearchPosition expected){return expected!=null&&expected.equals(capturePosition())&&!paused&&idle()&&searchViewCurrent();}

  /** Called after the usual original packet previews have been loaded by the host. */
  String revealSelectionMatches(int raw,PacketInspector request,PacketInspector response){
    if(closed||raw<0||search.getText().isBlank())return "";
    if(!searchViewCurrent()||busy.get()||debouncePending||paused)return "검색 갱신/중지 상태 · 표시된 이전 결과와 현재 조건을 확인하세요.";
    invalidateLinkedContexts();
    if(query.range()==0)return "검색 일치: 목록 메타데이터(메서드·URL·상태)";
    DiskTrafficStore disk=store.get();if(disk==null||raw>=disk.size())return "검색 원본 연결 없음";
    var ref=disk.ref(raw);String requested=search.getText().strip();long revision=contextRevision.get();
    linkedRequest=request;linkedResponse=response;
    java.util.function.BooleanSupplier valid=()->!closed&&revision==contextRevision.get()&&disk==store.get();
    if(request!=null)request.followTrafficSearch(ref,false,query,requested,valid);
    if(response!=null&&query.range()==2)response.followTrafficSearch(ref,true,query,requested,valid);
    return "검색 일치 위치: 아래 패킷의 ‘목록 검색 문맥’ 확인 · 원본 변경 없음";
  }

  private void invalidateLinkedContexts(){contextRevision.incrementAndGet();if(linkedRequest!=null)linkedRequest.invalidateTrafficSearch();if(linkedResponse!=null&&linkedResponse!=linkedRequest)linkedResponse.invalidateTrafficSearch();linkedRequest=null;linkedResponse=null;}

  static String resultFeedback(View result){
    if(result.count()==0)return "수집된 트래픽이 없습니다 · SCOPE와 수집 상태를 확인하고 요청을 관찰하세요.";
    String bounds=result.partial()>0?" · 본문 일부 검사 "+result.partial()+"건 (원문 앞 2 MiB)":"";
    if(result.matched()==0)return "현재 검색 조건에 맞는 항목이 없습니다 · 전체 "+result.count()+"건"+bounds
        +(result.partial()>0?" · 검사 범위 밖의 일치 여부는 미확인":"");
    return "표시 "+result.rows().cardinality()+" / 일치 "+result.matched()+" / 전체 "+result.count()+bounds;
  }

  static String searchProblem(String value,boolean expression){
    if(value.strip().length()>512)return "검색어는 512자까지 입력할 수 있습니다.";
    if(expression&&!value.isBlank())try{java.util.regex.Pattern.compile(value.strip());}
    catch(java.util.regex.PatternSyntaxException error){return "정규식 문법을 확인하세요"+(error.getIndex()>=0?" · 위치 "+(error.getIndex()+1):"")+" · 예: users/[0-9]+";}
    return "";
  }

  private void refreshInputValidation(){
    String main=searchProblem(search.getText(),query.regex());inputProblem.setText(main);inputProblem.setVisible(!main.isEmpty());
    String settings=searchProblem(search.getText(),regex.isSelected());
    if(settings.isEmpty()&&(account.getText().strip().length()>128||account.getText().chars().anyMatch(Character::isISOControl)))settings="계정 필터는 제어문자 없이 128자까지 입력하세요.";
    settingsProblem.setText(settings);settingsProblem.setVisible(!settings.isEmpty());
    if(settingsApply!=null){settingsApply.setEnabled(settings.isEmpty());settingsApply.setToolTipText(settings.isEmpty()?"입력한 검색 조건 적용":settings);}
  }

  private List<RelaxChoice> relaxationChoices(){
    List<RelaxChoice> choices=new ArrayList<>();
    if(!query.method().equals("전체"))choices.add(new RelaxChoice("method","메서드 조건"));
    if(!query.status().equals("전체"))choices.add(new RelaxChoice("status","응답 상태 조건"));
    if(!query.account().isBlank())choices.add(new RelaxChoice("account","계정 조건"));
    if(query.minutes()>0)choices.add(new RelaxChoice("minutes","기간 조건"));
    if(query.important())choices.add(new RelaxChoice("important","중요 표시 조건"));
    if(!search.getText().isBlank())choices.add(new RelaxChoice("text","검색어 조건"));
    return choices;
  }

  private void refreshRecoveryControls(){
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refreshRecoveryControls);return;}
    boolean zero=!closed&&!busy.get()&&!debouncePending&&!paused&&searchViewCurrent()&&table.getRowCount()==0;
    RelaxChoice selected=(RelaxChoice)relaxChoices.getSelectedItem();relaxChoices.removeAllItems();
    for(RelaxChoice choice:relaxationChoices())relaxChoices.addItem(choice);
    if(selected!=null)for(int i=0;i<relaxChoices.getItemCount();i++)if(relaxChoices.getItemAt(i).key().equals(selected.key()))relaxChoices.setSelectedIndex(i);
    boolean canUndo=relaxedBefore!=null&&relaxedAfter!=null&&relaxedAfter.equals(new SearchPosition(query,search.getText(),projectIdentity));
    relaxChoices.setEnabled(zero&&relaxChoices.getItemCount()>0);relaxCondition.setEnabled(zero&&relaxChoices.getItemCount()>0);
    undoRelaxation.setEnabled(canUndo&&!busy.get()&&!debouncePending);recoveryActions.setVisible(zero||canUndo);
  }

  void relaxOneCondition(String key){
    ensureProjectState();
    if(relaxationChoices().stream().noneMatch(choice->choice.key().equals(key)))throw new IllegalArgumentException("해제할 검색 조건을 다시 선택하세요.");
    SearchPosition before=capturePosition();
    if(key.equals("text"))search.setText("");else commitQuery(TrafficSearchPresets.clear(query,key),projectIdentity);
    relaxedBefore=before;relaxedAfter=capturePosition();refreshRecoveryControls();
  }

  void undoRelaxation(){
    SearchPosition before=relaxedBefore,after=relaxedAfter;
    if(before==null||after==null||!after.equals(capturePosition()))throw new IllegalArgumentException("조건이 바뀌었습니다. 현재 검색 조건을 유지합니다.");
    relaxedBefore=null;relaxedAfter=null;restorePosition(before);refreshRecoveryControls();
  }
  String captureSearchState(){ensureProjectState();return TrafficSearchPresets.capture(query);}
  void restoreSearchState(String json){ensureProjectState();commitQuery(TrafficSearchPresets.restore(json),projectIdentity);}
  void restoreSearchState(String json,String expectedProject){ensureProjectState();commitQuery(TrafficSearchPresets.restore(json),expectedProject);}
  String projectIdentity(){ensureProjectState();return projectIdentity;}
  void onSearchStateChanged(Runnable listener){searchStateChanged=Objects.requireNonNull(listener);}
  void onViewPublished(Runnable listener){viewPublished=Objects.requireNonNull(listener);}

  private void buildConditionBar(){
    conditionBar.setName("wb.traffic.conditions");conditionChips.setName("wb.traffic.conditions.chips");
    savedConditions.setName("wb.traffic.conditions.saved");savedConditions.setPreferredSize(new Dimension(170,30));
    savedConditions.setMaximumRowCount(12);savedConditions.setToolTipText("이 프로젝트에 저장한 필터 조건 · 검색어는 저장하지 않습니다.");
    presetApply.setName("wb.traffic.conditions.apply");presetSave.setName("wb.traffic.conditions.save");presetDelete.setName("wb.traffic.conditions.delete");
    presetApply.addActionListener(e->presetAction(this::applySelectedPreset));
    presetSave.addActionListener(e->presetAction(this::savePresetDialog));
    presetDelete.addActionListener(e->presetAction(this::deletePresetDialog));
    savedConditions.addActionListener(e->refreshPresetButtons());
    conditionBar.add(WorkbenchUi.row(new JLabel("저장한 조건"),savedConditions,presetApply,presetSave,presetDelete,undoStars,
        WorkbenchUi.hint("검색어 제외 · 조건만 저장")),BorderLayout.NORTH);
    conditionBar.add(conditionChips,BorderLayout.CENTER);
    listFeedback.setName("wb.traffic.conditions.feedback");WorkbenchUi.feedback(listFeedback);
    feedback.addPropertyChangeListener("text",e->{listFeedback.setText(Objects.toString(e.getNewValue()," "));refreshRecoveryControls();});
    inputProblem.setName("wb.traffic.search.validation");settingsProblem.setName("wb.traffic.settings.validation");
    inputProblem.setForeground(WorkbenchUi.toneInk(WorkbenchUi.Tone.ERROR));settingsProblem.setForeground(WorkbenchUi.toneInk(WorkbenchUi.Tone.ERROR));
    relaxChoices.setName("wb.traffic.zero.condition");relaxChoices.setPreferredSize(new Dimension(150,30));
    relaxCondition.setName("wb.traffic.zero.relax");undoRelaxation.setName("wb.traffic.zero.undo");
    relaxCondition.setToolTipText("선택한 목록 필터 하나만 해제 · SCOPE나 수집 설정은 바꾸지 않습니다.");
    undoRelaxation.setToolTipText("이번에 해제한 조건과 검색어를 복원 · 현재 프로젝트/조건이 같은 동안에만 사용");
    relaxCondition.addActionListener(e->presetAction(()->{RelaxChoice choice=(RelaxChoice)relaxChoices.getSelectedItem();if(choice!=null)relaxOneCondition(choice.key());}));
    undoRelaxation.addActionListener(e->presetAction(this::undoRelaxation));
    recoveryActions.setName("wb.traffic.zero.actions");recoveryActions.add(new JLabel("조건 하나씩 확인"));recoveryActions.add(relaxChoices);recoveryActions.add(relaxCondition);recoveryActions.add(undoRelaxation);recoveryActions.setVisible(false);
    JPanel status=new JPanel();status.setLayout(new BoxLayout(status,BoxLayout.Y_AXIS));status.add(inputProblem);status.add(listFeedback);status.add(recoveryActions);
    conditionBar.add(status,BorderLayout.SOUTH);refreshPresetChoices("");refreshInputValidation();
  }

  private void presetAction(Runnable action){try{action.run();}catch(RuntimeException error){feedback.setText(error.getMessage());}}

  private String currentProjectIdentity(){
    if(api==null||api.persistence()==null||api.persistence().extensionData()==null)return "";
    var data=api.persistence().extensionData();String key=BuildInfo.storageKey("auth-setup-library-project-v1");
    String id=data.getString(key);
    if(id==null){id=UUID.randomUUID().toString();data.setString(key,id);if(!id.equals(data.getString(key)))return "";}
    return id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")?id:"";
  }

  /** Every operation resolves the host's current project; a stale dialog cannot write into another project. */
  private boolean ensureProjectState(){
    String id;try{id=currentProjectIdentity();}catch(RuntimeException error){id="";}
    if(projectLoaded&&id.equals(projectIdentity))return false;
    boolean changed=projectLoaded;projectLoaded=true;projectIdentity=id;presetLoadFailure=false;storedPresets=null;presets=List.of();query=TrafficQuery.defaults();
    if(changed){clear();search.setText("");settings.closeWindow();relaxedBefore=null;relaxedAfter=null;}
    if(!id.isEmpty()){
      try{
        var data=api.persistence().extensionData();query=TrafficQuery.read(data.getString(BuildInfo.storageKey("traffic-view-v1")));
        storedPresets=data.getString(BuildInfo.storageKey("traffic-search-presets-v1"));presets=TrafficSearchPresets.decode(storedPresets);
      }catch(RuntimeException error){presetLoadFailure=true;feedback.setText("저장한 검색 조건을 읽을 수 없습니다 · 기존 저장 내용 보존");}
    }
    settingsProject=id;loadControls();refreshPresetChoices("");refreshConditionChips();updateColumns();
    return changed;
  }

  private void requireProject(String expected){
    ensureProjectState();
    if(!Objects.equals(expected,projectIdentity))throw new IllegalArgumentException("프로젝트가 바뀌었습니다. 현재 프로젝트의 조건을 다시 선택하세요.");
  }

  private void commitQuery(TrafficQuery next,String expected){
    requireProject(expected);next.prepare(search.getText().strip());query=next;
    if(!projectIdentity.isEmpty())try{
      TrafficSearchPresets.capture(next);
      api.persistence().extensionData().setString(BuildInfo.storageKey("traffic-view-v1"),MetadataExport.json(next.json()));
    }catch(RuntimeException error){feedback.setText("조건 저장 실패 · 현재 창에만 적용 · 계정 이름·프로젝트 저장 상태 확인");}
    loadControls();updateColumns();refreshConditionChips();schedule();searchStateChanged.run();
  }

  void clearCondition(String key){
    ensureProjectState();
    if(key.equals("text")){search.setText("");return;}
    commitQuery(TrafficSearchPresets.clear(query,key),projectIdentity);
  }

  private void refreshConditionChips(){
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refreshConditionChips);return;}
    conditionChips.removeAll();
    if(!search.getText().isBlank())addChip("text","검색어 적용");
    TrafficSearchPresets.conditions(query).forEach(this::addChip);
    if(conditionChips.getComponentCount()==0)conditionChips.add(WorkbenchUi.hint("적용 조건 없음 · 전체 목록"));
    else{
      JButton reset=new JButton("모두 해제");reset.setName("wb.traffic.conditions.clear");
      String expected=projectIdentity;
      reset.addActionListener(e->presetAction(()->{requireProject(expected);search.setText("");commitQuery(TrafficQuery.defaults(),expected);}));conditionChips.add(reset);
    }
    WorkbenchUi.style(conditionChips);conditionChips.revalidate();conditionChips.repaint();refreshRecoveryControls();
  }

  private void addChip(String key,String label){
    String compact=label.length()>28?label.substring(0,27)+"…":label;
    JButton chip=new JButton(compact+" ×");chip.setName("wb.traffic.condition."+key);
    chip.setToolTipText(label+" · 이 조건만 해제");chip.getAccessibleContext().setAccessibleName(label+" 조건 해제");
    String expected=projectIdentity;
    chip.addActionListener(e->presetAction(()->{requireProject(expected);clearCondition(key);}));conditionChips.add(chip);
  }

  private void refreshPresetChoices(String selected){
    savedConditions.removeAllItems();savedConditions.addItem("저장한 조건 선택");
    for(var preset:presets)savedConditions.addItem(preset.name());
    if(!selected.isBlank())savedConditions.setSelectedItem(selected);refreshPresetButtons();
  }

  private void refreshPresetButtons(){
    boolean selected=savedConditions.getSelectedIndex()>0;
    boolean available=api==null||!projectIdentity.isEmpty();
    presetApply.setEnabled(selected&&!presetLoadFailure&&available);presetDelete.setEnabled(selected&&!presetLoadFailure&&available);presetSave.setEnabled(!presetLoadFailure&&available);
    presetSave.setToolTipText(available?"현재 검색 조건을 이름 붙여 저장 · 검색어 제외":"프로젝트 저장소를 확인할 수 없어 조건 저장을 사용할 수 없습니다.");
  }

  private TrafficSearchPresets.Preset selectedPreset(){
    String selected=Objects.toString(savedConditions.getSelectedItem(),"");
    return presets.stream().filter(p->p.name().equals(selected)).findFirst().orElseThrow(()->new IllegalArgumentException("저장한 검색 조건을 선택하세요."));
  }

  void applySelectedPreset(){
    String expected=projectIdentity;var selected=selectedPreset();requireProject(expected);
    commitQuery(selected.query(),expected);feedback.setText("‘"+selected.name()+"’ 조건 적용 · 현재 검색어 유지");
  }

  private void savePresetDialog(){
    ensureProjectState();String expected=projectIdentity;TrafficQuery planned=query;
    String value=JOptionPane.showInputDialog(table,"검색 조건 이름 · 검색어는 저장하지 않습니다.","조건 저장",JOptionPane.PLAIN_MESSAGE);
    if(value==null)return;String name=TrafficSearchPresets.validName(value);requireProject(expected);
    if(presets.stream().anyMatch(p->p.name().equals(name))&&JOptionPane.showConfirmDialog(table,
        "‘"+name+"’의 저장 조건을 현재 조건으로 바꿀까요?","조건 갱신",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
    savePreset(name,planned,expected);feedback.setText("검색 조건 저장 · 검색어·패킷·인증값 제외"+(projectIdentity.isEmpty()?" · 현재 실행 중에만 유지":""));
  }

  void savePreset(String name,TrafficQuery planned,String expected){
    requireProject(expected);writePresets(TrafficSearchPresets.put(presets,name,planned),expected);refreshPresetChoices(name);
  }

  private void deletePresetDialog(){
    String expected=projectIdentity;var selected=selectedPreset();requireProject(expected);
    if(JOptionPane.showConfirmDialog(table,"‘"+selected.name()+"’ 저장 조건을 삭제할까요? 현재 검색 조건은 유지합니다.",
        "검색 조건 삭제",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
    requireProject(expected);List<TrafficSearchPresets.Preset> next=presets.stream().filter(p->!p.name().equals(selected.name())).toList();
    writePresets(next,expected);refreshPresetChoices("");feedback.setText("저장 조건 삭제 · 현재 검색 조건 유지");
  }

  private void writePresets(List<TrafficSearchPresets.Preset> next,String expected){
    requireProject(expected);if(presetLoadFailure)throw new IllegalArgumentException("읽을 수 없는 기존 저장 조건을 보존합니다. 프로젝트 저장 내용을 확인하세요.");
    if(api!=null&&projectIdentity.isEmpty())throw new IllegalArgumentException("프로젝트 저장소를 확인할 수 없어 검색 조건을 저장하지 않았습니다.");
    String encoded=TrafficSearchPresets.encode(next);
    if(!projectIdentity.isEmpty()){
      var data=api.persistence().extensionData();String key=BuildInfo.storageKey("traffic-search-presets-v1");
      if(!Objects.equals(storedPresets,data.getString(key))){projectLoaded=false;ensureProjectState();throw new IllegalArgumentException("다른 창에서 저장 조건이 변경됐습니다. 다시 확인하고 저장하세요.");}
      data.setString(key,encoded);if(!encoded.equals(data.getString(key)))throw new IllegalArgumentException("검색 조건 저장을 확인할 수 없습니다.");
    }
    storedPresets=encoded;presets=List.copyOf(next);
  }

  JPanel content(){
    JPanel body=new JPanel();body.setName("wb.traffic.search.content");body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS));body.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
    body.add(section("검색 조건"));body.add(WorkbenchUi.row(new JLabel("메서드"),method,new JLabel("상태"),response));
    body.add(section("검색 범위"));ButtonGroup choices=new ButtonGroup();JPanel range=new JPanel(new GridLayout(1,3,0,0));for(int i=0;i<3;i++){ranges[i].setName("wb.traffic.range."+i);choices.add(ranges[i]);range.add(ranges[i]);}range.setMaximumSize(new Dimension(Integer.MAX_VALUE,34));body.add(range);
    body.add(section("목록 정리"));body.add(group);body.add(important);
    advancedToggle.setName("wb.traffic.search.advanced");advancedToggle.setHorizontalAlignment(SwingConstants.LEFT);body.add(advancedToggle);
    advanced.removeAll();advanced.add(WorkbenchUi.row(new JLabel("계정"),account,new JLabel("기간"),period));advanced.add(WorkbenchUi.row(sensitive,regex));advanced.add(WorkbenchUi.row(normalize,memo));advanced.setVisible(advancedToggle.isSelected());body.add(advanced);
    for(ActionListener l:advancedToggle.getActionListeners())advancedToggle.removeActionListener(l);
    advancedToggle.addActionListener(e->{advanced.setVisible(advancedToggle.isSelected());advancedToggle.setText((advanced.isVisible()?"▾ ":"▸ ")+"고급 설정");body.revalidate();});
    JButton reset=new JButton("초기화"),apply=new JButton("적용"),close=new JButton("닫기");settingsApply=apply;reset.setName("wb.traffic.search.reset");apply.setName("wb.traffic.search.apply");
    reset.addActionListener(e->{query=TrafficQuery.defaults();loadControls();search.setText("");apply();});apply.addActionListener(e->{try{apply();settings.closeWindow();}catch(Exception ex){feedback.setText(ex.getMessage());}});close.addActionListener(e->settings.closeWindow());
    feedback.setName("wb.traffic.search.progress");body.add(settingsProblem);body.add(feedback);refreshInputValidation();
    JPanel shell=new JPanel(new BorderLayout(0,8));shell.setName("wb.traffic.search.window");shell.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));JScrollPane scroll=new JScrollPane(body);scroll.setBorder(null);scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);shell.add(scroll);shell.add(WorkbenchUi.row(reset,cancel,close,apply),BorderLayout.SOUTH);WorkbenchUi.style(shell);return shell;
  }
  static JDialog window(Component owner,String title,Component content,boolean modal,Runnable closed){
    if(GraphicsEnvironment.isHeadless())return null;JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(owner),title,Dialog.ModalityType.MODELESS);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setContentPane((Container)content);dialog.setSize(550,440);dialog.setMinimumSize(new Dimension(480,360));dialog.setLocationRelativeTo(owner);dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){closed.run();}});dialog.setVisible(true);return dialog;
  }
  private JLabel section(String text){JLabel l=new JLabel(text);l.setBorder(BorderFactory.createEmptyBorder(12,0,6,0));return l;}
  void loadControls(){TrafficQuery q=query;method.setSelectedItem(q.method());response.setSelectedItem(q.status());for(int i=0;i<3;i++)ranges[i].setSelected(i==q.range());group.setSelected(q.group());important.setSelected(q.important());normalize.setSelected(q.normalize());memo.setSelected(q.memo());sensitive.setSelected(q.sensitive());regex.setSelected(q.regex());account.setText(q.account());
    String time=q.minutes()==0?"전체":q.minutes()==60?"최근 1시간":q.minutes()==1440?"최근 24시간":"최근 "+q.minutes()+"분";
    boolean found=false;for(int i=0;i<period.getItemCount();i++)if(time.equals(period.getItemAt(i)))found=true;if(!found)period.addItem(time);period.setSelectedItem(time);
  }
  private int selectedMinutes(){String selected=Objects.toString(period.getSelectedItem(),"전체");return switch(selected){case "전체"->0;case "최근 1시간"->60;case "최근 24시간"->1440;default->Integer.parseInt(selected.replace("최근 ","").replace("분",""));};}
  void apply(){int range=0;for(int i=0;i<3;i++)if(ranges[i].isSelected())range=i;TrafficQuery next=new TrafficQuery(method.getSelectedItem().toString(),response.getSelectedItem().toString(),range,sensitive.isSelected(),regex.isSelected(),group.isSelected(),normalize.isSelected(),important.isSelected(),memo.isSelected(),account.getText().strip(),selectedMinutes());
    commitQuery(next,settingsProject);
  }
  void updateColumns(){int view=table.convertColumnIndexToView(10);if(view>=0){var c=table.getColumnModel().getColumn(view);c.setMinWidth(0);c.setMaxWidth(query.memo()?400:0);c.setPreferredWidth(query.memo()?130:0);} }
  void clear(){viewFrozen=false;frozenStore=null;frozenCount=0;starUndo.clear();annotationStatus="";updateLiveControls();updateMarkActions();invalidateLinkedContexts();relaxedBefore=null;relaxedAfter=null;marks.clear();marksVersion.incrementAndGet();expanded.clear();view=null;snapshot=null;runningKey=null;scanned=-1;generation.incrementAndGet();debounce.stop();debouncePending=false;cancelPending();paused=false;busy.set(false);refreshRecoveryControls();}
  @Override public void close(){closed=true;invalidateLinkedContexts();snapshot=null;runningKey=null;relaxedBefore=null;relaxedAfter=null;viewPublished=()->{};generation.incrementAndGet();debounce.stop();debouncePending=false;busy.set(false);settings.close();if(pending!=null)pending.cancel(true);worker.shutdownNow();}
}

