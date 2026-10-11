package kr.workbench;
import burp.api.montoya.proxy.*;
import burp.api.montoya.proxy.http.*;
import burp.api.montoya.core.Registration;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
/** Reads recorded responses. Neither polling nor event handling sends HTTP requests. */
public final class JsHistoryMonitor implements AutoCloseable {
 @FunctionalInterface public interface Sink {boolean offer(HttpRequest q,HttpResponse r);}
 private final Proxy proxy;private final Sink sink;private final BooleanSupplier enabled;private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Workbench-JS-History");t.setDaemon(true);return t;});private final Set<Integer> pending=new HashSet<>();private int cursor=-1;private Registration registration;private final AtomicBoolean closed=new AtomicBoolean();private final AtomicLong reset=new AtomicLong();private long appliedReset;final AtomicLong checked=new AtomicLong(),pendingOverflow=new AtomicLong(),failures=new AtomicLong();
 public JsHistoryMonitor(Proxy proxy,Sink sink,BooleanSupplier enabled){this.proxy=proxy;this.sink=sink;this.enabled=enabled;}
 public void start(){try{registration=proxy.registerResponseHandler(new ProxyResponseHandler(){public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse r){return ProxyResponseReceivedAction.continueWith(r,r.annotations());}public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse r){if(!closed.get()&&enabled.getAsBoolean())sink.offer(r.initiatingRequest(),r);return ProxyResponseToBeSentAction.continueWith(r,r.annotations());}});}catch(Exception e){failures.incrementAndGet();}timer.scheduleWithFixedDelay(this::poll,0,1,TimeUnit.SECONDS);}
 void poll(){if(closed.get()||!enabled.getAsBoolean())return;try{if(appliedReset!=reset.get()){cursor=-1;pending.clear();appliedReset=reset.get();}int previous=cursor;AtomicInteger max=new AtomicInteger(-1);List<ProxyHttpRequestResponse> history=proxy.history(item->{int id=item.id();max.accumulateAndGet(id,Math::max);return id>previous||pending.contains(id);});if(history==null)return;if(max.get()<cursor){cursor=-1;pending.clear();return;}history=new ArrayList<>(history);history.sort(Comparator.comparingInt(ProxyHttpRequestResponse::id));int handled=0;for(ProxyHttpRequestResponse item:history){if(closed.get()||!enabled.getAsBoolean()||handled++>=500)break;int id=item.id();HttpResponse response=item.response();if(response==null){if(pending.size()<2048)pending.add(id);else pendingOverflow.incrementAndGet();cursor=Math.max(cursor,id);continue;}if(!sink.offer(item.request(),response))break;pending.remove(id);cursor=Math.max(cursor,id);checked.incrementAndGet();}}catch(Exception e){failures.incrementAndGet();}}
 public void rescan(){reset.incrementAndGet();}
 @Override public void close(){if(!closed.compareAndSet(false,true))return;timer.shutdownNow();if(registration!=null)try{registration.deregister();}catch(Exception ignored){}}
}
