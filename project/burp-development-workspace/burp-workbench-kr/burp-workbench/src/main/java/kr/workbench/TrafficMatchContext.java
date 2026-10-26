package kr.workbench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded, ephemeral display excerpts. Character offsets never masquerade as wire byte offsets. */
final class TrafficMatchContext {
  static final int CONTEXT_CHARS = 4096, MAX_PAGES = 100;
  record Page(String text, int start, int end, int sourceOffset, String location) {}
  record Result(String source, List<Page> pages, boolean capped, boolean partial, String note) {}

  static Result locate(DiskTrafficStore.PacketRef ref, boolean response, TrafficQuery settings,
                       String query, BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean()) throw new CancellationException();
    if (settings.prepare(query).matchesText(ref.method()+" "+ref.url()+" "+ref.responseStatus(), cancelled))
      return new Result("목록 메타데이터", List.of(), false, false, "메서드·URL·상태에서 일치 · 본문 일치와 구분");
    String source = response ? "응답 원문" : "요청 원문";
    int length = response ? ref.responseLength() : ref.requestLength();
    if (response && !ref.hasResponse()) return new Result(source, List.of(), false, false, "응답 없음");
    byte[] prefix = response ? ref.responsePrefix(TrafficBrowser.SEARCH_BYTES) : ref.requestPrefix(TrafficBrowser.SEARCH_BYTES);
    boolean partial = length > prefix.length;
    Result raw = extract(HttpText.decode(prefix, null), source, settings, query, partial, cancelled);
    if (!raw.pages().isEmpty() || !raw.note().isEmpty() || !response || partial) return raw;
    // Match the list evaluator: decoding is attempted only with a complete, bounded wire response.
    try {
      var packet = burp.api.montoya.http.message.responses.HttpResponse.httpResponse(
          burp.api.montoya.core.ByteArray.byteArray(prefix));
      String decoded = JsBodyDecoder.decode(packet.body().getBytes(), packet.headerValue("Content-Encoding"),
          packet.headerValue("Content-Type"));
      return extract(decoded, "응답 디코딩 본문", settings, query, false, cancelled);
    } catch (CancellationException error) { throw error; }
    catch (Exception error) {
      return new Result(source, List.of(), false, false, "이 원문에서 일치 없음 · 본문 디코딩 불가/제한");
    }
  }

  static Result extract(String value, String source, TrafficQuery settings, String query,
                        boolean partial, BooleanSupplier cancelled) {
    PacketInspector.SearchResult found = PacketInspector.findContexts(value, query, settings.regex(), settings.sensitive(), cancelled);
    if (cancelled.getAsBoolean()) throw new CancellationException();
    if (found.error() != null) return new Result(source, List.of(), false, partial, found.error());
    List<Page> pages = new ArrayList<>();
    for (PacketInspector.Span span : found.spans()) {
      if (cancelled.getAsBoolean()) throw new CancellationException();
      if (pages.size() == MAX_PAGES) break;
      int from = Math.max(0, span.start() - CONTEXT_CHARS / 3);
      int to = Math.min(value.length(), Math.max(span.end(), from + CONTEXT_CHARS));
      // A single huge regex match must not expand the bounded UI excerpt.
      to = Math.min(to, from + CONTEXT_CHARS);
      if (from > 0 && Character.isLowSurrogate(value.charAt(from)) && Character.isHighSurrogate(value.charAt(from - 1))) from++;
      if (to < value.length() && to > from && Character.isHighSurrogate(value.charAt(to - 1)) && Character.isLowSurrogate(value.charAt(to))) to--;
      int separator = value.indexOf("\r\n\r\n"), separatorLength = 4;
      if (separator < 0) { separator = value.indexOf("\n\n");separatorLength = 2; }
      String location = source.contains("디코딩") ? source : source + (separator < 0 ? " (헤더/본문 경계 미확인)"
          : span.start() < separator + separatorLength ? " 헤더·시작줄" : " 본문");
      pages.add(new Page(value.substring(from, to), Math.max(0, span.start() - from),
          Math.min(to - from, span.end() - from), from, location));
    }
    return new Result(source, List.copyOf(pages), found.capped() || found.spans().size() > MAX_PAGES, partial, "");
  }

  private TrafficMatchContext() {}
}
