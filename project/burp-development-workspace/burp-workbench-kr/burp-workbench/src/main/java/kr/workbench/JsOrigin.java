package kr.workbench;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Script download location is never proof of the browser document base. */
final class JsOrigin {
  static String document(String source, String type, String referer) {
    String mime=Objects.toString(type,"").toLowerCase(Locale.ROOT);
    if (mime.contains("html") && http(source)) return source;
    if (http(referer)) return referer;
    if ((mime.contains("json")||mime.contains("xml")||mime.startsWith("text/plain"))&&http(source)) return source;
    return null;
  }
  static boolean http(String value) {
    if (value == null || value.isBlank()) return false;
    try {
      URI uri = URI.create(value);
      return uri.getHost() != null && uri.getUserInfo() == null
          && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
    } catch (IllegalArgumentException ex) { return false; }
  }
}
