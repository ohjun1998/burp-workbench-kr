package kr.workbench;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

/** Loopback-only Katana transport. Target traffic can leave this class only through Fetcher. */
final class ScopeKatanaProxy implements AutoCloseable {
  record Header(String name, String value) {
    Header { name = Objects.requireNonNull(name); value = Objects.requireNonNull(value); }
    @Override public String toString() { return "Header[" + name + ", value hidden]"; }
  }
  record Response(int status, List<Header> headers, byte[] body) {
    Response { headers = List.copyOf(headers); body = Objects.requireNonNull(body).clone(); }
    @Override public byte[] body() { return body.clone(); }
    @Override public String toString() { return "Response[status=" + status + ", headers=" + headers.size() + ", bytes=" + body.length + "]"; }
  }
  record Blocked(String url, String method, String reason) {
    Blocked {
      url = ScopeInspectionAnalysis.safeUrl(url);
      method = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "CONNECT").contains(method) ? method : "OTHER";
      reason = Objects.requireNonNullElse(reason, "프록시 경계 검사 실패");
    }
  }
  @FunctionalInterface interface Fetcher { Response fetch(String url, String method) throws Exception; }
  /** Public certificate material is also useful for isolated HTTPS fixtures; no trust store is installed. */
  record TlsMaterial(SSLContext context, X509Certificate certificate) {}
  private record Request(String method, String target, Map<String, List<String>> headers) {
    String single(String name) { var values = headers.get(name); return values == null || values.size() != 1 ? null : values.get(0); }
  }
  private static final class Rejected extends IOException {
    final int status; final String reason;
    Rejected(int status, String reason) { super(reason); this.status = status; this.reason = reason; }
  }

  static final int MAX_HEADER_BYTES = 32 * 1024;
  private static final int MAX_CONNECTIONS = 8, READ_TIMEOUT_MILLIS = 5_000, CONNECTION_MILLIS = 120_000;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> HOP = Set.of("connection", "proxy-connection", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade", "proxy-authenticate", "proxy-authorization");
  private final String origin, host, address;
  private final Predicate<String> allowed;
  private final Fetcher fetcher;
  private final Consumer<Blocked> blocked;
  private final byte[] authentication;
  private final SSLContext tls;
  private final ServerSocket listener;
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final Thread acceptor;
  private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, MAX_CONNECTIONS, 15, TimeUnit.SECONDS,
      new SynchronousQueue<>(), runnable -> daemon(runnable, "workbench-katana-proxy"));
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "workbench-katana-proxy-deadline"));
  private volatile boolean closed;

  ScopeKatanaProxy(String origin, Predicate<String> liveAllowed, Fetcher fetch, Consumer<Blocked> blocked) throws IOException {
    URI parsed;
    try {
      parsed = URI.create(Objects.requireNonNull(origin));
      if (parsed.getUserInfo() != null || parsed.getRawQuery() != null || parsed.getFragment() != null
          || parsed.getHost() == null || parsed.getHost().contains("%") || parsed.getPort() == 0 || parsed.getPort() > 65535
          || !(parsed.getRawPath() == null || parsed.getRawPath().isEmpty() || parsed.getRawPath().equals("/")))
        throw new IllegalArgumentException();
      this.origin = canonicalOrigin(parsed);
      this.host = parsed.getHost().replace("[", "").replace("]", "");
    } catch (RuntimeException error) { throw new IllegalArgumentException("Katana 프록시에는 고정 HTTP(S) Origin이 필요합니다."); }
    allowed = Objects.requireNonNull(liveAllowed); fetcher = Objects.requireNonNull(fetch); this.blocked = Objects.requireNonNull(blocked);
    byte[] secret = new byte[32]; RANDOM.nextBytes(secret);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret); Arrays.fill(secret, (byte) 0);
    authentication = ("Basic " + Base64.getEncoder().encodeToString(("wb:" + token).getBytes(StandardCharsets.US_ASCII))).getBytes(StandardCharsets.US_ASCII);
    try { tls = tlsFor(host).context(); }
    catch (GeneralSecurityException error) { throw new IOException("Katana 임시 TLS 인증서를 만들지 못했습니다.", error); }
    listener = new ServerSocket();
    try { listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), MAX_CONNECTIONS); }
    catch (IOException error) { listener.close(); throw error; }
    address = "http://wb:" + token + "@127.0.0.1:" + listener.getLocalPort();
    acceptor = daemon(this::accept, "workbench-katana-proxy-accept"); acceptor.start();
  }
  String address() { return address; }
  @Override public String toString() { return "ScopeKatanaProxy[loopback, credentials hidden, closed=" + closed + "]"; }

  private void accept() {
    while (!closed) {
      Socket socket = null;
      try {
        socket = listener.accept();
        if (closed || !socket.getInetAddress().isLoopbackAddress() || sockets.size() >= MAX_CONNECTIONS) { socket.close(); continue; }
        socket.setSoTimeout(READ_TIMEOUT_MILLIS); sockets.add(socket);
        Socket accepted = socket;
        ScheduledFuture<?> deadline = deadlines.schedule(() -> closeSocket(accepted), CONNECTION_MILLIS, TimeUnit.MILLISECONDS);
        try { workers.execute(() -> serve(accepted, deadline)); }
        catch (RejectedExecutionException error) { deadline.cancel(false); sockets.remove(socket); socket.close(); }
      } catch (IOException | RejectedExecutionException error) {
        if (socket != null) { sockets.remove(socket); closeSocket(socket); }
        if (closed) return;
      }
    }
  }

  private void serve(Socket socket, ScheduledFuture<?> deadline) {
    String target = "", method = "OTHER";
    try {
      Request request = readRequest(socket.getInputStream()); method = request.method(); target = attempted(request, false);
      if (!authenticated(request)) throw new Rejected(407, "로컬 프록시 인증 실패");
      framing(request);
      if (request.method().equals("CONNECT")) {
        if (!origin.startsWith("https://") || !authority(request.target(), "https", true).equals(origin))
          throw new Rejected(403, "CONNECT Origin 또는 포트 범위 밖");
        String outerHost = request.single("host");
        if (outerHost != null && !authority(outerHost, "https", false).equals(origin)) throw new Rejected(403, "CONNECT Host 불일치");
        socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
        try (SSLSocket secure = (SSLSocket) tls.getSocketFactory().createSocket(socket, "127.0.0.1", socket.getPort(), true)) {
          secure.setUseClientMode(false); secure.setNeedClientAuth(false); secure.setSoTimeout(READ_TIMEOUT_MILLIS);
          SSLParameters parameters = secure.getSSLParameters(); parameters.setApplicationProtocols(new String[] {"http/1.1"}); secure.setSSLParameters(parameters);
          secure.startHandshake();
          try {
            Request inner = readRequest(secure.getInputStream()); method = inner.method(); target = attempted(inner, true); framing(inner);
            String validated = target(inner, true);
            forward(inner, validated, secure.getOutputStream());
          } catch (Rejected error) { reject(target, method, error); writeFailure(secure.getOutputStream(), error.status); }
        }
      } else {
        String validated = target(request, false);
        forward(request, validated, socket.getOutputStream());
      }
    } catch (Rejected error) {
      reject(target, method, error);
      try { writeFailure(socket.getOutputStream(), error.status); } catch (IOException ignored) { }
    } catch (IOException error) {
      if (!closed) report(target, method, "로컬 프록시 연결·TLS·입력 제한으로 미완료");
    } finally { deadline.cancel(false); sockets.remove(socket); closeSocket(socket); }
  }

  /** Display-only attempted target; it is never passed to Fetcher or treated as validated. */
  private String attempted(Request request, boolean tunnel) {
    if (request.method().equals("CONNECT")) {
      try { return authority(request.target(), "https", true) + "/"; } catch (Rejected ignored) { return ""; }
    }
    String raw = request.target();
    return tunnel && raw.startsWith("/") && !raw.startsWith("//") ? origin + raw : raw;
  }

  private void forward(Request request, String target, OutputStream output) throws IOException {
    if (closed || !liveAllowed(target)) throw new Rejected(403, "전송 직전 live Scope 범위 밖 또는 실행 종료");
    Response response;
    try { response = fetcher.fetch(target, request.method()); }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new Rejected(503, "Katana 전송 취소·미완료"); }
    catch (Exception error) { throw new Rejected(502, "Montoya 전송·실행 제한으로 미완료"); }
    if (closed || !liveAllowed(target)) throw new Rejected(403, "응답 전달 전 live Scope 범위 밖 또는 실행 종료");
    if (response == null || response.status() < 200 || response.status() > 599) throw new Rejected(502, "일반 HTTP 최종 응답이 아님");
    byte[] body = request.method().equals("HEAD") || Set.of(204, 205, 304).contains(response.status()) ? new byte[0] : response.body();
    Set<String> removed = new HashSet<>(HOP); removed.add("content-length");
    for (var header : response.headers()) if (header.name().equalsIgnoreCase("connection"))
      for (String name : header.value().split(",")) removed.add(name.strip().toLowerCase(Locale.ROOT));
    StringBuilder headers = new StringBuilder("HTTP/1.1 " + response.status() + " Workbench\r\n");
    if (response.headers().size() > 256) throw new Rejected(502, "응답 헤더 개수 제한");
    for (var header : response.headers()) {
      if (!headerName(header.name()) || !headerValue(header.value())) throw new Rejected(502, "응답 헤더 형식 제한");
      if (!removed.contains(header.name().toLowerCase(Locale.ROOT))) headers.append(header.name()).append(": ").append(header.value()).append("\r\n");
      if (headers.length() > MAX_HEADER_BYTES - 128) throw new Rejected(502, "응답 헤더 크기 제한");
    }
    headers.append("Content-Length: ").append(body.length).append("\r\nConnection: close\r\n\r\n");
    output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1)); output.write(body); output.flush();
  }

  private String target(Request request, boolean tunnel) throws Rejected {
    if (!Set.of("GET", "HEAD").contains(request.method())) throw new Rejected(405, "GET/HEAD 외 메서드는 자동 실행하지 않음");
    String hostHeader = request.single("host");
    if (hostHeader == null || !authority(hostHeader, origin.startsWith("https://") ? "https" : "http", false).equals(origin))
      throw new Rejected(403, "요청 Host Origin 또는 포트 범위 밖");
    String raw = request.target();
    if (tunnel) {
      if (!raw.startsWith("/") || raw.startsWith("//")) throw new Rejected(400, "TLS 내부 요청은 고정 Origin의 경로만 허용");
      raw = origin + raw;
    }
    String normalized = ScopeInspectionRunner.normalized(raw);
    if (normalized == null || !canonicalOrigin(URI.create(normalized)).equals(origin)) throw new Rejected(403, "요청 URL·경로·Origin 경계 제외");
    if (!ScopeInspectionRunner.readOnlyUrl(normalized)) throw new Rejected(403, "변경 동작·대용량 경로 자동 실행 제외");
    return normalized;
  }
  private boolean liveAllowed(String target) { try { return allowed.test(target); } catch (RuntimeException ignored) { return false; } }
  private boolean authenticated(Request request) {
    String value = request.single("proxy-authorization");
    return value != null && MessageDigest.isEqual(authentication, value.getBytes(StandardCharsets.US_ASCII));
  }
  private static void framing(Request request) throws Rejected {
    if (request.headers().containsKey("transfer-encoding") || request.headers().containsKey("upgrade")
        || request.headers().getOrDefault("connection", List.of()).stream().anyMatch(v -> Arrays.stream(v.split(",")).anyMatch(n -> n.strip().equalsIgnoreCase("upgrade"))))
      throw new Rejected(400, "요청 본문·Upgrade·중첩 프로토콜 제외");
    var lengths = request.headers().get("content-length");
    if (lengths != null && (lengths.size() != 1 || !lengths.get(0).equals("0"))) throw new Rejected(400, "요청 본문 또는 중복 길이 헤더 제외");
    if (request.headers().getOrDefault("host", List.of()).size() > 1 || request.headers().getOrDefault("proxy-authorization", List.of()).size() > 1)
      throw new Rejected(400, "중복 라우팅·인증 헤더 제외");
  }
  private static Request readRequest(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int tail = 0, previous = -1;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (bytes.size() < MAX_HEADER_BYTES) {
      if (System.nanoTime() > deadline) throw new Rejected(408, "요청 헤더 시간 제한");
      int next = input.read(); if (next < 0) throw new Rejected(400, "불완전한 요청 헤더");
      if (next == '\n' && previous != '\r' || previous == '\r' && next != '\n') throw new Rejected(400, "CRLF 요청 경계 형식 오류");
      bytes.write(next); tail = (tail << 8) | next; previous = next;
      if (bytes.size() >= 4 && tail == 0x0d0a0d0a) break;
    }
    if (tail != 0x0d0a0d0a) throw new Rejected(431, "요청 헤더 크기 제한");
    String[] lines = bytes.toString(StandardCharsets.ISO_8859_1).split("\r\n", -1);
    String[] first = lines[0].split(" ", -1);
    if (first.length != 3 || first[0].isEmpty() || first[1].isEmpty() || first[1].length() > 8192
        || !Set.of("HTTP/1.1", "HTTP/1.0").contains(first[2]) || first[1].chars().anyMatch(c -> c <= 32 || c >= 127)
        || !first[0].matches("[A-Z]{1,16}")) throw new Rejected(400, "요청 줄 형식 오류");
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (int i = 1; i < lines.length - 2; i++) {
      String line = lines[i]; int colon = line.indexOf(':');
      if (i > 128 || colon <= 0 || !headerName(line.substring(0, colon)) || !headerValue(line.substring(colon + 1)))
        throw new Rejected(400, "요청 헤더 형식 제한");
      String name = line.substring(0, colon).toLowerCase(Locale.ROOT), value = line.substring(colon + 1).strip();
      headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
    }
    return new Request(first[0], first[1], headers);
  }
  private static boolean headerName(String value) { return value.length() <= 128 && value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"); }
  private static boolean headerValue(String value) { return value.length() <= 8192 && value.chars().noneMatch(c -> c == 127 || c < 32 && c != '\t' || c > 255); }
  private static String authority(String value, String scheme, boolean explicitPort) throws Rejected {
    try {
      if (value.isEmpty() || value.chars().anyMatch(c -> c <= 32 || c >= 127) || value.indexOf('%') >= 0) throw new IllegalArgumentException();
      URI uri = URI.create(scheme + "://" + value);
      if (uri.getUserInfo() != null || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
          || !Objects.requireNonNullElse(uri.getRawPath(), "").isEmpty() || explicitPort && uri.getPort() < 1
          || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
      return canonicalOrigin(uri);
    } catch (RuntimeException error) { throw new Rejected(403, "라우팅 authority 형식·포트 오류"); }
  }
  private static String canonicalOrigin(URI uri) {
    String scheme = Objects.requireNonNullElse(uri.getScheme(), "").toLowerCase(Locale.ROOT);
    if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
    String name = uri.getHost().toLowerCase(Locale.ROOT);
    if (name.indexOf(':') >= 0 && !name.startsWith("[")) name = "[" + name + "]";
    int port = uri.getPort();
    return scheme + "://" + name + (port < 0 || scheme.equals("http") && port == 80 || scheme.equals("https") && port == 443 ? "" : ":" + port);
  }
  private void reject(String target, String method, Rejected error) { report(target, method, error.reason); }
  private void report(String target, String method, String reason) { try { blocked.accept(new Blocked(target, method, reason)); } catch (RuntimeException ignored) { } }
  private static void writeFailure(OutputStream output, int status) throws IOException {
    byte[] body = "Workbench Katana transport blocked; inspect coverage.\n".getBytes(StandardCharsets.US_ASCII);
    String header = "HTTP/1.1 " + status + " Blocked\r\nContent-Type: text/plain\r\nCache-Control: no-store\r\n"
        + (status == 407 ? "Proxy-Authenticate: Basic realm=\"Workbench local crawl\"\r\n" : "")
        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
    output.write(header.getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
  }
  private static Thread daemon(Runnable runnable, String name) { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; }
  private static void closeSocket(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
  @Override public void close() {
    if (closed) return; closed = true;
    try { listener.close(); } catch (IOException ignored) { }
    for (Socket socket : sockets) closeSocket(socket);
    workers.shutdownNow(); deadlines.shutdownNow(); Arrays.fill(authentication, (byte) 0);
    acceptor.interrupt();
  }

  /** An ephemeral, non-CA server certificate built entirely with Java 17 public APIs. */
  static TlsMaterial tlsFor(String host) throws GeneralSecurityException {
    if (host == null || host.isBlank() || host.length() > 253 || host.contains("%")) throw new GeneralSecurityException("TLS host 형식 오류");
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048, RANDOM); KeyPair pair = generator.generateKeyPair();
    byte[] algorithm = sequence(oid("1.2.840.113549.1.1.11"), der(5, new byte[0]));
    byte[] name = new X500Principal("CN=Workbench Katana temporary").getEncoded();
    Instant now = Instant.now();
    DateTimeFormatter time = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    byte[] validity = sequence(der(23, time.format(now.minusSeconds(60)).getBytes(StandardCharsets.US_ASCII)), der(23, time.format(now.plusSeconds(86400)).getBytes(StandardCharsets.US_ASCII)));
    byte[] san;
    try {
      if (host.indexOf(':') >= 0) san = der(0x87, InetAddress.getByName(host.replace("[", "").replace("]", "")).getAddress());
      else if (host.matches("[0-9]+(?:\\.[0-9]+){3}")) {
        String[] parts = host.split("\\."); byte[] ip = new byte[4];
        for (int i = 0; i < 4; i++) { int value = Integer.parseInt(parts[i]); if (value > 255) throw new IllegalArgumentException(); ip[i] = (byte) value; }
        san = der(0x87, ip);
      } else {
        if (!host.matches("(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) throw new IllegalArgumentException();
        san = der(0x82, host.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
      }
    } catch (IOException | RuntimeException error) { throw new GeneralSecurityException("TLS host 형식 오류", error); }
    byte[] extensions = der(0xa3, sequence(
        extension("2.5.29.19", true, sequence()),
        extension("2.5.29.15", true, der(3, new byte[] {5, (byte) 0xa0})),
        extension("2.5.29.37", false, sequence(oid("1.3.6.1.5.5.7.3.1"))),
        extension("2.5.29.17", false, sequence(san))));
    byte[] tbs = sequence(der(0xa0, der(2, new byte[] {2})), der(2, new BigInteger(128, RANDOM).add(BigInteger.ONE).toByteArray()), algorithm, name, validity, name, pair.getPublic().getEncoded(), extensions);
    Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(pair.getPrivate(), RANDOM); signer.update(tbs);
    byte[] signature = signer.sign(), bits = new byte[signature.length + 1]; System.arraycopy(signature, 0, bits, 1, signature.length);
    X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(sequence(tbs, algorithm, der(3, bits))));
    certificate.checkValidity(); certificate.verify(pair.getPublic());
    char[] password = new char[32]; for (int i = 0; i < password.length; i++) password[i] = (char) ('a' + RANDOM.nextInt(26));
    try {
      KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, password); store.setKeyEntry("temporary", pair.getPrivate(), password, new java.security.cert.Certificate[] {certificate});
      KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(store, password);
      SSLContext context = SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(), null, RANDOM);
      return new TlsMaterial(context, certificate);
    } catch (IOException error) { throw new GeneralSecurityException("임시 TLS 메모리 저장소 오류", error); }
    finally { Arrays.fill(password, '\0'); }
  }
  private static byte[] extension(String oid, boolean critical, byte[] body) { return critical ? sequence(oid(oid), der(1, new byte[] {(byte) 0xff}), der(4, body)) : sequence(oid(oid), der(4, body)); }
  private static byte[] sequence(byte[]... parts) { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); for (byte[] part : parts) bytes.writeBytes(part); return der(48, bytes.toByteArray()); }
  private static byte[] der(int tag, byte[] body) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(tag);
    if (body.length < 128) bytes.write(body.length);
    else { int length = body.length, count = 0; for (int value = length; value > 0; value >>>= 8) count++; bytes.write(0x80 | count); for (int shift = (count - 1) * 8; shift >= 0; shift -= 8) bytes.write((length >>> shift) & 255); }
    bytes.writeBytes(body); return bytes.toByteArray();
  }
  private static byte[] oid(String value) {
    String[] parts = value.split("\\."); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    oidNumber(bytes, Long.parseLong(parts[0]) * 40 + Long.parseLong(parts[1]));
    for (int i = 2; i < parts.length; i++) oidNumber(bytes, Long.parseLong(parts[i]));
    return der(6, bytes.toByteArray());
  }
  private static void oidNumber(ByteArrayOutputStream bytes, long value) {
    int groups = 1; for (long remaining = value >>> 7; remaining > 0; remaining >>>= 7) groups++;
    for (int shift = (groups - 1) * 7; shift >= 0; shift -= 7) bytes.write((int) ((value >>> shift) & 127) | (shift > 0 ? 128 : 0));
  }
}
