package kr.workbench;

import java.util.*;

/** Search-view metadata only. Search words, packet bytes and authentication are never part of this format. */
final class TrafficSearchPresets {
  static final int LIMIT = 24;
  record Preset(String name, TrafficQuery query) {
    Preset { name = validName(name); Objects.requireNonNull(query); metadata(query.account()); }
    @Override public String toString() { return name; }
  }

  static String validName(String value) {
    if (value == null || value.strip().isEmpty() || value.strip().length() > 60)
      throw new IllegalArgumentException("검색 조건 이름은 1~60자로 입력하세요.");
    String name = value.strip(); metadata(name); return name;
  }

  private static void metadata(String value) {
    if (value.chars().anyMatch(Character::isISOControl)
        || value.matches("(?is).*(?:authorization\\s*:|cookie\\s*:|bearer\\s+|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.|[A-Fa-f0-9]{64,}).*"))
      throw new IllegalArgumentException("인증값은 검색 조건 이름이나 계정 이름에 저장할 수 없습니다.");
  }

  static List<Preset> put(List<Preset> before, String name, TrafficQuery query) {
    Preset next = new Preset(name, query);
    List<Preset> result = new ArrayList<>(before);
    for (int i = 0; i < result.size(); i++) if (result.get(i).name().equals(next.name())) {
      result.set(i, next); return List.copyOf(result);
    }
    if (result.size() >= LIMIT) throw new IllegalArgumentException("저장한 검색 조건은 최대 24개입니다.");
    result.add(next); return List.copyOf(result);
  }

  static String encode(List<Preset> presets) {
    if (presets.size() > LIMIT) throw new IllegalArgumentException("저장한 검색 조건은 최대 24개입니다.");
    Set<String> names = new HashSet<>(); List<Map<String,Object>> entries = new ArrayList<>();
    for (Preset preset : presets) {
      Preset checked = new Preset(preset.name(), preset.query());
      if (!names.add(checked.name())) throw new IllegalArgumentException("중복된 검색 조건 이름입니다.");
      entries.add(Map.of("name", checked.name(), "query", checked.query().json()));
    }
    return MetadataExport.json(Map.of("schema", 1, "presets", entries));
  }

  static List<Preset> decode(String json) {
    if (json == null || json.isBlank()) return List.of();
    Object parsed = MinerJson.parse(json, 96 * 1024, 2048);
    if (!(parsed instanceof Map<?,?> root) || !root.keySet().equals(Set.of("schema", "presets"))
        || !(root.get("schema") instanceof Number n) || n.doubleValue() != 1
        || !(root.get("presets") instanceof List<?> items) || items.size() > LIMIT)
      throw new IllegalArgumentException("지원하는 검색 조건 목록 형식이 아닙니다.");
    List<Preset> result = new ArrayList<>(); Set<String> names = new HashSet<>();
    for (Object item : items) {
      if (!(item instanceof Map<?,?> entry) || !entry.keySet().equals(Set.of("name", "query"))
          || !(entry.get("name") instanceof String name))
        throw new IllegalArgumentException("검색 조건 항목을 확인하세요.");
      Preset preset = new Preset(name, TrafficQuery.readStrict(MetadataExport.json(entry.get("query"))));
      if (!names.add(preset.name())) throw new IllegalArgumentException("중복된 검색 조건 이름입니다.");
      result.add(preset);
    }
    return List.copyOf(result);
  }

  static String capture(TrafficQuery query) {
    metadata(query.account());
    return MetadataExport.json(Map.of("schema", 1, "query", query.json()));
  }

  static TrafficQuery restore(String json) {
    Object parsed = MinerJson.parse(json, 8192, 96);
    if (!(parsed instanceof Map<?,?> root) || !root.keySet().equals(Set.of("schema", "query"))
        || !(root.get("schema") instanceof Number n) || n.doubleValue() != 1)
      throw new IllegalArgumentException("지원하는 검색 조건 복원 형식이 아닙니다.");
    TrafficQuery query = TrafficQuery.readStrict(MetadataExport.json(root.get("query")));
    metadata(query.account()); return query;
  }

  static TrafficQuery clear(TrafficQuery q, String key) {
    if (!Set.of("method","status","range","sensitive","regex","group","normalize","important","memo","account","minutes").contains(key))
      throw new IllegalArgumentException("해제할 검색 조건이 없습니다.");
    TrafficQuery d = TrafficQuery.defaults();
    return new TrafficQuery(key.equals("method")?d.method():q.method(), key.equals("status")?d.status():q.status(),
        key.equals("range")?d.range():q.range(), key.equals("sensitive")?d.sensitive():q.sensitive(),
        key.equals("regex")?d.regex():q.regex(), key.equals("group")?d.group():q.group(),
        key.equals("normalize")?d.normalize():q.normalize(), key.equals("important")?d.important():q.important(),
        key.equals("memo")?d.memo():q.memo(), key.equals("account")?d.account():q.account(),
        key.equals("minutes")?d.minutes():q.minutes());
  }

  static Map<String,String> conditions(TrafficQuery q) {
    Map<String,String> result = new LinkedHashMap<>();
    if (!q.method().equals("전체")) result.put("method", q.method());
    if (!q.status().equals("전체")) result.put("status", q.status());
    if (q.range() > 0) result.put("range", q.range() == 1 ? "요청 포함 검색" : "전체 패킷 검색");
    if (!q.account().isBlank()) result.put("account", "계정: " + q.account());
    if (q.minutes() > 0) result.put("minutes", "최근 " + q.minutes() + "분");
    if (q.important()) result.put("important", "중요 표시만");
    if (q.group()) result.put("group", "비슷한 요청 묶기");
    if (!q.normalize()) result.put("normalize", "숫자·UUID 개별 표시");
    if (!q.memo()) result.put("memo", "메모 열 숨김");
    if (q.sensitive()) result.put("sensitive", "대소문자 구분");
    if (q.regex()) result.put("regex", "정규식");
    return Collections.unmodifiableMap(result);
  }
}
