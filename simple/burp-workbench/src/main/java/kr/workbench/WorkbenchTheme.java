package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import javax.swing.*;
import kr.packetui.VisualTheme;

/** Workbench-local theme choice; the host look and feel and its defaults remain untouched. */
final class WorkbenchTheme implements AutoCloseable {
  static final String KEY = BuildInfo.storageKey("ui.theme.v1");
  private final MontoyaApi api;
  private JPanel root;
  private Runnable changed = () -> {};
  private boolean closed;

  WorkbenchTheme(MontoyaApi api) {
    this.api = api;
    String chosen = null;
    try { chosen = api.persistence().preferences().getString(KEY); }
    catch (RuntimeException ignored) { }
    VisualTheme.setDarkOverride("dark".equals(chosen) ? Boolean.TRUE : "light".equals(chosen) ? Boolean.FALSE : null);
  }

  void bind(JPanel root, Runnable changed) {
    this.root = root; this.changed = changed;
  }

  void toggle() {
    if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::toggle); return; }
    if (closed) return;
    boolean dark = !VisualTheme.dark();
    VisualTheme.setDarkOverride(dark);
    try { api.persistence().preferences().setString(KEY, dark ? "dark" : "light"); }
    catch (RuntimeException ignored) { }
    if (root == null) return;
    WorkbenchUi.style(root);
    changed.run();
    Window owner = SwingUtilities.getWindowAncestor(root);
    if (owner != null) refreshOwned(owner);
    root.revalidate(); root.repaint();
  }

  private static void refreshOwned(Window owner) {
    for (Window window : owner.getOwnedWindows()) {
      if (window instanceof RootPaneContainer pane) WorkbenchUi.style(pane.getContentPane());
      window.repaint(); refreshOwned(window);
    }
  }

  public void close() {
    if (closed) return;
    closed = true; root = null; changed = () -> {};
    VisualTheme.setDarkOverride(null);
  }
}
