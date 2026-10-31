package kr.workbench;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Grouping controls must delegate to the original actions rather than create parallel behavior. */
public final class SimpleApprovedWorkflowTest extends WorkbenchTest {
  private static Component named(Component root, String name) {
    Component result = SimpleWorkflowTest.named(root, name);
    if (result == null) throw new AssertionError("Missing approved-workflow component: " + name);
    return result;
  }
  private static JMenuItem item(JPopupMenu popup, String name) {
    Component result = named(popup, name);
    if (!(result instanceof JMenuItem)) throw new AssertionError("Not a real menu item: " + name);
    return (JMenuItem) result;
  }
  private static boolean exposed(Component component, Component root) {
    for (Component at=component; at!=null; at=at.getParent()) {
      if (!at.isVisible()) return false;
      if (at==root) return true;
    }
    return false;
  }
  public static void main(String[] args) throws Exception {
    int sent = PayloadIntegrationTest.sent.get();
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> handedOff = new AtomicReference<>();
    edt(() -> {
      String exactId = "retained-original-17";
      JTextField authored = new JTextField("KEEP authored 한글 & <bytes>");
      JButton source = new JButton("선택 원본 연결"); source.setName("wb.approved.original");
      source.setActionCommand("ORIGINAL_ACTION_17"); source.setEnabled(false);
      source.addActionListener(event -> {
        calls.incrementAndGet(); handedOff.set(exactId);
        check(event.getActionCommand().equals("ORIGINAL_ACTION_17"),
            "menu invokes the original action command");
      });
      JToggleButton toggle = new JToggleButton("고급 조건 포함");
      toggle.setName("wb.approved.toggle"); toggle.setEnabled(false);
      JPanel group = WorkflowUi.menu("wb.approved.menu", "선택 작업", source, toggle);
      JPanel host = new JPanel(); host.add(authored); host.add(group);
      JButton open = (JButton) named(group,"wb.approved.menu.open");
      JPopupMenu popup = open.getComponentPopupMenu();
      check(popup != null && popup.getComponentCount()==2,
          "one visible grouped entry retains both real menu actions");
      check(!open.isEnabled(), "an entirely unavailable action group cannot be opened");
      check(SwingUtilities.isDescendingFrom(named(group,"wb.approved.original"),group)
              && SwingUtilities.isDescendingFrom(named(group,"wb.approved.toggle"),group),
          "original controllers remain owned by their context, with their original names");
      check(!exposed(source,host) && !exposed(toggle,host),
          "secondary source buttons are not duplicated beside their grouped entry");
      JMenuItem proxy = item(popup,"wb.approved.original.menu");
      check(!proxy.isEnabled(),"disabled original is disabled in the actual menu");
      proxy.doClick(0); check(calls.get()==0,"disabled menu action cannot invoke a retained handler");
      source.setEnabled(true);
      check(open.isEnabled() && proxy.isEnabled(),
          "selection availability is reflected in both menu and entry immediately");
      source.setText("변경된 선택 원본 연결");
      check(proxy.getText().equals(source.getText()),"menu caption follows the same original controller");
      proxy.doClick(0);
      check(calls.get()==1 && exactId.equals(handedOff.get()),
          "explicit menu use invokes exactly one handler for the immutable original ID");
      check(authored.getText().equals("KEEP authored 한글 & <bytes>"),
          "grouping and handoff leave authored text unchanged");
      JMenuItem toggleProxy=item(popup,"wb.approved.toggle.menu");
      check(toggleProxy instanceof JCheckBoxMenuItem,
          "toggle settings remain stateful menu choices");
      toggle.setEnabled(true); toggle.setSelected(true);
      check(toggleProxy.isSelected(),"programmatic setting changes update the menu state");
      toggleProxy.doClick(0);
      check(!toggle.isSelected() && !toggleProxy.isSelected(),
          "menu selection changes the retained toggle once, without a parallel setting");
      source.setEnabled(false); toggle.setEnabled(false);
      check(!open.isEnabled() && !proxy.isEnabled() && !toggleProxy.isEnabled(),
          "cleared selection disables the grouped action without stale availability");
      check(calls.get()==1,"label, selection and layout changes invoke no extra original action");
      AtomicInteger nestedCalls = new AtomicInteger();
      JButton settings = new JButton("관련 설정"); settings.setName("wb.approved.settings");
      JMenuItem originalSetting = new JMenuItem("현재 계정 설정");
      originalSetting.setName("wb.approved.settings.account");
      originalSetting.addActionListener(event -> nestedCalls.incrementAndGet());
      JPopupMenu originalPopup = new JPopupMenu(); originalPopup.add(originalSetting);
      settings.setComponentPopupMenu(originalPopup);
      settings.addActionListener(event -> { throw new AssertionError("A hidden popup trigger must not be clicked"); });
      JPanel settingsGroup = WorkflowUi.menu("wb.approved.nested", "설정", settings);
      JPopupMenu groupedPopup = ((JButton) named(settingsGroup,"wb.approved.nested.open")).getComponentPopupMenu();
      JMenu nested = (JMenu) item(groupedPopup,"wb.approved.settings.menu");
      check(nested.getItemCount()==1 && nested.getItem(0).getName().equals("wb.approved.settings.account.menu"),
          "existing popup actions become a real nested menu rather than a hidden-anchor click");
      nested.getItem(0).doClick(0);
      check(nestedCalls.get()==1 && originalPopup.getComponentCount()==1,
          "nested menu delegates once and keeps the original popup model intact");
      originalSetting.setEnabled(false);
      check(!nested.getItem(0).isEnabled(),"nested actions track original selection availability");

      PacketInspector packet = new PacketInspector("wb.approved.packet");
      try {
        String wire = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"id\":9007199254740993,\"note\":\"KEEP 한글\"}";
        byte[] exact = wire.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        packet.setPacket(exact,null);
        JPanel card = WorkbenchUi.card("응답",packet);
        JPopupMenu tools = ((JButton) named(card,"wb.approved.packet.tools.open")).getComponentPopupMenu();
        check(exposed(packet.tabs.get(0),card) && exposed(packet.tabs.get(1),card)
                && !exposed(packet.tabs.get(2),card) && !exposed(packet.tabs.get(3),card),
            "packet header keeps Pretty and Raw direct while advanced modes share the real tools menu");
        check(!exposed(packet.search,card),
            "packet search starts folded to preserve reader height");
        item(tools,"wb.approved.packet.hex.menu").doClick(0);
        check(packet.mode()==PacketInspector.Mode.HEX && packet.text.getText().startsWith("00000000")
                && java.util.Arrays.equals(exact,packet.previewBytes()),
            "actual advanced-mode menu uses the retained Hex controller and exact wire bytes");
        packet.tabs.get(1).doClick(0);
        item(tools,"wb.approved.packet.search.open.menu").doClick(0);
        packet.search.setText("KEEP");
        check(exposed(packet.search,card) && packet.search.getText().equals("KEEP")
                && packet.text.getText().equals(wire),
            "actual tools search entry reveals the existing query editor without changing Raw content");
        packet.search.getActionMap().get("clear").actionPerformed(
            new java.awt.event.ActionEvent(packet.search,0,"escape"));
        check(!exposed(packet.search,card) && packet.search.getText().isEmpty(),
            "Escape folds packet search through its retained keyboard action");
        packet.getActionMap().get("find").actionPerformed(
            new java.awt.event.ActionEvent(packet,0,"find"));
        check(exposed(packet.search,card) && java.util.Arrays.equals(exact,packet.previewBytes()),
            "Ctrl+F restores the same search editor and keeps original packet bytes intact");
      } finally { packet.close(); }
    });
    check(PayloadIntegrationTest.sent.get()==sent,
        "building, regrouping and explicitly exercising local controller fixtures send zero target HTTP");
    System.out.println("SimpleApprovedWorkflowTest: " + checks + " checks passed");
  }
}
