package kr.workbench;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableModel;

/** A bounded in-memory view bookmark. Never reads cell values, text inputs or packet bytes. */
final class NavigationViewState {
  record Tab(JTabbedPane pane, Component selected) {}
  record Table(JTable table, TableModel model, long version, int[] rows) {}
  record Scroll(JViewport viewport, Point point) {}
  private final Component page;
  private final List<Tab> tabs;
  private final List<Table> tables;
  private final List<Scroll> scrolls;

  static final class ModelVersions implements AutoCloseable {
    private static final class Stamp { long value; TableModelListener listener; }
    private final Map<TableModel, Stamp> stamps = new IdentityHashMap<>();
    long version(TableModel model) {
      Stamp stamp = stamps.get(model);
      if (stamp == null) {
        stamp = new Stamp(); Stamp watched = stamp;
        stamp.listener = event -> watched.value++;
        model.addTableModelListener(stamp.listener); stamps.put(model, stamp);
      }
      return stamp.value;
    }
    public void close() {
      stamps.forEach((model, stamp) -> model.removeTableModelListener(stamp.listener)); stamps.clear();
    }
  }
  private NavigationViewState(Component page, List<Tab> tabs, List<Table> tables, List<Scroll> scrolls) {
    this.page = page; this.tabs = List.copyOf(tabs); this.tables = List.copyOf(tables); this.scrolls = List.copyOf(scrolls);
  }
  static NavigationViewState capture(Component page, ModelVersions versions) {
    List<Tab> tabs = new ArrayList<>(); List<Table> tables = new ArrayList<>(); List<Scroll> scrolls = new ArrayList<>();
    visit(page, component -> {
      if (component instanceof JTabbedPane pane) tabs.add(new Tab(pane, pane.getSelectedComponent()));
      if (component instanceof JTable table) {
        int min = table.getSelectionModel().getMinSelectionIndex(), max = table.getSelectionModel().getMaxSelectionIndex();
        List<Integer> selected = new ArrayList<>();
        // Large selections already remain in retained views; don't make an unbounded history copy.
        if (min >= 0 && max - min < 10_000) {
          for (int row = min; row <= max && selected.size() <= 128; row++)
            if (row < table.getRowCount() && table.isRowSelected(row)) selected.add(table.convertRowIndexToModel(row));
        }
        int[] rows = selected.size() > 128 || max - min >= 10_000 ? null : selected.stream().mapToInt(Integer::intValue).toArray();
        tables.add(new Table(table, table.getModel(), versions.version(table.getModel()), rows));
      }
      if (component instanceof JViewport viewport) scrolls.add(new Scroll(viewport, new Point(viewport.getViewPosition())));
    });
    return new NavigationViewState(page, tabs, tables, scrolls);
  }
  /** Capture late selection/scroll changes while preserving the route's old subtab choices. */
  NavigationViewState refresh(ModelVersions versions) {
    NavigationViewState fresh = capture(page, versions);
    return new NavigationViewState(page, tabs, fresh.tables, fresh.scrolls);
  }
  boolean sameTabs(NavigationViewState other) {
    if (other == null || page != other.page || tabs.size() != other.tabs.size()) return false;
    for (int i = 0; i < tabs.size(); i++)
      if (tabs.get(i).pane != other.tabs.get(i).pane || tabs.get(i).selected != other.tabs.get(i).selected) return false;
    return true;
  }
  void restore(ModelVersions versions) {
    for (Tab tab : tabs)
      if (tab.selected != null && tab.pane.indexOfComponent(tab.selected) >= 0) tab.pane.setSelectedComponent(tab.selected);
    for (Table saved : tables) {
      JTable table = saved.table;
      if (saved.rows == null || table.isEditing() || table.getModel() != saved.model
          || versions.version(saved.model) != saved.version) continue;
      table.clearSelection();
      for (int row : saved.rows) {
        if (row >= 0 && row < table.getModel().getRowCount()) {
          int visible = table.convertRowIndexToView(row);
          if (visible >= 0) table.addRowSelectionInterval(visible, visible);
        }
      }
    }
    restoreScrolls();
  }
  void restoreScrolls() {
    for (Scroll saved : scrolls) {
      Dimension view = saved.viewport.getViewSize(), extent = saved.viewport.getExtentSize();
      saved.viewport.setViewPosition(new Point(Math.max(0, Math.min(saved.point.x, view.width - extent.width)),
          Math.max(0, Math.min(saved.point.y, view.height - extent.height))));
    }
  }
  private static void visit(Component component, java.util.function.Consumer<Component> action) {
    action.accept(component);
    if (component instanceof Container container)
      for (Component child : container.getComponents()) visit(child, action);
  }
}
