package kr.workbench;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** Bounded local export plan. Copies no packet bytes and never sends a request. */
final class AuthEvidenceSnapshot {
  record Entry(HttpRequest request, HttpResponse response, String label, String note, String account) {}
  private AuthEvidenceSnapshot() {}

  static List<Entry> capture(AuthComparePanel.Run run) {
    String group = "권한 비교 실행 #" + run.no;
    List<Entry> entries = new ArrayList<>();
    String setup = group + "\n실행 요약: " + run.note
        + "\n기준 검증 상태: " + run.baselineState + " · " + run.baselineNote
        + "\n\n[실행 당시 설정 적용 범위]\n" + run.readiness.text()
        + "\n설정 적용은 실제 신원·권한·쓰기 성공의 증명이 아닙니다.";
    entries.add(new Entry(run.original, run.response, "권한 비교 원본", bounded(setup), ""));
    if (run.currentBaseline != null)
      entries.add(new Entry(run.original, run.currentBaseline, "권한 비교 기준 재조회",
          group + "\n읽기 기준 재조회 · " + run.baselineState + " · " + run.baselineNote, ""));
    for (int slot = 0; slot < run.packets.length; slot++) {
      AuthComparePanel.Packet packet = run.packets[slot];
      if (packet.request == null) continue;
      AuthFinding finding = packet.finding();
      String account = finding.kind() == AuthFinding.Kind.CHANGED
          || packet.note.contains("전송 세션 변경") ? "" : run.context.accountIds()[slot];
      String policy = "권한표 기대: " + run.context.matrix().expected(
          run.original.method(), run.original.url(), slot)
          + " · 경로 정책: " + run.context.policy().forRequest(run.original.method(), run.original.url());
      String note = group + "\n비교 계정: " + run.context.labels()[slot] + "\n" + policy
          + "\n판정: " + finding.kind() + " · " + packet.note
          + "\n판정 근거/차이:\n" + bounded(packet.changes);
      if (packet.effect != null) {
        var effect = packet.effect;
        note += "\n쓰기 조회: " + effect.outcome() + " · " + effect.note()
            + "\n관측: " + effect.config().mode() + " · 필드 " + effect.config().jsonPath()
            + " · 최대 " + effect.config().attempts() + "회";
      } else note += GraphqlSemantics.read(packet.request)
          ? "\n읽기 요청 · 쓰기 조회 대상 아님"
          : "\n쓰기 조회 증거: 없음 · 응답만으로 실제 변경 성공을 확정하지 않습니다.";
      entries.add(new Entry(packet.request, packet.response, run.context.labels()[slot],
          bounded(note), account));
      if (packet.effect != null) {
        addObservation(entries, packet.effect.before(), group, run.context.labels()[slot],
            "쓰기 전 조회", account, packet.effect.outcome().name());
        addObservation(entries, packet.effect.after(), group, run.context.labels()[slot],
            "쓰기 후 조회", account, packet.effect.outcome().name());
      }
    }
    return List.copyOf(entries);
  }

  private static void addObservation(List<Entry> entries, AuthEffectCheck.Snapshot snapshot,
      String group, String label, String phase, String account, String outcome) {
    if (snapshot == null || snapshot.request() == null) return;
    entries.add(new Entry(snapshot.request(), snapshot.response(), label + " · " + phase,
        group + "\n" + phase + " · 전체 쓰기 관측 결과: " + outcome
            + "\n현재 설정이 아니라 이 실행 당시의 조회 패킷입니다.", account));
  }

  private static String bounded(String text) {
    String value = java.util.Objects.requireNonNullElse(text, "");
    int limit = 16 * 1024;
    return value.length() <= limit ? value : value.substring(0, limit)
        + "\n[설명 16,384자 이후 생략 · 상세 원본은 로컬 비교 기록 확인]";
  }
}
