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
}
