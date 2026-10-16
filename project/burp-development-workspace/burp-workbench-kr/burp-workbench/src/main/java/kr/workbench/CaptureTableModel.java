package kr.workbench;

import java.util.function.IntFunction;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/** One virtual metadata row per captured packet; no duplicate Object[] row storage. */
final class CaptureTableModel extends DefaultTableModel {
  private static final String[] COLUMNS = {"NO", "메서드", "URL", "상태", "시간", "도구", "본문 bytes"};
  private IntFunction<DiskTrafficStore.PacketRef> source;
  private volatile int visible;
  private boolean publicationFailed;

  CaptureTableModel(IntFunction<DiskTrafficStore.PacketRef> source) {
    super(COLUMNS, 0);
    this.source = source;
  }

  @Override
  public int getRowCount() {
    return visible;
  }

  @Override
  public boolean isCellEditable(int row, int column) {
    return false;
  }

  @Override
  public Object getValueAt(int row, int column) {
    if (row < 0 || row >= visible) throw new IndexOutOfBoundsException(Integer.toString(row));
    if (column == 0) return (long) row + 1;
    DiskTrafficStore.PacketRef packet = source.apply(row);
    if (packet == null) return ""; // An unload may close the store before Swing disposes the tab.
    return switch (column) {
      case 1 -> packet.method();
      case 2 -> packet.url();
      case 3 -> packet.hasResponse() ? Integer.valueOf(packet.responseStatus()) : "없음";
      case 4 -> packet.time();
      case 5 -> packet.source();
      case 6 -> packet.responseBodyLength();
      default -> throw new IndexOutOfBoundsException(Integer.toString(column));
    };
  }

  void retryPublication() {
    publicationFailed = true;
  }

  /** Publish all committed rows in one event, independently of observer throughput. */
  void publish(int count) {
    if (!SwingUtilities.isEventDispatchThread())
      throw new IllegalStateException("Capture table publication requires EDT");
    if (count < visible) throw new IllegalArgumentException("Capture rows are append-only");
    int before = visible;
    visible = count;
    if (count == before && !publicationFailed) return;
    boolean resync = publicationFailed;
    publicationFailed = true;
    // A sorter/listener may throw after the count changed. Keep the dirty flag
    // until every listener succeeds, including when no new packet arrives.
    if (resync) fireTableDataChanged();
    else fireTableRowsInserted(before, count - 1);
    publicationFailed = false;
  }

  @Override
  public void setRowCount(int count) {
    // DefaultTableModel invokes this before our constructor has initialized source.
    if (source == null) {
      super.setRowCount(count);
      return;
    }
    if (count != 0) throw new IllegalArgumentException("Use publish for captured rows");
    int before = visible;
    visible = 0;
    if (before == 0 && !publicationFailed) return;
    boolean resync = publicationFailed;
    publicationFailed = true;
    if (resync) fireTableDataChanged();
    else fireTableRowsDeleted(0, before - 1);
    publicationFailed = false;
  }
}
