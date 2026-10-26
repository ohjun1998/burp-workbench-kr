package kr.workbench;

import javax.swing.*;

public final class ResponseDifferenceUiTest {
  static int checks;
  static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    checks++; System.out.println("PASS " + name);
  }
  static int row(StructuredComparePanel p, String path) {
    for (int i = 0; i < p.summaryModel.getRowCount(); i++)
      if (path.equals(p.summaryModel.getValueAt(i, 1))) return p.summaryTable.convertRowIndexToView(i);
    throw new AssertionError("Missing row " + path);
  }
  public static void main(String[] args) throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      var p = new StructuredComparePanel();
      String left = "HTTP/1.1 200 OK\r\nX-Test: a\r\n\r\n{\"user\":{\"role\":\"reader\"},\"old\":true}";
      String right = "HTTP/1.1 403 No\r\nX-Test: b\r\n\r\n{\"user\":{\"role\":\"writer\"},\"new\":false}";
      p.leftInput.setText(left); p.rightInput.setText(right); p.compare();
      check(p.resultTabs.getSelectedIndex() == 0, "field summary is the first result view");
      check(p.summaryTable.getRowCount() == 5, "status header and JSON changes are all inspectable rows");
      check(p.leftInput.getText().equals(left) && p.rightInput.getText().equals(right), "comparison keeps original input untouched");
      check(p.leftRaw.getText().equals(left) && p.rightRaw.getText().equals(right), "raw detail remains original instead of formatted alignment");
      p.summaryTable.setRowSelectionInterval(row(p, "$.user.role"), row(p, "$.user.role"));
      check(p.leftRaw.getSelectedText().equals("\"reader\"") && p.rightRaw.getSelectedText().equals("\"writer\""), "field selection jumps to exact original values on both sides");
      check(p.leftRaw.getHighlighter().getHighlights().length == 1 && p.rightRaw.getHighlighter().getHighlights().length == 1, "selected field raw spans are highlighted");
      p.summaryTable.setRowSelectionInterval(row(p, "$.new"), row(p, "$.new"));
      check(p.leftRaw.getHighlighter().getHighlights().length == 0 && p.rightRaw.getSelectedText().equals("false"), "added field clears absent side instead of leaving stale highlight");
      check(p.status.getText().contains("왼쪽 해당 항목 없음"), "missing side is explicitly labeled");
      p.summaryTable.getRowSorter().toggleSortOrder(1);
      p.summaryTable.setRowSelectionInterval(row(p, "$.old"), row(p, "$.old"));
      check(p.leftRaw.getSelectedText().equals("true") && p.status.getText().contains("오른쪽 해당 항목 없음"), "sorted row selection preserves left-right provenance");
      p.next.doClick();
      check(p.resultTabs.getSelectedIndex() == 1 && p.current == 0, "existing next difference opens unchanged line comparison");
      p.previous.doClick();
      check(p.resultTabs.getSelectedIndex() == 1, "existing previous navigation remains available");
      p.formatted.doClick();
      check(!p.formatted.isSelected() && p.summaryTable.getRowCount() == 5, "raw formatting toggle preserves field summary");
      check(p.leftInput.getText().equals(left), "format toggle never edits raw source");
      p.edit.doClick();
      check(!p.showing && !p.next.isEnabled(), "input editing disables hidden stale line navigation");
      p.rightInput.setText(left);
      check(p.summaryTable.getRowCount() == 0 && p.summary == null, "editing an input invalidates stale semantic summary");
      p.compare();
      check(p.summaryTable.getRowCount() == 0 && !p.next.isEnabled(), "equal input has no invented field or line changes");
      p.leftInput.setText("{\"x\":\"<html><img src='https://invalid.invalid/a'>\"}");
      p.rightInput.setText("{\"x\":\"<html><b>changed</b>\"}"); p.compare();
      WorkbenchUi.style(p);
      var renderer = p.summaryTable.getCellRenderer(0, 3);
      var component = (JComponent) renderer.getTableCellRendererComponent(p.summaryTable, "<html><img src='https://invalid.invalid/a'>", false, false, 0, 3);
      check(Boolean.TRUE.equals(component.getClientProperty("html.disable")), "untrusted table values cannot activate Swing HTML after theme restyling");
      check(component.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null, "no HTML document is built for response field content");
      p.leftInput.setText("x".repeat(70_000)); p.rightInput.setText("y"); p.compare();
      check(p.leftRaw.getDocument().getLength() == 65_536 && p.status.getText().contains("표시 제한"), "raw context uses the same bounded summary input and discloses partial view");
      p.clearSources();
      check(p.summary == null && p.summaryTable.getRowCount() == 0 && p.leftRaw.getText().isEmpty(), "reset releases semantic summary and raw context");
      check(!p.captureEvidence.isEnabled() && !p.next.isEnabled(), "reset preserves original evidence availability rules");
    });
    System.out.println("Response field difference UI: " + checks + " checks passed");
  }
}
