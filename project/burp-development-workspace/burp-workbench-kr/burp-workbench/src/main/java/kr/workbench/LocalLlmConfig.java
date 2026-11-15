package kr.workbench;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** The key is session-only. Endpoint validation deliberately performs no DNS lookup. */
record LocalLlmConfig(String baseUrl, String model, String apiKey, int timeoutSeconds,
                      int maxTokens, String reasoningEffort) {
  LocalLlmConfig {
    baseUrl = normalize(baseUrl);
    model = Objects.requireNonNullElse(model, "").strip();
    apiKey = Objects.requireNonNullElse(apiKey, "").strip();
    reasoningEffort = Objects.requireNonNullElse(reasoningEffort, "").strip();
    if (model.isEmpty() || model.length() > 200 || controls(model))
      throw new IllegalArgumentException("모델 ID를 확인하세요. 최대 200자입니다.");
    if (apiKey.length() > 4096 || apiKey.chars().anyMatch(c -> c < 33 || c > 126))
      throw new IllegalArgumentException("API 키 형식을 확인하세요.");
    if (timeoutSeconds < 1 || timeoutSeconds > 600)
      throw new IllegalArgumentException("응답 제한은 1~600초입니다.");
    if (maxTokens < 256 || maxTokens > 65536)
      throw new IllegalArgumentException("출력·추론 예산은 256~65536토큰입니다.");
    if (!Set.of("", "none", "low", "medium", "high", "xhigh").contains(reasoningEffort))
      throw new IllegalArgumentException("지원하는 추론 수준을 선택하세요.");
  }

  static LocalLlmConfig defaults() {
    return new LocalLlmConfig("http://127.0.0.1:8731/v1", "halogen-qwen3.8-flash-next",
        "", 180, 8192, "low");
  }

  private static boolean controls(String text) {
    return text.chars().anyMatch(c -> c < 32 || c == 127);
  }

  private static String normalize(String text) {
    try {
      String value = Objects.requireNonNullElse(text, "").strip();
      if (value.length() > 512 || controls(value)) throw new IllegalArgumentException();
      URI uri = URI.create(value);
      String scheme = Objects.requireNonNullElse(uri.getScheme(), "").toLowerCase(Locale.ROOT);
      if (!Set.of("http", "https").contains(scheme) || uri.getUserInfo() != null
          || uri.getRawQuery() != null || uri.getRawFragment() != null
          || uri.getPort() == 0 || uri.getPort() > 65535 || !localHost(uri.getHost()))
        throw new IllegalArgumentException();
      String path = Objects.requireNonNullElse(uri.getRawPath(), "");
      while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
      if (path.isEmpty()) path = "/v1";
      if (!path.equals("/v1")) throw new IllegalArgumentException();
      return scheme + "://" + uri.getRawAuthority() + path;
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("로컬 LLM 주소는 localhost 또는 사설 IP의 http(s)://주소[:포트]/v1 형식입니다.");
    }
  }

  private static boolean localHost(String host) {
    if (host == null) return false;
    if (host.equalsIgnoreCase("localhost")) return true;
    if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
    if (host.indexOf(':') >= 0) {
      if (host.indexOf('%') >= 0 || !host.matches("[0-9a-fA-F:]+")) return false;
      try {
        byte[] bytes = InetAddress.getByName(host).getAddress(); // colon-only literal, never DNS
        return bytes.length == 16 && (InetAddress.getByAddress(bytes).isLoopbackAddress()
            || (bytes[0] & 0xfe) == 0xfc);
      } catch (Exception invalid) { return false; }
    }
    if (!host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
    String[] parts = host.split("\\.");
    int[] octets = new int[4];
    for (int i = 0; i < 4; i++) {
      octets[i] = Integer.parseInt(parts[i]);
      if (octets[i] > 255) return false;
    }
    return octets[0] == 127 || octets[0] == 10
        || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
        || octets[0] == 192 && octets[1] == 168;
  }

  @Override public String toString() {
    return "LocalLlmConfig[baseUrl=" + baseUrl + ", model=" + model
        + ", apiKey=[가림], timeoutSeconds=" + timeoutSeconds + ", maxTokens=" + maxTokens
        + ", reasoningEffort=" + reasoningEffort + "]";
  }
}
