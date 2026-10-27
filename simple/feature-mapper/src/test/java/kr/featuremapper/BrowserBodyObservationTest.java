package kr.featuremapper;

import java.util.Map;

/** Hash observations still require byte-exact agreement with independently captured traffic. */
public final class BrowserBodyObservationTest {
    static void run() throws Exception {
        long now = System.currentTimeMillis();
        String url = "https://lab.test/api/profile";
        try (Mapper mapper = new Mapper()) {
            var first = mapper.accept(MapperTest.input(now, "PATCH", url, "{\"이름\":\"하나\"}", "{}", "A"));
            var second = mapper.accept(MapperTest.input(now + 10, "PATCH", url, "{\"이름\":\"둘\"}", "{}", "A"));
            Map<String, Object> event = MapperTest.event(now, "PATCH", url, first.bodyHash, "first", "1", "첫 수정");
            event.put("bodyHashSource", "page-observation");
            mapper.browser(event);
            MapperTest.check(first.browserAction.equals("first") && second.browserAction.isEmpty(),
                "page-observed UTF-8 hash maps only the matching body at an identical URL");
            MapperTest.check(first.reasons.stream().anyMatch(x -> x.contains("페이지 관측값") && x.contains("증명이 아님")),
                "page observation provenance is distinct from authenticated causality");
            Map<String, Object> next = MapperTest.event(now + 10, "PATCH", url, second.bodyHash, "second", "1", "둘째 수정");
            next.put("bodyHashSource", "page-observation");
            mapper.browser(next);
            MapperTest.check(second.browserAction.equals("second") && !first.run.equals(second.run),
                "distinct PATCH bodies retain distinct browser actions and runs");
            var ambiguous = MapperTest.event(now, "PATCH", url, first.bodyHash, "competing", "1", "중복 수정");
            ambiguous.put("bodyHashSource", "page-observation");
            mapper.browser(ambiguous);
            MapperTest.check(first.browserAction.isEmpty()
                && first.reasons.stream().noneMatch(x -> x.startsWith("본문 해시 출처:")),
                "ambiguity retracts page-observation provenance along with browser attribution");
        }
        try (Mapper mapper = new Mapper()) {
            var packet = mapper.accept(MapperTest.input(now, "POST", url, "{\"이름\":\"원본\"}", "{}", "A"));
            var event = MapperTest.event(now, "POST", url, Signals.digest("forged"), "wrong", "1", "잘못된 관측");
            event.put("bodyHashSource", "page-observation");
            mapper.browser(event);
            MapperTest.check(packet.browserAction.isEmpty(), "page metadata cannot bypass captured-body hash comparison");
            event.put("bodyHashSource", "authenticated");
            try { mapper.browser(event); throw new AssertionError("unknown hash provenance accepted"); }
            catch (IllegalArgumentException expected) { MapperTest.check(true, "unknown hash provenance is rejected"); }
        }
    }
    public static void main(String[] args) throws Exception {
        run();
        System.out.println("BrowserBodyObservationTest: " + MapperTest.checks + " checks passed");
    }
}
