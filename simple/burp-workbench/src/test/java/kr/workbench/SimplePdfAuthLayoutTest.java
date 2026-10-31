package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import java.awt.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** The PDF account roles and the complete native preparation share retained controllers. */
public final class SimplePdfAuthLayoutTest extends WorkbenchTest {
  private static Component named(Component root, String name) {
    Component value = SimpleWorkflowTest.named(root, name);
    if (value == null) throw new AssertionError("Missing authentication layout component: " + name);
    return value;
  }

  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    AtomicInteger sent = new AtomicInteger();
    Http http = proxy(Http.class, (p, m, a) -> {
      if (m.getName().equals("sendRequest")) {
        sent.incrementAndGet(); throw new AssertionError("Account preparation cannot send requests");
      }
      return def(m);
    });
    MontoyaApi api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("http") ? http : def(m));
    var original = AuthRecordTest.q("ORIGINAL_AUTH");
    var packet = AuthRecordTest.rr(original, response(200, "EXACT_ORIGINAL_RESPONSE"));
    edt(() -> {
      AuthComparePanel panel = new AuthComparePanel(api, () -> List.of(packet));
      try {
        panel.observe(AuthRecordTest.q("FIRST_AUTH"), response(200, "first"));
        panel.observe(AuthRecordTest.q("SECOND_AUTH"), response(200, "second"));
        panel.observe(request("https://other.test/path", "GET", true, List.of(header("Cookie", "OTHER_AUTH"))), response(200, "other"));
        var candidates = List.copyOf(panel.credentials.values());
        panel.mapCandidate(candidates.get(0), new AuthAccount("Alice", "alice", "101", "문서 소유자", "alice@example.test"));
        panel.mapCandidate(candidates.get(1), new AuthAccount("Bob", "bob", "102", "다른 사용자", "bob@example.test"));
        panel.assign(0, candidates.get(0)); panel.assign(1, candidates.get(1));
        panel.prepareSelection(List.of(packet));
        panel.profileRoles[0].setText("<html><b>AUTHORED_ROLE</b></html>");
        byte[] requestBytes = original.toByteArray().getBytes();
        JPanel form = panel.preparationAccountChoices();
        JLabel roleHint = (JLabel) named(form, "wb.auth.preparation.role.hint.0");
        check(roleHint.getText().equals("<html><b>AUTHORED_ROLE</b></html>")
                && roleHint.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null,
            "authored role metadata is displayed literally without enabling Swing HTML rendering");
        JLabel origin = (JLabel) named(form, "wb.auth.preparation.origin");
        check(origin.getText().contains(candidates.get(0).origin()), "one shared target context identifies the account origin");
        for (int slot : new int[]{0, 1, 2, 3}) {
          JComboBox<?> choices = (JComboBox<?>) named(form, "wb.auth.preparation.account." + slot);
          for (int item = 0; item < choices.getItemCount(); item++) {
            var value = (AuthComparePanel.CandidateChoice) choices.getItemAt(item);
            check(!value.label().contains("https://") && !value.label().contains("_AUTH"),
                "role captions do not repeat the shared origin or expose session credentials");
            check(value.candidate() == null || value.candidate().origin().equals(candidates.get(0).origin()),
                "each choice is still restricted by its retained origin rather than its shorter caption");
          }
        }
        JComboBox<?> first = (JComboBox<?>) named(form, "wb.auth.preparation.account.0");
        @SuppressWarnings("unchecked") ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) first.getRenderer();
        Component rendered = renderer.getListCellRendererComponent(new JList<>(),
            new AuthComparePanel.CandidateChoice(candidates.get(0), "<html><img src='http://127.0.0.1:1/forbidden'>"), 0, false, false);
        check(rendered instanceof JComponent component
                && component.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null,
            "candidate names cannot create a Swing HTML image lookup when a role chooser renders them");
        check(first.getItemAt(1).toString().contains("alice@example.test"), "linked account identity remains visible beside its role");
        first.setSelectedIndex(2);
        check(panel.slots[0] == candidates.get(1), "selecting a shortened caption assigns the exact retained candidate");
        first.setSelectedIndex(0);
        check(panel.slots[0] == null && ((JLabel) named(form, "wb.auth.preparation.connection.0")).getText().equals("미지정"),
            "clearing a role immediately updates both the retained slot and connection state");
        first.setSelectedIndex(1);
        check(((JLabel) named(form, "wb.auth.preparation.connection.0")).getText().equals("연결됨"),
            "reconnecting a role updates its visible connection state without an extra apply action");
        JComboBox<?> anonymous = (JComboBox<?>) named(form, "wb.auth.preparation.account.3");
        anonymous.setSelectedIndex(1);
        check(panel.slots[3] != null && panel.slots[3].headers().isEmpty()
                && panel.slots[3].origin().equals(candidates.get(0).origin()),
            "the anonymous role remains scoped and contains no inherited authentication headers");
        check(((JLabel) named(form, "wb.auth.preparation.connection.3")).getText().equals("사용"),
            "the anonymous row exposes its own current use state");
        JToggleButton extra = (JToggleButton) named(form, "wb.auth.preparation.extra.toggle");
        check(!extra.isSelected(), "an unused third account starts folded after the anonymous role");
        extra.doClick(0);
        check(ApprovedWorkflowTestSupport.visible(named(form, "wb.auth.preparation.account.2"), form),
            "opening the optional role reveals its actual retained account selector");

        panel.profileOwners[0].setText("AUTHORED_OWNER_101");
        JTabbedPane tabs = panel.preparationContent();
        check(tabs.getTabCount() == 3 && tabs.getTitleAt(0).equals("1 적용 상태")
                && tabs.getTitleAt(1).equals("2 계정·쓰기 조회") && tabs.getTitleAt(2).equals("3 세션·권한표"),
            "the production preparation exposes all three real tabs instead of a cropped account-only surrogate");
        JTable overview = (JTable) named(tabs.getComponentAt(0), "wb.auth.readiness.table");
        int expectedRows = AuthReadiness.selected(panel.context(), List.of(original)).rows().size();
        check(expectedRows == 3 && overview.getRowCount() == expectedRows
                && overview.getModel().getValueAt(0, 0).toString().contains("/object/101"),
            "the shared overview renders the actual retained request and three assigned roles instead of an empty capture placeholder");
        JPanel overviewPanel = (JPanel) named(tabs.getComponentAt(0), "wb.auth.preparation.overview");
        var refreshed = panel.updatePreparationOverview(overviewPanel, false, List.of(packet));
        check(refreshed.report().rows().size() == expectedRows
                && refreshed.context().profiles()[0] == panel.slots[0],
            "the same native readiness update reads current slot identities and produces the displayed report without approving it");
        check(tabs.getComponentAt(0) instanceof JScrollPane
                && ((JScrollPane) tabs.getComponentAt(0)).getViewport().getView() == overviewPanel
                && tabs.getComponentAt(1) instanceof JScrollPane && tabs.getComponentAt(2) instanceof JScrollPane,
            "each complete preparation tab scrolls its own content while retaining a fixed shared tab strip");
        JScrollPane overviewScroll = (JScrollPane) tabs.getComponentAt(0);
        overviewScroll.getViewport().setViewPosition(new java.awt.Point(0, 120));
        panel.updatePreparationOverview(overviewPanel, false, List.of(packet));
        check(overviewScroll.getViewport().getViewPosition().equals(new java.awt.Point()),
            "refreshing the actual overview returns its inner reader to the top without scrolling away the tabs");
        JPanel settingsShell = WorkbenchUi.settingsBody(tabs, () -> {});
        check(((java.awt.BorderLayout) settingsShell.getLayout()).getLayoutComponent(java.awt.BorderLayout.CENTER) == tabs,
            "the complete preparation is mounted directly in the settings shell so scrolling a tab cannot move the shared tab strip");
        WorkbenchUi.style(tabs);
        tabs.setSelectedIndex(1);
        Class<?> controlUi = ((JToggleButton) named(tabs, "wb.auth.preparation.extra.toggle")).getUI().getClass();
        tabs.setSelectedIndex(0); tabs.setSelectedIndex(1);
        JToggleButton rebuiltExtra = (JToggleButton) named(tabs, "wb.auth.preparation.extra.toggle");
        check(controlUi.getName().contains("WorkbenchSkin") && rebuiltExtra.getUI().getClass() == controlUi,
            "returning to the account tab styles newly built controls instead of restoring a Metal disclosure button");
        check(SwingUtilities.isDescendingFrom(panel.profileOwners[0], tabs)
                && SwingUtilities.isDescendingFrom(panel.matrixRules, tabs),
            "comparison settings and the permission matrix remain reachable in the complete preparation");
        check(panel.profileOwners[0].getText().equals("AUTHORED_OWNER_101")
                && java.util.Arrays.equals(original.toByteArray().getBytes(), requestBytes) && panel.preparationSelection.get(0) == packet,
            "layout and tab changes preserve authored settings, exact request bytes and staged original identity");
        check(sent.get() == 0, "role selection, disclosure and full preparation construction send zero requests");
      } finally { panel.close(); }
    });
    System.out.println("SimplePdfAuthLayoutTest: " + checks + " checks passed");
  }
}
