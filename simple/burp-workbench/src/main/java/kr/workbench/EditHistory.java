package kr.workbench;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Bounded immutable-state history. Packet/resource ownership stays with its caller. */
final class EditHistory<T> {
  private record Change<T>(T before, T after, String label) {}
  private final int capacity;
  private final Deque<Change<T>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

  EditHistory() { this(20); }
  EditHistory(int capacity) {
    if (capacity < 1 || capacity > 100)
      throw new IllegalArgumentException("편집 이력은 1~100단계로 제한합니다.");
    this.capacity = capacity;
  }

  /** Caller supplies immutable snapshots; a no-op must not destroy an available redo. */
  boolean record(T before, T after, String label) {
    Objects.requireNonNull(before); Objects.requireNonNull(after); Objects.requireNonNull(label);
    String text = label.strip();
    if (text.isEmpty() || text.length() > 128)
      throw new IllegalArgumentException("편집 이름은 1~128자여야 합니다.");
    if (before.equals(after)) return false;
    if (!undo.isEmpty() && !undo.peekLast().after.equals(before))
      throw new IllegalArgumentException("현재 편집 상태가 바뀌었습니다. 이전 이력을 먼저 비우세요.");
    undo.addLast(new Change<>(before, after, text)); redo.clear();
    while (undo.size() > capacity) undo.removeFirst();
    return true;
  }

  T undo(T current) {
    if (undo.isEmpty()) throw new IllegalArgumentException("실행 취소할 편집이 없습니다.");
    Change<T> change = undo.peekLast();
    if (!Objects.equals(change.after, current))
      throw new IllegalArgumentException("현재 편집 상태가 바뀌어 실행 취소할 수 없습니다.");
    undo.removeLast(); redo.addLast(change); return change.before;
  }

  T redo(T current) {
    if (redo.isEmpty()) throw new IllegalArgumentException("다시 실행할 편집이 없습니다.");
    Change<T> change = redo.peekLast();
    if (!Objects.equals(change.before, current))
      throw new IllegalArgumentException("현재 편집 상태가 바뀌어 다시 실행할 수 없습니다.");
    redo.removeLast(); undo.addLast(change); return change.after;
  }

  boolean canUndo() { return !undo.isEmpty(); }
  boolean canRedo() { return !redo.isEmpty(); }
  int undoCount() { return undo.size(); }
  int redoCount() { return redo.size(); }
  String undoLabel() { return undo.isEmpty() ? "" : undo.peekLast().label; }
  String redoLabel() { return redo.isEmpty() ? "" : redo.peekLast().label; }

  /** Exact retained objects let the owner close only stores no longer reachable from history. */
  List<T> retainedValues() {
    List<T> values = new ArrayList<>((undo.size() + redo.size()) * 2);
    for (Change<T> change : undo) { values.add(change.before); values.add(change.after); }
    for (Change<T> change : redo) { values.add(change.before); values.add(change.after); }
    return List.copyOf(values);
  }

  void clear() { undo.clear(); redo.clear(); }
}
