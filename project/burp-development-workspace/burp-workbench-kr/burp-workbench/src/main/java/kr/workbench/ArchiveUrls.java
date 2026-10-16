package kr.workbench;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

final class ArchiveUrls {
  static final int RESPONSE_LIMIT = 8 * 1024 * 1024, JSON_NODE_LIMIT = 40000;

  record Collection(int bytes, int urls, boolean atLimit) {}

  static URI endpoint(String domain, boolean subs) {
    return URI.create(
        "https://web.archive.org/cdx/search/cdx?url="
            + URLEncoder.encode(domain, StandardCharsets.UTF_8)
            + "&matchType="
            + (subs ? "domain" : "host")
            + "&output=json&fl=original&collapse=urlkey&limit="
            + PassiveUrls.LIMIT);
  }

  static int parse(String text, String domain, boolean subs, Consumer<String> found) {
    Object json = MinerJson.parse(text, RESPONSE_LIMIT, JSON_NODE_LIMIT);
    if (!(json instanceof List<?> rows)) throw new IllegalArgumentException("Wayback 응답 형식 오류");
    int count = 0;
    for (Object row : rows) {
      if (!(row instanceof List<?> values)
          || values.isEmpty()
          || !(values.get(0) instanceof String value)) continue;
      String url = PassiveUrls.url(value, domain, subs);
      if (url != null) {
        found.accept(url);
        if (++count >= PassiveUrls.LIMIT) break;
      }
    }
    return count;
  }

  static Collection collect(String domain, boolean subs, AtomicBoolean stop, Consumer<String> found)
      throws Exception {
    var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var request =
        HttpRequest.newBuilder(endpoint(domain, subs))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "Workbench-KR/" + BuildInfo.VERSION)
            .GET()
            .build();
    var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
    AtomicReference<InputStream> body = new AtomicReference<>();
    ScheduledExecutorService deadline =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "Wayback-deadline");
              t.setDaemon(true);
              return t;
            });
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
    deadline.scheduleAtFixedRate(
        () -> {
          if (stop.get() || System.nanoTime() > end) {
            future.cancel(true);
            try {
              InputStream stream = body.get();
              if (stream != null) stream.close();
            } catch (IOException ignored) {
            }
          }
        },
        100,
        100,
        TimeUnit.MILLISECONDS);
    try {
      var response = future.get(35, TimeUnit.SECONDS);
      body.set(response.body());
      try (InputStream stream = response.body()) {
        if (response.statusCode() != 200)
          throw new IOException("Wayback HTTP " + response.statusCode());
        byte[] data = stream.readNBytes(RESPONSE_LIMIT + 1);
        if (data.length > RESPONSE_LIMIT) throw new IOException("Wayback 응답 8MB 제한");
        int count =
            stop.get() ? 0 : parse(new String(data, StandardCharsets.UTF_8), domain, subs, found);
        return new Collection(data.length, count, count >= PassiveUrls.LIMIT);
      }
    } finally {
      deadline.shutdownNow();
      future.cancel(true);
    }
  }
}
