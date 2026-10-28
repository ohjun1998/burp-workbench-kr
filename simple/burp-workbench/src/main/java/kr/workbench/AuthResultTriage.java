package kr.workbench;

import java.util.Set;

/** Result navigation uses stable finding codes; display wording never changes the verdict. */
final class AuthResultTriage {
  static final String[] LABELS = {"전체", "우회 의심", "확인 필요", "계정·세션", "권한 설정", "쓰기 조회", "전송·미실행"};
  private static final Set<AuthFinding.Kind> SESSION = Set.of(AuthFinding.Kind.SESSION, AuthFinding.Kind.REFRESH, AuthFinding.Kind.CHANGED);
  private static final Set<AuthFinding.Kind> POLICY = Set.of(AuthFinding.Kind.INCOMPLETE, AuthFinding.Kind.CONFLICT, AuthFinding.Kind.OWNER_UNKNOWN);
  private static final Set<AuthFinding.Kind> WRITE = Set.of(AuthFinding.Kind.WRITE_UNVERIFIED, AuthFinding.Kind.WRITE_UNCHANGED,
      AuthFinding.Kind.WRITE_PENDING, AuthFinding.Kind.WRITE_PARTIAL, AuthFinding.Kind.WRITE_READ_FAILED, AuthFinding.Kind.WRITE_SETUP_FAILED);
  private static final Set<AuthFinding.Kind> TRANSPORT = Set.of(AuthFinding.Kind.ERROR, AuthFinding.Kind.NO_RESPONSE, AuthFinding.Kind.CANCELLED);

  static boolean kind(AuthFinding finding, int filter) {
    return switch (filter) {
      case 1 -> finding.verdict() == AuthVerdict.SUSPECT;
      case 2 -> finding.verdict() == AuthVerdict.REVIEW;
      case 3 -> SESSION.contains(finding.kind());
      case 4 -> POLICY.contains(finding.kind());
      case 5 -> WRITE.contains(finding.kind());
      case 6 -> TRANSPORT.contains(finding.kind());
      default -> true;
    };
  }

  static boolean matches(AuthComparePanel.Run run, int filter, String query) {
    String text = run.original.method() + " " + run.original.url();
    for (String label : run.context.labels()) text += " " + label;
    if (!text.toLowerCase(java.util.Locale.ROOT).contains(query.strip().toLowerCase(java.util.Locale.ROOT))) return false;
    if (filter == 0 || filter == 3 && run.baselineState == AuthComparePanel.Run.Baseline.INVALID) return true;
    if (filter == 1 && AuthRunSummary.of(run).verdict() == AuthVerdict.SUSPECT) return true;
    if (filter == 2 && AuthRunSummary.of(run).verdict() == AuthVerdict.REVIEW) return true;
    for (int i = 0; i < run.packets.length; i++)
      if (run.context.profiles()[i] != null && kind(run.packets[i].finding(), filter)) return true;
    return false;
  }

  static int firstSlot(AuthComparePanel.Run run, int filter) {
    for (int i = 0; i < run.packets.length; i++)
      if (run.context.profiles()[i] != null && kind(run.packets[i].finding(), filter)) return i;
    for (int i = 0; i < run.packets.length; i++) if (run.context.profiles()[i] != null) return i;
    return -1;
  }
}
