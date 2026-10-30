package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.Component;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;

/** Actual extension startup with the optional server module absent or hidden by its loader. */
public final class SimpleBridgeRuntimeTest extends WorkbenchTest {
  private static final String CHILD_MARKER = "BRIDGE_RUNTIME_CHILD";

  /** Mimic a host extension loader that cannot see the platform HTTP-server classes. */
  private static final class RestrictedLoader extends URLClassLoader {
    private final String denied;
    RestrictedLoader(URL[] urls) { this(urls, "com.sun.net.httpserver."); }
    RestrictedLoader(URL[] urls, String denied) {
      super(urls, SimpleBridgeRuntimeTest.class.getClassLoader());
      this.denied = denied;
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        if (name.startsWith(denied)) throw new ClassNotFoundException(name);
        if (name.startsWith("com.sun.net.httpserver."))
          return Class.forName(name, false, ClassLoader.getPlatformClassLoader());
        if (!name.startsWith("kr.") && !name.equals("burp.BurpExtender")) return super.loadClass(name, resolve);
        Class<?> type = findLoadedClass(name);
        if (type == null) type = findClass(name);
        if (resolve) resolveClass(type);
        return type;
      }
    }
  }

  private static void child(String mode) throws Exception {
    if (mode.equals("missing-module")) {
      scenario(mode);
      return;
    }
    check(ModuleLayer.boot().findModule("jdk.httpserver").isPresent(),
        "loader-isolation scenario retains the real HTTP-server module");
    var urls = new ArrayList<URL>();
    for (String entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
      urls.add(Path.of(entry).toAbsolutePath().toUri().toURL());
    try (var loader = new RestrictedLoader(urls.toArray(URL[]::new))) {
      try {
        Class.forName(SimpleBridgeRuntimeTest.class.getName(), true, loader)
            .getMethod("scenario", String.class).invoke(null, mode);
      } catch (InvocationTargetException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception failure) throw failure;
        if (cause instanceof Error failure) throw failure;
        throw new AssertionError(cause);
      }
    }
  }

  private static void runChild(String mode) throws Exception {
    Path folder = Files.createTempDirectory(Path.of(System.getProperty("user.home")), "bridge-runtime-");
    Process process = null;
    try {
      Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
      if (!Files.isExecutable(executable)) executable = executable.resolveSibling("java.exe");
      var command = new ArrayList<>(List.of(executable.toString(),
          mode.equals("missing-module") ? "--limit-modules" : "--add-modules",
          mode.equals("missing-module") ? "java.se" : "jdk.httpserver",
          "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Duser.home=" + folder,
          "-cp", System.getProperty("java.class.path"), SimpleBridgeRuntimeTest.class.getName(), mode));
      Path output = folder.resolve("child-output.txt");
      process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
      boolean completed = process.waitFor(45, TimeUnit.SECONDS);
      if (!completed) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
      String report = Files.readString(output, StandardCharsets.UTF_8);
      System.out.print(report);
      check(completed, mode + " child completes within its startup/cleanup deadline");
      check(process.exitValue() == 0, mode + " child exits successfully");
      var summary = Pattern.compile("(?m)^" + CHILD_MARKER + " " + Pattern.quote(mode)
          + " checks=(\\d+)$").matcher(report);
      check(summary.find(), mode + " reports its own completed behavioral checks");
      int childChecks = Integer.parseInt(summary.group(1));
      check(childChecks >= 20, mode + " executes the complete startup, mapping and cleanup scenario");
      checks += childChecks;
      check(!summary.find(), mode + " emits exactly one completed scenario summary");
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
      try (var paths = Files.walk(folder)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
      }
    }
  }

  private static void send(WorkbenchExtension extension, String url, int id) throws Exception {
    HttpRequest request = request(url, "GET", false, List.of());
    HttpResponse response = response(200, "{\"id\":101,\"runtime\":\"bridge-unavailable\"}");
    registerTrafficPacket(request, response);
    ToolSource source = proxy(ToolSource.class, (p, method, arguments) ->
        method.getName().equals("toolType") ? ToolType.PROXY : def(method));
    var outgoing = proxy(HttpRequestToBeSent.class, (p, method, arguments) -> switch (method.getName()) {
      case "messageId" -> id;
      case "toolSource" -> source;
      case "annotations" -> null;
      default -> HttpRequest.class.getMethod(method.getName(), method.getParameterTypes()).invoke(request, arguments);
    });
    extension.handleHttpRequestToBeSent(outgoing);
    var incoming = proxy(HttpResponseReceived.class, (p, method, arguments) -> switch (method.getName()) {
      case "messageId" -> id;
      case "initiatingRequest" -> request;
      case "toolSource" -> source;
      case "annotations" -> null;
      default -> HttpResponse.class.getMethod(method.getName(), method.getParameterTypes()).invoke(response, arguments);
    });
    extension.handleHttpResponseReceived(incoming);
    MapperIntegrationTest.idle(extension);
  }

  private static Object invoke(Object owner, String method) {
    try { return MapperIntegrationTest.invoke(owner, method, new Class<?>[0]); }
    catch (Exception error) { throw new AssertionError(error); }
  }

  /** A missing application class must still fail initialization instead of being treated as optional. */
  public static boolean unrelatedLinkage() throws Exception {
    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, method, arguments) -> def(method));
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    try {
      entry.initialize(SimpleWorkflowTest.previewApi());
      throw new AssertionError("A missing BrowserBridge application class was incorrectly hidden");
    } catch (Exception | LinkageError error) {
      for (Throwable cause = error; cause != null; cause = cause.getCause())
        if (cause instanceof NoClassDefFoundError && cause.getMessage() != null
            && cause.getMessage().replace('.', '/').contains("kr/featuremapper/BrowserBridge")) return true;
      throw new AssertionError("Initialization failed for a reason other than the deliberately hidden application class", error);
    } finally { SimpleWorkflowTest.unload(); }
  }

  private static long serverTimers() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.isAlive() && thread.getName().equals("idle-timeout-task")).count();
  }

  private static boolean waitForTimers(long expected) throws InterruptedException {
    for (int attempt = 0; attempt < 100; attempt++) {
      if (serverTimers() == expected) return true;
      Thread.sleep(20);
    }
    return false;
  }

  private static void partialModule(URL[] urls) throws Exception {
    long before = serverTimers();
    Class<?> serverType = Class.forName("com.sun.net.httpserver.HttpServer", true,
        ClassLoader.getPlatformClassLoader());
    Object warmup = serverType.getMethod("create", java.net.InetSocketAddress.class, int.class)
        .invoke(null, new java.net.InetSocketAddress("127.0.0.1", 0), 8);
    try {
      check(waitForTimers(before + 1), "server resource probe observes the real bound-server timer");
    } finally { serverType.getMethod("stop", int.class).invoke(warmup, 0); }
    check(waitForTimers(before), "server resource probe returns to baseline after normal stop");
    try (var loader = new RestrictedLoader(urls, "com.sun.net.httpserver.HttpHandler")) {
      Class<?> test = Class.forName(SimpleBridgeRuntimeTest.class.getName(), true, loader);
      test.getMethod("scenario", String.class).invoke(null, "partial-module");
      Field count = test.getSuperclass().getDeclaredField("checks"); count.setAccessible(true);
      checks += count.getInt(null);
    }
    check(waitForTimers(before), "partial HTTP-server linkage failure releases its already-bound server timer");
  }

  /** Public only to execute the exact same scenario through the restricted class loader. */
  public static void scenario(String mode) throws Exception {
    boolean missing = mode.equals("missing-module");
    check(ModuleLayer.boot().findModule("jdk.httpserver").isEmpty() == missing,
        mode + " has the intended module-presence precondition");
    try {
      String hiddenType = mode.equals("partial-module") ? "HttpHandler" : "HttpServer";
      Class.forName("com.sun.net.httpserver." + hiddenType, false, SimpleBridgeRuntimeTest.class.getClassLoader());
      throw new AssertionError("The scenario must actually hide " + hiddenType);
    } catch (ClassNotFoundException expected) {
      check(true, mode + " cannot resolve its required HTTP-server type through the extension loader");
    }
    AtomicInteger asynchronous = new AtomicInteger();
    var previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
      asynchronous.incrementAndGet(); error.printStackTrace();
    });
    var entry = new BurpExtender();
    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, method, arguments) -> def(method));
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    Object workspace = null;
    Path originalStore = null;
    try {
      // The production entry point calls invokeAndWait exactly as the reported Burp stack does.
      entry.initialize(SimpleWorkflowTest.previewApi()); edt(() -> {});
      var extension = (WorkbenchExtension) get(entry, "workbench");
      var root = (JPanel) get(extension, "root");
      var tabs = (JTabbedPane) get(extension, "tabs");
      workspace = get(extension, "featureMapper");
      Object currentWorkspace = workspace;
      check(PayloadIntegrationTest.suiteTabs.get() == 1, mode + " registers the single Workbench suite tab");
      check(get(entry, "payloadRunner") != null, mode + " completes the legacy/modern extension entry point");
      check(get(workspace, "bridge") == null, mode + " reports an unavailable bridge instead of a false active connection");
      String description = (String) invoke(workspace, "browserSummary");
      check(description.contains("브라우저 연결 사용 불가") && description.contains("jdk.httpserver"),
          mode + " explains the unavailable browser connection and runtime prerequisite");
      check(description.contains("HTTP 수집·자동 매핑") && description.contains("계속 사용할 수"),
          mode + " states the preserved HTTP mapping capability");
      edt(() -> {
        for (String tool : List.of("traffic", "mapper", "auth", "js", "environment", "response.recon", "payload", "bounty")) {
          check(SimpleWorkflowTest.toolVisible(root, tool),
              mode + " retains the " + tool + " navigation action");
          SimpleWorkflowTest.click(root, "wb.simple.tool." + tool);
          check(SimpleWorkflowTest.selectedFeature(tabs).equals(tool), mode + " opens the " + tool + " tool");
        }
      });
      Object model = get(workspace, "mapper");
      var disk = (DiskTrafficStore) get(extension, "trafficStore");
      edt(() -> ((WorkbenchScope) SimpleWorkflowTest.unchecked(extension, "localScope"))
          .add("https://bridge-runtime.test/", true));
      send(extension, "https://bridge-runtime.test/api/profile", 101);
      check(MapperIntegrationTest.count(model) == 0 && disk.size() == 0,
          mode + " preserves OFF collection when the helper is unavailable");
      edt(() -> ((JButton) SimpleWorkflowTest.unchecked(extension, "captureButton")).doClick(0));
      send(extension, "https://outside-runtime.test/api/profile", 102);
      check(MapperIntegrationTest.count(model) == 0 && disk.size() == 0,
          mode + " preserves SCOPE rejection without the helper");
      send(extension, "https://bridge-runtime.test/api/profile", 103);
      check(MapperIntegrationTest.count(model) == 1 && disk.size() == 1,
          mode + " captures and automatically maps one admitted HTTP response");
      Object store = invoke(model, "store");
      originalStore = (Path) invoke(store, "path");
      check(Files.isRegularFile(originalStore), mode + " retains the mapped original packet store");

      AtomicReference<Component> settings = new AtomicReference<>();
      Field opener = workspace.getClass().getDeclaredField("dialogOpener"); opener.setAccessible(true);
      Object dialog = Proxy.newProxyInstance(opener.getType().getClassLoader(), new Class<?>[]{opener.getType()},
          (p, method, arguments) -> { settings.set((Component) arguments[2]); return null; });
      opener.set(workspace, dialog);
      edt(() -> {
        invoke(currentWorkspace, "refresh");
        invoke(currentWorkspace, "updateIndicators");
        invoke(currentWorkspace, "showSettings");
        check(settings.get() != null, mode + " opens the actual Mapper settings after bridge failure");
        var status = (JTextArea) SimpleWorkflowTest.named(settings.get(), "fm.browser.status");
        check(status != null && status.getText().contains("브라우저 연결 사용 불가"),
            mode + " keeps browser-unavailable status visible in settings");
        invoke(currentWorkspace, "refresh");
        invoke(currentWorkspace, "updateIndicators");
        check(status.getText().contains("jdk.httpserver"), mode + " refresh retains the runtime recovery instruction");
      });
      check(PayloadIntegrationTest.sent.get() == 0, mode + " initialization, navigation and observed mapping send no target requests");
    } finally {
      try { SimpleWorkflowTest.unload(); edt(() -> {}); }
      finally { Thread.setDefaultUncaughtExceptionHandler(previous); }
    }
    check(workspace != null && Boolean.TRUE.equals(((JPanel) get(workspace, "root")).getClientProperty("fm.closed")),
        mode + " unload closes the Mapper UI without a bridge");
    check(originalStore != null && !Files.exists(originalStore), mode + " unload deletes temporary mapped originals");
    check(((ExecutorService) get(workspace, "io")).isShutdown()
        && ((ExecutorService) get(workspace, "previews")).isShutdown(), mode + " unload stops the file and preview workers");
    ((AutoCloseable) workspace).close();
    if (mode.equals("hidden-module")) {
      var urls = new ArrayList<URL>();
      for (String item : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
        urls.add(Path.of(item).toAbsolutePath().toUri().toURL());
      try (var loader = new RestrictedLoader(urls.toArray(URL[]::new), "kr.featuremapper.BrowserBridge")) {
        Object rejected = Class.forName(SimpleBridgeRuntimeTest.class.getName(), true, loader)
            .getMethod("unrelatedLinkage").invoke(null);
        check(Boolean.TRUE.equals(rejected), "unrelated missing application classes still fail extension initialization");
      }
      partialModule(urls.toArray(URL[]::new));
    }
    check(asynchronous.get() == 0, mode + " emits no asynchronous Swing errors during refresh and unload");
    System.out.println(CHILD_MARKER + " " + mode + " checks=" + checks);
  }

  public static void main(String[] args) throws Exception {
    if (args.length > 0) {
      try { child(args[0]); System.exit(0); }
      catch (Throwable error) { error.printStackTrace(); System.exit(1); }
      return;
    }
    runChild("missing-module");
    runChild("hidden-module");
    System.out.println("SimpleBridgeRuntimeTest: " + checks + " checks passed (isolated runtime and class-loader startup)");
  }
}
