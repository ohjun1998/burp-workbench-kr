package kr.workbench;

import java.util.Locale;

/** Names and values are evidence for review, never credential validity. */
final class SecretCandidates {
  private SecretCandidates() {}

  static boolean named(String name) {
    String key = name.replaceAll("[_-]", "").toLowerCase(Locale.ROOT);
    return key.matches(".*(?:apikey|secret|token|password|authorization|privatekey|streamkey|signingkey|accesskey).*")
        && !key.matches(".*(?:publickey|clientid|videoid|keyname|keyid).*");
  }

  static boolean plausible(String value) {
    if (value == null || value.length() < 8 || value.length() > 16384) return false;
    String text = value.strip();
    if (text.matches("(?is).*(?:example|placeholder|your[_ -]|changeme|undefined|process\\.env|\\{dynamic\\}|REPLACE_|redacted).*")) return false;
    if (text.matches("(?is)^(?:[a-z][a-z0-9+.-]*://|/|\\$\\{|<).*")) return false;
    return JsAnalysis.entropy(text) >= 3.0;
  }

  static boolean expression(String value){
    return value.matches("(?s).*(?:\\$\\{|\\|\\||&&|=>|\\b(?:process\\.env|import\\.meta)\\b).*")
        ||value.matches("(?:this\\.)?[A-Za-z_$][\\w$]*(?:\\??\\.[A-Za-z_$][\\w$]*)+(?:\\([^;]*\\))?")
        ||value.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\([^;]*\\)");
  }

  static boolean example(String name,String value){
    String key=name==null?"":name,valueText=value==null?"":value;
    return valueText.startsWith("sk_test_")||valueText.matches("(?is).*(?:example|placeholder|changeme|your[_ -]|replace_|redacted|(?:^|[_-])demo(?:[_-]|$)).*")
        ||key.matches("(?s)(?i:test|example|demo)(?:[A-Z_.$-].*|$)")
        ||key.matches("(?is).*(?:^|[_.$-])(?:test|example|demo)(?:[_.$-]|$).*");
  }

  static boolean publicIdentifier(String name){
    return name!=null&&name.replaceAll("[_-]","").matches("(?i).*(?:publickey|publicapikey|publishable|clientid|sitekey|measurementid).*");
  }
}
