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
  final JTextArea differences=new JTextArea();
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
    differences.setEditable(false);differences.setLineWrap(true);differences.setWrapStyleWord(true);differences.putClientProperty("wb.prose",true);differences.setName("wb.auth.inline.differences");tabs.addTab("계정 간 차이",new JScrollPane(differences));
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
    if(run==null||run.baseline()==null||run.packets[slot].response==null)differences.setText("비교할 두 응답이 필요합니다.");
    else {
      String a=new String(PacketInspector.prefix(run.baseline().toByteArray()),java.nio.charset.StandardCharsets.UTF_8),b=new String(PacketInspector.prefix(run.packets[slot].response.toByteArray()),java.nio.charset.StandardCharsets.UTF_8);
      // Decode the bounded wire previews only; this reader never requests or mutates a packet.
      var diff=ResponseDifference.compare(a,b);StringBuilder text=new StringBuilder();
      for(var item:diff.items())text.append(item.category()).append(" · ").append(item.path()).append(" · ").append(item.change()).append("\n기준: ").append(item.left()).append("\n선택 계정: ").append(item.right()).append("\n\n");
      if(diff.items().isEmpty())text.append("표시 가능한 상태·헤더·JSON 항목 차이 없음\n");text.append(diff.note());differences.setText(text.toString());
    }
    differences.setCaretPosition(0);evidence.showRun(run);
  }

  public void close() { baseline.close(); response.close(); request.close(); selected = null; }
}
