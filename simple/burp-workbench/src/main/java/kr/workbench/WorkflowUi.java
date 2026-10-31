package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import java.util.List;
import javax.swing.*;

/** Groups existing controllers without duplicating engines, listeners or user data. */
public final class WorkflowUi {
  private WorkflowUi() {}

  /** Original buttons retain their names and handlers; the popup mirrors their current models. */
  public static JPanel menu(String name, String caption, AbstractButton... actions) {
    JPanel group = new JPanel(new BorderLayout());
    group.setName(name);
    JButton open = new JButton(caption + " ▾");
    open.setName(name + ".open");
    open.getAccessibleContext().setAccessibleName(caption);
    JPopupMenu popup = new JPopupMenu();
    popup.setName(name + ".popup");
    JPanel controllers = new JPanel();
    controllers.setName(name + ".sources");
    controllers.setVisible(false);
    for (AbstractButton action : actions) {
      if (action == null) { popup.addSeparator(); continue; }
      controllers.add(action);
      if (action.getComponentPopupMenu() != null) {
        JMenu submenu = new JMenu(action.getText());
        submenu.setName(action.getName() + ".menu");
        for (Component item : action.getComponentPopupMenu().getComponents()) {
          if (item instanceof JMenuItem source) submenu.add(proxy(source));
          else if (item instanceof JPopupMenu.Separator) submenu.addSeparator();
        }
        action.addPropertyChangeListener("enabled", e -> submenu.setEnabled(action.isEnabled()));
        submenu.setEnabled(action.isEnabled());
        popup.add(submenu);
      } else popup.add(proxy(action));
    }
    Runnable enabled = () -> open.setEnabled(Arrays.stream(actions).anyMatch(a -> a != null && a.isEnabled()));
    for (AbstractButton action : actions) if (action != null)
      action.addPropertyChangeListener("enabled", e -> enabled.run());
    enabled.run();
    open.setComponentPopupMenu(popup);
    open.putClientProperty("wb.workflow.sources", Arrays.asList(actions));
    group.putClientProperty("wb.workflow.sources", Arrays.asList(actions));
    open.addActionListener(e -> { if (open.isShowing()) { WorkbenchUi.style(popup); popup.show(open, 0, open.getHeight()); } });
    group.add(open); group.add(controllers, BorderLayout.SOUTH);
    return group;
  }

  /** Menu entries preserve exact controller identity and stay current after selection changes. */
  public static JMenuItem proxy(AbstractButton source) {
    JMenuItem item;
    if (source instanceof JMenu sourceMenu) {
      JMenu nested = new JMenu();
      for (Component child : sourceMenu.getMenuComponents()) {
        if (child instanceof JMenuItem entry) nested.add(proxy(entry));
        else if (child instanceof JPopupMenu.Separator) nested.addSeparator();
      }
      item = nested;
    } else item = source instanceof JToggleButton || source instanceof JCheckBoxMenuItem
        ? new JCheckBoxMenuItem() : new JMenuItem();
    item.setName(source.getName() == null ? null : source.getName() + ".menu");
    Runnable sync = () -> {
      item.setText(source.getText()); item.setEnabled(source.isEnabled());
      item.setToolTipText(source.getToolTipText()); item.setSelected(source.isSelected());
      item.getAccessibleContext().setAccessibleName(source.getAccessibleContext().getAccessibleName());
    };
    sync.run();
    source.addPropertyChangeListener(e -> {
      if (List.of("text", "enabled", "ToolTipText", "icon").contains(e.getPropertyName())) sync.run();
    });
    source.addItemListener(e -> sync.run());
    if (!(source instanceof JMenu))
      item.addActionListener(e -> { if (source.isEnabled()) source.doClick(0); sync.run(); });
    return item;
  }

  /** A small context line, with full literal data available through tooltip/accessibility. */
  public static JLabel context(String name, String text) {
    JLabel label = new JLabel(text);
    label.setName(name); label.setToolTipText(text);
    label.getAccessibleContext().setAccessibleName(text);
    label.setMinimumSize(new Dimension(80, 24));
    return label;
  }

  public static void secondary(AbstractButton button) { button.putClientProperty("wb.primary", false); }
}
