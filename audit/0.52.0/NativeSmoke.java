package qa;

import burp.*;
import burp.api.montoya.*;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Benign native host smoke only: temporary profile, loopback hello GET, no security payloads. */
public final class NativeSmoke implements IBurpExtender, BurpExtension {
  private final BurpExtender extension = new BurpExtender();
  private final List<String> checks = new ArrayList<>();
  private final Path output = Path.of(System.getProperty("qa.output"));
  private HttpServer server;
  private ExecutorService pool;
  private MontoyaApi api;
  private final AtomicBoolean finished = new AtomicBoolean();
  private static final byte[] HELLO = "hello-workbench-smoke\n".getBytes(StandardCharsets.UTF_8);

  public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
    extension.registerExtenderCallbacks(callbacks);
  }

  public void initialize(MontoyaApi host) {
    api = host;
    try {
      extension.initialize(host);
      new Thread(this::run, "workbench-benign-native-smoke").start();
    } catch (Throwable failure) { finish(failure); }
  }

  private static Object field(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
    Method method = object.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(object, args);
  }

  private void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    checks.add("PASS " + name);
  }

  private static long count(Object store) throws Exception {
    return ((Number) call(store, "size", new Class<?>[0])).longValue();
  }

  private static void awaitCount(Object store, long expected) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < end) {
      if (count(store) == expected) return;
      Thread.sleep(30);
    }
    throw new AssertionError("expected " + expected + " captured hello packets, got " + count(store));
  }

  private void run() {
    try {
      checks.add("BURP " + api.burpSuite().version());
      checks.add("BOUNDARY actual Burp runtime / automated API and Swing controls / no manual desktop clicks");
      // This is a new temporary Burp instance, and the smoke does not need a proxy listener.
      api.burpSuite().importProjectOptionsFromJson("{\"proxy\":{\"request_listeners\":[]}}");
      Object workbench = field(extension, "workbench");
      Object store = field(workbench, "trafficStore");
      Object scope = field(workbench, "localScope");
      Object mapperWorkspace = field(workbench, "featureMapper");
      Object mapper = field(mapperWorkspace, "mapper");
      AtomicReference<List<String>> titles = new AtomicReference<>();
      AtomicReference<String> mode = new AtomicReference<>();
      SwingUtilities.invokeAndWait(() -> {
        try {
          JTabbedPane tabs = (JTabbedPane) field(workbench, "tabs");
          List<String> names = new ArrayList<>();
          for (int i = 0; i < tabs.getTabCount(); i++) names.add(tabs.getTitleAt(i));
          titles.set(names);
          mode.set(((JButton) field(workbench, "captureButton")).getText());
        } catch (Exception failure) { throw new RuntimeException(failure); }
      });
      check(titles.get().size() == 9, "single Next instance has eight tools plus Settings");
      check(titles.get().stream().filter("기능 자동 매핑"::equals).count() == 1, "Mapper appears once inside Workbench");
      check(mode.get().equals("OFF") && count(store) == 0, "fresh native session starts with collection OFF and no packets");
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      pool = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "native-smoke-hello"); t.setDaemon(true); return t; });
      server.setExecutor(pool);
      server.createContext("/", exchange -> {
        if (!exchange.getRequestMethod().equals("GET")) {
          exchange.sendResponseHeaders(405, -1);
        } else {
          exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
          exchange.sendResponseHeaders(200, HELLO.length);
          exchange.getResponseBody().write(HELLO);
        }
        exchange.close();
      });
      server.start();
      String origin = "http://127.0.0.1:" + server.getAddress().getPort();
      HttpRequestResponse off = api.http().sendRequest(HttpRequest.httpRequestFromUrl(origin + "/hello-off"));
      check(off.hasResponse() && off.response().statusCode() == 200, "benign local hello GET returns HTTP 200 through native Burp HTTP API");
      Thread.sleep(350);
      check(count(store) == 0 && ((Number) call(mapper, "count", new Class<?>[0])).longValue() == 0,
          "OFF excludes the hello packet from both capture and Mapper");
      // Add the owned loopback URL to Workbench's supported manual SCOPE list.
      call(scope, "add", new Class<?>[] {String.class, boolean.class}, origin + "/", false);
      SwingUtilities.invokeAndWait(() -> {
        try { ((JButton) field(workbench, "captureButton")).doClick(0); }
        catch (Exception failure) { throw new RuntimeException(failure); }
      });
      HttpRequestResponse hello = api.http().sendRequest(HttpRequest.httpRequestFromUrl(origin + "/hello-scope"));
      check(hello.hasResponse() && Arrays.equals(HELLO, hello.response().body().getBytes()), "SCOPE hello response matches the owned fixture bytes");
      awaitCount(store, 1);
      check(count(store) == 1, "SCOPE admits one hello packet without duplicate collection");
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (((Number) call(mapper, "count", new Class<?>[0])).longValue() != 1 && System.nanoTime() < end) Thread.sleep(30);
      check(((Number) call(mapper, "count", new Class<?>[0])).longValue() == 1, "embedded Mapper receives exactly the admitted hello packet");
      Object ref = call(store, "ref", new Class<?>[] {long.class}, 0L);
      HttpRequestResponse original = (HttpRequestResponse) call(ref, "packet", new Class<?>[0]);
      check(Arrays.equals(hello.request().toByteArray().getBytes(), original.request().toByteArray().getBytes())
          && Arrays.equals(hello.response().toByteArray().getBytes(), original.response().toByteArray().getBytes()),
          "stored hello request and response retain complete API packet bytes");
      Path trafficFile = (Path) call(store, "file", new Class<?>[0]);
      Object mapperStore = call(mapper, "store", new Class<?>[0]);
      Path mapperFile = (Path) call(mapperStore, "path", new Class<?>[0]);
      check(Files.exists(trafficFile) && Files.exists(mapperFile), "both native packet stores exist before unload");
      check(((AtomicInteger) field(workbench, "dropped")).get() == 0
          && ((AtomicLong) field(workbench, "processingErrors")).get() == 0,
          "hello collection has no drops or processing errors");
      api.extension().unload();
      long cleanup = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while ((Files.exists(trafficFile) || Files.exists(mapperFile)) && System.nanoTime() < cleanup) Thread.sleep(30);
      check(!Files.exists(trafficFile) && !Files.exists(mapperFile), "unload removes both owned raw packet stores");
      finish(null);
    } catch (Throwable failure) { finish(failure); }
  }

  private void finish(Throwable failure) {
    if (!finished.compareAndSet(false, true)) return;
    if (server != null) server.stop(0);
    if (pool != null) pool.shutdownNow();
    try {
      if (failure != null) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        checks.add("FAIL " + trace);
      }
      Files.writeString(output, String.join("\n", checks) + "\n" + (failure == null ? "NATIVE_SMOKE_SUCCESS" : "NATIVE_SMOKE_FAILED") + "\n");
    } catch (Exception writeFailure) { writeFailure.printStackTrace(); }
    System.exit(failure == null ? 0 : 1);
  }
}
