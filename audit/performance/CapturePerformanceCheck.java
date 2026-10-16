package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.logging.Logging;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableModel;

/** Production capture callback and Swing timers, with a synthetic Montoya host (not native Burp). */
public final class CapturePerformanceCheck extends WorkbenchTest {
    private static long heap() { var r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }
    private static long percentile(List<Long> samples, double percentile) {
        if (samples.isEmpty()) return 0;
        long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * percentile) - 1)];
    }
    private static Map<String,Object> timing(List<Long> samples) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("samples", samples.size());
        result.put("p50Millis", percentile(samples, .50) / 1_000_000.0);
        result.put("p95Millis", percentile(samples, .95) / 1_000_000.0);
        result.put("p99Millis", percentile(samples, .99) / 1_000_000.0);
        result.put("maxMillis", percentile(samples, 1) / 1_000_000.0);
        return result;
    }
    private static final class Fixture implements AutoCloseable {
        final WorkbenchExtension ext = new WorkbenchExtension();
        final AtomicReference<ExtensionUnloadingHandler> unload = new AtomicReference<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final AtomicLong restoredOnEdt = new AtomicLong(), restoredOffEdt = new AtomicLong();
        final AtomicLong insertEvents = new AtomicLong(), updateEvents = new AtomicLong(), deleteEvents = new AtomicLong();
        final ResponseReceivedAction action = proxy(ResponseReceivedAction.class, (p,m,a) -> def(m));
        final ToolSource source = proxy(ToolSource.class, (p,m,a) -> m.getName().equals("toolType") ? ToolType.PROXY : def(m));
        Fixture() throws Exception {
            ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p,m,a) ->
                    m.getReturnType() == ResponseReceivedAction.class ? action : def(m));
            installTrafficPacketFactory();
            Extension extension = proxy(Extension.class, (p,m,a) -> {
                if (m.getName().equals("registerUnloadingHandler")) unload.set((ExtensionUnloadingHandler) a[0]);
                return m.getReturnType().isInterface() ? PayloadIntegrationTest.host(m.getReturnType()) : def(m);
            });
            Logging logging = proxy(Logging.class, (p,m,a) -> {
                if (m.getName().equals("logToError")) errors.add(String.valueOf(a[0]).lines().findFirst().orElse(""));
                return def(m);
            });
            MontoyaApi api = proxy(MontoyaApi.class, (p,m,a) -> switch (m.getName()) {
                case "extension" -> extension;
                case "logging" -> logging;
                default -> PayloadIntegrationTest.host(m.getReturnType());
            });
            ext.initialize(api);
            edt(() -> {});
            MontoyaObjectFactory previous = ObjectFactoryLocator.FACTORY;
            ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p,m,a) -> {
                if (m.getName().equals("httpRequest") || m.getName().equals("httpResponse")) {
                    (SwingUtilities.isEventDispatchThread() ? restoredOnEdt : restoredOffEdt).incrementAndGet();
                }
                return m.invoke(previous, a);
            });
            edt(() -> {
                try {
                    ((DefaultTableModel) get(ext, "logModel")).addTableModelListener(e -> {
                        if (e.getType() == TableModelEvent.INSERT) insertEvents.incrementAndGet();
                        else if (e.getType() == TableModelEvent.DELETE) deleteEvents.incrementAndGet();
                        else updateEvents.incrementAndGet();
                    });
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        HttpResponseReceived event(HttpRequest request, HttpResponse response) {
            return proxy(HttpResponseReceived.class, (p,m,a) -> switch (m.getName()) {
                case "initiatingRequest" -> request;
                case "toolSource" -> source;
                case "annotations" -> null;
                case "messageId" -> 101;
                default -> HttpResponse.class.getMethod(m.getName(), m.getParameterTypes()).invoke(response, a);
            });
        }
        void send(HttpResponseReceived event) {
            if (ext.handleHttpResponseReceived(event) != action) throw new AssertionError("Burp continueWith action lost");
        }
        DiskTrafficStore store() throws Exception { return (DiskTrafficStore) get(ext, "trafficStore"); }
        long metric(String name) throws Exception { return ((AtomicLong) get(ext, name)).get(); }
        int dropped() throws Exception { return ((AtomicInteger) get(ext, "dropped")).get(); }
        int rowsOnEdt() throws Exception { return ((List<?>) get(ext, "traffic")).size(); }
        int rows() throws Exception {
            AtomicInteger count = new AtomicInteger();
            edt(() -> { try { count.set(rowsOnEdt()); } catch (Exception e) { throw new RuntimeException(e); } });
            return count.get();
        }
        long analysisPending() throws Exception {
            try {
                Object worker = get(ext, "captureObservations");
                Method method = worker.getClass().getDeclaredMethod("pending");
                method.setAccessible(true);
                return ((Number) method.invoke(worker)).longValue();
            } catch (NoSuchFieldException | NoSuchMethodException e) { return 0; }
        }
        void await(int expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < deadline) {
                if (rows() == expected && analysisPending() == 0) return;
                TimeUnit.MILLISECONDS.sleep(20);
            }
            throw new AssertionError("Drain timeout: rows=" + rows() + ", analysisPending=" + analysisPending());
        }
        DiskTrafficStore.PacketRef ref(int index) throws Exception {
            AtomicReference<DiskTrafficStore.PacketRef> ref = new AtomicReference<>();
            edt(() -> {
                try {
                    Object row = ((List<?>) get(ext, "traffic")).get(index);
                    Method method = row.getClass().getDeclaredMethod("packet"); method.setAccessible(true);
                    ref.set((DiskTrafficStore.PacketRef) method.invoke(row));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            return ref.get();
        }
        @Override public void close() throws Exception {
            if (unload.get() != null) unload.get().extensionUnloaded();
            edt(() -> {});
        }
    }
    private static Map<String,Object> scenario(String name, int rate, int count, int bodyBytes) throws Exception {
        HttpRequest request = request("https://benchmark.invalid/api/items/1", "GET", false, List.of());
        HttpResponse response = response(200, "x".repeat(bodyBytes));
        List<Long> callbacks = new ArrayList<>(), edtDelay = new CopyOnWriteArrayList<>();
        AtomicInteger sent = new AtomicInteger(), maximumBacklog = new AtomicInteger();
        AtomicLong oldestPendingAge = new AtomicLong(), peakHeap = new AtomicLong(), maxAnalysisPending = new AtomicLong();
        AtomicBoolean sampleQueued = new AtomicBoolean();
        long[] sentAt = new long[count];
        Map<String,Object> result = new LinkedHashMap<>();
        Path storeFile;
        try (Fixture fixture = new Fixture()) {
            storeFile = fixture.store().file();
            HttpResponseReceived event = fixture.event(request, response);
            System.gc();
            long heapBefore = heap();
            peakHeap.set(heapBefore);
            var sampler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "performance-sampler"); thread.setDaemon(true); return thread;
            });
            sampler.scheduleAtFixedRate(() -> {
                peakHeap.accumulateAndGet(heap(), Math::max);
                try { maxAnalysisPending.accumulateAndGet(fixture.analysisPending(), Math::max); }
                catch (Exception ignored) {}
                if (!sampleQueued.compareAndSet(false, true)) return;
                long queued = System.nanoTime();
                SwingUtilities.invokeLater(() -> {
                    try {
                        edtDelay.add(System.nanoTime() - queued);
                        int rows = fixture.rowsOnEdt(), accepted = sent.get();
                        maximumBacklog.accumulateAndGet(Math.max(0, accepted - rows), Math::max);
                        if (accepted > rows) oldestPendingAge.accumulateAndGet(System.nanoTime() - sentAt[rows], Math::max);
                    } catch (Exception e) { throw new RuntimeException(e); }
                    finally { sampleQueued.set(false); }
                });
            }, 0, 25, TimeUnit.MILLISECONDS);
            long start = System.nanoTime();
            try {
                for (int i = 0; i < count; i++) {
                    if (rate > 0) {
                        long deadline = start + (long) i * 1_000_000_000L / rate;
                        long remaining;
                        while ((remaining = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
                    }
                    long before = System.nanoTime();
                    fixture.send(event);
                    callbacks.add(System.nanoTime() - before);
                    sentAt[i] = System.nanoTime();
                    sent.incrementAndGet();
                }
                long sendComplete = System.nanoTime();
                int rowsAtSendEnd = fixture.rows();
                fixture.await(count);
                long drained = System.nanoTime();
                sampler.shutdownNow();
                edt(() -> {});
                if (fixture.metric("received") != count || fixture.store().size() != count || fixture.rows() != count
                        || fixture.dropped() != 0 || fixture.metric("processingErrors") != 0 || !fixture.errors.isEmpty()) {
                    throw new AssertionError("Capture fidelity failure: " + fixture.errors);
                }
                result.put("scenario", name); result.put("offeredRatePerSecond", rate == 0 ? "burst" : rate);
                result.put("responseBodyBytes", bodyBytes); result.put("callbacks", count);
                result.put("callbackLatency", timing(callbacks)); result.put("edtDispatchDelay", timing(edtDelay));
                result.put("sendSeconds", (sendComplete - start) / 1_000_000_000.0);
                result.put("postSendDrainSeconds", (drained - sendComplete) / 1_000_000_000.0);
                result.put("retainedAtSendEnd", rowsAtSendEnd); result.put("peakUiBacklog", maximumBacklog.get());
                result.put("oldestUiPendingMillis", oldestPendingAge.get() / 1_000_000.0);
                result.put("peakAnalysisPending", maxAnalysisPending.get());
                result.put("packetRestoresOnEdt", fixture.restoredOnEdt.get());
                result.put("packetRestoresOffEdt", fixture.restoredOffEdt.get());
                result.put("tableInsertEvents", fixture.insertEvents.get());
                result.put("tableUpdateEvents", fixture.updateEvents.get());
                result.put("tableDeleteEvents", fixture.deleteEvents.get());
                result.put("retainedPackets", fixture.store().size()); result.put("retainedRows", fixture.rows());
                result.put("diskBytes", fixture.store().diskBytes());
                result.put("heapBeforeBytes", heapBefore); result.put("sampledPeakHeapBytes", peakHeap.get());
                result.put("heapAfterBytes", heap());
                AtomicInteger rowObjects = new AtomicInteger();
                edt(() -> { try { rowObjects.set(((DefaultTableModel) get(fixture.ext, "logModel")).getDataVector().size()); }
                    catch (Exception e) { throw new RuntimeException(e); } });
                result.put("retainedDefaultTableRowVectors", rowObjects.get());
                // Fidelity checks run after accounting so three deliberate reads do not affect restore metrics.
                for (int index : new int[]{0, count / 2, count - 1}) {
                    DiskTrafficStore.PacketRef ref = fixture.ref(index);
                    if (!Arrays.equals(ref.request().toByteArray().getBytes(), request.toByteArray().getBytes())
                            || !Arrays.equals(ref.response().toByteArray().getBytes(), response.toByteArray().getBytes()))
                        throw new AssertionError("Raw packet bytes changed at " + index);
                }
                result.put("exactPacketSamples", 3); result.put("drops", fixture.dropped());
                result.put("processingErrors", fixture.metric("processingErrors"));
            } finally { sampler.shutdownNow(); }
        }
        if (Files.exists(storeFile)) throw new AssertionError("Temporary raw store survived unload");
        result.put("temporaryStoreDeleted", true);
        return result;
    }
    public static void main(String[] args) throws Exception {
        try {
            // Warm up host proxies and the real capture/observer paths, excluded from reported measurements.
            scenario("warmup", 0, 100, 4096);
            List<Map<String,Object>> scenarios = new ArrayList<>();
            scenarios.add(scenario("paced-100-small", 100, 300, 4096));
            scenarios.add(scenario("paced-500-small", 500, 1500, 4096));
            scenarios.add(scenario("paced-200-large", 200, 600, 65536));
            scenarios.add(scenario("burst-small", 0, 1500, 4096));
            Map<String,Object> report = new LinkedHashMap<>();
            report.put("host", "synthetic Montoya adapter; production capture callback, disk store and Swing timers");
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("heapLimitBytes", Runtime.getRuntime().maxMemory());
            report.put("scenarios", scenarios);
            Path output = Path.of(System.getProperty("qa.output")); Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, MetadataExport.json(report) + "\n");
            System.out.println("CAPTURE_PERFORMANCE_OK scenarios=" + scenarios.size());
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
