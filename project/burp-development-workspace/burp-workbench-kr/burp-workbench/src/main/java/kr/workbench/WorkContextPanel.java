package kr.workbench;

import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

/** Compact shared selection strip. Long paths stay in a tooltip, not in the minimum width. */
final class WorkContextPanel extends JPanel {
  final JLabel request = new JLabel(), state = new JLabel();
  private final WorkContext context;
  private final AtomicBoolean refreshPending = new AtomicBoolean();

  WorkContextPanel(WorkContext context) {
    super(new BorderLayout(6, 0));
    this.context = context;
    setName("wb.work.context");
    request.setName("wb.work.context.request");
    state.setName("wb.work.context.state");
    request.setFont(request.getFont().deriveFont(Font.BOLD));
    request.setMinimumSize(new Dimension(100, 18));
    state.setMinimumSize(new Dimension(100, 18));
    setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
    JPanel labels = new JPanel(new BorderLayout(0, 3));
    labels.setOpaque(false);
    labels.setMinimumSize(new Dimension(100, 38));
    labels.add(request, BorderLayout.NORTH);
    labels.add(state, BorderLayout.CENTER);
    add(labels, BorderLayout.CENTER);
    setMinimumSize(new Dimension(180, 48));
    context.onChange(this::scheduleRefresh);
    refresh();
  }

  private void scheduleRefresh() {
    if (SwingUtilities.isEventDispatchThread()) { refresh(); return; }
    if (refreshPending.compareAndSet(false, true)) SwingUtilities.invokeLater(() -> {
      refreshPending.set(false);
      refresh();
    });
  }

  private void refresh() {
    String description = context.snapshot().description();
    request.setText(description.length() <= 100 ? description : description.substring(0, 99) + "…");
    request.setToolTipText(description);
    state.setText(context.status());
    state.setToolTipText("표시는 수동 선택 비교의 시작 조건을 추적합니다. 자동 비교 이력과 보안 판정은 각 도구에서 확인하세요.");
    state.setForeground(WorkbenchUi.toneInk(context.comparisonStale()
        ? WorkbenchUi.Tone.WARNING : WorkbenchUi.Tone.NEUTRAL));
    getAccessibleContext().setAccessibleName("현재 작업 요청");
    getAccessibleContext().setAccessibleDescription(description + " · " + context.status());
  }
}
