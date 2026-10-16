package kr.featuremapper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Data-integrity and contention regressions for indexed mapping and leased project saves. */
final class MapperOptimizationTest {
  private static void check(boolean value, String message) {
    MapperTest.check(value, message);
  }

  static void run() throws Exception {
    indexedMemberships();
    indexedBrowserCandidates();
    browserLimitsAndOrdering();
    detachedProjectSnapshot();
    nonblockingSnapshotCopy();
    differentialTableRefresh();
  }

  private static void assertIndexes(Mapper mapper, String stage) {
    List<Mapper.Packet> all = mapper.packetList(null);
    Map<String, Integer> summary = new HashMap<>();
    mapper.featureSummaries().forEach(f -> summary.put(f.id(), f.packets()));
    boolean exact = true;
    for (Mapper.Feature feature : mapper.featureList()) {
      List<Long> expected =
          all.stream().filter(p -> p.links.containsKey(feature.id)).map(p -> p.id).toList();
      List<Long> actual = mapper.packetList(feature.id).stream().map(p -> p.id).toList();
      exact &= expected.equals(actual) && summary.get(feature.id) == expected.size();
    }
    check(exact, "feature index preserves every membership and capture order after " + stage);
  }

  private static void indexedMemberships() throws Exception {
    long time = System.currentTimeMillis();
    Path project = Files.createTempFile("mapper-index-", ".fmkr");
    try (Mapper mapper = new Mapper()) {
      var upload =
          mapper.accept(
              MapperTest.input(
                  time,
                  "POST",
                  "https://lab.test/api/files",
                  "{}",
                  "{\"fileId\":\"f-test\"}",
                  "A"));
      var post =
          mapper.accept(
              MapperTest.input(
                  time + 20,
                  "POST",
                  "https://lab.test/api/posts",
                  "{\"fileId\":\"f-test\"}",
                  "{\"postId\":\"p-test\"}",
                  "A"));
      mapper.accept(
          MapperTest.input(time + 40, "GET", "https://lab.test/api/posts/p-test", "", "{}", "A"));
      assertIndexes(mapper, "ancestor propagation");
      mapper.browser(
          MapperTest.event(time + 20, "POST", post.url, post.bodyHash, "click", "1", "등록"));
      assertIndexes(mapper, "late browser mapping");
      mapper.accept(
          MapperTest.input(time + 21, "POST", post.url, "{\"fileId\":\"f-test\"}", "{}", "A"));
      assertIndexes(mapper, "ambiguous browser retraction");
      mapper.move(post.id, upload.primary);
      assertIndexes(mapper, "manual correction");
      ProjectIO.save(mapper, project);
      try (Mapper restored = ProjectIO.load(project)) {
        assertIndexes(restored, "project import");
      }
    } finally {
      Files.deleteIfExists(project);
    }
  }

  private static void indexedBrowserCandidates() throws Exception {
    long time = System.currentTimeMillis();
    try (Mapper mapper = new Mapper()) {
      for (int i = 0; i < 300; i++)
        mapper.browser(
            MapperTest.event(
                time, "GET", "https://lab.test/events/" + i, "", "event-" + i, "1", "조회"));
      long before = mapper.browserCandidatesExamined();
      for (int i = 0; i < 500; i++)
        mapper.accept(
            MapperTest.input(time, "GET", "https://lab.test/packets/" + i, "", "{}", "A"));
      check(
          mapper.browserCandidatesExamined() == before,
          "unrelated browser URLs examine zero packet/event candidates");
      var first =
          mapper.accept(
              MapperTest.input(time, "POST", "https://lab.test/body", "first", "{}", "A"));
      var second = mapper.accept(MapperTest.input(time, "POST", first.url, "second", "{}", "A"));
      mapper.browser(
          MapperTest.event(time, "POST", first.url, first.bodyHash, "body", "1", "내용 확인"));
      check(
          !first.browserAction.isBlank() && second.browserAction.isBlank(),
          "body-hash index distinguishes identical method and URL");
      mapper.browser(MapperTest.event(time, "POST", first.url, "", "wildcard", "2", "내용 확인"));
      check(
          first.browserAction.isBlank() && second.browserAction.isBlank(),
          "hashless event retains conservative ambiguity across bodies");
      var unique =
          mapper.accept(MapperTest.input(time, "GET", "https://lab.test/single", "", "{}", "A"));
      mapper.browser(MapperTest.event(time, "GET", unique.url, "", "one", "1", "조회"));
      mapper.browser(MapperTest.event(time, "GET", unique.url, "", "two", "2", "조회"));
      check(
          unique.browserAction.isBlank(),
          "distinct simultaneous tab actions retract browser attribution");
      var manual =
          mapper.accept(
              MapperTest.input(time, "POST", "https://lab.test/manual", "manual", "{}", "A"));
      mapper.browser(
          MapperTest.event(time, "POST", manual.url, manual.bodyHash, "manual", "1", "수정"));
      String manuallyChosen = manual.primary;
      mapper.move(manual.id, manuallyChosen);
      mapper.accept(MapperTest.input(time + 1, "POST", manual.url, "manual", "{}", "A"));
      check(
          manual.browserAction.isBlank()
              && manual.primary.equals(manuallyChosen)
              && manual.links.containsKey(manuallyChosen),
          "ambiguous browser retraction preserves an explicit user correction");
      assertIndexes(mapper, "body and tab ambiguity");
    }
  }

  private static void browserLimitsAndOrdering() throws Exception {
    long time = System.currentTimeMillis();
    try (Mapper mapper = new Mapper()) {
      var boundary =
          mapper.accept(MapperTest.input(time, "GET", "https://lab.test/boundary", "", "{}", "A"));
      mapper.browser(
          MapperTest.event(
              time + Mapper.BROWSER_WINDOW, "GET", boundary.url, "", "edge", "1", "조회"));
      check(
          boundary.browserAction.isBlank(),
          "browser correlation keeps the exclusive 2500 ms boundary");
      mapper.accept(MapperTest.input(time, "GET", "https://lab.test/clock", "", "{}", "A"));
      var expired =
          mapper.accept(
              MapperTest.input(
                  time - Mapper.LINK_WINDOW - 1, "GET", "https://lab.test/old", "", "{}", "A"));
      mapper.browser(
          MapperTest.event(
              time - Mapper.LINK_WINDOW + 500, "GET", expired.url, "", "old", "1", "조회"));
      check(
          expired.browserAction.isBlank(),
          "out-of-order old packet expires from browser candidates without deleting raw traffic");
      for (int i = 0; i <= 2000; i++)
        mapper.browser(
            MapperTest.event(time, "GET", "https://lab.test/cap/" + i, "", "cap-" + i, "1", "조회"));
      var evicted =
          mapper.accept(MapperTest.input(time, "GET", "https://lab.test/cap/0", "", "{}", "A"));
      var retained =
          mapper.accept(MapperTest.input(time, "GET", "https://lab.test/cap/2000", "", "{}", "A"));
      check(
          evicted.browserAction.isBlank() && !retained.browserAction.isBlank(),
          "browser event history retains the latest 2000 events");
    }
  }

  private static void detachedProjectSnapshot() throws Exception {
    long time = System.currentTimeMillis();
    Mapper mapper = new Mapper();
    Path project = Files.createTempFile("mapper-snapshot-", ".fmkr");
    Path raw = mapper.store().path();
    var input =
        MapperTest.input(
            time, "POST", "https://lab.test/api/posts", "{}", "{\"postId\":\"saved\"}", "A");
    var packet = mapper.accept(input);
    mapper.edit(packet.primary, "저장 시점", "미점검", "원본 메모");
    try (Mapper.SaveSnapshot snapshot = mapper.snapshot()) {
      mapper.edit(packet.primary, "수정 후", "완료", "변경 메모");
      mapper.browser(
          MapperTest.event(time, "POST", packet.url, packet.bodyHash, "later", "1", "새 동작"));
      mapper.accept(
          MapperTest.input(time + 10, "GET", "https://lab.test/api/posts/saved", "", "{}", "A"));
      mapper.close();
      check(
          Files.exists(raw),
          "project close defers raw-file deletion while a save snapshot owns it");
      ProjectIO.save(snapshot, project);
      try (Mapper loaded = ProjectIO.load(project)) {
        var saved = loaded.packetList(null).get(0);
        check(
            loaded.count() == 1 && saved.browserAction.isBlank(),
            "save snapshot excludes later packets and detaches mutable mapping metadata");
        check(
            loaded.features.get(saved.primary).name.equals("저장 시점"),
            "save snapshot keeps feature state from its capture boundary");
        check(
            Arrays.equals(input.request(), loaded.raw(saved.id)[0])
                && Arrays.equals(input.response(), loaded.raw(saved.id)[1]),
            "snapshot remains byte-exact after project replacement and close");
      }
    } finally {
      mapper.close();
      Files.deleteIfExists(project);
    }
    check(!Files.exists(raw), "last save lease deletes a closed project's private packet file");
  }

  private static void nonblockingSnapshotCopy() throws Exception {
    Mapper mapper = new Mapper();
    long time = System.currentTimeMillis();
    byte[] body = new byte[256 * 1024];
    new Random(42).nextBytes(body);
    mapper.accept(
        MapperTest.input(
            time,
            "POST",
            "https://lab.test/large",
            body,
            body,
            "A",
            200,
            "application/octet-stream"));
    CountDownLatch writing = new CountDownLatch(1), resume = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try (Mapper.SaveSnapshot snapshot = mapper.snapshot()) {
      ByteArrayOutputStream result = new ByteArrayOutputStream();
      AtomicBoolean first = new AtomicBoolean(true);
      OutputStream slow =
          new FilterOutputStream(result) {
            public void write(byte[] bytes, int offset, int length) throws IOException {
              if (first.getAndSet(false)) {
                writing.countDown();
                try {
                  if (!resume.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new InterruptedIOException();
                }
              }
              out.write(bytes, offset, length);
            }
          };
      Future<?> copying =
          workers.submit(
              () -> {
                try {
                  snapshot.raw().copyTo(slow);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
      if (!writing.await(5, TimeUnit.SECONDS))
        throw new AssertionError("snapshot did not enter output");
      Future<?> capture =
          workers.submit(
              () -> {
                try {
                  mapper.accept(
                      MapperTest.input(
                          time + 1, "GET", "https://lab.test/during-save", "", "{}", "A"));
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
      capture.get(5, TimeUnit.SECONDS);
      check(mapper.count() == 2, "capture completes while snapshot output is deliberately blocked");
      mapper.close();
      resume.countDown();
      copying.get(5, TimeUnit.SECONDS);
      check(
          mapper.count() == 2,
          "closed mapper retains metadata for in-flight read-only snapshot consumers");
      check(
          result.size() == snapshot.raw().bytes(),
          "snapshot copies only its fixed prefix despite concurrent append and close");
    } finally {
      resume.countDown();
      workers.shutdownNow();
      mapper.close();
    }
  }

  private static void differentialTableRefresh() throws Exception {
    FeatureMapperExtension extension = new FeatureMapperExtension();
    extension.mapper = new Mapper();
    try {
      long time = System.currentTimeMillis();
      extension.mapper.accept(
          MapperTest.input(time, "GET", "https://lab.test/ui/one", "", "{}", "A"));
      var method = FeatureMapperExtension.class.getDeclaredMethod("fillRows");
      method.setAccessible(true);
      var rowsField = FeatureMapperExtension.class.getDeclaredField("rows");
      rowsField.setAccessible(true);
      var rows = (javax.swing.table.DefaultTableModel) rowsField.get(extension);
      List<javax.swing.event.TableModelEvent> events = new ArrayList<>();
      javax.swing.SwingUtilities.invokeAndWait(
          () -> {
            try {
              method.invoke(extension);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            rows.addTableModelListener(events::add);
            try {
              method.invoke(extension);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      check(
          events.isEmpty() && rows.getRowCount() == 1,
          "unchanged Mapper rows emit no table replacement or cell events");
      extension.mapper.accept(
          MapperTest.input(time + 1, "GET", "https://lab.test/ui/two", "", "{}", "A"));
      javax.swing.SwingUtilities.invokeAndWait(
          () -> {
            try {
              method.invoke(extension);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      check(
          events.size() == 1
              && events.get(0).getType() == javax.swing.event.TableModelEvent.INSERT
              && rows.getRowCount() == 2,
          "new capture appends one UI row without rebuilding existing rows");
      var tableField = FeatureMapperExtension.class.getDeclaredField("table");
      tableField.setAccessible(true);
      var table = (javax.swing.JTable) tableField.get(extension);
      List<javax.swing.JTextArea> details = new ArrayList<>();
      for (String name : List.of("requestText", "responseText", "explanation")) {
        var field = FeatureMapperExtension.class.getDeclaredField(name);
        field.setAccessible(true);
        details.add((javax.swing.JTextArea) field.get(extension));
      }
      javax.swing.SwingUtilities.invokeAndWait(
          () -> {
            table.setRowSelectionInterval(0, 0);
            details.forEach(area -> area.setText("이전 프로젝트의 상세"));
          });
      Mapper old = extension.mapper;
      extension.mapper = new Mapper();
      old.close();
      extension.mapper.accept(
          MapperTest.input(time + 2, "GET", "https://other.test/reused-id", "", "{}", "A"));
      javax.swing.SwingUtilities.invokeAndWait(
          () -> {
            try {
              method.invoke(extension);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      check(
          table.getSelectedRowCount() == 0
              && details.stream().allMatch(area -> area.getText().isEmpty()),
          "project replacement clears old selection and packet details even when packet IDs"
              + " repeat");
      byte[] big = new byte[200 * 1024];
      new Random(9).nextBytes(big);
      var input =
          MapperTest.input(
              time + 2,
              "POST",
              "https://lab.test/preview",
              big,
              big,
              "A",
              200,
              "application/octet-stream");
      var packet = extension.mapper.accept(input);
      var preview = extension.mapper.preview(packet.id);
      check(
          preview.request().length == 128 * 1024
              && preview.response().length == 128 * 1024
              && preview.requestBytes() == input.request().length
              && preview.responseBytes() == input.response().length,
          "Mapper preview reads bounded prefixes and preserves original packet lengths");
      check(
          Arrays.equals(extension.mapper.raw(packet.id)[0], input.request())
              && Arrays.equals(extension.mapper.raw(packet.id)[1], input.response()),
          "bounded preview leaves large original request and response byte-exact");
    } finally {
      var unload = FeatureMapperExtension.class.getDeclaredMethod("unload");
      unload.setAccessible(true);
      unload.invoke(extension);
    }
  }
}
