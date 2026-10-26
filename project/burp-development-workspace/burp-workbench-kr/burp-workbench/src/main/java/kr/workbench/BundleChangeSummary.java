package kr.workbench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Readable review of immutable setup snapshots; no account bindings or traffic are read. */
final class BundleChangeSummary {
  record Changes(List<String> added, List<String> changed, List<String> removed) {
    Changes { added = List.copyOf(added); changed = List.copyOf(changed); removed = List.copyOf(removed); }
    int total() { return added.size() + changed.size() + removed.size(); }
  }

  static Changes changes(Map<String, ?> before, Map<String, ?> after) {
    List<String> added = new ArrayList<>(), changed = new ArrayList<>(), removed = new ArrayList<>();
    for (String key : new TreeSet<>(after.keySet())) {
      if (!before.containsKey(key)) added.add(key);
      else if (!Objects.equals(before.get(key), after.get(key))) changed.add(key);
    }
    for (String key : new TreeSet<>(before.keySet())) if (!after.containsKey(key)) removed.add(key);
    return new Changes(added, changed, removed);
  }

  static String describe(AuthSetupBundles.Bundle bundle) {
    var settings = bundle.settings();
    return heading(bundle) + "\n포함된 설정\n"
        + "· 경로 정책 " + AuthPolicy.parse(settings.policyRules(), settings.fallback()).rules.size() + "개\n"
        + "· 계정별 권한표 " + BountyMatrix.parse(settings.matrixRules()).rules.size() + "개\n"
        + "· 쓰기 결과 조회 " + settings.effects().size() + "개\n"
        + "· JSON 차단 " + AuthJudgement.rules(settings.ignoredFields(), settings.denialRules(), settings.ownerPath()).denied().size() + "개\n"
        + "· 응답 차단 " + AuthResponseRules.parse(settings.responseRules()).rules.size() + "개\n\n"
        + reconnectGuidance();
  }

  static String compare(AuthSetupBundles.Bundle current, AuthSetupBundles.Bundle incoming) {
    if (current == null) return describe(incoming)
        + "\n\n현재 설정을 읽을 수 없어 변경 수를 계산하지 않았습니다. 대상 요청과 편집 중인 설정을 확인하세요.";
    var before = current.settings(); var after = incoming.settings();
    StringBuilder text = new StringBuilder(heading(incoming));
    text.append("\n현재 기준: ").append(current.method()).append(' ').append(current.origin()).append(current.path()).append('\n');
    if (!current.origin().equals(incoming.origin()) || !current.method().equals(incoming.method()) || !current.path().equals(incoming.path()))
      text.append("대상 범위가 다릅니다. 적용 시 선택 요청의 Origin·메서드·경로와 SCOPE를 다시 확인합니다.\n");
    text.append("\n현재 활성 설정과의 차이\n");
    append(text, "경로 정책", changes(policies(before), policies(after)));
    append(text, "계정별 권한표", changes(matrices(before), matrices(after)));
    append(text, "쓰기 결과 조회", changes(effects(before), effects(after)));
    append(text, "JSON 차단", changes(denials(before), denials(after)));
    append(text, "응답 차단", changes(responses(before), responses(after)));
    Changes fields = changes(fields(before), fields(after));
    text.append("· 기본 비교 설정 변경 ").append(fields.total()).append("개");
    if (!fields.changed().isEmpty()) text.append(" : ").append(String.join(", ", fields.changed()));
    text.append("\n\n").append(reconnectGuidance());
    return text.toString();
  }

  private static String heading(AuthSetupBundles.Bundle bundle) {
    return "설정 묶음: " + bundle.name() + " · r" + bundle.revision()
        + "\n대상 Origin: " + bundle.origin() + "\n대상 요청: " + bundle.method() + " " + bundle.path();
  }

  static String reconnectGuidance() {
    return "적용 후 다시 확인할 항목\n"
        + "1. 역할 1·2·3에 실제 인증 후보를 다시 연결합니다. 비로그인 조건도 확인합니다.\n"
        + "2. 계정별 소유값·역할·조직, 신원 검사와 세션 갱신 조건을 확인합니다.\n"
        + "3. 쓰기 요청이 있으면 쓰기 전송 허용을 다시 선택합니다.\n"
        + "4. 준비 화면에서 같은 원본과 적용 규칙을 검토한 뒤 비교를 시작합니다.\n\n"
        + "현재 계정 지정·신원/갱신 연결·쓰기 허용은 해제되며 인증 후보는 유지됩니다.\n"
        + "Cookie·Authorization·토큰·실제 계정 이름/소유값은 묶음에 저장하지 않습니다.\n"
        + "설정 적용은 요청을 전송하지 않습니다. 사용자 지정 규칙·조회 URL·기대값은 JSON 원문에서도 검토하세요.";
  }

  private static void append(StringBuilder text, String label, Changes delta) {
    text.append("· ").append(label).append(": 추가 ").append(delta.added().size()).append(" / 변경 ")
        .append(delta.changed().size()).append(" / 제거 ").append(delta.removed().size()).append('\n');
    detail(text, "추가", delta.added()); detail(text, "변경", delta.changed()); detail(text, "제거", delta.removed());
  }
  private static void detail(StringBuilder text, String label, List<String> keys) {
    if (keys.isEmpty()) return;
    text.append("    ").append(label).append(": ").append(String.join(", ", keys.subList(0, Math.min(8, keys.size()))));
    if (keys.size() > 8) text.append(" 외 ").append(keys.size() - 8).append("개");
    text.append('\n');
  }
  private static Map<String, String> policies(AuthSetupBundles.Settings settings) {
    Map<String, String> result = new LinkedHashMap<>();
    for (var rule : AuthPolicy.parse(settings.policyRules(), settings.fallback()).rules)
      result.put(rule.method() + " " + rule.path(), rule.access().name());
    return result;
  }
  private static Map<String, List<BountyMatrix.Expected>> matrices(AuthSetupBundles.Settings settings) {
    Map<String, List<BountyMatrix.Expected>> result = new LinkedHashMap<>();
    for (var rule : BountyMatrix.parse(settings.matrixRules()).rules)
      result.put(rule.method() + " " + rule.path(), rule.slots());
    return result;
  }
  private static Map<String, AuthEffectRules.Rule> effects(AuthSetupBundles.Settings settings) {
    Map<String, AuthEffectRules.Rule> result = new LinkedHashMap<>();
    for (var rule : settings.effects()) result.put(rule.key(), rule);
    return result;
  }
  private static Map<String, List<String>> denials(AuthSetupBundles.Settings settings) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (var rule : AuthJudgement.rules(settings.ignoredFields(), settings.denialRules(), settings.ownerPath()).denied())
      result.computeIfAbsent(rule.path(), key -> new ArrayList<>()).add(rule.value());
    result.values().forEach(values -> values.sort(String::compareTo)); return result;
  }
  private static Map<String, String> responses(AuthSetupBundles.Settings settings) {
    // Response fingerprints have no stable ID: report exact additions/removals instead of inventing a pairing.
    Map<String, String> result = new LinkedHashMap<>();
    for (var rule : AuthResponseRules.parse(settings.responseRules()).rules) {
      String identity = rule.target() + " " + rule.regex() + " " + rule.value();
      result.put(identity, identity);
    }
    return result;
  }
  private static Map<String, Object> fields(AuthSetupBundles.Settings settings) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("소유 JSON 경로", settings.ownerPath());
    result.put("제외 필드", new TreeSet<>(AuthJudgement.rules(settings.ignoredFields(), "", "").ignored()));
    result.put("중요 필드", new TreeSet<>(AuthJudgement.paths(settings.importantFields())));
    result.put("기본 접근 정책", settings.fallback()); result.put("배열 순서 무시", settings.unorderedArrays());
    return result;
  }
}
