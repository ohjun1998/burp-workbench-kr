package kr.workbench;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;

/** Per-workspace bounded task metadata. It never stores packets, queries or error bodies. */
final class BackgroundJobs implements AutoCloseable {
  static final int LIMIT = 64;
  enum State {
    RUNNING("진행 중"), CANCEL_REQUESTED("중지 요청 · 종료 대기"),
    COMPLETED("완료"), CANCELLED("중지됨"), FAILED("실패");
    final String label;
    State(String label) { this.label = label; }
    boolean terminal() { return this == COMPLETED || this == CANCELLED || this == FAILED; }
  }
  record View(long id, String title, String route, String stage, State state,
              long completed, long total, long elapsedMillis, boolean cancellable, boolean resultAvailable) {
    String count() { return total < 0 ? completed == 0 ? "건수 미정" : completed + "건 처리" : completed + " / " + total; }
  }
  private static final class Entry {
    final long id, generation, started;
    final String title, route;
    final Runnable cancel;
    String stage = "시작";
    State state = State.RUNNING;
    long completed, total = -1, finished;
    Runnable result;
    Entry(long id, long generation, long started, String title, String route, Runnable cancel) {
      this.id=id; this.generation=generation; this.started=started; this.title=title; this.route=route; this.cancel=cancel;
    }
  }
  static final class Handle {
    static final Handle NONE = new Handle(null, -1, -1);
    private final BackgroundJobs owner;
    private final long id, generation;
    private Handle(BackgroundJobs owner, long id, long generation) { this.owner=owner; this.id=id; this.generation=generation; }
    void progress(String stage, long completed, long total) { if(owner!=null) owner.progress(this,stage,completed,total); }
    void complete(String stage) { finish(State.COMPLETED,stage); }
    void cancelled(String stage) { finish(State.CANCELLED,stage); }
    void fail(String stage) { finish(State.FAILED,stage); }
    void stopping(String stage) { if(owner!=null)owner.stopping(this,stage); }
    /** The callback must retain immutable IDs only, never a packet or a raw search query. */
    void result(Runnable action) { if(owner!=null)owner.result(this,action); }
    boolean cancelRequested() { return owner!=null && owner.cancelRequested(this); }
    private void finish(State state,String stage) { if(owner!=null) owner.finish(this,state,stage); }
  }
  static Handle begin(BackgroundJobs registry, String title, String route, Runnable cancel) {
    return registry == null ? Handle.NONE : registry.start(title,route,cancel);
  }
  private final LinkedHashMap<Long,Entry> entries = new LinkedHashMap<>();
  private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
  private final AtomicBoolean notificationPending = new AtomicBoolean();
  private final LongSupplier clock;
  private long sequence, generation;
  private long overflow;
  private boolean closed;
  BackgroundJobs() { this(System::nanoTime); }
  BackgroundJobs(LongSupplier clock) { this.clock=Objects.requireNonNull(clock); }
  synchronized Handle start(String title,String route,Runnable cancel) {
    if(closed) return Handle.NONE;
    while(entries.size() >= LIMIT) {
      Long victim = entries.values().stream().filter(e -> e.state.terminal()).map(e -> e.id).findFirst().orElse(null);
      // Never evict a running task to make its stop/result controls disappear.
      if(victim == null) { overflow++;changed();return Handle.NONE; }
      entries.remove(victim);
    }
    long id=++sequence;
    entries.put(id,new Entry(id,generation,clock.getAsLong(),text(title),text(route),cancel));
    changed();
    return new Handle(this,id,generation);
  }
  private Entry current(Handle handle) {
    Entry e=entries.get(handle.id);
    return !closed && handle.generation==generation && e!=null && e.generation==handle.generation ? e : null;
  }
  private synchronized void progress(Handle handle,String stage,long completed,long total) {
    Entry e=current(handle); if(e==null || e.state.terminal())return;
    if(completed<0 || total < -1 || total>=0 && completed>total)throw new IllegalArgumentException("작업 건수 범위를 확인하세요.");
    e.stage=text(stage);e.completed=completed;e.total=total;changed();
  }
  private synchronized boolean cancelRequested(Handle handle) {
    Entry e=current(handle);return e==null || e.state==State.CANCEL_REQUESTED || e.state==State.CANCELLED;
  }
  private synchronized void stopping(Handle handle,String stage) {
    Entry e=current(handle);if(e==null || e.state.terminal())return;
    e.state=State.CANCEL_REQUESTED;e.stage=text(stage);changed();
  }
  private synchronized void result(Handle handle,Runnable action) {
    Entry e=current(handle);if(e==null)return;e.result=action;changed();
  }
  boolean openResult(long id) {
    Runnable action;
    synchronized(this){Entry e=entries.get(id);if(closed || e==null || e.result==null || !e.state.terminal())return false;action=e.result;}
    action.run();return true;
  }
  private synchronized void finish(Handle handle,State state,String stage) {
    Entry e=current(handle); if(e==null || e.state.terminal())return;
    // Completion acknowledges actual worker termination; requesting stop alone is not terminal.
    e.state=state;e.stage=text(stage);e.finished=clock.getAsLong();changed();
  }
  boolean requestCancel(long id) {
    Runnable cancel;
    synchronized(this) {
      Entry e=entries.get(id);
      if(closed || e==null || e.state!=State.RUNNING || e.cancel==null)return false;
      e.state=State.CANCEL_REQUESTED;e.stage="이미 시작한 처리가 종료되기를 기다립니다.";cancel=e.cancel;changed();
    }
    try { cancel.run(); }
    catch(RuntimeException error) {
      synchronized(this) { Entry e=entries.get(id);if(e!=null && e.state==State.CANCEL_REQUESTED)e.stage="중지 요청을 처리하지 못했습니다. 원래 화면에서 상태를 확인하세요.";changed(); }
    }
    return true;
  }
  synchronized List<View> snapshot() {
    long now=clock.getAsLong();List<View> result=new ArrayList<>();
    for(Entry e:entries.values()) result.add(new View(e.id,e.title,e.route,e.stage,e.state,e.completed,e.total,
        Math.max(0,((e.state.terminal()?e.finished:now)-e.started)/1_000_000),e.cancel!=null && e.state==State.RUNNING,e.result!=null && e.state.terminal()));
    Collections.reverse(result);return List.copyOf(result);
  }
  synchronized long overflowCount() { return overflow; }
  void onChange(Runnable listener) { listeners.add(Objects.requireNonNull(listener)); }
  void removeListener(Runnable listener) { listeners.remove(listener); }
  void reset() { reset(false); }
  @Override public void close() { reset(true); }
  private void reset(boolean closing) {
    List<Runnable> cancellations;
    synchronized(this) {
      if(closed)return;
      cancellations=entries.values().stream().filter(e->!e.state.terminal() && e.cancel!=null)
          .map(e->e.cancel).toList();
      generation++;entries.clear();overflow=0;closed=closing;changed();
      if(closing)listeners.clear();
    }
    // Cancellation may reenter a producer or post to EDT; never invoke it under the registry lock.
    for(Runnable cancellation:cancellations)try{cancellation.run();}catch(RuntimeException ignored){}
  }
  private void changed() {
    if(notificationPending.compareAndSet(false,true))SwingUtilities.invokeLater(() -> {
      notificationPending.set(false);for(Runnable listener:listeners)listener.run();
    });
  }
  private static String text(String value) {
    String clean=Objects.toString(value,"").replaceAll("[\\p{Cntrl}]"," ");return clean.length()>160?clean.substring(0,160):clean;
  }
}
