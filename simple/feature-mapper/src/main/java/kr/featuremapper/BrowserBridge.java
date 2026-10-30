package kr.featuremapper;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Authenticated loopback metadata bridge. Never forwards or modifies target requests. */
public final class BrowserBridge implements AutoCloseable {
  private final HttpServer server;
  private final java.util.concurrent.atomic.AtomicLong backpressure =
      new java.util.concurrent.atomic.AtomicLong();
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(32),
          r -> {
            Thread t = new Thread(r, "feature-mapper-browser");
            t.setDaemon(true);
            return t;
          },
          (task, pool) -> {
            if (!pool.isShutdown()) {
              backpressure.incrementAndGet();
              task.run();
            }
          });

  long backpressureCount() {
    return backpressure.get();
  }

  final String token;
  private volatile boolean running = true;
  private final java.util.concurrent.atomic.AtomicLong lastAuthenticated = new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong lastEvent = new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong receivedEvents = new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong rejectedRequests = new java.util.concurrent.atomic.AtomicLong();

  /** Transport observations only: receiving an event does not mean SCOPE accepted or mapped it. */
  record Snapshot(boolean running, long lastAuthenticated, long lastEvent, long receivedEvents,
                  long rejectedRequests) {
    String description(long now) {
      String connection = lastAuthenticated == 0 ? "인증 연결 대기"
          : now - lastAuthenticated <= 90_000 ? "최근 인증 통신 확인" : "최근 통신 없음 · 연결 재확인";
      return "브리지 " + (running ? "실행 중" : "종료") + " · " + connection
          + "\n브라우저 이벤트 수신 " + receivedEvents + "건 · 거부 " + rejectedRequests
          + "건" + (lastEvent == 0 ? " · 클릭 요청 미수신" : " · 마지막 수신 " + Math.max(0, (now-lastEvent)/1000) + "초 전");
    }
  }

  Snapshot snapshot() {
    return new Snapshot(running, lastAuthenticated.get(), lastEvent.get(), receivedEvents.get(), rejectedRequests.get());
  }

  private void authenticated() { lastAuthenticated.set(System.currentTimeMillis()); }
  private void reject(HttpExchange exchange, int status, String message) throws IOException {
    rejectedRequests.incrementAndGet();
    reply(exchange, status, message);
  }

  BrowserBridge(Consumer<Map<String, Object>> sink) throws IOException { this(sink, origin -> Map.of()); }

  BrowserBridge(Consumer<Map<String, Object>> sink, Function<String, Map<String,Object>> commands) throws IOException {
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    token = HexFormat.of().formatHex(random);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
    try {
    server.setExecutor(executor);
    server.createContext(
        "/health",
        x -> {
          try {
            if (!x.getRequestURI().getPath().equals("/health")) { reject(x, 404, "not found"); return; }
            if (!authorized(x)) { reject(x, 401, "unauthorized"); return; }
            if (!x.getRequestMethod().equals("GET")) { reject(x, 405, "GET required"); return; }
            authenticated();
            reply(x, 200, "Feature Mapper KR " + BuildInfo.VERSION);
          } finally { x.close(); }
        });
    server.createContext("/inspection/command", x -> {
      try {
        if (!x.getRequestURI().getPath().equals("/inspection/command")) { reject(x,404,"not found"); return; }
        if (!authorized(x)) { reject(x,401,"unauthorized"); return; }
        if (!x.getRequestMethod().equals("GET")) { reject(x,405,"GET required"); return; }
        String query=Objects.toString(x.getRequestURI().getRawQuery(),"");
        if(query.length()>2048||!query.startsWith("origin=")||query.indexOf('&')>=0){reject(x,400,"origin required");return;}
        String origin=URLDecoder.decode(query.substring(7),StandardCharsets.UTF_8);
        URI site=URI.create(origin);
        if(!Set.of("http","https").contains(site.getScheme())||site.getHost()==null||site.getUserInfo()!=null||site.getQuery()!=null||site.getFragment()!=null||!Objects.toString(site.getPath(),"").isEmpty()) {reject(x,400,"origin only");return;}
        String body=JsonWrite.text(commands.apply(origin));
        if(body.length()>65536){reject(x,413,"command too large");return;}
        authenticated();
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        reply(x,200,body);
      } catch(Exception invalid){reject(x,400,"invalid command");} finally{x.close();}
    });
    server.createContext(
        "/event",
        x -> {
          try {
            if (!x.getRequestURI().getPath().equals("/event")) { reject(x, 404, "not found"); return; }
            if (!authorized(x)) {
              reject(x, 401, "unauthorized");
              return;
            }
            if (!x.getRequestMethod().equals("POST")) {
              reject(x, 405, "POST required");
              return;
            }
            if (!String.valueOf(x.getRequestHeaders().getFirst("Content-Type"))
                .startsWith("application/json")) {
              reject(x, 415, "JSON required");
              return;
            }
            byte[] body = x.getRequestBody().readNBytes(65537);
            if (body.length > 65536) {
              reject(x, 413, "event too large");
              return;
            }
            Object value = Json.parse(new String(body, StandardCharsets.UTF_8), 65536, 1000);
            if (!(value instanceof Map<?, ?>)) {
              reject(x, 400, "object required");
              return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) value;
            sink.accept(event);
            authenticated();
            lastEvent.set(System.currentTimeMillis());
            receivedEvents.incrementAndGet();
            reply(x, 200, "accepted");
          } catch (Exception e) {
            reject(x, 400, "invalid event");
          } finally {
            x.close();
          }
        });
    server.start();
    } catch (RuntimeException | LinkageError failure) {
      // A host loader can expose HttpServer but hide HttpHandler/HttpExchange.
      // The constructor cannot publish a bridge for unload() to close on that path.
      try { server.stop(0); }
      catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
      finally { executor.shutdownNow(); }
      throw failure;
    }
  }

  private boolean authorized(HttpExchange x) {
    String header = x.getRequestHeaders().getFirst("X-Feature-Mapper-Token");
    return header != null
        && MessageDigest.isEqual(
            token.getBytes(StandardCharsets.US_ASCII), header.getBytes(StandardCharsets.US_ASCII));
  }

  private static void reply(HttpExchange x, int code, String value) throws IOException {
    byte[] body = value.getBytes(StandardCharsets.UTF_8);
    if(x.getResponseHeaders().getFirst("Content-Type")==null)x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    x.getResponseHeaders().set("Cache-Control", "no-store");
    x.sendResponseHeaders(code, body.length);
    try (OutputStream out = x.getResponseBody()) {
      out.write(body);
    }
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void close() {
    running = false;
    server.stop(0);
    executor.shutdownNow();
  }
}
