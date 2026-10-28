package kr.payloadrunner;
import burp.*;import burp.api.montoya.MontoyaApi;import burp.api.montoya.core.ByteArray;import burp.api.montoya.http.*;import burp.api.montoya.http.message.requests.HttpRequest;
import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;

/** Native timeout transport with a bounded legacy adapter for older host/test integrations. */
public final class PayloadHttp implements AutoCloseable {
 private final IBurpExtenderCallbacks callbacks;volatile MontoyaApi api;private final AtomicBoolean legacyBusy=new AtomicBoolean();
 private final ExecutorService legacy=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Workbench-payload-legacy-http");t.setDaemon(true);return t;});
 public PayloadHttp(IBurpExtenderCallbacks callbacks,MontoyaApi api){this.callbacks=callbacks;this.api=api;}
 public boolean busy(){return legacyBusy.get();}
 public byte[] send(IHttpService service,byte[] raw,int timeoutMs,AtomicBoolean stop)throws Exception{
  if(timeoutMs<100||timeoutMs>120000)throw new IllegalArgumentException("응답 대기시간 범위");if(stop.get())throw new InterruptedException("중지");
  MontoyaApi nativeApi=api;if(nativeApi!=null){var target=HttpService.httpService(service.getHost(),service.getPort(),service.getProtocol().equalsIgnoreCase("https"));var request=HttpRequest.httpRequest(target,ByteArray.byteArray(raw));
   var rr=nativeApi.http().sendRequest(request,RequestOptions.requestOptions().withResponseTimeout(timeoutMs).withRedirectionMode(RedirectionMode.NEVER));
   if(rr==null||rr.response()==null)throw new TimeoutException("응답 없음 · 후속 요청 중단");return rr.response().toByteArray().getBytes();}
  if(!legacyBusy.compareAndSet(false,true))throw new IllegalStateException("이전 Legacy 요청이 아직 종료되지 않았습니다.");
  // Release before FutureTask publishes completion, and only once for this task.
  // The run-finally also covers a task cancelled before its callable starts;
  // it must not clear the busy flag of a newer request after get() returns.
  AtomicBoolean released=new AtomicBoolean();Runnable release=()->{if(released.compareAndSet(false,true))legacyBusy.set(false);};
  FutureTask<byte[]> task=new FutureTask<>(()->{try{var rr=callbacks.makeHttpRequest(service,raw);return rr==null?null:rr.getResponse();}finally{release.run();}}){@Override public void run(){try{super.run();}finally{release.run();}}};try{legacy.execute(task);}catch(RejectedExecutionException e){release.run();throw e;}
  long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
  try{while(true){if(stop.get())throw new InterruptedException("중지 · 발송한 Legacy 요청은 Burp 대기시간 적용");long remaining=end-System.nanoTime();if(remaining<=0)throw new TimeoutException("응답 대기시간 초과 · 후속 요청 중단");try{return task.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);}catch(TimeoutException e){if(System.nanoTime()>=end)throw e;}}}
  catch(ExecutionException e){if(e.getCause() instanceof Exception ex)throw ex;throw e;}finally{if(!task.isDone())task.cancel(true);}
 }
 @Override public void close(){legacy.shutdownNow();}
}

