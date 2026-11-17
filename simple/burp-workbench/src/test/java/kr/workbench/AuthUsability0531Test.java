package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Local Swing adapters: compact previews, stable triage and exact-original repair. No live GUI claim. */
public final class AuthUsability0531Test extends WorkbenchTest {
  static Component named(Component value, String name) {
    if (name.equals(value.getName())) return value;
    if(value instanceof JComponent j&&j.getComponentPopupMenu()!=null){Component found=named(j.getComponentPopupMenu(),name);if(found!=null)return found;}
    if (value instanceof Container container) for (Component child : container.getComponents()) {
      Component found = named(child, name); if (found != null) return found;
    }
    return null;
  }

  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory(); AtomicInteger sends = new AtomicInteger();
    var http = proxy(Http.class, (p, m, a) -> { if (m.getName().equals("sendRequest")) sends.incrementAndGet(); return def(m); });
    var api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("http") ? http : def(m));
    WorkbenchScope.install(api).add("https://example.test/", true);
    HttpRequest get = BountyFeaturesTest.rq("https://example.test/items/1", "GET", "", List.of(header("Cookie", "secret-one")));
    HttpRequest patch = BountyFeaturesTest.rq("https://example.test/items/1", "PATCH", "{\"x\":1}", get.headers());
    var baseline = response(200, "{\"ownerId\":1}");
    HttpRequestResponse first = AuthRecordTest.rr(get, baseline), second = AuthRecordTest.rr(patch, baseline);
    AtomicReference<AuthComparePanel> holder = new AtomicReference<>();
    edt(() -> {
      var panel = new AuthComparePanel(api, () -> List.of(first, second)); holder.set(panel);
      panel.observe(BountyFeaturesTest.rq("https://other.test/", "GET", "", List.of(header("Cookie", "other-secret"))), baseline);
      panel.observe(get, baseline); panel.assign(0, panel.credentials.values().stream().filter(value -> value.origin().startsWith("https://example.test:")).findFirst().orElseThrow());
      panel.writes.setSelected(true); panel.matrixRules.setText("GET /items/1 ALLOW UNKNOWN UNKNOWN UNKNOWN");
    });
    AuthComparePanel panel = holder.get();
    try {
      edt(() -> {
        panel.prepareSelection(List.of(first, second));
        var context = panel.context(); var report = AuthReadiness.selected(context, List.of(get, patch));
        JPanel view = AuthReadiness.content(context, report, List.of(), panel::editReadinessRow, 1, 0);
        JTable table = (JTable) named(view, "wb.auth.readiness.table");
        check(table.getColumnCount() == 4 && table.getModel().getColumnCount() == 6, "compact preview keeps full reason data in inspector without six wide columns");
        check(table.convertRowIndexToModel(table.getSelectedRow()) == 1, "compact preview retains exact focused original");
        JComboBox<?> filter = (JComboBox<?>) named(view, "wb.auth.readiness.filter");
        filter.setSelectedIndex(1);
        check(table.getRowCount() == 1 && table.convertRowIndexToModel(0) == 1, "needs-setup filter keeps only incomplete original/account row");
        filter.setSelectedIndex(2);
        check(table.getRowCount() == 1 && table.getValueAt(0, 0).toString().startsWith("PATCH"), "write-only filter does not borrow GET readiness");
        JTextField search = (JTextField) named(view, "wb.auth.readiness.search");
        search.setText("missing"); check(table.getRowCount() == 0, "empty filter result has no stale selected row");
        search.setText("ITEMS/1"); check(table.getRowCount() == 1 && table.getSelectedRow() == 0, "case-insensitive search restores valid model/view selection");
        var choices = panel.preparationAccountChoices();
        JComboBox<?> candidate = (JComboBox<?>) named(choices, "wb.auth.preparation.account.0");
        check(!candidate.getItemAt(1).toString().contains("secret-one"), "account chooser labels do not expose credential values");
        candidate.setSelectedIndex(0); check(panel.slots[0] == null, "account chooser explicitly clears slot");
        var disconnected = panel.preparationAccountChoices();
        JComboBox<?> anonymous = (JComboBox<?>) named(disconnected, "wb.auth.preparation.account.3");
        check(((AuthComparePanel.CandidateChoice) anonymous.getItemAt(1)).candidate().origin().startsWith("https://example.test:") && candidate.getItemCount() == 2,
            "staged original restricts account choices and anonymous Origin despite earlier foreign credentials");
        candidate.setSelectedIndex(1); check(panel.slots[0] != null, "account chooser reconnects retained candidate without network");
        panel.preparationTabs = new JTabbedPane();
        for (int i = 0; i < 3; i++) panel.preparationTabs.addTab("test", new JPanel());
        check(panel.preparationOwner(panel) == panel.preparationTabs, "auxiliary settings use a child of active preparation dialog instead of blocked main-frame sibling");
        panel.editReadinessRow(report.rows().get(1), "policy");
        check(panel.preparationTabs.getSelectedIndex() == 2 && panel.preparationSelection.get(1) == second,
            "in-window policy repair preserves the original batch and chooses policy tab");
        panel.editReadinessRow(report.rows().get(1), "account");
        check(panel.preparationTabs.getSelectedIndex() == 1 && panel.preparationSlot == 0, "in-window account repair targets the affected slot");
        panel.preparationTabs = null;
        var reviewed = panel.context(); var stamp = panel.preparedStamp(reviewed);
        check(AuthComparePanel.sameReviewedContext(reviewed, panel.context()), "unchanged reviewed snapshot can be approved");
        panel.profileOwners[0].setText("another-owner");
        check(!AuthComparePanel.sameReviewedContext(reviewed, panel.context()), "settings edited after preview cannot approve old context");
        rejects(() -> panel.validatePreparedExecution(stamp, reviewed), "execution still rejects edited settings before first replay");
        panel.profileOwners[0].setText("");

        var run = new AuthComparePanel.Run(11, patch, baseline, panel.context()); run.note = "완료";
        run.packets[0].result(new AuthFinding(AuthFinding.Kind.WRITE_READ_FAILED, "arbitrary display text", ""));
        check(AuthResultTriage.matches(run, 5, "PATCH") && !AuthResultTriage.matches(run, 3, ""), "triage uses stable write finding codes and request search");
        run.packets[0].result(new AuthFinding(AuthFinding.Kind.SESSION, "우회 의심", ""));
        check(AuthResultTriage.matches(run, 3, "") && !AuthResultTriage.matches(run, 1, ""), "misleading display reason cannot change stable session category");
        panel.runs.add(run); panel.historyModel.addRow(new Object[]{11, patch.url(), "", "", "", "완료", "PATCH", "", ""});
        panel.refresh(run); panel.historyFilter.setSelectedIndex(3);
        check(panel.history.getRowCount() == 1, "history session filter exposes affected run");
        panel.historyFilter.setSelectedIndex(5); check(panel.history.getRowCount() == 0, "history cause filter hides unrelated session run");
        check(!((AbstractButton) named(panel, "wb.auth.history.repair.write")).isEnabled(), "repair action disables when filter removes selected result");
        panel.historyFilter.setSelectedIndex(0); panel.history.setRowSelectionInterval(0, 0);
        check(((AbstractButton) named(panel, "wb.auth.history.repair.write")).isEnabled(), "write repair enables for explicitly selected write result");
        panel.stageRepair(panel.current(), 3);
        check(panel.preparationSelection.size() == 1 && panel.preparationSelection.get(0).request() == patch && panel.preparationSlot == 0,
            "result repair stages exact historical request and affected account without replay");
        panel.matrixRules.setText(""); panel.matrixEditor.load(); panel.matrixEditor.selected();
        check(panel.matrixEditor.model.getRowCount() == 1 && panel.matrixEditor.model.getValueAt(0, 0).equals("PATCH"),
            "add-selected policy uses staged historical request rather than global two-request selection");
        panel.refreshAutomaticScope();
        check(panel.record.getText().equals("신규 요청 자동 비교 OFF") && panel.automaticScope.getText().contains("1계정"), "automatic replay label and account scope are explicit while off");
        check(sends.get() == 0, "filters, reconnect, setup repair and review validation send zero HTTP requests");
      });
    } finally { edt(panel::close); WorkbenchScope.release(api); }
    System.out.println("AuthUsability0531Test: " + checks + " checks passed");
  }
}
