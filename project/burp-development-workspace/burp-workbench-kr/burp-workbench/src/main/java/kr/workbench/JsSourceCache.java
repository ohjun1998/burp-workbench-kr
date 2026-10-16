package kr.workbench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One LRU budget for source text, packet previews, findings and recovered source maps. Costs
 * deliberately count UTF-16 text plus record overhead, not measured JVM heap use. Editor documents
 * and the one active parser are outside this retained-cache estimate.
 */
final class JsSourceCache {
  static final long DEFAULT_BUDGET = 24L * 1024 * 1024;
  static final int MAX_ENTRIES = 110;
  static final int MAX_RESULTS = 100;

  record Entry(String source, String fingerprint, JsAnalysis.Result result, String[] packet) {}

  record Change(boolean accepted, List<String> evicted) {}

  private final long budget;
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
  private long bytes;
  private long evictions;
  private long rejected;
  private String selected = "";

  JsSourceCache(long budget) {
    if (budget < 1) throw new IllegalArgumentException("positive cache budget required");
    this.budget = budget;
  }

  synchronized void select(String key) {
    selected = Objects.requireNonNullElse(key, "");
    entries.get(selected);
  }

  synchronized Entry get(String key) {
    return entries.get(key);
  }

  synchronized JsAnalysis.Result result(String key) {
    Entry entry = entries.get(key);
    return entry == null ? null : entry.result();
  }

  synchronized String source(String key) {
    Entry entry = entries.get(key);
    return entry == null ? null : entry.source();
  }

  synchronized String fingerprint(String key) {
    Entry entry = entries.get(key);
    return entry == null ? null : entry.fingerprint();
  }

  synchronized void resetFingerprints() {
    entries.replaceAll(
        (key, entry) -> new Entry(entry.source(), null, entry.result(), entry.packet()));
    recount();
  }

  synchronized Change analysis(
      String key, String source, String fingerprint, JsAnalysis.Result result, String[] packet) {
    Entry old = entries.get(key);
    if (packet == null && old != null) packet = old.packet();
    return replace(key, new Entry(source, fingerprint, result, packet));
  }

  synchronized Change packet(String key, String[] packet) {
    Entry old = entries.get(key);
    return replace(
        key,
        new Entry(
            old == null ? null : old.source(),
            old == null ? null : old.fingerprint(),
            old == null ? null : old.result(),
            packet.clone()));
  }

  synchronized Change sourceOnly(String key, String source) {
    Entry old = entries.get(key);
    return replace(
        key,
        new Entry(
            source,
            old == null ? null : old.fingerprint(),
            old == null ? null : old.result(),
            old == null ? null : old.packet()));
  }

  synchronized Change result(String key, JsAnalysis.Result result) {
    Entry old = entries.get(key);
    if (old == null) return new Change(false, List.of());
    return replace(key, new Entry(old.source(), old.fingerprint(), result, old.packet()));
  }

  private Change replace(String key, Entry replacement) {
    Entry old = entries.get(key);
    long cost = weight(key, replacement);
    Entry pinned = key.equals(selected) ? null : entries.get(selected);
    // Do not destroy other entries or the selected source when a single update cannot fit.
    if (cost > budget || pinned != null && cost + weight(selected, pinned) > budget) {
      rejected++;
      return new Change(false, List.of());
    }
    if (old != null) bytes -= weight(key, old);
    entries.put(key, replacement);
    bytes += cost;
    List<String> removed = new ArrayList<>();
    while (bytes > budget || entries.size() > MAX_ENTRIES || resultCount() > MAX_RESULTS) {
      String victim = null;
      for (var candidate : entries.entrySet()) {
        if (!candidate.getKey().equals(selected) && !candidate.getKey().equals(key)) {
          // Source-map only items do not relieve the independent result count limit.
          if (bytes <= budget
              && entries.size() <= MAX_ENTRIES
              && candidate.getValue().result() == null) continue;
          victim = candidate.getKey();
          break;
        }
      }
      if (victim == null) throw new IllegalStateException("cache budget invariant");
      Entry entry = entries.remove(victim);
      bytes -= weight(victim, entry);
      removed.add(victim);
      evictions++;
    }
    return new Change(true, List.copyOf(removed));
  }

  private int resultCount() {
    int count = 0;
    for (Entry entry : entries.values()) if (entry.result() != null) count++;
    return count;
  }

  private void recount() {
    bytes = 0;
    for (var entry : entries.entrySet()) bytes += weight(entry.getKey(), entry.getValue());
  }

  synchronized Map<String, Entry> snapshot() {
    return new LinkedHashMap<>(entries);
  }

  synchronized long bytes() {
    return bytes;
  }

  long budget() {
    return budget;
  }

  synchronized long evictions() {
    return evictions;
  }

  synchronized long rejected() {
    return rejected;
  }

  synchronized void clear() {
    entries.clear();
    bytes = 0;
    selected = "";
  }

  static long textBytes(String value) {
    return value == null ? 0 : 40L + value.length() * 2L;
  }

  static long weight(String key, Entry entry) {
    long size = 160 + textBytes(key) + textBytes(entry.source()) + textBytes(entry.fingerprint());
    if (entry.packet() != null) {
      size += 40;
      for (String text : entry.packet()) size += textBytes(text);
    }
    JsAnalysis.Result result = entry.result();
    if (result == null) return size;
    size += 96 + textBytes(result.status());
    for(var draft:result.requestDrafts().entrySet())size+=128+textBytes(draft.getKey())+textBytes(draft.getValue().url())+textBytes(draft.getValue().method())+textBytes(draft.getValue().body())+textBytes(draft.getValue().contentType());
    for (var endpoint : result.endpoints())
      size +=
          128
              + textBytes(endpoint.url())
              + textBytes(endpoint.method())
              + textBytes(endpoint.kind())
              + textBytes(endpoint.parameters())
              + textBytes(endpoint.headers())
              + textBytes(endpoint.bodyKeys())
              + textBytes(endpoint.evidence());
    for (var secret : result.secrets())
      size +=
          96
              + textBytes(secret.rule())
              + textBytes(secret.masked())
              + textBytes(secret.fingerprint())
              + textBytes(secret.confidence());
    JsMiner.Result miner = result.miner();
    size += 160 + textBytes(miner.note());
    for (var item : miner.domains())
      size += 64 + textBytes(item.host()) + textBytes(item.relation()) + textBytes(item.provider());
    for (var item : miner.clouds()) size += 56 + textBytes(item.provider()) + textBytes(item.url());
    for (var item : miner.dependencies())
      size +=
          64
              + textBytes(item.name())
              + textBytes(item.scope())
              + textBytes(item.origin())
              + textBytes(item.status());
    for (var item : miner.maps()) size += 48 + textBytes(item.url()) + textBytes(item.state());
    for (var item : miner.recovered())
      size += 56 + textBytes(item.mapUrl()) + textBytes(item.name()) + textBytes(item.content());
    return size;
  }
}
