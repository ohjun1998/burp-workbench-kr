package kr.workbench;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.*;

/** Real loopback HTTP/CONNECT/TLS; target transport is an isolated recording Fetcher. */
public final class ScopeKatanaProxyTest extends WorkbenchTest {
  record Wire(int status, Map<String, String> headers, byte[] body) {}
  static boolean allowed(String origin, String url, AtomicBoolean enabled) {
    try { String path = URI.create(url).getPath(); return enabled.get() && url.startsWith(origin + "/")
        && (path.equals("/app") || path.startsWith("/app/")) && !path.startsWith("/app/private"); }
    catch (RuntimeException error) { return false; }
  }
  static String auth(ScopeKatanaProxy proxy) {
    return "Basic " + Base64.getEncoder().encodeToString(URI.create(proxy.address()).getRawUserInfo().getBytes(StandardCharsets.US_ASCII));
  }
  static Socket socket(ScopeKatanaProxy proxy) throws IOException {
    Socket socket = new Socket(); socket.connect(new InetSocketAddress("127.0.0.1", URI.create(proxy.address()).getPort()), 2000); socket.setSoTimeout(5000); return socket;
  }
  static boolean ownedTransportClosed(ScopeKatanaProxy proxy) throws Exception {
    var listenerField = ScopeKatanaProxy.class.getDeclaredField("listener"); listenerField.setAccessible(true);
    var acceptorField = ScopeKatanaProxy.class.getDeclaredField("acceptor"); acceptorField.setAccessible(true);
    ServerSocket listener = (ServerSocket)listenerField.get(proxy);
    Thread acceptor = (Thread)acceptorField.get(proxy);
    acceptor.join(2000);
    return listener.isClosed() && !acceptor.isAlive();
  }
  static String request(ScopeKatanaProxy proxy, String method, String url, String host, String extra) {
    return method + " " + url + " HTTP/1.1\r\nHost: " + host + "\r\nProxy-Authorization: " + auth(proxy) + "\r\n" + extra + "\r\n";
  }
  static Wire http(ScopeKatanaProxy proxy, String request) throws Exception {
    try (Socket socket = socket(proxy)) {
      socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1)); socket.getOutputStream().flush();
      return read(socket.getInputStream(), false);
    }
  }
  static Wire read(InputStream input, boolean head) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int tail = 0;
    while (bytes.size() < 64 * 1024) { int next = input.read(); if (next < 0) throw new EOFException("no complete proxy response"); bytes.write(next); tail = tail << 8 | next; if (tail == 0x0d0a0d0a) break; }
    String[] lines = bytes.toString(StandardCharsets.ISO_8859_1).split("\r\n");
    int status = Integer.parseInt(lines[0].split(" ")[1]); Map<String, String> headers = new HashMap<>();
    for (int i = 1; i < lines.length; i++) { int colon = lines[i].indexOf(':'); if (colon > 0) headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).strip()); }
    int length = head || status == 200 && !headers.containsKey("content-length") ? 0 : Integer.parseInt(headers.getOrDefault("content-length", "0"));
    byte[] body = input.readNBytes(length); if (body.length != length) throw new EOFException("truncated proxy response"); return new Wire(status, headers, body);
  }
  static SSLSocket tunnel(ScopeKatanaProxy proxy, String authority, SSLContext client) throws Exception {
    Socket raw = socket(proxy);
    try {
      raw.getOutputStream().write(request(proxy, "CONNECT", authority, authority, "").getBytes(StandardCharsets.US_ASCII)); raw.getOutputStream().flush();
      if (read(raw.getInputStream(), false).status() != 200) throw new IOException("CONNECT rejected");
      SSLSocket secure = (SSLSocket) client.getSocketFactory().createSocket(raw, "secure.invalid", 443, true);
      secure.setSoTimeout(5000); SSLParameters settings = secure.getSSLParameters(); settings.setEndpointIdentificationAlgorithm("HTTPS"); settings.setApplicationProtocols(new String[] {"h2", "http/1.1"}); secure.setSSLParameters(settings); secure.startHandshake();
      return secure;
    } catch (Exception error) { raw.close(); throw error; }
  }
  static SSLContext testClient(AtomicReference<X509Certificate> observed) throws Exception {
    // This manager belongs only to these loopback test sockets; no default context or CA store changes.
    X509TrustManager trust = new X509TrustManager() {
      public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      public void checkClientTrusted(X509Certificate[] chain, String type) throws java.security.cert.CertificateException { throw new java.security.cert.CertificateException("server-only fixture"); }
      public void checkServerTrusted(X509Certificate[] chain, String type) throws java.security.cert.CertificateException {
        if (chain.length != 1 || chain[0].getBasicConstraints() != -1 || !chain[0].getSubjectX500Principal().getName().contains("Workbench Katana temporary")) throw new java.security.cert.CertificateException("not the ephemeral loopback leaf");
        try { chain[0].checkValidity(); chain[0].verify(chain[0].getPublicKey()); observed.set(chain[0]); }
        catch (Exception error) { throw new java.security.cert.CertificateException(error); }
      }
    };
    SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[] {trust}, null); return context;
  }
  static byte[] gzip(String text) throws IOException { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(text.getBytes(StandardCharsets.UTF_8)); } return bytes.toByteArray(); }
  public static void main(String[] args) throws Exception {
    String origin = "http://target.invalid:8080"; AtomicBoolean enabled = new AtomicBoolean(true);
    List<String> calls = new CopyOnWriteArrayList<>(); List<ScopeKatanaProxy.Blocked> blocked = new CopyOnWriteArrayList<>();
    byte[] compressed = gzip("<html><a href='/app/next'>다음</a></html>");
    try (var proxy = new ScopeKatanaProxy(origin, url -> allowed(origin, url, enabled), (url, method) -> {
      calls.add(method + " " + url);
      if (url.endsWith("/app/redirect")) return new ScopeKatanaProxy.Response(302, List.of(new ScopeKatanaProxy.Header("Location", "http://outside.invalid/escape?token=PRIVATE_REDIRECT")), new byte[0]);
      if (url.endsWith("/app/error")) throw new IOException("PRIVATE_FETCH_ERROR");
      return new ScopeKatanaProxy.Response(200, List.of(new ScopeKatanaProxy.Header("Content-Type", "text/html"), new ScopeKatanaProxy.Header("Content-Encoding", "gzip"),
          new ScopeKatanaProxy.Header("Content-Length", "9999"), new ScopeKatanaProxy.Header("Transfer-Encoding", "chunked"), new ScopeKatanaProxy.Header("Connection", "keep-alive, X-Hop"),
          new ScopeKatanaProxy.Header("X-Hop", "DROP_ME"), new ScopeKatanaProxy.Header("Proxy-Authenticate", "PRIVATE_PROXY_CHALLENGE")), compressed);
    }, blocked::add)) {
      URI address = URI.create(proxy.address());
      check(address.getHost().equals("127.0.0.1") && address.getPort() > 0 && address.getRawUserInfo().startsWith("wb:"), "proxy binds an ephemeral IPv4 loopback endpoint with a per-run capability");
      check(!proxy.toString().contains(address.getRawUserInfo()), "proxy diagnostics hide the local authentication capability");
      check(http(proxy, "GET " + origin + "/app HTTP/1.1\r\nHost: target.invalid:8080\r\n\r\n").status() == 407 && calls.isEmpty(), "unauthenticated local clients cannot dispatch a target request");
      var good = http(proxy, request(proxy, "GET", origin + "/app?q=ACTUAL_QUERY", "target.invalid:8080", "Authorization: Bearer CLIENT_PRIVATE\r\nCookie: SID=CLIENT_PRIVATE\r\n"));
      check(good.status() == 200 && calls.equals(List.of("GET " + origin + "/app?q=ACTUAL_QUERY")), "authenticated HTTP proxy request reaches only the canonical URL and method Fetcher");
      check(Arrays.equals(good.body(), compressed) && good.headers().get("content-encoding").equals("gzip"), "raw compressed response bytes and encoding reach the crawler unchanged");
      check(good.headers().get("content-length").equals(Integer.toString(compressed.length)) && good.headers().get("connection").equals("close"), "proxy rebuilds bounded one-response HTTP framing from actual bytes");
      check(!good.headers().containsKey("transfer-encoding") && !good.headers().containsKey("x-hop") && !good.headers().containsKey("proxy-authenticate"), "hop-by-hop and nominated headers cannot cross the proxy boundary");
      check(!calls.toString().contains("CLIENT_PRIVATE") && !calls.toString().contains(address.getRawUserInfo()), "crawler credentials and proxy capabilities are never forwarded to Fetcher");
      int accepted = calls.size();
      check(http(proxy, request(proxy, "POST", origin + "/app/report?token=PRIVATE_POST_QUERY", "target.invalid:8080", "")).status() == 405
          && calls.size() == accepted && blocked.stream().anyMatch(item -> item.method().equals("POST") && item.url().equals(origin + "/app/report")),
          "rejected POST retains its attempted URL as masked display metadata without dispatch");
      check(http(proxy, request(proxy, "GET", origin + "/outside?token=PRIVATE_SCOPE_QUERY", "target.invalid:8080", "")).status() == 403
          && calls.size() == accepted && blocked.stream().anyMatch(item -> item.url().equals(origin + "/outside")),
          "live Scope rejection retains the attempted path without secret query values");
      check(http(proxy, request(proxy, "GET", "http://burpsuite/?token=PRIVATE_PROBE_QUERY", "burpsuite", "")).status() == 403
          && calls.size() == accepted && blocked.stream().anyMatch(item -> item.url().equals("http://burpsuite/")),
          "official proxy diagnostic probe remains identifiable after Origin rejection without target traffic");
      check(!blocked.toString().contains("PRIVATE_POST_QUERY") && !blocked.toString().contains("PRIVATE_SCOPE_QUERY") && !blocked.toString().contains("PRIVATE_PROBE_QUERY"),
          "attempt provenance never retains rejected query credentials");
      for (String attack : List.of(
          request(proxy, "GET", "http://outside.invalid/app", "outside.invalid", ""),
          request(proxy, "GET", "http://target.invalid:8081/app", "target.invalid:8081", ""),
          request(proxy, "GET", origin + "/app", "outside.invalid", ""),
          request(proxy, "GET", origin + "/apple", "target.invalid:8080", ""),
          request(proxy, "GET", origin + "/app/private/data", "target.invalid:8080", ""),
          request(proxy, "GET", origin + "/app/../escape", "target.invalid:8080", ""),
          request(proxy, "GET", origin + "/app/%2e%2e/escape", "target.invalid:8080", ""),
          request(proxy, "GET", origin + "/app/%252e%252e/escape", "target.invalid:8080", ""),
          request(proxy, "GET", "http://user:PRIVATE_URL_PASSWORD@target.invalid:8080/app", "target.invalid:8080", ""),
          request(proxy, "GET", origin + "/app#fragment", "target.invalid:8080", ""),
          request(proxy, "GET", origin + "/app/delete/all", "target.invalid:8080", ""),
          request(proxy, "POST", origin + "/app", "target.invalid:8080", ""),
          request(proxy, "GET", origin + "/app", "target.invalid:8080", "Content-Length: 1\r\n") + "x",
          request(proxy, "GET", origin + "/app", "target.invalid:8080", "Transfer-Encoding: chunked\r\nContent-Length: 0\r\n"),
          request(proxy, "GET", origin + "/app", "target.invalid:8080", "Upgrade: websocket\r\n"),
          request(proxy, "GET", origin + "/app", "target.invalid:8080", "Host: target.invalid:8080\r\n")))
        check(http(proxy, attack).status() >= 400 && calls.size() == accepted, "hostile authority, path, method or framing is blocked before any target dispatch");
      check(http(proxy, request(proxy, "GET", origin + "/app", "target.invalid:8080", "X-Long: " + "x".repeat(ScopeKatanaProxy.MAX_HEADER_BYTES) + "\r\n")).status() == 431, "request headers have an explicit 32 KiB limit");
      var redirect = http(proxy, request(proxy, "GET", origin + "/app/redirect", "target.invalid:8080", ""));
      check(redirect.status() == 302 && redirect.headers().get("location").startsWith("http://outside.invalid/"), "the proxy returns the actual redirect instead of following it itself");
      accepted = calls.size();
      check(http(proxy, request(proxy, "GET", redirect.headers().get("location"), "outside.invalid", "")).status() == 403 && calls.size() == accepted, "a crawler redirect to another Origin is checked as a new blocked request");
      var failure = http(proxy, request(proxy, "GET", origin + "/app/error", "target.invalid:8080", ""));
      check(failure.status() == 502 && !new String(failure.body(), StandardCharsets.US_ASCII).contains("PRIVATE_FETCH_ERROR"), "transport failures are explicit and do not leak original exception values");
      enabled.set(false); accepted = calls.size();
      check(http(proxy, request(proxy, "GET", origin + "/app", "target.invalid:8080", "")).status() == 403 && calls.size() == accepted, "live Scope revocation blocks requests after the proxy has started");
      check(!blocked.toString().contains("PRIVATE_URL_PASSWORD") && !blocked.toString().contains("PRIVATE_REDIRECT") && !blocked.toString().contains("CLIENT_PRIVATE"), "blocked observations remain masked metadata");
    }

    String secureOrigin = "https://secure.invalid"; enabled.set(true); calls.clear(); blocked.clear();
    AtomicReference<X509Certificate> certificate = new AtomicReference<>(); SSLContext client = testClient(certificate);
    var secureProxy = new ScopeKatanaProxy(secureOrigin, url -> allowed(secureOrigin, url, enabled), (url, method) -> {
      calls.add(method + " " + url); return new ScopeKatanaProxy.Response(200, List.of(new ScopeKatanaProxy.Header("Content-Type", "application/json")), "{\"actual\":true}".getBytes(StandardCharsets.UTF_8));
    }, blocked::add);
    try {
      check(http(secureProxy, request(secureProxy, "CONNECT", "outside.invalid:443", "outside.invalid:443", "")).status() == 403 && calls.isEmpty(), "CONNECT cannot become a tunnel to a different host");
      check(http(secureProxy, request(secureProxy, "CONNECT", "secure.invalid:444", "secure.invalid:444", "")).status() == 403 && calls.isEmpty(), "CONNECT must match the exact configured TLS port");
      try (SSLSocket socket = tunnel(secureProxy, "secure.invalid:443", client)) {
        check(socket.getApplicationProtocol().equals("http/1.1"), "TLS termination negotiates HTTP/1.1 instead of an unparsed HTTP/2 tunnel");
        socket.getOutputStream().write("GET /app/me?actual=VALUE HTTP/1.1\r\nHost: secure.invalid\r\nAuthorization: CLIENT_PRIVATE\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
        var response = read(socket.getInputStream(), false);
        check(response.status() == 200 && calls.equals(List.of("GET " + secureOrigin + "/app/me?actual=VALUE")), "HTTPS is terminated locally and the real inner method and path are sent only through Fetcher");
        check(new String(response.body(), StandardCharsets.UTF_8).contains("actual"), "the actual HTTPS response body reaches the crawler");
      }
      check(certificate.get() != null && certificate.get().getBasicConstraints() == -1 && certificate.get().getNotAfter().getTime() - certificate.get().getNotBefore().getTime() < 2L * 86400000,
          "temporary proxy certificate is a short-lived non-CA leaf");
      check(certificate.get().getSubjectAlternativeNames().stream().anyMatch(san -> san.get(0).equals(2) && san.get(1).equals("secure.invalid")), "temporary certificate SAN is bound to the configured Origin host");
      int accepted = calls.size();
      for (String inner : List.of("GET /app/me HTTP/1.1\r\nHost: outside.invalid\r\n\r\n",
          "GET //outside.invalid/app HTTP/1.1\r\nHost: secure.invalid\r\n\r\n",
          "POST /app/me HTTP/1.1\r\nHost: secure.invalid\r\nContent-Length: 0\r\n\r\n",
          "CONNECT secure.invalid:443 HTTP/1.1\r\nHost: secure.invalid\r\n\r\n")) {
        try (SSLSocket socket = tunnel(secureProxy, "secure.invalid:443", client)) {
          socket.getOutputStream().write(inner.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
          check(read(socket.getInputStream(), false).status() >= 400 && calls.size() == accepted, "TLS inner Host, network-path, method and nested CONNECT cannot escape policy");
        }
      }
      try (SSLSocket socket = tunnel(secureProxy, "secure.invalid:443", client)) {
        socket.getOutputStream().write("HEAD /app/me HTTP/1.1\r\nHost: secure.invalid\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
        var response = read(socket.getInputStream(), true); check(response.status() == 200 && response.body().length == 0 && calls.get(calls.size() - 1).startsWith("HEAD "), "HEAD remains HEAD and never invents a response body");
      }
      accepted = calls.size();
      try (SSLSocket socket = tunnel(secureProxy, "secure.invalid:443", client)) {
        enabled.set(false); socket.getOutputStream().write("GET /app/me HTTP/1.1\r\nHost: secure.invalid\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
        check(read(socket.getInputStream(), false).status() == 403 && calls.size() == accepted, "an authenticated open CONNECT cannot bypass later live Scope revocation");
      }
      // Restore a valid Scope: rejection below must come from shutdown, not the preceding revocation.
      enabled.set(true); accepted = calls.size();
      String afterCloseRequest = request(secureProxy, "CONNECT", "secure.invalid:443", "secure.invalid:443", "");
      try (Socket idle = socket(secureProxy); Socket pending = socket(secureProxy)) {
        secureProxy.close(); boolean ended;
        try { ended = idle.getInputStream().read() < 0; }
        catch (SocketTimeoutException timeout) { ended = false; }
        catch (IOException expected) { ended = true; }
        check(ended, "close terminates accepted idle sockets as well as the listener");
        boolean rejected;
        try {
          pending.getOutputStream().write(afterCloseRequest.getBytes(StandardCharsets.US_ASCII)); pending.getOutputStream().flush();
          rejected = read(pending.getInputStream(), false).status() >= 400;
        } catch (SocketTimeoutException timeout) { rejected = false; }
        catch (IOException expected) { rejected = true; }
        check(rejected && calls.size() == accepted, "shutdown rejects an authenticated valid-Scope request on an established socket without target dispatch");
      }
      // A released ephemeral port may already belong to another local listener. Check this proxy's
      // owned transport instead of assuming a new TCP connection proves the closed proxy accepted it.
      check(ownedTransportClosed(secureProxy), "close releases its owned listener and terminates its acceptor within the bounded wait");
    } finally { secureProxy.close(); }
    System.out.println("ScopeKatanaProxyTest: " + checks + " checks passed (loopback HTTP/CONNECT/TLS only; no target network or CA installation)");
  }
}
