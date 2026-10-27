package kr.workbench;

import java.util.List;

/** Bounded multi-step editing, clear recovery and exact provenance object retention. */
public final class EditHistoryTest extends WorkbenchTest {
  record State(String project, List<Object> originals, String description) {
    State { originals = List.copyOf(originals); }
  }
  public static void main(String[] args) {
    sequence(); bounds(); identity();
    System.out.println("EditHistoryTest: " + checks + " checks passed");
  }
  static void sequence() {
    EditHistory<String> history = new EditHistory<>(3);
    check(!history.canUndo() && !history.canRedo() && history.undoLabel().isEmpty() && history.redoLabel().isEmpty(), "new history has no invented actions");
    rejects(() -> history.undo("A"), "empty undo refuses mutation");
    rejects(() -> history.redo("A"), "empty redo refuses mutation");
    history.record("A", "B", "순서 변경"); history.record("B", "C", "선택 제거"); history.record("C", "", "비우기");
    check(history.undoCount() == 3 && history.undoLabel().equals("비우기"), "history exposes exact most recent action and bounded step count");
    check(history.undo("").equals("C") && history.redoLabel().equals("비우기"), "clear is a normal recoverable edit");
    check(history.undo("C").equals("B") && history.undo("B").equals("A"), "several preceding edits restore in reverse chronological order");
    check(!history.canUndo() && history.redoCount() == 3, "moving history to redo does not duplicate retained entries");
    check(history.redo("A").equals("B") && history.redo("B").equals("C") && history.redo("C").equals(""), "redo replays the original order including clear");
    check(!history.canRedo() && history.undoCount() == 3, "redo consumes each original action exactly once");
    history.undo("");
    check(!history.record("C", "C", "변경 없음") && history.redoLabel().equals("비우기"), "a no-op does not invalidate an existing redo");
    history.record("C", "D", "설명 변경");
    check(!history.canRedo() && history.undoLabel().equals("설명 변경"), "new editing after undo retires the incompatible redo branch");
    rejects(() -> history.undo("wrong state"), "undo rejects a mismatched current state without consuming history");
    check(history.undoCount() == 3 && history.undo("D").equals("C"), "a failed undo leaves the valid restoration available");
    rejects(() -> history.redo("wrong state"), "redo independently checks its expected predecessor");
    check(history.redo("C").equals("D"), "a failed redo does not consume the valid forward action");
    rejects(() -> history.record("foreign state", "E", "edit"), "discontinuous snapshots cannot silently join the undo chain");
  }
  static void bounds() {
    rejects(() -> new EditHistory<>(0), "zero-capacity history is rejected");
    rejects(() -> new EditHistory<>(101), "unbounded history capacity is rejected");
    EditHistory<Integer> history = new EditHistory<>(2);
    history.record(0, 1, "one"); history.record(1, 2, "two"); history.record(2, 3, "three");
    check(history.undoCount() == 2 && history.retainedValues().size() == 4, "history retains at most the configured transitions and their state pairs");
    check(history.undo(3) == 2 && history.undo(2) == 1 && !history.canUndo(), "oldest trimmed edit cannot be resurrected");
    rejects(() -> history.record(1, 2, " "), "empty action labels are refused");
    rejects(() -> history.record(1, 2, "x".repeat(129)), "oversized action labels are refused");
    history.clear();
    check(history.retainedValues().isEmpty() && history.undoCount() == 0 && history.redoCount() == 0,
        "project reset or unload releases every undo and redo snapshot reference");
    history.record(100, 101, "new project");
    check(history.undo(101) == 100, "a cleared history can begin an independent project chain");
  }
  static void identity() {
    Object originalA = new Object(), originalB = new Object();
    State original = new State("A", List.of(originalA, originalB), "original");
    State removed = new State("A", List.of(originalB), "removed first");
    State clear = new State("A", List.of(), "cleared");
    EditHistory<State> history = new EditHistory<>();
    history.record(original, removed, "remove"); history.record(removed, clear, "clear");
    check(history.retainedValues().get(0) == original && history.retainedValues().get(2) == removed,
        "retained values expose exact immutable snapshot objects for resource ownership");
    State restored = history.undo(clear);
    check(restored == removed && restored.originals().get(0) == originalB, "clear recovery preserves exact original object identity");
    restored = history.undo(restored);
    check(restored == original && restored.originals().get(0) == originalA && restored.originals().get(1) == originalB,
        "multi-undo preserves original order and exact provenance rather than reconstructing by URL");
    rejects(() -> history.redo(new State("B", original.originals(), "original")), "same originals in another project cannot replay an old edit");
    history.clear();
    check(history.retainedValues().isEmpty(), "clear releases former project's original references from both stacks");
  }
}
