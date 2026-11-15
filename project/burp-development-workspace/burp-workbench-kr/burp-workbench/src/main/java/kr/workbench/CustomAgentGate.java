package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** A separate, serial transmission boundary for the embedded agent. Never grants session approvals. */
final class CustomAgentGate {
  interface Approval { boolean approve(HttpRequest request); }
  final MontoyaApi api;
  final URI modelBase;
  final Approval approval;
  final Consumer<String> status;
  final AtomicBoolean stopped = new AtomicBoolean(true);
  private final Object serial = new Object();
  private final Predicate<HttpRequest> scope;
  private volatile boolean closed;
  private volatile long generation;
  private volatile int targetCount, modelCount;
  private int targetLimit = 30, modelLimit = 20, delayMillis = 2000;
  private long lastSent;
  private String origin;
  private UnaryOperator<HttpRequest> identity;
  private Runnable validate;

  CustomAgentGate(MontoyaApi api, URI base, Approval approval, Consumer<String> status) {
    this(api,base,approval,status,q->WorkbenchScope.contains(api,q));
  }
  CustomAgentGate(MontoyaApi api, URI base, Approval approval, Consumer<String> status, Predicate<HttpRequest> scope) {
    this.api=api;this.modelBase=base;this.approval=approval;this.status=status;this.scope=scope;
  }
  void start(String targetOrigin,int limit,int models,int delay,UnaryOperator<HttpRequest> account,Runnable validator) {
    synchronized(serial) {
      if(closed)throw new IllegalStateException("Agent가 종료되었습니다.");
      if(!stopped.get())throw new IllegalStateException("먼저 대상 전송을 중지하세요.");
      if(limit<1||limit>300||models<1||models>100||delay<1000)throw new IllegalArgumentException("요청 제한 범위를 확인하세요.");
      origin=AutoLlmEngine.origin(targetOrigin); targetLimit=limit; modelLimit=models;delayMillis=delay;
      identity=Objects.requireNonNull(account);validate=Objects.requireNonNull(validator);
      validate.run();targetCount=0;modelCount=0;lastSent=0;generation++;stopped.set(false);
      status.accept("전송 준비 · 대상 요청은 매번 개별 승인 필요");
    }
  }
  void stop(){stopped.set(true);generation++;status.accept("대상 전송 중지 · 이미 전송한 요청은 종료 대기");}
  void close(){closed=true;stop();}
  int targets(){return targetCount;} int models(){return modelCount;}
  boolean running(){return !closed&&!stopped.get();}

  HttpRequestResponse send(HttpRequest request) {
    if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Agent 전송은 백그라운드에서 실행해야 합니다.");
    synchronized(serial) {
      if(closed)throw new CancellationException("Agent 종료");
      if(isModel(request)) {
        if(++modelCount>modelLimit)throw new IllegalStateException("로컬 모델 호출 예산 소진 · Agent를 종료하고 다시 열어 주세요.");
        var response=api.http().sendRequest(request,options(120000));
        status.accept("로컬 모델 호출 "+modelCount+" / "+modelLimit);
        return response;
      }
      long epoch=generation;
      check(epoch);if(targetCount>=targetLimit){stop();throw new IllegalStateException("대상 요청 예산 소진");}
      AutoLlmPanel.validateDestination(request,origin);
      if(!scope.test(request))throw new IllegalArgumentException("Workbench SCOPE 밖 요청 차단");
      HttpRequest q=identity.apply(request);
      AutoLlmPanel.validateDestination(q,origin);
      if(q.toByteArray().length()>131072)throw new IllegalArgumentException("128 KiB 초과 요청 차단");
      if(!approval.approve(q))throw new CancellationException("해당 요청을 승인하지 않았습니다.");
      check(epoch);
      long wait=delayMillis-(System.nanoTime()-lastSent)/1000000;
      while(wait>0){try{Thread.sleep(Math.min(wait,100));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}check(epoch);wait=delayMillis-(System.nanoTime()-lastSent)/1000000;}
      check(epoch);if(!scope.test(q))throw new IllegalArgumentException("승인 후 SCOPE 변경 · 전송 차단");
      targetCount++;lastSent=System.nanoTime();
      try {
        var response=api.http().sendRequest(q,options(10000));
        long elapsed=(System.nanoTime()-lastSent)/1000000;
        if(response==null||response.response()==null||response.response().statusCode()==429||response.response().statusCode()>=500||elapsed>=5000) {
          stop();throw new IllegalStateException("응답 오류·429·5xx·5초 이상 지연 · 후속 전송 중지");
        }
        status.accept("대상 요청 "+targetCount+" / "+targetLimit+" · 로컬 모델 "+modelCount+" / "+modelLimit);
        return response;
      } catch(RuntimeException e){stop();throw e;}
    }
  }
  private void check(long epoch){if(closed||stopped.get()||epoch!=generation)throw new CancellationException("전송 중지 또는 실행 변경");validate.run();}
  private boolean isModel(HttpRequest q) {
    URI u=URI.create(q.url());
    if(!AutoLlmEngine.origin(u.toString()).equals(AutoLlmEngine.origin(modelBase.toString())))return false;
    AutoLlmPanel.validateDestination(q,AutoLlmEngine.origin(modelBase.toString()));
    String base=modelBase.getPath().replaceAll("/$","");
    if(u.getRawQuery()!=null||u.getFragment()!=null||u.getUserInfo()!=null)throw new IllegalArgumentException("모델 URL 형식 오류");
    boolean models=q.method().equals("GET")&&u.getPath().equals(base+"/models");
    boolean chat=q.method().equals("POST")&&u.getPath().equals(base+"/chat/completions");
    if(!models&&!chat)throw new IllegalArgumentException("허용하지 않은 로컬 모델 API");
    return true;
  }
  private static RequestOptions options(int timeout){return RequestOptions.requestOptions().withResponseTimeout(timeout).withRedirectionMode(RedirectionMode.NEVER);}
}
