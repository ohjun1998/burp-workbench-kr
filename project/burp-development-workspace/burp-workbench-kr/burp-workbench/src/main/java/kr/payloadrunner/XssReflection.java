package kr.payloadrunner;

import java.util.*;
import java.util.regex.*;

/** Bounded HTML context observation. Does not execute HTML or claim browser confirmation. */
final class XssReflection {
  private static final Pattern TAG = Pattern.compile("^<\\s*([A-Za-z][A-Za-z0-9:-]*)");
  private static final Set<String> RAW = Set.of("script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "plaintext", "template", "noscript");

  static String context(String html, String marker) {
    if (marker.isEmpty() || !html.contains(marker)) return "";
    String lower = html.toLowerCase(Locale.ROOT);
    String raw = "";
    for (int pos = 0; pos < html.length();) {
      if (!raw.isEmpty()) {
        if (raw.equals("plaintext")) return "";
        int close = lower.indexOf("</" + raw, pos);
        if (close < 0) return "";
        int boundary = close + 2 + raw.length();
        if (boundary < html.length() && !Character.isWhitespace(html.charAt(boundary)) && html.charAt(boundary) != '>') {pos = boundary; continue;}
        int end = tagEnd(html, close);
        if (end < 0) return "";
        pos = end + 1; raw = ""; continue;
      }
      int start = html.indexOf('<', pos);
      if (start < 0) break;
      if (html.startsWith("<!--", start)) {
        int end = html.indexOf("-->", start + 4);
        if (end < 0) break;
        pos = end + 3; continue;
      }
      int end = tagEnd(html, start);
      if (end < 0) break;
      String tag = html.substring(start, end + 1);
      Matcher name = TAG.matcher(tag);
      if (name.find()) {
        String element = name.group(1).toLowerCase(Locale.ROOT);
        if (event(tag, name.end(), marker)) return "<" + element + "> 이벤트 속성 · Body: " + start;
        if (RAW.contains(element)) raw = element;
      }
      pos = end + 1;
    }
    return "";
  }

  private static boolean event(String tag, int pos, String marker) {
    while (pos < tag.length() - 1) {
      while (pos < tag.length() && (Character.isWhitespace(tag.charAt(pos)) || tag.charAt(pos) == '/')) pos++;
      int start = pos;
      while (pos < tag.length() && !Character.isWhitespace(tag.charAt(pos)) && "=/>".indexOf(tag.charAt(pos)) < 0) pos++;
      String name = tag.substring(start, pos).toLowerCase(Locale.ROOT);
      while (pos < tag.length() && Character.isWhitespace(tag.charAt(pos))) pos++;
      if (pos >= tag.length() || tag.charAt(pos) != '=') {if (pos == start) pos++; continue;}
      pos++; while (pos < tag.length() && Character.isWhitespace(tag.charAt(pos))) pos++;
      if (pos >= tag.length()) break;
      char quote = tag.charAt(pos);
      boolean quoted = quote == '\'' || quote == '"';
      start = quoted ? ++pos : pos;
      while (pos < tag.length() && (quoted ? tag.charAt(pos) != quote : !Character.isWhitespace(tag.charAt(pos)) && tag.charAt(pos) != '>')) pos++;
      String value = tag.substring(start, pos);
      if (quoted) pos++;
      if (name.matches("on[a-z]+") && value.contains(marker) && value.contains("console.log(") && !value.contains("&quot;") && !value.contains("&#")) return true;
    }
    return false;
  }

  private static int tagEnd(String html, int start) {
    char quote = 0;
    for (int i = start + 1; i < html.length() && i - start <= 8192; i++) {
      char c = html.charAt(i);
      if (quote != 0) {if (c == quote) quote = 0;}
      else if (c == '\'' || c == '"') quote = c;
      else if (c == '>') return i;
    }
    return -1;
  }
}
