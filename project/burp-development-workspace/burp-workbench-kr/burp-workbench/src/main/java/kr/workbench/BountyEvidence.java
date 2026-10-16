package kr.workbench;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Portable redacted text evidence. Complete API packet bytes remain in the local evidence store.
 */
final class BountyEvidence {
  /**
   * Legacy text constructors remain available for imported/test evidence. Live packets use disk
   * refs.
   */
  static final class Item {
    private final String label, method, url, note, account, evidenceId;
    private final String requestText, responseText;
    private final EvidencePacketStore.Ref packet;

    Item(String label, String method, String url, String request, String response, String note) {
      this(label, method, url, request, response, note, "");
    }

    Item(
        String label,
        String method,
        String url,
        String request,
        String response,
        String note,
        String account) {
      this(label, method, url, request, response, note, account, null);
    }

    Item(
        String label,
        String method,
        String url,
        String note,
        String account,
        EvidencePacketStore.Ref packet) {
      this(label, method, url, null, null, note, account, Objects.requireNonNull(packet));
    }

    private Item(
        String label,
        String method,
        String url,
        String request,
        String response,
        String note,
        String account,
        EvidencePacketStore.Ref packet) {
      this.label = Objects.toString(label, "");
      this.method = Objects.toString(method, "");
      this.url = Objects.toString(url, "");
      this.note = Objects.toString(note, "");
      this.account = Objects.toString(account, "");
      this.packet = packet;
      requestText = packet == null ? Objects.toString(request, "") : null;
      responseText = packet == null ? Objects.toString(response, "") : null;
      evidenceId = UUID.randomUUID().toString();
    }

    String label() {
      return label;
    }

    String method() {
      return method;
    }

    String url() {
      return url;
    }

    String note() {
      return note;
    }

    String account() {
      return account;
    }

    String evidenceId() {
      return evidenceId;
    }

    boolean diskBacked() {
      return packet != null;
    }

    String request() {
      return packet == null ? requestText : packet.requestPreview();
    }

    String response() {
      return packet == null ? responseText : packet.responsePreview();
    }

    boolean requestTruncated() {
      return packet == null
          ? requestText.length() > EvidencePacketStore.PREVIEW_BYTES
          : packet.request().length() > EvidencePacketStore.PREVIEW_BYTES;
    }

    boolean responseTruncated() {
      return packet == null
          ? responseText.length() > EvidencePacketStore.PREVIEW_BYTES
          : packet.response().length() > EvidencePacketStore.PREVIEW_BYTES;
    }

    EvidencePacketStore.Lease retain() {
      return packet == null ? null : packet.retain();
    }

    String packetId() {
      return packet == null
          ? "text-sha256:"
              + Core.hash(
                  (Core.hash(requestText.getBytes(StandardCharsets.UTF_8))
                          + ":"
                          + Core.hash(responseText.getBytes(StandardCharsets.UTF_8)))
                      .getBytes(StandardCharsets.US_ASCII))
          : packet.packetId();
    }
  }

  static final Pattern SECRET =
      Pattern.compile(
          "(?i)(password|passwd|secret|token|authorization|api[-_]?key|csrf|xsrf|session|cookie|credential)");

  static String esc(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  static String packet(String raw, Set<String> extra, Collection<String> secrets, boolean bodies) {
    return packet(
        raw,
        extra,
        secrets,
        bodies,
        raw != null && raw.length() > EvidencePacketStore.PREVIEW_BYTES);
  }

  static String packet(
      String raw,
      Set<String> extra,
      Collection<String> secrets,
      boolean bodies,
      boolean truncated) {
    String s = Objects.toString(raw, "");
    if (s.length() > 128 * 1024) {
      s = s.substring(0, 128 * 1024);
      truncated = true;
    }
    s = s.replace("\r\n", "\n");
    int split = s.indexOf("\n\n");
    String head = split < 0 ? s : s.substring(0, split),
        body = split < 0 ? "" : s.substring(split + 2);
    StringBuilder out = new StringBuilder();
    String type = "";
    for (String line : head.split("\n"))
      if (line.toLowerCase(Locale.ROOT).startsWith("content-type:"))
        type = line.substring(13).strip().split(";", 2)[0].toLowerCase(Locale.ROOT);
    for (String line : head.split("\n", -1)) {
      int colon = line.indexOf(':');
      if (colon > 0
          && (SECRET.matcher(line.substring(0, colon)).find()
              || extra.contains(line.substring(0, colon).toLowerCase(Locale.ROOT))))
        line = line.substring(0, colon) + ": [REDACTED]";
      line = line.replaceAll("([?#&][^=\\s&]+)=([^\\s&]*)", "$1=[REDACTED]");
      out.append(line).append('\n');
    }
    out.append('\n');
    if (truncated) out.append("[미리보기 128 KiB 한도 · 본문 제외 · 원본 길이/해시로 확인]");
    else if (!body.isEmpty()) {
      if (!bodies) out.append("[본문 제외 · 재현 시 원본을 로컬에서 확인]");
      else if (type.equals("application/x-www-form-urlencoded")) {
        try {
          StringJoiner form = new StringJoiner("&");
          for (String pair : body.split("&", -1)) {
            String[] p = pair.split("=", 2);
            String key = java.net.URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                value =
                    java.net.URLDecoder.decode(p.length == 2 ? p[1] : "", StandardCharsets.UTF_8);
            value =
                SECRET.matcher(key).find() || fragments(secrets).contains(value)
                    ? "[REDACTED]"
                    : known(value, secrets);
            form.add(
                java.net.URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "="
                    + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
          }
          out.append(form);
        } catch (Exception ex) {
          out.append("[폼 디코딩 실패 · 본문 제외]");
        }
      } else
        try {
          out.append(
              MetadataExport.json(
                  scrub(MinerJson.parse(body, 128 * 1024, 20000), "", fragments(secrets))));
        } catch (Exception e) {
          out.append("[비 JSON 본문 제외 · 바이트 수/해시로 원본 확인]");
        }
    }
    return known(out.toString(), secrets);
  }

  static Set<String> fragments(Collection<String> secrets) {
    Set<String> out = new HashSet<>();
    for (String value : secrets) {
      if (value == null || value.isEmpty()) continue;
      out.add(value);
      if (value.matches("(?i)Bearer .+|Basic .+")) out.add(value.substring(value.indexOf(' ') + 1));
      for (String pair : value.split(";")) {
        int n = pair.indexOf('=');
        if (n > 0 && !pair.substring(n + 1).strip().isEmpty())
          out.add(pair.substring(n + 1).strip());
      }
    }
    return out;
  }

  static Object scrub(Object value, String key) {
    return scrub(value, key, Set.of());
  }

  static Object scrub(Object value, String key, Set<String> secrets) {
    if (SECRET.matcher(key).find() || value instanceof String text && secrets.contains(text))
      return "[REDACTED]";
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(k.toString(), scrub(v, k.toString(), secrets)));
      return out;
    }
    if (value instanceof List<?> l) return l.stream().map(v -> scrub(v, key, secrets)).toList();
    return value;
  }

  static String known(String s, Collection<String> secrets) {
    Set<String> values = new LinkedHashSet<>();
    for (String value : secrets) {
      if (value == null) continue;
      values.add(value);
      if (value.matches("(?i)Bearer .+|Basic .+"))
        values.add(value.substring(value.indexOf(' ') + 1));
      for (String pair : value.split(";")) {
        int n = pair.indexOf('=');
        if (n > 0) values.add(pair.substring(n + 1).strip());
      }
    }
    for (String v :
        values.stream()
            .filter(v -> v.length() >= 4)
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList()) s = s.replace(v, "[REDACTED]");
    return s;
  }

  static String preview(
      String title,
      String impact,
      String steps,
      List<Item> items,
      Set<String> headers,
      Collection<String> secrets,
      boolean bodies,
      boolean image) {
    StringBuilder out = new StringBuilder("증거 가림 미리보기 · 원본 HTTP 바이트가 아닌 텍스트\n");
    out.append("제목: ")
        .append(known(title, secrets))
        .append("\n영향: ")
        .append(known(impact, secrets))
        .append("\n재현: ")
        .append(known(steps, secrets))
        .append("\n");
    int i = 0;
    for (Item item : items) {
      try (var retained = item.retain()) {
        out.append("\n--- ")
            .append(++i)
            .append(". ")
            .append(known(item.label, secrets))
            .append(" ---\n")
            .append(known(Objects.toString(ActivityTracker.safeUrl(item.url), ""), secrets))
            .append("\n")
            .append(known(item.note, secrets))
            .append("\n패킷 ID: ")
            .append(item.packetId())
            .append("\nREQUEST\n")
            .append(packet(item.request(), headers, secrets, bodies, item.requestTruncated()))
            .append("\nRESPONSE\n")
            .append(packet(item.response(), headers, secrets, bodies, item.responseTruncated()))
            .append("\n");
      }
    }
    if (image) out.append("\n브라우저 이미지 첨부 있음 · 이미지 내용은 자동 가림되지 않습니다. 직접 확인하세요.\n");
    return out.toString();
  }

  static byte[] imageBytes(Path image, String expected) throws Exception {
    byte[] data;
    try (var in = Files.newInputStream(image)) {
      data = in.readNBytes(8 * 1024 * 1024 + 1);
    }
    if (data.length > 8 * 1024 * 1024) throw new IllegalArgumentException("브라우저 이미지 8MB 제한");
    if (expected != null && !Core.hash(data).equals(expected))
      throw new IllegalArgumentException("브라우저 이미지 변경/유실 · 실행 근거를 다시 확인해 첨부하세요.");
    return data;
  }

  static String validateImage(Path image) throws Exception {
    return validateImageBytes(imageBytes(image, null));
  }

  static String validateImageBytes(byte[] image) throws Exception {
    try (var in =
        javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(image))) {
      if (in == null) throw new IllegalArgumentException("이미지 읽기 실패");
      var readers = javax.imageio.ImageIO.getImageReaders(in);
      if (!readers.hasNext()) throw new IllegalArgumentException("PNG/JPEG 이미지 필요");
      var r = readers.next();
      try {
        r.setInput(in);
        String f = r.getFormatName().toLowerCase(Locale.ROOT);
        if (!Set.of("png", "jpeg", "jpg").contains(f)
            || r.getWidth(0) > 4096
            || r.getHeight(0) > 4096) throw new IllegalArgumentException("PNG/JPEG 최대 4096 픽셀");
        r.read(0);
        return f.equals("png") ? "png" : "jpg";
      } finally {
        r.dispose();
      }
    }
  }

  static void save(
      Path path,
      String title,
      String impact,
      String steps,
      List<Item> items,
      Set<String> headers,
      Collection<String> secrets,
      boolean bodies,
      String coverage,
      Path image)
      throws Exception {
    save(path, title, impact, steps, items, headers, secrets, bodies, coverage, image, List.of());
  }

  static void save(
      Path path,
      String title,
      String impact,
      String steps,
      List<Item> items,
      Set<String> headers,
      Collection<String> secrets,
      boolean bodies,
      String coverage,
      Path image,
      List<Map<String, Object>> oast)
      throws Exception {
    save(path, title, impact, steps, items, headers, secrets, bodies, coverage, image, oast, null);
  }

  static void save(
      Path path,
      String title,
      String impact,
      String steps,
      List<Item> items,
      Set<String> headers,
      Collection<String> secrets,
      boolean bodies,
      String coverage,
      Path image,
      List<Map<String, Object>> oast,
      String expectedImageHash)
      throws Exception {
    if (items.isEmpty()) throw new IllegalArgumentException("증거 요청을 먼저 가져오세요.");
    if (items.size() > 32
        || title.length() > 256
        || impact.length() > 8192
        || steps.length() > 8192) throw new IllegalArgumentException("증거 크기 제한");
    List<EvidencePacketStore.Lease> retained = new ArrayList<>();
    Path temp = null;
    try {
      // A close/unload can retire the store, but cannot remove bytes used by this export.
      for (Item item : items) {
        var lease = item.retain();
        if (lease != null) retained.add(lease);
      }
      byte[] imageData = image == null ? null : imageBytes(image, expectedImageHash);
      String imageFormat = imageData == null ? null : validateImageBytes(imageData);
      temp = Files.createTempFile(path.toAbsolutePath().getParent(), "wb-evidence-", ".tmp");
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
        List<Map<String, Object>> metadata = new ArrayList<>();
        int i = 0;
        // Each packet pair is read, redacted and written independently. No archive-sized byte map.
        for (Item item : items) {
          i++;
          String sourceRequest = item.request(), sourceResponse = item.response();
          String request = packet(sourceRequest, headers, secrets, bodies, item.requestTruncated());
          String response =
              packet(sourceResponse, headers, secrets, bodies, item.responseTruncated());
          writeText(zip, "requests/" + i + ".http", request);
          writeText(zip, "responses/" + i + ".http", response);
          metadata.add(metadata(item, sourceRequest, sourceResponse, request, response, secrets));
        }
        writeReport(
            zip,
            "report.md",
            writer -> {
              writer.write(
                  "# "
                      + known(title, secrets)
                      + "\n\n## 영향 (진단자 작성)\n"
                      + known(impact, secrets)
                      + "\n\n## 재현 절차\n"
                      + known(steps, secrets)
                      + "\n\n");
              int n = 0;
              for (Item item : items) {
                n++;
                writer.write(
                    "## "
                        + n
                        + ". "
                        + known(item.label, secrets)
                        + "\n"
                        + item.method
                        + " "
                        + safeUrl(item, secrets)
                        + "\n\n"
                        + known(item.note, secrets)
                        + "\n\n패킷 ID: "
                        + item.packetId()
                        + "\n요청: requests/"
                        + n
                        + ".http\n응답: responses/"
                        + n
                        + ".http\n\n");
              }
              writer.write(
                  "\n"
                      + "자동 관찰은 후보이며 진단자가 실제 영향과 재현 결과를 확인해야 합니다. 인증값·쿼리값은 가려져 있으므로 재현 시 로컬 원본으로"
                      + " 보완하세요. 원본 HTTP 바이트는 이 ZIP에 포함되지 않습니다.\n");
            });
        writeReport(
            zip,
            "report.html",
            writer -> {
              writer.write(
                  "<!doctype html><html lang=ko><meta charset=utf-8><title>점검"
                      + " 증거</title><style>body{font-family:system-ui;max-width:1100px;margin:40px"
                      + " auto;padding:20px}pre{white-space:pre-wrap;background:#f0f5f8;padding:18px}h2{color:#126078}</style><h1>"
                      + esc(known(title, secrets))
                      + "</h1><h2>영향</h2><pre>"
                      + esc(known(impact, secrets))
                      + "</pre><h2>재현 절차</h2><pre>"
                      + esc(known(steps, secrets))
                      + "</pre>");
              int n = 0;
              for (Item item : items) {
                n++;
                writer.write(
                    "<h2>"
                        + n
                        + ". "
                        + esc(known(item.label, secrets))
                        + "</h2><p>"
                        + esc(item.method + " " + safeUrl(item, secrets))
                        + "</p><p>"
                        + esc(known(item.note, secrets))
                        + "</p><p>패킷 ID: "
                        + esc(item.packetId())
                        + "</p><h3>요청</h3><pre>");
                writer.write(
                    esc(packet(item.request(), headers, secrets, bodies, item.requestTruncated())));
                writer.write("</pre><h3>응답</h3><pre>");
                writer.write(
                    esc(
                        packet(
                            item.response(), headers, secrets, bodies, item.responseTruncated())));
                writer.write("</pre>");
              }
              writer.write("<p>가린 텍스트 증거 · 원본 HTTP 바이트는 ZIP에 포함되지 않습니다.</p></html>");
            });
        // Keep the old version key as a schema alias for readers; producerVersion is the release.
        writeText(
            zip,
            "evidence.json",
            MetadataExport.json(
                Map.of(
                    "version",
                    BuildInfo.EVIDENCE_SCHEMA_VERSION,
                    "schemaVersion",
                    BuildInfo.EVIDENCE_SCHEMA_VERSION,
                    "producerVersion",
                    BuildInfo.VERSION,
                    "items",
                    metadata)));
        writeText(zip, "coverage.json", known(coverage, secrets));
        List<Map<String, Object>> linked = new ArrayList<>();
        for (var event : oast) {
          Map<String, Object> row = new LinkedHashMap<>(event);
          List<Integer> refs = new ArrayList<>();
          List<String> ids = new ArrayList<>();
          for (int n = 0; n < items.size(); n++)
            if (Objects.equals(event.get("method"), items.get(n).method)
                && Objects.equals(
                    ActivityTracker.safeUrl(Objects.toString(event.get("url"), "")),
                    ActivityTracker.safeUrl(items.get(n).url))) {
              refs.add(n + 1);
              ids.add(items.get(n).packetId());
            }
          row.put("evidenceItems", refs);
          row.put("packetIds", ids);
          row.put("correlation", "제공 URL/메서드 연결 · 해당 패킷이 콜백을 유발했는지 수동 확인");
          linked.add(row);
        }
        writeText(zip, "oast.json", known(MetadataExport.json(linked), secrets));
        if (imageData != null) {
          writeBytes(zip, "browser-evidence." + imageFormat, imageData);
          writeText(
              zip,
              "browser-evidence.json",
              MetadataExport.json(
                  Map.of(
                      "sha256",
                      Core.hash(imageData),
                      "provenance",
                      "사용자 첨부 이미지 · 실행 여부는 진단자가 확인 · 자동 가림 없음")));
        }
      }
      try {
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      try {
        if (temp != null) Files.deleteIfExists(temp);
      } finally {
        for (int n = retained.size() - 1; n >= 0; n--) retained.get(n).close();
      }
    }
  }

  private static String safeUrl(Item item, Collection<String> secrets) {
    return known(Objects.toString(ActivityTracker.safeUrl(item.url), ""), secrets);
  }

  private static Map<String, Object> metadata(
      Item item,
      String sourceRequest,
      String sourceResponse,
      String request,
      String response,
      Collection<String> secrets) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("evidenceId", item.evidenceId());
    row.put("packetId", item.packetId());
    row.put("label", known(item.label, secrets));
    row.put("method", item.method);
    row.put("url", safeUrl(item, secrets));
    row.put("note", known(item.note, secrets));
    row.put("accountId", item.account);
    row.put("requestTextSha256", Core.hash(sourceRequest.getBytes(StandardCharsets.UTF_8)));
    row.put("responseTextSha256", Core.hash(sourceResponse.getBytes(StandardCharsets.UTF_8)));
    row.put(
        "requestUtf8Bytes", String.valueOf(sourceRequest.getBytes(StandardCharsets.UTF_8).length));
    row.put(
        "responseUtf8Bytes",
        String.valueOf(sourceResponse.getBytes(StandardCharsets.UTF_8).length));
    row.put(
        "sourceTextDigestRepresentation",
        item.diskBacked()
            ? "UTF-8로 해석한 최대 128 KiB 원본 바이트 미리보기 · 가림 전 · 전체 원본 바이트 해시와 다름"
            : "제공된 전체 텍스트의 UTF-8 · HTTP 원본 바이트 아님");
    row.put(
        "sourceTextEncoding",
        item.diskBacked()
            ? "UTF-8 · 유효하지 않은 바이트는 대체 문자로 표시 · 원본 바이트는 그대로 보존"
            : "제공된 문자열을 UTF-8로 인코딩");
    row.put("requestPreviewTruncated", item.requestTruncated());
    row.put("responsePreviewTruncated", item.responseTruncated());
    row.put("requestExportTextSha256", Core.hash(request.getBytes(StandardCharsets.UTF_8)));
    row.put("responseExportTextSha256", Core.hash(response.getBytes(StandardCharsets.UTF_8)));
    row.put("packetRepresentation", "가린 텍스트 미리보기 · 원본 HTTP 바이트가 아님");
    if (item.diskBacked()) {
      row.put("requestRawSha256", item.packet.request().sha256());
      row.put("responseRawSha256", item.packet.response().sha256());
      row.put("requestRawBytes", item.packet.request().length());
      row.put("responseRawBytes", item.packet.response().length());
      row.put("responsePresent", item.packet.hasResponse());
      row.put(
          "rawProvenance",
          "Burp API가 제공한 전체 HTTP 바이트 · 임시 디스크 보관 · 네트워크/TLS 원시 캡처 아님 · ZIP에는 원본 미포함");
    }
    return row;
  }

  @FunctionalInterface
  private interface ReportWriter {
    void write(Writer writer) throws IOException;
  }

  private static void writeReport(ZipOutputStream zip, String name, ReportWriter report)
      throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    Writer writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
    report.write(writer);
    writer.flush();
    zip.closeEntry();
  }

  private static void writeText(ZipOutputStream zip, String name, String text) throws IOException {
    writeReport(zip, name, writer -> writer.write(text));
  }

  private static void writeBytes(ZipOutputStream zip, String name, byte[] bytes)
      throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }

  static String fileHash(Path path) throws IOException {
    var digest = EvidencePacketStore.digest();
    byte[] block = new byte[EvidencePacketStore.BLOCK_BYTES];
    try (InputStream in = Files.newInputStream(path)) {
      for (int n; (n = in.read(block)) != -1; ) digest.update(block, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
