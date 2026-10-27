package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated loopback application and a wire-preserving Montoya adapter for integrated tests. */
final class ScopeInspectionFixture extends WorkbenchTest implements AutoCloseable {
  static final String API_SECRET = "SCOPE_PRIVATE_API_VALUE_0123456789abcdef";
  static final String SESSION = "SID=SCOPE_ALICE_SESSION";
  static final String MISSING = "<html><title>Missing fixture route</title><body>Not found in this application</body></html>";
  static final String FORM_SHELL = "<!doctype html><html><title>Account portal</title><body><form action='/app/register'>"
      + "<input name='password' type='password'><button>Register</button></form><script src='/app/discovery.js'></script></body></html>";
  record Visit(String method, String path, String account) {}
  final List<Visit> visits = new CopyOnWriteArrayList<>();
  final AtomicInteger revision = new AtomicInteger(1);
  final AtomicBoolean rateLimited = new AtomicBoolean();
  final AtomicBoolean exposedEnv = new AtomicBoolean(), declaredDiscovery = new AtomicBoolean(), spaFallback = new AtomicBoolean();
  final HttpServer server;
  final ExecutorService serverWorkers = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "scope-inspection-fixture"); t.setDaemon(true); return t;
  });
  final String origin;
  final MontoyaApi api;

  ScopeInspectionFixture() throws Exception {
    installFactory();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
    server.setExecutor(serverWorkers);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      String cookie = exchange.getRequestHeaders().getFirst("Cookie");
      boolean account = SESSION.equals(cookie);
      visits.add(new Visit(exchange.getRequestMethod(), path, account ? "alice" : "anonymous"));
      int code = 200;
      String type = "text/html; charset=UTF-8";
      String body;
      String missing = spaFallback.get() ? FORM_SHELL : MISSING;
      switch (path) {
        case "/app/", "/app" -> body = "<!doctype html><html><title>Scope fixture</title><nav>"
            + "<a href='/app/config'>Configuration</a><a href='/app/api-docs'>API specification</a>"
            + "<a href='/app/private'>Private documents</a><a href='/app/redirect'>Redirect</a>"
            + "<a href='/outside/sensitive'>Outside application</a><a href='/app/logout'>Logout</a>"
            + "</nav><script src='/app/app.js'></script><main>Read-only fixture " + revision.get() + "</main></html>";
        case "/app/api-docs" -> {
          type = "application/json";
          body = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Scope fixture\",\"version\":\"1\"},\"paths\":{"
              + "\"/app/api/me\":{\"get\":{}},\"/app/api/documented\":{\"get\":{}},"
              + "\"/app/api/items/{id}\":{\"get\":{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"}}]}},"
              + "\"/app/api/write-only\":{\"post\":{}}}}";
        }
        case "/app/app.js" -> {
          type = "application/javascript";
          body = "fetch('/app/api/me');\nfetch('/app/api/undocumented');\n"
              + "const api_key='" + API_SECRET + "';\nconst public_client_id='PUBLIC_BROWSER_CLIENT';";
        }
        case "/app/config" -> {
          type = "application/json";
          body = "{\"api_key\":\"" + API_SECRET + "\",\"database_url\":\"postgresql://app:DB_PRIVATE_VALUE@db.internal/app\","
              + "\"diagnostic\":\"org.postgresql.util.PSQLException SQLSTATE[08006]\",\"revision\":" + revision.get() + "}";
        }
        case "/app/private" -> {
          type = "application/json"; code = account ? 200 : 403;
          body = account ? "{\"owner\":\"alice\",\"document\":101}" : "{\"error\":\"forbidden\"}";
        }
        case "/app/api/me" -> {
          type = "application/json"; code = account ? 200 : 401;
          body = account ? "{\"id\":\"alice\",\"role\":\"user\"}" : "{\"error\":\"login required\"}";
        }
        case "/app/api/documented", "/app/api/undocumented" -> {
          type = "application/json"; body = "{\"readOnly\":true,\"revision\":" + revision.get() + "}";
        }
        case "/app/form-root", "/app/spa-root" -> body = FORM_SHELL;
        case "/app/discovery-root" -> body = "<!doctype html><html><title>Discovery portal</title><script src='/app/discovery.js'></script></html>";
        case "/app/discovery.js" -> { type = "application/javascript"; body = "fetch('/app/api/script-found');"; }
        case "/app/guarded-root" -> body = "<html><script src='/app/guarded.js'></script></html>";
        case "/app/guarded.js" -> {
          type = "application/javascript";
          body = "this.http.get(this.otherServer + '/app/api/not-own-host'); this.http.post('/app/api/post-observed', {name:'input'}); "
              + "fetch('/app/api/safe-grounded'); fetch('http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/app/api/absolute-grounded');";
        }
        case "/app/api/script-found", "/app/api/robots-found", "/app/api/sitemap-found", "/app/api/safe-grounded", "/app/api/absolute-grounded" -> { type = "application/json"; body = "{\"observed\":true}"; }
        case "/app/identity-mismatch" -> { type = "application/json"; body = "{\"id\":\"another-user\"}"; }
        case "/app/expired" -> { code = 401; body = FORM_SHELL; }
        case "/app/login-redirect" -> { code = 302; body = "redirect"; exchange.getResponseHeaders().set("Location", "/app/login"); }
        case "/app/auth-redirect" -> { code = 302; body = "redirect"; exchange.getResponseHeaders().set("Location", "/app/auth"); }
        case "/app/login", "/app/auth" -> body = "<html><form action='/app/login'><input type='password'><button>Sign in</button></form></html>";
        case "/app/.env" -> {
          type = exposedEnv.get() ? "text/plain" : "text/html";
          body = exposedEnv.get() ? "APP_ENV=local\nDB_PASSWORD=SCOPE_ENV_LOCAL_VALUE_0123456789\n" : missing;
        }
        case "/app/robots.txt" -> {
          type = declaredDiscovery.get() ? "text/plain" : "text/html";
          body = declaredDiscovery.get() ? "User-agent: *\nDisallow: /app/api/robots-found\nSitemap: /app/sitemap.xml\n" : missing;
        }
        case "/app/sitemap.xml" -> {
          type = declaredDiscovery.get() ? "application/xml" : "text/html";
          body = declaredDiscovery.get() ? "<urlset><url><loc>http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/app/api/sitemap-found</loc></url></urlset>" : missing;
        }
        case "/app/redirect" -> {
          code = 302; body = "redirect"; exchange.getResponseHeaders().set("Location", "/outside/sensitive");
        }
        case "/app/loop" -> {
          code = 302; body = "loop"; exchange.getResponseHeaders().set("Location", "/app/loop");
        }
        case "/app/rate" -> {
          code = rateLimited.get() ? 429 : 200; body = "rate fixture";
          exchange.getResponseHeaders().set("Retry-After", "0");
        }
        case "/app/challenge" -> body = "<title>Just a moment...</title>cf-chl-fixture";
        case "/outside/sensitive" -> body = "THIS_MUST_NOT_BE_FETCHED";
        case "/app/logout", "/app/api/write-only" -> { code = 405; body = "DO_NOT_EXECUTE"; }
        default -> body = missing;
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", type);
      exchange.getResponseHeaders().set("Server", "nginx/1.25 fixture");
      exchange.getResponseHeaders().set("X-Powered-By", "Apache Tomcat fixture");
      if (exchange.getRequestMethod().equals("HEAD")) {
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
        exchange.sendResponseHeaders(code, -1);
      } else {
        exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes);
      }
      exchange.close();
    });
    server.start(); origin = "http://127.0.0.1:" + server.getAddress().getPort();
    var client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    Http http = proxy(Http.class, (p, m, args) -> {
      if (!m.getName().equals("sendRequest")) return def(m);
      HttpRequest request = (HttpRequest) args[0];
      if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new AssertionError("Inspection sent HTTP from EDT");
      if (!request.url().startsWith(origin + "/")) throw new AssertionError("Fixture attempted external HTTP");
      if (!List.of("GET", "HEAD").contains(request.method())) throw new AssertionError("Fixture attempted a write request");
      var outgoing = java.net.http.HttpRequest.newBuilder(URI.create(request.url()))
          .timeout(java.time.Duration.ofSeconds(5))
          .method(request.method(), java.net.http.HttpRequest.BodyPublishers.noBody());
      for (HttpHeader h : request.headers()) if (!List.of("host", "connection", "content-length", "upgrade", "http2-settings")
          .contains(h.name().toLowerCase(java.util.Locale.ROOT))) outgoing.header(h.name(), h.value());
      var received = client.send(outgoing.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
      var headers = new ArrayList<HttpHeader>();
      received.headers().map().forEach((name, values) -> values.forEach(value -> headers.add(header(name, value))));
      HttpResponse response = wireResponse(received.statusCode(), headers, received.body());
      registerTrafficPacket(request, response);
      return proxy(HttpRequestResponse.class, (pp, mm, aa) -> switch (mm.getName()) {
        case "request" -> request; case "response" -> response; default -> def(mm);
      });
    });
    MontoyaApi fallback = SimpleWorkflowTest.previewApi();
    api = proxy(MontoyaApi.class, (p, m, args) -> m.getName().equals("http") ? http : m.invoke(fallback, args));
  }

  static void installFactory() {
    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, args) -> switch (m.getName()) {
      case "byteArray" -> args.length == 1 && args[0] instanceof byte[] raw ? bytes(raw)
          : args.length == 1 ? bytes(args[0].toString().getBytes(StandardCharsets.UTF_8)) : bytes(new byte[0]);
      case "requestOptions" -> proxy(RequestOptions.class, (pp, mm, aa) -> pp);
      case "httpService" -> service(args[0].toString(), ((Number)args[1]).intValue(), (Boolean)args[2]);
      case "httpRequestFromUrl" -> wireRequest(args[0].toString(), "GET", List.of(), new byte[0]);
      case "httpRequest" -> {
        if (args.length == 2) yield parseRequest((HttpService)args[0], raw(args[1]));
        byte[] packet = raw(args[0]);
        var host = java.util.regex.Pattern.compile("(?im)^Host:\\s*([^\\r\\n]+)")
            .matcher(new String(packet, 0, bodyOffset(packet), StandardCharsets.UTF_8));
        URI target = URI.create("http://" + (host.find() ? host.group(1).strip() : "fixture.invalid"));
        yield parseRequest(service(target.getHost(), target.getPort() < 0 ? 80 : target.getPort(), false), packet);
      }
      case "httpResponse" -> parseResponse(raw(args[0]));
      case "httpRequestResponse" -> proxy(HttpRequestResponse.class, (pp, mm, aa) -> switch (mm.getName()) {
        case "request" -> args[0]; case "response" -> args[1]; default -> def(mm);
      });
      default -> def(m);
    });
  }

  static HttpService service(String host, int port, boolean secure) {
    return proxy(HttpService.class, (p, m, args) -> switch (m.getName()) {
      case "host" -> host; case "port" -> port; case "secure" -> secure; default -> def(m);
    });
  }
  static HttpRequest wireRequest(String url, String method, List<HttpHeader> headers, byte[] body) {
    URI uri = URI.create(url);
    var originalHeaders = new ArrayList<>(headers);
    if (originalHeaders.stream().noneMatch(h -> h.name().equalsIgnoreCase("Host"))) originalHeaders.add(0, header("Host", uri.getRawAuthority()));
    HttpService service = service(uri.getHost(), uri.getPort() < 0 ? uri.getScheme().equals("https") ? 443 : 80 : uri.getPort(), uri.getScheme().equals("https"));
    String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    byte[] raw = wire(method + " " + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()) + " HTTP/1.1\r\n"
        + String.join("\r\n", originalHeaders.stream().map(HttpHeader::toString).toList()), body);
    return proxy(HttpRequest.class, (p, m, args) -> switch (m.getName()) {
      case "url" -> url; case "method" -> method; case "httpService" -> service; case "headers" -> List.copyOf(originalHeaders);
      case "body" -> bytes(body); case "bodyToString" -> new String(body, StandardCharsets.UTF_8);
      case "toString" -> new String(raw, StandardCharsets.UTF_8); case "toByteArray" -> bytes(raw);
      case "isInScope" -> true; case "parameters" -> List.of();
      case "headerValue" -> originalHeaders.stream().filter(h -> h.name().equalsIgnoreCase(args[0].toString())).map(HttpHeader::value).findFirst().orElse(null);
      case "withMethod" -> wireRequest(url, args[0].toString(), originalHeaders, body);
      case "withBody" -> wireRequest(url, method, originalHeaders, raw(args[0]));
      case "withRemovedHeader" -> {
        String name = args[0] instanceof HttpHeader h ? h.name() : args[0].toString();
        yield wireRequest(url, method, originalHeaders.stream().filter(h -> !h.name().equalsIgnoreCase(name)).toList(), body);
      }
      case "withHeader" -> {
        var changed = new ArrayList<>(originalHeaders); changed.removeIf(h -> h.name().equalsIgnoreCase(args[0].toString()));
        changed.add(header(args[0].toString(), args[1].toString())); yield wireRequest(url, method, changed, body);
      }
      default -> def(m);
    });
  }
  static byte[] raw(Object value) {
    return value instanceof ByteArray bytes ? bytes.getBytes() : value.toString().getBytes(StandardCharsets.UTF_8);
  }
  static byte[] wire(String head, byte[] body) {
    byte[] prefix = (head + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    byte[] packet = java.util.Arrays.copyOf(prefix, prefix.length + body.length);
    System.arraycopy(body, 0, packet, prefix.length, body.length); return packet;
  }
  static int bodyOffset(byte[] packet) {
    for (int index = 0; index + 3 < packet.length; index++)
      if (packet[index] == '\r' && packet[index+1] == '\n' && packet[index+2] == '\r' && packet[index+3] == '\n') return index + 4;
    for (int index = 0; index + 1 < packet.length; index++)
      if (packet[index] == '\n' && packet[index+1] == '\n') return index + 2;
    return packet.length;
  }
  static HttpRequest parseRequest(HttpService service, String raw) {
    return parseRequest(service, raw.getBytes(StandardCharsets.UTF_8));
  }
  static HttpRequest parseRequest(HttpService service, byte[] packet) {
    int offset = bodyOffset(packet);
    String[] lines = new String(packet, 0, offset, StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n"), first = lines[0].split(" ", 3);
    String origin = (service.secure() ? "https" : "http") + "://" + service.host()
        + (service.port() == (service.secure() ? 443 : 80) ? "" : ":" + service.port());
    return wireRequest(URI.create(origin).resolve(first[1]).toString(), first[0], parseHeaders(lines), java.util.Arrays.copyOfRange(packet, offset, packet.length));
  }
  static List<HttpHeader> parseHeaders(String[] lines) {
    var headers = new ArrayList<HttpHeader>();
    for (int i = 1; i < lines.length; i++) { int colon = lines[i].indexOf(':'); if (colon > 0) headers.add(header(lines[i].substring(0, colon), lines[i].substring(colon + 1).strip())); }
    return headers;
  }
  static HttpResponse parseResponse(String raw) {
    return parseResponse(raw.getBytes(StandardCharsets.UTF_8));
  }
  static HttpResponse parseResponse(byte[] packet) {
    int offset = bodyOffset(packet);
    String[] lines = new String(packet, 0, offset, StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n");
    return wireResponse(Integer.parseInt(lines[0].split(" ")[1]), parseHeaders(lines), java.util.Arrays.copyOfRange(packet, offset, packet.length));
  }
  static HttpResponse wireResponse(int code, List<HttpHeader> headers, byte[] body) {
    byte[] raw = wire("HTTP/1.1 " + code + (headers.isEmpty() ? "" : "\r\n"
        + String.join("\r\n", headers.stream().map(HttpHeader::toString).toList())), body);
    return proxy(HttpResponse.class, (p, m, args) -> switch (m.getName()) {
      case "statusCode" -> (short)code; case "headers" -> headers; case "body" -> bytes(body); case "bodyToString" -> new String(body, StandardCharsets.UTF_8);
      case "toString" -> new String(raw, StandardCharsets.UTF_8); case "toByteArray" -> bytes(raw);
      case "headerValue" -> headers.stream().filter(h -> h.name().equalsIgnoreCase(args[0].toString())).map(HttpHeader::value).findFirst().orElse(null);
      default -> def(m);
    });
  }

  ScopeInspection.Options options(boolean accounts) {
    var profiles = new ArrayList<ScopeInspection.Profile>();
    profiles.add(new ScopeInspection.Profile("anonymous", "비로그인", Map.of(), null));
    if (accounts) profiles.add(new ScopeInspection.Profile("alice", "Alice", Map.of("cookie", SESSION),
        new CookieSessionProbe.Config(origin + "/app/api/me", "$.id", "alice")));
    return new ScopeInspection.Options(origin + "/app/", 150, 2, 0, 3000, 1024 * 1024, 3, false, accounts, profiles);
  }

  @Override public void close() { server.stop(0); serverWorkers.shutdownNow(); }
}
