package kr.workbench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Data-only, position-preserving string extraction. XML entities are never resolved externally. */
final class TextCandidates {
  record Value(String text, String key, int start, int length, boolean literal) {
    Value(String text,String key,int start,int length){this(text,key,start,length,true);}
  }
  record Scan(String format, boolean dataOnly, List<Value> values, boolean limited) {}
  private static final int LIMIT = 2000;
  private static final Pattern ATTRIBUTE = Pattern.compile(
      "(?<![\\w:.-])([A-Za-z_][\\w:.-]{0,100})\\s*=\\s*([\"'])([^<>]{0,16384}?)\\2");
  private static final Pattern ELEMENT = Pattern.compile(
      "<([A-Za-z_][\\w:.-]{0,100})\\b[^>]{0,8192}>\\s*(?:<!\\[CDATA\\[([\\s\\S]{0,16384}?)\\]\\]>|([^<]{0,16384}))</\\1\\s*>");
  private static final Pattern URL = Pattern.compile(
      "(?i)(?:https?|wss?)://[^\\s\"'<>`\\\\]{1,8192}");
  private static final Pattern NAMED = Pattern.compile(
      "(?im)([A-Za-z_][\\w.-]{0,100})\\s*[:=]\\s*(?:\"([^\"\\r\\n]{1,16384})\"|'([^'\\r\\n]{1,16384})'|([^\\s<>\"']{1,16384}))");
  private static final Pattern PATH_LINE = Pattern.compile(
      "(?m)^\\s*((?:/|\\./|\\.\\./)[^\\s\"'<>]{1,2048})\\s*$");

  static boolean accepts(String type, String url) {
    String mime = type == null ? "" : type.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
    if (mime.equals("image/svg+xml")) return true;
    return !mime.startsWith("image/") && !mime.startsWith("audio/")
        && !mime.startsWith("video/") && !mime.startsWith("font/")
        && !mime.matches("application/(?:pdf|zip|x-7z-compressed|x-rar-compressed|gzip|x-gzip|wasm|x-font.*|font.*|.*protobuf.*)");
  }

  static boolean textual(String text) {
    if (text == null) return false;
    int count = Math.min(text.length(), 8192), controls = 0;
    for (int i = 0; i < count; i++) {
      char c = text.charAt(i);
      if (c == 0) return false;
      if (c < 32 && c != '\n' && c != '\r' && c != '\t' && c != '\f') controls++;
    }
    return controls <= count / 100;
  }

  static Scan scan(String source, String contentType, String base, long deadline) {
    String trimmed = source.stripLeading(), mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    String path = base == null ? "" : base.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
    String format = "";
    boolean data = false;
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      try {
        MinerJson.parse(source, JsAnalysis.SOURCE_LIMIT, 20000);
        format = "JSON"; data = true;
      } catch (IllegalArgumentException ignored) { }
    }
    if (format.isEmpty() && mime.contains("json")) { format = "JSON 텍스트"; data = true; }
    boolean html = mime.contains("html") || trimmed.matches("(?is)^<(?:!doctype\\s+html|html|head|body|script|div)\\b[\\s\\S]*");
    boolean markup = trimmed.startsWith("<") || mime.contains("xml");
    if (format.isEmpty() && markup) { format = html ? "HTML" : "XML"; data = !html; }
    if (format.isEmpty() && !mime.contains("javascript") && !mime.contains("ecmascript")
        && !path.matches(".*\\.(?:js|mjs|cjs)$")
        && (mime.startsWith("text/") || mime.contains("yaml") || mime.contains("graphql")
            || mime.contains("x-www-form-urlencoded")
            || path.matches(".*\\.(?:txt|yaml|yml|csv|ini|conf|config|log|graphql)$"))) {
      format = "일반 텍스트"; data = true;
    }
    List<Value> values = new ArrayList<>();
    if (format.startsWith("JSON")) {
      String key = "";
      for (int i=0;i<source.length() && room(values,deadline);i++) {
        if(source.charAt(i)!='"')continue;
        int begin=i, end=++i;
        while(end<source.length()) {
          char c=source.charAt(end++);
          if(c=='\\') {if(end<source.length())end++;}
          else if(c=='"')break;
        }
        i=end-1;
        if(end-begin>16386)continue;
        String value;
        try {value=(String)MinerJson.parse(source.substring(begin,end),JsAnalysis.SOURCE_LIMIT,2);}
        catch(IllegalArgumentException ex){continue;}
        int next=end;
        while(next<source.length()&&Character.isWhitespace(source.charAt(next)))next++;
        boolean property=next<source.length()&&source.charAt(next)==':';
        if(property){
          key=value;
          if(value.startsWith("/"))values.add(new Value(value,"경로 키",begin,end-begin));
        }else values.add(new Value(value,key,begin,end-begin));
      }
    } else if (markup) {
      Matcher attrs = ATTRIBUTE.matcher(source);
      while (attrs.find() && room(values, deadline))
        values.add(new Value(entities(attrs.group(3)), attrs.group(1), attrs.start(3), attrs.end(3)-attrs.start(3)));
      Matcher elements = ELEMENT.matcher(source);
      while (elements.find() && room(values, deadline)) {
        int group = elements.group(2) == null ? 3 : 2;
        values.add(new Value(group==2?elements.group(group):entities(elements.group(group)), elements.group(1), elements.start(group), elements.end(group)-elements.start(group)));
      }
    } else if (data) {
      scanPlain(source, values, deadline, false);
    }
    return new Scan(format, data, List.copyOf(values), !room(values, deadline));
  }

  static Scan fallback(String source, long deadline) {
    List<Value> values = new ArrayList<>();
    scanPlain(source, values, deadline, true);
    return new Scan("텍스트 보조", true, List.copyOf(values), !room(values, deadline));
  }

  private static void scanPlain(String source, List<Value> values, long deadline, boolean code) {
    Matcher named = NAMED.matcher(source);
    while (named.find() && room(values, deadline)) {
      if(named.group(1).matches("(?i)https?|wss?"))continue;
      int group = named.group(2) != null ? 2 : named.group(3) != null ? 3 : 4;
      boolean literal=!code||group!=4&&literalEnd(source,named.end());
      values.add(new Value(named.group(group), named.group(1), named.start(group), named.end(group)-named.start(group),literal));
    }
    Matcher urls = URL.matcher(source);
    while (urls.find() && room(values, deadline)) {
      boolean contained = false;
      for (Value value : values)
        if (urls.start() >= value.start() && urls.end() <= value.start()+value.length()) { contained = true; break; }
      if (!contained) values.add(new Value(urls.group().replaceAll("[),.;]+$", ""), "URL 문자열", urls.start(), urls.end()-urls.start()));
    }
    Matcher paths = PATH_LINE.matcher(source);
    while (paths.find() && room(values, deadline))
      values.add(new Value(paths.group(1), "경로 문자열", paths.start(1), paths.end(1)-paths.start(1)));
  }

  private static boolean literalEnd(String source,int end){
    int next=end;boolean newline=false;while(next<source.length()&&Character.isWhitespace(source.charAt(next))){newline|=source.charAt(next)=='\r'||source.charAt(next)=='\n';next++;}
    if(next==source.length()||";,})]".indexOf(source.charAt(next))>=0||source.startsWith("//",next)||source.startsWith("/*",next))return true;
    if(!newline||!Character.isJavaIdentifierStart(source.charAt(next)))return false;
    int word=next+1;while(word<source.length()&&Character.isJavaIdentifierPart(source.charAt(word)))word++;
    return !List.of("in","instanceof").contains(source.substring(next,word));
  }

  private static boolean room(List<Value> values, long deadline) {
    return values.size() < LIMIT && !Thread.currentThread().isInterrupted() && System.nanoTime() < deadline;
  }

  private static String entities(String text) {
    // Only predefined/numeric entities. Unknown names (including external entities) stay literal.
    Matcher numeric = Pattern.compile("&#(x[0-9a-fA-F]{1,6}|[0-9]{1,7});").matcher(text);
    StringBuffer out = new StringBuffer();
    while (numeric.find()) {
      String replacement = numeric.group();
      try {
        String n = numeric.group(1);
        int code = Integer.parseInt(n.startsWith("x") ? n.substring(1) : n, n.startsWith("x") ? 16 : 10);
        if (Character.isValidCodePoint(code) && code >= 32 && (code < 0xd800 || code > 0xdfff)) replacement = new String(Character.toChars(code));
      } catch (RuntimeException ignored) { }
      numeric.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    numeric.appendTail(out);
    return out.toString().replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
  }
}
