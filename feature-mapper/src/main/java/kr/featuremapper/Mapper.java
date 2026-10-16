package kr.featuremapper;

import java.io.*;
import java.net.*;
import java.util.*;

/** Automatic feature assignment plus per-execution, per-session evidence. No network sends. */
public final class Mapper implements AutoCloseable {
  static final long LINK_WINDOW = 120_000, RUN_GAP = 30_000, BROWSER_WINDOW = 2500;

  record Input(
      long time,
      String tool,
      String method,
      String url,
      int status,
      byte[] request,
      byte[] response,
      byte[] requestBody,
      byte[] responseBody,
      Map<String, String> requestHeaders,
      Map<String, String> responseHeaders) {}

  static final class Feature {
    final String id, key, origin, entity;
    String name, state = "미점검", note = "";
    boolean renamed;

    Feature(String id, String key, String origin, String entity, String name) {
      this.id = id;
      this.key = key;
      this.origin = origin;
      this.entity = entity;
      this.name = name;
    }
  }

  static final class Packet {
    final long id, time;
    final String tool, method, url, origin, signature, session, entity, operation, bodyHash, page;
    final int status;
    final boolean common, partial;
    final PacketStore.Ref raw;
    final Map<String, String> links = new LinkedHashMap<>();
    final List<Long> parents = new ArrayList<>();
    final List<String> reasons = new ArrayList<>();
    String primary, run, confidence = "중간", browserAction = "", browserTab = "", browserFrame = "";

    Packet(
        long id,
        Input i,
        String signature,
        String session,
        String entity,
        String operation,
        boolean common,
        PacketStore.Ref raw) {
      this.id = id;
      time = i.time;
      tool = i.tool;
      method = i.method;
      url = i.url;
      origin = Signals.origin(url);
      this.signature = signature;
      this.session = session;
      this.entity = entity;
      this.operation = operation;
      status = i.status;
      this.common = common;
      this.raw = raw;
      bodyHash = Signals.digest(i.requestBody);
      page = Signals.safePage(i.requestHeaders.getOrDefault("referer", ""));
      partial =
          i.requestBody.length > Signals.ANALYSIS_BYTES
              || i.responseBody.length > Signals.ANALYSIS_BYTES;
    }

    Packet(Map<String, Object> m) {
      id = num(m, "id");
      time = num(m, "time");
      tool = str(m, "tool");
      method = str(m, "method");
      url = str(m, "url");
      origin = Signals.origin(url);
      signature = str(m, "signature");
      session = str(m, "session");
      entity = str(m, "entity");
      operation = str(m, "operation");
      status = (int) num(m, "status");
      common = Boolean.TRUE.equals(m.get("common"));
      partial = Boolean.TRUE.equals(m.get("partial"));
      bodyHash = str(m, "bodyHash");
      page = str(m, "page");
      raw =
          new PacketStore.Ref(
              num(m, "offset"), (int) num(m, "requestBytes"), (int) num(m, "responseBytes"));
      primary = str(m, "primary");
      run = str(m, "run");
      confidence = str(m, "confidence");
      browserAction = str(m, "browserAction");
      browserTab = str(m, "browserTab");
      browserFrame = str(m, "browserFrame");
      ((Map<?, ?>) m.get("links"))
          .forEach((k, v) -> links.put(String.valueOf(k), String.valueOf(v)));
      for (Object v : (List<?>) m.get("parents")) parents.add(((Number) v).longValue());
      for (Object v : (List<?>) m.get("reasons")) reasons.add(String.valueOf(v));
    }

    Map<String, Object> json() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", id);
      m.put("time", time);
      m.put("tool", tool);
      m.put("method", method);
      m.put("url", url);
      m.put("signature", signature);
      m.put("session", session);
      m.put("entity", entity);
      m.put("operation", operation);
      m.put("status", status);
      m.put("common", common);
      m.put("partial", partial);
      m.put("bodyHash", bodyHash);
      m.put("page", page);
      m.put("offset", raw.offset());
      m.put("requestBytes", raw.requestBytes());
      m.put("responseBytes", raw.responseBytes());
      m.put("primary", primary);
      m.put("run", run);
      m.put("confidence", confidence);
      m.put("browserAction", browserAction);
      m.put("browserTab", browserTab);
      m.put("browserFrame", browserFrame);
      m.put("links", new LinkedHashMap<>(links));
      m.put("parents", List.copyOf(parents));
      m.put("reasons", List.copyOf(reasons));
      return m;
    }
  }

  record Producer(long packet, String field, long time, String entity, String page) {}

  private final PacketStore store;
  private String salt = UUID.randomUUID().toString();
  final LinkedHashMap<String, Feature> features = new LinkedHashMap<>();
  final LinkedHashMap<Long, Packet> packets = new LinkedHashMap<>();
  final Map<String, String> corrections = new LinkedHashMap<>();
  final Map<String, String> apiNames = new LinkedHashMap<>();
  final Map<String, Set<String>> knownIds = new HashMap<>();
  private final Map<String, List<Producer>> producers = new HashMap<>();
  private final Map<String, Packet> recentRuns = new HashMap<>();
  private final NavigableMap<Long, LinkedHashSet<Packet>> recentPackets = new TreeMap<>();
  private final Map<String, NavigableMap<Long, Packet>> featurePackets = new HashMap<>();
  private final TimeIndex<Packet> packetCandidates = new TimeIndex<>();
  private final TimeIndex<BrowserEvent> eventCandidates = new TimeIndex<>();
  private final LinkedHashMap<EventKey, BrowserEvent> browserEvents = new LinkedHashMap<>();
  private final PriorityQueue<BrowserEvent> eventExpiry =
      new PriorityQueue<>(Comparator.comparingLong(e -> num(e.values, "time")));
  private long browserCandidatesExamined;

  record RequestKey(String method, String url, String hash) {}

  record EventKey(String tab, String frame, String action, String url, long time) {}

  record BrowserEvent(EventKey key, Map<String, Object> values) {}

  /** Two request keys allow exact-body matching and explicitly hashless browser events. */
  private static final class TimeIndex<T> {
    final Map<RequestKey, NavigableMap<Long, LinkedHashSet<T>>> values = new HashMap<>();

    void add(RequestKey key, long time, T value) {
      values
          .computeIfAbsent(key, k -> new TreeMap<>())
          .computeIfAbsent(time, t -> new LinkedHashSet<>())
          .add(value);
    }

    void remove(RequestKey key, long time, T value) {
      var times = values.get(key);
      if (times == null) return;
      var at = times.get(time);
      if (at != null) {
        at.remove(value);
        if (at.isEmpty()) times.remove(time);
      }
      if (times.isEmpty()) values.remove(key);
    }

    List<T> near(RequestKey key, long time) {
      var times = values.get(key);
      if (times == null) return List.of();
      List<T> out = new ArrayList<>();
      for (var at :
          times.subMap(time - BROWSER_WINDOW, false, time + BROWSER_WINDOW, false).values())
        out.addAll(at);
      return out;
    }
  }

  private long sequence;

  public Mapper() throws IOException {
    store = new PacketStore();
  }

  synchronized Packet accept(Input i) throws IOException {
    String origin = Signals.origin(i.url), path = URI.create(i.url).getRawPath();
    if (path == null || path.isEmpty()) path = "/";
    Map<String, String> fields =
        new LinkedHashMap<>(Signals.fields(i.requestBody, i.requestHeaders));
    String query = URI.create(i.url).getRawQuery();
    if (query != null) Signals.form(query).forEach(fields::putIfAbsent);
    String template = Signals.template(path, knownIds.getOrDefault(origin, Set.of()));
    String operation = Signals.operation(i.method, template, fields);
    if (template.endsWith("graphql") && fields.getOrDefault("operationName", "").isBlank()) {
      String gql = fields.getOrDefault("query", "");
      java.util.regex.Matcher match =
          java.util.regex.Pattern.compile(
                  "(?:query|mutation|subscription)\\s+([A-Za-z_][A-Za-z_0-9]*)|\\{\\s*([A-Za-z_][A-Za-z_0-9]*)")
              .matcher(gql);
      if (match.find()) operation = match.group(1) != null ? match.group(1) : match.group(2);
    }
    if (template.endsWith("graphql") && Signals.json(i.requestBody) instanceof List<?> batch) {
      List<String> ops = new ArrayList<>();
      for (Object entry : batch)
        if (entry instanceof Map<?, ?> m)
          ops.add(String.valueOf(m.getOrDefault("operationName", null)));
      Collections.sort(ops);
      operation = "batch:" + String.join("+", ops);
    }
    String entity = Signals.entity(template);
    if (template.endsWith("graphql")) entity = "GraphQL";
    String signature = i.method + " " + template + " | " + operation;
    String session = Signals.session(origin, i.requestHeaders, salt);
    Packet p =
        new Packet(
            ++sequence,
            i,
            signature,
            session,
            entity,
            operation,
            Signals.common(path, i.responseHeaders),
            store.append(i.request, i.response));
    String key = origin + "|" + (p.common ? "공통 요청" : signature);
    Feature f =
        feature(
            key,
            origin,
            p.common ? "공통 요청" : entity,
            p.common
                ? "공통 요청"
                : apiNames.getOrDefault(
                    origin + "|" + i.method + " " + template,
                    entity + " / " + operation + " · " + template));
    p.primary = f.id;
    String correction = corrections.get(correctionKey(p));
    if (correction != null && features.containsKey(correction)) p.primary = correction;
    String runKey = origin + "|" + session + "|" + p.page + "|" + p.primary;
    Packet prev = recentRuns.get(runKey);
    p.run =
        prev != null && i.time >= prev.time && i.time - prev.time < RUN_GAP
            ? prev.run
            : "실행-" + p.id;
    link(p, p.primary, p.run);
    p.reasons.add(p.common ? "정적 자원·공통 경로" : "메서드·URL 구조·동작: " + signature);
    if (correction != null) {
      p.confidence = "높음";
      p.reasons.add("프로젝트에 저장한 분류 수정 규칙");
    }
    if (p.partial) p.reasons.add("원본 전체 보관 · 512KiB를 넘는 본문은 구조 분석 생략");
    packets.put(p.id, p);
    recentPackets.computeIfAbsent(p.time, k -> new LinkedHashSet<>()).add(p);
    recentRuns.put(runKey, p);
    if (p.tool.equalsIgnoreCase("PROXY") && !p.common) {
      packetCandidates.add(requestKey(p, false), p.time, p);
      packetCandidates.add(requestKey(p, true), p.time, p);
    }
    if (!p.common) connect(p, fields, path);
    Object responseJson = Signals.json(i.responseBody);
    if (responseJson instanceof Map<?, ?> responseMap
        && responseMap.get("paths") instanceof Map<?, ?> paths) discoverApiNames(origin, paths);
    if (i.status >= 200
        && i.status < 300
        && !p.common
        && Set.of("POST", "PUT", "PATCH").contains(i.method)) {
      for (var e : Signals.leaves(responseJson).entrySet())
        if (Signals.identifier(e.getKey(), e.getValue())) {
          knownIds.computeIfAbsent(origin, k -> new LinkedHashSet<>()).add(e.getValue());
          String index = index(p, e.getValue());
          producers
              .computeIfAbsent(index, k -> new ArrayList<>())
              .add(new Producer(p.id, e.getKey(), p.time, p.entity, p.page));
        }
    }
    Set<BrowserEvent> affected = prune(i.time);
    affected.addAll(eventsFor(p));
    applyBrowserEvents(affected);
    return p;
  }

  private Feature feature(String key, String origin, String entity, String name) {
    String id = Signals.digest(key).substring(0, 24);
    return features.computeIfAbsent(
        id, k -> new Feature(k, key, origin, entity, Signals.clean(name, 240)));
  }

  private String index(Packet p, String value) {
    return p.origin + "|" + p.session + "|" + Signals.digest(value);
  }

  private void connect(Packet p, Map<String, String> fields, String path) {
    Map<String, String> wanted = new LinkedHashMap<>();
    for (var e : fields.entrySet())
      if (Signals.identifier(e.getKey(), e.getValue())) wanted.put(e.getKey(), e.getValue());
    for (String segment : path.split("/"))
      if (!segment.isEmpty()) wanted.put("$path:" + segment, segment);
    Set<Long> matched = new LinkedHashSet<>();
    for (var e : wanted.entrySet()) {
      List<Producer> candidates =
          producers.getOrDefault(index(p, e.getValue()), List.of()).stream()
              .filter(v -> p.time >= v.time && p.time - v.time < LINK_WINDOW)
              .filter(v -> p.page.isBlank() || v.page.isBlank() || p.page.equals(v.page))
              .filter(
                  v ->
                      e.getKey().startsWith("$path:")
                          ? v.entity.equals(p.entity)
                          : relatedField(e.getKey(), v))
              .toList();
      Set<Long> unique = new HashSet<>();
      candidates.forEach(v -> unique.add(v.packet));
      if (unique.size() == 1) matched.add(unique.iterator().next());
      else if (unique.size() > 1) p.reasons.add("동일 ID의 생산 요청이 여러 개라 연결 보류");
    }
    for (long parentId : matched) {
      Packet parent = packets.get(parentId);
      p.parents.add(parentId);
      p.reasons.add("응답 ID → 요청 값 연결: 패킷 #" + parentId);
      p.confidence = "높음";
      attachAncestors(parent, p.primary, p.run, 0);
      if (parent.entity.equals(p.entity))
        for (var membership : parent.links.entrySet())
          link(p, membership.getKey(), membership.getValue());
    }
  }

  private static boolean relatedField(String consumer, Producer v) {
    String a = consumer.substring(consumer.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT),
        b = v.field.substring(v.field.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    if (a.equals(b) && !a.equals("id")) return true;
    String family = a.replaceAll("(uuid|id|key)$", "");
    String expected =
        switch (v.entity) {
          case "파일" -> "file|attachment";
          case "게시판" -> "post|article|board";
          case "회원" -> "user|member";
          case "주문" -> "order";
          default -> java.util.regex.Pattern.quote(v.entity.toLowerCase(Locale.ROOT));
        };
    return b.equals("id") && family.matches(expected);
  }

  private void attachAncestors(Packet p, String feature, String run, int depth) {
    if (depth > 8) return;
    link(p, feature, run);
    for (long parent : p.parents)
      if (packets.containsKey(parent))
        attachAncestors(packets.get(parent), feature, run, depth + 1);
  }

  private void discoverApiNames(String origin, Map<?, ?> paths) {
    for (var entry : paths.entrySet()) {
      if (!(entry.getValue() instanceof Map<?, ?> methods)) continue;
      String template = String.valueOf(entry.getKey()).replaceAll("\\{[^}]+}", "{id}");
      for (var method : methods.entrySet())
        if (method.getValue() instanceof Map<?, ?> op) {
          Object name = op.get("summary");
          if (name == null) name = op.get("operationId");
          if (name != null)
            apiNames.put(
                origin
                    + "|"
                    + String.valueOf(method.getKey()).toUpperCase(Locale.ROOT)
                    + " "
                    + template,
                Signals.clean(String.valueOf(name), 200));
        }
    }
  }

  private void link(Packet packet, String feature, String run) {
    packet.links.putIfAbsent(feature, run);
    featurePackets.computeIfAbsent(feature, k -> new TreeMap<>()).put(packet.id, packet);
  }

  private void unlink(Packet packet, String feature) {
    packet.links.remove(feature);
    var members = featurePackets.get(feature);
    if (members != null) {
      members.remove(packet.id);
      if (members.isEmpty()) featurePackets.remove(feature);
    }
  }

  private static RequestKey requestKey(Packet p, boolean anyBody) {
    return new RequestKey(p.method, p.url, anyBody ? "" : p.bodyHash);
  }

  private static RequestKey requestKey(BrowserEvent e) {
    return new RequestKey(str(e.values, "method"), str(e.values, "url"), str(e.values, "bodyHash"));
  }

  private List<Packet> packetsFor(BrowserEvent event) {
    List<Packet> result = packetCandidates.near(requestKey(event), num(event.values, "time"));
    browserCandidatesExamined += result.size();
    return result;
  }

  private List<BrowserEvent> eventsFor(Packet packet) {
    if (!packet.tool.equalsIgnoreCase("PROXY") || packet.common) return List.of();
    List<BrowserEvent> out =
        new ArrayList<>(eventCandidates.near(requestKey(packet, false), packet.time));
    out.addAll(eventCandidates.near(requestKey(packet, true), packet.time));
    browserCandidatesExamined += out.size();
    return out;
  }

  synchronized long browserCandidatesExamined() {
    return browserCandidatesExamined;
  }

  synchronized void browser(Map<String, Object> event) {
    if (!"network".equals(str(event, "type")))
      throw new IllegalArgumentException("network 이벤트만 지원합니다.");
    Signals.origin(str(event, "url"));
    if (str(event, "actionId").isBlank() || str(event, "label").isBlank()) return;
    long now = System.currentTimeMillis();
    if (num(event, "time") < now - LINK_WINDOW || num(event, "time") > now + LINK_WINDOW)
      throw new IllegalArgumentException("브라우저 이벤트 시각이 만료됐습니다.");
    Map<String, Object> clean = new LinkedHashMap<>();
    for (String key :
        List.of(
            "type",
            "url",
            "method",
            "bodyHash",
            "actionId",
            "label",
            "page",
            "title",
            "tab",
            "frame"))
      clean.put(key, Signals.clean(str(event, key), key.equals("url") ? 8192 : 240));
    clean.put("time", num(event, "time"));
    EventKey key =
        new EventKey(
            str(clean, "tab"),
            str(clean, "frame"),
            str(clean, "actionId"),
            str(clean, "url"),
            num(clean, "time"));
    Set<BrowserEvent> affected = prune(now);
    BrowserEvent old = browserEvents.get(key);
    if (old != null) removeEvent(old, affected);
    BrowserEvent added = new BrowserEvent(key, Map.copyOf(clean));
    browserEvents.put(key, added);
    eventCandidates.add(requestKey(added), key.time, added);
    eventExpiry.add(added);
    while (browserEvents.size() > 2000)
      removeEvent(browserEvents.values().iterator().next(), affected);
    // Bound stale heap entries from deduplicated/replaced events too.
    if (eventExpiry.size() > 4000) {
      eventExpiry.clear();
      eventExpiry.addAll(browserEvents.values());
    }
    affected.add(added);
    applyBrowserEvents(affected);
  }

  private void applyBrowserEvents(Collection<BrowserEvent> events) {
    for (BrowserEvent event : events) {
      if (browserEvents.get(event.key) != event) continue;
      Map<String, Object> e = event.values;
      List<Packet> candidates = packetsFor(event);
      if (candidates.size() != 1) {
        for (Packet candidate : candidates) unmapAmbiguousBrowser(candidate);
        continue;
      }
      Packet p = candidates.get(0);
      long alternatives =
          eventsFor(p).stream()
              .map(
                  other ->
                      new EventAction(
                          str(other.values, "tab"),
                          str(other.values, "frame"),
                          str(other.values, "actionId")))
              .distinct()
              .count();
      if (alternatives != 1) {
        unmapAmbiguousBrowser(p);
        continue;
      }
      if (!p.browserAction.isEmpty()) continue;
      String page = Signals.safePage(str(e, "page"));
      if (page.isBlank() || !Signals.origin(page).equals(p.origin)) continue;
      String label = Signals.clean(str(e, "label"), 80);
      String route =
          Signals.template(
              URI.create(page).getRawPath(), knownIds.getOrDefault(p.origin, Set.of()));
      Feature f =
          feature(
              p.origin + "|browser|" + route + "|" + label,
              p.origin,
              "화면 기능",
              label + " · " + route);
      p.browserAction = str(e, "actionId");
      p.browserTab = str(e, "tab");
      p.browserFrame = str(e, "frame");
      if (!corrections.containsKey(correctionKey(p))) p.primary = f.id;
      p.run =
          "브라우저-"
              + Signals.digest(
                      p.origin + p.session + p.browserTab + p.browserFrame + p.browserAction)
                  .substring(0, 16);
      link(p, f.id, p.run);
      p.links.put(f.id, p.run);
      p.reasons.add(
          "브라우저 화면·동작: " + label + " (탭 " + p.browserTab + " / 프레임 " + p.browserFrame + ")");
      p.confidence = p.parents.isEmpty() ? "중간" : "높음";
      // Temporal browser attribution is context, never proof of JS causality.
      attachAncestors(p, f.id, p.run, 0);
    }
  }

  record EventAction(String tab, String frame, String action) {}

  private void unmapAmbiguousBrowser(Packet p) {
    if (p.browserAction.isBlank()) return;
    String previousRun = p.run;
    Set<Packet> affected = new LinkedHashSet<>();
    affected.add(p);
    // Retract the run consistently, including prerequisites; explicit user links survive.
    for (String feature : new ArrayList<>(p.links.keySet())) {
      if (!features.get(feature).key.contains("|browser|")) continue;
      var members = featurePackets.get(feature);
      if (members == null) continue;
      for (Packet item : new ArrayList<>(members.values())) {
        if (!previousRun.equals(item.links.get(feature))) continue;
        if (previousRun.equals(item.run) && !item.browserAction.isBlank()) affected.add(item);
        if (!feature.equals(corrections.get(correctionKey(item)))) unlink(item, feature);
      }
    }
    for (Packet item : affected) {
      String intrinsic =
          Signals.digest(item.origin + "|" + (item.common ? "공통 요청" : item.signature))
              .substring(0, 24);
      String correction = corrections.get(correctionKey(item));
      item.primary = correction == null ? intrinsic : correction;
      item.run = item.links.getOrDefault(item.primary, "실행-" + item.id);
      if (correction != null) link(item, correction, item.run);
      item.browserAction = "";
      item.browserTab = "";
      item.browserFrame = "";
      item.confidence = correction == null ? "낮음" : "높음";
      item.reasons.removeIf(x -> x.startsWith("브라우저 화면·동작:"));
      item.reasons.add("동시 동일 요청·브라우저 동작 중복으로 화면 매핑 보류");
    }
  }

  private void removeEvent(BrowserEvent event, Set<BrowserEvent> affected) {
    browserEvents.remove(event.key);
    eventCandidates.remove(requestKey(event), event.key.time, event);
    for (Packet packet : packetsFor(event)) affected.addAll(eventsFor(packet));
  }

  private Set<BrowserEvent> prune(long time) {
    Set<BrowserEvent> affected = new LinkedHashSet<>();
    long cutoff = time - LINK_WINDOW;
    while (!recentPackets.isEmpty() && recentPackets.firstKey() < cutoff) {
      for (Packet packet : recentPackets.pollFirstEntry().getValue()) {
        affected.addAll(eventsFor(packet));
        packetCandidates.remove(requestKey(packet, false), packet.time, packet);
        packetCandidates.remove(requestKey(packet, true), packet.time, packet);
      }
    }
    while (!eventExpiry.isEmpty() && eventExpiry.peek().key.time < cutoff) {
      BrowserEvent event = eventExpiry.remove();
      if (browserEvents.get(event.key) == event) removeEvent(event, affected);
    }
    producers.values().forEach(a -> a.removeIf(p -> p.time < cutoff));
    producers.entrySet().removeIf(e -> e.getValue().isEmpty());
    recentRuns.entrySet().removeIf(e -> e.getValue().time < cutoff);
    return affected;
  }

  private static String correctionKey(Packet p) {
    return p.origin + "|" + p.signature + "|" + p.page;
  }

  synchronized void move(long packetId, String featureId) {
    if (!features.containsKey(featureId)) throw new IllegalArgumentException("기능이 없습니다.");
    Packet p = packets.get(packetId);
    if (p == null) throw new IllegalArgumentException("패킷이 없습니다.");
    if (!features.get(featureId).origin.equals(p.origin))
      throw new IllegalArgumentException("다른 사이트의 기능으로 이동할 수 없습니다.");
    p.primary = featureId;
    link(p, featureId, p.run);
    p.links.put(featureId, p.run);
    p.confidence = "높음";
    p.reasons.add("사용자가 분류 수정");
    corrections.put(correctionKey(p), featureId);
  }

  synchronized void edit(String id, String name, String state, String note) {
    Feature f = features.get(id);
    if (f == null) throw new IllegalArgumentException("기능 없음");
    f.name = Signals.clean(name, 240);
    f.state = state;
    f.note = note;
    f.renamed = true;
  }

  synchronized List<Feature> featureList() {
    return List.copyOf(features.values());
  }

  synchronized List<Packet> packetList(String feature) {
    if (feature == null) return List.copyOf(packets.values());
    var members = featurePackets.get(feature);
    return members == null ? List.of() : List.copyOf(members.values());
  }

  record FeatureSummary(
      String id, String origin, String entity, String name, String state, int packets) {}

  synchronized List<FeatureSummary> featureSummaries() {
    List<FeatureSummary> out = new ArrayList<>();
    for (Feature f : features.values())
      out.add(
          new FeatureSummary(
              f.id,
              f.origin,
              f.entity,
              f.name,
              f.state,
              featurePackets.getOrDefault(f.id, Collections.emptyNavigableMap()).size()));
    return List.copyOf(out);
  }

  record PacketRow(
      long id,
      String method,
      String url,
      int status,
      String session,
      String run,
      String confidence) {}

  synchronized List<PacketRow> packetRows(String feature) {
    List<PacketRow> rows = new ArrayList<>();
    for (Packet p : packetList(feature))
      rows.add(
          new PacketRow(
              p.id,
              p.method,
              p.url,
              p.status,
              p.session,
              feature == null ? p.run : p.links.get(feature),
              p.confidence));
    return rows;
  }

  synchronized Packet packet(long id) {
    return packets.get(id);
  }

  synchronized byte[][] raw(long id) throws IOException {
    Packet p = packets.get(id);
    if (p == null) throw new IOException("패킷 없음");
    return store.read(p.raw);
  }

  record Preview(
      byte[] request, byte[] response, int requestBytes, int responseBytes, String explanation) {}

  synchronized Preview preview(long id) throws IOException {
    Packet p = packets.get(id);
    if (p == null) throw new IOException("패킷 없음");
    byte[][] bytes = store.read(p.raw, 128 * 1024);
    return new Preview(
        bytes[0],
        bytes[1],
        p.raw.requestBytes(),
        p.raw.responseBytes(),
        "매핑: "
            + p.confidence
            + "\n동작: "
            + p.operation
            + "\n"
            + String.join("\n", p.reasons)
            + "\n\n매핑은 기능 추정이며 취약점 판정이 아닙니다.");
  }

  synchronized long count() {
    return packets.size();
  }

  synchronized long diskBytes() throws IOException {
    return store.bytes();
  }

  PacketStore store() {
    return store;
  }

  synchronized Map<String, Object> metadata() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("format", "feature-mapper-kr");
    m.put("version", 1);
    m.put("salt", salt);
    m.put("sequence", sequence);
    List<Object> fs = new ArrayList<>();
    for (Feature f : features.values())
      fs.add(
          Map.of(
              "id", f.id, "key", f.key, "origin", f.origin, "entity", f.entity, "name", f.name,
              "state", f.state, "note", f.note, "renamed", f.renamed));
    m.put("features", fs);
    m.put("packets", packets.values().stream().map(Packet::json).toList());
    m.put("corrections", new LinkedHashMap<>(corrections));
    m.put("apiNames", new LinkedHashMap<>(apiNames));
    Map<String, Object> ids = new LinkedHashMap<>();
    knownIds.forEach((key, values) -> ids.put(key, List.copyOf(values)));
    m.put("knownIds", ids);
    return m;
  }

  record SaveSnapshot(Map<String, Object> metadata, PacketStore.Snapshot raw)
      implements AutoCloseable {
    public void close() throws IOException {
      raw.close();
    }
  }

  synchronized SaveSnapshot snapshot() throws IOException {
    // Metadata is detached while raw bytes are pinned at the same append boundary.
    Map<String, Object> metadata = metadata();
    return new SaveSnapshot(metadata, store.snapshot());
  }

  @SuppressWarnings("unchecked")
  synchronized void restoreMetadata(Map<String, Object> m) throws IOException {
    if (!"feature-mapper-kr".equals(m.get("format")) || num(m, "version") != 1)
      throw new IOException("지원하지 않는 프로젝트 형식입니다.");
    salt = str(m, "salt");
    sequence = num(m, "sequence");
    for (Object x : (List<?>) m.get("features")) {
      Map<String, Object> a = (Map<String, Object>) x;
      Feature f =
          new Feature(
              str(a, "id"), str(a, "key"), str(a, "origin"), str(a, "entity"), str(a, "name"));
      f.state = str(a, "state");
      f.note = str(a, "note");
      f.renamed = Boolean.TRUE.equals(a.get("renamed"));
      if (features.put(f.id, f) != null) throw new IOException("중복 기능 ID");
    }
    for (Object x : (List<?>) m.get("packets")) {
      Packet p = new Packet((Map<String, Object>) x);
      store.validate(p.raw);
      if (p.id < 1
          || p.id > sequence
          || !features.containsKey(p.primary)
          || !features.keySet().containsAll(p.links.keySet())
          || packets.put(p.id, p) != null) throw new IOException("잘못된 패킷 참조");
    }
    for (Packet p : packets.values())
      for (String feature : p.links.keySet())
        featurePackets.computeIfAbsent(feature, k -> new TreeMap<>()).put(p.id, p);
    for (Packet p : packets.values())
      if (!packets.keySet().containsAll(p.parents) || p.parents.stream().anyMatch(id -> id >= p.id))
        throw new IOException("잘못된 패킷 연결");
    ((Map<?, ?>) m.get("corrections"))
        .forEach((k, v) -> corrections.put(String.valueOf(k), String.valueOf(v)));
    ((Map<?, ?>) m.get("apiNames"))
        .forEach((k, v) -> apiNames.put(String.valueOf(k), String.valueOf(v)));
    ((Map<?, ?>) m.get("knownIds"))
        .forEach(
            (k, v) -> {
              Set<String> ids = new LinkedHashSet<>();
              for (Object id : (List<?>) v) ids.add(String.valueOf(id));
              knownIds.put(String.valueOf(k), ids);
            });
  }

  static String str(Map<?, ?> m, String k) {
    return m.get(k) == null ? "" : String.valueOf(m.get(k));
  }

  static long num(Map<?, ?> m, String k) {
    Object v = m.get(k);
    if (!(v instanceof Number n)) throw new IllegalArgumentException("숫자 필드 없음: " + k);
    return n.longValue();
  }

  public synchronized void close() throws IOException {
    store.close();
  }
}
