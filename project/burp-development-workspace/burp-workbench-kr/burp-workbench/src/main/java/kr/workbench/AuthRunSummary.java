package kr.workbench;

/** A row represents a comparison run, not one verdict per HTTP status cell. */
final class AuthRunSummary {
  record Result(String label, AuthVerdict verdict, String reason) {}

  static boolean reference(AuthComparePanel.Packet packet) {
    return packet.note.startsWith("원본과 동일 인증 · 교차 인가 미검증");
  }

  static String responseLabel(AuthComparePanel.Run run, int slot) {
    var packet = run.packets[slot];
    if (run.context.profiles()[slot] == null) return "미지정";
    if (packet.response != null)
      return packet.response.statusCode() + (reference(packet) ? " · 기준" : "");
    return run.note.equals("진행 중") && packet.note.equals("대기") ? "대기" : "응답 없음";
  }

  static Result of(AuthComparePanel.Run run) {
    if (run.note.equals("진행 중"))
      return new Result("비교 중", AuthVerdict.NONE, "계정별 비교가 끝난 뒤 종합 결과를 표시합니다.");
    int compared = 0, suspect = 0, blocked = 0, allowed = 0, uncertain = 0, missing = 0;
    for (int i = 0; i < run.packets.length; i++) {
      if (run.context.profiles()[i] == null || reference(run.packets[i])) continue;
      var packet = run.packets[i];
      var verdict = AuthVerdict.of(packet.note);
      if (packet.response == null || verdict == AuthVerdict.NONE) { missing++; continue; }
      compared++;
      if (verdict == AuthVerdict.SUSPECT) suspect++;
      else if (verdict == AuthVerdict.BLOCKED) blocked++;
      else if ((packet.note.startsWith("공개/공유 허용 ·")
          || packet.note.startsWith("기대 ALLOW · 성공 응답 관측"))
          && !packet.note.contains("설정 충돌") && !packet.note.contains("재검토")) allowed++;
      else uncertain++;
    }
    if (compared == 0)
      return new Result("비교 미성립", AuthVerdict.NONE,
          "기준 계정을 제외한 유효한 비교 응답이 없습니다. 상세에서 미전송·세션 상태를 확인하세요.");
    if (run.response == null || run.response.statusCode() < 200 || run.response.statusCode() >= 300)
      return new Result("확인 필요", AuthVerdict.REVIEW, "기준 응답이 정상 성공 응답이 아닙니다. 기준 계정·세션을 확인하세요.");
    String counts = "기준 계정 제외 · 비교 응답 " + compared + "개 · 우회 의심 " + suspect
        + " · 차단 " + blocked + " · 허용 정책 일치 " + allowed + " · 확인 필요 " + uncertain
        + " · 비교 미성립 " + missing;
    if (suspect > 0) return new Result("우회 의심", AuthVerdict.SUSPECT, counts + " · 실제 권한 위반 여부는 상세 근거로 확인하세요.");
    if (uncertain > 0 || missing > 0 || !run.note.equals("완료"))
      return new Result("확인 필요", AuthVerdict.REVIEW, counts + " · 일부 결과가 미확인 상태입니다.");
    if (allowed > 0) return new Result("정책 일치", AuthVerdict.BLOCKED, counts + " · 설정한 허용/차단 정책과 관측 결과가 일치합니다.");
    return new Result("차단 응답 확인", AuthVerdict.BLOCKED, counts + " · 비교 대상 모두에서 차단 근거를 확인했습니다.");
  }
}
