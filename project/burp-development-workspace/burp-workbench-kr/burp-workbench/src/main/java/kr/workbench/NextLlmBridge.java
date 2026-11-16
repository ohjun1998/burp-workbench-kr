package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import java.util.*;

/** Explicit, bounded snapshots. AI preparation never sends, replays, or changes source packets. */
final class NextLlmBridge {
  record Entry(String id, HttpRequestResponse packet, String account, String note) {}
  record Prepared(LocalLlmTasks.Input input, Map<String,HttpRequestResponse> originals) {}
  private NextLlmBridge() {}

  static Prepared prepare(MontoyaApi api, LocalLlmTasks.Task task, String context, List<Entry> entries) {
    if (entries == null || entries.isEmpty() || entries.size() > 24)
      throw new IllegalArgumentException("AI 분석은 원본 1~24개씩 선택하세요. 기능별로 나누면 문맥과 응답 시간이 개선됩니다.");
    var originals = new LinkedHashMap<String,HttpRequestResponse>();
    var sources = new ArrayList<LocalLlmTasks.Source>();
    var notes = new StringBuilder(Objects.toString(context, ""));
    long bytes=0;
    for (var entry : entries) {
      var rr = entry.packet();
      if (rr == null || rr.request() == null || !WorkbenchScope.contains(api, rr.request()))
        throw new IllegalArgumentException("현재 SCOPE 안의 원본 요청이 필요합니다.");
      // Keep retained original memory bounded independently from the smaller sanitized prompt.
      if (rr.request().toByteArray().length() > 2 * 1024 * 1024
          || rr.response() != null && rr.response().toByteArray().length() > 2 * 1024 * 1024)
        throw new IllegalArgumentException("AI 연결 원본은 각 요청/응답 2 MiB 이하입니다. 필요한 원본을 따로 선택하세요.");
      bytes+=(long)rr.request().toByteArray().length()+(rr.response()==null?0:rr.response().toByteArray().length());
      if(bytes>8L*1024*1024)throw new IllegalArgumentException("AI 연결 원본 합계 8 MiB 초과 · 기능별로 나누세요.");
      if (originals.putIfAbsent(entry.id(), rr) != null)
        throw new IllegalArgumentException("동일 원본 ID가 중복되었습니다.");
      sources.add(LocalLlmTasks.capture(entry.id(), rr, Objects.toString(entry.account(), "")));
      if (entry.note() != null && !entry.note().isBlank()) {
        if (entry.note().length() > 16 * 1024) throw new IllegalArgumentException("AI 근거 설명은 원본별 16 KiB 이하로 줄이세요.");
        notes.append("\n\n[원본 ").append(entry.id()).append("]\n").append(entry.note());
      }
    }
    var input = new LocalLlmTasks.Input(task, notes.toString(), List.copyOf(sources));
    LocalLlmTasks.user(input); // Validate before replacing the user's previous prepared input.
    return new Prepared(input, Collections.unmodifiableMap(originals));
  }
}
