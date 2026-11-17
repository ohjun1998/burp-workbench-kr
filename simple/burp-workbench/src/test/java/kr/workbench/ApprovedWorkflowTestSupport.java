package kr.workbench;

import java.awt.Component;
import java.awt.Container;
import javax.swing.*;

/** Inspect the actual visible entry for a retained controller, including grouped menus. */
final class ApprovedWorkflowTestSupport {
  private ApprovedWorkflowTestSupport() {}
  record MenuAccess(JButton open, JMenuItem item) {}
  static boolean visible(Component value, Component workspace) {
    for (Component current=value;current!=null;current=current.getParent()) {
      if (!current.isVisible()) return false;
      if (current==workspace) return true;
    }
    return false;
  }
  static JMenuItem item(JPopupMenu popup,String name) {
    if (popup==null) return null;
    for (Component child:popup.getComponents()) {
      if (child instanceof JMenuItem candidate && name.equals(candidate.getName())) return candidate;
      if (child instanceof JMenu submenu) {
        JMenuItem found=item(submenu.getPopupMenu(),name);
        if (found!=null) return found;
      }
    }
    return null;
  }
  static MenuAccess menuAccess(Component source,Component workspace) {
    String name=source.getName(); if(name==null)return null;
    for(Component ancestor=source.getParent();ancestor!=null;ancestor=ancestor.getParent()) {
      if(ancestor instanceof JComponent group && group.getClientProperty("wb.workflow.sources")!=null) {
        Component found=SimpleWorkflowTest.named(group,group.getName()+".open");
        if(found instanceof JButton open && visible(open,workspace)) {
          JMenuItem proxy=item(open.getComponentPopupMenu(),name+".menu");
          if(proxy!=null)return new MenuAccess(open,proxy);
        }
      }
      if(ancestor==workspace)break;
    }
    return visibleMenu(workspace,workspace,name+".menu");
  }
  private static MenuAccess visibleMenu(Component root,Component workspace,String name) {
    if(root instanceof JButton open && visible(open,workspace)) {
      JMenuItem proxy=item(open.getComponentPopupMenu(),name);
      if(proxy!=null)return new MenuAccess(open,proxy);
    }
    if(root instanceof Container parent)for(Component child:parent.getComponents()) {
      MenuAccess found=visibleMenu(child,workspace,name);if(found!=null)return found;
    }
    return null;
  }
  static Component representation(Component source,Component workspace) {
    if(visible(source,workspace))return source;
    MenuAccess access=menuAccess(source,workspace);
    return access==null?null:access.open();
  }
  static boolean available(Component source,Component workspace) {
    return representation(source,workspace)!=null;
  }
}
