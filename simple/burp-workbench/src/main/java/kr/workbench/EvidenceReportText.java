package kr.workbench;

import java.util.ArrayList;
import java.util.List;

/** Small writing aids; no generated finding or automatic security judgement. */
final class EvidenceReportText {
  static final String TEMPLATE = "사전 조건\n\n재현 단계\n1. \n\n관측 결과\n";
  static String template(String previous) {
    if (previous.contains("사전 조건") && previous.contains("재현 단계") && previous.contains("관측 결과")) return previous;
    String result = previous.isBlank() ? TEMPLATE : previous.stripTrailing() + "\n\n" + TEMPLATE;
    if (result.length() > EvidenceDrafts.REPRO_LIMIT)
      throw new IllegalArgumentException("작성 양식을 넣을 공간이 없습니다. 재현 절차를 32,768자 이내로 정리하세요.");
    return result;
  }
  static List<String> missing(String title, String impact, String repro) {
    List<String> missing = new ArrayList<>();
    if (title.isBlank() || title.strip().equals("취약점 재현 증거")) missing.add("구체적인 제목");
    if (impact.isBlank()) missing.add("실제 영향");
    if (repro.isBlank()) missing.add("재현 절차");
    else if (repro.contains("사전 조건") && repro.contains("재현 단계") && repro.contains("관측 결과")) {
      int first = repro.indexOf("사전 조건"), steps = repro.indexOf("재현 단계", first + 5), observed = repro.indexOf("관측 결과", steps + 5);
      if (steps > first && observed > steps) {
        if (repro.substring(first + 5, steps).isBlank()) missing.add("사전 조건");
        if (repro.substring(steps + 5, observed).replaceAll("(?m)^\\s*\\d+[.)]\\s*", "").isBlank()) missing.add("재현 단계");
        if (repro.substring(observed + 5).isBlank()) missing.add("관측 결과");
      }
    }
    return List.copyOf(missing);
  }
}
