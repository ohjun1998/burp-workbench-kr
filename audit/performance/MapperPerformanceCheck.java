package kr.featuremapper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Identical production Mapper API workload on each source snapshot; no remote traffic. */
public final class MapperPerformanceCheck {
    private static final byte[] REQUEST_BODY = new byte[0];
    private static final byte[] RESPONSE_BODY = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    private static Mapper.Input input(long time, String url) {
        byte[] request = ("GET " + url + " HTTP/1.1\r\nHost: benchmark.invalid\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        return new Mapper.Input(time, "PROXY", "GET", url, 200, request, response, REQUEST_BODY, RESPONSE_BODY,
                Map.of("referer", "https://benchmark.invalid/app"), Map.of("content-type", "application/json"));
    }
    private static long heap() { var r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }
    private static Object candidates(Mapper mapper) throws Exception {
        try {
            Method method = Mapper.class.getDeclaredMethod("browserCandidatesExamined"); method.setAccessible(true);
            return method.invoke(mapper);
        } catch (NoSuchMethodException absent) { return "not instrumented in this snapshot"; }
    }
    private static Map<String,Object> event(long time, String url, String action) {
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("type", "network"); event.put("time", time); event.put("url", url); event.put("method", "GET");
        event.put("actionId", action); event.put("label", action); event.put("page", "https://benchmark.invalid/app");
        event.put("title", "Synthetic benchmark"); event.put("tab", "one"); event.put("frame", "main");
        return event;
    }
    private static Map<String,Object> featureQueries(int packetCount, int featureCount, int rounds) throws Exception {
        Map<String,Object> report = new LinkedHashMap<>();
        try (Mapper mapper = new Mapper()) {
            System.gc(); long heapBefore = heap(), time = System.currentTimeMillis(), start = System.nanoTime();
            for (int i = 0; i < packetCount; i++)
                mapper.accept(input(time + i, "https://benchmark.invalid/feature-" + (char) ('a' + i % featureCount / 26)
                        + (char) ('a' + i % featureCount % 26) + "/items/123"));
            long accepted = System.nanoTime();
            List<Mapper.Feature> features = mapper.featureList();
            if (features.size() != featureCount) throw new AssertionError("Unexpected feature count " + features.size());
            long memberships = 0, ids = 0;
            for (int round = 0; round < rounds; round++) for (Mapper.Feature feature : features)
                for (Mapper.Packet packet : mapper.packetList(feature.id)) { memberships++; ids += packet.id; }
            long queried = System.nanoTime();
            if (memberships != (long) rounds * packetCount || ids != (long) rounds * packetCount * (packetCount + 1) / 2)
                throw new AssertionError("Feature lookup lost/duplicated memberships");
            byte[][] first = mapper.raw(1), last = mapper.raw(packetCount);
            if (!new String(first[1], StandardCharsets.UTF_8).endsWith("{\"ok\":true}")
                    || !new String(last[1], StandardCharsets.UTF_8).endsWith("{\"ok\":true}"))
                throw new AssertionError("Raw packets changed");
            report.put("scenario", "feature-query-" + packetCount); report.put("packets", packetCount);
            report.put("features", featureCount); report.put("queryRounds", rounds);
            report.put("queryCalls", (long) rounds * featureCount); report.put("membershipsReturned", memberships);
            report.put("packetIdSum", ids); report.put("ingestMillis", (accepted - start) / 1_000_000.0);
            report.put("featureQueriesMillis", (queried - accepted) / 1_000_000.0);
            report.put("heapBeforeBytes", heapBefore); report.put("heapAfterBytes", heap());
            report.put("diskBytes", mapper.diskBytes()); report.put("rawSamples", 2);
        }
        return report;
    }
    private static Map<String,Object> browserQueries() throws Exception {
        Map<String,Object> report = new LinkedHashMap<>();
        try (Mapper mapper = new Mapper()) {
            long time = System.currentTimeMillis();
            for (int i = 0; i < 600; i++) mapper.accept(input(time + i, "https://benchmark.invalid/items/123?sample=" + i));
            Object beforeUnrelated = candidates(mapper);
            long start = System.nanoTime();
            for (int i = 0; i < 40; i++) mapper.browser(event(time + i, "https://benchmark.invalid/unrelated?sample=" + i, "unrelated-" + i));
            long unrelatedDone = System.nanoTime();
            Object afterUnrelated = candidates(mapper);
            for (int i = 30; i < 50; i++) mapper.browser(event(time + i, "https://benchmark.invalid/items/123?sample=" + i, "open-" + i));
            long matchedDone = System.nanoTime();
            long mapped = mapper.packetList(null).stream().filter(p -> !p.browserAction.isBlank()).count();
            if (mapped != 20) throw new AssertionError("Expected 20 uniquely attributed packets, got " + mapped);
            for (int i = 600; i < 800; i++) mapper.accept(input(time + i, "https://benchmark.invalid/other/123?sample=" + i));
            long unrelatedPacketsDone = System.nanoTime();
            mapper.accept(input(time + 31, "https://benchmark.invalid/items/123?sample=30"));
            long mappedAfterDuplicate = mapper.packetList(null).stream().filter(p -> !p.browserAction.isBlank()).count();
            if (mappedAfterDuplicate != 19) throw new AssertionError("Ambiguous browser attribution must be revoked");
            Object finalCandidates = candidates(mapper);
            if (beforeUnrelated instanceof Number before && afterUnrelated instanceof Number after
                    && before.longValue() != after.longValue())
                throw new AssertionError("Unrelated URL events should examine no indexed packet candidates");
            report.put("scenario", "browser-correlation"); report.put("initialPackets", 600);
            report.put("unrelatedEvents", 40); report.put("matchingEvents", 20); report.put("unrelatedNewPackets", 200);
            report.put("unrelatedEventsMillis", (unrelatedDone - start) / 1_000_000.0);
            report.put("matchingEventsMillis", (matchedDone - unrelatedDone) / 1_000_000.0);
            report.put("unrelatedNewPacketsMillis", (unrelatedPacketsDone - matchedDone) / 1_000_000.0);
            report.put("mappedBeforeDuplicate", mapped); report.put("mappedAfterDuplicate", mappedAfterDuplicate);
            report.put("indexedCandidatesBeforeUnrelated", beforeUnrelated);
            report.put("indexedCandidatesAfterUnrelated", afterUnrelated);
            report.put("indexedCandidatesTotal", finalCandidates); report.put("retainedPackets", mapper.count());
        }
        return report;
    }
    public static void main(String[] args) throws Exception {
        featureQueries(200, 10, 5); // Excluded JVM/code-path warmup.
        List<Map<String,Object>> scenarios = new ArrayList<>();
        scenarios.add(featureQueries(5000, 100, 30));
        scenarios.add(featureQueries(15000, 100, 30));
        scenarios.add(browserQueries());
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("host", "synthetic inputs to production Mapper; no Burp/browser/network");
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("heapLimitBytes", Runtime.getRuntime().maxMemory()); report.put("scenarios", scenarios);
        Path output = Path.of(System.getProperty("qa.output")); Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, JsonWrite.text(report) + "\n");
        System.out.println("MAPPER_PERFORMANCE_OK scenarios=" + scenarios.size());
    }
}
