package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Persistence;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JPanel;

/** A real adapter lookup race: project changes when the write resolves extensionData. */
public final class WorkspaceAppearanceProjectIsolationTest extends WorkbenchTest {
  static final String A = "11111111-1111-1111-1111-111111111111";
  static final String B = "22222222-2222-2222-2222-222222222222";

  static boolean appearanceWriteInProgress() {
    return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
        frame.getClassName().startsWith("kr.workbench.WorkspaceAppearance$") && frame.getMethodName().equals("write")));
  }

  static void switchAtWriteLookup(boolean existingSecondProfile) {
    Map<String, String> first = new HashMap<>(), second = new HashMap<>();
    first.put(WorkspaceResume.PROJECT_KEY, A); second.put(WorkspaceResume.PROJECT_KEY, B);
    if (existingSecondProfile)
      second.put(WorkspaceAppearance.KEY, WorkspaceAppearance.encode(WorkspaceAppearance.defaults(B, false).display(125, true, false)));
    String secondBefore = second.get(WorkspaceAppearance.KEY);
    AtomicReference<String> current = new AtomicReference<>(A);
    AtomicBoolean armed = new AtomicBoolean(), switched = new AtomicBoolean();
    Map<String, PersistedObject> handles = new HashMap<>();
    for (var entry : Map.of(A, first, B, second).entrySet()) {
      Map<String, String> values = entry.getValue();
      handles.put(entry.getKey(), proxy(PersistedObject.class, (p, method, args) -> switch (method.getName()) {
        case "getString" -> values.get(args[0].toString());
        case "setString" -> { values.put(args[0].toString(), args[1].toString()); yield null; }
        default -> def(method);
      }));
    }
    Persistence persistence = proxy(Persistence.class, (p, method, args) -> {
      if (!method.getName().equals("extensionData")) return def(method);
      if (armed.get() && appearanceWriteInProgress() && armed.compareAndSet(true, false)) {
        current.set(B); switched.set(true);
      }
      return handles.get(current.get());
    });
    MontoyaApi api = proxy(MontoyaApi.class, (p, method, args) ->
        method.getName().equals("persistence") ? persistence : def(method));
    WorkspaceAppearance appearance = WorkspaceAppearance.forApi(api, new JPanel(), true, null);
    try {
      appearance.display(150, false, true);
      check(WorkspaceAppearance.decode(first.get(WorkspaceAppearance.KEY)).project().equals(A),
          "normal adapter display save remains bound to project A");
      String firstBefore = first.get(WorkspaceAppearance.KEY);
      armed.set(true);
      appearance.display(100, true, true);
      check(switched.get(), "fixture switches the active persisted handle during the actual appearance write lookup");
      check(java.util.Objects.equals(secondBefore, second.get(WorkspaceAppearance.KEY)),
          existingSecondProfile ? "late project switch never overwrites project B's existing profile with project A state"
              : "late project switch never creates a foreign profile in an otherwise empty project B");
      check(firstBefore.equals(first.get(WorkspaceAppearance.KEY)),
          "a write redirected before binding cannot claim to update project A's durable profile");
      appearance.refreshProject();
      check(appearance.state().project().equals(B) && !appearance.blocked(),
          "following the switch reads project B's own valid settings instead of a contaminated foreign record");
    } finally { appearance.close(); WorkbenchUi.setAppearance(100, false); }
  }

  public static void main(String[] args) throws Exception {
    edt(() -> { switchAtWriteLookup(true); switchAtWriteLookup(false); });
    System.out.println("WorkspaceAppearanceProjectIsolationTest: " + checks + " checks passed");
  }
}
