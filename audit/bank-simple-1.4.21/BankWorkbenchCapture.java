package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Read-only Robot views of the already executed live bank session, never a result fixture. */
public final class BankWorkbenchCapture {
  private static final String EXPECTED_JAR_SHA = "9d3d20af946add3498da79a021be31fafe8d84e046352463c4a559616187301c";
  private static final List<String> TOOLS = List.of("traffic", "mapper", "js", "environment",
      "response.recon", "auth", "payload", "bounty", "data", "activity", "exposure", "dashboard");
  private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
  private static volatile List<String> forbidden = List.of();

  private BankWorkbenchCapture() { }

  /** Values are inspected in memory only. They are never written to the capture manifest. */
  public static void setSecrets(Collection<String> values) {
    forbidden = values.stream().filter(Objects::nonNull).filter(value -> value.length() >= 4)
        .distinct().toList();
  }

  public static void capture(WorkbenchExtension extension, Object payload, Path images) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Capture must run outside EDT");
    if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Run the live session on isolated Xorg");
    if (!BuildInfo.VERSION.equals("1.4.21")) throw new IllegalStateException("Expected immutable Simple 1.4.21");
    Files.createDirectories(images);
    if (Files.exists(images.resolve("workbench-capture-verification.json")))
      throw new IllegalStateException("Preserve existing captures; choose a fresh result directory");
    JPanel root = (JPanel) field(extension, "root");
    JTabbedPane pages = (JTabbedPane) field(extension, "tabs");
    WorkbenchWindow desktop = (WorkbenchWindow) field(extension, "workbenchWindow");
    FolderNavigation navigation = (FolderNavigation) field(extension, "navigation");
    WorkbenchTheme theme = (WorkbenchTheme) field(extension, "theme");
    MontoyaApi api = (MontoyaApi) field(theme, "api");
    String savedTheme = api.persistence().preferences().getString(WorkbenchTheme.KEY);
    Boolean savedOverride = (Boolean) field(kr.packetui.VisualTheme.class, "darkOverride");
    Component[] initialPage = {null};
    Map<JTabbedPane,Integer> nested = new IdentityHashMap<>();
    List<Runnable> restoreSelections = new ArrayList<>();
    Set<String> rememberedSelections = new HashSet<>();
    edt(() -> { initialPage[0] = pages.getSelectedComponent(); rememberTabs(root, nested); });
    JFrame initialWindow = desktop.window;
    boolean initialShowing = initialWindow != null && initialWindow.isShowing();
    Rectangle initialBounds = initialWindow == null ? null : initialWindow.getBounds();
    int initialState = initialWindow == null ? Frame.NORMAL : initialWindow.getExtendedState();
    List<Map<String,Object>> records = new ArrayList<>();
    Throwable failure = null;
    Robot robot = new Robot(); robot.setAutoDelay(35);
    Path jar = Path.of(WorkbenchExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (!EXPECTED_JAR_SHA.equals(sha(jar))) throw new IllegalStateException("Capture JAR differs from frozen release");
    try {
      edt(desktop::open);
      await(() -> desktop.window != null && desktop.window.isShowing(), "live workspace opens");
      JFrame frame = desktop.window;
      Rectangle screen = frame.getGraphicsConfiguration().getBounds();
      if (screen.width < 1640 || screen.height < 1080)
        throw new IllegalStateException("Use isolated display at least1640x1080 for uncut1600x1000 window");
      if (initialWindow == null || (initialState & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH)
        await(() -> frame.getWidth() > screen.width * .9, "initial window-manager maximize settles");
      settle(); edt(() -> frame.setExtendedState(Frame.NORMAL)); settle();
      edt(() -> frame.setBounds(screen.x + 20, screen.y + 40, 1600, 1000));
      await(() -> frame.getWidth() == 1600 && frame.getHeight() == 1000, "native workspace size");
      for (boolean dark : new boolean[] {false, true}) {
        edt(() -> { if (kr.packetui.VisualTheme.dark() != dark) theme.toggle(); }); settle();
        Path folder = images.resolve(dark ? "dark" : "light"); Files.createDirectories(folder);
        for (String tool : TOOLS) {
          edt(() -> { extension.openFeature(tool); navigation.refreshSelection(); });
          settle();
          Map<String,Object> selection = prepareExistingResult(extension, payload, tool,
              restoreSelections, rememberedSelections);
          settle();
          Map<String,Object> captured = picture(robot, root, folder.resolve(tool + ".png"), tool, dark,
              Objects.toString(invoke(extension, "currentNavigationRoute"), ""));
          if (!selection.isEmpty()) captured.put("existingResultSelection", selection);
          records.add(captured);
          if (tool.equals("auth")) {
            JTabbedPane evidenceTabs = (JTabbedPane) named(root, "wb.auth.inline.tabs");
            edt(() -> evidenceTabs.setSelectedIndex(3)); settle();
            Map<String,Object> judgement = picture(robot, root, folder.resolve("auth-judgement.png"),
                "auth-judgement", dark, "auth");
            judgement.put("existingResultSelection", selection); records.add(judgement);
          }
        }
        captureInspection(extension, root, robot, folder, dark, records);
      }
    } catch (Throwable error) {
      failure = error;
    } finally {
      edt(() -> {
        for (int index = restoreSelections.size() - 1; index >= 0; index--) restoreSelections.get(index).run();
        if (initialPage[0] != null && pages.indexOfComponent(initialPage[0]) >= 0)
          pages.setSelectedComponent(initialPage[0]);
        nested.forEach((tabs, index) -> { if (index >= 0 && index < tabs.getTabCount()) tabs.setSelectedIndex(index); });
        kr.packetui.VisualTheme.setDarkOverride(savedOverride);
        if (savedTheme == null) api.persistence().preferences().deleteString(WorkbenchTheme.KEY);
        else api.persistence().preferences().setString(WorkbenchTheme.KEY, savedTheme);
        WorkbenchUi.style(root);
        ((Runnable) field(theme, "changed")).run();
        navigation.refreshSelection();
        if (desktop.window != null) {
          if (initialBounds != null) desktop.window.setBounds(initialBounds);
          desktop.window.setExtendedState(initialState);
          if (!initialShowing) desktop.hide();
        }
      });
      Map<String,Object> report = new LinkedHashMap<>();
      report.put("result", failure == null ? "completed" : "failed");
      report.put("version", BuildInfo.VERSION); report.put("jarSha256", sha(jar));
      report.put("capturedAtUTC", Instant.now().toString());
      report.put("nativeSwing", true); report.put("nativeBurpGui", false);
      report.put("realTargetResponses", true); report.put("syntheticResultFixture", false);
      report.put("knownSecretsProvided", !forbidden.isEmpty());
      report.put("captures", records);
      report.put("capturedPngCount", records.stream().filter(r -> "CAPTURED".equals(r.get("status"))).count());
      report.put("skippedForVisibleSecrets", records.stream().filter(r -> "NOT_CAPTURED_SECRET_VISIBLE".equals(r.get("status"))).count());
      report.put("scope", "Robot captures of existing production tool views after live local-bank execution; no data/result/model injection, no sends initiated by capture.");
      if (failure != null) report.put("failureType", failure.getClass().getSimpleName());
      Files.writeString(images.resolve("workbench-capture-verification.json"), MetadataExport.json(report));
    }
    if (failure instanceof Exception exception) throw exception;
    if (failure != null) throw new IllegalStateException("Native capture failed", failure);
  }

  /** Only retained selection/filter/source controllers are used; no target HTTP or data mutation. */
  private static Map<String,Object> prepareExistingResult(WorkbenchExtension extension, Object payload,
      String tool, List<Runnable> restores, Set<String> remembered) throws Exception {
    Map<String,Object> facts = new LinkedHashMap<>();
    if (tool.equals("payload")) edt(() -> {
      JTable table = (JTable) field(payload, "table");
      JPanel panel = (JPanel) field(payload, "panel");
      if (remembered.add(tool)) {
        int filter = ((Number) field(payload, "resultFilter")).intValue();
        int[] rows = table.getSelectedRows(); Point scroll = viewportPoint(table);
        restores.add(() -> {
          AbstractButton button = (AbstractButton) named(panel, "wb.payload.results.filter."
              + new String[] {"all", "observed", "control"}[filter]);
          if (button != null && !button.isSelected()) button.doClick(0);
          restoreRows(table, rows); restoreViewport(table, scroll);
        });
      }
      int chosen = -1;
      for (int row = 0; row < table.getModel().getRowCount(); row++)
        if (Objects.toString(table.getModel().getValueAt(row, 5), "").contains("SQL 조건 차이 후보")) { chosen = row; break; }
      if (chosen < 0) throw new IllegalStateException("The live SQL candidate row is required for a meaningful payload capture");
      int view = table.convertRowIndexToView(chosen);
      if (view < 0) {
        ((AbstractButton) named(panel, "wb.payload.results.filter.all")).doClick(0);
        view = table.convertRowIndexToView(chosen);
      }
      ((JTabbedPane) field(payload, "workflow")).setSelectedIndex(1);
      table.setRowSelectionInterval(view, view); table.scrollRectToVisible(table.getCellRect(view, 0, true));
      facts.put("modelRow", chosen); facts.put("viewRow", view);
      facts.put("verdict", table.getModel().getValueAt(chosen, 5));
      facts.put("httpStatus", table.getModel().getValueAt(chosen, 2));
      facts.put("retainedRows", table.getModel().getRowCount());
      facts.put("actualRequestAndResponseSelected", !((JTextArea) field(payload, "detail")).getText().isEmpty());
    });
    if (tool.equals("auth")) edt(() -> {
      AuthComparePanel auth = (AuthComparePanel) field(extension, "authCompare");
      if (remembered.add(tool)) {
        int filter = auth.historyFilter.getSelectedIndex(); String search = auth.historySearch.getText();
        int[] rows = auth.history.getSelectedRows(); Point scroll = viewportPoint(auth.history);
        int account = auth.inlinePreview.account.getSelectedIndex();
        restores.add(() -> {
          auth.historyFilter.setSelectedIndex(filter); auth.historySearch.setText(search);
          restoreRows(auth.history, rows); auth.inlinePreview.account.setSelectedIndex(account);
          restoreViewport(auth.history, scroll);
        });
      }
      AuthComparePanel.Run chosen = null; int compared = -1, reference = -1;
      for (AuthComparePanel.Run run : auth.runs) {
        if (!run.original.pathWithoutQuery().startsWith("/check_balance/")) continue;
        int owner = -1, other = -1;
        for (int slot = 0; slot < run.packets.length; slot++) {
          if (AuthRunSummary.reference(run.packets[slot]) && "wb_alice".equals(run.context.labels()[slot])) owner = slot;
          if ("wb_bob".equals(run.context.labels()[slot])) other = slot;
        }
        if (owner >= 0 && other >= 0 && run.packets[other].response != null) {
          chosen = run; reference = owner; compared = other; break;
        }
      }
      if (chosen == null) throw new IllegalStateException("The actual Alice-reference/Bob IDOR comparison is required");
      auth.historyFilter.setSelectedIndex(0); auth.historySearch.setText("");
      int row = auth.history.convertRowIndexToView(auth.runs.indexOf(chosen));
      if (row < 0) throw new IllegalStateException("Actual bank auth comparison is hidden by an unexpected filter");
      auth.history.setRowSelectionInterval(row, row); auth.history.scrollRectToVisible(auth.history.getCellRect(row, 0, true));
      auth.inlinePreview.account.setSelectedIndex(compared);
      // The frozen release's first response-tab baseline caption assumes account slot0.
      // The retained difference tab labels the true baseline generically and avoids that misleading caption.
      ((JTabbedPane) named(auth, "wb.auth.inline.tabs")).setSelectedIndex(1);
      facts.put("runNo", chosen.no); facts.put("path", chosen.original.pathWithoutQuery());
      facts.put("referenceActor", chosen.context.labels()[reference]);
      facts.put("comparedActor", chosen.context.labels()[compared]);
      facts.put("referenceHttpStatus", chosen.baseline().statusCode());
      facts.put("comparedHttpStatus", chosen.packets[compared].response.statusCode());
      facts.put("comparedVerdict", chosen.packets[compared].finding().verdict().name());
      facts.put("visibleTab", "계정 간 차이");
      facts.put("knownFrozenUiLimitation", "응답 비교 탭의 기준 캡션은 slot0에 고정되어 실제 기준 응답과 계정 이름이 다를 수 있음. 원래 계정 간 차이/판정 근거 탭을 사용함.");
    });
    if (tool.equals("js")) {
      JsAnalysisPanel js = (JsAnalysisPanel) field(extension, "jsAnalysis");
      edt(() -> {
        if (remembered.add(tool)) {
          Object selected = js.sources.getSelectedItem(); int[] rows = js.urlTable.getSelectedRows();
          Point scroll = viewportPoint(js.urlTable);
          restores.add(() -> { js.sources.setSelectedItem(selected); restoreRows(js.urlTable, rows); restoreViewport(js.urlTable, scroll); });
        }
        String selected = null;
        for (int index = 0; index < js.sources.getItemCount(); index++) {
          String source = js.sources.getItemAt(index);
          if (source.contains("/static/dashboard.js")) { selected = source; break; }
        }
        if (selected == null) for (int index = 0; index < js.sources.getItemCount(); index++) {
          String source = js.sources.getItemAt(index);
          if (source.contains("/static/openapi.json")) { selected = source; break; }
        }
        if (selected == null) throw new IllegalStateException("Actual dashboard.js/OpenAPI source is missing from the analysis history");
        js.sources.setSelectedItem(selected);
      });
      await(js::idle, "selected retained JS source is restored from local analysis history");
      edt(() -> {
        js.results.setSelectedIndex(0);
        if (js.urlTable.getRowCount() == 0) throw new IllegalStateException("The selected real JS source has no visible endpoint results");
        int row = 0;
        for (int index = 0; index < js.urlTable.getRowCount(); index++)
          if (Objects.toString(js.endpoints.getValueAt(js.urlTable.convertRowIndexToModel(index), 0), "").endsWith("/transfer")) { row = index; break; }
        js.urlTable.setRowSelectionInterval(row, row); js.urlTable.scrollRectToVisible(js.urlTable.getCellRect(row, 0, true));
        facts.put("source", js.sources.getSelectedItem()); facts.put("visibleEndpoints", js.urlTable.getRowCount());
        int modelRow = js.urlTable.convertRowIndexToModel(row);
        facts.put("selectedEndpoint", js.endpoints.getValueAt(modelRow, 0));
        facts.put("selectedMethod", js.endpoints.getValueAt(modelRow, 1));
        facts.put("sourceLine", js.endpoints.getValueAt(modelRow, 6));
        facts.put("sourceLocateControllerApplied", true);
      });
    }
    return facts;
  }

  private static Point viewportPoint(Component child) {
    Container ancestor = SwingUtilities.getAncestorOfClass(JViewport.class, child);
    return ancestor instanceof JViewport viewport ? viewport.getViewPosition() : null;
  }
  private static void restoreViewport(Component child, Point position) {
    Container ancestor = SwingUtilities.getAncestorOfClass(JViewport.class, child);
    if (position != null && ancestor instanceof JViewport viewport) viewport.setViewPosition(position);
  }
  private static void restoreRows(JTable table, int[] rows) {
    table.clearSelection();
    for (int row : rows) if (row >= 0 && row < table.getRowCount()) table.addRowSelectionInterval(row, row);
  }

  private static void captureInspection(WorkbenchExtension extension, JPanel root, Robot robot, Path folder,
      boolean dark, List<Map<String,Object>> records) throws Exception {
    if (field(extension, "scopeInspectionPanel") == null) {
      records.add(Map.of("feature", "scope", "theme", dark ? "dark" : "light", "status", "NOT_RUN",
          "reason", "No live inspection panel was created by this run")); return;
    }
    TargetWindow controller = (TargetWindow) field(extension, "scopeInspectionWindow");
    JDialog existing = controller == null ? null : (JDialog) field(controller, "window");
    boolean wasShowing = existing != null && existing.isShowing();
    Rectangle before = existing == null ? null : existing.getBounds();
    edt(() -> {
      AbstractButton button = (AbstractButton) named(root, "wb.navigation.primary.inspect");
      if (button == null) throw new IllegalStateException("Missing retained inspection controller");
      button.doClick(0);
    });
    TargetWindow live = (TargetWindow) field(extension, "scopeInspectionWindow");
    await(() -> field(live, "window") instanceof JDialog dialog && dialog.isShowing(), "live inspection window");
    JDialog dialog = (JDialog) field(live, "window");
    try {
      edt(() -> { dialog.toFront(); dialog.validate(); }); settle();
      records.add(picture(robot, dialog.getContentPane(), folder.resolve("scope.png"), "scope", dark, "scope"));
    } finally {
      edt(() -> {
        if (!wasShowing) live.closeWindow();
        else if (before != null) dialog.setBounds(before);
      });
    }
  }

  private static Map<String,Object> picture(Robot robot, Container view, Path destination,
      String feature, boolean dark, String route) throws Exception {
    Map<String,Object> record = new LinkedHashMap<>();
    record.put("feature", feature); record.put("actualRoute", route); record.put("theme", dark ? "dark" : "light");
    Rectangle[] bounds = {null}; List<Map<String,Object>> components = new ArrayList<>();
    Set<String> unsafe = new LinkedHashSet<>();
    edt(() -> {
      bounds[0] = new Rectangle(view.getLocationOnScreen(), view.getSize());
      inspect(view, components, unsafe);
    });
    record.put("bounds", List.of(bounds[0].x, bounds[0].y, bounds[0].width, bounds[0].height));
    record.put("visibleComponents", components);
    record.put("rowsInVisibleTableModels", components.stream().filter(c -> c.containsKey("rows"))
        .mapToInt(c -> ((Number)c.get("rows")).intValue()).sum());
    record.put("visibleTableRows", components.stream().filter(c -> c.containsKey("visibleRows"))
        .mapToInt(c -> ((Number)c.get("visibleRows")).intValue()).sum());
    record.put("viewState", components.stream().anyMatch(c -> c.containsKey("visibleRows") && ((Number)c.get("visibleRows")).intValue() > 0)
        ? "NONEMPTY_VISIBLE_TABLE" : "NO_VISIBLE_TABLE_ROWS");
    if (!unsafe.isEmpty()) {
      record.put("status", "NOT_CAPTURED_SECRET_VISIBLE"); record.put("unsafeComponents", List.copyOf(unsafe)); return record;
    }
    Rectangle display = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    if (!display.contains(bounds[0])) throw new IllegalStateException("Native view exceeds isolated display: " + feature);
    robot.mouseMove(display.x + display.width - 10, display.y + display.height - 10); settle();
    ImageIO.write(robot.createScreenCapture(bounds[0]), "png", destination.toFile());
    record.put("status", "CAPTURED"); record.put("file", destination.getParent().getFileName() + "/" + destination.getFileName());
    record.put("sha256", sha(destination)); record.put("bytes", Files.size(destination)); return record;
  }

  private static void inspect(Component component, List<Map<String,Object>> records, Set<String> unsafe) {
    if (!component.isShowing()) return;
    String name = Objects.toString(component.getName(), "");
    Map<String,Object> item = new LinkedHashMap<>();
    item.put("class", component.getClass().getName()); item.put("name", name);
    item.put("bounds", List.of(component.getX(), component.getY(), component.getWidth(), component.getHeight()));
    if (component instanceof JTextComponent text && !(component instanceof JPasswordField) && secret(text.getText())) unsafe.add(name + ":text");
    if (component instanceof JLabel label && secret(label.getText())) unsafe.add(name + ":label");
    if (component instanceof AbstractButton button && secret(button.getText())) unsafe.add(name + ":button");
    if (component instanceof JTable table) {
      item.put("rows", table.getRowCount()); item.put("columns", table.getColumnCount());
      item.put("selectedRows", Arrays.stream(table.getSelectedRows()).boxed().toList());
      Rectangle visible = table.getVisibleRect();
      int first = table.rowAtPoint(new Point(visible.x, visible.y));
      int last = table.rowAtPoint(new Point(visible.x, visible.y + Math.max(0, visible.height - 1)));
      if (first >= 0 && last < 0) last = table.getRowCount() - 1;
      item.put("visibleRows", visible.isEmpty() || first < 0 ? 0 : Math.max(0, last - first + 1));
      for (int row = Math.max(0, first); !visible.isEmpty() && first >= 0 && row <= last; row++) for (int column = 0; column < table.getColumnCount(); column++) {
        if (visible.intersects(table.getCellRect(row, column, true)) && secret(Objects.toString(table.getValueAt(row, column), "")))
          unsafe.add(name + ":table");
      }
    }
    records.add(item);
    if (component instanceof Container parent) for (Component child : parent.getComponents()) inspect(child, records, unsafe);
  }

  private static boolean secret(String text) {
    if (text == null || text.isEmpty()) return false;
    if (JWT.matcher(text).find()) return true;
    return forbidden.stream().anyMatch(text::contains);
  }

  private static void rememberTabs(Component component, Map<JTabbedPane,Integer> state) {
    if (component instanceof JTabbedPane tabs) state.put(tabs, tabs.getSelectedIndex());
    if (component instanceof Container parent) for (Component child : parent.getComponents()) rememberTabs(child, state);
  }
  private static Component named(Component component, String name) {
    if (name.equals(component.getName())) return component;
    if (component instanceof Container parent) for (Component child : parent.getComponents()) {
      Component found = named(child, name); if (found != null) return found;
    }
    return null;
  }
  private static Object field(Object owner, String name) {
    try {
      Class<?> type = owner instanceof Class<?> clazz ? clazz : owner.getClass();
      Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner instanceof Class<?> ? null : owner);
    } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Missing field: " + name, failure); }
  }
  private static Object invoke(Object owner, String name) {
    try { Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(owner); }
    catch (ReflectiveOperationException failure) { throw new IllegalStateException("Missing view method: " + name, failure); }
  }
  private static void edt(Runnable operation) {
    try { if (SwingUtilities.isEventDispatchThread()) operation.run(); else SwingUtilities.invokeAndWait(operation); }
    catch (Exception failure) { throw new IllegalStateException("Native EDT operation failed", failure); }
  }
  private static void await(BooleanSupplier condition, String description) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) { boolean[] ready = {false}; edt(() -> ready[0] = condition.getAsBoolean()); if (ready[0]) return; Thread.sleep(30); }
    throw new IllegalStateException("Timed out: " + description);
  }
  private static void settle() throws Exception { edt(() -> {}); Toolkit.getDefaultToolkit().sync(); Thread.sleep(200); }
  private static String sha(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
}
