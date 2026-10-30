package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTable;

/** Actual integrated UI actions against an isolated HTTP application; no native Burp GUI. */
public final class ScopeInspectionUiTest extends WorkbenchTest {
  static void await(BooleanSupplier condition, String reason) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      edt(() -> {});
      if (condition.getAsBoolean()) return;
      Thread.sleep(10);
    }
    throw new AssertionError("Timed out: " + reason);
  }
  static Object value(Object owner, String field) {
    try { return get(owner, field); } catch (Exception e) { throw new AssertionError(e); }
  }
  static void spin(Object owner, String field, int selected) {
    ((JSpinner)value(owner, field)).setValue(selected);
  }
  static boolean terminal(ScopeInspection.Snapshot snapshot) {
    return snapshot != null && List.of(ScopeInspection.State.COMPLETED, ScopeInspection.State.FAILED,
        ScopeInspection.State.STOPPED).contains(snapshot.state());
  }
  static JTable table(Component panel, String suffix) {
    Component value = SimpleWorkflowTest.named(panel, "wb.scope.inspection." + suffix);
    if (!(value instanceof JTable table)) throw new AssertionError("Missing actual table: " + suffix);
    return table;
  }
  static void click(Component panel, String suffix) { SimpleWorkflowTest.click(panel, "wb.scope.inspection." + suffix); }
  static void selectTable(Component panel, String suffix) {
    JTable table = table(panel, suffix);
    if (table.getRowCount() < 1) throw new AssertionError("No row to select: " + suffix);
    table.setRowSelectionInterval(0, 0);
  }
  static String visibleText(Component root) {
    var text = new StringBuilder();
    for (Component component : SimpleWorkflowTest.visibleComponents(root)) {
      if (component instanceof javax.swing.text.JTextComponent input) text.append(input.getText());
      if (component instanceof javax.swing.JLabel label) text.append(label.getText());
      if (component instanceof JTable table) for (int row = 0; row < table.getRowCount(); row++)
        for (int col = 0; col < table.getColumnCount(); col++) text.append(table.getValueAt(row, col));
    }
    return text.toString();
  }

  static void selectionRefresh(burp.api.montoya.MontoyaApi api,ScopeInspection.Snapshot live) throws Exception {
    var a=live.findings().stream().filter(f->f.category().equals("SECRET")&&f.account().equals("비로그인")).findFirst().orElseThrow();
    var b=live.findings().stream().filter(f->f.category().equals("SECRET")&&f.account().equals("Alice")&&f.fingerprint().equals(a.fingerprint())).findFirst().orElseThrow();
    var pageA=live.pages().stream().filter(p->p.original()!=null&&p.original().id().equals(a.originalId())).findFirst().orElseThrow();
    var pageB=live.pages().stream().filter(p->p.original()!=null&&p.original().id().equals(b.originalId())).findFirst().orElseThrow();
    var initial=new ScopeInspection.Snapshot("selection-fixture",ScopeInspection.State.READY,"선택 복원 회귀 검증",0,2,0,0,0,0,List.of(a,b),List.of(pageA,pageB),List.of(),0,0);
    var changed=new ScopeInspection.Snapshot("selection-fixture",ScopeInspection.State.READY,"원본 순서 변경",0,2,0,0,0,0,List.of(a,b),List.of(pageB,pageA),List.of(),0,0);
    ScopeInspectionPanel.Actions noop=new ScopeInspectionPanel.Actions(){
      public void original(DiskTrafficStore.PacketRef ref){} public void repeater(DiskTrafficStore.PacketRef ref){}
      public void compare(DiskTrafficStore.PacketRef ref){} public void evidence(List<DiskTrafficStore.PacketRef> refs,String note){}
      public void tool(String id,DiskTrafficStore.PacketRef ref){}
    };
    var holder=new AtomicReference<ScopeInspectionPanel>();
    edt(()->holder.set(new ScopeInspectionPanel(api,()->List.of(new ScopeInspection.Profile("anonymous","비로그인",Map.of(),null)),noop)));
    ScopeInspectionPanel panel=holder.get();
    try{edt(()->{
      panel.accept(initial);panel.pages.setSelectedIndex(indexOf(panel.pages,"secrets"));JTable secrets=panel.tables.get("secrets");
      secrets.getRowSorter().setSortKeys(List.of(new javax.swing.RowSorter.SortKey(2,javax.swing.SortOrder.ASCENDING)));
      int selected=secrets.convertRowIndexToView(0);secrets.setRowSelectionInterval(selected,selected);panel.accept(initial);
      check(panel.selectedOriginal().id().equals(pageA.original().id()),"sorted finding refresh preserves the selected account and exact original despite a shared fingerprint");
      panel.pages.setSelectedIndex(indexOf(panel.pages,"paths"));JTable paths=panel.tables.get("paths");
      paths.getRowSorter().setSortKeys(List.of(new javax.swing.RowSorter.SortKey(0,javax.swing.SortOrder.ASCENDING)));
      selected=paths.convertRowIndexToView(0);paths.setRowSelectionInterval(selected,selected);panel.accept(changed);
      check(panel.selectedOriginal().id().equals(pageA.original().id()),"sorted path refresh captures the old exact packet identity before replacing page order");
    });}finally{edt(panel::close);}
  }

  public static void main(String[] args) throws Exception {
    try (var fixture = new ScopeInspectionFixture()) {
      var entry = new BurpExtender();
      entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
      edt(() -> entry.initialize(fixture.api)); edt(() -> {});
      var extension = (WorkbenchExtension)get(entry, "workbench");
      var workspace = (JPanel)get(extension, "root");
      var dialog = new AtomicReference<Component>();
      edt(() -> {
        extension.dialogOpener = (owner, title, body, modal, closed) -> { dialog.set(body); return null; };
        SimpleWorkflowTest.click(workspace, "wb.scope.inspection.open");
        check(dialog.get() instanceof ScopeInspectionPanel, "global one-touch button opens the actual integrated panel");
        check(dialog.get() == value(extension, "scopeInspectionPanel"), "global entry retains its production inspection controller");
        for (String id : List.of("traffic", "mapper", "auth", "js", "environment", "response.recon", "payload", "bounty")) {
          check(SimpleWorkflowTest.toolVisible(workspace, id),
              "integrated inspection preserves existing " + id + " entry point");
          SimpleWorkflowTest.click(workspace, "wb.simple.tool." + id);
        }
      });
      check(fixture.visits.isEmpty(), "opening the inspection and existing tools sends zero target HTTP");
      ScopeInspectionPanel global = (ScopeInspectionPanel)dialog.get();
      WorkbenchScope.current(fixture.api).add(fixture.origin + "/app/", false);
      edt(() -> {
        global.url.setText(fixture.origin + "/app/"); global.browserMode.setSelected(false);
        spin(global, "depth", 0); spin(global, "delay", 100); global.start.doClick(0);
      });
      await(() -> !global.current.pages().isEmpty(), "actual global inspection retains a response before unload");
      edt(() -> global.pause.doClick(0));
      await(() -> global.current.state() == ScopeInspection.State.PAUSED, "pause global inspection before opening native originals");
      var nativeHosts = new ArrayList<Component>();
      var preview = new AtomicReference<JPanel>();
      edt(() -> {
        global.pages.setSelectedIndex(indexOf(global.pages, "paths")); selectTable(global,"paths");
        var ref=global.selectedOriginal();global.original.doClick(0);preview.set((JPanel)dialog.get());
        PacketInspector request=(PacketInspector)SimpleWorkflowTest.named(preview.get(),"wb.scope.inspection.original.request");
        PacketInspector response=(PacketInspector)SimpleWorkflowTest.named(preview.get(),"wb.scope.inspection.original.response");
        request.more.doClick(0);nativeHosts.add(dialog.get());
        check("wb.scope.inspection.original.request.native".equals(dialog.get().getName())
            &&ref.id().equals(((JComponent)dialog.get()).getClientProperty("wb.scope.inspection.original.id")),
            "request full-view action opens the exact original in an independent native host");
        response.more.doClick(0);nativeHosts.add(dialog.get());
        check("wb.scope.inspection.original.response.native".equals(dialog.get().getName())
            &&ref.id().equals(((JComponent)dialog.get()).getClientProperty("wb.scope.inspection.original.id")),
            "response full-view action opens the same original without replacing the main packet viewer");
      });
      SimpleWorkflowTest.unload();
      check(global.runner != null && global.runner.snapshot().state() == ScopeInspection.State.CLOSED,
          "actual extension unload closes its integrated request coordinator");
      check(((PacketInspector)SimpleWorkflowTest.named(preview.get(),"wb.scope.inspection.original.request")).closed(),
          "unload closes inspection preview workers as well as their windows");
      check(nativeHosts.stream().allMatch(host -> visibleText(host).contains("패킷 없음")),
          "unload clears native editor message references");
      int unloadedCount = fixture.visits.size(); Thread.sleep(150); edt(() -> {});
      check(fixture.visits.size() == unloadedCount, "unloaded extension dispatches no further target requests");
      fixture.visits.clear();

      var scope = WorkbenchScope.install(fixture.api); scope.add(fixture.origin + "/app/", false);
      var observed = new CopyOnWriteArrayList<DiskTrafficStore.PacketRef>();
      var original = new AtomicReference<DiskTrafficStore.PacketRef>();
      var repeated = new AtomicReference<DiskTrafficStore.PacketRef>();
      var compared = new AtomicReference<DiskTrafficStore.PacketRef>();
      var captured = new AtomicReference<List<DiskTrafficStore.PacketRef>>();
      var fullRequest = new AtomicReference<DiskTrafficStore.PacketRef>();
      var fullResponse = new AtomicReference<DiskTrafficStore.PacketRef>();
      var tool = new AtomicReference<String>();
      ScopeInspectionPanel.Actions actions = new ScopeInspectionPanel.Actions() {
        public void original(DiskTrafficStore.PacketRef ref) { original.set(ref); }
        public void repeater(DiskTrafficStore.PacketRef ref) { repeated.set(ref); }
        public void compare(DiskTrafficStore.PacketRef ref) { compared.set(ref); }
        public void evidence(List<DiskTrafficStore.PacketRef> refs, String note) { captured.set(List.copyOf(refs)); }
        public void tool(String id, DiskTrafficStore.PacketRef ref) { tool.set(id); }
        public void observed(DiskTrafficStore.PacketRef ref) { observed.add(ref); }
        public void fullOriginal(DiskTrafficStore.PacketRef ref,boolean request) { (request?fullRequest:fullResponse).set(ref); }
      };
      var holder = new AtomicReference<ScopeInspectionPanel>();
      edt(() -> holder.set(new ScopeInspectionPanel(fixture.api, () -> fixture.options(true).profiles(), actions)));
      ScopeInspectionPanel panel = holder.get();
      try {
        edt(() -> {
          panel.url.setText(fixture.origin + "/app/");
          panel.browserMode.setSelected(false); panel.compareAccounts.setSelected(true);
          spin(panel, "budget", 200); spin(panel, "depth", 2); spin(panel, "delay", 0); spin(panel, "timeout", 3);
          check(!((AbstractButton)SimpleWorkflowTest.named(panel, "wb.scope.inspection.evidence")).isEnabled(),
              "evidence handoff is disabled before a selected original exists");
          check(panel.profile.getItemCount() == 2, "inspection exposes configured account profiles without credentials");
          for (int i = 0; i < panel.profile.getItemCount(); i++) check(!panel.profile.getItemAt(i).toString().contains("SCOPE_ALICE_SESSION"),
              "profile rendering never reveals its authentication snapshot");
        });
        check(fixture.visits.isEmpty(), "configuring Scope/account/limits does not execute the inspection");
        edt(() -> panel.start.doClick(0));
        await(() -> terminal(panel.current), "first complete inspection");
        ScopeInspection.Snapshot first = panel.current;
        check(first.state() == ScopeInspection.State.COMPLETED, "actual loopback inspection completes through the production panel");
        check(first.sent() <= panel.options.budget(), "all integrated request phases share the configured request budget");
        check(first.sent() == fixture.visits.size(), "displayed request count exactly matches real target requests including controls");
        check(!first.pages().isEmpty() && first.pages().stream().allMatch(p -> p.original() != null), "each inspected response retains its exact original packet");
        check(first.pages().stream().anyMatch(p -> p.classification().equals("MISSING_CONTROL")), "soft-404 control responses retain inspectable originals");
        check(first.pages().stream().anyMatch(p -> p.classification().equals("IDENTITY_CONTROL")), "multi-account identity controls are visible in retained response coverage");
        check(first.pages().stream().map(ScopeInspection.Page::account).distinct().count() >= 2, "account comparison retains separate profile provenance");
        check(first.findings().stream().anyMatch(f -> f.category().equals("SECRET")), "actual fetched responses feed the integrated secret analyzer");
        check(first.findings().stream().anyMatch(f -> f.category().equals("API")), "actual fetched documents and JS feed the API inventory");
        check(first.findings().stream().anyMatch(f -> f.category().equals("DATABASE")), "DB-specific evidence is retained as an inference");
        check(fixture.visits.stream().noneMatch(v -> v.path().startsWith("/outside/")), "same-origin redirect cannot escape the selected application Scope");
        check(fixture.visits.stream().noneMatch(v -> v.path().equals("/app/logout") || v.path().equals("/app/api/write-only")), "menu/API discovery excludes logout and write-only routes");
        check(fixture.visits.stream().allMatch(v -> List.of("GET", "HEAD").contains(v.method())), "one-touch inspection sends only read-oriented methods");
        check(observed.size() == observed.stream().map(DiskTrafficStore.PacketRef::id).distinct().count(), "progress callbacks forward each original to existing analyzers only once");
        check(!observed.isEmpty(), "integrated capture is explicitly shared with retained analyzers");
        int selectionSent=fixture.visits.size();selectionRefresh(fixture.api,first);
        check(fixture.visits.size()==selectionSent,"selection-only snapshot refresh sends no target requests");
        int handoffSent=fixture.visits.size();
        edt(() -> {
          panel.pages.setSelectedIndex(indexOf(panel.pages, "secrets"));
          selectTable(panel, "secrets");
          check(!visibleText(panel).contains(ScopeInspectionFixture.API_SECRET), "integrated result tables and details mask the fetched secret");
          click(panel, "original"); click(panel, "repeater"); click(panel, "compare"); click(panel, "evidence");
          JPanel content=panel.originalContent(original.get());
          ((PacketInspector)SimpleWorkflowTest.named(content,"wb.scope.inspection.original.request")).more.doClick(0);
          ((PacketInspector)SimpleWorkflowTest.named(content,"wb.scope.inspection.original.response")).more.doClick(0);
          panel.closeOriginalContent(content);
        });
        check(original.get() != null && repeated.get() != null && compared.get() != null, "selected result keeps original/Repeater/account comparison handoffs available");
        check(original.get().id().equals(repeated.get().id()) && original.get().id().equals(compared.get().id()), "all handoffs resolve the same immutable selected original");
        check(captured.get() != null && captured.get().stream().anyMatch(p -> p.id().equals(original.get().id())), "evidence handoff preserves the selected original reference");
        check(fullRequest.get()==original.get()&&fullResponse.get()==original.get(), "both full native actions preserve the exact selected PacketRef");
        check(fixture.visits.size()==handoffSent,"native full-view callbacks and original handoffs send no target requests");
        String privateWire = original.get().response().toString();
        check(privateWire.contains(ScopeInspectionFixture.API_SECRET), "masking the result view never destroys the exact private response evidence");
        int preparedCount = fixture.visits.size(); edt(() -> {});
        check(fixture.visits.size() == preparedCount, "preparing original/Repeater/evidence handoffs sends no extra HTTP");

        fixture.revision.set(2);
        edt(() -> panel.start.doClick(0));
        await(() -> terminal(panel.current) && !panel.current.id().equals(first.id()), "second complete inspection");
        check(!panel.current.id().equals(first.id()), "reinspection uses a distinct execution identity");
        await(() -> table(panel, "changes").getRowCount() > 0, "masked history comparison finishes asynchronously");
        edt(() -> check(table(panel, "changes").getRowCount() > 0, "same-condition reinspection exposes response or finding changes"));

        edt(() -> { spin(panel, "delay", 30); panel.start.doClick(0); });
        await(() -> panel.pause.isEnabled(), "pause action becomes available");
        edt(() -> panel.pause.doClick(0));
        await(() -> panel.current != null && panel.current.state() == ScopeInspection.State.PAUSED, "manual inspection pause");
        int pausedSent = panel.current.sent(); Thread.sleep(120); edt(() -> {});
        check(panel.current.sent() == pausedSent, "paused UI state dispatches no new requests");
        edt(() -> panel.resume.doClick(0));
        await(() -> panel.current.sent() > pausedSent, "valid Scope resumes the same inspection");
        check(panel.current.state() == ScopeInspection.State.RUNNING, "resume reuses the retained inspection with live Scope validation");
        edt(() -> panel.stop.doClick(0));
        await(() -> panel.current.state() == ScopeInspection.State.STOPPED, "explicit UI stop");
        int stoppedSent = panel.current.sent(); Thread.sleep(120); edt(() -> {});
        check(panel.current.sent() == stoppedSent, "explicit UI stop retains partial results without further requests");

        edt(() -> panel.start.doClick(0));
        await(() -> panel.pause.isEnabled(), "fresh inspection can start after stop");
        edt(() -> panel.pause.doClick(0));
        await(() -> panel.current.state() == ScopeInspection.State.PAUSED, "pause before Scope revocation");
        int revokedSent = panel.current.sent();
        scope.clear();
        edt(() -> panel.resume.doClick(0));
        await(() -> panel.current != null && panel.current.state() != ScopeInspection.State.RUNNING,
            "revoked scope remains paused or stops explicitly");
        Thread.sleep(120); edt(() -> {});
        check(panel.current.sent() == revokedSent, "resuming after Scope revocation cannot send another request");
        edt(() -> panel.close());
        int closedSent = fixture.visits.size(); Thread.sleep(80);
        check(fixture.visits.size() == closedSent, "closed integrated UI retains no request-producing background task");
      } finally {
        edt(panel::close); WorkbenchScope.release(fixture.api);
      }
    }
    System.out.println("ScopeInspectionUiTest: " + checks + " checks passed (real loopback HTTP, production Swing/API adapter; not native Burp GUI)");
  }

  static int indexOf(javax.swing.JTabbedPane pages, String tableName) {
    for (int i = 0; i < pages.getTabCount(); i++)
      if (SimpleWorkflowTest.named(pages.getComponentAt(i), "wb.scope.inspection." + tableName) != null) return i;
    throw new AssertionError("Missing tab for " + tableName);
  }
}
