package kr.workbench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Vector;
import java.util.function.Function;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

/** Applies immutable metadata snapshots without clearing every Swing row or its selection. */
final class KeyedTableRows<K> {
  private final List<K> keys = new ArrayList<>();
  private final Map<K, Object> snapshots = new HashMap<>();

  /** Releases cached metadata after the owning panel has been unloaded. */
  void clear() {
    keys.clear();
    snapshots.clear();
  }

  <T> boolean update(
      JTable table, List<T> rows, Function<T, K> keyOf, Function<T, Object[]> cellsOf) {
    DefaultTableModel model = (DefaultTableModel) table.getModel();
    K selected = selectedKey(table);
    List<K> nextKeys = rows.stream().map(keyOf).toList();
    Set<K> present = new HashSet<>(nextKeys);
    Set<K> previous = new HashSet<>(keys);
    if (present.size() != nextKeys.size()) {
      throw new IllegalArgumentException("Duplicate table row key");
    }

    boolean changed = false;
    boolean restoreOnSort = table.getUpdateSelectionOnSort();
    table.setUpdateSelectionOnSort(false);
    table.getSelectionModel().setValueIsAdjusting(true);
    try {
      for (int i = keys.size() - 1; i >= 0; i--) {
        if (!present.contains(keys.get(i))) {
          snapshots.remove(keys.get(i));
          keys.remove(i);
          model.removeRow(i);
          changed = true;
        }
      }
      for (int i = 0; i < rows.size(); i++) {
        K key = nextKeys.get(i);
        // Callers supply immutable records. Unchanged records do not need
        // cell arrays, parameter counts, or repeated label formatting.
        if (i < keys.size()
            && Objects.equals(keys.get(i), key)
            && snapshots.get(key) == rows.get(i)) continue;
        Object[] cells = cellsOf.apply(rows.get(i));
        if (i >= keys.size() || !Objects.equals(keys.get(i), key)) {
          // Stable insertion order is the normal case. Reordered/imported rows
          // still receive precise delete/insert events instead of a reset.
          int old = previous.contains(key) ? keys.indexOf(key) : -1;
          if (old >= 0) {
            keys.remove(old);
            model.removeRow(old);
          }
          keys.add(i, key);
          model.insertRow(i, cells);
          changed = true;
        } else if (replaceCells(model, i, cells)) {
          changed = true;
        }
        snapshots.put(key, rows.get(i));
      }
      if (changed && table.getRowSorter() instanceof TableRowSorter<?> sorter) {
        sorter.sort();
      }
      if (selected != null) {
        int index = keys.indexOf(selected);
        int visible = index < 0 ? -1 : table.convertRowIndexToView(index);
        if (visible < 0) table.clearSelection();
        else table.setRowSelectionInterval(visible, visible);
      }
    } finally {
      table.setUpdateSelectionOnSort(restoreOnSort);
      table.getSelectionModel().setValueIsAdjusting(false);
    }
    return changed;
  }

  private K selectedKey(JTable table) {
    int selected = table.getSelectedRow();
    int modelRow = selected < 0 ? -1 : table.convertRowIndexToModel(selected);
    return modelRow < 0 || modelRow >= keys.size() ? null : keys.get(modelRow);
  }

  @SuppressWarnings("unchecked")
  private static boolean replaceCells(DefaultTableModel model, int row, Object[] cells) {
    Vector<Object> existing = model.getDataVector().get(row);
    boolean changed = false;
    for (int column = 0; column < cells.length; column++) {
      if (!Objects.equals(existing.get(column), cells[column])) {
        existing.set(column, cells[column]);
        changed = true;
      }
    }
    if (changed) model.fireTableRowsUpdated(row, row);
    return changed;
  }
}
