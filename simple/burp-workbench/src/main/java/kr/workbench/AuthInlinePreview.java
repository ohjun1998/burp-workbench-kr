package kr.workbench;

import java.awt.BorderLayout;
import javax.swing.*;

/** Selection-driven comparison reader, independent of the existing enlarged window. */
final class AuthInlinePreview extends JPanel implements AutoCloseable {
  final JComboBox<String> account = new JComboBox<>();
  final PacketInspector baseline = new PacketInspector("wb.auth.inline.baseline");
  final PacketInspector response = new PacketInspector("wb.auth.inline.response");
  final PacketInspector request = new PacketInspector("wb.auth.inline.request");
  final AuthEvidencePanel evidence = new AuthEvidencePanel();
  final JLabel summary = new JLabel("비교 기록에서 항목을 선택하세요.");
  AuthComparePanel.Run selected;
  boolean updating;

  AuthInlinePreview() {
    super(new BorderLayout(0, 4)); setName("wb.auth.inline");
    account.setName("wb.auth.inline.account");account.setPreferredSize(new java.awt.Dimension(200,32));
    for (int i = 0; i < 4; i++) account.addItem(i == 3 ? "비로그인" : "비교 계정 " + (i + 1));
    add(WorkbenchUi.row(new JLabel("비교 계정"), account, summary), BorderLayout.NORTH);
    JTabbedPane tabs = new JTabbedPane(); tabs.setName("wb.auth.inline.tabs");
    tabs.addTab("응답 비교", WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,
        WorkbenchUi.card("기준 응답", baseline), WorkbenchUi.card("계정 응답", response), .5));
    tabs.addTab("선택 계정 요청", request); tabs.addTab("판정 근거", evidence);
    add(tabs);
    account.addActionListener(e -> { if (!updating) render(); });
  }

  void showRun(AuthComparePanel.Run run) {
    selected = run; updating = true;
    int index = Math.max(0, account.getSelectedIndex());
    account.removeAllItems();
    for (int i = 0; i < 4; i++) account.addItem(run == null
        ? (i == 3 ? "비로그인" : "비교 계정 " + (i + 1)) : run.context.labels()[i]);
    account.setSelectedIndex(index); updating = false; render();
  }

  private void render() {
    var run = selected; int slot = Math.max(0, account.getSelectedIndex());
    summary.setText(run == null ? "비교 기록에서 항목을 선택하세요." : AuthRunSummary.of(run).label());
    summary.setToolTipText(run == null ? null : AuthRunSummary.of(run).reason());
    AuthComparePanel.loadInspector(baseline, null, run == null ? null : run.baseline());
    AuthComparePanel.loadInspector(response, null, run == null ? null : run.packets[slot].response);
    AuthComparePanel.loadInspector(request, run == null ? null : run.packets[slot].request, null);
    evidence.showRun(run);
  }

  public void close() { baseline.close(); response.close(); request.close(); selected = null; }
}
