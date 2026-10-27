package kr.workbench;

import burp.api.montoya.http.message.requests.HttpRequest;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;

/** Exact selected request identity, without retaining a second copy of its HTTP bytes. */
final class OriginalRequestIds {
  private static final int LIMIT = 4096;
  private record Entry(WeakReference<HttpRequest> request, String id) {}
  private final Deque<Entry> entries = new ArrayDeque<>();

  synchronized void remember(HttpRequest request, String id) {
    entries.removeIf(entry -> entry.request().get() == null || entry.request().get() == request);
    if (entries.size() >= LIMIT) entries.removeFirst();
    entries.addLast(new Entry(new WeakReference<>(request), id));
  }
  synchronized String find(HttpRequest request) {
    entries.removeIf(entry -> entry.request().get() == null);
    for (Entry entry : entries) if (entry.request().get() == request) return entry.id();
    return "";
  }
  synchronized void clear() { entries.clear(); }
}
