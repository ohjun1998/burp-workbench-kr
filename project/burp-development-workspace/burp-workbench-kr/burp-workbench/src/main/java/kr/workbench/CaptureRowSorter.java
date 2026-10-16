package kr.workbench;

import java.util.function.Consumer;
import javax.swing.RowFilter;
import javax.swing.table.TableRowSorter;

/** Isolate lazy metadata failures at user-triggered search and header-sort boundaries. */
final class CaptureRowSorter extends TableRowSorter<CaptureTableModel> {
  private final Consumer<RuntimeException> failure;

  CaptureRowSorter(CaptureTableModel model, Consumer<RuntimeException> failure) {
    super(model);
    this.failure = failure;
  }

  @Override
  public void setRowFilter(RowFilter<? super CaptureTableModel, ? super Integer> filter) {
    try {
      super.setRowFilter(filter);
    } catch (RuntimeException error) {
      recover(error);
    }
  }

  @Override
  public void toggleSortOrder(int column) {
    try {
      super.toggleSortOrder(column);
    } catch (RuntimeException error) {
      recover(error);
    }
  }

  private void recover(RuntimeException error) {
    getModel().retryPublication();
    failure.accept(error);
  }
}
